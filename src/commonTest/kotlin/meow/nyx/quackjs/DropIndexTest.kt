package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropIndexTest {
    private val functionText = "function (x) { /* 雪, exact spacing */ return x.split('').reverse().join(''); }"
    private fun index(extra: String): SourceIndex = SourceIndex("var g={};g.C=function(){};$extra", "C")

    @Test fun releaseKeepsOffsetsStatusesAndDeferredErrors() {
        val source = index("var good=$functionText;var bad=class {};var duplicate=1;duplicate=2;var letters=['é'];var alias=letters;alias[0]='bad';")
        val names = listOf("missing", "bad", "duplicate", "letters", "alias")
        val errors = names.associateWith { name -> assertFailsWith<UnsupportedPlayerException> { source.definition(name) }.message }
        assertEquals(functionText, (source.definition("good") as FunctionExpr).sourceText())
        source.releaseCompileState()
        source.releaseCompileState()
        assertFalse(source.hasDefinition("missing"))
        assertTrue(source.hasDefinition("bad"))
        assertTrue(source.hasDefinition("duplicate"))
        assertEquals(functionText, (source.definition("good") as FunctionExpr).sourceText())
        for (name in names) assertEquals(errors[name], assertFailsWith<UnsupportedPlayerException> { source.definition(name) }.message)
    }

    private fun compile(signature: String, extra: String = ""): PlayerProgram = PlayerProgram.compile("""
        var g={};
        var sig=function(x){$signature};
        var late=$functionText;
        g.C=function(){this.V={};};
        g.C.prototype.set=function(k,v){this.V[k]=v;};
        g.C.prototype.get=function(k){apply(this);return this.V[k];};
        var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
        $extra
    """.trimIndent(), PlayerHints("sig(INPUT)", "C", 1))

    @Test fun publishedProgramsResolveUnprobedBranchesAndKeepFunctionText() {
        val callable = compile("if(x.length<8)return late(x);return x;")
        val text = compile("if(x.length<8)return String(late)+x;return x;")
        for (input in listOf("", "a", "雪☃", "\ud800", "1234567", "12345678", "x".repeat(16_384))) {
            repeat(2) {
                assertEquals(if (input.length < 8) input.reversed() else input, callable.decipherSignature(input))
                assertEquals(if (input.length < 8) functionText + input else input, text.decipherSignature(input))
                assertEquals(input.reversed(), callable.transformN(input))
            }
        }
    }

    @Test fun releasedProgramsPreserveLazyFailuresAndLiveAliases() {
        val unsupported = compile("if(x.length<8)return bad(x);return x;", "var bad=function(){return new RegExp('x')};")
        assertFailsWith<UnsupportedPlayerException> { unsupported.decipherSignature("") }
        assertEquals("12345678", unsupported.decipherSignature("12345678"))
        val alias = compile("if(x.length<8){var alias=letters;alias[0]=x;return read();}return x;", "var letters=['old'];var read=function(){return letters[0]};")
        for (input in listOf("", "é", "next", "é")) assertEquals(input, alias.decipherSignature(input))
        val mutated = compile("if(x.length<8)return changed.join('');return x;", "var changed=['old'];var same=changed;same[0]='new';")
        assertFailsWith<UnsupportedPlayerException> { mutated.decipherSignature("a") }
        val ambiguous = compile("if(x.length<8)return duplicate;return x;", "var duplicate='one';duplicate='two';")
        assertFailsWith<UnsupportedPlayerException> { ambiguous.decipherSignature("a") }
    }

    @Test fun oversizedFunctionTextStillFailsOnlyOnDemand() {
        val source = index("var big=function(){/*${"x".repeat(65_536)}*/return 1;};")
        source.releaseCompileState()
        val fn = source.definition("big") as FunctionExpr
        assertEquals("Function string size limit exceeded", assertFailsWith<UnsupportedPlayerException> { fn.sourceText() }.message)
    }
}
