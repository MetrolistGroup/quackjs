package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmallArityCallsLane10Test {
    private fun runtime() = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))

    private fun evaluate(body: String): JsValue = runtime().expression(
        Parser(Lexer("(function(){$body})()").tokens()).completeExpression()
    )

    @Test fun ordinaryAndCallArgumentsEvaluateOnceFromLeftToRight() {
        for (arity in 0..5) for (call in listOf(false, true)) {
            val args = (0 until arity).joinToString(",") { "mark('$it')" }
            val invocation = if (call) "target()[key()](receiver()${if (args.isEmpty()) "" else ",$args"})" else "target()[key()]($args)"
            val digits = (0 until arity).joinToString("")
            assertEquals("tk${if (call) "r" else ""}$digits:self:$arity", evaluate("""
                var log='',obj={tag:'self'},fn=function(){return this.tag+':'+arguments.length};
                obj.f=fn;
                var target=function(){log+='t';return ${if (call) "fn" else "obj"}};
                var key=function(){log+='k';return '${if (call) "call" else "f"}'};
                var receiver=function(){log+='r';return obj};
                var mark=function(v){log+=v;return v};
                var result=$invocation;
                return log+':'+result;
            """), "arity=$arity call=$call")
        }
    }

    @Test fun thrownArgumentStopsEvaluationBeforeInvocation() {
        for (call in listOf(false, true)) {
            val target = if (call) "fn.call(null," else "fn("
            assertEquals("01", evaluate("""
                var log='',fn=function(){log+='called'},mark=function(v){log+=v;if(v==1)throw 'stop';return v};
                try{${target}mark(0),mark(1),mark(2),mark(3))}catch(e){}
                return log;
            """))
        }
    }

    @Test fun missingNullUndefinedAndEscapedSnapshotsRemainDistinct() {
        for (call in listOf(false, true)) {
            val f = if (call) "f.call(null," else "f("
            val empty = if (call) "f.call()" else "f()"
            assertEquals("0:1:1:1:true:true:true:outer:inner:null", evaluate("""
                var f=function(v){return {args:arguments,read:function(){return v}}};
                var a=$empty,b=${f}null),c=${f}undefined),d=${f}'outer',${f}'inner'));
                b.args[0]='changed';
                return [a.args.length,b.args.length,c.args.length,d.args[1].args.length,
                    a.read()===undefined,b.read()===null,c.read()===undefined,d.read(),d.args[1].read(),String(b.read())].join(':');
            """))
        }
        assertEquals("true:true:true", evaluate("""
            var f=function(){return this};
            return [f.call()===undefined,f.call(null)===null,f.call(undefined)===undefined].join(':');
        """))
    }

    @Test fun constructorsAndIndirectCallKeepTheirReceiversAndArguments() {
        assertEquals("abcd:abcd", evaluate("""
            var log='',mark=function(v){log+=v;return v};
            var a=new Array(mark('a'),mark('b'),mark('c'),mark('d'));
            return log+':'+a.join('');
        """))
        assertEquals("self:3:a:b:c", evaluate("""
            var f=function(){return this.tag+':'+arguments.length+':'+arguments.join(':')},call=f.call;
            return call.call(f,{tag:'self'},'a','b','c');
        """))
        assertFailsWith<UnsupportedPlayerException> { evaluate("var f=function(){};return new f.call(null);") }
    }

    @Test fun callbackArgumentsAndCapturedFramesDoNotAliasLaterInvocations() {
        assertEquals("a:0:2:self:b:1:2:self", evaluate("""
            var out=[];
            ['a','b'].forEach(function(v,i,a){
                var saved=arguments,self=this;
                out.push(function(){return [v,saved[1],a.length,self.tag].join(':')});
            },{tag:'self'});
            return out[0]()+':'+out[1]();
        """))
    }

    @Test fun callArgumentAllocationChargesStayExactForAllArityPaths() {
        for (arity in 0..5) for (call in listOf(false, true)) {
            val runtime = runtime()
            repeat(30) { runtime.allocate(65_536) }
            runtime.allocate(33_888 - arity)
            val fn = Native { _, self, args ->
                assertSame(if (call) JsNull else Undefined, self)
                JsNumber(args.size.toDouble())
            }
            val args = List(arity) { Lit(it.toDouble()) }
            val expr = if (call) Call(Member(Lit(fn), Lit("call")), listOf(Lit(JsNull)) + args) else Call(Lit(fn), args)
            assertEquals(arity.toDouble(), runtime.expression(expr))
            assertTrue(assertFailsWith<UnsupportedPlayerException> { runtime.allocate(0) }.message!!.contains("Allocation limit"))
        }
        val runtime = runtime()
        val fn = Native { _, self, args -> assertSame(Undefined, self); assertTrue(args.isEmpty()); JsString("empty") }
        assertEquals("empty", runtime.expression(Call(Member(Lit(fn), Lit("call")), emptyList())))
    }
}
