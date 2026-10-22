package meow.nyx.quackjs

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StringReplaceOracleTest {
    @Test fun supportedReplacementSubsetMatchesIndependentQuickJs() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { thread ->
            runBlocking(thread) {
                QuickJs.create(thread).use { js ->
                    for ((body, expected) in replaceCases) {
                        val oracle = js.evaluate<String>("(function(){$body})()")
                        assertEquals(expected, oracle, body)
                        assertEquals(oracle, evaluateReplace(body), body)
                    }
                    for ((body, expected) in errorReplaceCases) {
                        assertEquals(expected, js.evaluate<String>("(function(){$body})()"), body)
                        assertFailsWith<UnsupportedPlayerException>(body) { evaluateReplace(body) }
                    }
                    for (pattern in listOf("/a/gg", "/a/q", "/[z-a]/") + rawLineTerminatorRegexes) {
                        assertFailsWith<QuickJsException> { js.evaluate<String>("'a\\nb'.replace($pattern,'X')") }
                        val body = "return 'a'.replace('a',$pattern)"
                        assertFailsWith<QuickJsException> { js.evaluate<String>("(function(){$body})()") }
                        assertFailsWith<UnsupportedPlayerException> { evaluateReplace(body) }
                    }
                    val patterns = listOf("a", ".", "[a-z]", "[^a]", "[Z-a]", "[\\\\d_a]", "\\\\A", "(a)", "(a)?b", "((a)b)?", "()?", "(^)?", "((())?)", "(?:)?", "(?:)", "^", "${'$'}", "a*", "a*?", "a+", "a?", "a*ab", "(a*)b")
                    val inputs = listOf("", "a", "b", "ab", "aaab", "baaba", "AaZ[z1_", "a\\\\n", "\\\\r\\\\n", "a\\\\u2028b")
                    for (pattern in patterns) for (input in inputs) for (flags in listOf("g", "gi", "gm", "gs")) {
                        val body = "return '$input'.replace(/$pattern/$flags,\"<$&:$1:$01:$12:$`:$'>\");"
                        assertEquals(js.evaluate<String>("(function(){$body})()"), evaluateReplace(body), body)
                    }
                }
            }
        }
    }
}
