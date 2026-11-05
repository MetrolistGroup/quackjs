package meow.nyx.quackjs.verification

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerChallenge
import meow.nyx.quackjs.PlayerProgram
import meow.nyx.quackjs.PlayerTransformResult

private const val FARADAY_SNAPSHOT = "882624d59ccd921aa4cbf528e046ae61e137be47"
private val faradayJson = Json { prettyPrint = true }

/** Opt-in only: stream one hash-checked source at a time; report all players before failing. */
fun main() {
    val corpus = Path.of(System.getProperty("quackjs.faraday.corpus", "fixtures/faraday-200"))
    val cache = Path.of(System.getProperty("quackjs.faraday.cache", "build/faraday-200"))
    val oracleOnly = java.lang.Boolean.getBoolean("quackjs.faraday.oracle")
    val reportName = if (oracleOnly) "quickjs-report.json" else "jvm-report.json"
    val report = Path.of(System.getProperty("quackjs.faraday.report", cache.resolve(reportName).toString()))
    val manifestText = Files.readString(corpus.resolve("manifest.json"))
    val manifest = Json.parseToJsonElement(manifestText).jsonObject
    check(manifest.getValue("schemaVersion").jsonPrimitive.int == 1)
    check(manifest.getValue("snapshot").jsonObject.text("commit") == FARADAY_SNAPSHOT)
    val players = manifest.getValue("players").jsonArray.map { it.jsonObject }
    check(players.size == 200 && players.map { it.text("id") }.toSet().size == 200)
    check(players.first().text("id") == "3f99f1e0" && players.last().text("id") == "9c9b2502")
    players.forEach { check(Regex("[a-f0-9]{8}").matches(it.text("id"))) }

    // Missing/invalid oracle data is an explicit failure for every player, never a skipped pass.
    val globals = runCatching { Files.readString(corpus.parent.resolve("oracle-globals.js")) }
    val goldenResult = runCatching {
        val goldens = Json.parseToJsonElement(Files.readString(corpus.resolve("goldens.json"))).jsonObject
        check(goldens.getValue("schemaVersion").jsonPrimitive.int == 1)
        check(goldens.text("manifestSha256") == sha256(manifestText)) { "golden manifest SHA-256 mismatch" }
        check(goldens.getValue("oracle").jsonObject.text("globalsSha256") == sha256(globals.getOrThrow())) { "oracle globals SHA-256 mismatch" }
        check(goldens.getValue("players").jsonArray.map { it.jsonObject.text("id") } == players.map { it.text("id") })
        goldens
    }
    val statuses = mutableListOf<JsonObject>()
    val concurrentConfigs = mapOf("hinted" to mutableSetOf<String>(), "hintFree" to mutableSetOf<String>())
    val started = Instant.now().toString()
    Files.createDirectories(report.toAbsolutePath().parent)
    fun save() {
        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("snapshot", FARADAY_SNAPSHOT)
            put("manifestSha256", sha256(manifestText))
            put("startedAt", started)
            put("updatedAt", Instant.now().toString())
            put("java", System.getProperty("java.runtime.version"))
            put("cache", cache.toAbsolutePath().toString())
            put("verification", if (oracleOnly) "quickjs" else "kotlin")
            if (oracleOnly) {
                put("engine", "quickjs-kt-jvm:1.0.15")
                put("oraclePolicy", "2 fresh full-source contexts; first: compiled batch twice, reversed; second: forward")
                put("limits", "192 MiB native heap, 2 MiB JS stack, 8 MiB thread, 30s per evaluation")
            } else {
                put("concurrencyPolicy", "first 8 successful distinct primary configs per mode; 4 workers, all 31 cases, 2 passes; then 62-entry duplicate batch per worker")
                put("batchPolicy", "two 62-entry forward/reverse batches per mode; per-entry comparisons against unchanged Node goldens")
            }
            put("completed", statuses.size)
            put("passed", statuses.count { it.text("status") == "pass" })
            put("failed", statuses.count { it.text("status") != "pass" })
            put("statuses", JsonArray(statuses))
        }
        Files.writeString(report, faradayJson.encodeToString(JsonObject.serializer(), document) + "\n")
    }
    for ((index, player) in players.withIndex()) {
        val id = player.text("id")
        val sourceResult = runCatching {
            val path = cache.resolve("sources/$id.js")
            check(Files.size(path) in 100_000..8L * 1024 * 1024) { "source byte bounds" }
            Files.readString(path).also { check(sha256(it) == player.text("sha256")) { "source SHA-256 mismatch" } }
        }
        val casesResult = runCatching {
            val goldens = goldenResult.getOrThrow()
            val golden = goldens.getValue("players").jsonArray[index].jsonObject
            check(golden.text("sha256") == player.text("sha256")) { "golden source SHA-256 mismatch" }
            val inputs = goldens.getValue("inputs").jsonObject
            listOf("signature", "n").flatMap { kind ->
                val challenges = inputs.getValue(kind).jsonArray
                val outputs = golden.getValue(kind).jsonArray
                check(challenges.size == if (kind == "signature") 16 else 15) { "wrong vector count" }
                check(outputs.size == challenges.size) { "missing golden outputs" }
                challenges.zip(outputs) { input, output ->
                    val c = input.jsonObject
                    check(output.jsonPrimitive.isString) { "non-string golden output" }
                    Challenge(kind, c.text("name"), c.text("input"), output.jsonPrimitive.content).also {
                        if (kind == "n") check(it.output != it.input && Regex("[A-Za-z0-9_-]{5,}").matches(it.output) &&
                            !it.output.startsWith("enhanced_except_")) { "invalid n golden" }
                    }
                }
            }.also { cases ->
                check(cases.map { it.kind to it.name }.toSet().size == 31) { "duplicate vectors" }
                check(cases.any { it.kind == "signature" && it.input.length == 86 })
                check(cases.any { it.kind == "n" && it.input == "KdrqFlzJXl9EcCwlmEy" })
            }
        }
        val modes = (if (oracleOnly) listOf("quickJs") else listOf("hinted", "hintFree")).associateWith { mode ->
            when {
                sourceResult.isFailure -> failedMode("source", sourceResult.exceptionOrNull()!!)
                casesResult.isFailure -> failedMode("oracle", casesResult.exceptionOrNull()!!)
                oracleOnly -> verifyFaradayOracle(RealPlayer(id, sourceResult.getOrThrow(), globals.getOrThrow(),
                    player.text("signatureExpression"), player.text("nClass"),
                    player.getValue("signatureTimestamp").jsonPrimitive.int, casesResult.getOrThrow()))
                else -> {
                    val configs = concurrentConfigs.getValue(mode)
                    val primary = player.text("configPrimaryHash")
                    val concurrent = configs.size < 8 && primary !in configs
                    verifyFaradayMode(sourceResult.getOrThrow(), player, casesResult.getOrThrow(), mode, concurrent).also {
                        if (it["concurrency"]?.jsonPrimitive?.content == "pass") configs += primary
                    }
                }
            }
        }
        val passed = modes.values.all { it.text("status") == "pass" }
        statuses += buildJsonObject {
            put("id", id)
            put("status", if (passed) "pass" else "fail")
            put("source", if (sourceResult.isSuccess) "pass" else "fail")
            put("oracle", if (casesResult.isSuccess) "pass" else "fail")
            modes.forEach { (name, result) -> put(name, result) }
        }
        save()
        println("${index + 1}/200 $id: " + modes.entries.joinToString(" ") { "${it.key}=${it.value.text("status")}" })
    }
    val failed = statuses.count { it.text("status") != "pass" }
    println("Faraday: ${200 - failed}/200 passed ${if (oracleOnly) "QuickJS" else "BOTH modes"}; exhaustive report: ${report.toAbsolutePath()}")
    check(failed == 0) { "$failed/200 players failed; see $report" }
}

private fun failedMode(phase: String, failure: Throwable, category: String = phase): JsonObject = buildJsonObject {
    put("status", "fail")
    put("phase", phase)
    put("category", category)
    put("reason", "${failure.javaClass.simpleName}: ${failure.message}".take(1000))
}

private fun verifyFaradayMode(source: String, player: JsonObject, cases: List<Challenge>, mode: String, concurrent: Boolean): JsonObject {
    val program = try {
        PlayerProgram.compile(source, if (mode == "hinted") PlayerHints(player.text("signatureExpression"), player.text("nClass"),
            player.getValue("signatureTimestamp").jsonPrimitive.int) else null)
    } catch (failure: Exception) {
        val category = if (mode == "hintFree" && failure.message?.contains("Missing or ambiguous signature/n landmarks") == true)
            "hint-free-landmark-ambiguity" else "compile"
        return failedMode("compile", failure, category)
    }
    val errors = mutableListOf<JsonObject>()
    if (mode == "hinted" && program.signatureTimestamp != player.getValue("signatureTimestamp").jsonPrimitive.int) {
        errors += failedMode("timestamp", IllegalStateException("signatureTimestamp mismatch"))
    }
    var checked = 0
    for ((pass, ordered) in listOf(cases, cases.reversed(), cases).withIndex()) {
        for (c in ordered) {
            try {
                val actual = solveFaraday(program, c)
                check(actual == c.output) { "expected=${c.output} actual=$actual" }
            } catch (failure: Exception) {
                errors += buildJsonObject {
                    put("phase", "parity")
                    put("kind", c.kind)
                    put("case", c.name)
                    put("pass", pass)
                    put("reason", "${failure.javaClass.simpleName}: ${failure.message}".take(1000))
                }
            }
            checked++
        }
    }
    var batchChecked = 0
    repeat(2) { pass ->
        val ordered = if (pass == 0) cases + cases.reversed() else cases.reversed() + cases
        try {
            checkBatch(program, ordered)
            batchChecked += ordered.size
        } catch (failure: Exception) {
            errors += failedMode("batch-parity", failure)
        }
    }
    var concurrency = "not-run-parity-failure"
    if (errors.isEmpty()) {
        concurrency = if (concurrent) {
            try { concurrentFaraday(program, cases); "pass" }
            catch (failure: Exception) { errors += failedMode("concurrency", failure); "fail" }
        } else "not-selected"
    }
    return buildJsonObject {
        put("status", if (errors.isEmpty()) "pass" else "fail")
        put("phase", if (errors.isEmpty()) "complete" else "parity")
        put("compile", "pass")
        put("checked", checked)
        put("batchChecked", batchChecked)
        put("concurrency", concurrency)
        put("errors", JsonArray(errors))
    }
}

/** No Kotlin transforms: all expectations are the independent, read-only Node goldens. */
internal fun verifyFaradayOracle(player: RealPlayer): JsonObject {
    val errors = mutableListOf<JsonObject>()
    var contexts = 0
    var checked = 0
    repeat(2) { context ->
        var phase = "initialization"
        try {
            QuickJsPlayer.open(player).use { js ->
                contexts++
                fun compare(cases: List<Challenge>, actual: String) {
                    checked += cases.size
                    check(actual == expectedJson(cases)) { "${player.id}: $phase golden mismatch; actual=${actual.take(1000)}" }
                }
                if (context == 0) {
                    phase = "compiled-repeat"
                    val batch = js.prepareBatch(player.cases)
                    repeat(2) { compare(player.cases, js.runBatch(batch)) }
                    phase = "reversed"
                    compare(player.cases.reversed(), js.solve(player.cases.reversed()))
                } else {
                    phase = "fresh-context"
                    compare(player.cases, js.solve(player.cases))
                }
                phase = "disposal"
            }
        } catch (failure: Exception) {
            errors += buildJsonObject {
                put("context", context)
                put("phase", phase)
                put("reason", "${failure.javaClass.simpleName}: ${failure.message}".take(2000))
            }
        }
    }
    return buildJsonObject {
        put("status", if (errors.isEmpty() && contexts == 2 && checked == player.cases.size * 4) "pass" else "fail")
        put("contexts", contexts)
        put("checked", checked)
        put("errors", JsonArray(errors))
    }
}

private fun solveFaraday(program: PlayerProgram, c: Challenge): String =
    if (c.kind == "signature") program.decipherSignature(c.input) else program.transformN(c.input)

private fun checkBatch(program: PlayerProgram, cases: List<Challenge>) {
    val challenges = cases.map {
        if (it.kind == "signature") PlayerChallenge.Signature(it.input) else PlayerChallenge.N(it.input)
    }
    val actual = program.transformBatch(challenges)
    check(actual.size == cases.size) { "batch result count mismatch" }
    for (i in cases.indices) {
        check(actual[i] == PlayerTransformResult.Success(cases[i].output)) {
            "batch ${cases[i].kind}/${cases[i].name} mismatch: ${actual[i]}"
        }
    }
}

private fun concurrentFaraday(program: PlayerProgram, cases: List<Challenge>) {
    val pool = Executors.newFixedThreadPool(4)
    val start = CyclicBarrier(4)
    try {
        val tasks = List(4) { worker -> Callable {
            start.await(10, TimeUnit.SECONDS)
            repeat(2) { pass ->
                for (i in cases.indices) {
                    val c = cases[(i + worker + pass) % cases.size]
                    check(solveFaraday(program, c) == c.output) { "concurrent ${c.kind}/${c.name} mismatch" }
                }
            }
            checkBatch(program, cases + cases.reversed())
        } }
        pool.invokeAll(tasks, 60, TimeUnit.SECONDS).forEach { it.get() }
    } finally {
        pool.shutdownNow()
        check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "concurrent workers did not terminate" }
    }
}
