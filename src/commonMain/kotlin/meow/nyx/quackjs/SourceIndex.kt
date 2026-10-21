package meow.nyx.quackjs

/** Lex once to index the player scope; parse and execute only selected dependencies. */
internal class SourceIndex(val source: String, className: String) {
    private data class Assignment(val lhs: List<Token>, val offset: Int, val braces: Int, val parens: Int)
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
        val recent = ArrayDeque<Token>()
        val found = mutableListOf<Assignment>()
        val methodCalls = mutableListOf<Triple<String, Int, Int>>()
        val callArguments = mutableListOf<Pair<Int, Int>>()
        val updates = mutableListOf<Triple<String, Int, Boolean>>()
        val stamps = mutableSetOf<Int>()
        var pendingStamp: Int? = null
        var braces = 0
        var parens = 0
        var count = 0
        var previous: String? = null
        var prefixUpdate = false
        fun recordUpdate(name: String, member: Boolean) {
            if (updates.size >= 100_000) unsupported("Source mutation limit exceeded")
            updates.add(Triple(name, braces, member))
        }
        while (true) {
            val token = lexer.next(previous)
            if (token.kind == -1) break
            if (++count > 2_000_000) unsupported("Source token limit exceeded")
            if (pendingStamp != null && token.kind == 0 && token.text in setOf(",", "}")) stamps.add(pendingStamp)
            pendingStamp = if (token.kind == 2 && token.text.length in 4..6 && recent.lastOrNull()?.text == ":" && recent.elementAtOrNull(recent.size - 2)?.text == "signatureTimestamp") token.text.toIntOrNull() else null
            if (prefixUpdate) {
                if (token.kind == 0 && identifier(token.text)) {
                    val next = Lexer(source, token.offset + token.text.length).next(token.context)
                    recordUpdate(token.text, next.kind == 0 && next.text in listOf(".", "["))
                    prefixUpdate = false
                }
                else if (token.kind != 0 || token.text != "(") prefixUpdate = false
            }
            if (token.kind == 0 && token.text == "delete") prefixUpdate = true
            if (token.kind == 0 && (token.text in compoundWrites || token.text == "++" || token.text == "--")) {
                val lhs = assignmentTarget(recent, call = true, grouped = true)
                if (lhs != null) recordUpdate(lhs.first().text, lhs.size > 1)
                prefixUpdate = token.text == "++" || token.text == "--"
            }
            if (token.kind == 0 && token.text == "=") {
                val lhs = assignmentTarget(recent, grouped = true)
                if (lhs != null) {
                    if (found.size >= 100_000) unsupported("Source definition limit exceeded")
                    found.add(Assignment(lhs, token.offset + 1, braces, parens))
                }
            }
            if (token.kind == 0 && token.text == "(") {
                val target = assignmentTarget(recent, call = true)
                if (target != null && target.first().text !in nonCalls || target == null && recent.lastOrNull()?.text in listOf(")", "?.")) {
                    if (callArguments.size >= 100_000) unsupported("Source call limit exceeded")
                    callArguments.add(token.offset + 1 to braces)
                    if (target != null && target.size > 1) methodCalls.add(Triple(target.first().text, braces, parens))
                }
            }
            if (token.kind == 0) when (token.text) {
                "{" -> braces++
                "}" -> braces--
                "(" -> parens++
                ")" -> parens--
            }
            if (braces < 0 || parens < 0 || braces > 512 || parens > 512) unsupported("Source nesting limit exceeded")
            if (recent.size == 96) recent.removeFirst()
            recent.addLast(token)
            previous = token.context
        }
        if (braces != 0 || parens != 0) unsupported("Unbalanced player source")
        val classes = found.filter { it.lhs.map(Token::text) == listOf("g", ".", className) }
        val depth = classes.minOfOrNull { it.braces } ?: unsupported("Missing n class '$className'")
        val classAssignments = classes.filter { it.braces == depth }
        if (classAssignments.size != 1) unsupported("Ambiguous n class '$className'")
        rootBraces = classAssignments.single().braces
        rootParens = classAssignments.single().parens
        assignments = found.filter { it.braces == rootBraces && it.parens == rootParens }
        names = assignments.filter { it.lhs.size == 1 }.groupBy { it.lhs.single().text }
        val writes = found.filter { it.braces == rootBraces }
        writeCounts = writes.filter { it.lhs.size == 1 }.groupingBy { it.lhs.single().text }.eachCount()
        val primitives = mutableMapOf<String, Boolean>()
        fun canMutate(name: String): Boolean = !primitiveDefinition(name, primitives)
        val sharedMutations = (methodCalls.filter { it.second == rootBraces }.map { it.first } + writes.filter { it.lhs.size > 1 }.map { it.lhs.first().text } + updates.filter { it.second == rootBraces && it.third }.map { it.first }).filter(::canMutate).toMutableSet()
        // Calls can mutate reference values, but cannot mutate a primitive or its copied aliases.
        for ((offset, depth) in callArguments) if (depth == rootBraces) argumentReferences(offset).filterTo(sharedMutations, ::canMutate)
        val aliases = mutableMapOf<String, MutableSet<String>>()
        for (write in writes) {
            if (write.lhs.size != 1) continue
            val direct = referenceBase(write.offset)
            val first = if (direct == null) Lexer(source, write.offset).next() else null
            val targets = if (direct != null) setOf(direct) else if (first?.kind == 0 && first.text in listOf("[", "{")) argumentReferences(first.offset + 1, if (first.text == "[") "]" else "}", first.text) else emptySet()
            val alias = write.lhs.single().text
            for (target in targets) {
                aliases.getOrPut(alias) { mutableSetOf() }.add(target)
                aliases.getOrPut(target) { mutableSetOf() }.add(alias)
            }
        }
        val queue = ArrayDeque(sharedMutations)
        while (queue.isNotEmpty()) {
            for (alias in aliases[queue.removeFirst()].orEmpty()) if (canMutate(alias) && sharedMutations.add(alias)) queue.addLast(alias)
        }
        mutatedObjects = sharedMutations + updates.filter { it.second == rootBraces }.map { it.first }
        timestamps = stamps
    }

    /** Prove immutable initializer results without executing unrelated player code. */
    private fun primitiveDefinition(name: String, known: MutableMap<String, Boolean>, resolving: MutableSet<String> = mutableSetOf()): Boolean {
        known[name]?.let { return it }
        if (resolving.size >= 64 || !resolving.add(name)) return false
        try {
            fun primitive(expr: Expr): Boolean = when (expr) {
                is Lit -> expr.value == null || expr.value is String || expr.value is Double || expr.value is Boolean
                is Id -> primitiveDefinition(expr.name, known, resolving)
                is Unary -> expr.op in setOf("!", "~", "+", "-", "typeof", "void", "delete")
                is Binary -> when (expr.op) {
                    "=", "," -> primitive(expr.right)
                    "&&", "||", "??" -> primitive(expr.left) && primitive(expr.right)
                    else -> true
                }
                is Conditional -> primitive(expr.yes) && primitive(expr.no)
                else -> false
            }
            val entries = names[name]
            val result = if (entries == null) name in setOf("undefined", "NaN", "Infinity")
            else if (entries.size != 1 || writeCounts[name] != 1) false
            else {
                val offset = entries.single().offset
                val first = Lexer(source, offset).next()
                if (first.kind == 0 && first.text in setOf("function", "{", "[")) false
                else try { primitive(parseAt(offset)) } catch (_: UnsupportedPlayerException) { false }
            }
            // A budget cut in a longer alias chain must not poison a shorter proof.
            if (result || resolving.size == 1) known[name] = result
            return result
        } finally { resolving.remove(name) }
    }

    private fun argumentReferences(offset: Int, closing: String = ")", opening: String = "("): Set<String> {
        val lexer = Lexer(source, offset)
        val references = mutableSetOf<String>()
        var previous: String? = opening
        var depth = 0
        var count = 0
        var functionDepth: Int? = null
        var functionBody = false
        var parametersClosed = false
        var reference: Token? = null
        var beforeReference: String? = null
        while (true) {
            val token = lexer.next(previous)
            if (++count > 150_000) unsupported("Initialization call limit exceeded")
            if (token.kind == -1) unsupported("Unclosed initialization call")
            reference?.let { if (!(token.kind == 0 && token.text == ":" && beforeReference in listOf("{", ","))) references.add(it.text) }
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
                if (depth == 0 && token.text == closing) return references
                when (token.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> depth--
                }
            }
            previous = token.context
        }
    }

    /** Link direct reference paths without evaluating unrelated initializers. */
    private fun referenceBase(offset: Int): String? {
        val lexer = Lexer(source, offset)
        var previous: String? = null
        var count = 0
        fun next(): Token? {
            if (++count > 1024) return null
            return lexer.next(previous).also { previous = it.context }
        }
        var token = next() ?: return null
        var groups = 0
        while (token.kind == 0 && token.text == "(") { groups++; token = next() ?: return null }
        if (token.kind != 0 || !identifier(token.text) || token.text in setOf("true", "false", "null", "undefined", "NaN", "Infinity")) return null
        val base = token.text
        token = next() ?: return base
        while (token.kind == 0 && token.text in listOf(".", "[")) {
            if (token.text == ".") {
                token = next() ?: return base
                if (token.kind != 0 || !identifier(token.text)) return null
            } else {
                var depth = 1
                while (depth > 0) {
                    token = next() ?: return base
                    if (token.kind == -1) return null
                    if (token.kind == 0 && token.text == "[") depth++
                    if (token.kind == 0 && token.text == "]") depth--
                }
            }
            token = next() ?: return base
        }
        while (groups > 0) {
            if (token.kind != 0 || token.text != ")") return null
            groups--; token = next() ?: return base
        }
        return base.takeIf { token.kind == -1 || token.kind == 0 && token.text in listOf(";", ",", ")", "}") }
    }

    fun hasDefinition(name: String): Boolean = names.containsKey(name)

    fun definition(name: String): Expr {
        val entries = names[name] ?: unsupported("Missing dependency '$name'")
        if (entries.size != 1) unsupported("Ambiguous dependency '$name'")
        if (name in mutatedObjects || writeCounts[name] != 1) unsupported("Externally mutated dependency '$name'")
        return parseAt(entries.single().offset)
    }

    fun parseAt(offset: Int): Expr = Parser(slice(offset)).completeExpression()

    fun slice(offset: Int): List<Token> {
        val lexer = Lexer(source, offset)
        val tokens = mutableListOf<Token>()
        var depth = 0
        while (true) {
            val token = lexer.next(tokens.lastOrNull()?.context)
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
        val entry = assignments.singleOrNull { it.lhs.map(Token::text) == listOf("g", ".", name) }
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
        fun identifier(name: String): Boolean = name.isNotEmpty() && name[0].let { it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '$' } && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '$' }
        private val nonCalls = setOf("function", "if", "while", "for", "switch", "catch", "with", "typeof", "void", "delete", "return", "throw", "new")
        private val compoundWrites = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "**=", "&&=", "||=", "??=")
        private fun assignmentTarget(tokens: List<Token>, call: Boolean = false, grouped: Boolean = false): List<Token>? {
            var end = tokens.lastIndex
            if (grouped) while (end >= 0 && tokens[end].kind == 0 && tokens[end].text == ")") end--
            var start = end
            if (start < 0) return null
            var brackets = 0
            while (start >= 0) {
                val token = tokens[start]
                if (token.kind != 0) { if (brackets == 0) return null; start--; continue }
                if (token.text == "]") brackets++
                if (token.text == "[") brackets--
                if (brackets == 0 && token.text !in listOf(".", "[", "]")) {
                    if (!identifier(token.text)) return null
                    if (start > 0 && tokens[start - 1].text == ".") { start--; continue }
                    val before = tokens.getOrNull(start - 1)?.text
                    if (before != null && before !in listOf("var", "let", "const", ";", ",", "{", "}", "(", ")") && !(call && before in listOf("=", "return", "new", "(", "?", ":"))) return null
                    return tokens.subList(start, end + 1).toList()
                }
                start--
            }
            return null
        }
    }
}
