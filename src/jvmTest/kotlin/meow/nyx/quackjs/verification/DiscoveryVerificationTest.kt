package meow.nyx.quackjs.verification

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiscoveryVerificationTest {
    @Test fun sweepFailsWhenOnlyHintedCompilationPasses() {
        val source = """
            var g={};var sig=function(x){return x.split('').reverse().join('')};
            g.C=function(raw,enabled){this.V={}};
            g.C.prototype.set=function(k,v){this.V[k]=v};
            g.C.prototype.get=function(k){this.set('n',this.V.n.split('').reverse().join(''));return this.V[k]};
        """.trimIndent()
        val program = PlayerProgram.compile(source, PlayerHints("sig(INPUT)", "C", 1))
        assertEquals("cba", program.decipherSignature("abc"))
        assertEquals("cba", program.transformN("abc"))
        val directory = Files.createTempDirectory("quackjs-sweep-test")
        try {
            Files.createDirectory(directory.resolve("sources"))
            Files.writeString(directory.resolve("sources/test.js"), source)
            val sweep = buildJsonObject {
                put("commit", "test"); put("current", "test"); put("total", 1)
                put("inputs", buildJsonObject {
                    for (kind in listOf("signature", "n")) put(kind, buildJsonArray { add(buildJsonObject { put("input", "abc") }) })
                })
                put("players", buildJsonArray { add(buildJsonObject {
                    put("id", "test"); put("sha256", sha256(source))
                    put("signatureExpression", "sig(INPUT)"); put("nClass", "C"); put("signatureTimestamp", 1)
                    put("signature", buildJsonArray { add("cba") }); put("n", buildJsonArray { add("cba") })
                }) })
            }
            Files.writeString(directory.resolve("sweep.json"), sweep.toString())
            assertFailsWith<IllegalStateException> { runFaradaySweep(directory) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun warmSamplesCheckEveryRepetitionAfterTiming() {
        val cases = listOf(Challenge("n", "first", "a", "A"), Challenge("n", "second", "b", "B"))
        val times = mutableListOf<Double>()
        val allocated = mutableListOf<Long>()
        var calls = 0
        val error = assertFailsWith<IllegalStateException> {
            sampleWarm(cases, 8, times, allocated) { input ->
                assertTrue(times.isEmpty())
                calls++
                if (calls == 1) "wrong" else input.uppercase()
            }
        }
        assertEquals("n/first golden mismatch", error.message)
        assertEquals(16, calls)
        assertEquals(1, times.size)
        assertEquals(1, allocated.size)
    }
}
