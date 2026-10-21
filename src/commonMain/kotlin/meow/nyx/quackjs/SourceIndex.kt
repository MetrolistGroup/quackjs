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
        if (className.length > 128 || !identifier.matches(className)) unsupported("Invalid n class hint")
        val lexer = Lexer(source)
        val recent = ArrayDeque<Token>()
        val found = mutableListOf<Assignment>()
        val methodCalls = mutableListOf<Triple<String, Int, Int>>()
        val updates = mutableListOf<Pair<String, Int>>()
        val stamps = mutableSetOf<Int>()
        var pendingStamp: Int? = null
        var braces = 0
        var parens = 0
        var count = 0
        var previous: String? = null
        var prefixUpdate = false
        fun recordUpdate(name: String) {
            if (updates.size >= 100_000) unsupported("Source mutation limit exceeded")
            updates.add(name to braces)
        }
        while (true) {
            val token = lexer.next(previous)
            if (token.kind == -1) break
            if (++count > 2_000_000) unsupported("Source token limit exceeded")
            if (pendingStamp != null && token.kind == 0 && token.text in setOf(",", "}")) stamps.add(pendingStamp)
            pendingStamp = if (token.kind == 2 && token.text.length in 4..6 && recent.lastOrNull()?.text == ":" && recent.elementAtOrNull(recent.size - 2)?.text == "signatureTimestamp") token.text.toIntOrNull() else null
            if (prefixUpdate) {
                if (token.kind == 0 && identifier.matches(token.text)) { recordUpdate(token.text); prefixUpdate = false }
                else if (token.kind != 0 || token.text != "(") prefixUpdate = false
            }
            if (token.kind == 0 && (token.text in compoundWrites || token.text == "++" || token.text == "--")) {
                val lhs = assignmentTarget(recent.toList().dropLastWhile { it.kind == 0 && it.text == ")" }, call = true)
                if (lhs != null) recordUpdate(lhs.first().text)
                prefixUpdate = token.text == "++" || token.text == "--"
            }
            if (token.kind == 0 && token.text == "=") {
                val lhs = assignmentTarget(recent.toList())
                if (lhs != null) {
                    if (found.size >= 100_000) unsupported("Source definition limit exceeded")
                    found.add(Assignment(lhs, token.offset + 1, braces, parens))
                }
            }
            if (token.kind == 0 && token.text == "(") {
                val target = assignmentTarget(recent.toList(), call = true)
                if (target != null && target.size > 1) {
                    if (methodCalls.size >= 100_000) unsupported("Source call limit exceeded")
                    methodCalls.add(Triple(target.first().text, braces, parens))
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
        mutatedObjects = methodCalls.filter { it.second == rootBraces }.map { it.first }.toSet() + writes.filter { it.lhs.size > 1 }.map { it.lhs.first().text } + updates.filter { it.second == rootBraces }.map { it.first }
        timestamps = stamps
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
        val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val compoundWrites = setOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "**=", "&&=", "||=", "??=")
        private fun assignmentTarget(tokens: List<Token>, call: Boolean = false): List<Token>? {
            var start = tokens.lastIndex
            if (start < 0) return null
            var brackets = 0
            while (start >= 0) {
                val token = tokens[start]
                if (token.kind != 0) { if (brackets == 0) return null; start--; continue }
                if (token.text == "]") brackets++
                if (token.text == "[") brackets--
                if (brackets == 0 && token.text !in listOf(".", "[", "]")) {
                    if (!identifier.matches(token.text)) return null
                    if (start > 0 && tokens[start - 1].text == ".") { start--; continue }
                    val before = tokens.getOrNull(start - 1)?.text
                    if (before != null && before !in listOf("var", "let", "const", ";", ",", "{", "}") && !(call && before in listOf("=", "return", "new", "(", "?", ":"))) return null
                    return tokens.subList(start, tokens.size).toList()
                }
                start--
            }
            return null
        }
    }
}
