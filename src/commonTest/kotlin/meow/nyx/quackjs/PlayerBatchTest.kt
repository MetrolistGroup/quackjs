package meow.nyx.quackjs

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerBatchTest {
    private fun program(): PlayerProgram = PlayerProgram.compile("""
        var g={};
        var state=[];
        var sig=function(x){
            if(x=='fail')throw 'signature failure';
            state.push(x);return state.length+':'+x;
        };
        g.C=function(raw,enabled){this.raw=raw;this.enabled=enabled;this.V={};};
        g.C.prototype.set=function(k,v){this.V[k]=v;};
        g.C.prototype.get=function(k){applyN(this);return this.V[k];};
        var applyN=function(o){
            if(o.V.n=='fail')throw 'n failure';
            o.set('n',o.V.n.split('').reverse().join(''));
        };
    """.trimIndent(), PlayerHints("sig(INPUT)", "C", 12345))

    @Test fun mixedBatchPreservesOrderAndIsolatesFailuresAndState() {
        val program = program()
        val results = program.transformBatch(listOf(
            PlayerChallenge.Signature("abc"), PlayerChallenge.N("abc"),
            PlayerChallenge.Signature("fail"), PlayerChallenge.N("fail"),
            PlayerChallenge.Signature("next"), PlayerChallenge.N("next"),
            PlayerChallenge.N(""),
        ))
        assertEquals(PlayerTransformResult.Success("1:abc"), results[0])
        assertEquals(PlayerTransformResult.Success("cba"), results[1])
        assertTrue(assertIs<PlayerTransformResult.Failure>(results[2]).message.contains("signature failure"))
        assertTrue(assertIs<PlayerTransformResult.Failure>(results[3]).message.contains("n failure"))
        assertEquals(PlayerTransformResult.Success("1:next"), results[4])
        assertEquals(PlayerTransformResult.Success("txen"), results[5])
        assertEquals(PlayerTransformResult.Success(""), results[6])
        assertEquals("1:after", program.decipherSignature("after"))
    }

    @Test fun successesAndFailuresAreMemoizedByKindOnlyWithinOneBatch() {
        val program = program()
        val challenges = listOf(
            PlayerChallenge.Signature("abc"), PlayerChallenge.N("abc"),
            PlayerChallenge.Signature("fail"), PlayerChallenge.N("fail"),
            PlayerChallenge.Signature("x".repeat(16_385)),
        )
        val results = program.transformBatch(challenges + challenges.map {
            when (it) {
                is PlayerChallenge.Signature -> PlayerChallenge.Signature(it.input)
                is PlayerChallenge.N -> PlayerChallenge.N(it.input)
            }
        })
        for (i in challenges.indices) assertSame(results[i], results[i + challenges.size])
        assertNotSame(results[0], results[1])
        assertNotSame(results[2], results[3])
        assertIs<PlayerTransformResult.Failure>(results[4])
        val again = program.transformBatch(challenges)
        assertEquals(results.take(challenges.size), again)
        for (i in challenges.indices) assertNotSame(results[i], again[i])
    }

    @Test fun cancellationRunsForMemoHitsAndPropagatesUnchanged() {
        val program = program()
        val challenge = PlayerChallenge.N("abc")
        val cancellation = CancellationException("stop")
        var visits = 0
        val thrown = assertFailsWith<CancellationException> {
            program.transformBatch(List(4) { challenge }) {
                if (++visits == 3) throw cancellation
            }
        }
        assertSame(cancellation, thrown)
        assertEquals(3, visits)
        assertEquals("cba", program.transformN("abc"))
        // Even a callback throwing the library's exception must not become an entry failure.
        val error = UnsupportedPlayerException("callback")
        assertSame(error, assertFailsWith<UnsupportedPlayerException> {
            program.transformBatch(listOf(challenge)) { throw error }
        })
    }

    @Test fun emptyAndMaximumBatchesAndOversizedRejection() {
        val program = program()
        var visits = 0
        assertEquals(emptyList(), program.transformBatch(emptyList()) { visits++ })
        val maximum = List(256) { PlayerChallenge.N("abc") }
        val results = program.transformBatch(maximum) { visits++ }
        assertEquals(256, visits)
        assertEquals(256, results.size)
        assertTrue(results.all { it === results.first() })
        assertFailsWith<UnsupportedPlayerException> {
            program.transformBatch(maximum + PlayerChallenge.N("abc")) { visits++ }
        }
        assertEquals(256, visits)
    }
}
