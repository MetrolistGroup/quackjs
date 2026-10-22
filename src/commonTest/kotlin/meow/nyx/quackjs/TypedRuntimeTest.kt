package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TypedRuntimeTest {
    private fun runtime() = Runtime(SourceIndex("g.C=function(){};", "C"))
    private fun evaluate(body: String): JsValue {
        val source = "(function(){$body})()"
        return runtime().expression(Parser(Lexer(source).tokens(), source).completeExpression())
    }

    @Test fun omittedNativeArgumentsAreUndefined() {
        kotlin.test.assertEquals(JsString("object:object"), evaluate("return typeof Object()+':'+typeof new Object();"))
        for (call in listOf("f.apply()", "f.apply(undefined)", "f.apply(null,null)", "f.apply(undefined,undefined)", "f.apply(null,[])")) {
            kotlin.test.assertEquals(JsString("0"), evaluate("var f=function(){return arguments.length};return String($call);"), call)
        }
    }

    @Test fun missingBindingsNullUndefinedHolesAndCompletionsStayDistinct() {
        val env = Env()
        assertNull(env["missing"])
        for (value in listOf(JsNull, Undefined, Hole)) {
            env["value"] = value
            assertSame(value, env["value"])
            assertSame(value, (runtime().exec(ReturnStmt(Lit(value)), env) as Completion.Returned).value)
        }
        assertSame(Completion.Normal, runtime().exec(EmptyStmt, env))
        kotlin.test.assertEquals(Completion.Jumped(false, "outer"), runtime().exec(Jump(false, "outer"), env))
        kotlin.test.assertEquals(JsString("false:true:false"), evaluate("return [null===undefined,null==undefined,0==null].join(':');"))
    }

    @Test fun literalReadsReusePrimitivesWithoutChangingNumericEquality() {
        val runtime = runtime()
        for (value in listOf(JsNumber(-0.0), JsNumber(Double.NaN), JsString("literal"), JsBoolean(false))) {
            val literal = Lit(value)
            repeat(2) { assertSame(value, runtime.expression(literal)) }
        }
        kotlin.test.assertEquals(JsString("true:true:false:false:-Infinity"), evaluate("var n=NaN;return [0===-0,-0==0,n===n,n==n,1/-0].join(':');"))
        for (number in listOf("1.5", "1e23", "1e-7")) {
            assertFailsWith<UnsupportedPlayerException> { evaluate("try{return String($number)}catch(e){return 'caught'}") }
        }
    }

    @Test fun nativeAliasesDoNotRenameSharedMethods() {
        val original = Native { _, _, _ -> Undefined }
        val first = Native(mapOf("first" to original)) { _, _, _ -> Undefined }
        val second = Native(mapOf("second" to original)) { _, _, _ -> Undefined }
        assertNull(original.functionName)
        kotlin.test.assertEquals("function first() { [native code] }", text(first.fields.getValue("first")))
        kotlin.test.assertEquals("function second() { [native code] }", text(second.fields.getValue("second")))
        val named = original.named("fixed")
        assertSame(named, named.named("other"))
    }
}
