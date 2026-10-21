package meow.nyx.quackjs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NRootRuntimeLane30Test {
    private fun compile(
        constructor: String = "this.raw=raw;this.enabled=enabled;this.V={};",
        getter: String = "applyN(7,19,this);return this.V[k];",
        update: String = "o.set('n',o.V.n.split('').reverse().join(''));",
        setter: String = "this.V[k]=v;",
        extra: String = "",
    ): PlayerProgram = PlayerProgram.compile("""
        var g={};
        var sig=function(x){return x;};
        g.C=function(raw,enabled){$constructor};
        g.C.prototype.set=function(k,v){$setter};
        g.C.prototype.get=function(k){$getter};
        var applyN=function(a,b,o){var key=a^b;if(key==20){$update}};
        $extra
    """.trimIndent(), PlayerHints("sig(INPUT)", "C", 12345))

    @Test fun constructorAndSetterKeepFreshGlobalEffects() {
        val program = compile(
            constructor = "this.raw=raw;this.enabled=enabled;this.V={};state.count++;state.value='constructed';this.count=state.count;",
            setter = "state.count++;this.V[k]=state.value+':'+state.count+':'+v;",
            update = "o.set('n',o.count+':'+o.enabled+':'+o.raw+':'+o.V.n.split('').reverse().join(''));",
            extra = "var state={count:0,value:'initial'};",
        )
        for (input in listOf("abc", "different", "", "abc")) {
            assertEquals("constructed:2:1:true::${input.reversed()}", program.transformN(input))
            assertEquals(input, program.decipherSignature(input))
        }
    }

    @Test fun constructorDependenciesClosuresAndTableIdentityRemainLive() {
        val program = compile(
            constructor = "this.V=createTable();this.V.owner=this;this.tables=[this.V];var table=this.V;this.same=function(){return table===this.V};",
            update = "if(o.V.owner!==o||o.tables[0]!==o.V||!o.same())throw 'identity';o.tables[0].n=o.V.n.split('').reverse().join('');o.set('n',state.count+':'+o.V.n);",
            extra = "var state={count:0};var createTable=function(){state.count++;return {}};",
        )
        for (input in listOf("abc", "longer-challenge", "", "abc")) {
            assertEquals("1:${input.reversed()}", program.transformN(input))
        }
    }

    @Test fun inputDerivedAndGlobalArgumentsAreEvaluatedAfterConstruction() {
        val program = compile(
            constructor = "this.raw=raw;this.V={};mode.value='live';",
            getter = "var value=this.V.n;rootN(7,19,this,value,value.length-1,mode.value);this.raw.replace('x','y');return this.V[k];",
            extra = """
                var mode={value:'initial'};
                var rootN=function(a,b,o,value,size,tag){o.set('n',tag+':'+value.slice(0,size).split('').reverse().join(''));};
            """.trimIndent(),
        )
        for (input in listOf("abcdef", "xy", "", "\u2603\ud83d\ude00", "another-challenge")) {
            assertEquals("live:${input.dropLast(1).reversed()}", program.transformN(input))
        }
    }

    @Test fun arbitraryArgumentListsAndRootReceiversRemainSupported() {
        val program = compile(
            getter = "rootN.call(this,7,19,'tag',true,null,void 0);this.raw.replace('x','y');return this.V[k];",
            extra = """
                var rootN=function(a,b,tag,flag,nil,missing){
                    this.set('n',[a,b,tag,flag,nil===null,missing===undefined,arguments.length,this.V.n.split('').reverse().join('')].join(':'));
                };
            """.trimIndent(),
        )
        for (input in listOf("", "abc", "different", "\u2603\ud83d\ude00")) {
            val reversedUnits = input.toList().reversed().joinToString("")
            assertEquals("7:19:tag:true:true:true:6:$reversedUnits", program.transformN(input))
        }
        val noArguments = compile(
            getter = "rootN.call(this);this.raw.replace('x','y');return this.V[k];",
            extra = "var rootN=function(){this.set('n',this.V.n.split('').reverse().join(''));};",
        )
        assertEquals("cba", noArguments.transformN("abc"))
    }

    @Test fun resultIsReadFromTheCurrentRootTable() {
        val program = compile(update = "o.V={n:o.V.n.split('').reverse().join('')};")
        for (input in listOf("abc", "", "another-challenge")) assertEquals(input.reversed(), program.transformN(input))
    }

    @Test fun argumentEvaluationAndInvocationStillEnforceResourceBounds() {
        val program = compile(
            getter = "rootN(this,this.V.n+this.V.n+this.V.n+this.V.n+this.V.n);this.raw.replace('x','y');return this.V[k];",
            extra = "var rootN=function(o,value){o.set('n',value.split('').reverse().join(''));};",
        )
        assertEquals("cba".repeat(5), program.transformN("abc"))
        assertEquals("a".repeat(65_000), program.transformN("a".repeat(13_000)))
        val sizeError = assertFailsWith<UnsupportedPlayerException> { program.transformN("a".repeat(16_384)) }
        assertTrue(sizeError.message!!.contains("String size limit"))
        assertFailsWith<UnsupportedPlayerException> { program.transformN("a".repeat(16_385)) }
        val loop = compile(update = "if(o.V.n=='evil'){try{for(;;){}}catch(e){o.set('n','caught')}}o.set('n',o.V.n.split('').reverse().join(''));")
        val stepError = assertFailsWith<UnsupportedPlayerException> { loop.transformN("evil") }
        assertTrue(stepError.message!!.contains("step limit"))
        assertEquals("cba", loop.transformN("abc"))
    }
}
