package meow.nyx.quackjs.verification

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class Faraday200OracleTest {
    private fun player(body: String = "return 'valid_value'", initialization: String = ""): RealPlayer = RealPlayer(
        "synthetic", """
            var _yt_player={};
            (function(g){
                $initialization
                function Ab(a,b,input){return 'signature_value'}
                g.CD=function(url){this.get=function(){$body}};
            })(_yt_player);
        """.trimIndent(), "", "Ab(1,2,INPUT)", "CD", 123,
        listOf(Challenge("signature", "sample", "challenge", "signature_value"),
            Challenge("n", "probe", "probe_value", "valid_value")),
    )

    @Test
    fun repeatedReversedAndFreshContextsAllCompare() {
        val result = verifyFaradayOracle(player(initialization =
            "if(globalThis.initialized)throw Error('reused context'); globalThis.initialized=true;"))
        assertEquals("pass", result.text("status"))
        assertEquals(2, result.getValue("contexts").jsonPrimitive.int)
        assertEquals(8, result.getValue("checked").jsonPrimitive.int)
    }

    @Test
    fun failedInitializationNeverCountsAsAPass() {
        val result = verifyFaradayOracle(player(initialization = "throw Error('broken init');"))
        assertEquals("fail", result.text("status"))
        assertEquals(0, result.getValue("contexts").jsonPrimitive.int)
        assertEquals(0, result.getValue("checked").jsonPrimitive.int)
        assertEquals(2, result.getValue("errors").jsonArray.size)
    }

    @Test
    fun missingThrownUnchangedSentinelAndMismatchedResultsFail() {
        for (body in listOf("return undefined", "return null", "throw Error('broken transform')",
            "return 'probe_value'", "return 'enhanced_except_bad'", "return 'wrong_value'")) {
            val result = verifyFaradayOracle(player(body))
            assertEquals("fail", result.text("status"), body)
            assertEquals(2, result.getValue("errors").jsonArray.size, body)
        }
    }
}
