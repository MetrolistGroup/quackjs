package meow.nyx.quackjs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearNPlanTest {
    private val source = SourceIndex("var g={};g.C=function(){};", "C")
    private fun expression(body: String): Expr {
        val code = "(function(s){$body})(INPUT)"
        return Parser(Lexer(code).tokens(), code).completeExpression()
    }
    private fun plan(body: String, length: Int): LinearNPlan {
        val analysis = LinearNAnalysis(length)
        val runtime = Runtime(source, analysis = analysis)
        return assertNotNull(analysis.finish(runtime.expression(expression(body), mapOf("INPUT" to analysis.input())), runtime).plan)
    }
    private fun interpreted(body: String, input: String): String =
        (Runtime(source).expression(expression(body), mapOf("INPUT" to JsString(input))) as JsString).value
    private val cipher = """
        var a=s.split(''),e='abcd'.split(''),seed=['b','a'],r=4;
        a.forEach(function(v,i,t){this.push(t[i]=e[(e.indexOf(v)-e.indexOf(this[i])+i+r--)%e.length])},seed);
        return a.join('');
    """

    @Test fun derivesRecurrenceWithoutAnInputAndGuardsInvalidRanks() {
        val random = Random(41)
        for (length in listOf(0, 1, 2, 3, 19, 64, 200)) {
            val plan = plan(cipher, length)
            repeat(30) {
                val input = buildString { repeat(length) { append("abcd"[random.nextInt(4)]) } }
                assertEquals(interpreted(cipher, input), plan.execute(input))
            }
            for (char in listOf('?', '\u0000', '\u2603', '\ud83d', '\ude00')) {
                val input = char.toString().repeat(length)
                plan.execute(input)?.let { assertEquals(interpreted(cipher, input), it) }
            }
        }
        assertNull(plan(cipher, 2).execute("abc"))
        val guarded = "var e=['a','b'];return e[e.indexOf(s)]"
        assertNull(plan(guarded, 1).execute("?"))
    }

    @Test fun duplicateAndUnicodeAlphabetsKeepFirstOccurrenceRanks() {
        val zero = "var e=['a','b'],i=e.indexOf(s)*0;return e[e.indexOf(e[i])]"
        assertEquals(interpreted(zero, "?"), plan(zero, 1).execute("?"))
        for (alphabet in listOf("aabc", "a\u2603\u00e9x")) {
            val body = cipher.replace("'abcd'", "'$alphabet'").replace("['b','a']", "['a','a']")
            val input = alphabet.repeat(5)
            assertEquals(interpreted(body, input), plan(body, input.length).execute(input))
        }
    }

    @Test fun mutationsAndAliasesFollowExecutionOrder() {
        val bodies = listOf(
            "try{0()}catch(e){return s.split('').reverse().join('')}",
            "var a=s.split(''),b=a;b.reverse();return a.join('')",
            cipher.replace("return a.join('');", "e.reverse();return a.join('');"),
            cipher.replace("e.length])", "e.length]);e.reverse()"),
            "var a=s.split(''),e='abcd'.split('');e.reverse();a.forEach(function(v,i,t){t[i]=e[e.indexOf(v)]});return a.join('')",
            "var a=s.split('');a.reverse=function(){this[0]='x'};a.reverse();return a.join('')",
            "var a=s.split(''),e=['a','b'];e.indexOf=function(v){return 0};a[0]=e[e.indexOf(a[0])];return a.join('')",
        )
        for (body in bodies) for (length in listOf(1, 2, 19)) {
            val input = "abcd".repeat(5).take(length)
            assertEquals(interpreted(body, input), plan(body, length).execute(input))
        }
        val alias = "var a=s.split('');a.forEach(function(v,i,t){t[i]=t[t.indexOf(v)]});return a.join('')"
        assertFailsWith<LinearNFailure> { plan(alias, 2) }
    }

    @Test fun characterDependentBranchesUseCheckedPathConditions() {
        val body = "if(s[0]=='a')return 'changed';return s"
        val guarded = plan(body, 1)
        assertNull(guarded.execute("a"))
        assertEquals("b", guarded.execute("b"))
        val mutation = "var a=s.split('');if(s[0]=='a')a.reverse=function(){this[0]='x'};a.reverse();return a.join('')"
        assertNull(plan(mutation, 1).execute("a"))
        assertEquals(interpreted(mutation, "b"), plan(mutation, 1).execute("b"))
    }

    @Test fun inputDependentControlCoercionAndWritePositionsFailClosed() {
        assertFailsWith<UnsupportedPlayerException> { plan("try{s.unknown()}catch(e){return s}", 1) }
        for (body in listOf(
            "var a=s.split('');if(a.indexOf('a')>=0)return 'wrong';return s",
            "var e=['a','b'];var x=e.indexOf(s);if(x)return s;return 'wrong'",
            "var a=s.split('');a[s]=1;return a.join('')",
            "return String(Number(s))",
            "return s.replace('a','b')",
            "try{0()}catch(e){return 'except_'+s}",
            "var e=['a','b'],x=e.indexOf(s);var a=[function(){},x*0.5];return String(+a)",
        )) assertFailsWith<LinearNFailure>(body) { plan(body, 1) }
    }

    @Test fun primitiveArrayNumberProofsKeepChargesAndCheckNumericCharacters() {
        val body = "var a=s.split(''),x=+a;if(x===x)return 'numeric';return s"
        val expr = expression(body)
        for (input in listOf("?", "12", "abc", "\u2603\ud83d\ude00")) {
            val analysis = LinearNAnalysis(input.length)
            val fast = Runtime(source, analysis = analysis)
            val value = fast.expression(expr, mapOf("INPUT" to analysis.input()))
            val slow = Runtime(source)
            val expected = slow.expression(expr, mapOf("INPUT" to JsString(input)))
            assertEquals(slow.chargedSteps, fast.chargedSteps)
            assertEquals(slow.chargedAllocation, fast.chargedAllocation)
            assertEquals((expected as JsString).value, assertNotNull(analysis.finish(value, fast).plan).execute(input))
        }
        val single = plan(body, 1)
        for (char in listOf('0', '1', '9', ' ', '\t', '\n', '\u00a0', '\ufeff')) assertNull(single.execute(char.toString()))
        for (char in listOf('?', 'a', '-', '.', '\u0000', '\u2603', '\ud83d', '\ude00')) {
            val input = char.toString()
            assertEquals(interpreted(body, input), single.execute(input))
        }
        for (method in listOf("join", "valueOf", "toString")) {
            val mutated = body.replace("x=+a", "x=(a.$method=function(){return '1'},+a)")
            assertEquals(interpreted(mutated, "12"), plan(mutated, 2).execute("12"))
        }
    }

    @Test fun publicCallsRestartTheInterpreterAfterFailedGuards() {
        val js = """
            var g={};
            var sig=function(s){return s.split('').reverse().join('')};
            g.C=function(){this.V={}};
            g.C.prototype.get=function(k){update(this);return this.V[k]};
            g.C.prototype.set=function(k,v){this.V[k]=v};
            var update=function(o){
                var a=o.V.n.split('');
                if(a[0]=='a')a.reverse=function(){this[0]='x'};
                a.reverse();o.set('n',a.join(''));
            };
        """
        val program = PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 1))
        assertNull(assertNotNull(program.linearNResult(3).plan).execute("abc"))
        for (input in listOf("abc", "bbb", "", "\u2603\ud83d\ude00", "a".repeat(201))) {
            assertEquals(program.transformNInterpreted(input), program.transformN(input))
        }
        assertEquals("xbc", program.transformN("abc"))
    }

    @Test fun conservativePreflightDoesNotPublishOverBudgetPlans() {
        for (allocationLimit in listOf(false, true)) {
            val analysis = LinearNAnalysis(200)
            val runtime = Runtime(source, analysis = analysis)
            val input = analysis.input()
            if (allocationLimit) {
                var units = 2_000_000 - 401
                while (units > 0) {
                    val chunk = minOf(units, 65_537)
                    runtime.allocate(chunk - 1)
                    units -= chunk
                }
            } else runtime.tick(2_000_000 - 399)
            val error = assertFailsWith<LinearNFailure> { analysis.finish(input, runtime) }
            assertEquals("plan resource bound", error.message)
        }
    }

    @Test fun interpreterAndPlanConstructionBudgetsStayBounded() {
        assertFailsWith<LinearNFailure> { plan("return '" + "x".repeat(65_537) + "'", 1) }
        assertFailsWith<UnsupportedPlayerException> { plan("while(true){}", 1) }
        assertFailsWith<UnsupportedPlayerException> { plan("var a=[];while(true)a.push(s);", 1) }
        val body = "var e=['a','b'],x=e.indexOf(s);for(var i=0;i<21000;i++)x=x+1;return s"
        assertFailsWith<LinearNFailure> { plan(body, 1) }
        val gather = plan("return s.split('').reverse().join('')", 200)
        assertEquals(interpreted("return s.split('').reverse().join('')", "\u2603".repeat(200)), gather.execute("\u2603".repeat(200)))
        assertEquals(true, gather.steps <= 2_000_000 && gather.allocation <= 2_000_000)
    }
}
