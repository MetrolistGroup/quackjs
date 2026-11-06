package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyntaxAuditTest {
    private fun compile(body: String = "return x;", extra: String = ""): PlayerProgram = PlayerProgram.compile("""
        var g={};var sig=function(x){$body};
        g.C=function(){this.V={};};
        g.C.prototype.set=function(k,v){this.V[k]=v;};
        g.C.prototype.get=function(k){apply(this);return this.V[k];};
        var apply=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
        $extra
    """.trimIndent(), PlayerHints("sig(INPUT)", "C", 1))

    @Test fun lineCommentsEndAtEveryLineTerminator() {
        for (end in listOf("\n", "\r", "\r\n", "\u2028", "\u2029")) {
            assertFailsWith<UnsupportedPlayerException> {
                compile("return letters[0]+x;", "var letters=['a']; // note${end}letters[0]='b';\n")
            }
            assertEquals("b!", compile("var a='a';// note${end}a='b';return a+x;").decipherSignature("!"))
        }
    }

    @Test fun templateInterpolationEffectsFailClosed() {
        for (effect in listOf("letters[0]='b'", "letters.reverse()", "unknown(letters)")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                compile("return letters[0]+x;", "var letters=['a'];var unused=`${'$'}{$effect}`;")
            }
            assertTrue(error.message!!.contains("Template interpolation"), error.message)
        }
        assertEquals("!", compile(extra = "var unused=`opaque \\${'$'}{text}`;").decipherSignature("!"))
    }

    @Test fun escapedIdentifiersFailBeforeIndexing() {
        for (name in listOf("\\u006cetters", "le\\u0074ters", "\\u{6c}etters")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                compile("return letters[0]+x;", "var letters=['a'];$name[0]='b';")
            }
            assertTrue(error.message!!.contains("Escaped identifiers"), error.message)
        }
        assertEquals("a!", compile("return letters[0]+x;", "var letters=['a'];/* \\u006c */var unused='\\u006c';").decipherSignature("!"))
    }

    @Test fun radixNumbersDoNotConsumeAdjacentAdditionOrSubtraction() {
        for ((expr, expected) in listOf("0x1e+2" to "32!", "0X1E-2" to "28!", "0b10+2" to "4!", "0o10-2" to "6!", "1e+2+2" to "102!", "1e-2*100" to "1!")) {
            assertEquals(expected, compile("return ($expr)+x;").decipherSignature("!"), expr)
        }
    }

    @Test fun regexOperandsRemainOpaqueInBothLexingPaths() {
        for (op in listOf("===", "!==", "==", "!=", "<", ">", "<=", ">=", "%", "/", "**", "<<", ">>", ">>>", "??", "+=", "=", "in", "instanceof")) {
            val source = "var unused=0 $op /[})]/;"
            val lexer = Lexer(source)
            val materialized = lexer.tokens()
            assertEquals(1, materialized.count { it.kind == 3 }, op)
            assertEquals("!", compile(extra = source).decipherSignature("!"), op)
        }
    }

    @Test fun regexAfterControlHeadersAndBlocksRemainsOpaque() {
        for (statement in listOf(
            "if(true)/[})]/.test('x');",
            "while(false)/[})]/.test('x');",
            "for(;false;)/[})]/.test('x');",
            "if(true){} /[})]/.test('x');",
            "if(true){}/[})]/.test('x');",
            "function unused(){} /[})]/.test('x');",
            "switch(0){case 0:{} /[})]/.test('x');}",
            "switch(0){case true?0:1:{} /[})]/.test('x');}",
            "switch(0){default:{} /[})]/.test('x');}",
            "try{}catch{} /[})]/.test('x');",
            "var unused=1\n{} /[})]/.test('x');",
            "var unused=1\nfunction other(){} /[})]/.test('x');",
        )) {
            assertEquals(1, Lexer(statement).tokens().count { it.kind == 3 }, statement)
            assertEquals("!", compile(extra = statement).decipherSignature("!"), statement)
        }
    }

    @Test fun divisionAfterExpressionBracesDoesNotHideMutations() {
        for (value in listOf("{}", "function(){}", "(function(){})", "({})")) {
            assertFailsWith<UnsupportedPlayerException>(value) {
                compile("return letters[0]+x;", "var letters=['a'];var unused=$value/1;letters[0]='b';/2/g;")
            }
            assertEquals(2, Lexer("var unused=$value/1;letters[0]='b';/2/g;").tokens().count { it.text == "/" || it.kind == 3 }, value)
        }
    }

    @Test fun keywordPropertyNamesDoNotChangeRegexContext() {
        for (name in listOf("in", "instanceof", "return", "function", "if", "while", "delete")) {
            val source = "var obj={};var letters=['a'];var unused=obj.$name(1)/1;letters[0]='b';/2/g;"
            assertEquals(2, Lexer(source).tokens().count { it.text == "/" || it.kind == 3 }, name)
            assertFailsWith<UnsupportedPlayerException>(name) { compile("return letters[0]+x;", source) }
            val property = "var obj={function:0};var unused=obj.$name/1;letters[0]='b';/2/g;"
            assertEquals(2, Lexer(property).tokens().count { it.text == "/" || it.kind == 3 }, name)
        }
    }

    @Test fun numericObjectKeysUseCanonicalNamesAndQuotedKeysStayLiteral() {
        for ((key, lookup) in listOf("0x10" to "16", "0X41" to "65", "0b10" to "2", "0o10" to "8", "1e1" to "10", "1.0" to "1", ".0" to "0", "1e-0" to "1")) {
            assertEquals("abc", compile("return ({$key:x})[$lookup]||'wrong';").decipherSignature("abc"), key)
        }
        assertEquals("abc", compile("return ({'0x10':x})['0x10'];").decipherSignature("abc"))
        for (key in listOf("1.5", "1e21", "01")) {
            assertFailsWith<UnsupportedPlayerException>(key) { compile("return ({$key:x})['$key'];") }
        }
    }

    @Test fun postfixUpdatesRespectLineTerminatorsIncludingComments() {
        for (gap in listOf("\n", "\r", "\u2028", "\u2029", "/*\n*/", "// note\r")) {
            assertEquals("1:2:abc", compile("var a=1,b=1;a${gap}++b;return a+':'+b+':'+x;").decipherSignature("abc"))
            assertEquals("1:0:abc", compile("var a=1,b=1;a${gap}--b;return a+':'+b+':'+x;").decipherSignature("abc"))
        }
        assertEquals("2:1:abc", compile("var a=1,b=1;a/*same line*/++;b;return a+':'+b+':'+x;").decipherSignature("abc"))
    }

    @Test fun returnRespectsLineTerminatorsIncludingComments() {
        for (gap in listOf("\n", "\r", "\u2028", "\u2029", "/*\n*/", "// note\r")) {
            assertEquals("abc", compile("var f=function(){return${gap}'bad';};return f()===undefined?x:'wrong';").decipherSignature("abc"))
        }
        assertEquals("badabc", compile("var f=function(){return/*same line*/'bad';};return f()+x;").decipherSignature("abc"))
    }

    @Test fun otherRestrictedStatementsRespectLineTerminators() {
        for (gap in listOf("\n", "\r", "\u2028", "\u2029", "/*\n*/", "// note\r")) {
            assertEquals("11:abc", compile("var target=0,a=0;target:while(true){while(true){a++;break${gap}target;}a+=10;break;}return a+':'+x;").decipherSignature("abc"))
            assertEquals("24:abc", compile("var target=0,a=0;target:for(var i=0;i<2;i++){for(var j=0;j<2;j++){a++;continue${gap}target;}a+=10;}return a+':'+x;").decipherSignature("abc"))
            assertFailsWith<UnsupportedPlayerException> { compile("throw${gap}x;") }
        }
    }

    @Test fun functionDeclarationsInStatementsFailClosed() {
        for (body in listOf("function helper(s){return s.split('').reverse().join('')}return typeof helper==='function'?helper(x):x;", "if(true){function helper(s){return s}}return x;", "function helper(s){return s.split('').reverse().join('')}return helper(x);")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                compile(body, "var helper=function(s){return s.slice(1)};")
            }
            assertTrue(error.message!!.contains("Function declarations"), error.message)
        }
        assertEquals("cba", compile("var helper=function local(s){return s.split('').reverse().join('')};return helper(x);").decipherSignature("abc"))
        assertEquals("abc", compile("'function';return x;").decipherSignature("abc"))
    }
}
