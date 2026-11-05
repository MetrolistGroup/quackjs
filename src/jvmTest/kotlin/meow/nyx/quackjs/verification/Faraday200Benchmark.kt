package meow.nyx.quackjs.verification

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

private val corpus = Path.of(System.getProperty("quackjs.faraday.corpus", "fixtures/faraday-200"))
private val cache = Path.of(System.getProperty("quackjs.faraday.cache", "build/faraday-200"))
private val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean

private class BenchPlayer(val id: String, val sha256: String, val hints: PlayerHints, val cases: List<Challenge>) {
    val signatures = cases.filter { it.kind == "signature" }
    val ns = cases.filter { it.kind == "n" }
    val firstSignature = signatures.single { it.name == "seeded-86" }
    val firstN = ns.single { it.name == "faraday-probe" }
    fun source(): String = Files.readString(cache.resolve("sources/$id.js"))
    val cold = mutableListOf<Double>()
    val linearBuildMs = mutableListOf<Double>()
    val coldBytes = mutableListOf<Long>()
    val warmSignature = mutableListOf<Double>()
    val warmSignatureBytes = mutableListOf<Long>()
    val warmN = mutableListOf<Double>()
    val warmNBytes = mutableListOf<Long>()
    var retainedBytes = -1L
    var failure: String? = null
}

private fun loadPlayers(): List<BenchPlayer> {
    val manifestText = Files.readString(corpus.resolve("manifest.json"))
    val manifest = Json.parseToJsonElement(manifestText).jsonObject
    val goldens = Json.parseToJsonElement(Files.readString(corpus.resolve("goldens.json"))).jsonObject
    check(goldens.text("manifestSha256") == sha256(manifestText)) { "golden manifest SHA-256 mismatch" }
    val inputs = goldens.getValue("inputs").jsonObject
    val goldenPlayers = goldens.getValue("players").jsonArray
    return manifest.getValue("players").jsonArray.mapIndexed { index, element ->
        val player = element.jsonObject
        val golden = goldenPlayers[index].jsonObject
        check(golden.text("id") == player.text("id"))
        val cases = listOf("signature", "n").flatMap { kind ->
            inputs.getValue(kind).jsonArray.zip(golden.getValue(kind).jsonArray) { input, output ->
                Challenge(kind, input.jsonObject.text("name"), input.jsonObject.text("input"), output.jsonPrimitive.content)
            }
        }
        BenchPlayer(player.text("id"), player.text("sha256"),
            PlayerHints(player.text("signatureExpression"), player.text("nClass"), player.getValue("signatureTimestamp").jsonPrimitive.int), cases)
    }
}

private fun compileAndFirstPair(player: BenchPlayer, source: String, planCosts: MutableList<Double>? = null): Pair<PlayerProgram, List<String>> {
    val program = PlayerProgram.compile(source, player.hints)
    val signature = program.decipherSignature(player.firstSignature.input)
    if (planCosts != null) {
        val start = System.nanoTime()
        program.linearNResult(player.firstN.input.length)
        planCosts += (System.nanoTime() - start) / 1e6
    }
    return program to listOf(signature, program.transformN(player.firstN.input))
}

private fun checkFirstPair(player: BenchPlayer, results: List<String>) {
    check(results[0] == player.firstSignature.output && results[1] == player.firstN.output) { "first pair golden mismatch" }
}

private fun sampleCold(player: BenchPlayer): PlayerProgram {
    val source = player.source()
    val bytes = threads.currentThreadAllocatedBytes
    val start = System.nanoTime()
    val (program, results) = compileAndFirstPair(player, source, player.linearBuildMs)
    val elapsed = System.nanoTime() - start
    player.coldBytes += threads.currentThreadAllocatedBytes - bytes
    player.cold += elapsed / 1e6
    checkFirstPair(player, results)
    return program
}

/** Times repeated calls on one program; compares complete outputs after the timer stops. */
private fun sampleWarm(cases: List<Challenge>, repeats: Int, times: MutableList<Double>, allocated: MutableList<Long>, call: (String) -> String) {
    val results = arrayOfNulls<String>(cases.size)
    val bytes = threads.currentThreadAllocatedBytes
    val start = System.nanoTime()
    repeat(repeats) { for (i in cases.indices) results[i] = call(cases[i].input) }
    val elapsed = System.nanoTime() - start
    val calls = repeats * cases.size
    allocated += (threads.currentThreadAllocatedBytes - bytes) / calls
    times += elapsed.toDouble() / calls
    for (i in cases.indices) check(results[i] == cases[i].output) { "${cases[i].kind}/${cases[i].name} golden mismatch" }
}

private fun measureRound(player: BenchPlayer, reverseKinds: Boolean) {
    val program = sampleCold(player)
    // Discarded pass: settles this program's lazy state before warm timing.
    for (c in player.cases) if (c.kind == "signature") program.decipherSignature(c.input) else program.transformN(c.input)
    val signature = { sampleWarm(player.signatures, WARM_REPEATS, player.warmSignature, player.warmSignatureBytes, program::decipherSignature) }
    val n = { sampleWarm(player.ns, WARM_REPEATS, player.warmN, player.warmNBytes, program::transformN) }
    if (reverseKinds) { n(); signature() } else { signature(); n() }
}

private fun usedHeap(): Long {
    repeat(2) { System.gc() }
    return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}

private fun measureRetained(player: BenchPlayer) {
    val before = usedHeap()
    val program = compileAndFirstPair(player, player.source()).first
    player.retainedBytes = usedHeap() - before
    Reference.reachabilityFence(program)
}

/** Fresh-JVM child: the only sample that charges class loading and an unwarmed JIT. */
private fun firstCallChild(id: String) {
    val player = loadPlayers().single { it.id == id }
    val source = player.source()
    val start = System.nanoTime()
    val results = compileAndFirstPair(player, source).second
    val elapsed = System.nanoTime() - start
    checkFirstPair(player, results)
    println("FIRST_CALL_MS ${elapsed / 1e6}")
}

private fun firstCallSamples(players: List<BenchPlayer>): Map<String, List<Double>> {
    val runtime = ManagementFactory.getRuntimeMXBean()
    val java = ProcessHandle.current().info().command().orElseThrow()
    val spaced = players.filterIndexed { i, _ -> i % (players.size / FIRST_CALL_PLAYERS).coerceAtLeast(1) == 0 }
    return spaced.associate { player ->
        player.id to List(FIRST_CALL_SAMPLES) {
            val process = ProcessBuilder(listOf(java) + runtime.inputArguments + listOf(
                "-cp", System.getProperty("java.class.path"), "meow.nyx.quackjs.verification.Faraday200BenchmarkKt", "--first-call", player.id,
            )).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${player.id}: first-call child failed: ${output.take(1000)}" }
            output.lineSequence().first { it.startsWith("FIRST_CALL_MS ") }.substringAfter(' ').toDouble()
        }
    }
}

private const val WARM_REPEATS = 8
private const val FIRST_CALL_PLAYERS = 24
private const val FIRST_CALL_SAMPLES = 2

/** Opt-in, offline: cold, warm, allocation and retained heap for each pinned player. A wrong output fails that player. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--first-call") return firstCallChild(args[1])
    val rounds = Integer.getInteger("quackjs.bench.rounds", 5)
    val limit = Integer.getInteger("quackjs.bench.players", 200)
    val report = Path.of(System.getProperty("quackjs.bench.report", cache.resolve("bench-report.json").toString()))
    val all = loadPlayers()
    check(all.size == 200 && limit in 1..200)
    val players = all.filterIndexed { i, _ -> i % (200 / limit) == 0 }.take(limit)
    for (player in players) check(sha256(player.source()) == player.sha256) { "${player.id}: source SHA-256 mismatch" }
    println("java=${System.getProperty("java.runtime.version")} jvmArgs=${ManagementFactory.getRuntimeMXBean().inputArguments} players=${players.size} rounds=$rounds")

    val jitWarmup = players.filterIndexed { i, _ -> i % (players.size / 12).coerceAtLeast(1) == 0 }
    repeat(3) { for (player in jitWarmup) runCatching { compileAndFirstPair(player, player.source()) } }

    fun guarded(player: BenchPlayer, action: () -> Unit) {
        if (player.failure != null) return
        try { action() } catch (failure: Exception) {
            player.failure = "${failure.javaClass.simpleName}: ${failure.message}".take(500)
            println("FAIL ${player.id}: ${player.failure}")
        }
    }
    repeat(rounds) { round ->
        val ordered = if (round % 2 == 0) players else players.reversed()
        for (player in ordered) guarded(player) { measureRound(player, round % 2 == 1) }
        println("round ${round + 1}/$rounds done")
    }
    for (player in players) guarded(player) { measureRetained(player) }
    val firstCall = firstCallSamples(players.filter { it.failure == null })

    val document = buildJsonObject {
        put("schemaVersion", 1)
        put("label", System.getProperty("quackjs.bench.label", "unlabelled"))
        put("java", System.getProperty("java.runtime.version"))
        put("jvmArgs", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
        put("rounds", rounds)
        put("failed", players.count { it.failure != null })
        put("players", buildJsonArray {
            for (p in players) add(buildPlayerJson(p, firstCall[p.id]))
        })
    }
    Files.createDirectories(report.toAbsolutePath().parent)
    Files.writeString(report, Json.encodeToString(JsonObject.serializer(), document) + "\n")
    val failed = players.count { it.failure != null }
    println("${players.size - failed}/${players.size} players passed; report: ${report.toAbsolutePath()}")
    check(failed == 0) { "$failed players failed" }
}

private fun buildPlayerJson(p: BenchPlayer, firstCall: List<Double>?): JsonObject = buildJsonObject {
    put("id", p.id)
    p.failure?.let { put("failure", it) }
    putJsonArray("coldMs") { p.cold.forEach { add(it) } }
    putJsonArray("linearBuildMs") { p.linearBuildMs.forEach { add(it) } }
    putJsonArray("coldBytes") { p.coldBytes.forEach { add(it) } }
    putJsonArray("warmSignatureNs") { p.warmSignature.forEach { add(it) } }
    putJsonArray("warmSignatureBytes") { p.warmSignatureBytes.forEach { add(it) } }
    putJsonArray("warmNNs") { p.warmN.forEach { add(it) } }
    putJsonArray("warmNBytes") { p.warmNBytes.forEach { add(it) } }
    put("retainedBytes", p.retainedBytes)
    firstCall?.let { samples -> putJsonArray("firstCallMs") { samples.forEach { add(it) } } }
}
