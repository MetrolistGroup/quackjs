package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssignmentReferencesLane09Test {
    private fun parse(source: String): Expr = Parser(Lexer(source).tokens()).completeExpression()
    private fun runtime(): Runtime = Runtime(SourceIndex("g.C=function(){};", "C"))
    private fun evaluate(body: String): Any? {
        val runtime = runtime()
        return runtime.invoke(runtime.function(parse("function(){$body}") as FunctionExpr), Undefined, emptyList())
    }

    @Test fun assignmentsReturnTheWrittenValueForSlotsAndMembers() {
        val targets = listOf("value=20" to "value", "box={value:20}" to "box.value", "box=[20]" to "box[0]", "box=[20]" to "box['0']")
        val operations = listOf("=" to 2, "+=" to 22, "-=" to 18, "*=" to 40, "/=" to 10, "%=" to 0,
            "&=" to 0, "|=" to 22, "^=" to 22, "<<=" to 80, ">>=" to 5, ">>>=" to 5)
        for ((declaration, target) in targets) for ((op, expected) in operations) {
            assertEquals("$expected:$expected", evaluate("var $declaration;var result=($target $op 2);return [result,$target].join(':');"), "$target $op")
        }
        assertEquals("13:13", evaluate("var value=3;var result=(value+=(value=10));return [result,value].join(':');"))
    }

    @Test fun updatesReturnNumericOldOrNewValues() {
        for ((declaration, target) in listOf("value='3'" to "value", "box={value:'3'}" to "box.value", "box=['3']" to "box[0]")) {
            for ((update, expected) in listOf("$target++" to "3:4", "++$target" to "4:4", "$target--" to "3:2", "--$target" to "2:2")) {
                assertEquals("$expected:number", evaluate("var $declaration;var result=$update;return [result,$target,typeof result].join(':');"), update)
            }
        }
    }

    @Test fun memberReceiverAndKeyAreEvaluatedOnceBeforeTheRhs() {
        val setup = """
            var object={v:3},original=object,trace='';
            var receiver=function(){trace+='r';return object};
            var key=function(){trace+='k';return 'v'};
            var rhs=function(){trace+='x';object={v:99};original.v=8;return 4};
        """
        for ((operation, expected) in listOf("=rhs()" to "rkx:4:4:99", "+=rhs()" to "rkx:7:7:99", "++" to "rk:3:4:4", "--" to "rk:3:2:2")) {
            assertEquals(expected, evaluate("$setup var result=receiver()[key()]$operation;return [trace,result,original.v,object.v].join(':');"), operation)
        }
        for ((operation, expected) in listOf("=rhs()" to "rkx", "+=rhs()" to "rk", "++" to "rk")) {
            assertEquals(expected, evaluate("""
                var trace='';var receiver=function(){trace+='r';return null};
                var key=function(){trace+='k';return 'v'};var rhs=function(){trace+='x';return 4};
                try{receiver()[key()]$operation}catch(e){return trace}return 'not caught';
            """), operation)
        }
    }

    @Test fun capturedAndCatchShadowedWritesUseTheirOriginalOwners() {
        assertEquals("3:14:14", evaluate("""
            var value=3;var change=function(){var old=value++;value+=(value=10);return old+':'+value};
            var result=change();return result+':'+value;
        """))
        assertEquals("7:7:9:1", evaluate("""
            var value=1,result;try{throw 3}catch(value){
                var change=function(){value++;return value};result=(value+=change())+':'+value;
                value=9;result+=':'+value;
            }return result+':'+value;
        """))
    }

    @Test fun globalWritesInvalidateCachedReads() {
        val runtime = Runtime(SourceIndex("g.C=function(){};var counter=1;", "C"), globalCount = 1)
        val id = Id("counter")
        val reader = runtime.function(FunctionExpr(emptyList(), Block(listOf(ReturnStmt(id)))))
        id.globalSlot = 0
        fun read(): Any? = runtime.invoke(reader, Undefined, emptyList())
        assertEquals(1.0, read())
        for ((operation, expected) in listOf("counter=4" to 4.0, "counter+=3" to 7.0, "counter++" to 8.0, "--counter" to 7.0)) {
            runtime.expression(parse(operation))
            assertEquals(expected, read(), operation)
        }
    }

    @Test fun mutationPathsKeepTheirStepAndSizeBudgets() {
        val target = Id("value").also { it.slot = 0 }
        val env = Env(names = mapOf("value" to 0))
        val array = JsArray(mutableListOf(3.0))
        val member = Member(Lit(array), Lit(0.0))
        val cases = listOf(
            Binary("=", target, Lit(2.0)) to 2,
            Binary("+=", target, Lit(2.0)) to 2,
            Update("++", target, false) to 4,
            Binary("=", member, Lit(2.0)) to 4,
            Binary("+=", member, Lit(2.0)) to 5,
            Update("--", member, true) to 4,
            Binary("=", Member(Lit(JsObject()), Lit("key")), Lit(2.0)) to 7,
        )
        for ((expression, cost) in cases) {
            val runtime = runtime()
            env.slots[0] = "123"
            runtime.tick(2_000_000 - cost)
            runtime.eval(expression, env)
            assertTrue(assertFailsWith<UnsupportedPlayerException> { runtime.tick() }.message!!.contains("step limit"))
        }
        for (operation in listOf("a[65536]=1", "a[65536]++", "a.length+=65537")) {
            assertTrue(assertFailsWith<UnsupportedPlayerException> { evaluate("var a=[];$operation;return a;") }.message!!.contains("limit"), operation)
        }
        val undeclaredEnv = Env().also { it["sentinel"] = 0.0 }
        assertFailsWith<UnsupportedPlayerException> { runtime().eval(parse("missing=(sentinel=1)"), undeclaredEnv) }
        assertEquals(0.0, undeclaredEnv["sentinel"])
    }
}
