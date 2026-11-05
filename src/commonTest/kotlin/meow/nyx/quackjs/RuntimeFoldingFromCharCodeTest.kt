package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RuntimeFoldingFromCharCodeTest {
    private val source = SourceIndex("g.C=function(){};", "C")
    private fun evaluate(body: String): JsValue {
        val text = "(function(){$body})()"
        return Runtime(source).expression(Parser(Lexer(text).tokens()).completeExpression())
    }

    @Test fun convertsUtf16NumbersAndCoercibleValuesInOrder() {
        val runtime = Runtime(source)
        val method = runtime.get(runtime.resolve("String"), "fromCharCode")
        val numbers = listOf(0.0, -0.0, 65.9, -1.0, 65536.0, 65537.0, 9731.0, 55296.0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4294967361.0)
        for (n in numbers) {
            val expected = JsString((int(n) and 65535).toChar().toString())
            assertEquals(expected, runtime.invoke(method, Undefined, listOf(JsNumber(n))))
        }
        assertEquals(JsString(""), runtime.invoke(method, Undefined, emptyList()))
        assertEquals(JsString("A\u0000\uffff"), runtime.invoke(method, Undefined, listOf(JsNumber(65.0), Undefined, JsNumber(-1.0))))
        assertEquals(JsString("AB:ab"), evaluate("""
            var log='',a={valueOf:function(){log+='a';return 65}},alias=a,
                b={valueOf:function(){log+='b';return '66'}};
            return String.fromCharCode(alias,b)+':'+log;
        """))
        assertEquals(JsString("C"), evaluate("var a={valueOf:function(){return 65}};a.valueOf=function(){return 67};return String.fromCharCode(a);"))
        assertEquals(JsString("override"), evaluate("var original=String;String={fromCharCode:function(){return 'override'}};return String.fromCharCode(65);"))
    }

    @Test fun reusesOnlyImmutableCharactersAndRetainsExactCharges() {
        val runtime = Runtime(source)
        val method = runtime.get(runtime.resolve("String"), "fromCharCode")
        val first = runtime.invoke(method, Undefined, listOf(JsNumber(65.0)))
        assertSame(first, runtime.invoke(method, Undefined, listOf(JsNumber(65.0))))
        val bounded = Runtime(source)
        bounded.tick(2_000_000 - 2)
        var units = 2_000_000 - 4
        while (units > 0) { val chunk = minOf(units, 65_537); bounded.allocate(chunk - 1); units -= chunk }
        assertEquals(JsString("A"), bounded.invoke(method, Undefined, listOf(JsNumber(65.0))))
        assertFailsWith<UnsupportedPlayerException> { bounded.tick() }
        assertFailsWith<UnsupportedPlayerException> { bounded.allocate(0) }
        assertFailsWith<UnsupportedPlayerException> {
            Runtime(source).invoke(method, Undefined, List(65_537) { JsNumber(65.0) })
        }
    }

    @Test fun numericShortcutPreservesConversionDepthFailure() {
        for (depth in listOf(63, 64)) {
            val runtime = Runtime(source)
            var value: JsValue = JsNumber(65.0)
            repeat(depth) {
                val next = value
                value = JsObject(mutableMapOf("valueOf" to Native { r, _, _ -> r.expression(Unary("+", Lit(next))) }))
            }
            if (depth == 63) assertEquals(JsNumber(65.0), runtime.expression(Unary("+", Lit(value))))
            else assertEquals("Primitive conversion depth limit exceeded", assertFailsWith<UnsupportedPlayerException> {
                runtime.expression(Unary("+", Lit(value)))
            }.message)
        }
    }
}
