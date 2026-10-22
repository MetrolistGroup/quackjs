package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCallbackLane22Test {
    private fun runtime() = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
    private fun expression(source: String) = Parser(Lexer(source).tokens()).completeExpression()
    private fun run(body: String): JsValue = runtime().expression(expression("(function(){$body})()"))

    @Test fun traversalReadsLiveElementsButKeepsTheOriginalLength() {
        assertEquals("a0:b1:C2|abC", run("""
            var a=['a',,'c','d'],out=[];
            a.forEach(function(v,i,arr){
                out.push(v+i);
                if(i==0){arr[1]='b';delete arr[2];arr.push('ignored')}
                if(i==1){arr.length=3;arr[2]='C'}
            });
            return out.join(':')+'|'+a.join('');
        """.trimIndent()))
        assertEquals("a0:c1:d2", run("""
            var a=['a','b','c','d'],out=[];
            a.forEach(function(v,i,arr){out.push(v+i);if(i==0)arr.shift()});
            return out.join(':');
        """.trimIndent()))
    }

    @Test fun nativeCallbacksReceiveThreeArgumentsAndTheThisArgument() {
        assertEquals("a:0:true:b:1:true:6", run("""
            var a=['a','b'],out=[];
            a.forEach(out.push,out);
            return [out[0],out[1],out[2]===a,out[3],out[4],out[5]===a,out.length].join(':');
        """.trimIndent()))
        assertEquals("true:true:true:true", run("""
            var a=['a','b'],out=[],arg={};
            a.forEach(function(v,i,arr,missing){out.push(this===arg&&arr===a&&missing===undefined)},arg);
            a.forEach(function(){out.push(this===undefined)});
            return out.join(':');
        """.trimIndent()))
    }

    @Test fun retainedArgumentsAndClosuresKeepTheirOwnInvocationValues() {
        assertEquals("a:0:true:b:1:true:false:a1:b1:a2", run("""
            var a=['a','b'],args=[],closures=[];
            a.forEach(function(v,i){
                args.push(arguments);
                var count=0;
                closures.push(function(){return v+(++count)});
            });
            return [args[0][0],args[0][1],args[0][2]===a,
                args[1][0],args[1][1],args[1][2]===a,args[0]===args[1],
                closures[0](),closures[1](),closures[0]()].join(':');
        """.trimIndent()))
    }

    @Test fun recursiveTraversalDoesNotOverwriteAnActiveCallbackFrameOrArguments() {
        assertEquals("a0:b1:ab0:c0:d1:cd1", run("""
            var out=[];
            var callback=function visit(v,i){
                if(v.length>1)v.split('').forEach(visit);
                out.push(v+i);
            };
            ['ab','cd'].forEach(callback);
            return out.join(':');
        """.trimIndent()))
        val error = assertFailsWith<UnsupportedPlayerException> {
            run("var callback=function recur(v){[v].forEach(recur)};try{[0].forEach(callback)}catch(e){return 'bad'};")
        }
        assertTrue(error.message!!.contains("Call depth limit"), error.message)
    }

    @Test fun callbackExceptionsStopTraversalAndUnwindTheCallDepth() {
        assertEquals("a:b", run("""
            var out=[];
            try{['a','ignored'].forEach(function(v){out.push(v);throw 'stop'})}catch(e){}
            ['b'].forEach(out.push,out);
            return out[0]+':'+out[1];
        """.trimIndent()))
    }

    @Test fun reusedArgumentsAndFramesKeepTheirExecutionAndAllocationCharges() {
        fun invoke(runtime: Runtime) {
            val callback = runtime.function(expression("function(v,i,a){}") as FunctionExpr)
            val target = JsArray(mutableListOf(JsString("a"), JsString("b")))
            runtime.invoke(runtime.get(target, "forEach"), target, listOf(callback))
        }
        // Lookup + outer invocation + two visits + preparation + two callback invocations.
        val steps = runtime()
        steps.tick(2_000_000 - 30)
        invoke(steps)
        assertFailsWith<UnsupportedPlayerException> { steps.tick() }

        // Two outer argument units + six frame units + four units per callback.
        val allocations = runtime()
        repeat(30) { allocations.allocate(65_536) }
        allocations.allocate(33_873)
        invoke(allocations)
        assertFailsWith<UnsupportedPlayerException> { allocations.allocate(0) }
    }

    @Test fun emptyAndAllHoleArraysNeverPrepareOrInvokeACallback() {
        val runtime = runtime()
        val callback = runtime.function(FunctionExpr(emptyList(), Block(listOf(
            Vars(List(10_000) { "v$it" to null }), ThrowStmt(Lit("must not run"))
        ))))
        for (target in listOf(JsArray(mutableListOf()), JsArray(MutableList(8) { Hole }))) {
            repeat(200) { runtime.invoke(runtime.get(target, "forEach"), target, listOf(callback)) }
        }
    }
}
