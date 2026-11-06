package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class QPerfTest {
    @Test fun releasedIndexReusesParsedDefinitionsButNotRuntimeValues() {
        val source = SourceIndex("g.C=function(){};var late=function(x){return x;};var state=[];var bad=class {};", "C")
        source.releaseCompileState()
        assertSame(source.definition("late"), source.definition("late"))
        assertSame(source.definition("state"), source.definition("state"))
        val first = Runtime(source).resolve("state")
        val second = Runtime(source).resolve("state")
        assertNotSame(first, second)
        repeat(2) { assertFailsWith<UnsupportedPlayerException> { source.definition("bad") } }
    }

    @Test fun lateDefinitionCacheStopsAtItsEntryLimit() {
        val source = SourceIndex("g.C=function(){};" + (0..512).joinToString("") { "var f$it=function(){return $it;};" }, "C")
        source.releaseCompileState()
        for (i in 0 until 512) assertSame(source.definition("f$i"), source.definition("f$i"))
        assertNotSame(source.definition("f512"), source.definition("f512"))
        assertSame(source.definition("f0"), source.definition("f0"))
    }

    @Test fun lateDefinitionCacheAlsoBoundsTotalParsedTokens() {
        val literal = "[" + "0,".repeat(64_999) + "0]"
        val source = SourceIndex("g.C=function(){};" + (0..3).joinToString("") { "var a$it=$literal;" }, "C")
        source.releaseCompileState()
        for (i in 0..2) assertSame(source.definition("a$i"), source.definition("a$i"))
        assertNotSame(source.definition("a3"), source.definition("a3"))
        assertSame(source.definition("a0"), source.definition("a0"))
    }

    @Test fun nestedAssignmentsKeepBuiltinGuardsAndShallowClassSelection() {
        val source = """
            var g={};var letters=['a'];var Native=Array;
            var nested=function(){g.C=function(){};Native['isArray']=function(x){return true};};
            g.C=function(root){this.V={};};Array.isArray(letters);
        """.trimIndent()
        val index = SourceIndex(source, "C")
        assertEquals(listOf("root"), index.classFunction("C").params)
        assertFailsWith<UnsupportedPlayerException> { index.definition("letters") }
        assertFailsWith<UnsupportedPlayerException> { SourceIndex(source + "g.C=function(){};", "C") }
        assertFailsWith<UnsupportedPlayerException> {
            SourceIndex("g.C=function(){};var f=function(){" + "x=0;".repeat(100_000) + "};", "C")
        }
    }

    @Test fun computedMethodsKeepAmbiguityChecksAmongUnrelatedAssignments() {
        val source = """
            var g={};var keys=['prototype','get','set'];g.C=function(){this.V={};};
            var unrelated=1;g.Other.prototype.get=function(){};
            g.C[keys[0]][keys[1]]=function(k){return this.V[k];};
            g.C[keys[0]][keys[2]]=function(k,v){this.V[k]=v;};
        """.trimIndent()
        val index = SourceIndex(source, "C")
        assertEquals(listOf("k"), index.method("C", "get", Runtime(index)).params)
        val duplicate = SourceIndex(source + "g.C.prototype.get=function(k){return k;};", "C")
        assertFailsWith<UnsupportedPlayerException> { duplicate.method("C", "get", Runtime(duplicate)) }
    }
}
