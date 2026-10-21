package meow.nyx.quackjs.verification

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale

private const val WARMUPS = 10
private const val SAMPLES = 15
private const val REPEATS = 8

/** Opt-in per-kind/length evidence; no timing threshold is an acceptance assertion. */
fun main() {
    val players = PlayerFixtures.load().map { it to lane32Compile(it) }
    // Fail closed on correctness/concurrency before reporting any measurements.
    val verified = players.map { (player, program) -> lane32Verify(player, program) }
    val allocation = allocationBean()
    println("lane32 java=${System.getProperty("java.runtime.version")} vendor=${System.getProperty("java.vendor")}")
    println("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} arch=${System.getProperty("os.arch")}")
    println("cpus=${Runtime.getRuntime().availableProcessors()} maxHeap=${Runtime.getRuntime().maxMemory()} jvmArgs=${ManagementFactory.getRuntimeMXBean().inputArguments}")
    println("seed=0x32acc3e7 lengths=$lane32Lengths variants=2 warmups=$WARMUPS samples=$SAMPLES repeats=$REPEATS")
    println("Goldens, unseen challenges, malformed signatures, length guards and shared-program concurrency passed.")
    println("Malformed n comparisons above are diagnostic only: QuickJS uses the original URL-encoded wrapper route.")
    println("Warm medians include result-array construction and JSON serialization; inputs/compile/checks excluded.")
    println("Kotlin bytes/op: current JVM thread only, includes result serialization; not retained/peak/native memory.")
    println("QuickJS: precompiled bridge; dispatcher/JNI/marshalling included; native/worker allocations NOT measured.")
    println("quickjs-kt-jvm=1.0.15 nativeLimit=192MiB stack=2MiB dedicatedThreadStack=8MiB timeout=30s")
    println("player,kind,length,ops,kotlin_us/op,quickjs_us/op,kotlin_thread_bytes/op")
    players.forEachIndexed { index, (player, program) ->
        val cases = verified[index]
        println("player=${player.id} unseen=${cases.size} malformedDiagnostics=${lane32Malformed().size} concurrency=4x3 passes")
        QuickJsPlayer.open(player).use { js ->
            for (kind in listOf("signature", "n")) for (length in lane32Lengths) {
                val selected = cases.filter { it.kind == kind && it.input.length == length }
                check(selected.size == 2)
                val batch = List(REPEATS) { selected }.flatten()
                val expected = expectedJson(batch)
                val bytecode = js.prepareBatch(batch)
                val kotlin = mutableListOf<Sample>()
                val quickJs = mutableListOf<Sample>()
                repeat(WARMUPS + SAMPLES) { sample ->
                    fun kotlinSample() = measure(allocation, expected) { lane32Solve(program, batch) }
                    fun jsSample() = measure(null, expected) { js.runBatch(bytecode) }
                    val pair = if (sample % 2 == 0) {
                        val k = kotlinSample(); val q = jsSample(); k to q
                    } else {
                        val q = jsSample(); val k = kotlinSample(); k to q
                    }
                    if (sample >= WARMUPS) { kotlin += pair.first; quickJs += pair.second }
                }
                val bytes = if (kotlin.all { it.bytes != null })
                    String.format(Locale.ROOT, "%.1f", median(kotlin.map { it.bytes!! }) / batch.size) else "unsupported"
                println(String.format(Locale.ROOT, "%s,%s,%d,%d,%.3f,%.3f,%s", player.id, kind, length,
                    batch.size, median(kotlin.map { it.nanos }) / batch.size / 1_000.0,
                    median(quickJs.map { it.nanos }) / batch.size / 1_000.0, bytes))
            }
        }
    }
}

private data class Sample(val nanos: Long, val bytes: Long?)

private inline fun measure(allocation: ThreadMXBean?, expected: String, block: () -> String): Sample {
    @Suppress("DEPRECATION") // Keep this test-only harness runnable on Java 17 as well as the build toolchain.
    val thread = Thread.currentThread().id
    val before = allocation?.getThreadAllocatedBytes(thread)
    val start = System.nanoTime()
    val actual = block()
    val elapsed = System.nanoTime() - start
    val after = allocation?.getThreadAllocatedBytes(thread)
    check(actual == expected) { "Lane32 output mismatch; discard run" }
    return Sample(elapsed, if (before != null && after != null && before >= 0 && after >= before) after - before else null)
}

private fun median(values: List<Long>): Double = values.sorted()[values.size / 2].toDouble()

private fun allocationBean(): ThreadMXBean? {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
    try {
        if (bean != null && bean.isThreadAllocatedMemorySupported) {
            if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
            if (bean.isThreadAllocatedMemoryEnabled) {
                println("JVM thread-allocated bytes: enabled")
                return bean
            }
        }
    } catch (_: UnsupportedOperationException) {
        // Timing remains usable on JVMs without allocation accounting.
    } catch (_: SecurityException) {
        // A restricted management policy must not turn unsupported accounting into zero bytes.
    }
    println("JVM thread-allocated bytes: unsupported or disabled by JVM policy")
    return null
}
