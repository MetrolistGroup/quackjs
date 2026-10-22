package meow.nyx.quackjs.verification

import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram
import meow.nyx.quackjs.UnsupportedPlayerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal val lane32Lengths = listOf(16, 19, 32, 64, 86, 128, 256)

/** Input generation never reads transform outputs; java.util.Random specifies a reproducible sequence. */
internal fun lane32Challenges(): List<Challenge> {
    val random = Random(0x32acc3e7L)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    return listOf("signature", "n").flatMap { kind ->
        lane32Lengths.flatMap { length ->
            List(2) { variant ->
                val input = CharArray(length) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
                Challenge(kind, "lane32-$length-$variant", input, "")
            }
        }
    }
}

internal fun lane32Malformed(): List<Challenge> = listOf("signature", "n").flatMap { kind ->
    listOf(
        "empty" to "",
        "single-character" to "a",
        "whitespace" to " \t\n\r".repeat(5),
        "url-punctuation" to "%&=+?#/!;:".repeat(2),
        "controls" to "\u0000\u0001\u007f".repeat(7),
        "unicode-bmp" to "\u00e9\u2603\u4e2d".repeat(7),
    ).map { (name, input) -> Challenge(kind, "malformed-$name", input, "") }
}

internal fun lane32Compile(player: RealPlayer): PlayerProgram = PlayerProgram.compile(player.source,
    PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp))

internal fun lane32Solve(program: PlayerProgram, cases: List<Challenge>): String = resultsJson(cases.map {
    if (it.kind == "signature") program.decipherSignature(it.input) else program.transformN(it.input)
})

/** Expected strings come only from the full original player in QuickJS, never from PlayerProgram. */
internal fun lane32Verify(player: RealPlayer, program: PlayerProgram): List<Challenge> {
    val challenges = lane32Challenges()
    check(challenges == lane32Challenges()) { "Challenge generation must be deterministic" }
    check(challenges.map { it.input }.toSet().size == challenges.size)
    check(challenges.none { c -> player.cases.any { it.input == c.input } }) { "Challenge overlaps stored goldens" }
    val verified = QuickJsPlayer.open(player).use { js ->
        check(js.solve(player.cases) == expectedJson(player.cases)) { "${player.id}: independent Node/QuickJS goldens failed" }
        fun expected(cases: List<Challenge>): List<Challenge> {
            val outputs = Json.parseToJsonElement(js.solve(cases)).jsonArray
            check(outputs.size == cases.size)
            return cases.zip(outputs) { c, output ->
                check(output.jsonPrimitive.isString) { "${player.id}/${c.name}: oracle did not return a string" }
                c.copy(output = output.jsonPrimitive.content)
            }
        }
        val valid = expected(challenges)
        check(valid.none { it.output.startsWith("enhanced_except_") }) { "${player.id}: oracle error sentinel" }
        check(js.solve(valid.reversed()) == expectedJson(valid.reversed())) { "${player.id}: oracle retained invocation state" }
        for (c in lane32Malformed()) {
            val expected = js.solve(listOf(c), requireStrings = c.kind == "signature")
            val actual = try {
                lane32Solve(program, listOf(c))
            } catch (failure: UnsupportedPlayerException) {
                "UnsupportedPlayerException: ${failure.message}"
            }
            // The n oracle takes a URL-encoded route, so malformed comparisons are diagnostic only.
            if (c.kind == "signature") assertEquals(expected, actual, "${player.id}/${c.name}")
            println("malformed player=${player.id} kind=${c.kind} case=${c.name} match=${actual == expected} quickjs=$expected kotlin=$actual")
        }
        valid
    }
    check(lane32Solve(program, player.cases) == expectedJson(player.cases)) { "${player.id}: stored parity failed" }
    for (cases in listOf(verified, verified.reversed(), verified)) {
        for (c in cases) assertEquals(c.output, lane32SolveOne(program, c), "${player.id}/${c.kind}/${c.name}")
    }
    // Resource policy is a Kotlin API guard, not a full-player JavaScript behavior to copy.
    val oversized = "x".repeat(16_385)
    assertFailsWith<UnsupportedPlayerException> { program.decipherSignature(oversized) }
    assertFailsWith<UnsupportedPlayerException> { program.transformN(oversized) }
    lane32Concurrent(player, program, verified)
    return verified
}

private fun lane32SolveOne(program: PlayerProgram, c: Challenge): String =
    if (c.kind == "signature") program.decipherSignature(c.input) else program.transformN(c.input)

private fun lane32Concurrent(player: RealPlayer, program: PlayerProgram, cases: List<Challenge>) {
    val workers = 4
    val start = CyclicBarrier(workers)
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val tasks = List(workers) { worker ->
            Callable {
                start.await(10, TimeUnit.SECONDS)
                repeat(3) { pass ->
                    for (index in cases.indices) {
                        val c = cases[(index + worker + pass) % cases.size]
                        assertEquals(c.output, lane32SolveOne(program, c), "${player.id}/concurrent/${c.kind}/${c.name}")
                    }
                }
            }
        }
        pool.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
    } finally {
        pool.shutdownNow()
        check(pool.awaitTermination(30, TimeUnit.SECONDS)) { "Lane32 workers did not terminate" }
    }
}

class Lane32AcceptanceTest {
    @Test fun unseenChallengesMatchQuickJsWithSharedProgramsAndMalformedInputsAreReported() {
        for (player in PlayerFixtures.load()) lane32Verify(player, lane32Compile(player))
    }
}
