package meow.nyx.quackjs

/** Optional, externally supplied landmarks from a known player configuration. */
public data class PlayerHints(val signatureExpression: String, val nClass: String, val signatureTimestamp: Int)

public class UnsupportedPlayerException(message: String) : IllegalArgumentException(message)

/** An immutable player slice. Every invocation owns its globals, arrays and execution budget. */
public class PlayerProgram private constructor(
    private val source: SourceIndex,
    private val signature: Expr,
    private val nPlan: NPlan,
    private val parsed: Map<String, Expr>,
    public val signatureTimestamp: Int?,
) {
    public fun decipherSignature(input: String): String = run(input) { runtime ->
        runtime.expression(signature, mapOf("INPUT" to input))
    }

    public fun transformN(input: String): String = run(input) { runtime -> nPlan.execute(runtime, input) }

    private inline fun run(input: String, transform: (Runtime) -> Any?): String {
        if (input.length > 16_384) unsupported("Challenge length limit exceeded")
        try {
            val output = transform(Runtime(source, parsed).also { it.challenge = input })
            if (output !is String) unsupported("Transform did not return a string")
            if (output.length > 65_536) unsupported("Transform output limit exceeded")
            return output
        } catch (error: JsThrown) {
            unsupported("Transform threw: ${if (error.value is String) error.value.take(160) else "JavaScript exception"}")
        }
    }

    public companion object {
        /** Compile a dispatcher/table-based player without running its browser initialization.
         * Throws [UnsupportedPlayerException] for unsupported dependencies or resource limits.
         * Source is capped at 8 Mi UTF-16 units; individual challenges at 16,384 units.
         */
        public fun compile(playerJs: String, hints: PlayerHints? = null): PlayerProgram {
            if (playerJs.length > 8 * 1024 * 1024) unsupported("Player source limit exceeded")
            val derived = hints ?: derive(playerJs)
            val source = SourceIndex(playerJs, derived.nClass)
            if (derived.signatureExpression.length > 512) unsupported("Signature expression limit exceeded")
            val signature = Parser(Lexer(derived.signatureExpression).tokens()).completeExpression()
            validateSignatureExpression(signature)
            val runtime = Runtime(source)
            try {
                val plan = discoverN(source, derived.nClass, runtime)
                val result = runtime.expression(signature, mapOf("INPUT" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"))
                if (result !is String) unsupported("Signature transform did not return a string")
                return PlayerProgram(source, signature, plan, runtime.definitions.toMap(), hints?.signatureTimestamp ?: source.signatureTimestamp)
            } catch (error: JsThrown) {
                unsupported("Player initialization failed: ${if (error.value is String) error.value.take(160) else "JavaScript exception"}")
            }
        }

        private fun derive(source: String): PlayerHints {
            data class Landmark(val signature: String, val nClass: String, val locations: List<Pair<Int, String>>)
            val candidates = mutableListOf<Landmark>()
            var from = 0
            while (true) {
                val alr = source.indexOf(".set(\"alr\",\"yes\")", from)
                if (alr < 0) break
                from = alr + 1
                val beforeStart = (alr - 160).coerceAtLeast(0)
                val before = source.substring(beforeStart, alr)
                val after = source.substring(alr, (alr + 260).coerceAtMost(source.length))
                val n = Regex("new\\s+g\\.([A-Za-z0-9_$]+)\\s*\\([^,()]+,\\s*!0\\s*\\)").findAll(before).lastOrNull() ?: continue
                val sig = Regex("([A-Za-z0-9_$]+)\\((\\d+),(\\d+),\\s*[A-Za-z0-9_$]+\\(\\d+,\\d+,").find(after) ?: continue
                if (candidates.size >= 1024) unsupported("Player landmark limit exceeded")
                candidates.add(Landmark("${sig.groupValues[1]}(${sig.groupValues[2]},${sig.groupValues[3]},INPUT)", n.groupValues[1],
                    listOf(alr to ".", beforeStart + n.range.first to "new", alr + sig.range.first to sig.groupValues[1])))
            }
            // A landmark inside a comment, string or template is never executable code.
            val wanted = candidates.flatMap { it.locations }.toSet()
            val actual = mutableSetOf<Pair<Int, String>>()
            val lexer = Lexer(source)
            var previous: String? = null
            var count = 0
            val end = wanted.maxOfOrNull { it.first } ?: -1
            while (end >= 0) {
                val token = lexer.next(previous)
                if (token.kind == -1 || token.offset > end) break
                if (++count > 2_000_000) unsupported("Source token limit exceeded")
                val location = token.offset to token.text
                if (token.kind == 0 && location in wanted) actual.add(location)
                previous = token.context
            }
            val valid = candidates.filter { actual.containsAll(it.locations) }.map { it.signature to it.nClass }.toSet()
            if (valid.size != 1) unsupported("Missing or ambiguous signature/n landmarks; supply PlayerHints")
            val (signature, nClass) = valid.single()
            return PlayerHints(signature, nClass, 0)
        }

        private fun validateSignatureExpression(expr: Expr) {
            fun argument(e: Expr): Boolean = e == Id("INPUT") || e is Lit && e.value is Double || e is Unary && e.op in setOf("+", "-") && argument(e.expr)
            if (expr !is Call || expr.construct || expr.fn !is Id || expr.args.count { it == Id("INPUT") } != 1 || !expr.args.all(::argument)) {
                unsupported("Signature hint must be a function call with numeric arguments and INPUT")
            }
        }
    }
}

private data object RootArgument
private data class NPlan(val function: FunctionExpr, val arguments: List<Any?>, val selfRoot: Boolean, val mapKey: String, val constructor: FunctionExpr, val setter: FunctionExpr) {
    fun execute(runtime: Runtime, input: String): Any? {
        runtime.challenge = input
        val root = makeRoot(runtime, constructor, mapKey, input, setter)
        runtime.invoke(JsFunction(function, Env()), if (selfRoot) root else Undefined, arguments.map { if (it === RootArgument) root else it })
        return (root.fields[mapKey] as? JsObject)?.fields?.get("n") ?: unsupported("n transform removed its result")
    }
}

private fun makeRoot(runtime: Runtime, constructor: FunctionExpr, mapKey: String?, input: String, setter: FunctionExpr): JsObject {
    val root = JsObject()
    runtime.invoke(JsFunction(constructor, Env()), root, listOf("", true))
    root.fields["set"] = JsFunction(setter, Env())
    if (mapKey != null) {
        val map = root.fields[mapKey] as? JsObject ?: unsupported("Unsupported n parameter table")
        map.fields["n"] = input
    }
    return root
}

/** Follow only calls receiving the URL instance, then execute the n-table update in isolation. */
private fun discoverN(source: SourceIndex, name: String, runtime: Runtime): NPlan {
    val constructor = source.classFunction(name)
    val getter = source.method(name, "get", runtime)
    val setter = source.method(name, "set", runtime)
    val initial = makeRoot(runtime, constructor, null, "", setter)
    val tables = initial.fields.filterValues { it is JsObject }.keys
    if (tables.size != 1) unsupported("Cannot identify n parameter table")
    val mapKey = tables.single()
    data class Candidate(val function: FunctionExpr, val args: List<Any?>, val selfRoot: Boolean, val level: Int, val label: String)
    val pending = mutableListOf(
        Candidate(constructor, listOf("", true), true, 0, "constructor"),
        Candidate(getter, listOf("n"), true, 0, "getter"),
    )
    val seen = mutableSetOf<String>()
    val probe = "KdrqFlzJXl9EcCwlmEy"
    var cursor = 0
    var selected: Pair<String, NPlan>? = null
    var lastError = "No n-table update found"
    while (cursor < pending.size) {
        if (pending.size > 64) unsupported("n discovery dependency limit exceeded")
        val candidate = pending[cursor++]
        if (candidate.level > 8) continue
        val key = candidate.label + ":" + candidate.args.joinToString { if (it === RootArgument) "ROOT" else text(it) }
        if (!seen.add(key)) {
            if (selected?.first == key) unsupported("Ambiguous repeated n-table updates")
            continue
        }
        val root = makeRoot(runtime, constructor, mapKey, probe, setter)
        runtime.challenge = probe
        val args = candidate.args.map { if (it === RootArgument) root else it }
        var conflicting = false
        if (candidate.label != "constructor") {
            try {
                runtime.invoke(JsFunction(candidate.function, Env()), if (candidate.selfRoot) root else Undefined, args)
                val result = (root.fields[mapKey] as? JsObject)?.fields?.get("n")
                if (result is String && result != probe) {
                    val plan = NPlan(candidate.function, candidate.args, candidate.selfRoot, mapKey, constructor, setter)
                    val second = plan.execute(runtime, "0123456789abcdefghijklmno")
                    if (second is String && second != "0123456789abcdefghijklmno" && second.length < 256) {
                        if (selected != null) conflicting = true else selected = key to plan
                    }
                }
            } catch (error: UnsupportedPlayerException) { lastError = error.message ?: lastError }
            catch (_: JsThrown) { /* URL-only dependencies are not part of the transform. */ }
        }
        if (conflicting) unsupported("Ambiguous n-table updates")
        if (selected?.first == key) continue
        val cleanRoot = makeRoot(runtime, constructor, mapKey, probe, setter)
        val env = runtime.functionEnv(JsFunction(candidate.function, Env()), if (candidate.selfRoot) cleanRoot else Undefined, candidate.args.map { if (it === RootArgument) cleanRoot else it })
        // Dispatcher keys are initialized before the first branch.
        for (stmt in candidate.function.body.statements) {
            if (stmt !is Vars) break
            if (stmt.items.any { it.second != null && !constant(it.second!!) }) break
            runtime.exec(stmt, env)
        }
        walk(candidate.function.body, runtime, env) { call ->
            var target = call.fn
            var selfRoot = false
            var argExprs = call.args
            if (target is Member && target.key == Lit("call") && target.target is Id && argExprs.isNotEmpty()) {
                if (!constant(argExprs.first())) return@walk
                selfRoot = try { runtime.eval(argExprs.first(), env) === cleanRoot } catch (_: JsThrown) { false }
                argExprs = argExprs.drop(1); target = target.target
            }
            if (target !is Id || call.construct || !argExprs.all(::constant)) return@walk
            val values = try { argExprs.map { runtime.eval(it, env) } } catch (_: JsThrown) { return@walk } catch (_: UnsupportedPlayerException) { return@walk }
            if (!selfRoot && values.none { it === cleanRoot }) return@walk
            if (values.any { it !== cleanRoot && it !is Double && it !is String && it !is Boolean && it !== Undefined && it != null }) return@walk
            val fn = try { runtime.resolve(target.name) } catch (_: UnsupportedPlayerException) { return@walk }
            if (fn !is JsFunction) return@walk
            pending.add(Candidate(fn.code, values.map { if (it === cleanRoot) RootArgument else it }, selfRoot, candidate.level + 1, target.name))
        }
    }
    return selected?.second ?: unsupported("Cannot isolate n transform: $lastError")
}

private fun constant(expr: Expr): Boolean = when (expr) {
    is Lit, is Id -> true
    is Unary -> expr.op in setOf("!", "~", "+", "-", "typeof", "void") && constant(expr.expr)
    is Binary -> expr.op !in setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", ">>=", "<<=", ">>>=") && constant(expr.left) && constant(expr.right)
    is Member -> constant(expr.target) && constant(expr.key)
    is Conditional -> constant(expr.test) && constant(expr.yes) && constant(expr.no)
    else -> false
}

private fun knownTruth(expr: Expr, runtime: Runtime, env: Env): Boolean? {
    if (expr is Binary && expr.op == "&&") {
        val left = knownTruth(expr.left, runtime, env)
        return if (left == false) false else if (left == true) knownTruth(expr.right, runtime, env) else null
    }
    if (expr is Binary && expr.op == "||") {
        val left = knownTruth(expr.left, runtime, env)
        return if (left == true) true else if (left == false) knownTruth(expr.right, runtime, env) else null
    }
    fun independent(e: Expr): Boolean = when (e) {
        is Lit -> true
        is Id -> env.owner(e.name)?.values?.get(e.name).let { value ->
            if (env.owner(e.name) != null) value !== Undefined && value !is JsObject && value !is JsArray else runtime.source.hasDefinition(e.name)
        }
        is Member -> independent(e.target) && independent(e.key)
        is Unary -> constant(e) && independent(e.expr)
        is Binary -> constant(e) && independent(e.left) && independent(e.right)
        else -> false
    }
    if (!independent(expr)) return null
    return try { truth(runtime.eval(expr, env)) } catch (_: UnsupportedPlayerException) { null } catch (_: JsThrown) { null }
}

private fun walk(stmt: Stmt, runtime: Runtime, env: Env, visit: (Call) -> Unit) {
    fun expr(e: Expr) { walkExpression(e, runtime, env, visit) }
    fun child(s: Stmt) { walk(s, runtime, env, visit) }
    when (stmt) {
        is Block -> stmt.statements.forEach(::child)
        is ExpressionStmt -> expr(stmt.expr)
        is Vars -> stmt.items.forEach { it.second?.let(::expr) }
        is IfStmt -> {
            expr(stmt.test)
            val known = knownTruth(stmt.test, runtime, env)
            if (known != false) child(stmt.yes)
            if (known != true) stmt.no?.let(::child)
        }
        is ReturnStmt -> stmt.expr?.let(::expr)
        is ThrowStmt -> expr(stmt.expr)
        is Loop -> { stmt.init?.let(::child); stmt.test?.let(::expr); stmt.update?.let(::expr); if (stmt.test == null || knownTruth(stmt.test, runtime, env) != false) child(stmt.body) }
        is Label -> child(stmt.body)
        is TryStmt -> { child(stmt.body); stmt.handler?.let(::child); stmt.finalizer?.let(::child) }
        is SwitchStmt -> { expr(stmt.expr); stmt.cases.forEach { (key, body) -> key?.let(::expr); body.forEach(::child) } }
        else -> Unit
    }
}
private fun walkExpression(e: Expr, runtime: Runtime, env: Env, visit: (Call) -> Unit) {
    fun child(expr: Expr) { walkExpression(expr, runtime, env, visit) }
    when (e) {
        is Call -> { visit(e); child(e.fn); e.args.forEach(::child) }
        is Unary -> child(e.expr)
        is Binary -> {
            child(e.left)
            val known = if (e.op == "&&" || e.op == "||") knownTruth(e.left, runtime, env) else null
            if (!(e.op == "&&" && known == false || e.op == "||" && known == true)) child(e.right)
        }
        is Conditional -> { child(e.test); val known = knownTruth(e.test, runtime, env); if (known != false) child(e.yes); if (known != true) child(e.no) }
        is Member -> { child(e.target); child(e.key) }
        is Update -> child(e.target)
        is ArrayExpr -> e.items.forEach(::child)
        is ObjectExpr -> e.items.forEach { child(it.second) }
        // Nested function bodies have their own parameters and are not discovery roots.
        else -> Unit
    }
}
