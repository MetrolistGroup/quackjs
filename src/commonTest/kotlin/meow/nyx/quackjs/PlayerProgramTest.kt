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

    @Test fun isolatedNHelpersKeepInputDerivedArguments() {
        val extra = """
            var rootN=function(o,v,size){o.set('n',v.slice(0,size).split('').reverse().join(''));};
            var bridge=function(o,v){rootN(o,v,v.length);o.raw.replace('x','y');};
        """
        val calls = listOf(
            "rootN(this,this.V.n,this.V.n.length)" to false,
            "var value=this.V.n,size=value.length;rootN(this,value,size)" to false,
            "bridge(this,this.V.n)" to false,
            "rootN(this,this.V.n,this.V.n.length-1)" to true,
        )
        for ((call, trim) in calls) {
            val js = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$call;this.raw.replace('x','y');return this.V[k];")
            val program = PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 12345))
            for (input in listOf("abcdef", "xy", "", "another-challenge")) {
                assertEquals((if (trim) input.dropLast(1) else input).reversed(), program.transformN(input), call)
            }
        }
        val branching = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", """
            var value=this.V.n;
            if(value.length>10)rootN(this,value,value.length);else bridge(this,value);
            this.raw.replace('x','y');return this.V[k];
        """.trimIndent())
        assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(branching, PlayerHints("sig(INPUT)", "C", 12345)) }
        val doubled = (1..30).joinToString("") { "var v$it=v${it - 1}+v${it - 1};" }
        val expanding = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "var v0=this.V.n.length;$doubled rootN(this,this.V.n,v30);this.raw.replace('x','y');return this.V[k];")
        val error = assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(expanding, PlayerHints("sig(INPUT)", "C", 12345)) }
        assertTrue(error.message!!.contains("Syntax tree node limit"))
    }

    @Test fun nIsolationDoesNotDropInputDependentGuards() {
        val extra = """
            var rootN=function(o){o.set('n',o.V.n.split('').reverse().join(''));};
            var check=function(o){return o.V.n.length>10;};
            var bridge=function(o){if(o.V.n.length>10)rootN(o);o.raw.replace('x','y');};
        """
        val bodies = listOf(
            "if(this.V.n.length>10)rootN(this);",
            "this.V.n.length>10&&rootN(this);",
            "this.V.n.length<=10||rootN(this);",
            "this.V.n.length>10?rootN(this):null;",
            "var value=this.V.n;if(value.length>10)rootN(this);",
            "if(check(this))rootN(this);",
            "if(this.V.n.length<=10)return this.V[k];rootN(this);",
            "if(true){if(this.V.n.length<=10)return this.V[k];}rootN(this);",
            "while(this.V.n.length>10){rootN(this);break;}",
            "switch(this.V.n.length){case 19:case 24:rootN(this);break;}",
            "bridge(this);",
            "return this.V[k];rootN(this);",
        )
        for (body in bodies) {
            val js = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$body this.raw.replace('x','y');return this.V[k];")
            assertFailsWith<UnsupportedPlayerException>(body) { PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 12345)) }
        }
        val supported = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "if(this.V.n.length>10)rootN(this);return this.V[k];")
        val program = PlayerProgram.compile(supported, PlayerHints("sig(INPUT)", "C", 12345))
        assertEquals("abc", program.transformN("abc"))
        assertEquals("lkjihgfedcba", program.transformN("abcdefghijkl"))
        val constant = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "if(7==7)rootN(this);this.raw.replace('x','y');return this.V[k];")
        assertEquals("cba", PlayerProgram.compile(constant, PlayerHints("sig(INPUT)", "C", 12345)).transformN("abc"))
        val manyGuards = "if(this.raw)return this.V[k];".repeat(130)
        val bounded = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$manyGuards rootN(this);this.raw.replace('x','y');return this.V[k];")
        val error = assertFailsWith<UnsupportedPlayerException> { PlayerProgram.compile(bounded, PlayerHints("sig(INPUT)", "C", 12345)) }
        assertTrue(error.message!!.contains("n control-flow limit"))
    }

    @Test fun mutationsThroughObjectAliasesAreRejected() {
        val signature = "var a=x.split('');helper.f(a);return a.join('');"
        val helper = "var helper={f:function(a){a.reverse()}};"
        for (mutation in listOf(
            "var alias=helper;alias.f=function(a){a.shift()};",
            "var alias=((helper)),second=alias;second['f']=function(a){a.shift()};",
            "var alias=helper;alias.f.call=function(){};",
            "var alias=helper;alias.f([]);",
        )) assertFailsWith<UnsupportedPlayerException>(mutation) { compile(signature, extra = helper + mutation) }
        assertFailsWith<UnsupportedPlayerException> {
            compile("var a=x.split('');helper.nested.f(a);return a.join('');", extra = "var helper={nested:{f:function(a){a.reverse()}}};var alias=helper['nested'];alias.f=function(a){a.shift()};")
        }
        for (update in listOf("alias.counter++", "++alias.counter", "--alias['counter']", "(alias.counter)++", "alias.counter+=2")) {
            assertFailsWith<UnsupportedPlayerException>(update) { compile("return helper.counter+x;", extra = "var helper={counter:1};var alias=helper;$update;") }
        }
        val chain = (1..1000).joinToString("") { "var alias$it=alias${it - 1};" }
        assertFailsWith<UnsupportedPlayerException> { compile(signature, extra = helper + "var alias0=helper;" + chain + "alias1000.f=function(a){a.shift()};") }
        assertEquals("cba", compile(signature, extra = helper + "var alias=helper;").decipherSignature("abc"))
        assertEquals("cba", compile("var a=x.split('');alias.f(a);return a.join('');", extra = helper + "var alias=helper;").decipherSignature("abc"))
        assertEquals("1abc", compile("return offset+x;", extra = "var offset=1;var alias=offset;alias++;").decipherSignature("abc"))
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

    @Test fun parenthesizedGlobalWritesAreNotTreatedAsConstants() {
        for (write in listOf("(offset=2)", "((offset=2))", "(offset)=2", "((offset))=2", "(0,offset=2)")) {
            assertFailsWith<UnsupportedPlayerException>(write) {
                compile("return offset+x;", extra = "var offset=1;$write;")
            }
        }
        assertFailsWith<UnsupportedPlayerException> {
            compile("return helper.n+x;", extra = "var helper={n:1};((helper.n))=2;")
        }
        assertEquals("1abc", compile("return offset+x;", extra = "var offset=1;var unused=function(){(offset=2);};").decipherSignature("abc"))
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

    @Test fun assignmentsAndUpdatesEvaluateTheirTargetsOnce() {
        assertEquals("a!b:1", compile("var a=x.split(''),i=0;a[i++] += '!';return a.join('')+':'+i;").decipherSignature("ab"))
        assertEquals("aab:b:1", compile("var a=[{v:'a'},{v:'b'}],i=0;a[i++].v += x;return a[0].v+':'+a[1].v+':'+i;").decipherSignature("ab"))
        assertEquals("5:2:1", compile("var a=[1,2],i=0;a[i++] += (a[0]=4);return a.join(':')+':'+i;").decipherSignature("ab"))
        for ((update, expected) in listOf("a[i++]++" to "4:9:3:1", "++a[i++]" to "4:9:4:1", "a[i++]--" to "2:9:3:1", "--a[i++]" to "2:9:2:1")) {
            assertEquals(expected, compile("var a=[3,9],i=0,result=$update;return a.join(':')+':'+result+':'+i;").decipherSignature("ab"), update)
        }
        assertEquals("3:2:1", compile("var o={a:2},i=0;var key=function(){i++;return 'a';};var result=o[key()]++;return o.a+':'+result+':'+i;").decipherSignature("ab"))
        assertEquals("ab:b:1", compile("var a=['a','b'],i=0;a[i++]=x;return a.join(':')+':'+i;").decipherSignature("ab"))
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
