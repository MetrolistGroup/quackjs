package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AbstractCoercionTest {
    private fun evaluate(body: String): JsValue {
        val source = "function(){$body}"
        val runtime = Runtime(SourceIndex("g.C=function(){};", "C"))
        val code = Parser(Lexer(source).tokens(), source).completeExpression() as FunctionExpr
        return runtime.invoke(runtime.function(code), Undefined, emptyList())
    }

    @Test fun equalityUsesDefaultHintWithoutCoercingIdentityOrNullComparisons() {
        assertEquals("true:true:true:true:true:false:false:false:vv", evaluate("""
            var log='',o={valueOf:function(){log+='v';return 2},toString:function(){throw 'wrong hint'}};
            return [o==2,'2'==o,[]==false,({})=='[object Object]',o==o,o=={},o==null,o==='2'].join(':')+':'+log;
        """))
        assertEquals("true:true:false", evaluate("return [({valueOf:function(){return {}},toString:function(){return '4'}})==4,({valueOf:0,toString:function(){return '0'}})==false,({valueOf:function(){return null}})==0].join(':');"))
        assertEquals("caught", evaluate("try{return String(({valueOf:null,toString:null})==2)}catch(e){return 'caught'}"))
        assertFailsWith<UnsupportedPlayerException> { evaluate("return String(new Date('1970-01-01T00:00:00Z')==0);") }
    }

    @Test fun assignmentReferencesConvertObjectKeysOnceBeforeRhsEffects() {
        for ((operation, expected) in listOf("=rhs()" to "kr:7:7", "+=rhs()" to "kr:10:10", "++" to "k:3:4", "--" to "k:3:2")) {
            assertEquals(expected, evaluate("""
                var log='',o={v:3},key={toString:function(){log+='k';return 'v'},valueOf:function(){throw 'wrong hint'}},
                    rhs=function(){log+='r';key.toString=function(){throw 'too late'};return 7};
                var result=o[key]$operation;return [log,result,o.v].join(':');
            """), operation)
        }
        assertEquals("1:4:3", evaluate("var n=0,a=[3,9],key={toString:function(){n++;return '0'}};var old=a[key]++;return [n,a[0],old].join(':');"))
        assertEquals("k", evaluate("var log='',o={},key={toString:function(){log+='k';throw 'bad'}},rhs=function(){log+='r';return 1};try{o[key]=rhs()}catch(e){return log}"))
        for ((operation, expected) in listOf("null[key]" to "", "null[key]+=rhs()" to "", "null[key]++" to "", "null[key]=rhs()" to "r")) {
            assertEquals(expected, evaluate("var log='',key={toString:function(){log+='k';return 'v'}},rhs=function(){log+='r';return 1};try{$operation}catch(e){return log}"), operation)
        }
    }

    @Test fun propertyReadsCallsDeletionAndInUseTheSameStringHint() {
        assertEquals("7:7:true:true:4", evaluate("""
            var n=0,key={toString:function(){n++;return 'v'},valueOf:function(){throw 'wrong hint'}},o={v:7},
                f={v:function(){return this.value},value:7};
            var read=o[key],call=f[key](),present=key in o,removed=delete o[key];
            return [read,call,present,removed,n].join(':');
        """))
        assertEquals("caught:0", evaluate("var n=0,key={toString:function(){n++;return 'x'}};try{return String(key in null)}catch(e){return 'caught:'+n}"))
    }

    @Test fun stringBuiltinsCoerceReceiversArgumentsAndPositionsInOrderOnce() {
        assertEquals("a|b:rls", evaluate("""
            var log='',receiver={toString:function(){log+='r';return 'a,b,c'}},separator={toString:function(){log+='s';return ','}},
                limit={valueOf:function(){log+='l';separator.toString=function(){log+='s';return ','};return 2}};
            return ''.split.call(receiver,separator,limit).join('|')+':'+log;
        """))
        assertEquals("0:ls", evaluate("var log='',sep={toString:function(){log+='s';return ','}},limit={valueOf:function(){log+='l';return 0}};return 'a,b'.split(sep,limit).length+':'+log;"))
        for (method in listOf("indexOf", "lastIndexOf", "startsWith", "endsWith", "includes")) {
            val result = when (method) { "indexOf", "lastIndexOf" -> "1"; "endsWith" -> "false"; else -> "true" }
            assertEquals("$result:rsp", evaluate("""
                var log='',receiver={toString:function(){log+='r';return 'abc'}},search={toString:function(){log+='s';return 'b'}},
                    position={valueOf:function(){log+='p';return 1}};
                return String(''.$method.call(receiver,search,position))+':'+log;
            """), method)
        }
        assertEquals("oops:oops", evaluate("var o={toString:function(){return 'oops'}};return Error(o).message+':'+new TypeError(o).message;"))
        assertEquals("b:2:2", evaluate("var n=0,o={toString:function(){n++;return 'abc'}};return ''.charAt.call(o,1)+':'+''.slice.call(o,1).length+':'+n;"))
    }

    @Test fun newlySharedConversionsStillEnforceBudgetsAndUnsupportedOperations() {
        for (operation in listOf("({valueOf:function(){return digits}})==0", "({})[key]", "digits.indexOf(key)", "String(key)")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                evaluate("var digits='${"1".repeat(65_536)}',key={toString:function(){return digits}};for(var i=0;i<10000;i++){$operation}return '';")
            }
            assertTrue(error.message!!.contains("limit"), error.message)
        }
        for (expression in listOf("String(new Date('1970-01-01T00:00:00Z'))", "String(function(){}.toString())", "'a'.split(/a/)", "'a'.includes(/a/)")) {
            // Regex search rejection is a JavaScript TypeError; regex execution remains unsupported.
            if (expression.endsWith("includes(/a/)")) assertEquals("caught", evaluate("try{return $expression}catch(e){return 'caught'}"))
            else assertFailsWith<UnsupportedPlayerException> { evaluate("try{return $expression}catch(e){return 'caught'}") }
        }
    }
}
