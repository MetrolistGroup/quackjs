package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class BindingAuditTest {
    private fun parse(text: String): Expr = Parser(Lexer(text).tokens()).completeExpression()

    private fun compile(body: String, extra: String = "", dispatch: Boolean = false): PlayerProgram {
        val source = """
            var g={};
            var table='V;prototype;get;set'.split(';');
            var sig=function(${if (dispatch) "a,b," else ""}x){$body};
            g.C=function(raw,enabled){this.raw=raw;this.enabled=enabled;this[table[0]]={};};
            g.C[table[1]][table[3]]=function(k,v){this.V[k]=v;};
            g.C[table[1]][table[2]]=function(k){applyN(7,19,this);return this.V[k];};
            var applyN=function(a,b,o){if((a^b)==20)o.set('n',o.V.n.split('').reverse().join(''));};
            $extra
        """.trimIndent()
        return PlayerProgram.compile(source, PlayerHints(if (dispatch) "sig(2,3,INPUT)" else "sig(INPUT)", "C", 12345))
    }

    @Test fun playerBindingsPrecedeBuiltins() {
        val extra = "var Math={floor:function(){return 7}};"
        assertEquals("abc7", compile("return x+Math.floor(1);", extra).decipherSignature("abc"))
        assertEquals("7abc", compile("return Math.floor(1)+x;", extra).decipherSignature("abc"))
        assertEquals("abc1", compile("return x+Math.floor(1);").decipherSignature("abc"))
    }

    @Test fun builtinShadowsStillRejectAmbiguityAndExternalMutation() {
        for (extra in listOf(
            "var Math={floor:function(){return 7}};var Math={floor:function(){return 8}};",
            "var Math={floor:function(){return 7}};Math.floor=function(){return 8};",
        )) assertFailsWith<UnsupportedPlayerException> { compile("return x+Math.floor(1);", extra) }
    }

    @Test fun regexLiteralsCreateFreshObjectsOnEveryEvaluation() {
        val program = compile("var f=function(){return /a/;};return f()===f()?x:x.split('').reverse().join('');")
        repeat(2) { assertEquals("cba", program.decipherSignature("abc")) }
        val runtime = Runtime(SourceIndex("g.C=function(){};", "C"))
        val literal = parse("/a/")
        val first = runtime.expression(literal)
        val charged = runtime.chargedAllocation
        assertNotSame(first, runtime.expression(literal))
        assertEquals(charged * 2, runtime.chargedAllocation)
        assertTrue(charged > 0)
    }

    @Test fun parametersAndLocalsShadowTheFunctionName() {
        for (body in listOf(
            "var echo=function echo(echo){return echo};return typeof echo(x)+':'+x;",
            "var f=function self(self){return typeof self};return f(x)+':'+x;",
        )) assertEquals("string:abc", compile(body).decipherSignature("abc"))
        assertEquals("undefined:abc", compile("var f=function self(){var self;return typeof self};return f()+':'+x;").decipherSignature("abc"))
        assertEquals("abc", compile("var f=function self(v){if(v.length>3)return self(v.slice(0,3));return v};return f(x);").decipherSignature("abc"))
    }

    @Test fun parametersShadowArgumentsButVarDeclarationsDoNot() {
        assertEquals("abc", compile("var f=function(arguments){return arguments};return f(x);").decipherSignature("abc"))
        assertEquals("abc", compile("var f=function(arguments){return function(){return arguments[0]}};return f('outer')(x);").decipherSignature("abc"))
        assertEquals("abc", compile("var f=function(){var arguments;return arguments[0]};return f(x);").decipherSignature("abc"))
        assertEquals("abc", compile("var f=function arguments(){return arguments[0]};return f(x);").decipherSignature("abc"))
    }

    @Test fun declaredGlobalsResolveBeforeTheirFirstWrite() {
        for (operation in listOf("counter=1", "counter+=1", "counter++", "++counter")) {
            val program = compile("if(x.length==3)$operation;return x+counter;", "var counter=0;")
            repeat(2) {
                assertEquals("abc1", program.decipherSignature("abc"), operation)
                assertEquals("abcd0", program.decipherSignature("abcd"), operation)
            }
        }
        val program = compile("if(x.length==3)missing=1;return x;")
        assertFailsWith<UnsupportedPlayerException> { program.decipherSignature("abc") }
    }

    @Test fun foldedCalleesKeepValueCallReceivers() {
        for (callee in listOf("a?o.f:o.f", "a&&o.f", "0||o.f", "null??o.f")) {
            val body = "var o={f:function(){return this===o?'bound':'unbound'}};return ($callee)()+x;"
            assertEquals("unboundz", compile(body, dispatch = true).decipherSignature("z"), callee)
        }
        assertEquals("boundz", compile("var o={f:function(){return this===o?'bound':'unbound'}};return o.f()+x;", dispatch = true).decipherSignature("z"))
    }

    @Test fun repeatedDeclarationsKeepTheirOwnPrefixRewrite() {
        val program = compile("var y=k;var before=y;var k=a^b;var y=k;return before+':'+y+':'+x;", dispatch = true)
        assertEquals("undefined:1:z", program.decipherSignature("z"))
    }

    @Test fun foldedStringAdditionsRetainFallbackRejection() {
        val program = compile("try{throw 0}catch(e){return 'marker_'+'abc'}", dispatch = true)
        assertEquals("marker_abc", program.decipherSignature("other"))
        assertEquals("Transform entered an exception fallback", assertFailsWith<UnsupportedPlayerException> {
            program.decipherSignature("abc")
        }.message)
    }
}
