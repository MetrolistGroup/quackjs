package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenlessIndexTest {
    private fun index(extra: String): SourceIndex = SourceIndex("var g={};g.C=function(){};$extra", "C")

    @Test fun cursorPreservesOffsetsDecodedStringsAndOpaqueLiterals() {
        val source = "/*skip*/ x='a\\x62\\u0063\\\r\nz';return /[\\/]x/g;`opaque \\${'$'}{text}`;ā;é;>>>="
        val lexer = Lexer(source)
        val expected = listOf(
            "x" to 0, "=" to 0, "abcz" to 1, ";" to 0, "return" to 0,
            "/[\\/]x/g" to 3, ";" to 0, "`opaque \\${'$'}{text}`" to 4,
            ";" to 0, "ā" to 0, ";" to 0, "é" to 0, ";" to 0, ">>>=" to 0, "<eof>" to -1,
        )
        var regexAllowed = true
        var previousEnd = 0
        for ((text, kind) in expected) {
            lexer.scan(regexAllowed)
            assertEquals(kind, lexer.kind)
            assertEquals(text, lexer.text)
            assertTrue(lexer.offset >= previousEnd)
            assertEquals(Token(text, kind, lexer.offset), lexer.materialize())
            if (text == "ā") assertEquals(ScanTags.IDENTIFIER, lexer.tag)
            previousEnd = lexer.end
            regexAllowed = lexer.regexAfter
        }
        assertEquals(source.length, lexer.position)
        assertEquals(-1, Lexer("").next().kind)
    }

    @Test fun regexContextTagsMatchEveryPrefixAndRejectSimilarIdentifiers() {
        val prefixes = listOf("=", "(", "[", ",", ":", ";", "return", "case", "else", "!", "&&", "||", "?", "{", "}", "+", "-", "*", "^", "&", "|", "throw", "==", "??", "delete")
        val values = listOf("returned", "elsewhere", "throwing", "ā", "x", "1", "'return'", ")", "]", "++", "--", "?.", "function")
        for (prefix in prefixes + values) {
            val lexer = Lexer("$prefix /x/")
            lexer.scan()
            lexer.scan(lexer.regexAfter)
            assertEquals(if (prefix in prefixes) 3 else 0, lexer.kind, prefix)
        }
    }

    @Test fun irrelevantMalformedLiteralsStillFailDuringScan() {
        for (literal in listOf("'\\xG0'", "'\\u123'", "'\\01'", "'\\7'", "'unterminated", "/unterminated", "`unclosed", "`x${'$'}{1", "/*unclosed")) {
            assertFailsWith<UnsupportedPlayerException>(literal) { index("var unused=$literal;") }
        }
        assertFailsWith<UnsupportedPlayerException> { index("var unused=" + "`x${'$'}{".repeat(65) + "0" + "}`".repeat(65) + ";") }
        assertFailsWith<UnsupportedPlayerException> { index("(".repeat(513) + "0" + ")".repeat(513) + ";") }
        assertFailsWith<UnsupportedPlayerException> { index("0;".repeat(1_000_001)) }
    }

    @Test fun retainedTargetsDecodeKeysAndKeepAliasAndMutationProofs() {
        val padding = "0;'unrelated';".repeat(99)
        val source = index("var letters=['é',''];var alias=letters;${padding}alias['\\x30']='bad';")
        assertFailsWith<UnsupportedPlayerException> { source.definition("letters") }
        val safe = index("var letters=['é',''];${padding}Array['isArray'](letters);")
        assertEquals(listOf(Lit("é"), Lit("")), (safe.definition("letters") as ArrayExpr).items)
        for (update in listOf("++alias[0]", "delete alias[0]", "alias[0]+='x'", "unknown(alias)", "(0,unknown)(alias)")) {
            val changed = index("var letters=['é'];var alias=letters;${padding}$update;")
            assertFailsWith<UnsupportedPlayerException>(update) { changed.definition("letters") }
        }
    }

    @Test fun tokenlessEffectsKeepArrayLengthAndNativePredicateGuards() {
        val safe = index("var letters=['é'];var alias=letters;unknown(alias['length']);Array.isArray(alias);")
        assertTrue(safe.definition("letters") is ArrayExpr)
        val changed = index("var letters=['é'];var alias=letters;var Native=Array;Native.isArray=function(x){x[0]='bad'};Array.isArray(alias);")
        assertFailsWith<UnsupportedPlayerException> { changed.definition("letters") }
    }
    @Test fun compiledCallsKeepLiveAliasesAcrossBoundaryAndUnicodeInputs() {
        val program = PlayerProgram.compile("""
            var g={};var letters=['é',''];
            var sig=function(x){var alias=letters;if(x.length==2)alias[0]='雪';return letters.join('')+x;};
            g.C=function(){this.V={};};
            g.C.prototype.set=function(k,v){this.V[k]=v;};
            g.C.prototype.get=function(k){apply(this);return this.V[k];};
            var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
        """.trimIndent(), PlayerHints("sig(INPUT)", "C", 1))
        for (input in listOf("", "a", "bc", "雪☃", "\ud800", "x".repeat(16_384))) {
            assertEquals((if (input.length == 2) "雪" else "é") + input, program.decipherSignature(input))
            assertEquals(input.reversed(), program.transformN(input))
        }
        assertEquals("éz", program.decipherSignature("z"))
        assertFailsWith<UnsupportedPlayerException> { program.decipherSignature("x".repeat(16_385)) }
    }

}
