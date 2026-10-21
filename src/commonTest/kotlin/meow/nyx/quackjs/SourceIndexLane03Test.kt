package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceIndexLane03Test {
    private val constructor = "g.C=function(root){};"

    private fun index(extra: String): SourceIndex = SourceIndex("var g={};$constructor$extra", "C")

    private fun rejectsLetters(source: SourceIndex) {
        val error = assertFailsWith<UnsupportedPlayerException> { source.definition("letters") }
        assertTrue(error.message!!.contains("Externally mutated dependency 'letters'"), error.message)
    }

    @Test fun targetsSurviveRecentWindowWrapAndGroupedWrites() {
        val padding = "0;".repeat(60)
        assertEquals(Lit("ok"), index("${padding}var value='ok';").definition("value"))
        val reassigned = index("var letters=['a'];$padding((letters))=['b'];")
        assertEquals("Ambiguous dependency 'letters'", assertFailsWith<UnsupportedPlayerException> { reassigned.definition("letters") }.message)
        for (write in listOf("((letters[0]))='b'", "((letters['x'][0]))='b'", "((letters[0]))++", "letters[0]+=1")) {
            rejectsLetters(index("var letters=['a'];$padding$write;"))
        }
    }

    @Test fun rootCallsKeepDirectComputedGroupedAndIndirectTargets() {
        for (call in listOf("unknown(letters)", "Object.assign(letters,{})", "Object['assign'](letters,{})", "(Object.assign)(letters,{})", "(0,Object.assign)(letters,{})", "unknown?.(letters)")) {
            rejectsLetters(index("var letters=['a'];$call;"))
        }
        val beforeClass = SourceIndex("var letters=['a'];unknown(letters);$constructor", "C")
        rejectsLetters(beforeClass)
    }

    @Test fun shallowerClassKeepsEarlierRootCallsAndIgnoresDeeperCalls() {
        val nested = "var nested=function(){g.C=function(nested){};unknown(letters);};"
        val safe = SourceIndex("var letters=['a'];$nested$constructor Array.isArray(letters);", "C")
        assertEquals(listOf("root"), safe.classFunction("C").params)
        assertTrue(safe.definition("letters") is ArrayExpr)
        rejectsLetters(SourceIndex("var letters=['a'];unknown(letters);$nested$constructor", "C"))
    }

    @Test fun classAmbiguityStillUsesTheShallowestDepth() {
        val error = assertFailsWith<UnsupportedPlayerException> {
            SourceIndex("$constructor var nested=function(){g.C=function(){};};((g.C))=function(){};", "C")
        }
        assertTrue(error.message!!.contains("Ambiguous n class 'C'"), error.message)
    }

    @Test fun nestedBuiltinWritesAndUpdatesStillInvalidateAliasedPredicates() {
        for (mutation in listOf("Native['isArray']=function(a){a.reverse()}", "Native.isArray++", "++Native.isArray", "delete Native.isArray")) {
            rejectsLetters(index("var letters=['a'];var Native=Array;var patch=function(){$mutation;};Array.isArray(letters);"))
        }
        assertTrue(index("var letters=['a'];var nested=function(){unknown(letters);};Array.isArray(letters);").definition("letters") is ArrayExpr)
    }

    @Test fun nonCallFunctionHeadersStillRecordShadowedBuiltins() {
        for (wrapper in listOf("function(Array)", "function Array()")) {
            rejectsLetters(SourceIndex("($wrapper{var letters=['a'];$constructor Array.isArray(letters);})({});", "C"))
        }
        val unshadowed = SourceIndex("(function(g){var letters=['a'];$constructor Array.isArray(letters);})({});", "C")
        assertTrue(unshadowed.definition("letters") is ArrayExpr)
    }

    @Test fun discardedNestedCallsStillConsumeTheCallLimit() {
        val calls = "f();".repeat(100_000)
        index("var nested=function(){$calls};")
        val error = assertFailsWith<UnsupportedPlayerException> { index("var nested=function(){${calls}f();};") }
        assertTrue(error.message!!.contains("Source call limit exceeded"), error.message)
    }
}
