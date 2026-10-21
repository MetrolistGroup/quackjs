package meow.nyx.quackjs

/** Lex once to index the player scope; parse and execute only selected dependencies. */
internal class SourceIndex(val source: String, className: String) {
    private class ScanBudget {
        private var tokens = 0
        private var units = 0
        fun next(lexer: Lexer, previous: String? = null): Token {
            if (tokens >= 2_000_000 || units >= 16 * 1024 * 1024) unsupported("Source scan limit exceeded")
            val start = lexer.position
            val token = lexer.next(previous)
            tokens++
            units += lexer.position - start
            if (units > 16 * 1024 * 1024) unsupported("Source scan limit exceeded")
            return token
        }
    }
    private data class Assignment(val lhs: List<Token>, val offset: Int, val braces: Int, val parens: Int)
    private data class CallSite(val target: List<Token>?, val offset: Int, val braces: Int, val statement: Boolean)
    private data class References(val names: Set<String>, val lengths: Set<String>, val end: Int)
    private data class Reference(val name: String, val projected: Boolean)
    private data class CallEffects(val receiver: String?, val predicate: String?, val reversal: String?, val arguments: References)
    private data class FunctionScope(val depth: Int, val parameters: Int, val name: String?)
    private enum class DefinitionKind { PRIMITIVE, ARRAY, UNKNOWN }
    private val assignments: List<Assignment>
    private val rootBraces: Int
    private val rootParens: Int
    private val names: Map<String, List<Assignment>>
    private val mutatedObjects: Set<String>
    private val writeCounts: Map<String, Int>
    private val timestamps: Set<Int>
    val signatureTimestamp: Int? get() = if (timestamps.size <= 1) timestamps.singleOrNull() else unsupported("Conflicting signature timestamps")

    init {
        if (source.length > 8 * 1024 * 1024) unsupported("Player source limit exceeded")
        if (className.length > 128 || !identifier(className)) unsupported("Invalid n class hint")
        val lexer = Lexer(source)
        val scans = ScanBudget()
        val recent = ArrayDeque<Token>()
        val found = mutableListOf<Assignment>()
        val calls = mutableListOf<CallSite>()
        val updates = mutableListOf<Pair<String, Int>>()
        val stamps = mutableSetOf<Int>()
        var pendingStamp: Int? = null
        var braces = 0
        var parens = 0
        var count = 0
        var callCount = 0
        var previous: String? = null
        var prefixUpdate = false
        val headers = mutableMapOf<Int, Pair<Int, String?>>()
        var bodyParameters: Pair<Int, String?>? = null
        val activeScopes = ArrayDeque<FunctionScope>()
        var classDepth = Int.MAX_VALUE
        var classParameters = emptyList<Int>()
        var classBindings = emptyList<String>()
        var classFunctionScope = false
        fun recordUpdate(name: String) {
            if (updates.size >= 100_000) unsupported("Source mutation limit exceeded")
            updates.add(name to braces)
        }
        while (true) {
            val token = lexer.next(previous)
            if (token.kind == -1) break
            if (++count > 2_000_000) unsupported("Source token limit exceeded")
            bodyParameters?.let { parameters ->
                if (token.kind == 0 && token.text == "{") {
                    activeScopes.addLast(FunctionScope(braces + 1, parameters.first, parameters.second))
                }
            }
            bodyParameters = null
            if (pendingStamp != null && token.kind == 0 && token.text in setOf(",", "}")) stamps.add(pendingStamp)
            pendingStamp = if (token.kind == 2 && token.text.length in 4..6 && recent.lastOrNull()?.text == ":" && recent.elementAtOrNull(recent.size - 2)?.text == "signatureTimestamp") token.text.toIntOrNull() else null
            if (prefixUpdate) {
                if (token.kind == 0 && identifier(token.text)) {
                    recordUpdate(token.text)
                    prefixUpdate = false
                }
                else if (token.kind != 0 || token.text != "(") prefixUpdate = false
            }
            if (token.kind == 0 && token.text == "delete") prefixUpdate = true
            if (token.kind == 0 && (token.text in compoundWrites || token.text == "++" || token.text == "--")) {
                val start = assignmentTargetStart(recent, targetEnd(recent), call = true)
                if (start >= 0) recordUpdate(recent[start].text)
                prefixUpdate = token.text == "++" || token.text == "--"
            }
            if (token.kind == 0 && token.text == "=") {
                val end = targetEnd(recent)
                val start = assignmentTargetStart(recent, end)
                if (start >= 0) {
                    if (found.size >= 100_000) unsupported("Source definition limit exceeded")
                    val lhs = recent.subList(start, end + 1).toList()
                    found.add(Assignment(lhs, token.offset + 1, braces, parens))
                    if (classTarget(lhs, className) && braces < classDepth) {
                        classDepth = braces
                        calls.removeAll { it.braces > classDepth }
                        classParameters = activeScopes.map { it.parameters }
                        classBindings = activeScopes.mapNotNull { it.name }
                        classFunctionScope = activeScopes.lastOrNull()?.depth == braces
                    }
                }
            }
            if (token.kind == 0 && token.text == "(") {
                val start = assignmentTargetStart(recent, call = true)
                val namedFunction = recent.getOrNull(recent.lastIndex - 1)
                if (start >= 0 && start == recent.lastIndex && recent[start].text == "function") headers[parens] = token.offset + 1 to null
                else if (namedFunction?.kind == 0 && namedFunction.text == "function") headers[parens] = token.offset + 1 to recent.last().text
                if (start >= 0 && recent[start].text !in nonCalls || start < 0 && recent.lastOrNull()?.text in listOf(")", "?.")) {
                    if (callCount++ >= 100_000) unsupported("Source call limit exceeded")
                    // Only call effects at the shallowest class depth can be consumed below.
                    if (braces <= classDepth) {
                        val before = recent.getOrNull(start - 1)
                        val statement = start >= 0 && (before == null || before.kind == 0 && before.text in setOf(";", "{", "}", ")", "else"))
                        val target = if (start < 0) null else recent.subList(start, recent.size).toList()
                        calls.add(CallSite(target, token.offset + 1, braces, statement))
                    }
                }
            }
            if (token.kind == 0) when (token.text) {
                "{" -> braces++
                "}" -> {
                    if (activeScopes.lastOrNull()?.depth == braces) activeScopes.removeLast()
                    braces--
                }
                "(" -> parens++
                ")" -> { parens--; bodyParameters = headers.remove(parens) }
            }
            if (braces < 0 || parens < 0 || braces > 512 || parens > 512) unsupported("Source nesting limit exceeded")
            if (recent.size == 96) recent.removeFirst()
            recent.addLast(token)
            previous = token.context
        }
        if (braces != 0 || parens != 0) unsupported("Unbalanced player source")
        val classes = found.filter { classTarget(it.lhs, className) }
        val depth = classes.minOfOrNull { it.braces } ?: unsupported("Missing n class '$className'")
        val classAssignments = classes.filter { it.braces == depth }
        if (classAssignments.size != 1) unsupported("Ambiguous n class '$className'")
        rootBraces = classAssignments.single().braces
        rootParens = classAssignments.single().parens
        assignments = found.filter { it.braces == rootBraces && it.parens == rootParens }
        names = assignments.filter { it.lhs.size == 1 }.groupBy { it.lhs.single().text }
        val writes = found.filter { it.braces == rootBraces }
        writeCounts = writes.filter { it.lhs.size == 1 }.groupingBy { it.lhs.single().text }.eachCount()
        val kinds = mutableMapOf<String, DefinitionKind>()
        var nativeSplit = true
        fun canMutate(name: String): Boolean = definitionKind(name, kinds, scans, nativeSplit) != DefinitionKind.PRIMITIVE
        fun arrayLength(name: String): Boolean = definitionKind(name, kinds, scans, nativeSplit) == DefinitionKind.ARRAY
        val aliases = mutableMapOf<String, MutableSet<String>>()
        val contents = mutableMapOf<String, MutableSet<String>>()
        val lengthContents = mutableMapOf<String, MutableSet<String>>()
        val origins = mutableMapOf<String, MutableSet<String>>()
        val arrays = mutableSetOf<String>()
        for (write in writes) {
            val owner = write.lhs.first().text
            val direct = referenceBase(write.offset, scans)
            val first = if (direct == null) scans.next(Lexer(source, write.offset)) else null
            if (direct != null) {
                if (write.lhs.size > 1) contents.getOrPut(owner) { mutableSetOf() }.add(direct.name)
                else if (direct.projected) origins.getOrPut(owner) { mutableSetOf() }.add(direct.name)
                else {
                    aliases.getOrPut(owner) { mutableSetOf() }.add(direct.name)
                    aliases.getOrPut(direct.name) { mutableSetOf() }.add(owner)
                }
            } else if (first?.kind == 0 && first.text in listOf("[", "{")) {
                val refs = argumentReferences(first.offset + 1, scans, if (first.text == "[") "]" else "}", first.text)
                contents.getOrPut(owner) { mutableSetOf() }.addAll(refs.names)
                lengthContents.getOrPut(owner) { mutableSetOf() }.addAll(refs.lengths)
                val after = scans.next(Lexer(source, refs.end), first.text)
                if (write.lhs.size == 1 && first.text == "[" && after.kind == 0 && after.text in setOf(";", ",", ")", "}")) arrays.add(owner)
            }
        }
        val arrayQueue = ArrayDeque(arrays)
        while (arrayQueue.isNotEmpty()) for (alias in aliases[arrayQueue.removeFirst()].orEmpty()) if (arrays.add(alias)) arrayQueue.addLast(alias)
        val parameters = classParameters.flatMap { argumentReferences(it, scans).let { it.names + it.lengths } }.toSet()
        val unknownScope = rootBraces > 0 && !classFunctionScope
        val shadowed = names.keys + parameters + classBindings + if (unknownScope) readOnlyPredicates.keys else emptySet()
        val builtins = mutableMapOf<String, String>()
        for (root in readOnlyPredicates.keys) if (root !in shadowed) {
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (name !in builtins) { builtins[name] = root; queue.addAll(aliases[name].orEmpty()) }
            }
        }
        val effects = calls.filter { it.braces == rootBraces }.map { call ->
            val refs = argumentReferences(call.offset, scans)
            val target = call.target?.let(::targetExpression)
            var predicate = target
            if (predicate is Member && predicate.key in setOf(Lit("call"), Lit("apply"))) predicate = predicate.target
            val member = predicate as? Member
            val builtin = (member?.target as? Id)?.name?.let(builtins::get)
            val key = (member?.key as? Lit)?.value as? String
            val pure = builtin?.takeIf { key != null && key in readOnlyPredicates.getValue(it) }
            val reverse = if (target is Member && target.target is Id && target.key == Lit("reverse") && call.statement) {
                val after = scans.next(Lexer(source, refs.end), ")")
                target.target.name.takeIf { after.kind == 0 && after.text in setOf(";", "}") }
            } else null
            CallEffects(call.target?.takeIf { it.size > 1 }?.first()?.text, pure, reverse, refs)
        }
        val memberWrites = writes.filter { it.lhs.size > 1 }.map { it to targetExpression(it.lhs) }
        val updated = updates.filter { it.second == rootBraces }.map { it.first }
        val reassigned = writeCounts.filterValues { it > 1 }.keys
        val explicit = memberWrites.map { it.first.lhs.first().text }.toSet() + updated + reassigned
        fun ownSlot(expr: Expr?): Boolean = expr is Member && expr.target is Id && expr.key is Lit && expr.key.value.let {
            it == null || it is String || it is Double || it is Boolean
        } && expr.key.value != "length"
        val deepWrites = memberWrites.filter { !ownSlot(it.second) }.map { it.first.lhs.first().text }
        val changedLookups = (updated + reassigned + memberWrites.filter {
            !ownSlot(it.second) || ((it.second as? Member)?.key as? Lit)?.value in setOf("reverse", "__proto__")
        }.map { it.first.lhs.first().text }).toMutableSet()
        val changedQueue = ArrayDeque(changedLookups)
        while (changedQueue.isNotEmpty()) for (alias in aliases[changedQueue.removeFirst()].orEmpty()) if (changedLookups.add(alias)) changedQueue.addLast(alias)
        fun mutations(blocked: Set<String>): Set<String> {
            val stableSplit = "String" !in blocked && "String" !in shadowed
            if (nativeSplit != stableSplit) { nativeSplit = stableSplit; kinds.clear() }
            val own = mutableSetOf<String>()
            val escaped = mutableSetOf<String>()
            val queue = ArrayDeque<Pair<String, Boolean>>()
            fun add(name: String, escape: Boolean = false) { if (canMutate(name)) queue.addLast(name to escape) }
            explicit.forEach { add(it) }
            (deepWrites + updated).forEach { add(it, true) }
            for (effect in effects) {
                if (effect.predicate != null && effect.predicate !in blocked) continue
                // A discarded native reverse only changes the outer array's own slots.
                val reversal = effect.reversal
                if (reversal != null && reversal in arrays && reversal !in changedLookups && "Array" !in blocked && "Array" !in shadowed) {
                    add(reversal)
                } else {
                    effect.receiver?.let { add(it, true) }
                    effect.arguments.names.forEach { add(it, true) }
                    effect.arguments.lengths.filterNot(::arrayLength).forEach { add(it, true) }
                }
            }
            while (queue.isNotEmpty()) {
                val (name, escape) = queue.removeFirst()
                if (own.add(name)) {
                    aliases[name].orEmpty().forEach { add(it) }
                    origins[name].orEmpty().forEach { add(it, true) }
                }
                if (escape && escaped.add(name)) {
                    (aliases[name].orEmpty() + contents[name].orEmpty()).forEach { add(it, true) }
                    lengthContents[name].orEmpty().filterNot(::arrayLength).forEach { add(it, true) }
                }
            }
            return own
        }
        val builtinWrites = found.filter { it.lhs.size > 1 || it.braces != rootBraces || (writeCounts[it.lhs.first().text] ?: 0) > 1 }
        val blocked = (builtinWrites.mapNotNull { builtins[it.lhs.first().text] } + updates.mapNotNull { builtins[it.first] }).toMutableSet()
        var affected = mutations(blocked)
        while (true) {
            val changed = readOnlyPredicates.keys.filter { it in affected && it !in blocked }
            if (changed.isEmpty()) break
            blocked.addAll(changed)
            affected = mutations(blocked)
        }
        mutatedObjects = affected + updated
        timestamps = stamps
    }

    private fun targetExpression(tokens: List<Token>): Expr? =
        try { Parser(tokens + Token("<eof>", -1)).completeExpression() } catch (_: UnsupportedPlayerException) { null }

    /** Prove initializer value kinds without executing unrelated player code. */
    private fun definitionKind(name: String, known: MutableMap<String, DefinitionKind>, scans: ScanBudget, nativeSplit: Boolean, resolving: MutableSet<String> = mutableSetOf()): DefinitionKind {
        known[name]?.let { return it }
        if (resolving.size >= 64 || !resolving.add(name)) return DefinitionKind.UNKNOWN
        try {
            fun kind(expr: Expr): DefinitionKind = when (expr) {
                is Lit -> if (expr.value == null || expr.value is String || expr.value is Double || expr.value is Boolean) DefinitionKind.PRIMITIVE else DefinitionKind.UNKNOWN
                is Id -> definitionKind(expr.name, known, scans, nativeSplit, resolving)
                is ArrayExpr -> DefinitionKind.ARRAY
                is Unary -> if (expr.op in setOf("!", "~", "+", "-", "typeof", "void", "delete")) DefinitionKind.PRIMITIVE else DefinitionKind.UNKNOWN
                is Binary -> when (expr.op) {
                    "=", "," -> kind(expr.right)
                    "&&", "||", "??" -> kind(expr.left).takeIf { it == kind(expr.right) } ?: DefinitionKind.UNKNOWN
                    else -> DefinitionKind.PRIMITIVE
                }
                is Conditional -> kind(expr.yes).takeIf { it == kind(expr.no) } ?: DefinitionKind.UNKNOWN
                is Member -> if (expr.key == Lit("length") && kind(expr.target) == DefinitionKind.ARRAY) DefinitionKind.PRIMITIVE else DefinitionKind.UNKNOWN
                is Call -> if (nativeSplit && !expr.construct && expr.fn is Member && expr.fn.target is Lit && expr.fn.target.value is String && expr.fn.key == Lit("split") && expr.args.all { it is Lit }) DefinitionKind.ARRAY else DefinitionKind.UNKNOWN
                else -> DefinitionKind.UNKNOWN
            }
            val entries = names[name]
            val result = if (entries == null) {
                if (name in setOf("undefined", "NaN", "Infinity")) DefinitionKind.PRIMITIVE else DefinitionKind.UNKNOWN
            } else if (entries.size != 1 || writeCounts[name] != 1) DefinitionKind.UNKNOWN
            else {
                val offset = entries.single().offset
                val first = scans.next(Lexer(source, offset))
                if (first.kind == 0 && first.text in setOf("function", "{")) DefinitionKind.UNKNOWN
                else {
                    val tokens = slice(offset, scans)
                    val expr = try { Parser(tokens).completeExpression() } catch (_: UnsupportedPlayerException) { null }
                    expr?.let(::kind) ?: DefinitionKind.UNKNOWN
                }
            }
            // A budget cut in a longer alias chain must not poison a shorter proof.
            if (result != DefinitionKind.UNKNOWN || resolving.size == 1) known[name] = result
            return result
        } finally { resolving.remove(name) }
    }

    private fun argumentReferences(offset: Int, scans: ScanBudget, closing: String = ")", opening: String = "("): References {
        val lexer = Lexer(source, offset)
        val references = mutableSetOf<String>()
        val lengths = mutableSetOf<String>()
        var previous: String? = opening
        var depth = 0
        var count = 0
        var functionDepth: Int? = null
        var functionBody = false
        var parametersClosed = false
        var reference: Token? = null
        var beforeReference: String? = null
        while (true) {
            val token = scans.next(lexer, previous)
            if (++count > 150_000) unsupported("Initialization call limit exceeded")
            if (token.kind == -1) unsupported("Unclosed initialization call")
            reference?.let {
                val projection = Lexer(source, lexer.position)
                val length = if (token.kind != 0) false else when (token.text) {
                    "." -> scans.next(projection, ".").let { it.kind == 0 && it.text == "length" }
                    "[" -> scans.next(projection, "[").let { it.kind == 1 && it.text == "length" } && scans.next(projection, "<value>").let { it.kind == 0 && it.text == "]" }
                    else -> false
                }
                if (!(token.kind == 0 && token.text == ":" && beforeReference in listOf("{", ","))) {
                    if (length) lengths.add(it.text) else references.add(it.text)
                }
            }
            reference = null
            // Do not confuse property names or a callback's locals with escaped player globals.
            if (token.kind == 0 && token.text == "function" && functionDepth == null) functionDepth = depth
            if (token.kind == 0 && functionDepth == null && previous != "." && identifier(token.text)) { reference = token; beforeReference = previous }
            if (token.kind == 0) {
                if (functionDepth != null) {
                    if (!functionBody && token.text == ")" && depth == functionDepth + 1) parametersClosed = true
                    if (!functionBody && parametersClosed && token.text == "{" && depth == functionDepth) functionBody = true
                    else if (functionBody && token.text == "}" && depth == functionDepth + 1) { functionDepth = null; functionBody = false; parametersClosed = false }
                }
                if (depth == 0 && token.text == closing) return References(references, lengths, lexer.position)
                when (token.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> depth--
                }
            }
            previous = token.context
        }
    }

    /** Link direct reference paths without evaluating unrelated initializers. */
    private fun referenceBase(offset: Int, scans: ScanBudget): Reference? {
        val lexer = Lexer(source, offset)
        var previous: String? = null
        var count = 0
        fun next(): Token? {
            if (++count > 1024) return null
            return scans.next(lexer, previous).also { previous = it.context }
        }
        var token = next() ?: return null
        var groups = 0
        while (token.kind == 0 && token.text == "(") { groups++; token = next() ?: return null }
        if (token.kind != 0 || !identifier(token.text) || token.text in setOf("true", "false", "null", "undefined", "NaN", "Infinity")) return null
        val base = token.text
        var projected = false
        token = next() ?: return Reference(base, projected)
        while (token.kind == 0 && token.text in listOf(".", "[")) {
            projected = true
            if (token.text == ".") {
                token = next() ?: return Reference(base, projected)
                if (token.kind != 0 || !identifier(token.text)) return null
            } else {
                var depth = 1
                while (depth > 0) {
                    token = next() ?: return Reference(base, projected)
                    if (token.kind == -1) return null
                    if (token.kind == 0 && token.text == "[") depth++
                    if (token.kind == 0 && token.text == "]") depth--
                }
            }
            token = next() ?: return Reference(base, projected)
        }
        while (groups > 0) {
            if (token.kind != 0 || token.text != ")") return null
            groups--; token = next() ?: return Reference(base, projected)
        }
        return Reference(base, projected).takeIf { token.kind == -1 || token.kind == 0 && token.text in listOf(";", ",", ")", "}") }
    }

    fun hasDefinition(name: String): Boolean = names.containsKey(name)

    fun definition(name: String): Expr {
        val entries = names[name] ?: unsupported("Missing dependency '$name'")
        if (entries.size != 1) unsupported("Ambiguous dependency '$name'")
        if (name in mutatedObjects || writeCounts[name] != 1) unsupported("Externally mutated dependency '$name'")
        return parseAt(entries.single().offset)
    }

    fun parseAt(offset: Int): Expr = Parser(slice(offset)).completeExpression()

    private fun slice(offset: Int, scans: ScanBudget? = null): List<Token> {
        val lexer = Lexer(source, offset)
        val tokens = mutableListOf<Token>()
        var depth = 0
        while (true) {
            val previous = tokens.lastOrNull()?.context
            val token = scans?.next(lexer, previous) ?: lexer.next(previous)
            if (token.kind == -1 || token.kind == 0 && depth == 0 && token.text in setOf(",", ";", ")", "}")) {
                tokens.add(Token("<eof>", -1, token.offset)); return tokens
            }
            if (token.kind == 0) {
                if (token.text == "(" || token.text == "[" || token.text == "{") depth++
                if (token.text == ")" || token.text == "]" || token.text == "}") depth--
            }
            if (depth > 128) unsupported("Source nesting limit exceeded")
            if (tokens.size >= 150_000) unsupported("Definition token limit exceeded")
            tokens.add(token)
        }
    }

    fun classFunction(name: String): FunctionExpr {
        val entry = assignments.singleOrNull { classTarget(it.lhs, name) }
            ?: unsupported("Missing or ambiguous n class '$name'")
        return parseAt(entry.offset) as? FunctionExpr ?: unsupported("Unsupported n class")
    }

    fun method(name: String, methodName: String, runtime: Runtime): FunctionExpr {
        val methods = mutableListOf<FunctionExpr>()
        for (entry in assignments) {
            if (entry.lhs.take(3).map(Token::text) != listOf("g", ".", name) || entry.lhs.size == 3) continue
            val target = Parser(entry.lhs + Token("<eof>", -1)).completeExpression() as? Member ?: continue
            val parent = target.target as? Member ?: continue
            val root = parent.target as? Member ?: continue
            if (root.target != Id("g") || root.key != Lit(name)) continue
            if (runtime.expression(parent.key) == "prototype" && runtime.expression(target.key) == methodName) {
                methods.add(parseAt(entry.offset) as? FunctionExpr ?: unsupported("Invalid n method"))
            }
        }
        if (methods.size != 1) unsupported("Missing or ambiguous n $methodName method for '$name'")
        return methods.single()
    }

    companion object {
        // Track String mutations too: an overridden split need not return an array.
        private val readOnlyPredicates = mapOf("Array" to setOf("isArray"), "Number" to setOf("isNaN", "isFinite"), "String" to emptySet())
        fun identifier(name: String): Boolean = name.isNotEmpty() && name[0].let { it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '$' } && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '$' }
        private val nonCalls = setOf("function", "if", "while", "for", "switch", "catch", "with", "typeof", "void", "delete", "return", "throw", "new")
        private val compoundWrites = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "**=", "&&=", "||=", "??=")
        private val targetPrefixes = setOf("var", "let", "const", ";", ",", "{", "}", "(", ")")
        private val callPrefixes = setOf("=", "return", "new", "(", "?", ":")
        private fun classTarget(tokens: List<Token>, name: String): Boolean =
            tokens.size == 3 && tokens[0].text == "g" && tokens[1].text == "." && tokens[2].text == name
        private fun targetEnd(tokens: List<Token>): Int {
            var end = tokens.lastIndex
            while (end >= 0 && tokens[end].kind == 0 && tokens[end].text == ")") end--
            return end
        }
        private fun assignmentTargetStart(tokens: List<Token>, end: Int = tokens.lastIndex, call: Boolean = false): Int {
            var start = end
            if (start < 0) return -1
            var brackets = 0
            while (start >= 0) {
                val token = tokens[start]
                if (token.kind != 0) { if (brackets == 0) return -1; start--; continue }
                if (token.text == "]") brackets++
                if (token.text == "[") brackets--
                if (brackets == 0 && token.text != "." && token.text != "[" && token.text != "]") {
                    if (!identifier(token.text)) return -1
                    if (start > 0 && tokens[start - 1].text == ".") { start--; continue }
                    val before = tokens.getOrNull(start - 1)?.text
                    if (before != null && before !in targetPrefixes && !(call && before in callPrefixes)) return -1
                    return start
                }
                start--
            }
            return -1
        }
    }
}
