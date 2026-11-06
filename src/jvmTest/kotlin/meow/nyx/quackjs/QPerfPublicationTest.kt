package meow.nyx.quackjs

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class QPerfPublicationTest {
    @Test fun concurrentFirstResolutionPublishesOneImmutableDefinition() {
        val source = SourceIndex("g.C=function(){};var late=function(x){" + "x=x;".repeat(1000) + "return x;};", "C")
        source.releaseCompileState()
        val pool = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        try {
            val tasks = List(4) { Callable { barrier.await(10, TimeUnit.SECONDS); source.definition("late") } }
            val results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS).map { it.get() }
            results.forEach { assertSame(results.first(), it) }
            assertSame(results.first(), source.definition("late"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun failedDiscoveryDoesNotPublishBrowserDefinitionsOrTemplates() {
        val program = PlayerProgram.compile("""
            var g={};var table=[1,2,3];var sig=function(x){return x;};
            var browser=function(){var value=table[0];throw value;};
            g.C=function(){this.V={};};
            g.C.prototype.set=function(k,v){this.V[k]=v;};
            g.C.prototype.get=function(k){apply(this);browser();return this.V[k];};
            var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
        """.trimIndent(), PlayerHints("sig(INPUT)", "C", 1))
        for (name in listOf("parsed", "templates")) {
            val field = PlayerProgram::class.java.getDeclaredField(name).also { it.isAccessible = true }
            val entries = field.get(program) as Map<*, *>
            assertFalse("table" in entries, name)
            assertFalse("browser" in entries, name)
        }
        assertEquals("abc", program.decipherSignature("abc"))
        assertEquals("cba", program.transformN("abc"))
    }
}
