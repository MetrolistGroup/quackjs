package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith

class QSemanticsTest {
    private fun evaluate(body: String): JsValue {
        val source = "(function(){$body})()"
        return Runtime(SourceIndex("g.C=function(){};", "C"))
            .expression(Parser(Lexer(source).tokens(), source).completeExpression())
    }

    @Test fun implicitErrorsExposeTheirNamesToCatchHandlers() {
        assertEquals("bc", evaluate("var x='abc';try{null.p}catch(e){return e.name==='TypeError'?x.slice(1):x.split('').reverse().join('')}"))
        for (operation in listOf("null.p", "undefined.p=1", "(0)()", "Number({valueOf:null,toString:null})", "[].forEach(null)", "'x'.includes(/x/)", "'x' in null")) {
            assertEquals("object:TypeError", evaluate("try{$operation}catch(e){return typeof e+':'+e.name}"), operation)
        }
        assertEquals("RangeError", evaluate("try{var a=[];a.length=-1}catch(e){return e.name}"))
    }

    @Test fun implicitErrorMessagesAndDefaultCoercionFailClosed() {
        for (inspection in listOf("String(e)", "String(e.message)", "String(e.stack)")) {
            assertFailsWith<UnsupportedPlayerException>(inspection) {
                evaluate("try{null.p}catch(e){try{return $inspection}catch(other){return 'fallback'}}")
            }
        }
        assertEquals("literal:string", evaluate("try{throw 'literal'}catch(e){return e+':'+typeof e}"))
        assertEquals("custom", evaluate("try{null.p}catch(e){e.toString=function(){return 'custom'};return String(e)}"))
    }

    @Test fun sliceSnapshotsLengthBeforeBothPositionConversions() {
        assertEquals("2:false:false:0", evaluate("var a=[1,2];var b=a.slice(0,{valueOf:function(){a.length=0;return 2}});return [b.length,0 in b,1 in b,a.length].join(':');"))
        assertEquals("1,2:1,2,3", evaluate("var a=[1,2];var b=a.slice({valueOf:function(){a.push(3);return 0}});return [b.join(','),a.join(',')].join(':');"))
        assertEquals("2:false:false", evaluate("var a=[1,2];var b=a.slice({valueOf:function(){a.length=0;return 0}});return [b.length,0 in b,1 in b].join(':');"))
    }

    @Test fun spliceUsesTheOriginalLengthAndLiveEntriesAfterCoercion() {
        assertEquals("1:false:3:,x,:false:false", evaluate("var a=[1,2,3];var b=a.splice({valueOf:function(){a.length=0;return 1}},1,'x');return [b.length,0 in b,a.length,a.join(','),0 in a,2 in a].join(':');"))
        assertEquals("1:false:2:,:false:false", evaluate("var a=[1,2,3];var b=a.splice(0,{valueOf:function(){a.length=0;return 1}});return [b.length,0 in b,a.length,a.join(','),0 in a,1 in a].join(':');"))
        assertEquals("0:3:1,x,2", evaluate("var a=[1,2];var b=a.splice(1,{valueOf:function(){a.push(3,4);return 0}},'x');return [b.length,a.length,a.join(',')].join(':');"))
        assertEquals("0:5:,,,x,y:false:true", evaluate("var a=[1,2,3];var b=a.splice({valueOf:function(){a.length=0;return 3}},0,'x','y');return [b.length,a.length,a.join(','),0 in a,3 in a].join(':');"))
    }

    @Test fun indexOfLimitsSearchToTheOriginalLengthAndSkipsMissingEntries() {
        assertEquals("-1", evaluate("var a=['a'];return String(a.indexOf('b',{valueOf:function(){a.push('b');return 0}}));"))
        assertEquals("-1", evaluate("var a=['a','b'];return String(a.indexOf('b',{valueOf:function(){a.length=0;return 1}}));"))
        assertEquals("-1", evaluate("var a=[1,undefined];return String(a.indexOf(undefined,{valueOf:function(){a.length=0;return 0}}));"))
        assertEquals("-1:0", evaluate("var a=[],n=0;return a.indexOf(1,{valueOf:function(){n++;return 0}})+':'+n;"))
    }

    @Test fun inIncludesSupportedBuiltinsAndDistinguishesHolesFromUndefined() {
        assertEquals("true:true:true:true:true", evaluate("return ['length' in [],'push' in [],'join' in [],'toString' in {},'valueOf' in {}].join(':');"))
        assertEquals("false:true:true:true:false:true", evaluate("var a=[,undefined];a.join=undefined;var o={x:undefined};return [0 in a,1 in a,'join' in a,'x' in o,'missing' in o,delete a.join&&'join' in a].join(':');"))
        assertEquals("cba", evaluate("var a='abc'.split('');if('length' in a)a.reverse();return a.join('');"))
        for (body in listOf("return String('constructor' in []);", "return String('__proto__' in {});", "return String('hasOwnProperty' in {});", "return String('map' in []);")) {
            assertFailsWith<UnsupportedPlayerException> { evaluate(body) }
        }
    }

    @Test fun radixNumbersRoundOnlyAfterTheCompleteIntegerIsParsed() {
        assertEquals("32:256", evaluate("return [Number('0x200000000000011')%64,Number('0x1000000000000081')>>>0].join(':');"))
        for ((digits, expected) in listOf(
            "20000000000001" to 9007199254740992.0,
            "20000000000003" to 9007199254740996.0,
            "1000000000000080" to 1152921504606846976.0,
            "1000000000000081" to 1152921504606847232.0,
        )) {
            val integer = digits.toLong(16)
            for ((prefix, radix) in listOf("0x" to 16, "0o" to 8, "0b" to 2)) {
                kotlin.test.assertEquals(expected, number(JsString(prefix + integer.toString(radix))), "$prefix$digits")
            }
        }
        kotlin.test.assertEquals(Double.POSITIVE_INFINITY, number(JsString("0x" + "f".repeat(256))))
        kotlin.test.assertTrue(number(JsString("0x" + "f".repeat(256) + "z")).isNaN())
    }
}
