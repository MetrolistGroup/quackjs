package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal fun templateSourceLane28(extra: String): String = """
    var g={};
    g.C=function(){this.V={};};
    g.C.prototype.set=function(k,v){this.V[k]=v;};
    g.C.prototype.get=function(k){applyN(this);return this.V[k];};
    var applyN=function(o){var x=o.V.n;o.set('n',x.length>16?x.split('').reverse().join(''):transform(x));};
    var sig=function(x){return transform(x);};
    $extra
""".trimIndent()

internal fun templateProgramLane28(): PlayerProgram = PlayerProgram.compile(templateSourceLane28("""
    var state={nested:[{values:[]},{values:[]}],read:function(){return state.nested[0].values[0]}};
    var alias=state;
    var readers=[function(){return state.nested[0].values[0]},function(){return state.nested[0].values[0]}];
    var readerAlias=readers[0];
    var factory=function(){var values=[];return function(x){values.push(x);return values.length}};
    var captured=factory();
    var transform=function(x){
        state.nested[0].values.push(x);
        var old=alias,before=state.read(),local=factory(),other=factory();
        state={nested:[{values:[x+'!']}]};
        return [alias===old,old===state,old.nested[0]===old.nested[1],
            old.nested[0].values.length,old.nested[1].values.length,before,old.read(),
            readers[0]===readerAlias,readers[0]===readers[1],readers[0](),
            captured(x),local(x),other(x),local(x)].join(':');
    };
"""), PlayerHints("sig(INPUT)", "C", 28))

internal fun templateExpectedLane28(input: String): String =
    "true:false:false:1:0:$input:$input!:true:false:$input!:1:1:1:2"

class TemplateCopyingLane28Test {
    @Test fun nestedTemplatesAliasesAndClosuresStayIndependent() {
        val program = templateProgramLane28()
        repeat(3) {
            for (input in listOf("first", "second", "", "\u2603\ud83d\ude00")) {
                assertEquals(templateExpectedLane28(input), program.decipherSignature(input))
                assertEquals(templateExpectedLane28(input), program.transformN(input))
            }
        }
    }

    @Test fun copiesKeepFreshContainersAndRuntimeBoundFunctions() {
        val source = SourceIndex(templateSourceLane28(""), "C")
        val expr = Parser(Lexer("[{v:[1,2],f:function(){return value[0].v[0]}},[]]").tokens()).completeExpression()
        val template = Runtime(source).expression(expr) as JsArray
        fun runtime() = Runtime(source, mapOf("value" to expr), mapOf("value" to template))
        val first = runtime()
        val second = runtime()
        val a = first.resolve("value") as JsArray
        val b = second.resolve("value") as JsArray
        assertSame(a, first.resolve("value"))
        assertNotSame(template, a)
        assertNotSame(a, b)
        val aObject = a.values[0] as JsObject
        val bObject = b.values[0] as JsObject
        val aValues = aObject.fields["v"] as JsArray
        val bValues = bObject.fields["v"] as JsArray
        val aFunction = aObject.fields["f"] as JsFunction
        val bFunction = bObject.fields["f"] as JsFunction
        assertNotSame(aObject, bObject)
        assertNotSame(aValues, bValues)
        assertNotSame(a.values[1], b.values[1])
        assertNotSame(aFunction, bFunction)
        assertNotSame(aFunction.env, bFunction.env)
        assertSame(aFunction.code, bFunction.code)
        aValues.values[0] = "changed"
        assertEquals("changed", first.invoke(aFunction, Undefined, emptyList()))
        assertEquals(1.0, second.invoke(bFunction, Undefined, emptyList()))
        assertEquals(1.0, ((template.values[0] as JsObject).fields["v"] as JsArray).values[0])
    }

    @Test fun copiesPreserveAllocationChargesForEveryNestedValue() {
        val source = SourceIndex(templateSourceLane28(""), "C")
        val expr = Parser(Lexer("[{v:[1,2],f:function(){}},[]]").tokens()).completeExpression()
        val template = Runtime(source).expression(expr)
        // Outer array, object, nested array, function and empty array cost 3+3+3+2+1 units.
        for (shortfall in 0..1) {
            val runtime = Runtime(source, mapOf("value" to expr), mapOf("value" to template))
            repeat(30) { runtime.allocate(65_536) }
            runtime.allocate(2_000_000 - 30 * 65_537 - 12 - 1 + shortfall)
            if (shortfall == 0) runtime.resolve("value")
            val error = assertFailsWith<UnsupportedPlayerException> {
                if (shortfall == 1) runtime.resolve("value") else runtime.allocate(0)
            }
            assertTrue(error.message!!.contains("Allocation limit"), error.message)
        }
    }

    @Test fun templateInitializersStillObeyDepthLimits() {
        val nested = "[".repeat(130) + "0" + "]".repeat(130)
        val source = templateSourceLane28("var deep=$nested;var transform=function(x){return deep[0]+x};")
        val error = assertFailsWith<UnsupportedPlayerException> {
            PlayerProgram.compile(source, PlayerHints("sig(INPUT)", "C", 28))
        }
        assertTrue(error.message!!.contains("depth limit") || error.message!!.contains("nesting limit"), error.message)
    }
}
