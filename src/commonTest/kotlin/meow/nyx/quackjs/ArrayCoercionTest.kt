package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArrayCoercionTest {
    private fun evaluate(body: String): JsValue {
        val source = "function(){$body}"
        val runtime = Runtime(SourceIndex("g.C=function(){};", "C"))
        val code = Parser(Lexer(source).tokens(), source).completeExpression() as FunctionExpr
        return runtime.invoke(runtime.function(code), Undefined, emptyList())
    }

    @Test fun deepCoercionFailsClosedWithoutOverflowingTheHostStack() {
        for (conversion in listOf("String(a)", "String(Number(a))", "a.join('')")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                evaluate("var a=['x'];for(var i=0;i<12000;i++)a=[a];try{return $conversion}catch(e){return 'caught'}")
            }
            assertTrue(error.message!!.contains("limit"), error.message)
        }
        assertEquals("x", evaluate("var a=['x'];for(var i=0;i<8;i++)a=[a];return String(a);"))
    }

    @Test fun joinSnapshotsLengthBeforeSeparatorAndReadsLiveEntriesSafely() {
        assertEquals("x,", evaluate("var a=[{toString:function(){a.length=0;return 'x'}},2];return a.join(',');"))
        assertEquals(",", evaluate("var a=[1,2];return a.join({toString:function(){a.length=0;return ','}});"))
        assertEquals("1:2", evaluate("var a=[1,2];return a.join({toString:function(){a.push(3);return ':'}});"))
        assertEquals("x,changed,", evaluate("var a=[{toString:function(){a[1]='changed';delete a[2];a.push(4);return 'x'}},2,3];return a.join(',');"))
    }

    @Test fun methodLookupFollowsEarlierConversionSideEffects() {
        assertEquals("7", evaluate("var o={toString:function(){this.valueOf=function(){return 7};return {}}};return [o].join('');"))
        assertEquals("9", evaluate("var o={valueOf:function(){this.toString=function(){return '9'};return {}}};return String(Number(o));"))
        assertEquals("7", evaluate("return String({toString:3,valueOf:function(){return 7}});"))
        assertEquals("caught", evaluate("try{return String({toString:null,valueOf:null})}catch(e){return 'caught'}"))
    }

    @Test fun rootAndNestedOverridesUseOrdinaryConversionHints() {
        assertEquals("12", evaluate("var a=[function(){}];a.valueOf=function(){return 12};return String(Number(a));"))
        assertEquals("12:12:12", evaluate("var a=[1];a.join=function(){return '12'};return [String(a),String([a]),a.toString()].join(':');"))
        assertEquals("13:vt", evaluate("var log='',a=[1];a.valueOf=function(){log+='v';return {}};a.toString=function(){log+='t';return '13'};return Number(a)+':'+log;"))
        assertEquals("NaN:x", evaluate("var log='',a=[{toString:function(){log+='x';return '1'}},function(){}];return Number(a)+':'+log;"))
        assertEquals("14:x", evaluate("var log='',a=[function(){}],inner=[1];inner.join=function(){log+='x';return '14'};a[0]=inner;return Number(a)+':'+log;"))
    }

    @Test fun relationalConversionUsesNumberHintAndSupportedDateTimes() {
        assertEquals("true:true:true:true:ababbaba", evaluate("""
            var log='',a={valueOf:function(){log+='a';return 2},toString:function(){throw 'wrong hint'}},
                b={valueOf:function(){log+='b';return 3}};
            var values=[a<b,a<=b,b>a,b>=a];return values.join(':')+':'+log;
        """))
        assertEquals("true:true:true:true", evaluate("var d=new Date('1970-01-01T00:00:00Z');return [d<1,d<=0,1>d,0>=d].join(':');"))
        assertEquals("true:false", evaluate("return [['2']<['3'],['2']<['12']].join(':');"))
    }

    @Test fun holesNullsAndCyclesKeepJoinSemanticsIncludingReentrantJoins() {
        assertEquals("1,,,2,3", evaluate("return String([1,,null,[2,3]]);"))
        assertEquals("1,,2", evaluate("var a=[1];a.push(a,2);return String(a);"))
        assertEquals("1,2,,3,2,,3", evaluate("var a=[1],b=[2,a,3];a.push(b,b);return String(a);"))
        assertEquals(",2", evaluate("var a=[{toString:function(){return a.join()}},2];return String(a);"))
        assertEquals("caught:1", evaluate("var a=[{toString:function(){throw 'oops'}}],out='';try{a.join()}catch(e){out='caught'}a[0]=1;return out+':'+a.join();"))
    }

    @Test fun conversionAllocationsAndTraversalRemainBounded() {
        for (body in listOf(
            "var a=Array(65536);for(var i=0;i<10000;i++)a.join('');return '';",
            "var a=[function(){}];for(var i=0;i<10000;i++){a=[a,a];Number(a)}return '';",
            "return [function(){/*${"x".repeat(65_536)}*/}].join('');",
            "return ['${"x".repeat(65_536)}','x'].join('');",
        )) {
            assertTrue(assertFailsWith<UnsupportedPlayerException> { evaluate(body) }.message!!.contains("limit"))
        }
        assertEquals("NaN", evaluate("return String(Number([function(){/*${"x".repeat(65_536)}*/}]));"))
        assertEquals("NaN", evaluate("return String(Number([/a|b/]));"))
        assertEquals("12", evaluate("var a=[/a|b/];a.valueOf=function(){return 12};return String(Number(a));"))
        assertFailsWith<UnsupportedPlayerException> { evaluate("return String(Number([function(){},new Date('1970-01-01T00:00:00Z')]));") }
    }
}
