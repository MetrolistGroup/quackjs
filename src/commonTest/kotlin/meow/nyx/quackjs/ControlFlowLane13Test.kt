package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ControlFlowLane13Test {
    private fun run(body: String): Any? {
        val runtime = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
        return testHostValue(runtime.expression(Parser(Lexer("(function(){$body})()").tokens()).completeExpression()))
    }

    @Test fun returnsPreserveFalsyValuesAndObjectIdentity() {
        for ((value, expected) in listOf(
            "null" to null, "undefined" to Undefined, "false" to false,
            "0" to 0.0, "''" to "", "'value'" to "value",
        )) {
            assertEquals(expected, run("return $value;throw 'unreachable';"), value)
            assertEquals(expected, run("outer:{while(true){try{switch(1){case 1:if(true)return $value;}}finally{}}}throw 'unreachable';"), value)
        }
        assertSame(Undefined, run("return;throw 'unreachable';"))
        assertSame(Undefined, run("var unused=null;"))
        assertTrue((run("return NaN;") as Double).isNaN())
        for (value in listOf("{}", "[]", "function(){return null}", "String")) {
            assertEquals(true, run("var value=$value,f=function(){return value};return f()===value;"), value)
        }
    }

    @Test fun nestedAndReusedCallbackCallsKeepTheirOwnResults() {
        assertEquals("3:2:1:null", run("""
            var f=function(n){
                if(n==0)return null;
                try{return n+':'+String(f(n-1));}finally{f(0);}
            };
            return f(3);
        """))
        assertEquals("1:null:3", run("""
            var out=[],f=function(v){if(v==2)return null;try{return v;}finally{(function(){return 'other';})();}};
            [1,2,3].forEach(function(v){out.push(String(f(v)));return false;});
            return out.join(':');
        """))
    }

    @Test fun normalFinalizersPreservePendingReturns() {
        assertNull(run("try{return null;}finally{(function(){return 'ignored';})();}"))
        assertEquals("saved", run("""
            try{return 'saved';}finally{
                while(true){try{return 'discarded';}finally{break;}}
                try{throw 'ignored';}catch(e){(function(){return e;})();}
            }
        """))
        assertEquals("caught", run("try{throw 'caught';}catch(e){return e;}finally{(function(){return null;})();}"))
        assertNull(run("try{throw 'caught';}catch(e){return null;}finally{var unused=false;}"))
    }

    @Test fun abruptFinalizersOverrideReturnsAndThrows() {
        assertEquals(false, run("try{return null;}finally{return false;}"))
        assertNull(run("try{throw 'pending';}finally{return null;}"))
        assertSame(Undefined, run("try{throw 'pending';}catch(e){throw 'second';}finally{return;}"))
        assertEquals("override", assertFailsWith<JsThrown> {
            run("try{return null;}finally{throw 'override';}")
        }.value)
        assertEquals("pending", assertFailsWith<JsThrown> {
            run("try{throw 'pending';}finally{(function(){return null;})();}")
        }.value)
    }

    @Test fun switchLoopsAndLabelsPropagateOrConsumeTheRightFlow() {
        assertNull(run("outer:{for(var i=0;i<3;i++){switch(i){case 0:continue;case 1:return null;default:break outer;}}}return 'wrong';"))
        assertEquals("done", run("outer:{while(true){switch(1){case 1:try{return null;}finally{break outer;}}}}return 'done';"))
        assertEquals("done", run("switch(1){case 1:try{return null;}finally{break;}}return 'done';"))
        assertEquals(3.0, run("var i=0;while(i++<2){try{return 'old';}finally{continue;}}return i;"))
        assertEquals("ab02", run("""
            var out='',a=(out+='a'),b=(out+='b');
            for(var i=0;i<4;i++){
                switch(i){case 1:continue;case 3:break;default:out+=i;}
                if(i==2)break;
            }
            do{out+='';}while(false);
            return out;
        """))
    }

    @Test fun rejectionAndResourceFailuresCannotBecomeReturns() {
        for (body in listOf("break;", "continue;", "outer:while(true){continue outer;}")) {
            assertFailsWith<UnsupportedPlayerException>(body) { run(body) }
        }
        val error = assertFailsWith<UnsupportedPlayerException> {
            run("try{for(;;){}}catch(e){return 'caught';}finally{return 'override';}")
        }
        assertTrue(error.message!!.contains("step limit"))
    }
}
