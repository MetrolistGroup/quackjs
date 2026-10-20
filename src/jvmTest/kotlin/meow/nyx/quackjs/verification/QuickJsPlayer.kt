package meow.nyx.quackjs.verification

import com.dokar.quickjs.QuickJs
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** Full original player, with only browser globals and two exports injected. No Kotlin cipher code. */
internal class QuickJsPlayer private constructor(
    private val js: QuickJs,
    private val thread: ExecutorCoroutineDispatcher,
) : Closeable {
    companion object {
        fun open(player: RealPlayer): QuickJsPlayer {
            // QuickJS stack accounting requires creation, evaluation, and disposal on one native thread.
            val thread = Executors.newSingleThreadExecutor { task ->
                Thread(null, task, "quackjs-oracle", 8L * 1024 * 1024)
            }.asCoroutineDispatcher()
            var js: QuickJs? = null
            try {
                val engine = runBlocking(thread) {
                    QuickJs.create(thread).also {
                        js = it
                        it.memoryLimit = 192L * 1024 * 1024
                        it.maxStackSize = 2L * 1024 * 1024
                        it.evaluationTimeoutMillis = 30_000
                        it.evaluate<Any?>(player.globals + "\n;undefined;")
                        it.evaluate<Any?>(player.exportedSource() + "\n;undefined;")
                    }
                }
                return QuickJsPlayer(engine, thread)
            } catch (failure: Throwable) {
                try {
                    runBlocking(thread) { js?.close() }
                } finally {
                    thread.close()
                }
                throw failure
            }
        }
    }

    fun solve(cases: List<Challenge>): String = runBlocking(thread) {
        js.evaluate<String>("JSON.stringify(${inputsJson(cases)}.map(c => c[0] === 'signature' ? __quackSig(c[1]) : __quackN(c[1])))")
    }

    /** Hoist input transfer and bridge compilation out of steady-state samples. */
    fun prepareBatch(cases: List<Challenge>): ByteArray = runBlocking(thread) {
        js.evaluate<Any?>("globalThis.__quackCases=${inputsJson(cases)}; undefined;")
        js.compile("JSON.stringify(__quackCases.map(c => c[0] === 'signature' ? __quackSig(c[1]) : __quackN(c[1])))")
    }

    fun runBatch(bytecode: ByteArray): String = runBlocking(thread) { js.evaluate<String>(bytecode) }

    override fun close() {
        try {
            runBlocking(thread) { js.close() }
        } finally {
            thread.close()
        }
    }
}
