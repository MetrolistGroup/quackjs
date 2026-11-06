package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LexerContextStorageTest {
    @Test fun operatorsKeepTheirLongestMatchAndTags() {
        for (operator in listOf(
            ">>>=", "&&=", "||=", "??=", "===", "!==", ">>>", "<<=", ">>=", "**=", "=>",
            "==", "!=", "<=", ">=", "++", "--", "&&", "||", "??", "<<", ">>", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "**", "?.", "...",
        )) {
            val lexer = Lexer("x $operator 2")
            lexer.scan()
            lexer.scan(lexer.regexAfter)
            assertEquals(operator, lexer.text)
            assertEquals(ScanTags.code(operator, 0, operator.length), lexer.tag, operator)
            assertEquals(2 + operator.length, lexer.position)
        }
    }

    @Test fun asciiClassificationKeepsUnicodeLettersAndDigits() {
        val tokens = Lexer("āé0 foo\u0301 ١٢ \u2003©").tokens()
        assertEquals(listOf("āé0" to 0, "foo" to 0, "\u0301" to 0, "١٢" to 2, "©" to 0, "<eof>" to -1), tokens.map { it.text to it.kind })
        val error = assertFailsWith<UnsupportedPlayerException> { Lexer("'\\0١'").tokens() }
        assertEquals("Legacy octal string escape", error.message)
    }

    @Test fun contextStorageGrowsAndKeepsTheNestingLimit() {
        val tokens = Lexer("(".repeat(1024) + "x" + ")".repeat(1024) + "/2;").tokens()
        assertEquals(0, tokens.count { it.kind == 3 })
        assertEquals(1, tokens.count { it.text == "/" })
        val error = assertFailsWith<UnsupportedPlayerException> { Lexer("(".repeat(1025)).tokens() }
        assertEquals("Source nesting limit exceeded", error.message)
    }

    @Test fun questionsGrowAndClosingBracketsDiscardInnerQuestions() {
        val lexer = Lexer("?".repeat(1100) + ":".repeat(1100) + "{} /2;")
        assertEquals(0, lexer.tokens().count { it.kind == 3 })
        for (source in listOf(
            "switch(x){case (x?y): {} /a/;}",
            "switch(x){case [x?y]: {} /a/;}",
            "switch(x){case x?y?z:a:b: {} /a/;}",
        )) assertEquals(1, Lexer(source).tokens().count { it.kind == 3 }, source)
    }

    @Test fun caseHeadersAndFunctionFlagsStayWithTheirBrackets() {
        for ((source, regexes) in listOf(
            "switch(x){case (x?y:z): {} /a/; default: {switch(y){case 1: {} /b/;}} /c/;}" to 3,
            "switch(x){case 0:{} /a/;} var x={default:0}/2;" to 1,
            "function f(){} /a/;var f=function(){} /2;var g=()=>{} /2;" to 1,
            "ā: {} /a/;" to 1,
        )) assertEquals(regexes, Lexer(source).tokens().count { it.kind == 3 }, source)
    }

    @Test fun decodingStringsPreservesTheCursorAndContext() {
        val lexer = Lexer("switch(x){case 'a\\x62\\u0063':\n{} /a/;}")
        while (true) {
            lexer.scan(lexer.regexAfter)
            if (lexer.kind == 1) break
        }
        val position = lexer.position
        assertEquals("abc", lexer.text)
        assertEquals(position, lexer.position)
        assertFalse(lexer.regexAfter)
        assertEquals("abc", lexer.materialize().text)
        assertEquals(listOf(":", "{", "}", "/a/", ";", "}", "<eof>"), lexer.tokens().map { it.text })
    }
}
