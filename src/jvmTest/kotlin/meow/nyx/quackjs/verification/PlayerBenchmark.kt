package meow.nyx.quackjs.verification

import java.lang.management.ManagementFactory
import java.util.Locale
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

private const val COLD_WARMUPS = 3
private const val COLD_SAMPLES = 11
private const val WARM_WARMUPS = 20
private const val WARM_SAMPLES = 31
private const val BATCH_REPEATS = 16

/** Opt-in, one JVM, no network or disk I/O in measured regions. Fail closed on any parity mismatch. */
fun main() {
    val players = PlayerFixtures.load()
    println("java=${System.getProperty("java.runtime.version")} vendor=${System.getProperty("java.vendor")}")
    println("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} arch=${System.getProperty("os.arch")}")
    println("cpus=${Runtime.getRuntime().availableProcessors()} maxHeap=${Runtime.getRuntime().maxMemory()} jvmArgs=${ManagementFactory.getRuntimeMXBean().inputArguments}")
    println("quickjs-kt-jvm=1.0.15 nativeLimit=192MiB stack=2MiB dedicatedThreadStack=8MiB timeout=30s")
    println("cold: warmups=$COLD_WARMUPS samples=$COLD_SAMPLES; warm: warmups=$WARM_WARMUPS samples=$WARM_SAMPLES batchRepeats=$BATCH_REPEATS")

    // Validate the entire corpus with both implementations before printing any measurements.
    for (player in players) {
        val program = compile(player)
        check(solve(program, player.cases) == expectedJson(player.cases)) { "${player.id}: Kotlin parity failed" }
        QuickJsPlayer.open(player).use { js ->
            check(js.runBatch(js.prepareBatch(player.cases)) == expectedJson(player.cases)) { "${player.id}: QuickJS parity failed" }
        }
    }
    println("All full-player goldens passed. Times below are medians in ms, not process-startup times.")
    for (player in players) {
        val first = player.firstPair
        val expectedFirst = expectedJson(first)
        val coldKotlin = mutableListOf<Long>()
        val coldJs = mutableListOf<Long>()
        repeat(COLD_WARMUPS + COLD_SAMPLES) { sample ->
            fun kotlinSample() = checkedTime(expectedFirst) { solve(compile(player), first) }
            fun jsSample(): Long {
                var js: QuickJsPlayer? = null
                return try {
                    checkedTime(expectedFirst) { QuickJsPlayer.open(player).also { js = it }.solve(first) }
                } finally {
                    // Disposal is excluded for both implementations.
                    js?.close()
                }
            }
            val times = if (sample % 2 == 0) {
                val k = kotlinSample(); val q = jsSample(); k to q
            } else {
                val q = jsSample(); val k = kotlinSample(); k to q
            }
            if (sample >= COLD_WARMUPS) { coldKotlin += times.first; coldJs += times.second }
        }
        val cases = List(BATCH_REPEATS) { player.cases }.flatten()
        val expectedBatch = expectedJson(cases)
        val program = compile(player)
        val warmKotlin = mutableListOf<Long>()
        val warmJs = mutableListOf<Long>()
        QuickJsPlayer.open(player).use { js ->
            val bytecode = js.prepareBatch(cases)
            repeat(WARM_WARMUPS + WARM_SAMPLES) { sample ->
                fun kotlinSample() = checkedTime(expectedBatch) { solve(program, cases) }
                fun jsSample() = checkedTime(expectedBatch) { js.runBatch(bytecode) }
                val times = if (sample % 2 == 0) {
                    val k = kotlinSample(); val q = jsSample(); k to q
                } else {
                    val q = jsSample(); val k = kotlinSample(); k to q
                }
                if (sample >= WARM_WARMUPS) { warmKotlin += times.first; warmJs += times.second }
            }
        }
        println("player=${player.id} transformsPerWarmBatch=${cases.size}")
        report("cold compile+first signature+n", coldKotlin, coldJs)
        report("warm batch (QuickJS precompiled bridge)", warmKotlin, warmJs)
    }
}

private fun compile(player: RealPlayer) = PlayerProgram.compile(player.source,
    PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp))

private fun solve(program: PlayerProgram, cases: List<Challenge>): String = resultsJson(cases.map {
    if (it.kind == "signature") program.decipherSignature(it.input) else program.transformN(it.input)
})

private inline fun checkedTime(expected: String, block: () -> String): Long {
    val start = System.nanoTime()
    val actual = block()
    val elapsed = System.nanoTime() - start
    check(actual == expected) { "Benchmark output mismatch; discard this run" }
    return elapsed
}

private fun report(label: String, kotlin: List<Long>, quickJs: List<Long>) {
    fun median(values: List<Long>) = values.sorted()[values.size / 2] / 1_000_000.0
    println(String.format(Locale.ROOT, "  %s: Kotlin=%.3f QuickJS=%.3f samples=%d/%d", label, median(kotlin), median(quickJs), kotlin.size, quickJs.size))
}
