package meow.nyx.quackjs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotCharacterPlanLane27Test {
    private val body = "this.push(a[i]=e[(e.indexOf(v)-e.indexOf(this[i])+i+r--)%e.length])"
    private fun code(body: String = this.body) = Parser(Lexer("function(v,i,a){$body}").tokens()).completeExpression() as FunctionExpr
    private val source = SourceIndex("var g={};g.C=function(){};", "C")
    private data class State(val runtime: Runtime, val env: Env, val outer: Env, val arrays: List<JsArray>)
    private fun state(code: FunctionExpr, alphabet: List<Any?> = listOf("a", "b", "c"), seed: List<Any?> = listOf("b"), input: List<Any?> = listOf("a"), remaining: Any? = 3.0, alias: Int = 0): State {
        val runtime = Runtime(source)
        val table = JsArray(alphabet.toMutableList())
        val self = if (alias == 1 || alias == 3) table else JsArray(seed.toMutableList())
        val target = if (alias == 2) table else if (alias == 3 || alias == 4) self else JsArray(input.toMutableList())
        val outer = Env().also { it.values.putAll(mapOf("e" to table, "r" to remaining, "keys" to JsArray(mutableListOf("push", "indexOf", "length")), "mask" to 7.0)) }
        val env = runtime.functionEnv(JsFunction(code, outer), self, listOf(target.values[0], 0.0, target))
        return State(runtime, env, outer, listOf(table, self, target))
    }
    private fun compare(code: FunctionExpr, alphabet: List<Any?> = listOf("a", "b", "c"), seed: List<Any?> = listOf("b"), input: List<Any?> = listOf("a"), remaining: Double = 3.0, alias: Int = 0) {
        val fast = state(code, alphabet, seed, input, remaining, alias)
        val slow = state(code, alphabet, seed, input, remaining, alias)
        assertTrue(assertNotNull(code.hotCharacterPlan).execute(fast.runtime, fast.env))
        slow.runtime.exec(code.body, slow.env)
        assertEquals(slow.arrays.map { it.values }, fast.arrays.map { it.values })
        assertEquals(slow.outer["r"], fast.outer["r"])
    }

    @Test fun recognizedCallbacksKeepLiveKeysAliasingAndNumericSemantics() {
        val literal = code()
        val indexed = code(body.replace(".push", "[keys[mask^7]]").replace(".indexOf", "[keys[mask^6]]").replace(".length", "[keys[2]]"))
        for (callback in listOf(literal, indexed)) {
            assertNotNull(callback.hotCharacterPlan)
            for (alias in 0..4) compare(callback, alias = alias)
            for (remaining in listOf(-9.0, -0.0, 1e30, Double.NaN, Double.POSITIVE_INFINITY)) compare(callback, remaining = remaining)
            compare(callback, alphabet = emptyList())
            compare(callback, alphabet = listOf(Hole, Undefined, null, "a", "a", 2.0), seed = emptyList())
        }
        val random = Random(27)
        repeat(150) {
            val alphabet = List(random.nextInt(1, 40)) { ('a' + random.nextInt(8)).toString() }
            compare(indexed, alphabet, List(random.nextInt(5)) { ('a' + random.nextInt(10)).toString() }, listOf(('a' + random.nextInt(10)).toString()), random.nextInt(-100, 100).toDouble())
        }
    }

    @Test fun unsupportedShapesAndDynamicAssumptionsFallBackBeforeMutation() {
        for (body in listOf(";$body", "return $body", body.replace("r--", "--r"), body.replace("-e.indexOf", "+e.indexOf"), body.replace("e.length", "e['len'+'gth']"), body.replace(".push", "[keys[r^7]]"))) assertNull(code(body).hotCharacterPlan, body)
        val callback = code()
        val plan = assertNotNull(callback.hotCharacterPlan)
        for ((name, value) in listOf("e" to "abc", "e" to JsObject(), "r" to "3", "r" to Undefined)) {
            val s = state(callback)
            s.outer[name] = value
            assertFalse(plan.execute(s.runtime, s.env))
            assertEquals(listOf<Any?>("a"), s.arrays[2].values)
            assertEquals(value, s.outer[name])
        }
        for (value in listOf(-1.0, 0.5, Double.NaN, Double.POSITIVE_INFINITY, 2.0)) {
            val s = state(callback)
            s.env.slots[callback.parameterSlots[1]] = value
            assertFalse(plan.execute(s.runtime, s.env))
            assertEquals(3.0, s.outer["r"])
        }
        for ((array, method) in listOf(0 to "indexOf", 1 to "push")) {
            val s = state(callback)
            s.arrays[array].fields[method] = Native { _, _, _ -> -1.0 }
            assertFalse(plan.execute(s.runtime, s.env))
            assertEquals(3.0, s.outer["r"])
            // The interpreter still executes an overridden method rather than assuming the built-in.
            s.runtime.exec(callback.body, s.env)
            assertEquals(2.0, s.outer["r"])
        }
        for ((slot, value) in listOf(callback.thisSlot to JsObject(), callback.parameterSlots[0] to "long", callback.parameterSlots[2] to "array")) {
            val s = state(callback)
            s.env.slots[slot] = value
            assertFalse(plan.execute(s.runtime, s.env))
            assertEquals(3.0, s.outer["r"])
        }
        val keyed = code(body.replace(".push", "[keys[0]]"))
        val s = state(keyed)
        (s.outer["keys"] as JsArray).values[0] = "pop"
        assertFalse(assertNotNull(keyed.hotCharacterPlan).execute(s.runtime, s.env))
    }

    @Test fun exactInterpreterStepAndAllocationChargesAreRetained() {
        val indexed = body.replace(".push", "[keys[mask^7]]").replace(".indexOf", "[keys[mask^6]]").replace(".length", "[keys[2]]")
        for ((callback, steps) in listOf(code() to 70, code(indexed) to 88)) for (fast in listOf(false, true)) {
            val fractional = state(callback, remaining = 0.5)
            assertFailsWith<UnsupportedPlayerException> {
                if (fast) callback.hotCharacterPlan!!.execute(fractional.runtime, fractional.env) else fractional.runtime.exec(callback.body, fractional.env)
            }
            val s = state(callback)
            // Five frame slots plus callback steps; 6 frame units plus 8 native-call units.
            s.runtime.tick(2_000_000 - steps)
            var units = 2_000_000 - 14
            while (units > 0) { val chunk = minOf(units, 65_537); s.runtime.allocate(chunk - 1); units -= chunk }
            if (fast) assertTrue(assertNotNull(callback.hotCharacterPlan).execute(s.runtime, s.env)) else s.runtime.exec(callback.body, s.env)
            assertFailsWith<UnsupportedPlayerException> { s.runtime.tick() }
            assertFailsWith<UnsupportedPlayerException> { s.runtime.allocate(0) }
        }
    }

    @Test fun expressionDepthAndArrayGrowthStillFailClosed() {
        for (fast in listOf(false, true)) {
            val callback = code(if (fast) body else ";$body")
            val s = state(callback, seed = List(65_536) { "b" })
            assertFailsWith<UnsupportedPlayerException> { s.runtime.invoke(JsFunction(callback, s.outer), s.arrays[1], listOf("a", 0.0, s.arrays[2])) }
            val expr = """(function(){
                var e=['a','b','c'],r=3,callback=function(v,i,a){${if (fast) body else ";$body"}};
                var recurse=function(n){if(n)return ${"! ".repeat(8)}recurse(n-1);callback.call(['b'],'a',0,['a']);return 0};
                return recurse(28)
            })()"""
            val error = assertFailsWith<UnsupportedPlayerException> { Runtime(source).expression(Parser(Lexer(expr).tokens()).completeExpression()) }
            assertTrue(error.message!!.contains("Expression execution depth"), error.message)
        }
    }

    @Test fun realDispatcherMatchesInterpreterAcrossRandomUtf16Challenges() {
        val fallbackSource = realPlayerSlice.replace("function(f,t,\nP){", "function(f,t,\nP){;")
        assertTrue(fallbackSource != realPlayerSlice)
        val hints = PlayerHints("Su(11,3932,INPUT)", "cg", 20724)
        val fast = PlayerProgram.compile(realPlayerSlice, hints)
        val slow = PlayerProgram.compile(fallbackSource, hints)
        val random = Random(2701)
        val alphabet = "abcXYZ012_-/?=\u0000\u2603\ud83d\ude00"
        repeat(60) {
            val input = (0 until random.nextInt(180)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            assertEquals(slow.transformN(input), fast.transformN(input), "random challenge $it")
        }
    }
}
