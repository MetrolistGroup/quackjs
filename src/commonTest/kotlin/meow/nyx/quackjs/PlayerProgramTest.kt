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

    @Test fun cachedInitializersKeepLiveAliasesClosuresAndNestedStateIndependent() {
        val p = compile("state.nested.a.push(x);return alias.nested.a.length+':'+reader();", extra = """
            var state={nested:{a:[]}};
            var alias=state;
            var reader=function(){return state.nested.a[0]};
        """)
        for (input in listOf("first", "second", "third")) assertEquals("1:$input", p.decipherSignature(input))
        val table = compile("letters.reverse();return letters.join('')+x;", extra = "var letters='a;b;c'.split(';');")
        repeat(3) { assertEquals("cba!", table.decipherSignature("!")) }
        assertEquals("2:true", compile("var f=function(){if(false)var unused;return arguments.length+':'+(unused===undefined)};return f(x,1);").decipherSignature("x"))
    }

    @Test fun globalSlotCacheObservesRebindingAndLiveObjectMutations() {
        val p = compile("var first=read();state=x;var second=read();items[0]=x;return first+':'+second+':'+readTable();", extra = "var state='initial';var items=['old'];var read=function(){return state};var readTable=function(){return items[0]};")
        for (input in listOf("one", "two", "three")) assertEquals("initial:$input:$input", p.decipherSignature(input))
        val fn = compile("var first=helper(x);helper=other;return first+':'+helper(x);", extra = "var helper=function(x){return 'a'+x};var other=function(x){return 'b'+x};")
        repeat(3) { assertEquals("az:bz", fn.decipherSignature("z")) }
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

    @Test fun nIsolationRejectsStaleSubstitutedLocalsAndRootWrites() {
        val extra = "var rootN=function(o,v){o.set('n',v.split('').reverse().join(''));};"
        for (body in listOf(
            "var v=this.V.n;v=v.slice(1);rootN(this,v);",
            "var v=this.V.n;v+='!';rootN(this,v);",
            "var v=this.V.n;if(v.length<4)v='changed';rootN(this,v);",
            "var v=this.V.n;v++;rootN(this,v);",
            "var v=this.V.n;this.raw;var v='changed';rootN(this,v);",
            "var v=this.V.n;var mutate=function(){v=v.slice(1)};mutate();rootN(this,v);",
            "var v=this.V.n;var mutate=function(){v+='!'};mutate();rootN(this,v);",
            "var o=this;var mutate=function(){o.V.n=o.V.n.slice(1)};mutate();rootN(this,this.V.n);",
            "this.V.n=this.V.n.slice(1);rootN(this,this.V.n);",
        )) {
            val js = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$body this.raw.replace('x','y');return this.V[k];")
            assertFailsWith<UnsupportedPlayerException>(body) { PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 12345)) }
        }
        val supported = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "var v=this.V.n;v=v.slice(1);rootN(this,v);return this.V[k];")
        assertEquals("cb", PlayerProgram.compile(supported, PlayerHints("sig(INPUT)", "C", 12345)).transformN("abc"))
    }

    @Test fun nIsolationUsesBindingsAtEachCallSite() {
        val extra = "var rootN=function(o,v){o.set('n',v.split('').reverse().join(''));};"
        for (body in listOf(
            "var flag=1;if(flag)rootN(this,this.V.n);flag=2;",
            "var flag=1;flag&&rootN(this,this.V.n);flag=2;",
            "var flag=1;flag?rootN(this,this.V.n):null;flag=2;",
            "var v=this.V.n;rootN(this,v);v='later';",
        )) {
            val js = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$body this.raw.replace('x','y');return this.V[k];")
            val program = PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 12345))
            for (input in listOf("abc", "", "another-challenge")) assertEquals(input.reversed(), program.transformN(input), body)
        }
        for (body in listOf(
            "var flag=0;if(flag)rootN(this,this.V.n);flag=1;",
            "var flag=1;flag=0;if(flag)rootN(this,this.V.n);",
            "rootN(this,this.V.n);this.V.n='later';",
            "var v=this.V.n;if(v.length>10)rootN(this,v);v='later';",
            "var v=this.V.n,i=0;while(true){rootN(this,v);v='changed';if(i++>0)break;}",
            "var i=0;while(true){rootN(this,this.V.n);if(i++>0)break;}",
            "var v=this.V.n;rootN(this,v);v='changed';rootN(this,v);",
            "rootN(this,this.V.n);rootN(this,this.V.n.slice(1));",
            "rootN(this,this.V.n);if(this.V.n.length>10)rootN(this,this.V.n);",
            "var v=this.V.n;rootN(this,v);v='changed';rootN.call(null,this,v);",
            "var v=this.V.n;rootN(this,v);v='changed';rootN.apply(null,[this,v]);",
            "var flag=1;if(flag)rootN(this,this.V.n);flag=2;this.set('n','later');",
        )) {
            val js = source(extra = extra).replace("applyN(7,19,this);return this.V[k];", "$body this.raw.replace('x','y');return this.V[k];")
            assertFailsWith<UnsupportedPlayerException>(body) { PlayerProgram.compile(js, PlayerHints("sig(INPUT)", "C", 12345)) }
        }
    }

    @Test fun primitivePropertyArgumentsDoNotEscapeTheirOwners() {
        val letters = "var letters='a;b'.split(';');"
        for (call in listOf(
            "Math.floor(letters.length)",
            "unknown(letters.length)",
            "unknown(letters['length'])",
            "unknown(alias.length)",
            "unknown([letters.length])",
        )) assertEquals("aabc", compile("return letters[0]+x;", extra = letters + "var alias=letters;Array.isArray(letters);var unused=$call;").decipherSignature("abc"), call)
        for (extra in listOf(
            "var letters=['a','b'];unknown(letters.length);",
            "var letters='a;b'.split(';');var size=letters.length;unknown(size);",
            "var letters='a;b'.split(';');var wrapper=[letters.length];unknown(wrapper);",
        )) assertEquals("aabc", compile("return letters[0]+x;", extra = extra).decipherSignature("abc"))
        for (mutation in listOf(
            "unknown(letters.length,letters)",
            "unknown(letters[0])",
            "unknown(letters.length||letters)",
        )) assertFailsWith<UnsupportedPlayerException>(mutation) {
            compile("return letters[0]+x;", extra = letters + "$mutation;")
        }
        assertFailsWith<UnsupportedPlayerException> {
            compile("return helper.length[0]+x;", extra = "var helper={length:['a']};unknown(helper.length);")
        }
        for (override in listOf(
            "String.prototype.split=function(){return {length:[]}};",
            "var Native=String;Native.prototype.split=function(){return {length:[]}};",
            "var proto=String.prototype;proto.split=function(){return {length:[]}};",
            "unknown(String);",
        )) for (argument in listOf("letters.length", "size", "[letters.length]", "wrapper")) {
            assertFailsWith<UnsupportedPlayerException>("$override $argument") {
                compile("return letters[0]+x;", extra = override + letters + "var size=letters.length,wrapper=[letters.length];unknown($argument);")
            }
        }
    }

    @Test fun callsCannotMutatePrimitiveGlobalsOrTheirCopies() {
        for ((initializer, suffix) in listOf(
            "1" to "1", "(1<<2)-3" to "1", "true?1:2" to "1",
            "true" to "true", "'value'" to "value", "null" to "null", "void 0" to "undefined",
        )) {
            val p = compile("return x+key;", extra = "var key=$initializer;var unused=Math.floor(key);")
            assertEquals("abc$suffix", p.decipherSignature("abc"), initializer)
        }
        for (extra in listOf(
            "var key=1;var alias=((key));var unused=Math.floor(alias);",
            "var key=1;var helper={key:key};Object.assign(helper,{key:2});",
        )) assertEquals("abc1", compile("return x+key;", extra = extra).decipherSignature("abc"))
        assertEquals("abc1", compile("return helper.f(x)+key;", extra = "var key=1;var helper={key:key,f:function(x){return x}};var unused=Math.floor(key);").decipherSignature("abc"))
        assertEquals("abcvalue", compile("return x+key;", extra = "var key='value';var unused=key.charAt(0);").decipherSignature("abc"))
        val chain = (1..100).joinToString("") { "var alias$it=alias${it - 1};" }
        assertEquals("abc1", compile("return x+alias50;", extra = "var key=1;var alias0=key;${chain}var unused=Math.floor(alias100);").decipherSignature("abc"))
        for (write in listOf("key++", "key+=1", "key=2")) {
            assertFailsWith<UnsupportedPlayerException>(write) { compile("return x+key;", extra = "var key=1;var unused=Math.floor(key);$write;") }
        }
        assertFailsWith<UnsupportedPlayerException> {
            compile("return helper.f(x);", extra = "var helper=factory();var factory=function(){return {f:function(x){return x}}};Object.assign(helper,{f:function(x){return 'changed'}});")
        }
    }

    @Test fun stableReadOnlyPredicatesDoNotEscapeTheirArguments() {
        val letters = "var letters='a;b'.split(';');"
        for (call in listOf(
            "Array.isArray(letters)", "Array['isArray'](letters)",
            "Array.isArray.call(null,letters)", "Array.isArray.apply(null,[letters])",
            "Number.isNaN(letters)", "Number.isFinite(letters)",
            "Ctor.isArray(letters)",
        )) assertEquals("aabc", compile("return letters[0]+x;", extra = letters + "var Ctor=Array;var unused=$call;").decipherSignature("abc"), call)
        val scoped = source("return letters[0]+x;", extra = letters + "var ignored=function(Array){};var unused=Array.isArray(letters);")
        assertEquals("aabc", PlayerProgram.compile("(function(g){$scoped})({});", PlayerHints("sig(INPUT)", "C", 12345)).decipherSignature("abc"))
        assertFailsWith<UnsupportedPlayerException> {
            PlayerProgram.compile("(function(Array){$scoped})({isArray:function(a){a.reverse()}});", PlayerHints("sig(INPUT)", "C", 12345))
        }
        assertFailsWith<UnsupportedPlayerException> {
            PlayerProgram.compile("var setup=function Array(){$scoped};setup.isArray=function(a){a.reverse()};setup();", PlayerHints("sig(INPUT)", "C", 12345))
        }
        for (mutation in listOf(
            "var Array={isArray:function(a){a.reverse()}};",
            "Array.isArray=function(a){a.reverse()};",
            "var patch=function(){Array.isArray=function(a){a.reverse()}};patch();",
            "var Ctor=Array;var patch=function(){Ctor.isArray=function(a){a.reverse()}};patch();",
            "var Ctor=Array;Object.assign(Ctor,{isArray:function(a){a.reverse()}});",
        )) assertFailsWith<UnsupportedPlayerException>(mutation) {
            compile("return letters[0]+x;", extra = letters + mutation + "var unused=Array.isArray(letters);")
        }
    }

    @Test fun mutatingAContainerDoesNotMutateItsContents() {
        val letters = "var letters='a;b'.split(';');"
        for (mutation in listOf(
            "var wrapper=[letters];wrapper.reverse();",
            "var wrapper=[letters],alias=wrapper;alias.reverse();",
            "var wrapper=[letters];wrapper.tag=1;wrapper.reverse();",
            "var wrapper=[letters],alias=wrapper;alias.tag=1;wrapper.reverse();",
            "var wrapper=[letters];wrapper[0]=letters;wrapper.reverse();",
            "var wrapper=[letters];wrapper[0]=[];",
            "var wrapper={value:letters};wrapper.value=[];",
            "var wrapper=[letters],item=wrapper[0];wrapper.reverse();",
        )) assertEquals("aabc", compile("return letters[0]+x;", extra = letters + mutation).decipherSignature("abc"), mutation)
        for (mutation in listOf(
            "var wrapper=[letters];mutate(wrapper);var mutate=function(o){o[0].reverse()};",
            "var wrapper=[letters];wrapper[0].reverse();",
            "var wrapper=[letters];wrapper[0][0]='changed';",
            "var wrapper=[letters],item=wrapper[0];item[0]='changed';",
            "var wrapper=[letters],alias=wrapper.reverse();alias[0].reverse();",
            "var wrapper=[letters];wrapper.reverse=function(){this[0].reverse()};wrapper.reverse();",
            "var wrapper=[letters],alias=wrapper;alias.reverse=function(){this[0].reverse()};wrapper.reverse();",
            "var wrapper=[letters];wrapper.length={valueOf:function(){letters.reverse();return 0}};",
            "var wrapper=[letters];Array.prototype.reverse=function(){this[0].reverse()};wrapper.reverse();",
        )) assertFailsWith<UnsupportedPlayerException>(mutation) { compile("return letters[0]+x;", extra = letters + mutation) }
    }

    @Test fun externalCallsCannotSilentlyMutateDependencies() {
        val signature = "return helper.f(x);"
        val helper = "var helper={f:function(x){return x;}};"
        for (mutation in listOf(
            "Object.assign(helper,{f:function(x){return 'changed'}});",
            "Object['assign']((helper),{f:function(x){return 'changed'}});",
            "var alias=helper;Object.assign(alias,{f:function(x){return 'changed'}});",
            "Object.assign.apply(null,[helper,{f:function(x){return 'changed'}}]);",
            "(Object.assign)(helper,{f:function(x){return 'changed'}});",
            "(0,Object.assign)(helper,{f:function(x){return 'changed'}});",
            "mutate({target:helper});var mutate=function(o){o.target.f=function(x){return 'changed'}};",
            "var args=[helper,{f:function(x){return 'changed'}}];Object.assign.apply(null,args);",
            "var box={target:helper};mutate(box);var mutate=function(o){o.target.f=function(x){return 'changed'}};",
            "delete helper.f;",
            "mutate(helper);var mutate=function(o){o.f=function(x){return 'changed'}};",
        )) assertFailsWith<UnsupportedPlayerException>(mutation) { compile(signature, extra = helper + mutation) }
        assertEquals("cba", compile("var o={};Object.assign(o,{v:x});return o.v.split('').reverse().join('');").decipherSignature("abc"))
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

    @Test fun nestedInitializationCallsShareTheirSourceScanBudget() {
        val expression = List(50_000) { "1" }.joinToString("+")
        for (value in listOf(expression, "'${"x".repeat(1_000_000)}'")) {
            val nested = "f(".repeat(500) + value + ")".repeat(500)
            val error = assertFailsWith<UnsupportedPlayerException> { compile(extra = "var unused=$nested;") }
            assertTrue(error.message!!.contains("Source scan limit"), error.message)
        }
    }

    @Test fun functionFramesConsumeTheAllocationBudget() {
        val locals = (0 until 10_000).joinToString(",") { "v$it" }
        val p = compile("if(x=='evil')for(var i=0;i<1000;i++)large();return x;", extra = "var large=function(){var $locals;};")
        val error = assertFailsWith<UnsupportedPlayerException> { p.decipherSignature("evil") }
        assertTrue(error.message!!.contains("Allocation limit"))
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

    @Test fun multiCharacterSplitChargesOnlyVisitedCandidates() {
        val entries = List(1_001) { "x" }.joinToString("||")
        val p = compile("return entries.length+':'+entries[1000]+':'+x;", extra = "var entries='$entries'.split('||');")
        assertEquals("1001:x:abc", p.decipherSignature("abc"))
        val split = compile("return x.split('||').join(',');")
        for ((input, expected) in listOf(
            "" to "", "||" to ",", "||||" to ",,", "x|||y" to "x,|y",
            "a||b||" to "a,b,", "\ud83d\ude00||\u2603" to "\ud83d\ude00,\u2603",
        )) assertEquals(expected, split.decipherSignature(input), input)
        assertEquals("a,b", compile("return x.split('||',2).join(',');").decipherSignature("a||b||c"))
        assertEquals("|ba", compile("return x.split('aba').join('|');").decipherSignature("ababa"))
        val unmatched = "a".repeat(6_000)
        assertEquals(unmatched, compile("return x.split(separator).join('|');", extra = "var separator='${"b".repeat(1_000)}';").decipherSignature(unmatched))
    }

    @Test fun budgetedStringSearchPreservesJavaScriptPositions() {
        assertEquals("1:1:2:0:1:true", compile("return [x.indexOf('aa',1),x.lastIndexOf('aa',1),x.lastIndexOf('aa'),x.indexOf(''),x.lastIndexOf('',1),x.includes('aa',2)].join(':');").decipherSignature("aaaa"))
        assertEquals("3:0:3:4:-1:-1:-1", compile("return [x.indexOf('a',1),x.lastIndexOf('a',2),x.lastIndexOf('a'),x.lastIndexOf('',9),x.indexOf('a',99),''.indexOf('a'),''.lastIndexOf('a')].join(':');").decipherSignature("abca"))
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

    @Test fun traversalWorkIsBoundedEvenWithoutNewAllocations() {
        for (operation in listOf("a.join('')", "a.toString()", "a.unshift()", "a.indexOf('missing')")) {
            val program = compile("if(x.length<4){var a=Array(65536);try{for(var i=0;i<10000;i++){$operation;}}catch(e){return x}}return x;")
            val error = assertFailsWith<UnsupportedPlayerException>(operation) { program.decipherSignature("abc") }
            assertTrue(error.message!!.contains(if (operation == "a.join('')") "step limit" else "limit"))
        }
        val program = compile("if(x.length<4){var a=large;for(var i=0;i<10000;i++)a.includes('missing')}return x;", extra = "var large='${"a".repeat(65_536)}';")
        assertFailsWith<UnsupportedPlayerException> { program.decipherSignature("abc") }
    }

    @Test fun coercionComparisonAndPropertyWorkConsumesTheStepBudget() {
        val extra = "var digits='${"1".repeat(65_536)}';var other='${"1".repeat(65_535)}2';"
        for (operation in listOf("Number(digits)", "digits|0", "digits==0", "digits<other", "Math.floor(digits)", "[].slice(digits)", "[digits].indexOf(other)", "({})[digits]")) {
            val p = compile("if(x.length<5)for(var i=0;i<10000;i++){$operation;}return x;", extra = extra)
            assertTrue(assertFailsWith<UnsupportedPlayerException>(operation) { p.decipherSignature("evil") }.message!!.contains("step limit"))
        }
    }

    @Test fun quadraticStringSearchesConsumeTheStepBudget() {
        val extra = "var haystack='${"a".repeat(65_536)}';var needle='${"a".repeat(32_767)}b';"
        for (method in listOf("indexOf", "lastIndexOf", "includes", "split")) {
            val p = compile("if(x.length<5)haystack.$method(needle);return x;", extra = extra)
            assertTrue(assertFailsWith<UnsupportedPlayerException>(method) { p.decipherSignature("evil") }.message!!.contains("step limit"))
        }
    }

    @Test fun implicitObjectEqualityFailsClosed() {
        for (expression in listOf("x.split('')==x", "x==x.split('')", "[]==0", "[]==false", "({})=='[object Object]'", "(new Date('1970-01-01T00:00:00Z'))==0", "(new Date('1970-01-01T00:00:00Z'))==false")) {
            assertFailsWith<UnsupportedPlayerException>(expression) { compile("return ($expression)?x+'!':x;") }
        }
        assertEquals("true:false:false:false", compile("var a=[];return [a==a,a==[],a==null,a===x].join(':');").decipherSignature("a"))
    }

    @Test fun nativeMethodsUseTheInvocationReceiver() {
        assertEquals("abc:zy", compile("var a=x.split(''),b=['y','z'];a.reverse.call(b);return a.join('')+':'+b.join('');").decipherSignature("abc"))
        assertEquals("abc:yz!", compile("var a=x.split(''),b=['y','z'];a.push.apply(b,['!']);return a.join('')+':'+b.join('');").decipherSignature("abc"))
        assertEquals("yz", compile("var a=x.split(''),b=['y','z'];return a.join.call(b,'');").decipherSignature("abc"))
        assertEquals("z", compile("return x.slice.call('yz',1);").decipherSignature("abc"))
        assertEquals("121", compile("return x.charCodeAt.call('yz',0).toString();").decipherSignature("abc"))
        assertEquals("true", compile("var a=x.split(''),f=function(){return this===a};return String(f.call(a));").decipherSignature("abc"))
        assertEquals("true", compile("var f=function(v){return v===undefined};return String(f.apply(null,[,]));").decipherSignature("abc"))
        assertEquals("abc?", compile("var f=function(v){return v+'!'},g=function(v){return v+'?'},call=f.call;return call.call(g,null,x);").decipherSignature("abc"))
        assertEquals("abc?", compile("var f=function(v){return v+'!'},g=function(v){return v+'?'},apply=f.apply;return apply.call(g,null,[x]);").decipherSignature("abc"))
        assertEquals("caught", compile("try{String.call.call(null)}catch(e){return 'caught'}return x;").decipherSignature("abc"))
        assertFailsWith<UnsupportedPlayerException> { compile("return x.split('').reverse.call({}).join('');") }
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

    @Test fun nullEqualityAndNumericWhitespaceMatchJavaScript() {
        assertEquals("false:false:false:true:false:1:NaN:NaN", compile("return [false==null,0==null,''==null,null==undefined,null===undefined,Number('\\ufeff1'),Number('\\u001c1'),Number('0x\\uff11')].join(':');").decipherSignature("x"))
        assertEquals("1:1:1:1", compile("return [Number('\\u00a01'),Number('\\u16801'),Number('\\u20001'),Number('\\u202f1')].join(':');").decipherSignature("x"))
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

    @Test fun dispatcherFoldingPreservesWritesCapturesAndInputDependentBranches() {
        val forward = compile("return dispatch(2,3,x);", extra = "var dispatch=function(a,b,x){var before=key,key=a^b;return before+':'+key+':'+x};")
        assertEquals("undefined:1:z", forward.decipherSignature("z"))
        val duplicate = compile("return dispatch(2,3,x);", extra = "var dispatch=function(a,b,a){return a+':'+b};")
        assertEquals("z:3", duplicate.decipherSignature("z"))
        val mutable = compile("return dispatch(2,3,x);",  extra = "var dispatch=function(a,b,x){var key=a^b;if(x.length<3){a=9;key=8}return (a+b)+':'+key+':'+x};")
        assertEquals("12:8:y", mutable.decipherSignature("y"))
        assertEquals("5:1:long", mutable.decipherSignature("long"))
        val captured = compile("return dispatch(2,3,x);", extra = "var dispatch=function(a,b,x){var key=a^b,change=function(){a=x.length;key++};change();return a+':'+b+':'+key+':'+x};")
        assertEquals("1:3:2:z", captured.decipherSignature("z"))
        assertEquals("4:3:2:long", captured.decipherSignature("long"))
        val shadowed = compile("return dispatch(2,3,x);", extra = "var dispatch=function(a,b,x){var key=a^b;try{throw x}catch(key){return key+':'+a+':'+b}};")
        assertEquals("z:2:3", shadowed.decipherSignature("z"))
        val fallback = compile("return dispatch(x.length,3,x);", extra = "var dispatch=function(a,b,x){var key=a^b;return key+':'+x};")
        for (input in listOf("a", "ab", "abc", "abcd")) assertEquals("${input.length xor 3}:$input", fallback.decipherSignature(input))
        val global = compile("letters.reverse();return dispatch(2,3,x);", extra = "var letters=['a','b','c'];var dispatch=function(a,b,x){return letters[(a+b)%3]+x};")
        repeat(3) { assertEquals("az", global.decipherSignature("z")) }
        assertEquals("2:3:z", compile("return dispatch(2,3,x);", extra = "var dispatch=function(a,b,x){return arguments[0]+':'+arguments[1]+':'+x};").decipherSignature("z"))
    }

    @Test fun dispatcherFoldingReadsLiveGlobalTables() {
        val declaration = "var letters='a;b'.split(';');"
        for ((mutation, expected) in listOf(
            "letters[1]=x;" to "zz",
            "if(x)letters[1]=x;" to "zz",
            "delete letters[1];" to "undefinedz",
            "++letters[1];" to "NaNz",
            "var alias=letters;alias[1]=x;" to "zz",
            "var alias={value:letters};alias.value[1]=x;" to "zz",
            "letters['reverse']();" to "az",
            "(letters)[1]=x;" to "zz",
        )) {
            val extra = declaration + "var dispatch=function(a,b,x){$mutation return letters[a^b]+x};"
            assertEquals(expected, compile("return dispatch(2,3,x);", extra = extra).decipherSignature("z"), mutation)
        }
        val late = compile("return dispatch(2,3,x);", extra = declaration + "var dispatch=function(a,b,x){if(x.length<3)mutate(x);return letters[a^b]+x};var mutate=function(x){letters[1]=x};")
        assertEquals("zz", late.decipherSignature("z"))
        val readOnly = compile("return dispatch(2,3,x);", extra = declaration + "var dispatch=function(a,b,x){return letters[a^b]+x};")
        for (input in listOf("a", "bc", "arbitrary-input")) assertEquals("b$input", readOnly.decipherSignature(input))
    }

    @Test fun slotBindingsPreserveCatchAndClosureScopes() {
        assertEquals("caught:outer:x", compile("var value='outer',fn;try{throw 'caught'}catch(value){fn=function(){return value}}return fn()+':'+value+':'+x;").decipherSignature("x"))
        assertEquals("abc:2", compile("var i=0,f=function(){i++;return function(){i++;return x+':'+i}};return f()();").decipherSignature("abc"))
        assertEquals("y:3", compile("var f=function(a,a){return a+':'+arguments.length};return f(x,'y','z');").decipherSignature("abc"))
    }

    @Test fun forEachFramesDoNotLeakLocalsArgumentsOrCapturedVariables() {
        assertEquals("onceb", compile("var out=[];x.split('').forEach(function(v,i){var previous;if(i==0)previous='once';out.push(previous===undefined?v:previous)});return out.join('');").decipherSignature("ab"))
        assertEquals("a1:b1:a2", compile("var out=[];x.split('').forEach(function(v){var count=0;out.push(function(){return v+(++count)})});return out[0]()+':'+out[1]()+':'+out[0]();").decipherSignature("ab"))
        assertEquals("ab", compile("var out=[];x.split('').forEach(function(){out.push(arguments)});return out[0][0]+out[1][0];").decipherSignature("ab"))
        assertEquals("caught", compile("try{[].forEach(null)}catch(e){return 'caught'}return x;").decipherSignature("ab"))
        assertEquals("0:0:0:0", compile("return [Math.floor(null),Math.ceil(null),Math.abs(null),Math.trunc(null)].join(':');").decipherSignature("ab"))
        assertFailsWith<UnsupportedPlayerException> { compile("return new Date('1970-01-01T00:00:00Z')+x;") }
        assertFailsWith<UnsupportedPlayerException> { compile("try{return new Date('1970-01-01T00:00:00Z').getTime()+x}catch(e){return x}") }
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

    @Test fun emptyAndSparseForEachDoNotAllocateCallbackFrames() {
        val locals = (0 until 10_000).joinToString(",") { "v$it" }
        for (array in listOf("[]", "Array(8)")) {
            val program = compile("var callback=function(){var $locals;throw 'must not run'};for(var i=0;i<200;i++)$array.forEach(callback);return x;")
            assertEquals("abc", program.decipherSignature("abc"))
        }
    }

    @Test fun dateAndNumericDispatchSemantics() {
        val p = compile("return String.fromCharCode(65,65536+66)+':'+(new Date('1970-01-01T09:45:22.000+09:45')/1000);")
        assertEquals("AB:22", p.decipherSignature("abc"))
        assertEquals("0:0", compile("return (('='/2)|0)+':'+(('} '/2)|0);").decipherSignature("abc"))
        assertEquals("abab", compile("return 'ab'.repeat(2.9);").decipherSignature("x"))
        assertEquals("", compile("return 'x'.repeat(-0.1);").decipherSignature("x"))
        assertEquals("", compile("return 'x'.repeat();").decipherSignature("x"))
        assertEquals("caught:caught", compile("var a,b;try{'x'.repeat(-1)}catch(e){a='caught'}try{''.repeat(1/0)}catch(e){b='caught'}return a+':'+b;").decipherSignature("x"))
        assertEquals("xx", compile("return 'x'.repeat('2');").decipherSignature("x"))
        assertFailsWith<UnsupportedPlayerException> { compile("return 'ab'.repeat(40000);").decipherSignature("x") }
    }
}
