package meow.nyx.quackjs.verification

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

/** Extra cold and deferred-dependency workloads for the player benchmark. */
internal fun qPerfBenchmarks(players: List<RealPlayer>) {
    val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
    fun measure(label: String, expected: String, repeats: Int = 1, operation: () -> String) {
        val times = mutableListOf<Long>()
        val bytes = mutableListOf<Long>()
        repeat(16) { sample ->
            val before = allocation.currentThreadAllocatedBytes
            val start = System.nanoTime()
            var result = ""
            repeat(repeats) { result = operation() }
            val elapsed = System.nanoTime() - start
            val allocated = allocation.currentThreadAllocatedBytes - before
            check(result == expected) { "q-perf output mismatch" }
            if (sample >= 5) { times += elapsed / repeats; bytes += allocated / repeats }
        }
        println(String.format(Locale.ROOT, "q-perf %s: ms=%.4f bytes=%d samples=11", label, times.sorted()[5] / 1e6, bytes.sorted()[5]))
    }
    for (player in players) {
        val expected = expectedJson(player.firstPair)
        measure("hinted ${player.id}", expected) {
            val p = PlayerProgram.compile(player.source, PlayerHints(player.signatureExpression, player.nClass, player.signatureTimestamp))
            resultsJson(player.firstPair.map { if (it.kind == "signature") p.decipherSignature(it.input) else p.transformN(it.input) })
        }
        measure("hint-free ${player.id}", expected) {
            val p = PlayerProgram.compile(player.source)
            resultsJson(player.firstPair.map { if (it.kind == "signature") p.decipherSignature(it.input) else p.transformN(it.input) })
        }
    }
    val hints = PlayerHints("sig(INPUT)", "C", 1)
    fun source(signature: String, extra: String, browser: String = "") = """
        var g={};var sig=function(x){$signature};$extra
        g.C=function(){this.V={};};
        g.C.prototype.set=function(k,v){this.V[k]=v;};
        g.C.prototype.get=function(k){apply(this);$browser return this.V[k];};
        var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
    """.trimIndent()
    val failed = source("return x;", "var table=[" + "1,".repeat(64_999) + "1];var browser=function(){var v=table[0];throw v;};", "browser();")
    measure("discarded-probe", "cba") { PlayerProgram.compile(failed, hints).transformN("abc") }
    val late = source("if(x.length>64)return late(x);return x;", "var late=function(x){" + "x=x;".repeat(5000) + "return x;};")
    val program = PlayerProgram.compile(late, hints)
    val input = "x".repeat(86)
    measure("late-dependency", input, repeats = 8) { program.decipherSignature(input) }
}
