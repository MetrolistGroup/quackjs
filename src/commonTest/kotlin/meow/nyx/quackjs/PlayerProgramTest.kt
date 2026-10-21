package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerProgramTest {
    private fun source(signature: String = "return x.split('').reverse().join('');", update: String = "o.set('n',o.V.n.split('').reverse().join(''));", extra: String = ""): String = """
        var g={};
        var table='V;prototype;get;set'.split(';');
        var sig=function(x){$signature};
        g.C=function(raw,enabled){this.raw=raw;this.enabled=enabled;this[table[0]]={};};
        g.C[table[1]][table[3]]=function(k,v){this.V[k]=v;};
        g.C[table[1]][table[2]]=function(k){applyN(7,19,this);return this.V[k];};
        var applyN=function(a,b,o){var key=a^b;if(key==20){$update}};
        $extra
    """.trimIndent()

    private fun compile(signature: String = "return x.split('').reverse().join('');", update: String = "o.set('n',o.V.n.split('').reverse().join(''));", extra: String = ""): PlayerProgram =
        PlayerProgram.compile(source(signature, update, extra), PlayerHints("sig(INPUT)", "C", 12345))

    @Test fun publicApiAndIndependentCalls() {
        val p = compile("state.push(x);return state.length+':'+x;", extra = "var state=[];")
        assertEquals(12345, p.signatureTimestamp)
        repeat(3) {
            assertEquals("1:hello", p.decipherSignature("hello"))
            assertEquals("olleh", p.transformN("hello"))
        }
        assertEquals("", p.transformN(""))
        assertEquals("\ude00\ud83d\u2603", p.transformN("\u2603\ud83d\ude00"))
    }

    @Test fun derivesLandmarksAndOptionalTimestamp() {
        val js = source() + """
            var decode=function(a,b,x){return x;};
            var landmark=function(M,k,X){M=new g.C(M,!0);M.set("alr","yes");X&&(X=sig2(11,3932,decode(3,673,X)));};
            var sig2=function(a,b,x){return sig(x);};
            var settings={signatureTimestamp:20724};
        """
        val fake = """
            /* signatureTimestamp:12345; new g.C(M,!0);M.set("alr","yes");x=wrong(1,2,decode(3,4,x)); */
            var unused='signatureTimestamp:54321';
        """
        val p = PlayerProgram.compile(fake + js)
        assertEquals(20724, p.signatureTimestamp)
        assertEquals("cba", p.decipherSignature("abc"))
        assertNull(PlayerProgram.compile((fake + js).replace("signatureTimestamp:20724", "other:20724")).signatureTimestamp)
    }

    @Test fun onlyLexicalPlayerScopeDefinitionsAreUsed() {
        val p = compile(extra = """
            var bait="sig=function(x){return 'bad'};";
            /* sig=function(x){return 'bad'}; */
            var unrelated=function(){var sig=function(x){return 'bad'};};
        """)
        assertEquals("cba", p.decipherSignature("abc"))
        assertEquals("cba", compile(extra = "var template=`sig=function(x){return 'bad'};`; ").decipherSignature("abc"))
        assertFailsWith<UnsupportedPlayerException> { compile(extra = "sig=function(x){return x;};") }
        assertFailsWith<UnsupportedPlayerException> { compile("return helper.join('')+x;", extra = "var helper=[];helper.push('prefix');") }
        assertFailsWith<UnsupportedPlayerException> { compile("return helper.join('')+x;", extra = "var helper=[];var unused=helper['push']('prefix');") }
        assertFailsWith<UnsupportedPlayerException> { compile("return helper.f(x);", extra = "var helper={f:function(x){return x;}};helper.f=function(x){return 'bad'};") }
    }

    @Test fun foldsDispatchAndIgnoresFalseTransformBranches() {
        val p = compile(update = "if(false)wrong(o);o.set('n',o.V.n.split('').reverse().join(''));", extra = "var wrong=function(o){o.set('n','bad')};")
        assertEquals("zyx", p.transformN("xyz"))
        assertEquals("cba", p.decipherSignature("abc"))
    }

    @Test fun resourceLimitsAreExplicitAndNotCatchableByPlayer() {
        val loop = compile("if(x=='evil'){try{for(;;){}}catch(e){return x;}}return x;")
        assertFailsWith<UnsupportedPlayerException> { loop.decipherSignature("evil") }
        assertFailsWith<UnsupportedPlayerException> { loop.decipherSignature("x".repeat(16_385)) }
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(" ".repeat(8 * 1024 * 1024 + 1)) }
        assertFailsWith<UnsupportedPlayerException> { compile("try{return Array(1000000000).join('x')}catch(e){return x}") }
        assertFailsWith<UnsupportedPlayerException> { compile("try{var a=[];a.length=1000000000}catch(e){return x}return x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("var a=[];a[2147483648]=x;return String(a.length);") }
        assertFailsWith<UnsupportedPlayerException> { compile("return " + List(500) { "1" }.joinToString("+") + "+x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("return recur(x);", extra = "var recur=function(x){return recur(x)};") }
    }

    @Test fun splitHonorsJavaScriptLimitsWithoutTruncatingOversizedTables() {
        val entries = "v;".repeat(69_999) + "v"
        for (limit in listOf("", ",undefined", ",70000")) {
            val error = assertFailsWith<UnsupportedPlayerException> {
                compile("return entries[69999]+x;", extra = "var entries='$entries'.split(';'$limit);")
            }
            assertTrue(error.message!!.contains("Array size limit"))
        }
        assertEquals("65536:v", compile("return entries.length+':'+entries[65535];", extra = "var entries='$entries'.split(';',65536);").decipherSignature("abc"))
        assertFailsWith<UnsupportedPlayerException> {
            compile("return entries[69999]+x;", extra = "var entries='${"v".repeat(70_000)}'.split('');")
        }
        assertEquals("a|b", compile("return x.split(',',2).join('|');").decipherSignature("a,b,c"))
        assertEquals("a|b|", compile("return x.split(',').join('|');").decipherSignature("a,b,"))
        assertEquals("", compile("return x.split(',',4294967296).join('|');").decipherSignature("a,b"))
        val runtime = Runtime(SourceIndex(source(), "C"))
        val value = "x".repeat(8_000_000)
        val split = runtime.invoke(runtime.get(value, "split"), value, listOf("", 1.0)) as JsArray
        assertEquals(listOf<Any?>("x"), split.values)
    }

    @Test fun joinRejectsOverflowingLengthsBeforeAllocatingOutput() {
        val runtime = Runtime(SourceIndex(source(), "C"))
        val literal = "x".repeat(8_000_000)
        val values = runtime.array(List(270) { literal })
        val error = assertFailsWith<UnsupportedPlayerException> {
            runtime.invoke(runtime.get(values, "join"), values, listOf(""))
        }
        assertTrue(error.message!!.contains("String size limit"))
    }

    @Test fun externalUpdatesAndCompoundAssignmentsAreRejected() {
        val updates = listOf("offset++", "++offset", "offset--", "--offset", "(offset)++", "++(offset)",
            "offset+=1", "offset-=1", "offset*=2", "offset/=2", "offset%=2", "offset&=1", "offset|=2", "offset^=2",
            "offset<<=1", "offset>>=1", "offset>>>=1", "offset**=2", "offset&&=2", "offset||=2", "offset??=2")
        for (update in updates) {
            val error = assertFailsWith<UnsupportedPlayerException>(update) {
                compile("return offset+x;", extra = "var offset=1;$update;")
            }
            assertTrue(error.message!!.contains("Externally mutated dependency 'offset'"), update)
        }
        for (update in listOf("helper.n++", "++helper.n", "helper['n']+=2")) {
            assertFailsWith<UnsupportedPlayerException>(update) {
                compile("return helper.n+x;", extra = "var helper={n:1};$update;")
            }
        }
        assertEquals("1abc", compile("return offset+x;", extra = "var offset=1;var unused=function(){offset++;offset+=2;};").decipherSignature("abc"))
    }

    @Test fun blockScopedDeclarationsAreRejectedInsteadOfHoisted() {
        for (keyword in listOf("let", "const")) {
            val error = assertFailsWith<UnsupportedPlayerException> { compile("var k='a';{$keyword k='b'}return k+x;") }
            assertTrue(error.message!!.contains("Block-scoped declarations"))
            assertFailsWith<UnsupportedPlayerException> { compile("var k='a';for($keyword k=0;k<1;){break;}return k+x;") }
        }
        assertEquals("babc", compile("var k='a';{var k='b'}return k+x;").decipherSignature("abc"))
        assertEquals("cba", compile(extra = "var unused=function(){let k=1;const n=2;};").decipherSignature("abc"))
    }

    @Test fun unsupportedOperationsNeverBecomeSuccessFallbacks() {
        assertFailsWith<UnsupportedPlayerException> { compile("try{return x.replace(/a/g,'b')}catch(e){return x}") }
        assertFailsWith<UnsupportedPlayerException> { compile("try{return x.replace(/a/g,'b')}finally{return x}") }
        assertFailsWith<UnsupportedPlayerException> { compile("return missing(x);") }
        assertFailsWith<UnsupportedPlayerException> { compile("try{return Math.random()+x}catch(e){return x}") }
        assertFailsWith<UnsupportedPlayerException> { compile("return typeof new String(x);") }
        assertFailsWith<UnsupportedPlayerException> { compile("return 1e23+x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("return 010+x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("break;return x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("return (") }
        assertFailsWith<UnsupportedPlayerException> { compile(update = "try{throw 'failed'}catch(e){o.set('n','marker_'+o.V.n)}") }
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(source(), PlayerHints("sig(INPUT);evil()", "C", 1)) }
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(source(), PlayerHints("sig(INPUT)", "C;evil()", 1)) }
    }

    @Test fun arrayOperationsAndJavaScriptCoercion() {
        val p = compile("""
            var a=x.split('');
            a.splice(-2,1,'!');a.unshift(a.pop());a.push(a.shift());
            return a.slice(1,-1).join('');
        """.trimIndent())
        assertEquals("bcd!", p.decipherSignature("abcdef"))
        assertEquals("1:0:3:5:0:1:1:2", compile("""
            var a=[,undefined,3],count=0;
            a.forEach(function(v){count++});
            return [(4294967297|0),(NaN|0),(-1>>>30),((2+3)*1),Number(''),Number('0x1'),a.indexOf(undefined),count].join(':');
        """.trimIndent()).decipherSignature("x"))
    }

    @Test fun closuresThisAndExceptionSemantics() {
        val p = compile("""
            var a=x.split(''), suffix='!';
            a.forEach(function(v,i,arr){arr[i]=this[i]+suffix},'XYZ'.split(''));
            var caught=false;
            try{(0,[0][0])()}catch(e){caught=true;var result=null}finally{suffix='?'}
            return (caught&&result===null?a.join(''):'bad')+suffix;
        """.trimIndent())
        assertEquals("X!Y!Z!?", p.decipherSignature("abc"))
        assertEquals("oops", compile("try{throw new Error('oops')}catch(e){return e.message}").decipherSignature("abc"))
        assertEquals("abc", compile("try{return 'wrong'}finally{return x}").decipherSignature("abc"))
    }

    @Test fun dateAndNumericDispatchSemantics() {
        val p = compile("return String.fromCharCode(65,65536+66)+':'+(new Date('1970-01-01T09:45:22.000+09:45')/1000);")
        assertEquals("AB:22", p.decipherSignature("abc"))
        assertEquals("0:0", compile("return (('='/2)|0)+':'+(('} '/2)|0);").decipherSignature("abc"))
        assertTrue(assertFailsWith<UnsupportedPlayerException> { compile("return x.repeat(2)") }.message!!.contains("Unsupported"))
    }
}
