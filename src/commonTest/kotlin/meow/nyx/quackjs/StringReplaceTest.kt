package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Shared with the independent JVM QuickJS oracle; these expected results run on Native too.
internal val replaceCases = listOf(
    "return 'a'.replace(/(a)/,'$12');" to "a2",
    "return 'a'.replace(/(a)/,'$01:$00:$0:$10:$99');" to "a:$00:$0:a0:$99",
    "return 'abcdefghijkl'.replace(/(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)/,'$12:$01:$09');" to "l:a:i",
    "return 'ab'.replace(/(a)?b/,'<$1:$2>');" to "<a:$2>",
    "return 'b'.replace(/(a)?b/,'<$1:$2:$01>');" to "<:$2:>",
    "return 'b'.replace(/(a)?b/,function(m,a,i,s){return typeof a+':'+i+':'+s});" to "undefined:0:b",
    "return 'abc'.replace('b',\"$$:$&:$`:$'\");" to "a$:b:a:cc",
    "return 'abc'.replace('b','$1:$01:$0');" to "a$1:$01:$0c",
    "return 'abc'.replace('','-');" to "-abc",
    "return ''.replace('','-');" to "-",
    "return 'abc'.replace('z','-');" to "abc",
    "return 'a,a'.replace('a','X');" to "X,a",
    "return 'a1b2'.replace(/([a-z])(\\d)/g,'$2$1');" to "1a2b",
    "return 'a1b2'.replace(/([a-z])(\\d)/g,function(m,l,n,i,s){return i+':'+l+n+':'+s+';'});" to "0:a1:a1b2;2:b2:a1b2;",
    "return 'A'.replace(/\\A/,'X');" to "X",
    "return '\\ud83d\\ude00'.replace(/./g,'X');" to "XX",
    "return '\\ud83d\\ude00'.replace(/(?:)/g,function(m,i){return String(i)});" to "0\ud83d1\ude002",
    "return 'ab'.replace(/(?:)/g,'-');" to "-a-b-",
    "return ''.replace(/(?:)/g,'-');" to "-",
    "return 'a'.replace(/a*/g,'-');" to "--",
    "return 'baa'.replace(/a*/g,'-');" to "-b--",
    "return 'aaa'.replace(/a+?/g,'X');" to "XXX",
    "return 'aaa'.replace(/a+/g,'X');" to "X",
    "return 'aaab'.replace(/a*ab/,'X');" to "X",
    "return 'aaab'.replace(/a*?ab/,'X');" to "X",
    "return 'aab'.replace(/a?ab/,'X');" to "X",
    "return 'ab'.replace(/(a)??b/,'<$1>');" to "<a>",
    "return 'ab'.replace(/(a)??/g,'<$1>');" to "<>a<>b<>",
    "return 'abc'.replace(/((a)b)c/,'$1:$2');" to "ab:a",
    "return 'b'.replace(/((a))?b/,function(m,a,b){return typeof a+':'+typeof b});" to "undefined:undefined",
    "return 'a'.replace(/()?/,function(m,a){return typeof a});" to "undefineda",
    "return 'a'.replace(/(^)?/g,function(m,a){return typeof a});" to "undefinedaundefined",
    "return ''.replace(/((())?)/,function(m,a,b,c){return a+':'+typeof b+':'+typeof c});" to ":undefined:undefined",
    "return 'a'.replace(/(?:)?/g,'-');" to "-a-",
    "return 'Aa_z'.replace(/[a-z]/gi,'X');" to "XX_X",
    "return 'Z[a'.replace(/[Z-a]/gi,'X');" to "XXX",
    "return 'aA'.replace(/a/i,'X');" to "XA",
    "return 'a\\n\\r\\u2028\\u2029z'.replace(/./g,'X');" to "X\n\r\u2028\u2029X",
    "return 'a\\n\\r\\u2028\\u2029z'.replace(/./gs,'X');" to "XXXXXX",
    "return 'a\\n\\r\\u2028\\u2029b'.replace(/[\\n\\r\\u2028\\u2029]/g,'X');" to "aXXXXb",
    "return 'a\\n'.replace(/a$/,'X');" to "a\n",
    "return 'a\\r\\n'.replace(/a$/m,'X');" to "X\r\n",
    "return '\\r\\n'.replace(/^$/gm,'X');" to "X\rX\nX",
    "return 'a\\u2028b\\u2029'.replace(/^./gm,'X');" to "X\u2028X\u2029",
    "return 'x1_!'.replace(/\\w/g,'X');" to "XXX!",
    "return 'x1_!'.replace(/[\\W\\d]/g,'X');" to "xX_X",
    "return 'a\\u00a0\\ufeff\\u001c'.replace(/\\s/g,'X');" to "aXX\u001c",
    "return '1a2'.replace(/[^\\d]/g,'X');" to "1X2",
    "return 'abc'.replace(/[]/g,'X');" to "abc",
    "return '\\n\\ud83d\\ude00'.replace(/[^]/g,'X');" to "XXX",
    "return '-]'.replace(/[\\]\\-]/g,'X');" to "XX",
    "return 'A/\\b\\u0000'.replace(/\\x41\\/[\\b]\\0/,'X');" to "X",
    "return '\\t\\n\\r\\v\\f'.replace(/[\\t\\n\\r\\v\\f]/g,'X');" to "XXXXX",
    "return '\\u0001A'.replace(/\\cA\\u0041/,'X');" to "X",
    "return '\\ud800x'.replace(/\\ud800/,'X');" to "Xx",
    "var n=0,o={toString:function(){n++;return String(n)}};return 'a,a'.replace(/a/g,o)+':'+n;" to "1,1:1",
    "var n=0,o={toString:function(){n++;return 'X'}};return 'a'.replace('z',o)+':'+n;" to "a:1",
    "var n=0,o={toString:function(){n++;return 'X'}};return 'a'.replace(/z/g,o)+':'+n;" to "a:1",
    "var order='',s={toString:function(){order+='s';return 'a'}},r={toString:function(){order+='r';return 'X'}};return 'a'.replace(s,r)+':'+order;" to "X:sr",
    "var n=0;return 'a,a'.replace(/a/g,function(){return {toString:function(){n++;return String(n)}}})+':'+n;" to "1,2:2",
    "var o={toString:function(){this.valueOf=function(){return 'X'};return {}}};return 'a'.replace('a',o);" to "X",
    "var n=0;return 'a'.replace('z',function(){n++;return 'X'})+':'+n;" to "a:0",
    "var a=[1];a.toString=function(){return 'a'};return 'ab'.replace(a,'X');" to "Xb",
    "return 'a'.replace('a',function(){return '$&'});" to "$&",
    "return 'undefined'.replace();" to "undefined",
    "return 'null'.replace(null,'X');" to "X",
    "return 'x'.replace('x',{name:'Error',message:'oops'});" to "[object Object]",
    "var e=new Error('oops');e.toString=function(){return 'custom'};return 'x'.replace('x',e);" to "custom",
    "return 'x'.replace(/z/g,function(){return new Error('oops')});" to "x",
    "return 'a'.replace('a',/b/ig);" to "/b/gi",
    "return 'a'.replace('a',/b/smig);" to "/b/gims",
    "return 'a'.replace('a',/[a/b]/mig);" to "/[a/b]/gim",
    "return 'a'.replace('a',/a\\/b/mi);" to "/a\\/b/im",
    "return 'a'.replace('a',/(?:)/ig);" to "/(?:)/gi",
    "return 'a'.replace(/a/g,function(){return /b/mi});" to "/b/im",
    "return 'a'.replace('z',/b/ig);" to "a",
    "return 'x'.replace.call(/b/ig,'b','c');" to "/c/gi",
    "return 'a'.replace('a',/$&/ig);" to "/a/gi",
    "return 'a'.replace('a',function(){return /$&/ig});" to "/$&/gi",
)

internal val errorReplaceCases = listOf(
    "return 'x'.replace('x',new Error('oops'));" to "Error: oops",
    "return 'x'.replace('x',new TypeError('oops'));" to "TypeError: oops",
    "return 'x'.replace('x',new RangeError('oops'));" to "RangeError: oops",
    "return 'x'.replace(/x/g,function(){return new Error('oops')});" to "Error: oops",
    "return 'x'.replace(/x/g,function(){return new TypeError('oops')});" to "TypeError: oops",
    "return 'x'.replace(/x/g,function(){return new RangeError('oops')});" to "RangeError: oops",
    "return 'x'.replace('z',new Error('oops'));" to "x",
    "return 'Error: oops'.replace(new Error('oops'),'X');" to "X",
    "return 'x'.replace.call(new Error('oops'),'Error','X');" to "X: oops",
    "var e=new TypeError('oops');delete e.name;delete e.message;return 'x'.replace('x',e);" to "TypeError",
)

internal val rawLineTerminatorRegexes = listOf("\n", "\r", "\r\n", "\u2028", "\u2029").flatMap { line ->
    listOf("/a${line}b/", "/a\\${line}b/", "/a[${line}]b/", "/a[\\${line}]b/")
}

internal fun evaluateReplace(body: String, runtime: Runtime = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))): String {
    val fn = runtime.expression(Parser(Lexer("(function(){$body})").tokens()).completeExpression())
    return runtime.invoke(fn, Undefined, emptyList()) as String
}

class StringReplaceTest {
    @Test fun supportedCasesMatchJavaScript() {
        for ((body, expected) in replaceCases) assertEquals(expected, evaluateReplace(body), body)
    }

    @Test fun defaultErrorCoercionFailsClosedThroughCatchAndFinally() {
        for ((body, _) in errorReplaceCases) {
            assertFailsWith<UnsupportedPlayerException>(body) {
                evaluateReplace("try{$body}catch(e){return 'caught'}finally{return 'fallback'}")
            }
        }
    }

    @Test fun copiedErrorsKeepTheirCoercionRestriction() {
        val source = SourceIndex("var g={};g.C=function(){};", "C")
        val expression = Parser(Lexer("new Error('oops')").tokens()).completeExpression()
        val template = Runtime(source).expression(expression)
        val runtime = Runtime(source, mapOf("error" to expression), mapOf("error" to template))
        assertFailsWith<UnsupportedPlayerException> { runtime.replace("x", "x", runtime.resolve("error")) }
    }

    @Test fun unsupportedAndMalformedPatternsFailClosedThroughCatchAndFinally() {
        for (pattern in listOf(
            "/(a)\\1/", "/a|b/", "/(?=a)/", "/(?<x>a)/", "/a{2}/", "/(a)+/", "/a*b*/",
            "/a/u", "/a/y", "/a/d", "/a/gg", "/[z-a]/", "/[\\d-a]/", "/(/", "/[a/",
            "/\\b/", "/\\B/", "/\\01/", "/\\xZZ/", "/\\u{41}/", "/a**/",
        )) {
            for (body in listOf(
                "return 'aaa'.replace($pattern,'X')",
                "return 'aaa'.replace('a',$pattern)",
                "return 'aaa'.replace(/a/g,function(){return $pattern})",
                "return 'aaa'.replace('z',$pattern)",
            )) assertFailsWith<UnsupportedPlayerException>(pattern) {
                evaluateReplace("try{$body}catch(e){return 'caught'}finally{return 'fallback'}")
            }
        }
        for ((pattern, input) in listOf("/a/i" to "\\u212a", "/\\u00e9/i" to "e", "/./i" to "\\ud83d\\ude00")) {
            assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return '$input'.replace($pattern,'X')") }
        }
    }

    @Test fun regexValuesCannotBypassValidationThroughLegacyCoercion() {
        for (replacement in listOf("[/b/ig]", "String(/b/ig)")) {
            assertEquals("/b/gi", evaluateReplace("return 'a'.replace('a',$replacement)"))
        }
        for (replacement in listOf("[/a/gg]", "String(/a/gg)")) {
            assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return 'a'.replace('a',$replacement)") }
        }
    }

    @Test fun rawRegexLineTerminatorsFailClosedEvenAfterBackslashes() {
        for (pattern in rawLineTerminatorRegexes) {
            val runtime = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
            assertFailsWith<UnsupportedPlayerException> { BoundedRegex(runtime, pattern) }
            for (body in listOf(
                "return 'a\\nb'.replace($pattern,'X')",
                "return 'a'.replace('a',$pattern)",
                "return 'a'.replace('a',function(){return $pattern})",
            )) assertFailsWith<UnsupportedPlayerException> {
                evaluateReplace("try{$body}catch(e){return 'caught'}finally{return 'fallback'}")
            }
        }
    }

    @Test fun regexSearchFinishesBeforeCallbacksRun() {
        val runtime = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
        var callbacks = 0
        val callback = Native { _, _, _ -> callbacks++; "X" }
        assertFailsWith<UnsupportedPlayerException> {
            runtime.replace("ab" + "a".repeat(4_000) + "c", RegexValue("/a*ab/g"), callback)
        }
        assertEquals(0, callbacks)
    }

    @Test fun searchBacktrackingOutputAndPatternBudgetsFailClosed() {
        val costly = "a".repeat(4_000) + "c"
        val error = assertFailsWith<UnsupportedPlayerException> { evaluateReplace("try{return '$costly'.replace(/a*ab/,'X')}catch(e){return 'caught'}") }
        assertTrue(error.message!!.contains("limit"))
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return '$costly'.replace('${"a".repeat(1_000)}b','X')") }
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return '${"a".repeat(1_000)}'.replace(/a/g,'$`')") }
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return 'a'.replace(/${"a".repeat(513)}/,'X')") }
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return 'a'.replace(/${"(".repeat(17)}a${")".repeat(17)}/,'X')") }
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return 'a'.replace(/${"a".repeat(129)}/,'X')") }
        val runtime = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
        runtime.tick(1_999_950)
        assertFailsWith<UnsupportedPlayerException> { evaluateReplace("return '${"a".repeat(100)}'.replace('a','X')", runtime) }
    }
}
