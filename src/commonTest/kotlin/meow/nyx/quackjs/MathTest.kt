package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal val mathCases =
    listOf(
        "Math.pow(2, 3)" to 8.0,
        "Math.pow(-2, 3)" to -8.0,
        "Math.pow(-2, 0.5)" to Double.NaN,
        "Math.pow(NaN, 0)" to 1.0,
        "Math.pow(1, NaN)" to Double.NaN,
        "Math.pow(-1, Infinity)" to Double.NaN,
        "Math.pow(1, -Infinity)" to Double.NaN,
        "Math.pow(-0, 3)" to -0.0,
        "Math.pow(-0, -3)" to Double.NEGATIVE_INFINITY,
        "Math.pow(-Infinity, -3)" to -0.0,
        "Math.pow(-Infinity, 0.5)" to Double.POSITIVE_INFINITY,
        "Math.pow('2', true)" to 2.0,
        "Math.pow(null, null)" to 1.0,
        "Math.pow()" to Double.NaN,
        "(-1) ** Infinity" to Double.NaN,
        "1 ** NaN" to Double.NaN,
        "NaN ** 0" to 1.0,
        "Math.round(2.5)" to 3.0,
        "Math.round(-1.5)" to -1.0,
        "Math.round(-0.5)" to -0.0,
        "Math.round(-0.1)" to -0.0,
        "Math.round(-0)" to -0.0,
        "Math.round(0.49999999999999994)" to 0.0,
        "Math.round(-0.5000000000000001)" to -1.0,
        "Math.round(4503599627370495.5)" to 4503599627370496.0,
        "Math.round(Infinity)" to Double.POSITIVE_INFINITY,
        "Math.round(-Infinity)" to Double.NEGATIVE_INFINITY,
        "Math.round('2.5')" to 3.0,
        "Math.round()" to Double.NaN,
        "Math.sign(-0)" to -0.0,
        "Math.sign(0)" to 0.0,
        "Math.sign(-Infinity)" to -1.0,
        "Math.sign(Infinity)" to 1.0,
        "Math.sign('')" to 0.0,
        "Math.sign()" to Double.NaN,
        "Math.imul(0xffffffff, 5)" to -5.0,
        "Math.imul(0x80000000, -1)" to -2147483648.0,
        "Math.imul(4294967297, 3)" to 3.0,
        "Math.imul(-1.9, 2.9)" to -2.0,
        "Math.imul(Infinity, 3)" to 0.0,
        "Math.imul()" to 0.0,
        "Math.clz32(0)" to 32.0,
        "Math.clz32(-1)" to 0.0,
        "Math.clz32(1)" to 31.0,
        "Math.clz32(4294967297)" to 31.0,
        "Math.clz32()" to 32.0,
        "Math.log(1)" to 0.0,
        "Math.log(-0)" to Double.NEGATIVE_INFINITY,
        "Math.log(-1)" to Double.NaN,
        "Math.log(Infinity)" to Double.POSITIVE_INFINITY,
        "Math.log()" to Double.NaN,
        "Math.log2(8)" to 3.0,
        "Math.log2(-0)" to Double.NEGATIVE_INFINITY,
        "Math.log2(-1)" to Double.NaN,
        "Math.sqrt(4)" to 2.0,
        "Math.sqrt(-0)" to -0.0,
        "Math.sqrt(-1)" to Double.NaN,
        "Math.sqrt(Infinity)" to Double.POSITIVE_INFINITY,
        "Math.sqrt()" to Double.NaN,
        "Math.exp(0)" to 1.0,
        "Math.exp(-Infinity)" to 0.0,
        "Math.exp(Infinity)" to Double.POSITIVE_INFINITY,
        "Math.exp()" to Double.NaN,
        "Math.E" to 2.718281828459045,
        "Math.LN10" to 2.302585092994046,
        "Math.LN2" to 0.6931471805599453,
        "Math.LOG10E" to 0.4342944819032518,
        "Math.LOG2E" to 1.4426950408889634,
        "Math.PI" to 3.141592653589793,
        "Math.SQRT1_2" to 0.7071067811865476,
        "Math.SQRT2" to 1.4142135623730951,
    )

internal fun evaluateMath(expression: String): Double =
    Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
        .expression(Parser(Lexer(expression).tokens()).completeExpression()) as Double

internal fun assertMathResult(
    expected: Double,
    actual: Double,
    expression: String,
) {
    if (expected.isNaN()) {
        assertTrue(actual.isNaN(), expression)
    } else {
        assertEquals(expected.toBits(), actual.toBits(), expression)
    }
}

class MathTest {
    @Test
    fun mathPreservesJavaScriptCoercionRoundingAndSignedZeros() {
        for ((expression, expected) in mathCases) {
            assertMathResult(expected, evaluateMath(expression), expression)
        }
    }

    @Test
    fun randomRemainsUnsupportedUnlessAPlayerNeedsIt() {
        assertFailsWith<UnsupportedPlayerException> { evaluateMath("Math.random()") }
    }
}
