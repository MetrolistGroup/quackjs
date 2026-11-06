package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BudgetLaneQTest {
    private fun source() = SourceIndex("g.C=function(){};", "C")
    private fun parse(text: String) = Parser(Lexer(text).tokens(), text).completeExpression()
    private fun stepLimit(block: () -> Unit) {
        val error = assertFailsWith<UnsupportedPlayerException>(block = block)
        assertEquals("Execution step limit exceeded", error.message)
    }

    @Test fun deepCallbackKeysFailBeforeStructuralComparison() {
        for (key in listOf(List(20_000) { "0" }.joinToString("+"), "'indexOf'" + "+''".repeat(20_000))) {
            val callback = "function(v,i,a){this.push(a[i]=e[(e[$key](v)-e[$key](this[i])+i+r--)%e.length])}"
            val error = assertFailsWith<UnsupportedPlayerException> { parse(callback) }
            assertTrue(error.message!!.contains("depth limit"), error.message)
        }
    }

    @Test fun duplicateParametersConsumeStepsAndKeepLastBinding() {
        val code = parse("function(a,a,a,a,a,a,a,a){return a}") as FunctionExpr
        val runtime = Runtime(source())
        runtime.tick(2_000_000 - code.slots.size - 4)
        stepLimit { runtime.functionEnv(runtime.function(code), Undefined, emptyList()) }
        val normal = Runtime(source())
        assertEquals(JsNumber(8.0), normal.invoke(normal.function(code), Undefined, List(8) { JsNumber(it + 1.0) }))
        assertEquals(Undefined, normal.invoke(normal.function(code), Undefined, listOf(JsNumber(1.0))))
    }

    @Test fun symbolicArrayContentScanConsumesSteps() {
        val runtime = Runtime(source(), analysis = LinearNAnalysis(70))
        val array = JsArray(MutableList(100) { if (it == 0) JsNumber(0.0) else Hole })
        val method = runtime.get(array, "indexOf")
        runtime.tick(2_000_000 - runtime.chargedSteps - 10)
        stepLimit { runtime.invoke(method, array, listOf(JsNumber(0.0))) }
    }

    @Test fun uninitializedDeclaratorsConsumeSteps() {
        val runtime = Runtime(source())
        val declarations = Vars(List(100) { "v$it" to null })
        runtime.tick(2_000_000 - 10)
        stepLimit { runtime.exec(declarations, Env()) }
    }

    @Test fun emptySwitchFallthroughAndDefaultSearchConsumeSteps() {
        for (value in listOf(0.0, -1.0)) {
            val runtime = Runtime(source())
            val cases = List(100) { Lit(it.toDouble()) to emptyList<Stmt>() } + listOf(null to emptyList<Stmt>())
            runtime.tick(2_000_000 - if (value < 0) 250 else 10)
            stepLimit { runtime.exec(SwitchStmt(Lit(value), cases), Env()) }
        }
    }

    @Test fun overlappingDefinitionsShareAnAllocationBudget() {
        val leaf = "function(){" + "0;".repeat(30_000) + "}"
        var expression = leaf
        for (i in 9 downTo 0) expression = "0||[0,f${i + 1}=$expression,0]"
        val text = "g.C=function(){};var " + (0..10).joinToString(",") { "f$it" } + ";f0=$expression;"
        val index = SourceIndex(text, "C")
        val runtime = Runtime(index)
        val error = assertFailsWith<UnsupportedPlayerException> {
            for (i in 10 downTo 0) runtime.resolve("f$i")
        }
        assertEquals("Compilation allocation limit exceeded", error.message)
    }

    @Test fun repeatedAliasValidationSharesAWorkBudget() {
        fun balanced(depth: Int): String = if (depth == 0) "0" else "(${balanced(depth - 1)}+${balanced(depth - 1)})"
        val aliases = (1..1_000).joinToString("") { "var a$it=a${it - 1};" }
        val text = """
            var g={};var sig=function(x){return x};
            g.C=function(){this.V={}};
            g.C.prototype.set=function(k,v){this.V[k]=v};
            g.C.prototype.get=function(k){var a0=${balanced(10)};$aliases helper(this);this.raw.replace('x','y');return this.V[k]};
            var helper=function(o){o.set('n',o.V.n.split('').reverse().join(''))};
        """.trimIndent()
        val error = assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(text, PlayerHints("sig(INPUT)", "C", 1)) }
        assertEquals("Compilation work limit exceeded", error.message)
    }
}
