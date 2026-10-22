package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArrayMutationLane18Test {
    private fun runtime() = Runtime(SourceIndex("var g={};g.C=function(){};", "C"))
    private fun call(runtime: Runtime, target: JsArray, method: String, args: List<Any?> = emptyList()): Any? =
        runtime.invoke(runtime.get(target, method), target, args.map(::testJsValue))

    private fun positions(size: Int): List<Pair<Any?, Int>> = listOf(
        Undefined to 0, null to 0, Double.NaN to 0, Double.NEGATIVE_INFINITY to 0,
        Double.POSITIVE_INFINITY to size, -99.0 to 0, -2.9 to (size - 2).coerceAtLeast(0),
        -1.0 to (size - 1).coerceAtLeast(0), -0.0 to 0, 2.9 to minOf(2, size),
        99.0 to size, " -2 " to (size - 2).coerceAtLeast(0), "NaN" to 0,
    )

    @Test fun splicePreservesSparseRangesForEveryResizeDirectionAndArity() {
        val runtime = runtime()
        val objectValue = JsObject()
        for (size in 0..6) {
            val original = List<Any?>(size) { if (it % 3 == 0) Hole else if (it % 3 == 1) objectValue else Undefined }
            val starts = listOf(emptyList<Any?>() to 0) + positions(size).map { listOf(it.first) to it.second }
            for ((prefix, start) in starts) {
                val counts = listOf<Pair<List<Any?>, Int>>(
                    emptyList<Any?>() to if (prefix.isEmpty()) 0 else size - start,
                    listOf(Undefined) to 0, listOf(null) to 0, listOf(Double.NaN) to 0,
                    listOf(Double.NEGATIVE_INFINITY) to 0, listOf(-1.0) to 0,
                    listOf(1.9) to minOf(1, size - start), listOf(3.0) to minOf(3, size - start),
                    listOf(Double.POSITIVE_INFINITY) to size - start,
                )
                for ((countArgs, count) in counts) {
                    if (prefix.isEmpty() && countArgs.isNotEmpty()) continue
                    val insertions = if (countArgs.isEmpty()) listOf(emptyList()) else listOf(
                        emptyList(), listOf("new"), listOf(null, Undefined), listOf(objectValue, "a", "b", "c"),
                    )
                    for (inserted in insertions) {
                        val target = JsArray(original.map(::testJsValue).toMutableList(), mutableMapOf("tag" to objectValue))
                        val storage = target.values
                        val removed = call(runtime, target, "splice", prefix + countArgs + inserted) as JsArray
                        assertEquals(original.drop(start).take(count), removed.values)
                        assertEquals(original.take(start) + inserted + original.drop(start + count), target.values)
                        assertSame(storage, target.values)
                        assertSame(objectValue, target.fields["tag"])
                        assertNotSame(target, removed)
                        assertNotSame(target.values, removed.values)
                        removed.values.clear()
                        assertEquals(size - count + inserted.size, target.values.size)
                    }
                }
            }
        }
    }

    @Test fun sliceClampsIndexesAndCopiesHolesWithoutSharingStorage() {
        val runtime = runtime()
        val objectValue = JsObject()
        val target = JsArray(mutableListOf(Hole, objectValue, Undefined, JsNull, JsString("end")))
        val original = target.values.toList()
        val starts = listOf(emptyList<Any?>() to 0) + positions(original.size).map { listOf(it.first) to it.second }
        for ((args, start) in starts) {
            val ends = listOf(emptyList<Any?>() to original.size, listOf(Undefined) to original.size) +
                positions(original.size).filter { it.first !== Undefined }.map { listOf(it.first) to it.second }
            for ((endArgs, end) in ends) {
                if (args.isEmpty() && endArgs.isNotEmpty()) continue
                val copy = call(runtime, target, "slice", args + endArgs) as JsArray
                assertEquals(original.drop(start).take((end - start).coerceAtLeast(0)), copy.values)
                assertNotSame(target.values, copy.values)
                copy.values.clear()
                assertEquals(original, target.values)
            }
        }
    }

    @Test fun unshiftAndShiftKeepOrderHolesAndSelfAliases() {
        val runtime = runtime()
        val target = JsArray(mutableListOf(Hole, Undefined, JsString("tail")))
        val storage = target.values
        assertEquals(3.0, call(runtime, target, "unshift"))
        assertEquals(4.0, call(runtime, target, "unshift", listOf(target)))
        assertSame(target, call(runtime, target, "shift"))
        assertSame(Undefined, call(runtime, target, "shift"))
        assertSame(Undefined, call(runtime, target, "shift"))
        assertEquals(4.0, call(runtime, target, "unshift", listOf("a", null, "b")))
        assertEquals(listOf<Any?>("a", null, "b", "tail"), target.values)
        assertSame(storage, target.values)
        assertEquals("a", call(runtime, target, "shift", listOf("ignored")))
        assertEquals(null, call(runtime, target, "shift"))
        assertEquals("b", call(runtime, target, "shift"))
        assertEquals("tail", call(runtime, target, "shift"))
        assertSame(Undefined, call(runtime, target, "shift"))
    }

    @Test fun methodAliasesUseTheirReceiverAndCopiesRemainShallow() {
        val runtime = runtime()
        val target = JsArray(mutableListOf(JsString("a"), Hole, JsString("b")))
        val other = JsArray(mutableListOf(target, JsString("c")))
        val splice = runtime.get(target, "splice")
        val removed = runtime.invoke(splice, other, listOf(JsNumber(0.0), JsNumber(1.0), other)) as JsArray
        assertSame(target, removed.values[0])
        assertSame(other, other.values[0])
        assertEquals(listOf<Any?>("a", Hole, "b"), target.values)
        val copy = call(runtime, other, "slice") as JsArray
        assertSame(other, copy.values[0])
        assertTrue(copy.fields.isEmpty())
        assertFailsWith<UnsupportedPlayerException> { runtime.invoke(splice, JsObject(), emptyList()) }
    }

    @Test fun growingMutationsRejectOversizedArraysWithoutChangingAliases() {
        for ((method, args) in listOf(
            "splice" to listOf(0.0, 0.0, "new"),
            "unshift" to listOf("new"),
        )) {
            val runtime = runtime()
            val target = JsArray(MutableList(65_536) { Hole })
            val storage = target.values
            val error = assertFailsWith<UnsupportedPlayerException> { call(runtime, target, method, args) }
            assertTrue(error.message!!.contains("Array size limit"))
            assertSame(storage, target.values)
            assertEquals(65_536, target.values.size)
            assertTrue(target.values.all { it === Hole })
        }
        val runtime = runtime()
        val target = JsArray(MutableList(65_536) { Hole })
        assertEquals(1, (call(runtime, target, "splice", listOf(0.0, 1.0, "new")) as JsArray).values.size)
        assertEquals(65_536, target.values.size)
        assertEquals("new", target.values[0])
    }

    private fun fillAllocation(runtime: Runtime, amount: Int) {
        var remaining = amount
        while (remaining > 65_537) { runtime.allocate(65_536); remaining -= 65_537 }
        if (remaining > 0) runtime.allocate(remaining - 1)
    }

    @Test fun mutationsKeepExistingStepAndAllocationChargesEvenForNoOps() {
        val cases = listOf(
            Triple("slice", emptyList<Any?>(), 4),
            Triple("slice", listOf(2.0, 1.0), 1),
            Triple("splice", emptyList<Any?>(), 2),
            Triple("splice", listOf(1.0, 1.0, "x", "y"), 5),
            Triple("unshift", emptyList<Any?>(), 1),
            Triple("unshift", listOf("x"), 2),
            Triple("shift", emptyList<Any?>(), 0),
        )
        for ((method, args, internalAllocation) in cases) {
            val stepCost = method.length + 2 + if (method == "slice") if (args.isEmpty()) 3 else 0 else 3
            val runtime = runtime()
            val target = JsArray(mutableListOf(JsString("a"), Hole, Undefined))
            runtime.tick(2_000_000 - stepCost)
            call(runtime, target, method, args)
            assertTrue(assertFailsWith<UnsupportedPlayerException> { runtime.tick() }.message!!.contains("step limit"))

            val allocation = args.size + 1 + internalAllocation
            for (excess in 0..1) {
                val budgeted = runtime()
                val array = JsArray(mutableListOf(JsString("a"), Hole, Undefined))
                fillAllocation(budgeted, 2_000_000 - allocation + excess)
                if (excess == 0) {
                    call(budgeted, array, method, args)
                    assertTrue(assertFailsWith<UnsupportedPlayerException> { budgeted.allocate(0) }.message!!.contains("Allocation limit"))
                } else {
                    assertTrue(assertFailsWith<UnsupportedPlayerException> { call(budgeted, array, method, args) }.message!!.contains("Allocation limit"))
                    assertEquals(listOf<Any?>("a", Hole, Undefined), array.values)
                }
            }
        }
    }
}
