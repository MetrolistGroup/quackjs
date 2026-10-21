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
    private val templates: Map<String, Any?>,
    private val dates: Map<String, Double>,
    private val dispatch: Map<DispatchKey, Block>,
    private val globalCount: Int,
    public val signatureTimestamp: Int?,
) {
    public fun decipherSignature(input: String): String = run(input) { runtime ->
        runtime.expression(signature, mapOf("INPUT" to input))
    }

    public fun transformN(input: String): String = run(input) { runtime -> nPlan.execute(runtime, input) }

    private inline fun run(input: String, transform: (Runtime) -> Any?): String {
        if (input.length > 16_384) unsupported("Challenge length limit exceeded")
        try {
            val output = transform(Runtime(source, parsed, templates, dates, dispatch, globalCount = globalCount).also { it.challenge = input })
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
            val runtime = Runtime(source, recordDispatch = true)
            try {
                val plan = discoverN(source, derived.nClass, runtime)
                val result = runtime.expression(signature, mapOf("INPUT" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"))
                if (result !is String) unsupported("Signature transform did not return a string")
                val parsed = runtime.definitions.toMap()
                val initializer = Runtime(source)
                val templates = parsed.filterValues(::templateSafe).mapValues { initializer.expression(it.value) }
                val dispatch = runtime.specializedBodies!!.toMap()
                val globals = parsed.keys.withIndex().associate { it.value to it.index }
                val pending = ArrayDeque<Any>()
                pending.addAll(parsed.values)
                pending.addAll(listOf(signature, plan.function, plan.constructor, plan.setter))
                pending.addAll(dispatch.values)
                while (pending.isNotEmpty()) {
                    val node = pending.removeLast()
                    if (node is Id && node.free) node.globalSlot = globals[node.name] ?: -1
                    syntaxChildren(node) { pending.add(it) }
                }
                return PlayerProgram(source, signature, plan, parsed, templates, runtime.constantDates.toMap(), dispatch, globals.size, hints?.signatureTimestamp ?: source.signatureTimestamp)
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

// Cache only closed, side-effect-free initializers. Aliases must still resolve against live globals.
private fun templateSafe(expr: Expr): Boolean = when (expr) {
    is Lit, is FunctionExpr -> true
    is ArrayExpr -> expr.items.all(::templateSafe)
    is ObjectExpr -> expr.items.all { templateSafe(it.second) }
    is Call -> !expr.construct && expr.fn is Member && expr.fn.target is Lit && expr.fn.target.value is String && expr.fn.key == Lit("split") && expr.args.all { it is Lit }
    else -> false
}

private val RootArgument = Id("<n-root>")
private fun nArguments(runtime: Runtime, arguments: List<Expr>, root: JsObject): List<Any?> =
    arguments.map { runtime.expression(it, mapOf(RootArgument.name to root)) }

private data class NPlan(val function: FunctionExpr, val arguments: List<Expr>, val selfRoot: Boolean, val mapKey: String, val constructor: FunctionExpr, val setter: FunctionExpr) {
    fun execute(runtime: Runtime, input: String): Any? {
        runtime.challenge = input
        val root = makeRoot(runtime, constructor, mapKey, input, setter)
        runtime.invoke(runtime.function(function), if (selfRoot) root else Undefined, nArguments(runtime, arguments, root))
        return (root.fields[mapKey] as? JsObject)?.fields?.get("n") ?: unsupported("n transform removed its result")
    }
}

private fun makeRoot(runtime: Runtime, constructor: FunctionExpr, mapKey: String?, input: String, setter: FunctionExpr): JsObject {
    val root = JsObject()
    runtime.invoke(runtime.function(constructor), root, listOf("", true))
    root.fields["set"] = runtime.function(setter)
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
    data class Candidate(val function: FunctionExpr, val args: List<Expr>, val selfRoot: Boolean, val level: Int, val label: String)
    data class Key(val label: String, val args: List<Expr>, val selfRoot: Boolean)
    val pending = mutableListOf(
        Candidate(constructor, listOf(Lit(""), Lit(true)), true, 0, "constructor"),
        Candidate(getter, listOf(Lit("n")), true, 0, "getter"),
    )
    val seen = mutableSetOf<Key>()
    val probe = "KdrqFlzJXl9EcCwlmEy"
    var cursor = 0
    var selected: Pair<Key, NPlan>? = null
    var lastError = "No n-table update found"
    while (cursor < pending.size) {
        if (pending.size > 64) unsupported("n discovery dependency limit exceeded")
        val candidate = pending[cursor++]
        if (candidate.level > 8) continue
        val key = Key(candidate.label, candidate.args, candidate.selfRoot)
        if (!seen.add(key)) {
            if (selected?.first == key) unsupported("Ambiguous repeated n-table updates")
            continue
        }
        val root = makeRoot(runtime, constructor, mapKey, probe, setter)
        runtime.challenge = probe
        val args = nArguments(runtime, candidate.args, root)
        var conflicting = false
        if (candidate.label != "constructor") {
            try {
                runtime.invoke(runtime.function(candidate.function), if (candidate.selfRoot) root else Undefined, args)
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
        val env = runtime.functionEnv(runtime.function(candidate.function), if (candidate.selfRoot) cleanRoot else Undefined, nArguments(runtime, candidate.args, cleanRoot))
        val bindings = mutableMapOf<String, Expr>("this" to if (candidate.selfRoot) RootArgument else Lit(Undefined))
        candidate.function.params.forEachIndexed { i, name -> bindings[name] = candidate.args.getOrElse(i) { Lit(Undefined) } }
        // Preserve input-derived expressions while folding the dispatcher prefix.
        val prefix = mutableSetOf<Vars>()
        for (stmt in candidate.function.body.statements) {
            if (stmt !is Vars) break
            if (stmt.items.any { it.second != null && !constant(it.second!!) }) break
            for ((name, expr) in stmt.items) if (expr != null) {
                val bound = substitute(expr, bindings, env)
                if (bound == null) bindings.remove(name)
                else { validateDepth(bound); bindings[name] = bound }
            }
            runtime.exec(stmt, env)
            prefix.add(stmt)
        }
        val dynamic = bindings.filterValues(::dependsOnRoot).keys.toMutableSet()
        var rootWrite = false
        // Follow reachable dispatcher branches, but never reuse a binding after a possible write.
        walk(candidate.function.body, runtime, env, dynamic, write = { target ->
            if (target is Id) { bindings.remove(target.name); dynamic.add(target.name) }
            else if (target is Member && substitute(target, bindings, env) == Member(Member(RootArgument, Lit(mapKey)), Lit("n"))) rootWrite = true
        }, prefix = prefix) { _, _ -> }
        if (rootWrite) continue
        walk(candidate.function.body, runtime, env, dynamic) { call, guards ->
            var target = call.fn
            var selfRoot = false
            var argExprs = call.args
            if (target is Member && target.key == Lit("call") && target.target is Id && argExprs.isNotEmpty()) {
                if (!constant(argExprs.first())) return@walk
                val self = substitute(argExprs.first(), bindings, env) ?: return@walk
                if (self != RootArgument && self != Lit(Undefined)) return@walk
                selfRoot = self == RootArgument
                argExprs = argExprs.drop(1); target = target.target
            }
            if (target !is Id || call.construct || !argExprs.all(::constant)) return@walk
            val boundArgs = argExprs.map { substitute(it, bindings, env) ?: return@walk }
            boundArgs.forEach(::validateDepth)
            val values = try { nArguments(runtime, boundArgs, cleanRoot) } catch (_: JsThrown) { return@walk } catch (_: UnsupportedPlayerException) { return@walk }
            if (!selfRoot && values.none { it === cleanRoot }) return@walk
            if (values.any { it !== cleanRoot && it !is Double && it !is String && it !is Boolean && it !== Undefined && it != null }) return@walk
            val fn = try { runtime.resolve(target.name) } catch (_: UnsupportedPlayerException) { return@walk }
            if (fn !is JsFunction) return@walk
            for (guard in guards) {
                val bound = substitute(guard, bindings, env)
                if (bound != null) validateDepth(bound)
                if (bound == null || inputDependentGuard(bound, runtime, mapKey)) {
                    lastError = "Cannot discard input-dependent n control flow"
                    return@walk
                }
            }
            if (pending.size >= 64) unsupported("n discovery dependency limit exceeded")
            pending.add(Candidate(fn.code, boundArgs, selfRoot, candidate.level + 1, target.name))
        }
    }
    return selected?.second ?: unsupported("Cannot isolate n transform: $lastError")
}

private fun constant(expr: Expr): Boolean = when (expr) {
    is Lit, is Id -> true
    is Unary -> expr.op in setOf("!", "~", "+", "-", "typeof", "void") && constant(expr.expr)
    is Binary -> expr.op !in assignmentOperators && constant(expr.left) && constant(expr.right)
    is Member -> constant(expr.target) && constant(expr.key)
    is Conditional -> constant(expr.test) && constant(expr.yes) && constant(expr.no)
    else -> false
}

private fun substitute(expr: Expr, bindings: Map<String, Expr>, env: Env): Expr? {
    fun child(e: Expr): Expr? = substitute(e, bindings, env)
    return when (expr) {
        is Lit -> expr
        is Id -> bindings[expr.name] ?: expr.takeIf { env.owner(expr.name)?.frame == null }
        is Unary -> Unary(expr.op, child(expr.expr) ?: return null)
        is Binary -> Binary(expr.op, child(expr.left) ?: return null, child(expr.right) ?: return null)
        is Member -> Member(child(expr.target) ?: return null, child(expr.key) ?: return null)
        is Conditional -> Conditional(child(expr.test) ?: return null, child(expr.yes) ?: return null, child(expr.no) ?: return null)
        is Call -> Call(child(expr.fn) ?: return null, expr.args.map { child(it) ?: return null }, expr.construct)
        else -> null
    }
}

private fun dependsOnRoot(expr: Expr): Boolean = when (expr) {
    is Id -> expr == RootArgument
    is Unary -> dependsOnRoot(expr.expr)
    is Binary -> dependsOnRoot(expr.left) || dependsOnRoot(expr.right)
    is Member -> dependsOnRoot(expr.target) || dependsOnRoot(expr.key)
    is Conditional -> dependsOnRoot(expr.test) || dependsOnRoot(expr.yes) || dependsOnRoot(expr.no)
    else -> false
}

private fun inputDependentGuard(expr: Expr, runtime: Runtime, mapKey: String): Boolean {
    fun rootPath(e: Expr): List<String>? {
        if (e == RootArgument) return emptyList()
        if (e !is Member) return null
        val parent = rootPath(e.target) ?: return null
        if (!constant(e.key) || dependsOnRoot(e.key)) return null
        val key = try { text(runtime.expression(e.key)) } catch (_: UnsupportedPlayerException) { return null } catch (_: JsThrown) { return null }
        return parent + key
    }
    fun sensitive(e: Expr): Boolean {
        rootPath(e)?.let { return it.isEmpty() || it.first() == mapKey && (it.size == 1 || it[1] == "n") }
        return when (e) {
            is Lit, is Id -> false
            is Unary -> sensitive(e.expr)
            is Binary -> sensitive(e.left) || sensitive(e.right)
            is Member -> sensitive(e.target) || sensitive(e.key)
            is Conditional -> sensitive(e.test) || sensitive(e.yes) || sensitive(e.no)
            is Call -> e.fn.let { it is Member && rootPath(it.target)?.isEmpty() == true } || sensitive(e.fn) || e.args.any(::sensitive)
            else -> true
        }
    }
    return sensitive(expr)
}

private fun knownTruth(expr: Expr, runtime: Runtime, env: Env, dynamic: Set<String>): Boolean? {
    if (expr is Binary && expr.op == "&&") {
        val left = knownTruth(expr.left, runtime, env, dynamic)
        return if (left == false) false else if (left == true) knownTruth(expr.right, runtime, env, dynamic) else null
    }
    if (expr is Binary && expr.op == "||") {
        val left = knownTruth(expr.left, runtime, env, dynamic)
        return if (left == true) true else if (left == false) knownTruth(expr.right, runtime, env, dynamic) else null
    }
    fun independent(e: Expr): Boolean = when (e) {
        is Lit -> true
        is Id -> env.owner(e.name)?.get(e.name).let { value ->
            if (env.owner(e.name)?.frame != null) e.name !in dynamic && value !== Undefined && value !is JsObject && value !is JsArray else runtime.source.hasDefinition(e.name)
        }
        is Member -> independent(e.target) && independent(e.key)
        is Unary -> constant(e) && independent(e.expr)
        is Binary -> constant(e) && independent(e.left) && independent(e.right)
        else -> false
    }
    if (!independent(expr)) return null
    return try { truth(runtime.eval(expr, env)) } catch (_: UnsupportedPlayerException) { null } catch (_: JsThrown) { null }
}

private data class DiscoveryFlow(val guards: List<Expr> = emptyList(), val exits: Boolean = false) {
    init { if (guards.size > 128) unsupported("n control-flow limit exceeded") }
}

private fun walk(stmt: Stmt, runtime: Runtime, env: Env, dynamic: Set<String>, guards: List<Expr> = emptyList(), write: (Expr) -> Unit = {}, prefix: Set<Vars> = emptySet(), visit: (Call, List<Expr>) -> Unit): DiscoveryFlow {
    if (guards.size > 128) unsupported("n control-flow limit exceeded")
    fun expr(e: Expr, path: List<Expr> = guards) { walkExpression(e, runtime, env, dynamic, path, write, visit) }
    fun child(s: Stmt, path: List<Expr> = guards): DiscoveryFlow = walk(s, runtime, env, dynamic, path, write, prefix, visit)
    when (stmt) {
        is Block -> {
            val tail = mutableListOf<Expr>()
            for (s in stmt.statements) {
                val flow = child(s, guards + tail)
                tail.addAll(flow.guards)
                if (tail.size > 128) unsupported("n control-flow limit exceeded")
                if (flow.exits) return DiscoveryFlow(tail, true)
            }
            return DiscoveryFlow(tail)
        }
        is ExpressionStmt -> expr(stmt.expr)
        is Vars -> stmt.items.forEach { (name, value) -> if (value != null) { expr(value); if (stmt !in prefix) write(Id(name)) } }
        is IfStmt -> {
            expr(stmt.test)
            val known = knownTruth(stmt.test, runtime, env, dynamic)
            if (known == true) return child(stmt.yes)
            if (known == false) return stmt.no?.let { child(it) } ?: DiscoveryFlow()
            val path = guards + stmt.test
            val yes = child(stmt.yes, path)
            val no = stmt.no?.let { child(it, path) } ?: DiscoveryFlow()
            val tail = if (yes.exits || no.exits || yes.guards.isNotEmpty() || no.guards.isNotEmpty()) listOf(stmt.test) + yes.guards + no.guards else emptyList()
            return DiscoveryFlow(tail, yes.exits && no.exits)
        }
        is ReturnStmt -> { stmt.expr?.let { expr(it) }; return DiscoveryFlow(exits = true) }
        is ThrowStmt -> { expr(stmt.expr); return DiscoveryFlow(exits = true) }
        is Jump -> return DiscoveryFlow(exits = true)
        is Loop -> {
            stmt.init?.let { child(it) }; stmt.test?.let { expr(it) }
            val known = stmt.test?.let { knownTruth(it, runtime, env, dynamic) }
            val path = if (stmt.test != null && known == null) guards + stmt.test else guards
            stmt.update?.let { expr(it, path) }
            if (stmt.test == null || known != false) {
                val body = child(stmt.body, path)
                if (body.exits || body.guards.isNotEmpty()) return DiscoveryFlow(listOfNotNull(stmt.test) + body.guards)
            }
        }
        is Label -> return child(stmt.body)
        is TryStmt -> {
            val body = child(stmt.body)
            val handler = stmt.handler?.let { child(it, guards + RootArgument) }
            val finalizer = stmt.finalizer?.let { child(it) }
            return DiscoveryFlow(body.guards + handler?.guards.orEmpty() + finalizer?.guards.orEmpty(), finalizer?.exits == true || body.exits && (handler == null || handler.exits))
        }
        is SwitchStmt -> {
            expr(stmt.expr)
            val flows = stmt.cases.map { (key, body) -> key?.let { expr(it) }; child(Block(body), guards + stmt.expr) }
            val tail = if (flows.any { it.exits || it.guards.isNotEmpty() }) listOf(stmt.expr) + flows.flatMap { it.guards } else emptyList()
            return DiscoveryFlow(tail, stmt.cases.any { it.first == null } && flows.all { it.exits })
        }
        else -> Unit
    }
    return DiscoveryFlow()
}
private fun walkExpression(e: Expr, runtime: Runtime, env: Env, dynamic: Set<String>, guards: List<Expr>, write: (Expr) -> Unit, visit: (Call, List<Expr>) -> Unit) {
    if (guards.size > 128) unsupported("n control-flow limit exceeded")
    fun child(expr: Expr, path: List<Expr> = guards) { walkExpression(expr, runtime, env, dynamic, path, write, visit) }
    when (e) {
        is Call -> { visit(e, guards); child(e.fn); e.args.forEach { child(it) } }
        is Unary -> { child(e.expr); if (e.op == "delete") write(e.expr) }
        is Binary -> {
            child(e.left)
            val logical = e.op == "&&" || e.op == "||"
            val known = if (logical) knownTruth(e.left, runtime, env, dynamic) else null
            if (!(e.op == "&&" && known == false || e.op == "||" && known == true)) child(e.right, if (logical && known == null) guards + e.left else guards)
            if (e.op in assignmentOperators) write(e.left)
        }
        is Conditional -> {
            child(e.test)
            val known = knownTruth(e.test, runtime, env, dynamic)
            val path = if (known == null) guards + e.test else guards
            if (known != false) child(e.yes, path)
            if (known != true) child(e.no, path)
        }
        is Member -> { child(e.target); child(e.key) }
        is Update -> { child(e.target); write(e.target) }
        is ArrayExpr -> e.items.forEach { child(it) }
        is ObjectExpr -> e.items.forEach { child(it.second) }
        is FunctionExpr -> {
            // A closure can change a substituted outer local before the isolated call.
            val pending = ArrayDeque<Any>()
            pending.add(e.body)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                val target = when (node) {
                    is Binary -> if (node.op in assignmentOperators) node.left else null
                    is Update -> node.target
                    is Unary -> if (node.op == "delete") node.expr else null
                    else -> null
                }
                var base = target
                while (base is Member) base = base.target
                if (base is Id && base.slot < 0 && target != null) write(target)
                syntaxChildren(node) { pending.add(it) }
            }
        }
        else -> Unit
    }
}
