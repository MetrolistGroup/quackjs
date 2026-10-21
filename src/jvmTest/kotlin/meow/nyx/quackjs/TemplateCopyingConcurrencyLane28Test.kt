package meow.nyx.quackjs

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateCopyingConcurrencyLane28Test {
    @Test fun nestedMutableTemplatesAndClosuresStayIndependentAcrossThreads() {
        val program = templateProgramLane28()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(32) { worker ->
                Callable {
                    repeat(20) { call ->
                        val input = "worker${worker}call$call"
                        assertEquals(templateExpectedLane28(input), program.decipherSignature(input))
                        assertEquals(templateExpectedLane28(input), program.transformN(input))
                    }
                }
            }
            pool.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
