package meow.nyx.quackjs

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test

class MathOracleTest {
    @Test
    fun mathCasesMatchIndependentJavaScriptIncludingSignedZeros() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { thread ->
            runBlocking(thread) {
                QuickJs.create(thread).use { js ->
                    for ((expression, _) in mathCases) {
                        assertMathResult(js.evaluate<Double>(expression), evaluateMath(expression), expression)
                    }
                }
            }
        }
    }
}
