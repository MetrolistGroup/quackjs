package meow.nyx.quackjs

import com.dokar.quickjs.QuickJs
import java.math.BigInteger
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RadixNumberOracleTest {
    @Test fun radixRoundingMatchesJavaScriptAtTiesAndOverflow() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { thread ->
            runBlocking(thread) {
                QuickJs.create(thread).use { js ->
                    for (exponent in listOf(53, 54, 56, 57, 60, 63, 64, 127, 255, 511, 1022, 1023, 1024)) {
                        val power = BigInteger.ONE.shiftLeft(exponent)
                        val half = BigInteger.ONE.shiftLeft(exponent - 53)
                        for (offset in listOf(-half - BigInteger.ONE, -half, -BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE, half, half + BigInteger.ONE, half * BigInteger.valueOf(3), half * BigInteger.valueOf(3) + BigInteger.ONE)) {
                            for ((prefix, radix) in listOf("0x" to 16, "0o" to 8, "0b" to 2)) {
                                val value = prefix + (power + offset).toString(radix)
                                assertEquals(js.evaluate<Double>("Number('$value')"), number(JsString(value)), "$prefix at exponent $exponent with offset $offset")
                            }
                        }
                    }
                }
            }
        }
    }
}
