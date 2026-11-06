package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QPerfLandmarksTest {
    private val source = """
        var g={};var sig=function(a,b,x){return x;};
        g.C=function(){this.V={};};
        g.C.prototype.set=function(k,v){this.V[k]=v;};
        g.C.prototype.get=function(k){apply(this);return this.V[k];};
        var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
    """.trimIndent()
    private val landmark = "var mark=function(M,X){M=new g.C(M,!0);M.set(\"alr\",\"yes\");X=sig(1,2,decode(3,4,X));};"
    private val decoy = "new g.Other(M,!0);M.set(\"alr\",\"yes\");X=wrong(5,6,decode(7,8,X));"

    @Test fun ignoresNonExecutableLandmarksBeforeAndAfterTheRealOne() {
        val fakes = listOf("/*$decoy*/", "var bait='$decoy';", "var bait=`$decoy`;", "var bait=/$decoy/;")
        for (fake in fakes) for (js in listOf(fake + source + landmark, source + landmark + fake)) {
            val program = PlayerProgram.compile(js)
            assertEquals("abc", program.decipherSignature("abc"))
            assertEquals("cba", program.transformN("abc"))
        }
    }

    @Test fun rejectsMissingAndAmbiguousExecutableLandmarks() {
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(source + "/*$decoy*/") }
        val other = landmark.replace("sig(1,2,", "wrong(5,6,")
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(source + landmark + other) }
    }

    @Test fun derivedClassesKeepTheIdentifierLengthLimit() {
        val name = "C".repeat(129)
        val error = assertFailsWith<UnsupportedPlayerException> {
            PlayerProgram.compile((source + landmark).replace("g.C", "g.$name"))
        }
        assertEquals("Invalid n class hint", error.message)
    }

    @Test fun selectedClassKeepsItsWrapperScopeAfterACommentDecoy() {
        val js = "/*$decoy*/(function(Array){" + source.replace("var sig=function(a,b,x){return x;};", "var letters=['a'];var sig=function(a,b,x){return letters.join('')+x;};") +
            landmark + "Array.isArray(letters);})({});"
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(js) }
    }
}
