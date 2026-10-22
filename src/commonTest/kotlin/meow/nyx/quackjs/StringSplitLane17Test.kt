package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StringSplitLane17Test {
    private fun runtime() = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))

    private fun split(runtime: Runtime, value: Any?, args: List<Any?>): JsArray =
        runtime.invoke(runtime.get(JsString(""), "split"), testJsValue(value), args.map(::testJsValue)) as JsArray

    @Test fun emptySeparatorSplitsUtf16UnitsIncludingNulAndLoneSurrogates() {
        val input = (0..255).map { it.toChar() }.joinToString("") + "\u0100\ud800\udc00\ud83d\ude00\uffff"
        assertEquals<List<Any?>>(input.map { it.toString() }, split(runtime(), input, listOf("")).values)
        for (inputUnit in listOf("\u0000", "\u00ff", "\u0100", "\ud800", "\udc00")) {
            assertEquals<List<Any?>>(listOf(inputUnit), split(runtime(), inputUnit, listOf("")).values)
        }
        assertEquals<List<Any?>>(listOf("\ud83d"), split(runtime(), "\ud83d\ude00", listOf("", 1.0)).values)
        assertEquals(emptyList(), split(runtime(), "", listOf("")).values)
    }

    @Test fun limitsKeepUint32Coercion() {
        for (limit in listOf(null, false, "", 0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4_294_967_296.0)) {
            assertEquals(emptyList(), split(runtime(), "abc", listOf("", limit)).values, "limit=$limit")
        }
        for (limit in listOf(true, 1.9, "1", 4_294_967_297.0, -4_294_967_295.0)) {
            assertEquals<List<Any?>>(listOf("a"), split(runtime(), "abc", listOf("", limit)).values, "limit=$limit")
        }
        for (limit in listOf("2.9", "0x2", 2.0)) {
            assertEquals<List<Any?>>(listOf("a", "b"), split(runtime(), "abc", listOf("", limit)).values, "limit=$limit")
        }
        for (limit in listOf(Undefined, -1.0)) {
            assertEquals<List<Any?>>(listOf("a", "b", "c"), split(runtime(), "abc", listOf("", limit)).values)
        }
        assertEquals(emptyList(), split(runtime(), "abc", listOf(JsObject(), 0.0)).values)
        assertEquals(emptyList(), split(runtime(), "abc", listOf("", JsObject())).values)
        assertFailsWith<UnsupportedPlayerException> { split(runtime(), "abc", listOf(RegexValue("/a/"), 0.0)) }
        assertEquals<List<Any?>>(listOf("abc"), split(runtime(), "abc", listOf(JsObject())).values)
    }

    @Test fun separatorsAndReceiversKeepTheirExistingCoercion() {
        for (input in listOf("", "a\u0000b\ud800\udc00")) {
            assertEquals<List<Any?>>(listOf(input), split(runtime(), input, emptyList()).values)
            assertEquals<List<Any?>>(listOf(input), split(runtime(), input, listOf(Undefined)).values)
        }
        for ((input, separator, expected) in listOf(
            Triple("", ",", listOf("")),
            Triple(",a,,", ",", listOf("", "a", "", "")),
            Triple("ababa", "aba", listOf("", "ba")),
            Triple("a\u0000b\u0000", "\u0000", listOf("a", "b", "")),
            Triple("a\ud800b\ud800", "\ud800", listOf("a", "b", "")),
            Triple("a\ud83d\ude00b", "\ud83d\ude00", listOf("a", "b")),
            Triple("anullb", null, listOf("a", "b")),
            Triple("a1b", 1.0, listOf("a", "b")),
            Triple("atrueb", true, listOf("a", "b")),
        )) assertEquals<List<Any?>>(expected, split(runtime(), input, listOf(separator)).values)
        assertEquals<List<Any?>>(listOf("a", "b"), split(runtime(), "a,b,c", listOf(",", 2.0)).values)
        assertEquals<List<Any?>>(listOf("1", "2", "3"), split(runtime(), 123.0, listOf("")).values)
        assertEquals<List<Any?>>(listOf("t", "ue"), split(runtime(), true, listOf("r")).values)
        assertFailsWith<JsThrown> { split(runtime(), null, listOf("")) }
        assertFailsWith<JsThrown> { split(runtime(), Undefined, listOf("")) }
    }

    @Test fun splitOwnsItsMutableStorageButArrayHelpersStillCopyBorrowedLists() {
        for (separator in listOf("", ",")) {
            val runtime = runtime()
            val first = split(runtime, "a,b", listOf(separator))
            val second = split(runtime, "a,b", listOf(separator))
            val expected = second.values.toList()
            first.values[0] = JsString("changed")
            first.values.add(JsString("extra"))
            first.values.removeAt(1)
            assertEquals(expected, second.values)
        }
        val input = mutableListOf<JsValue>(JsString("a"), JsString("b"))
        val copy = runtime().array(input.subList(0, 1))
        input[0] = JsString("changed")
        copy.values.add(JsString("extra"))
        assertEquals<List<Any?>>(listOf("a", "extra"), copy.values)
        assertEquals<List<Any?>>(listOf("changed", "b"), input)
    }

    @Test fun characterSplitPreservesAllocationAndStepChargesEvenForSharedStrings() {
        for ((input, allocationCost) in listOf("" to 3, "a" to 4, "ab" to 9, "\u0100\ud800" to 9, "aaaaaaaa" to 27)) {
            fun preallocate(runtime: Runtime, cost: Int) {
                var remaining = cost
                while (remaining > 65_537) { runtime.allocate(65_536); remaining -= 65_537 }
                if (remaining > 0) runtime.allocate(remaining - 1)
            }
            val exactAllocation = runtime()
            preallocate(exactAllocation, 2_000_000 - allocationCost)
            assertEquals<List<Any?>>(input.map { it.toString() }, split(exactAllocation, input, listOf("")).values)
            val exhaustedAllocation = runtime()
            preallocate(exhaustedAllocation, 2_000_001 - allocationCost)
            assertTrue(assertFailsWith<UnsupportedPlayerException> {
                split(exhaustedAllocation, input, listOf(""))
            }.message!!.contains("Allocation limit"))

            val stepCost = 7 + 2 * input.length // get("split"), invoke, then two steps per UTF-16 unit.
            val exactSteps = runtime()
            exactSteps.tick(2_000_000 - stepCost)
            assertEquals<List<Any?>>(input.map { it.toString() }, split(exactSteps, input, listOf("")).values)
            val exhaustedSteps = runtime()
            exhaustedSteps.tick(2_000_001 - stepCost)
            assertTrue(assertFailsWith<UnsupportedPlayerException> {
                split(exhaustedSteps, input, listOf(""))
            }.message!!.contains("step limit"))
        }
    }

    @Test fun characterSplitEnforcesArrayBoundsWithoutChargingUnvisitedInput() {
        assertEquals(65_536, split(runtime(), "a".repeat(65_536), listOf("")).values.size)
        val oversized = "a".repeat(70_000)
        assertTrue(assertFailsWith<UnsupportedPlayerException> {
            split(runtime(), oversized, listOf(""))
        }.message!!.contains("Array size limit"))
        assertEquals(65_536, split(runtime(), oversized, listOf("", 65_536.0)).values.size)
        assertEquals<List<Any?>>(listOf("a"), split(runtime(), "a".repeat(8_000_000), listOf("", 1.0)).values)
    }
}
