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
private class Returned(val value: Any?)
internal class Env(val parent: Env? = null, private val names: Map<String, Int>? = null) {
    private var entries: MutableMap<String, Any?>? = null
    var revision: Int = 0
        private set
    val values: MutableMap<String, Any?> get() = entries ?: mutableMapOf<String, Any?>().also { entries = it }
    val slots: Array<Any?> = Array(names?.size ?: 0) { Undefined }
    val frame: Env? = if (names != null) this else parent?.frame
    fun owner(name: String): Env? = if (names?.containsKey(name) == true || entries?.containsKey(name) == true) this else parent?.owner(name)
    operator fun get(name: String): Any? { val slot = names?.get(name); return if (slot == null) entries?.get(name) else slots[slot] }
    operator fun set(name: String, value: Any?) { val slot = names?.get(name); if (slot == null) { values[name] = value; revision++ } else slots[slot] = value }
}

internal class Runtime(val source: SourceIndex, private val parsed: Map<String, Expr> = emptyMap(), private val templates: Map<String, Any?> = emptyMap(), private val dates: Map<String, Double> = emptyMap(), private val dispatch: Map<DispatchKey, Block> = emptyMap(), recordDispatch: Boolean = false, globalCount: Int = 0) {
    val definitions = mutableMapOf<String, Expr>()
    val constantDates = mutableMapOf<String, Double>()
    val specializedBodies: MutableMap<DispatchKey, Block>? = if (recordDispatch) mutableMapOf() else null
    private val global = Env()
    private val globalSlots: Array<Any?> = Array(globalCount) { Missing }
    private var globalRevision = 0
    private val resolving = mutableSetOf<String>()
    private var steps = 0
    private var allocated = 0
    private var calls = 0
    var challenge: String? = null
    private var handlers = 0
    private var expressionDepth = 0
    fun tick(cost: Int = 1) { if (cost < 0 || cost > 2_000_000 - steps) unsupported("Execution step limit exceeded"); steps += cost }
    fun allocate(size: Int) {
        if (size < 0 || size > 65_536) unsupported("Value size limit exceeded")
        allocated += size + 1
        if (allocated > 2_000_000) unsupported("Allocation limit exceeded")
    }
    fun array(items: List<Any?>): JsArray { allocate(items.size); return JsArray(items.toMutableList()) }
    fun string(value: String): String { allocate(value.length); return value }
    private fun number(value: Any?): Double { if (value is String) tick(value.length); return meow.nyx.quackjs.number(value) }
    private fun int(value: Any?): Int { if (value is String) tick(value.length); return meow.nyx.quackjs.int(value) }
    private fun uint(value: Any?): Long { if (value is String) tick(value.length); return meow.nyx.quackjs.uint(value) }
    private fun integer(value: Any?): Int { if (value is String) tick(value.length); return meow.nyx.quackjs.integer(value) }
    private fun relative(value: Any?, length: Int): Int { if (value is String) tick(value.length); return meow.nyx.quackjs.relative(value, length) }
    private fun find(value: String, search: String, from: Int, backwards: Boolean = false): Int {
        tick()
        if (search.isEmpty()) return from
        if (search.length == 1) {
            val result = if (backwards) value.lastIndexOf(search[0], from) else value.indexOf(search[0], from)
            val cost = if (backwards) {
                val start = minOf(from, value.lastIndex)
                if (result < 0) start + 1 else start - result + 1
            } else if (result < 0) value.length - from else result - from + 1
            tick(cost)
            return result
        }
        val last = value.length - search.length
        val candidates = if (backwards) minOf(from, last) downTo 0 else from..last
        for (index in candidates) {
            var matched = 0
            while (matched < search.length) {
                tick()
                if (value[index + matched] != search[matched]) break
                matched++
            }
            if (matched == search.length) return index
        }
        return -1
    }
    private fun equal(a: Any?, b: Any?, strict: Boolean): Boolean {
        if (a is String && b is String) { if (a.length == b.length) tick(a.length) }
        else if (!strict) { if (a is String) tick(a.length); if (b is String) tick(b.length) }
        return meow.nyx.quackjs.equal(a, b, strict)
    }
    fun resolve(name: String): Any? {
        global.values[name]?.let { return it }
        if (global.values.containsKey(name)) return global.values[name]
        val builtin = builtin(name)
        if (builtin !== Missing) { global.values[name] = builtin; return builtin }
        if (!resolving.add(name)) unsupported("Circular initializer '$name'")
        if (resolving.size > 64 || parsed.size + definitions.size > 512) unsupported("Dependency limit exceeded")
        try {
            val expr = parsed[name] ?: definitions.getOrPut(name) { source.definition(name) }
            val value = if (templates.containsKey(name)) copyTemplate(templates[name]) else eval(expr, global)
            global.values[name] = value
            return value
        } finally { resolving.remove(name) }
    }
    fun eval(expr: Expr, env: Env): Any? {
        tick()
        if (expr is Lit) return expr.value
        if (expr is Id) return if (expr.slot >= 0) env.frame!!.slots[expr.slot] else if (expr.free && env.frame?.parent === global) globalValue(expr) else { val owner = env.owner(expr.name); if (owner != null) owner[expr.name] else resolve(expr.name) }
        return evalComplex(expr, env)
    }
    private fun globalValue(id: Id): Any? {
        if (id.globalSlot < 0) return resolve(id.name)
        if (globalRevision != global.revision) { globalSlots.fill(Missing); globalRevision = global.revision }
        val value = globalSlots[id.globalSlot]
        if (value !== Missing) return value
        return resolve(id.name).also { globalSlots[id.globalSlot] = it }
    }
    private fun evalComplex(expr: Expr, env: Env): Any? {
        if (++expressionDepth > 256) unsupported("Expression execution depth limit exceeded")
        try { return when (expr) {
            is Lit, is Id -> error("Leaf expression in complex evaluator")
            is Unary -> when (expr.op) {
                "typeof" -> {
                    val value = if (expr.expr is Id && env.owner(expr.expr.name) == null && !source.hasDefinition(expr.expr.name) && builtin(expr.expr.name) === Missing) Undefined else eval(expr.expr, env)
                    when (value) { Undefined -> "undefined"; null -> "object"; is String -> "string"; is Double, is Int -> "number"; is Boolean -> "boolean"; is JsFunction, is Native -> "function"; else -> "object" }
                }
                "delete" -> {
                    val target = expr.expr as? Member ?: unsupported("Unsupported delete target")
                    val obj = eval(target.target, env); val key = text(eval(target.key, env))
                    tick(key.length)
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
                    "=" -> assign(expr.left, env, read = false) { eval(expr.right, env) }
                    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" -> assign(expr.left, env, read = true) {
                        binary(expr.op.dropLast(1), it, eval(expr.right, env))
                    }
                    "&&" -> eval(expr.left, env).let { if (truth(it)) eval(expr.right, env) else it }
                    "||" -> eval(expr.left, env).let { if (truth(it)) it else eval(expr.right, env) }
                    "??" -> eval(expr.left, env).let { if (it == null || it === Undefined) eval(expr.right, env) else it }
                    "," -> { eval(expr.left, env); eval(expr.right, env) }
                    else -> binary(expr.op, eval(expr.left, env), eval(expr.right, env))
                }
            }
            is Conditional -> eval(if (truth(eval(expr.test, env))) expr.yes else expr.no, env)
            is Member -> property(eval(expr.target, env), eval(expr.key, env))
            is Call -> {
                var self: Any? = Undefined
                val fn = if (expr.fn is Member) {
                    self = eval(expr.fn.target, env)
                    val key = eval(expr.fn.key, env)
                    if (!expr.construct && key == "call" && (self is JsFunction || self is Native)) {
                        if (expr.args.isEmpty()) return invoke(self, Undefined, emptyList())
                        val receiver = eval(expr.args[0], env)
                        return invoke(self, receiver, arguments(expr.args, env, 1))
                    }
                    val method = property(self, key)
                    if (method === Undefined && key is String && (self is String || self is JsArray || self is Native) && identifierProperty(key) && key !in setOf("NaN", "Infinity", "undefined")) unsupported("Unsupported built-in method '$key'")
                    method
                } else eval(expr.fn, env)
                val args = arguments(expr.args, env)
                if (expr.construct) {
                    if (fn !is Native) unsupported("Only built-in transform constructors are supported")
                    invoke(fn, Undefined, args, construct = true)
                } else invoke(fn, self, args)
            }
            is Update -> {
                var old = 0.0
                val value = assign(expr.target, env, read = true) {
                    old = number(it)
                    old + if (expr.op == "++") 1 else -1
                }
                if (expr.prefix) value else old
            }
            is ArrayExpr -> array(expr.items.map { eval(it, env) })
            is ObjectExpr -> { allocate(expr.items.size); JsObject(expr.items.associate { it.first to eval(it.second, env) }.toMutableMap()) }
            is FunctionExpr -> { allocate(1); JsFunction(expr, env) }
        } } finally { expressionDepth-- }
    }
    private fun arguments(args: List<Expr>, env: Env, start: Int = 0): List<Any?> = when (args.size - start) {
        0 -> emptyList()
        1 -> listOf(eval(args[start], env))
        2 -> listOf(eval(args[start], env), eval(args[start + 1], env))
        3 -> listOf(eval(args[start], env), eval(args[start + 1], env), eval(args[start + 2], env))
        else -> ArrayList<Any?>(args.size - start).also { values ->
            for (i in start until args.size) values.add(eval(args[i], env))
        }
    }
    fun function(code: FunctionExpr): JsFunction = JsFunction(code, global)
    fun expression(expr: Expr, values: Map<String, Any?> = emptyMap()): Any? = eval(expr, Env(global).also { it.values.putAll(values) })
    fun functionEnv(fn: JsFunction, self: Any?, args: List<Any?>, reuse: Env? = null): Env {
        if (reuse == null) allocate(fn.code.slots.size)
        tick(fn.code.slots.size)
        val env = reuse ?: Env(fn.env, fn.code.slots)
        if (reuse != null) env.slots.fill(Undefined)
        env.slots[fn.code.thisSlot] = self
        for (i in fn.code.parameterSlots.indices) env.slots[fn.code.parameterSlots[i]] = args.getOrElse(i) { Undefined }
        if (fn.code.usesArguments) env.slots[fn.code.argumentsSlot] = array(args)
        if (fn.code.nameSlot >= 0) env.slots[fn.code.nameSlot] = fn
        return env
    }
    fun invoke(fn: Any?, self: Any?, args: List<Any?>, construct: Boolean = false, reuse: Env? = null): Any? {
        tick()
        allocate(args.size)
        if (++calls > 64) unsupported("Call depth limit exceeded")
        try {
            return when (fn) {
                is Native -> {
                    if (construct && !fn.constructible || !construct && !fn.callable) unsupported("Unsupported built-in constructor/call")
                    fn.invoke(this, self, args)
                }
                is JsFunction -> {
                    val env = functionEnv(fn, self, args, reuse)
                    val key = if (args.size >= 2 && args[0] is Double && args[1] is Double) DispatchKey(fn.code, args[0] as Double, args[1] as Double) else null
                    val body = if (key == null) fn.code.body else dispatch[key] ?: specializedBodies?.get(key) ?: if (specializedBodies != null && specializedBodies.size < 256) specialize(fn.code, key.first, key.second, this).also { specializedBodies[key] = it } else fn.code.body
                    when (val flow = exec(body, env)) {
                        is Returned -> flow.value
                        is Jump -> unsupported("Invalid or unsupported jump from transform function")
                        else -> Undefined
                    }
                }
                else -> throw JsThrown("Value is not callable")
            }
        } finally { calls-- }
    }
    private fun copyTemplate(value: Any?): Any? = when (value) {
        is JsArray -> array(value.values.map(::copyTemplate))
        is JsObject -> { allocate(value.fields.size); JsObject(value.fields.mapValues { copyTemplate(it.value) }.toMutableMap()) }
        is JsFunction -> { allocate(1); JsFunction(value.code, global) }
        else -> value
    }
    // Return and jump are ordinary interpreter control flow, not stack-trace-bearing exceptions.
    fun exec(stmt: Stmt, env: Env): Any? {
        tick()
        when (stmt) {
            is Block -> for (child in stmt.statements) { val flow = exec(child, env); if (flow != null) return flow }
            is ExpressionStmt -> { eval(stmt.expr, env) }
            is Vars -> stmt.items.forEach { (name, expr) -> if (expr != null) (env.owner(name) ?: env)[name] = eval(expr, env) }
            is IfStmt -> return if (truth(eval(stmt.test, env))) exec(stmt.yes, env) else stmt.no?.let { exec(it, env) }
            is ReturnStmt -> return Returned(if (stmt.expr == null) Undefined else eval(stmt.expr, env))
            is ThrowStmt -> throw JsThrown(eval(stmt.expr, env))
            is Loop -> {
                stmt.init?.let { exec(it, env) }
                var first = true
                while (stmt.doFirst && first || stmt.test == null || truth(eval(stmt.test, env))) {
                    tick(); first = false
                    val flow = exec(stmt.body, env)
                    if (flow is Jump && flow.label == null) { if (!flow.isContinue) break }
                    else if (flow != null) return flow
                    stmt.update?.let { eval(it, env) }
                }
            }
            is Jump -> { if (stmt.isContinue && stmt.label != null) unsupported("Labeled continue is unsupported"); return stmt }
            is Label -> {
                val flow = exec(stmt.body, env)
                if (flow !is Jump || flow.label != stmt.name || flow.isContinue) return flow
            }
            is TryStmt -> {
                // Unsupported syntax and resource failures are deliberately not JavaScript exceptions.
                var pending: JsThrown? = null
                val flow = try {
                    try { exec(stmt.body, env) } catch (error: JsThrown) {
                        if (stmt.handler == null) throw error
                        val catchEnv = Env(env); catchEnv.values[stmt.catchName!!] = error.value
                        handlers++
                        try { exec(stmt.handler, catchEnv) } finally { handlers-- }
                    }
                } catch (error: JsThrown) { pending = error; null }
                val finalFlow = stmt.finalizer?.let { exec(it, env) }
                if (finalFlow != null) return finalFlow
                if (pending != null) throw pending
                return flow
            }
            is SwitchStmt -> {
                val value = eval(stmt.expr, env)
                var start = stmt.cases.indexOfFirst { it.first != null && equal(value, eval(it.first!!, env), true) }
                if (start < 0) start = stmt.cases.indexOfFirst { it.first == null }
                if (start >= 0) for (i in start until stmt.cases.size) for (child in stmt.cases[i].second) {
                    val flow = exec(child, env)
                    if (flow is Jump && !flow.isContinue && flow.label == null) return null
                    if (flow != null) return flow
                }
            }
            EmptyStmt -> Unit
        }
        return null
    }
    // Keep the target and its old value stable across RHS mutations without allocating a reference.
    private inline fun assign(expr: Expr, env: Env, read: Boolean, value: (Any?) -> Any?): Any? = when (expr) {
        is Id -> {
            if (expr.slot >= 0) {
                val slots = env.frame!!.slots
                val result = value(if (read) slots[expr.slot] else Undefined)
                slots[expr.slot] = result
                result
            } else {
                val owner = env.owner(expr.name) ?: unsupported("Assignment to undeclared '${expr.name}'")
                val result = value(if (read) owner[expr.name] else Undefined)
                owner[expr.name] = result
                result
            }
        }
        is Member -> {
            val obj = eval(expr.target, env); val key = eval(expr.key, env)
            val result = value(if (read) property(obj, key) else Undefined)
            val i = numericIndex(key)
            if (obj is JsArray && i != null) setIndex(obj, i, result) else set(obj, text(key), result)
            result
        }
        else -> unsupported("Invalid assignment target")
    }
    fun set(obj: Any?, key: String, value: Any?) {
        tick(key.length)
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
                    else setIndex(obj, i, value)
                }
            }
            null, Undefined -> throw JsThrown("Cannot set property on null/undefined")
            else -> unsupported("Unsupported property assignment")
        }
    }
    private fun setIndex(obj: JsArray, i: Int, value: Any?) {
        if (i >= 65_536) unsupported("Array index limit exceeded")
        if (i >= obj.values.size) { allocate(i + 1 - obj.values.size); repeat(i + 1 - obj.values.size) { obj.values.add(Hole) } }
        obj.values[i] = value
    }
    private fun numericIndex(key: Any?): Int? = if (key is Double && key >= 0 && key < 65_536 && key == key.toInt().toDouble()) key.toInt() else null
    private fun property(obj: Any?, key: Any?): Any? {
        val i = numericIndex(key)
        if (i != null) when (obj) {
            is JsArray -> { tick(); val value = obj.values.getOrElse(i) { Undefined }; return if (value === Hole) Undefined else value }
            is String -> { tick(); return if (i < obj.length) obj[i].toString() else Undefined }
        }
        return get(obj, text(key))
    }
    fun get(obj: Any?, key: String): Any? {
        tick(key.length + 1)
        if (obj == null || obj === Undefined) throw JsThrown("Cannot read '$key' on null/undefined")
        if ((obj is JsFunction || obj is Native) && key == "call") return callMethod
        if ((obj is JsFunction || obj is Native) && key == "apply") return applyMethod
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
            return arrayMethods[key] ?: Undefined
        }
        if (obj is String) {
            if (key == "length") return obj.length.toDouble()
            index(key)?.let { return if (it < obj.length) obj[it].toString() else Undefined }
            return stringMethods[key] ?: Undefined
        }
        if (key == "toString" && (obj is Double || obj is Int || obj is Boolean)) return Native { r, self, a ->
            if (obj is Boolean && self !is Boolean || obj !is Boolean && self !is Double && self !is Int) throw JsThrown("Invalid primitive method receiver")
            if (a.isNotEmpty() && r.number(a[0]) != 10.0) unsupported("Unsupported number radix")
            text(self)
        }
        if (obj is JsDate) unsupported("Unsupported date property '$key'")
        // Regexes may be opaque constants, but must never silently execute with Kotlin regex semantics.
        if (obj is RegexValue) unsupported("Unsupported regex operation '$key'")
        return Undefined
    }
    private companion object {
    // Native methods are receiver-driven and immutable; only Runtime and the receiver own state.
    val callMethod = Native { r, self, a -> r.invoke(self, a.getOrElse(0) { Undefined }, a.drop(1)) }
    val applyMethod = Native { r, self, a ->
        val args = a.getOrNull(1)
        val items = if (args == null || args === Undefined) emptyList() else (args as? JsArray)?.values ?: throw JsThrown("Invalid apply arguments")
        r.tick(items.size); r.allocate(items.size)
        r.invoke(self, a.getOrElse(0) { Undefined }, items.map { if (it === Hole) Undefined else it })
    }
    val stringConstructor = Native(mapOf(
        "fromCharCode" to Native { r, _, a -> r.tick(a.size); r.string(a.map { (r.int(it) and 65535).toChar() }.joinToString("")) }
    )) { r, _, a -> r.string(if (a.isEmpty()) "" else text(a[0])) }
    val numberConstructor = Native(mapOf(
        "isNaN" to Native { _, _, a -> (a.firstOrNull() as? Double)?.isNaN() ?: false },
        "isFinite" to Native { _, _, a -> (a.firstOrNull() as? Double)?.isFinite() ?: false }
    )) { r, _, a -> if (a.isEmpty()) 0.0 else r.number(a[0]) }
    val arrayMethods = listOf("reverse", "join", "toString", "push", "unshift", "pop", "shift", "slice", "splice", "forEach", "indexOf").associateWith(::arrayMethod)
    val stringMethods = listOf("split", "slice", "substring", "substr", "charCodeAt", "charAt", "indexOf", "lastIndexOf", "startsWith", "endsWith", "includes", "toString", "valueOf").associateWith(::stringMethod)
    private fun arrayMethod(name: String): Any? {
        return when (name) {
            "reverse" -> Native { r, self, _ -> val target = self as? JsArray ?: unsupported("Unsupported array method receiver"); r.tick(target.values.size); target.values.reverse(); target }
            "join", "toString" -> Native { r, self, a ->
                val v = (self as? JsArray ?: unsupported("Unsupported array method receiver")).values
                r.tick(v.size)
                val sep = if (name == "toString" || a.isEmpty() || a[0] === Undefined) "," else text(a[0])
                val result = StringBuilder()
                for (i in v.indices) {
                    val item = v[i]
                    val part = if (item == null || item === Undefined || item === Hole) "" else text(item)
                    if (result.length.toLong() + part.length + (if (i == 0) 0 else sep.length) > 65_536) unsupported("String size limit exceeded")
                    if (i > 0) result.append(sep)
                    result.append(part)
                }
                r.string(result.toString())
            }
            "push" -> Native { r, self, a -> val v = arrayValues(self); r.allocate(a.size); if (v.size + a.size > 65_536) unsupported("Array size limit exceeded"); v.addAll(a); v.size.toDouble() }
            "unshift" -> Native { r, self, a -> val v = arrayValues(self); r.tick(v.size); r.allocate(a.size); if (v.size + a.size > 65_536) unsupported("Array size limit exceeded"); v.addAll(0, a); v.size.toDouble() }
            "pop" -> Native { _, self, _ -> val v = arrayValues(self); if (v.isEmpty()) Undefined else v.removeAt(v.lastIndex).let { if (it === Hole) Undefined else it } }
            "shift" -> Native { r, self, _ -> val v = arrayValues(self); if (v.isEmpty()) Undefined else { r.tick(v.size); v.removeAt(0).let { if (it === Hole) Undefined else it } } }
            "slice" -> Native { r, self, a -> val v = arrayValues(self); val start = r.relative(a.getOrElse(0) { 0.0 }, v.size); val end = if (a.size < 2 || a[1] === Undefined) v.size else r.relative(a[1], v.size); r.tick(end.coerceAtLeast(start) - start); r.array(v.subList(start, end.coerceAtLeast(start))) }
            "splice" -> Native { r, self, a ->
                val v = arrayValues(self)
                val start = r.relative(a.getOrElse(0) { 0.0 }, v.size)
                val count = if (a.isEmpty()) 0 else if (a.size < 2) v.size - start else r.integer(a[1]).coerceIn(0, v.size - start)
                val added = a.drop(2)
                if (v.size - count + added.size > 65_536) unsupported("Array size limit exceeded")
                val removed = r.array(v.subList(start, start + count)); r.allocate(added.size); r.tick(v.size)
                v.subList(start, start + count).clear(); v.addAll(start, added); removed
            }
            "forEach" -> Native { r, self, a ->
                val target = self as? JsArray ?: unsupported("Unsupported array method receiver")
                val v = target.values
                val length = v.size
                val callback = a.getOrElse(0) { Undefined }
                if (callback !is JsFunction && callback !is Native) throw JsThrown("Invalid forEach callback")
                val selfArg = a.getOrElse(1) { Undefined }
                // Without closures or arguments, a callback cannot retain its invocation frame.
                var frame: Env? = null
                for (i in 0 until length) {
                    r.tick()
                    if (i < v.size && v[i] !== Hole) {
                        if (frame == null && callback is JsFunction && !callback.code.hasClosures && !callback.code.usesArguments) {
                            frame = r.functionEnv(callback, selfArg, emptyList())
                        }
                        r.invoke(callback, selfArg, listOf(v[i], i.toDouble(), target), reuse = frame)
                    }
                }
                Undefined
            }
            "indexOf" -> Native { r, self, a ->
                val v = arrayValues(self)
                val start = r.relative(a.getOrElse(1) { 0.0 }, v.size)
                val search = a.getOrElse(0) { Undefined }
                var result = -1
                if (search is String && search.length == 1 || search is Boolean) {
                    val found = if (start == 0) v.indexOf(search) else v.subList(start, v.size).indexOf(search)
                    r.tick(if (found < 0) v.size - start else found + 1)
                    if (found >= 0) result = start + found
                } else for (i in start until v.size) { r.tick(); if (v[i] !== Hole && r.equal(v[i], search, true)) { result = i; break } }
                result.toDouble()
            }
            else -> Undefined
        }
    }
    private fun arrayValues(self: Any?): MutableList<Any?> = (self as? JsArray ?: unsupported("Unsupported array method receiver")).values
    private fun stringReceiver(self: Any?): String = if (self == null || self === Undefined) throw JsThrown("Invalid string method receiver") else text(self)
    private fun stringMethod(name: String): Any? = when (name) {
        "split" -> Native { r, self, a ->
            val value = stringReceiver(self)
            if (a.firstOrNull() is RegexValue) unsupported("Regex split is unsupported")
            val limit = if (a.size < 2 || a[1] === Undefined) 4_294_967_295L else r.uint(a[1])
            val sep = a.firstOrNull()
            val parts = mutableListOf<Any?>()
            fun addPart(start: Int, end: Int) {
                r.tick(end - start + 1)
                if (parts.size >= 65_536) unsupported("Array size limit exceeded")
                if (start == 0 && end == value.length) parts.add(value)
                else { r.allocate(end - start); parts.add(value.substring(start, end)) }
            }
            if (limit > 0) {
                if (a.isEmpty() || sep === Undefined) addPart(0, value.length)
                else {
                    val separator = text(sep)
                    var start = 0
                    if (separator.isEmpty()) {
                        while (start < value.length && parts.size.toLong() < limit) { addPart(start, start + 1); start++ }
                    } else while (parts.size.toLong() < limit) {
                        val end = r.find(value, separator, start)
                        if (end < 0) { addPart(start, value.length); break }
                        addPart(start, end)
                        start = end + separator.length
                    }
                }
            }
            r.array(parts)
        }
        "slice", "substring", "substr" -> Native { r, self, a ->
            val value = stringReceiver(self)
            val rawStart = a.getOrElse(0) { 0.0 }
            var start = if (name == "substring") r.integer(rawStart).coerceIn(0, value.length) else r.relative(rawStart, value.length)
            var end = if (a.size < 2 || a[1] === Undefined) value.length else if (name == "slice") r.relative(a[1], value.length) else if (name == "substr") (start.toLong() + r.integer(a[1]).coerceAtLeast(0)).coerceAtMost(value.length.toLong()).toInt() else r.integer(a[1]).coerceIn(0, value.length)
            if (name == "substring" && start > end) { val temp = start; start = end; end = temp }
            r.tick(end.coerceAtLeast(start) - start)
            r.string(value.substring(start, end.coerceAtLeast(start)))
        }
        "charCodeAt", "charAt" -> Native { r, self, a -> val value = stringReceiver(self); val i = r.integer(a.getOrElse(0) { 0.0 }); if (i !in value.indices) if (name == "charAt") "" else Double.NaN else if (name == "charAt") value[i].toString() else value[i].code.toDouble() }
        "indexOf", "lastIndexOf" -> Native { r, self, a ->
            val value = stringReceiver(self)
            val search = text(a.getOrElse(0) { Undefined })
            val from = if (a.size < 2 || a[1] === Undefined || name == "lastIndexOf" && r.number(a[1]).isNaN()) if (name == "indexOf") 0 else value.length else r.integer(a[1]).coerceIn(0, value.length)
            r.find(value, search, from, backwards = name == "lastIndexOf").toDouble()
        }
        "startsWith", "endsWith", "includes" -> Native { r, self, a ->
            val value = stringReceiver(self)
            if (a.firstOrNull() is RegexValue) throw JsThrown("Regex argument")
            val search = text(a.getOrElse(0) { Undefined })
            if (name != "includes") r.tick(value.length)
            val pos = if (a.size < 2 || a[1] === Undefined) if (name == "endsWith") value.length else 0 else r.integer(a[1]).coerceIn(0, value.length)
            when (name) { "startsWith" -> value.startsWith(search, pos); "endsWith" -> value.substring(0, pos).endsWith(search); else -> r.find(value, search, pos) >= 0 }
        }
        "toString", "valueOf" -> Native { _, self, _ -> self as? String ?: throw JsThrown("Invalid string method receiver") }
        else -> Undefined
    }
    }
    private fun binary(op: String, left: Any?, right: Any?): Any? = when (op) {
        "+" -> { if (left is JsDate || right is JsDate) unsupported("Default date coercion is unsupported"); val a = primitive(left); val b = primitive(right); if (a is String || b is String) { val x = text(a); val y = text(b); if (handlers > 0 && challenge != null && y == challenge && x.isNotEmpty()) unsupported("Transform entered an exception fallback"); if (x.length.toLong() + y.length > 65_536) unsupported("String size limit exceeded"); string(x + y) } else number(a) + number(b) }
        "-" -> number(left) - number(right); "*" -> number(left) * number(right); "/" -> number(left) / number(right); "%" -> number(left) % number(right); "**" -> number(left).pow(number(right))
        "^" -> (int(left) xor int(right)).toDouble(); "&" -> (int(left) and int(right)).toDouble(); "|" -> (int(left) or int(right)).toDouble()
        "<<" -> (int(left) shl (int(right) and 31)).toDouble(); ">>" -> (int(left) shr (int(right) and 31)).toDouble(); ">>>" -> (int(left).toUInt() shr (int(right) and 31)).toDouble()
        "==" -> equal(left, right, false); "!=" -> !equal(left, right, false); "===" -> equal(left, right, true); "!==" -> !equal(left, right, true)
        "<", ">", "<=", ">=" -> {
            val a = primitive(left); val b = primitive(right)
            if (a is String && b is String) { tick(minOf(a.length, b.length)); when (op) { "<" -> a < b; ">" -> a > b; "<=" -> a <= b; else -> a >= b } }
            else { val x = number(a); val y = number(b); when (op) { "<" -> x < y; ">" -> x > y; "<=" -> x <= y; else -> x >= y } }
        }
        "in" -> { val key = text(left); tick(key.length); when (right) { is JsObject -> right.fields.containsKey(key); is JsArray -> index(key)?.let { it < right.values.size && right.values[it] !== Hole } ?: right.fields.containsKey(key); else -> throw JsThrown("Invalid right-hand side of in") } }
        else -> unsupported("Unsupported operator '$op'")
    }
    private fun builtin(name: String): Any? = when (name) {
        "undefined" -> Undefined; "NaN" -> Double.NaN; "Infinity" -> Double.POSITIVE_INFINITY
        "String" -> stringConstructor
        "Number" -> numberConstructor
        "Date" -> Native(constructible = true, callable = false) { _, _, a ->
            allocate(1)
            val value = a.firstOrNull() as? String ?: unsupported("Only constant ISO dates are supported")
            JsDate(dates[value] ?: constantDates[value] ?: parseDate(value).also { if (constantDates.size < 256) constantDates[value] = it })
        }
        "Math" -> math
        "Array" -> Native(mapOf("isArray" to Native { _, _, a -> a.firstOrNull() is JsArray }), constructible = true) { r, _, a -> if (a.size == 1 && a[0] is Double) { val n = number(a[0]); if (n != floor(n) || n < 0 || n > 65_536) unsupported("Array size limit exceeded"); r.allocate(n.toInt()); JsArray(MutableList(n.toInt()) { Hole }) } else r.array(a) }
        "Error", "TypeError", "RangeError" -> Native(constructible = true) { _, _, a -> JsObject(mutableMapOf("name" to name, "message" to if (a.isEmpty() || a[0] === Undefined) "" else text(a[0]))) }
        "Object" -> objectConstructor
        else -> Missing
    }
    private val objectConstructor get() = Native(mapOf(
        "assign" to Native { r, _, a -> val target = a.firstOrNull() as? JsObject ?: unsupported("Unsupported Object.assign target"); a.drop(1).forEach { obj -> if (obj is JsObject) obj.fields.forEach { (k, v) -> r.set(target, k, v) } else if (obj != null && obj !== Undefined) unsupported("Unsupported Object.assign source") }; target }
    ), constructible = true) { _, _, a ->
        val value = a.firstOrNull()
        if (value == null || value === Undefined) JsObject() else if (value is JsObject || value is JsArray || value is JsFunction || value is Native) value else unsupported("Primitive object boxing is unsupported")
    }
    private val math get() = JsObject(mutableMapOf(
        "floor" to Native { _, _, a -> floor(number(a.getOrElse(0) { Undefined })) },
        "ceil" to Native { _, _, a -> ceil(number(a.getOrElse(0) { Undefined })) },
        "abs" to Native { _, _, a -> abs(number(a.getOrElse(0) { Undefined })) },
        "trunc" to Native { _, _, a -> val n = number(a.getOrElse(0) { Undefined }); if (n < 0) ceil(n) else floor(n) },
        "max" to Native { _, _, a -> a.map(::number).maxOrNull() ?: Double.NEGATIVE_INFINITY },
        "min" to Native { _, _, a -> a.map(::number).minOrNull() ?: Double.POSITIVE_INFINITY }
    ), "Math")
    private data object Missing
}

private fun identifierProperty(name: String): Boolean = SourceIndex.identifier(name)

internal fun truth(value: Any?): Boolean = when (value) { is Boolean -> value; is Double -> value != 0.0 && !value.isNaN(); is Int -> value != 0; is String -> value.isNotEmpty(); null, Undefined, Hole -> false; else -> true }
internal fun primitive(value: Any?): Any? = when (value) { is JsDate -> value.time; is JsArray -> unsupported("Implicit array coercion is unsupported"); is JsObject -> unsupported("Implicit object coercion is unsupported"); else -> value }
internal fun number(value: Any?): Double = if (value is Double) value else when (val v = primitive(value)) { null -> 0.0; Undefined, Hole -> Double.NaN; is Double -> v; is Int -> v.toDouble(); is Boolean -> if (v) 1.0 else 0.0; is String -> numericString(v); else -> Double.NaN }
private val decimalNumber = Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
private fun numericString(value: String): Double {
    val s = value.trim(::jsWhitespace)
    if (s.isEmpty()) return 0.0
    if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
    if (s == "-Infinity") return Double.NEGATIVE_INFINITY
    val radix = when { s.startsWith("0x", true) -> 16; s.startsWith("0b", true) -> 2; s.startsWith("0o", true) -> 8; else -> 0 }
    if (radix != 0) {
        if (s.length == 2) return Double.NaN
        var n = 0.0
        for (i in 2 until s.length) {
            val digit = when (val c = s[i]) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> return Double.NaN }
            if (digit >= radix) return Double.NaN
            n = n * radix + digit
        }
        return n
    }
    if (!decimalNumber.matches(s)) return Double.NaN
    return s.toDoubleOrNull() ?: Double.NaN
}
internal fun int(value: Any?): Int { val n = number(value); if (n >= -2147483648.0 && n < 2147483648.0) return n.toInt(); if (!n.isFinite() || n == 0.0) return 0; val t = if (n < 0) ceil(n) else floor(n); return (t % 4294967296.0).toLong().toInt() }
internal fun uint(value: Any?): Long = int(value).toUInt().toLong()
internal fun integer(value: Any?): Int { val n = number(value); return when { n.isNaN() -> 0; n >= Int.MAX_VALUE -> Int.MAX_VALUE; n <= Int.MIN_VALUE -> Int.MIN_VALUE; else -> n.toInt() } }
internal fun relative(value: Any?, length: Int): Int { val n = integer(value); return if (n < 0) (length.toLong() + n).coerceAtLeast(0).toInt() else n.coerceAtMost(length) }
internal fun index(key: String): Int? { if (key.isEmpty() || key.length > 10 || key.length > 1 && key[0] == '0' || key.any { it !in '0'..'9' }) return null; return key.toIntOrNull()?.takeIf { it >= 0 } }
internal fun text(value: Any?): String = when (value) {
    is String -> value; null -> "null"; Undefined -> "undefined"; is Boolean -> value.toString(); is Int -> value.toString()
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
        if (a == null || a === Undefined || b == null || b === Undefined) return (a == null || a === Undefined) && (b == null || b === Undefined)
        if ((a is JsArray || a is JsObject || a is JsFunction || a is Native || a is JsDate) && (b is String || b is Double || b is Boolean) ||
            (b is JsArray || b is JsObject || b is JsFunction || b is Native || b is JsDate) && (a is String || a is Double || a is Boolean)) unsupported("Implicit object equality coercion is unsupported")
        if (a is Boolean || b is Boolean || a is Double && b is String || a is String && b is Double) return number(a) == number(b)
    }
    return false
}

private val isoDate = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?(Z|[+-]\\d{2}:\\d{2})$")
private fun parseDate(value: String): Double {
    if (value.length !in 20..29) unsupported("Unsupported date literal")
    val match = isoDate.matchEntire(value) ?: unsupported("Unsupported date literal")
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
