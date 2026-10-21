package meow.nyx.quackjs

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

internal data object Undefined
internal data object Hole
internal class JsObject(val fields: MutableMap<String, Any?> = mutableMapOf(), val builtin: String? = null)
internal class JsArray(val values: MutableList<Any?>, val fields: MutableMap<String, Any?> = mutableMapOf())
internal class JsFunction(val code: FunctionExpr, val env: Env)
internal class Native(val fields: Map<String, Any?> = emptyMap(), val constructible: Boolean = false, val callable: Boolean = true, val invoke: (Runtime, Any?, List<Any?>) -> Any?)
internal class JsDate(val time: Double)
internal class JsThrown(val value: Any?) : Exception()
private class Returned(val value: Any?) : Exception()
private class Jumped(val jump: Jump) : Exception()
private class Reference(val read: () -> Any?, val write: (Any?) -> Unit)
internal class Env(val parent: Env? = null) {
    val values = mutableMapOf<String, Any?>()
    fun owner(name: String): Env? = if (values.containsKey(name)) this else parent?.owner(name)
}

internal class Runtime(val source: SourceIndex, parsed: Map<String, Expr> = emptyMap()) {
    val definitions = parsed.toMutableMap()
    private val global = Env()
    private val resolving = mutableSetOf<String>()
    private var steps = 0
    private var allocated = 0
    private var calls = 0
    var challenge: String? = null
    private var handlers = 0
    private var expressionDepth = 0
    fun tick(cost: Int = 1) { steps += cost; if (steps > 2_000_000) unsupported("Execution step limit exceeded") }
    fun allocate(size: Int) {
        if (size < 0 || size > 65_536) unsupported("Value size limit exceeded")
        allocated += size + 1
        if (allocated > 2_000_000) unsupported("Allocation limit exceeded")
    }
    fun array(items: List<Any?>): JsArray { allocate(items.size); return JsArray(items.toMutableList()) }
    fun string(value: String): String { allocate(value.length); return value }
    fun resolve(name: String): Any? {
        global.values[name]?.let { return it }
        if (global.values.containsKey(name)) return global.values[name]
        val builtin = builtin(name)
        if (builtin !== Missing) { global.values[name] = builtin; return builtin }
        if (!resolving.add(name)) unsupported("Circular initializer '$name'")
        if (resolving.size > 64 || definitions.size > 512) unsupported("Dependency limit exceeded")
        try {
            val expr = definitions.getOrPut(name) { source.definition(name) }
            val value = eval(expr, global)
            global.values[name] = value
            return value
        } finally { resolving.remove(name) }
    }
    fun eval(expr: Expr, env: Env): Any? {
        tick()
        if (++expressionDepth > 256) unsupported("Expression execution depth limit exceeded")
        try { return when (expr) {
            is Lit -> expr.value
            is Id -> env.owner(expr.name)?.values?.get(expr.name) ?: if (env.owner(expr.name) != null) null else resolve(expr.name)
            is Unary -> when (expr.op) {
                "typeof" -> {
                    val value = if (expr.expr is Id && env.owner(expr.expr.name) == null && !source.hasDefinition(expr.expr.name) && builtin(expr.expr.name) === Missing) Undefined else eval(expr.expr, env)
                    when (value) { Undefined -> "undefined"; null -> "object"; is String -> "string"; is Double, is Int -> "number"; is Boolean -> "boolean"; is JsFunction, is Native -> "function"; else -> "object" }
                }
                "delete" -> {
                    val target = expr.expr as? Member ?: unsupported("Unsupported delete target")
                    val obj = eval(target.target, env); val key = text(eval(target.key, env))
                    when (obj) {
                        is JsObject -> obj.fields.remove(key)
                        is JsArray -> { val i = index(key); if (i != null && i < obj.values.size) obj.values[i] = Hole else obj.fields.remove(key) }
                        else -> unsupported("Unsupported delete target")
                    }; true
                }
                "void" -> { eval(expr.expr, env); Undefined }
                "!" -> !truth(eval(expr.expr, env))
                "~" -> int(eval(expr.expr, env)).inv().toDouble()
                "+" -> number(eval(expr.expr, env))
                "-" -> -number(eval(expr.expr, env))
                else -> unsupported("Unsupported unary operator ${expr.op}")
            }
            is Binary -> {
                when (expr.op) {
                    "=" -> { val ref = reference(expr.left, env); val value = eval(expr.right, env); ref.write(value); value }
                    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" -> {
                        val ref = reference(expr.left, env)
                        val value = binary(expr.op.dropLast(1), ref.read(), eval(expr.right, env))
                        ref.write(value); value
                    }
                    "&&" -> eval(expr.left, env).let { if (truth(it)) eval(expr.right, env) else it }
                    "||" -> eval(expr.left, env).let { if (truth(it)) it else eval(expr.right, env) }
                    "??" -> eval(expr.left, env).let { if (it == null || it === Undefined) eval(expr.right, env) else it }
                    "," -> { eval(expr.left, env); eval(expr.right, env) }
                    else -> binary(expr.op, eval(expr.left, env), eval(expr.right, env))
                }
            }
            is Conditional -> eval(if (truth(eval(expr.test, env))) expr.yes else expr.no, env)
            is Member -> get(eval(expr.target, env), text(eval(expr.key, env)))
            is Call -> {
                var self: Any? = Undefined
                val fn = if (expr.fn is Member) {
                    self = eval(expr.fn.target, env)
                    val key = text(eval(expr.fn.key, env))
                    val method = get(self, key)
                    if (method === Undefined && (self is String || self is JsArray || self is Native) && identifierProperty(key) && key !in setOf("NaN", "Infinity", "undefined")) unsupported("Unsupported built-in method '$key'")
                    method
                } else eval(expr.fn, env)
                val args = expr.args.map { eval(it, env) }
                if (expr.construct) {
                    if (fn !is Native) unsupported("Only built-in transform constructors are supported")
                    invoke(fn, Undefined, args, construct = true)
                } else invoke(fn, self, args)
            }
            is Update -> {
                val ref = reference(expr.target, env)
                val old = number(ref.read()); val value = old + if (expr.op == "++") 1 else -1
                ref.write(value); if (expr.prefix) value else old
            }
            is ArrayExpr -> array(expr.items.map { eval(it, env) })
            is ObjectExpr -> { allocate(expr.items.size); JsObject(expr.items.associate { it.first to eval(it.second, env) }.toMutableMap()) }
            is FunctionExpr -> { allocate(1); JsFunction(expr, env) }
        } } finally { expressionDepth-- }
    }
    fun expression(expr: Expr, values: Map<String, Any?> = emptyMap()): Any? = eval(expr, Env(global).also { it.values.putAll(values) })
    fun functionEnv(fn: JsFunction, self: Any?, args: List<Any?>): Env {
        val env = Env(fn.env)
        env.values["this"] = self
        fn.code.params.forEachIndexed { i, name -> env.values[name] = args.getOrElse(i) { Undefined } }
        env.values["arguments"] = array(args)
        fn.code.name?.let { env.values[it] = fn }
        hoist(fn.code.body, env)
        return env
    }
    fun invoke(fn: Any?, self: Any?, args: List<Any?>, construct: Boolean = false): Any? {
        tick()
        if (++calls > 64) unsupported("Call depth limit exceeded")
        try {
            return when (fn) {
                is Native -> {
                    if (construct && !fn.constructible || !construct && !fn.callable) unsupported("Unsupported built-in constructor/call")
                    fn.invoke(this, self, args)
                }
                is JsFunction -> {
                    val env = functionEnv(fn, self, args)
                    try { exec(fn.code.body, env); Undefined } catch (r: Returned) { r.value }
                    catch (_: Jumped) { unsupported("Invalid or unsupported jump from transform function") }
                }
                else -> throw JsThrown("Value is not callable")
            }
        } finally { calls-- }
    }
    private fun hoist(stmt: Stmt, env: Env) {
        when (stmt) {
            is Vars -> stmt.items.forEach { if (!env.values.containsKey(it.first)) env.values[it.first] = Undefined }
            is Block -> stmt.statements.forEach { hoist(it, env) }
            is IfStmt -> { hoist(stmt.yes, env); stmt.no?.let { hoist(it, env) } }
            is Loop -> { stmt.init?.let { hoist(it, env) }; hoist(stmt.body, env) }
            is Label -> hoist(stmt.body, env)
            is TryStmt -> { hoist(stmt.body, env); stmt.handler?.let { hoist(it, env) }; stmt.finalizer?.let { hoist(it, env) } }
            is SwitchStmt -> stmt.cases.forEach { it.second.forEach { child -> hoist(child, env) } }
            else -> Unit
        }
    }
    fun exec(stmt: Stmt, env: Env) {
        tick()
        when (stmt) {
            is Block -> stmt.statements.forEach { exec(it, env) }
            is ExpressionStmt -> { eval(stmt.expr, env) }
            is Vars -> stmt.items.forEach { (name, expr) -> if (expr != null) (env.owner(name) ?: env).values[name] = eval(expr, env) }
            is IfStmt -> if (truth(eval(stmt.test, env))) exec(stmt.yes, env) else stmt.no?.let { exec(it, env) }
            is ReturnStmt -> throw Returned(if (stmt.expr == null) Undefined else eval(stmt.expr, env))
            is ThrowStmt -> throw JsThrown(eval(stmt.expr, env))
            is Loop -> {
                stmt.init?.let { exec(it, env) }
                var first = true
                while (stmt.doFirst && first || stmt.test == null || truth(eval(stmt.test, env))) {
                    tick(); first = false
                    try { exec(stmt.body, env) } catch (jump: Jumped) {
                        if (jump.jump.label != null) throw jump
                        if (!jump.jump.isContinue) break
                    }
                    stmt.update?.let { eval(it, env) }
                }
            }
            is Jump -> { if (stmt.isContinue && stmt.label != null) unsupported("Labeled continue is unsupported"); throw Jumped(stmt) }
            is Label -> try { exec(stmt.body, env) } catch (jump: Jumped) {
                if (jump.jump.label != stmt.name || jump.jump.isContinue) throw jump
            }
            is TryStmt -> {
                // Unsupported syntax and resource failures are deliberately not JavaScript exceptions.
                var pending: Exception? = null
                try {
                    try { exec(stmt.body, env) } catch (error: JsThrown) {
                        if (stmt.handler == null) throw error
                        val catchEnv = Env(env); catchEnv.values[stmt.catchName!!] = error.value
                        handlers++
                        try { exec(stmt.handler, catchEnv) } finally { handlers-- }
                    }
                } catch (error: JsThrown) { pending = error }
                catch (returned: Returned) { pending = returned }
                catch (jumped: Jumped) { pending = jumped }
                stmt.finalizer?.let { exec(it, env) }
                if (pending != null) throw pending
            }
            is SwitchStmt -> {
                val value = eval(stmt.expr, env)
                var start = stmt.cases.indexOfFirst { it.first != null && equal(value, eval(it.first!!, env), true) }
                if (start < 0) start = stmt.cases.indexOfFirst { it.first == null }
                if (start >= 0) try {
                    for (i in start until stmt.cases.size) stmt.cases[i].second.forEach { exec(it, env) }
                } catch (jump: Jumped) { if (jump.jump.isContinue || jump.jump.label != null) throw jump }
            }
            EmptyStmt -> Unit
        }
    }
    private fun reference(expr: Expr, env: Env): Reference = when (expr) {
        is Id -> {
            val owner = env.owner(expr.name) ?: unsupported("Assignment to undeclared '${expr.name}'")
            Reference({ owner.values[expr.name] }, { value -> owner.values[expr.name] = value })
        }
        is Member -> {
            val obj = eval(expr.target, env); val key = text(eval(expr.key, env))
            Reference({ get(obj, key) }, { value -> set(obj, key, value) })
        }
        else -> unsupported("Invalid assignment target")
    }
    fun set(obj: Any?, key: String, value: Any?) {
        when (obj) {
            is JsObject -> { if (!obj.fields.containsKey(key)) allocate(1); obj.fields[key] = value }
            is JsArray -> {
                if (key == "length") {
                    val length = number(value)
                    if (length < 0 || length != floor(length)) throw JsThrown("Invalid array length")
                    if (length > 65_536) unsupported("Array size limit exceeded")
                    val size = length.toInt()
                    if (size > obj.values.size) { allocate(size - obj.values.size); repeat(size - obj.values.size) { obj.values.add(Hole) } }
                    else while (obj.values.size > size) obj.values.removeAt(obj.values.lastIndex)
                } else {
                    val numeric = key.toLongOrNull()
                    if (numeric != null && numeric in 65_536L..4_294_967_294L && key == numeric.toString()) unsupported("Array index limit exceeded")
                    val i = index(key)
                    if (i == null) { if (!obj.fields.containsKey(key)) allocate(1); obj.fields[key] = value }
                    else {
                        if (i >= 65_536) unsupported("Array index limit exceeded")
                        if (i >= obj.values.size) { allocate(i + 1 - obj.values.size); repeat(i + 1 - obj.values.size) { obj.values.add(Hole) } }
                        obj.values[i] = value
                    }
                }
            }
            null, Undefined -> throw JsThrown("Cannot set property on null/undefined")
            else -> unsupported("Unsupported property assignment")
        }
    }
    fun get(obj: Any?, key: String): Any? {
        tick()
        if (obj == null || obj === Undefined) throw JsThrown("Cannot read '$key' on null/undefined")
        if ((obj is JsFunction || obj is Native) && key == "call") return Native { r, _, a -> r.invoke(obj, a.firstOrNull() ?: Undefined, a.drop(1)) }
        if ((obj is JsFunction || obj is Native) && key == "apply") return Native { r, _, a ->
            val args = a.getOrNull(1)
            r.invoke(obj, a.firstOrNull() ?: Undefined, if (args == null || args === Undefined) emptyList() else (args as? JsArray)?.values?.toList() ?: throw JsThrown("Invalid apply arguments"))
        }
        if (obj is Native) return if (obj.fields.containsKey(key)) obj.fields[key] else unsupported("Unsupported built-in property '$key'")
        if (obj is JsFunction) return when (key) {
            "length" -> obj.code.params.size.toDouble()
            "name", "prototype", "constructor", "bind", "caller", "arguments", "toString", "valueOf" -> unsupported("Unsupported function property '$key'")
            else -> Undefined
        }
        if (obj is JsObject) return obj.fields[key] ?: if (obj.fields.containsKey(key)) null else if (obj.builtin != null) unsupported("Unsupported ${obj.builtin}.$key") else Undefined
        if (obj is JsArray) {
            if (key == "length") return obj.values.size.toDouble()
            index(key)?.let { return obj.values.getOrElse(it) { Undefined }.let { item -> if (item === Hole) Undefined else item } }
            if (obj.fields.containsKey(key)) return obj.fields[key]
            return arrayMethod(obj, key)
        }
        if (obj is String) {
            if (key == "length") return obj.length.toDouble()
            index(key)?.let { return if (it < obj.length) obj[it].toString() else Undefined }
            return stringMethod(obj, key)
        }
        if (key == "toString" && (obj is Double || obj is Int || obj is Boolean)) return Native { _, _, a ->
            if (a.isNotEmpty() && number(a[0]) != 10.0) unsupported("Unsupported number radix")
            text(obj)
        }
        // Regexes may be opaque constants, but must never silently execute with Kotlin regex semantics.
        if (obj is RegexValue) unsupported("Unsupported regex operation '$key'")
        return Undefined
    }
    private fun arrayMethod(arr: JsArray, name: String): Any? {
        val v = arr.values
        return when (name) {
            "reverse" -> Native { _, _, _ -> tick(v.size); v.reverse(); arr }
            "join", "toString" -> Native { _, _, a ->
                val sep = if (name == "toString" || a.isEmpty() || a[0] === Undefined) "," else text(a[0])
                val size = v.sumOf { if (it == null || it === Undefined || it === Hole) 0L else text(it).length.toLong() } + (v.size - 1).coerceAtLeast(0).toLong() * sep.length
                if (size > 65_536) unsupported("String size limit exceeded")
                string(v.joinToString(sep) { if (it == null || it === Undefined || it === Hole) "" else text(it) })
            }
            "push" -> Native { _, _, a -> allocate(a.size); if (v.size + a.size > 65_536) unsupported("Array size limit exceeded"); v.addAll(a); v.size.toDouble() }
            "unshift" -> Native { _, _, a -> allocate(a.size); if (v.size + a.size > 65_536) unsupported("Array size limit exceeded"); v.addAll(0, a); v.size.toDouble() }
            "pop" -> Native { _, _, _ -> if (v.isEmpty()) Undefined else v.removeAt(v.lastIndex).let { if (it === Hole) Undefined else it } }
            "shift" -> Native { _, _, _ -> if (v.isEmpty()) Undefined else { tick(v.size); v.removeAt(0).let { if (it === Hole) Undefined else it } } }
            "slice" -> Native { _, _, a -> val start = relative(a.getOrElse(0) { 0.0 }, v.size); val end = if (a.size < 2 || a[1] === Undefined) v.size else relative(a[1], v.size); array(v.subList(start, end.coerceAtLeast(start))) }
            "splice" -> Native { _, _, a ->
                val start = relative(a.getOrElse(0) { 0.0 }, v.size)
                val count = if (a.isEmpty()) 0 else if (a.size < 2) v.size - start else integer(a[1]).coerceIn(0, v.size - start)
                val added = a.drop(2)
                if (v.size - count + added.size > 65_536) unsupported("Array size limit exceeded")
                val removed = array(v.subList(start, start + count)); allocate(added.size); tick(v.size)
                v.subList(start, start + count).clear(); v.addAll(start, added); removed
            }
            "forEach" -> Native { r, _, a ->
                val length = v.size
                for (i in 0 until length) { tick(); if (i < v.size && v[i] !== Hole) r.invoke(a.getOrElse(0) { Undefined }, a.getOrElse(1) { Undefined }, listOf(v[i], i.toDouble(), arr)) }
                Undefined
            }
            "indexOf" -> Native { _, _, a -> val start = relative(a.getOrElse(1) { 0.0 }, v.size); var result = -1; for (i in start until v.size) { tick(); if (v[i] !== Hole && equal(v[i], a.getOrElse(0) { Undefined }, true)) { result = i; break } }; result.toDouble() }
            else -> Undefined
        }
    }
    private fun stringMethod(value: String, name: String): Any? = when (name) {
        "split" -> Native { _, _, a ->
            if (a.firstOrNull() is RegexValue) unsupported("Regex split is unsupported")
            val limit = if (a.size < 2 || a[1] === Undefined) 4_294_967_295L else uint(a[1])
            val sep = a.firstOrNull()
            val parts = mutableListOf<Any?>()
            fun addPart(start: Int, end: Int) {
                tick()
                if (parts.size >= 65_536) unsupported("Array size limit exceeded")
                if (start == 0 && end == value.length) parts.add(value)
                else { allocate(end - start); parts.add(value.substring(start, end)) }
            }
            if (limit > 0) {
                if (a.isEmpty() || sep === Undefined) addPart(0, value.length)
                else {
                    val separator = text(sep)
                    var start = 0
                    if (separator.isEmpty()) {
                        while (start < value.length && parts.size.toLong() < limit) { addPart(start, start + 1); start++ }
                    } else while (parts.size.toLong() < limit) {
                        val end = value.indexOf(separator, start)
                        if (end < 0) { addPart(start, value.length); break }
                        addPart(start, end)
                        start = end + separator.length
                    }
                }
            }
            array(parts)
        }
        "slice", "substring", "substr" -> Native { _, _, a ->
            val rawStart = a.getOrElse(0) { 0.0 }
            var start = if (name == "substring") integer(rawStart).coerceIn(0, value.length) else relative(rawStart, value.length)
            var end = if (a.size < 2 || a[1] === Undefined) value.length else if (name == "slice") relative(a[1], value.length) else if (name == "substr") (start.toLong() + integer(a[1]).coerceAtLeast(0)).coerceAtMost(value.length.toLong()).toInt() else integer(a[1]).coerceIn(0, value.length)
            if (name == "substring" && start > end) { val temp = start; start = end; end = temp }
            string(value.substring(start, end.coerceAtLeast(start)))
        }
        "charCodeAt", "charAt" -> Native { _, _, a -> val i = integer(a.getOrElse(0) { 0.0 }); if (i !in value.indices) if (name == "charAt") "" else Double.NaN else if (name == "charAt") value[i].toString() else value[i].code.toDouble() }
        "indexOf", "lastIndexOf" -> Native { _, _, a ->
            val search = text(a.getOrElse(0) { Undefined }); val from = if (a.size < 2 || a[1] === Undefined || name == "lastIndexOf" && number(a[1]).isNaN()) if (name == "indexOf") 0 else value.length else integer(a[1]).coerceIn(0, value.length)
            if (name == "indexOf") value.indexOf(search, from).toDouble() else value.lastIndexOf(search, from).toDouble()
        }
        "startsWith", "endsWith", "includes" -> Native { _, _, a ->
            if (a.firstOrNull() is RegexValue) throw JsThrown("Regex argument")
            val search = text(a.getOrElse(0) { Undefined }); val pos = if (a.size < 2 || a[1] === Undefined) if (name == "endsWith") value.length else 0 else integer(a[1]).coerceIn(0, value.length)
            when (name) { "startsWith" -> value.startsWith(search, pos); "endsWith" -> value.substring(0, pos).endsWith(search); else -> value.indexOf(search, pos) >= 0 }
        }
        "toString", "valueOf" -> Native { _, _, _ -> value }
        else -> Undefined
    }
    private fun binary(op: String, left: Any?, right: Any?): Any? = when (op) {
        "+" -> { val a = primitive(left); val b = primitive(right); if (a is String || b is String) { val x = text(a); val y = text(b); if (handlers > 0 && challenge != null && y == challenge && x.isNotEmpty()) unsupported("Transform entered an exception fallback"); if (x.length.toLong() + y.length > 65_536) unsupported("String size limit exceeded"); string(x + y) } else number(a) + number(b) }
        "-" -> number(left) - number(right); "*" -> number(left) * number(right); "/" -> number(left) / number(right); "%" -> number(left) % number(right); "**" -> number(left).pow(number(right))
        "^" -> (int(left) xor int(right)).toDouble(); "&" -> (int(left) and int(right)).toDouble(); "|" -> (int(left) or int(right)).toDouble()
        "<<" -> (int(left) shl (int(right) and 31)).toDouble(); ">>" -> (int(left) shr (int(right) and 31)).toDouble(); ">>>" -> (int(left).toUInt() shr (int(right) and 31)).toDouble()
        "==" -> equal(left, right, false); "!=" -> !equal(left, right, false); "===" -> equal(left, right, true); "!==" -> !equal(left, right, true)
        "<", ">", "<=", ">=" -> {
            val a = primitive(left); val b = primitive(right)
            if (a is String && b is String) when (op) { "<" -> a < b; ">" -> a > b; "<=" -> a <= b; else -> a >= b }
            else { val x = number(a); val y = number(b); when (op) { "<" -> x < y; ">" -> x > y; "<=" -> x <= y; else -> x >= y } }
        }
        "in" -> { val key = text(left); when (right) { is JsObject -> right.fields.containsKey(key); is JsArray -> index(key)?.let { it < right.values.size && right.values[it] !== Hole } ?: right.fields.containsKey(key); else -> throw JsThrown("Invalid right-hand side of in") } }
        else -> unsupported("Unsupported operator '$op'")
    }
    private fun builtin(name: String): Any? = when (name) {
        "undefined" -> Undefined; "NaN" -> Double.NaN; "Infinity" -> Double.POSITIVE_INFINITY
        "String" -> stringConstructor
        "Number" -> numberConstructor
        "Date" -> Native(constructible = true, callable = false) { _, _, a -> JsDate(if (a.firstOrNull() is String) parseDate(a[0] as String) else unsupported("Only constant ISO dates are supported")) }
        "Math" -> math
        "Array" -> Native(mapOf("isArray" to Native { _, _, a -> a.firstOrNull() is JsArray }), constructible = true) { r, _, a -> if (a.size == 1 && a[0] is Double) { val n = number(a[0]); if (n != floor(n) || n < 0 || n > 65_536) unsupported("Array size limit exceeded"); r.allocate(n.toInt()); JsArray(MutableList(n.toInt()) { Hole }) } else r.array(a) }
        "Error", "TypeError", "RangeError" -> Native(constructible = true) { _, _, a -> JsObject(mutableMapOf("name" to name, "message" to if (a.isEmpty() || a[0] === Undefined) "" else text(a[0]))) }
        "Object" -> objectConstructor
        else -> Missing
    }
    private val stringConstructor get() = Native(mapOf(
        "fromCharCode" to Native { r, _, a -> r.string(a.map { (int(it) and 65535).toChar() }.joinToString("")) }
    )) { r, _, a -> r.string(if (a.isEmpty()) "" else text(a[0])) }
    private val numberConstructor get() = Native(mapOf(
        "isNaN" to Native { _, _, a -> (a.firstOrNull() as? Double)?.isNaN() ?: false },
        "isFinite" to Native { _, _, a -> (a.firstOrNull() as? Double)?.isFinite() ?: false }
    )) { _, _, a -> if (a.isEmpty()) 0.0 else number(a[0]) }
    private val objectConstructor get() = Native(mapOf(
        "assign" to Native { r, _, a -> val target = a.firstOrNull() as? JsObject ?: unsupported("Unsupported Object.assign target"); a.drop(1).forEach { obj -> if (obj is JsObject) obj.fields.forEach { (k, v) -> r.set(target, k, v) } else if (obj != null && obj !== Undefined) unsupported("Unsupported Object.assign source") }; target }
    ), constructible = true) { _, _, a ->
        val value = a.firstOrNull()
        if (value == null || value === Undefined) JsObject() else if (value is JsObject || value is JsArray || value is JsFunction || value is Native) value else unsupported("Primitive object boxing is unsupported")
    }
    private val math get() = JsObject(mutableMapOf(
        "floor" to Native { _, _, a -> floor(number(a.firstOrNull() ?: Undefined)) },
        "ceil" to Native { _, _, a -> ceil(number(a.firstOrNull() ?: Undefined)) },
        "abs" to Native { _, _, a -> abs(number(a.firstOrNull() ?: Undefined)) },
        "trunc" to Native { _, _, a -> val n = number(a.firstOrNull() ?: Undefined); if (n < 0) ceil(n) else floor(n) },
        "max" to Native { _, _, a -> a.map(::number).maxOrNull() ?: Double.NEGATIVE_INFINITY },
        "min" to Native { _, _, a -> a.map(::number).minOrNull() ?: Double.POSITIVE_INFINITY }
    ), "Math")
    private data object Missing
}

private fun identifierProperty(name: String): Boolean = SourceIndex.identifier.matches(name)

internal fun truth(value: Any?): Boolean = when (value) { null, Undefined, Hole -> false; is Boolean -> value; is Double -> value != 0.0 && !value.isNaN(); is Int -> value != 0; is String -> value.isNotEmpty(); else -> true }
internal fun primitive(value: Any?): Any? = when (value) { is JsDate -> value.time; is JsArray -> unsupported("Implicit array coercion is unsupported"); is JsObject -> unsupported("Implicit object coercion is unsupported"); else -> value }
internal fun number(value: Any?): Double = when (val v = primitive(value)) { null -> 0.0; Undefined, Hole -> Double.NaN; is Double -> v; is Int -> v.toDouble(); is Boolean -> if (v) 1.0 else 0.0; is String -> numericString(v); else -> Double.NaN }
private fun numericString(value: String): Double {
    val s = value.trim()
    if (s.isEmpty()) return 0.0
    if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
    if (s == "-Infinity") return Double.NEGATIVE_INFINITY
    val radix = when { s.startsWith("0x", true) -> 16; s.startsWith("0b", true) -> 2; s.startsWith("0o", true) -> 8; else -> 0 }
    if (radix != 0) {
        if (s.length == 2) return Double.NaN
        var n = 0.0
        for (c in s.drop(2)) { val digit = c.digitToIntOrNull(radix) ?: return Double.NaN; n = n * radix + digit }
        return n
    }
    if (!Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$").matches(s)) return Double.NaN
    return s.toDoubleOrNull() ?: Double.NaN
}
internal fun int(value: Any?): Int { val n = number(value); if (!n.isFinite() || n == 0.0) return 0; val t = if (n < 0) ceil(n) else floor(n); return (t % 4294967296.0).toLong().toInt() }
internal fun uint(value: Any?): Long = int(value).toUInt().toLong()
internal fun integer(value: Any?): Int { val n = number(value); return when { n.isNaN() -> 0; n >= Int.MAX_VALUE -> Int.MAX_VALUE; n <= Int.MIN_VALUE -> Int.MIN_VALUE; else -> n.toInt() } }
internal fun relative(value: Any?, length: Int): Int { val n = integer(value); return if (n < 0) (length.toLong() + n).coerceAtLeast(0).toInt() else n.coerceAtMost(length) }
internal fun index(key: String): Int? { if (key.isEmpty() || key.length > 10 || key.length > 1 && key[0] == '0' || key.any { it !in '0'..'9' }) return null; return key.toIntOrNull()?.takeIf { it >= 0 } }
internal fun text(value: Any?): String = when (value) {
    null -> "null"; Undefined -> "undefined"; is String -> value; is Boolean -> value.toString(); is Int -> value.toString()
    is Double -> when { value.isNaN() -> "NaN"; value == Double.POSITIVE_INFINITY -> "Infinity"; value == Double.NEGATIVE_INFINITY -> "-Infinity"; value == 0.0 -> "0"; abs(value) <= 9007199254740991.0 && value == floor(value) -> value.toLong().toString(); else -> unsupported("Non-integer number string coercion is unsupported") }
    is RegexValue -> value.source
    else -> unsupported("Unsupported string coercion")
}
internal fun equal(a: Any?, b: Any?, strict: Boolean): Boolean {
    if (a === b) return a !is Double || !a.isNaN()
    if (a is Double && b is Double) return a == b
    if (a is String && b is String) return a == b
    if (a is Boolean && b is Boolean) return a == b
    if (!strict) {
        if ((a == null || a === Undefined) && (b == null || b === Undefined)) return true
        if (a is Boolean || b is Boolean || a is Double && b is String || a is String && b is Double) return number(a) == number(b)
        if (a is JsDate || b is JsDate) return number(a) == number(b)
    }
    return false
}

private fun parseDate(value: String): Double {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?(Z|[+-]\\d{2}:\\d{2})$").matchEntire(value) ?: unsupported("Unsupported date literal")
    val p = match.groupValues
    val year = p[1].toInt(); val month = p[2].toInt(); val day = p[3].toInt()
    if (year !in 1900..2100 || month !in 1..12 || day !in 1..31 || p[4].toInt() > 23 || p[5].toInt() > 59 || p[6].toInt() > 59) unsupported("Invalid date literal")
    fun daysBefore(y: Int): Int { val n = y - 1; return 365 * n + n / 4 - n / 100 + n / 400 }
    val months = intArrayOf(31, if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var days = daysBefore(year) - daysBefore(1970) + day - 1
    for (m in 1 until month) days += months[m - 1]
    val zone = p[8]
    if (zone != "Z" && (zone.substring(1, 3).toInt() > 23 || zone.substring(4, 6).toInt() > 59)) unsupported("Invalid date offset")
    val offset = if (zone == "Z") 0 else (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()) * if (zone[0] == '-') -1 else 1
    return days * 86400000.0 + p[4].toInt() * 3600000.0 + (p[5].toInt() - offset) * 60000.0 + p[6].toInt() * 1000.0 + (p[7].toIntOrNull() ?: 0)
}
