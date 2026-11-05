package meow.nyx.quackjs.verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

/** Compare complete plans and fallbacks with the unchanged interpreter on hash-checked sources. */
fun main() {
    val cache = Path.of(System.getProperty("quackjs.linear.cache"))
    val report = Path.of(System.getProperty("quackjs.linear.report"))
    val players = Json.parseToJsonElement(Files.readString(Path.of(System.getProperty("quackjs.linear.manifest", "fixtures/faraday-200/manifest.json"))))
        .jsonObject.getValue("players").jsonArray
    val limit = Integer.getInteger("quackjs.linear.players", players.size)
    check(limit in 1..players.size)
    val statuses = mutableListOf<kotlinx.serialization.json.JsonObject>()
    var mismatches = 0
    var comparisons = 0
    fun save() {
        Files.createDirectories(report.toAbsolutePath().parent)
        Files.writeString(report, Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("completed", statuses.size)
            put("comparisons", comparisons)
            put("mismatches", mismatches)
            put("players", buildJsonArray { statuses.forEach { add(it) } })
        }))
    }
    for (player in players.take(limit)) {
        val p = player.jsonObject
        val id = p.text("id")
        val sourcePath = cache.resolve("sources/$id.js")
        check(Files.size(sourcePath) in 100_000..8L * 1024 * 1024) { "$id: source byte bounds" }
        val source = Files.readString(sourcePath)
        check(sha256(source) == p.text("sha256")) { "$id: source SHA-256 mismatch" }
        val program = PlayerProgram.compile(source, PlayerHints(p.text("signatureExpression"), p.text("nClass"), p.getValue("signatureTimestamp").jsonPrimitive.int))
        val random = Random(4100)
        val lengths = mutableListOf<Int>()
        val causes = mutableMapOf<String, Int>()
        var hits = 0
        var guards = 0
        var errors = 0
        var maxInstructions = 0
        var maxSteps = 0
        var maxAllocation = 0
        for (length in 0..200) {
            val result = program.linearNResult(length)
            val plan = result.plan
            if (plan != null) {
                lengths += length
                maxInstructions = maxOf(maxInstructions, plan.instructions)
                maxSteps = maxOf(maxSteps, plan.steps)
                maxAllocation = maxOf(maxAllocation, plan.allocation)
            } else causes[result.reason] = (causes[result.reason] ?: 0) + 1
            repeat(12) { sample ->
                val alphabet = when (sample) {
                    9 -> "!?/=+ \u0000"
                    10 -> "\u2603\u00e9\u4e2d\ud83d\ude00"
                    11 -> "Aa0_-!?\u2603\ud83d"
                    else -> "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
                }
                val input = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
                val expected = runCatching { program.transformNInterpreted(input) }
                val actual = runCatching { program.transformN(input) }
                val direct = plan?.execute(input)
                if (direct != null) hits++ else if (plan != null) guards++
                val same = if (expected.isSuccess) actual.isSuccess && expected.getOrNull() == actual.getOrNull()
                    else expected.exceptionOrNull()?.javaClass == actual.exceptionOrNull()?.javaClass && expected.exceptionOrNull()?.message == actual.exceptionOrNull()?.message
                if (!same || direct != null && direct != expected.getOrNull()) {
                    mismatches++
                    if (mismatches <= 10) println("MISMATCH $id length=$length sample=$sample expected=$expected actual=$actual")
                }
                if (expected.isFailure) errors++
                comparisons++
            }
        }
        statuses += buildJsonObject {
            put("id", id)
            put("planLengths", buildJsonArray { lengths.forEach { add(it) } })
            put("fallbackCauses", buildJsonObject { causes.forEach { (cause, count) -> put(cause, count) } })
            put("planHits", hits)
            put("guardFallbacks", guards)
            put("interpreterExceptions", errors)
            put("maxInstructions", maxInstructions)
            put("maxSteps", maxSteps)
            put("maxAllocation", maxAllocation)
        }
        save()
        println("$id plans=${lengths.size}/201 hits=$hits guards=$guards causes=$causes mismatches=$mismatches")
    }
    check(mismatches == 0) { "$mismatches differential mismatches" }
    println("Linear n: ${statuses.size} players, $comparisons comparisons, $mismatches mismatches; $report")
}
