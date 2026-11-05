package meow.nyx.quackjs

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class DroppedIndexConcurrencyTest {
    @Test fun concurrentLateDefinitionsKeepPrivateAliasesAndExactFunctionText() {
        val text = "function (x) { return x.split('').reverse().join(''); }"
        val program = PlayerProgram.compile("""
            var g={};var state=[];var late=$text;
            var sig=function(x){if(x.length<8){var alias=state;alias.push(x);return String(late)+':'+late(x)+':'+state.length;}return x;};
            g.C=function(){this.V={};};
            g.C.prototype.set=function(k,v){this.V[k]=v;};
            g.C.prototype.get=function(k){apply(this);return this.V[k];};
            var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
        """.trimIndent(), PlayerHints("sig(INPUT)", "C", 1))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(16) {
                Callable {
                    repeat(3) {
                        for (input in listOf("", "é", "雪☃", "\ud800", "abc", "12345678")) {
                            assertEquals(if (input.length < 8) "$text:${input.reversed()}:1" else input, program.decipherSignature(input))
                            assertEquals(input.reversed(), program.transformN(input))
                        }
                    }
                }
            }
            pool.invokeAll(tasks, 30, TimeUnit.SECONDS).forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }
}
