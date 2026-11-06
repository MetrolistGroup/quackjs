package meow.nyx.quackjs

internal fun unsupported(message: String): Nothing = throw UnsupportedPlayerException(message)
internal fun jsWhitespace(c: Char): Boolean = if (c <= '\u007f') c == ' ' || c in '\u0009'..'\u000d' else c in '\u2000'..'\u200a' || c in "\u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff"
internal fun jsLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

internal data class Token(val text: String, val kind: Int = 0, val offset: Int = 0, val lineBreakBefore: Boolean = false)
internal val Token.context: String get() = if (kind == 0) text else "<value>"

/** Lex only selected definitions. Strings and regexes stay opaque while slicing. */
internal class Lexer(private val source: String, private var p: Int = 0) {
    val position: Int get() = p
    private data class Context(val closing: Int, val regexAfter: Boolean, val functionExpression: Boolean? = null, val switchBody: Boolean = false)
    private val contexts = ArrayDeque<Context>()
    private val questions = ArrayDeque<Int>()
    private val caseHeaders = mutableSetOf<Int>()
    private var started = false
    private var statementStart = p == 0
    private var previousTag = -1
    private var pendingFunction: Boolean? = null
    private var pendingBody: Boolean? = null
    private var pendingSwitch = false
    private var statementName = false
    fun tokens(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            val t = next(out.lastOrNull()?.context)
            out.add(t)
            if (out.size > 150_000) unsupported("Transform definition exceeds token limit")
            if (t.kind == -1) return out
        }
    }

    var kind: Int = -1
        private set
    var offset: Int = p
        private set
    var end: Int = p
        private set
    var tag: Int = -1
        private set
    private var cachedText: String? = null
    val text: String get() = cachedText ?: when (kind) {
        -1 -> "<eof>"
        1 -> Lexer(source, offset + 1).quoted(source[offset], true)!!
        else -> source.substring(offset, end)
    }.also { cachedText = it }
    var regexAfter: Boolean = true
        private set
    var lineBreakBefore: Boolean = false
        private set
    fun materialize(): Token = Token(text, kind, offset, lineBreakBefore)
    fun next(previous: String? = null): Token {
        scan(if (started) regexAfter else previous == null || previous in regexPrefix)
        return materialize()
    }
    private fun finish(start: Int, tokenKind: Int) {
        offset = start
        end = p
        kind = tokenKind
        tag = if (kind == 0) ScanTags.code(source, start, p) else -kind - 1
        cachedText = null
        updateContext()
    }

    private fun isText(value: String): Boolean = end - offset == value.length && source.regionMatches(offset, value, 0, value.length)

    private fun updateContext() {
        val wasStatementStart = statementStart
        val body = pendingBody
        val switchBody = pendingSwitch
        pendingBody = null
        pendingSwitch = false
        val name = kind == 0 && offset < end && source[offset].let { it.isLetter() || it == '_' || it == '$' }
        val memberName = name && (previousTag == ScanTags.DOT || previousTag == ScanTags.OPTIONAL)
        if (tag != ScanTags.FUNCTION && tag != ScanTags.LPAREN && tag != ScanTags.STAR && !name) pendingFunction = null
        when {
            kind != 0 -> { regexAfter = false; statementStart = false }
            memberName -> { regexAfter = false; statementStart = false }
            tag == ScanTags.FUNCTION -> {
                pendingFunction = !(statementStart || lineBreakBefore && !regexAfter)
                regexAfter = false; statementStart = false
            }
            tag == ScanTags.LPAREN -> {
                val control = previousTag in controlHeaders
                contexts.addLast(Context(ScanTags.RPAREN, control, pendingFunction, previousTag == ScanTags.SWITCH))
                pendingFunction = null
                regexAfter = true; statementStart = false
            }
            tag == ScanTags.LBRACKET -> {
                contexts.addLast(Context(ScanTags.RBRACKET, false))
                regexAfter = true; statementStart = false
            }
            tag == ScanTags.LBRACE -> {
                val block = body != null || statementStart || lineBreakBefore && !regexAfter || previousTag == ScanTags.ARROW ||
                    previousTag == ScanTags.RETURN && lineBreakBefore
                contexts.addLast(Context(ScanTags.RBRACE, if (body != null) !body else block && previousTag != ScanTags.ARROW, switchBody = switchBody))
                regexAfter = true; statementStart = block
            }
            tag == ScanTags.RPAREN || tag == ScanTags.RBRACKET || tag == ScanTags.RBRACE -> {
                val context = contexts.lastOrNull()?.takeIf { it.closing == tag }?.also { contexts.removeLast() }
                caseHeaders.remove(contexts.size + 1)
                while (questions.lastOrNull()?.let { it > contexts.size } == true) questions.removeLast()
                regexAfter = context?.regexAfter ?: (tag == ScanTags.RBRACE)
                pendingBody = context?.functionExpression
                pendingSwitch = context?.switchBody == true && tag == ScanTags.RPAREN
                statementStart = regexAfter
            }
            tag == ScanTags.SEMI || tag == ScanTags.ELSE || tag == ScanTags.CATCH || isText("do") || isText("try") || isText("finally") -> {
                regexAfter = true; statementStart = true
            }
            contexts.lastOrNull()?.switchBody == true && (tag == ScanTags.CASE || isText("default")) -> {
                caseHeaders.add(contexts.size)
                regexAfter = true; statementStart = false
            }
            tag == ScanTags.QUESTION -> {
                questions.addLast(contexts.size)
                regexAfter = true; statementStart = false
            }
            tag == ScanTags.COLON -> {
                val conditional = questions.lastOrNull() == contexts.size
                if (conditional) questions.removeLast()
                val caseHeader = !conditional && caseHeaders.remove(contexts.size)
                regexAfter = true; statementStart = !conditional && (statementName || caseHeader)
            }
            else -> {
                regexAfter = tag in regexTags || isText("in") || isText("instanceof")
                statementStart = false
            }
        }
        if (contexts.size > 1024) unsupported("Source nesting limit exceeded")
        statementName = wasStatementStart && name
        previousTag = if (memberName) ScanTags.IDENTIFIER else tag
        started = true
    }

    /** Advance without allocating token text. All literal validation still runs. */
    fun scan(regexAllowed: Boolean = true) {
        lineBreakBefore = false
        while (p < source.length) {
            if (jsWhitespace(source[p])) { if (jsLineTerminator(source[p])) lineBreakBefore = true; p++; continue }
            if (source.startsWith("//", p)) {
                while (p < source.length && !jsLineTerminator(source[p])) p++
                continue
            }
            if (source.startsWith("/*", p)) {
                val end = source.indexOf("*/", p + 2)
                if (end < 0) unsupported("Unclosed comment")
                for (i in p + 2 until end) if (jsLineTerminator(source[i])) lineBreakBefore = true
                p = end + 2; continue
            }
            break
        }
        val start = p
        if (p == source.length) return finish(p, -1)
        val c = source[p++]
        if (c == '`') {
            while (p < source.length) {
                val ch = source[p++]
                if (ch == '\\') { if (p < source.length) p++; continue }
                if (ch == '`') return finish(start, 4)
                if (ch == '$' && p < source.length && source[p] == '{') unsupported("Template interpolation is unsupported")
            }
            unsupported("Unclosed template literal")
        }
        if (c == '\'' || c == '"') {
            quoted(c, false)
            return finish(start, 1)
        }
        if (c == '\\') unsupported("Escaped identifiers are unsupported")
        if (c.isLetter() || c == '_' || c == '$') {
            while (p < source.length && (source[p].isLetterOrDigit() || source[p] == '_' || source[p] == '$')) p++
            return finish(start, 0)
        }
        if (c.isDigit() || c == '.' && p < source.length && source[p].isDigit()) {
            if (c == '0' && p < source.length && source[p] in "xXbBoO") {
                p++
                while (p < source.length && source[p].isLetterOrDigit()) p++
            } else {
                while (p < source.length && source[p].isDigit()) p++
                if (c != '.' && p < source.length && source[p] == '.') {
                    p++
                    while (p < source.length && source[p].isDigit()) p++
                }
                if (p < source.length && source[p] in "eE") {
                    p++
                    if (p < source.length && source[p] in "+-") p++
                    while (p < source.length && source[p].isDigit()) p++
                }
                while (p < source.length && source[p].isLetterOrDigit()) p++
            }
            return finish(start, 2)
        }
        if (c == '/' && regexAllowed) {
            var bracket = false
            while (p < source.length) {
                val ch = source[p++]
                if (ch == '\\') { p++; continue }
                if (ch == '[') bracket = true
                if (ch == ']') bracket = false
                if (ch == '/' && !bracket) {
                    while (p < source.length && source[p].isLetter()) p++
                    return finish(start, 3)
                }
                if (jsLineTerminator(ch)) unsupported("Unclosed regex")
            }
            unsupported("Unclosed regex")
        }
        for (op in operators[c].orEmpty()) if (source.startsWith(op, start)) {
            p = start + op.length; return finish(start, 0)
        }
        return finish(start, 0)
    }

    private fun quoted(c: Char, decode: Boolean): String? {
        val result = if (decode) StringBuilder() else null
        while (p < source.length) {
            var ch = source[p++]
            if (ch == c) return result?.toString()
            if (ch == '\\') {
                if (p == source.length) unsupported("Unclosed string escape")
                ch = source[p++]
                when (ch) {
                    'n' -> result?.append('\n'); 'r' -> result?.append('\r'); 't' -> result?.append('\t')
                    'b' -> result?.append('\b'); 'f' -> result?.append('\u000c'); 'v' -> result?.append('\u000b')
                    '\n' -> Unit
                    '\r' -> if (p < source.length && source[p] == '\n') p++
                    'x', 'u' -> {
                        val length = if (ch == 'x') 2 else 4
                        if (p + length > source.length) unsupported("Invalid string escape")
                        val n = source.substring(p, p + length).toIntOrNull(16) ?: unsupported("Invalid string escape")
                        result?.append(n.toChar()); p += length
                    }
                    '0' -> { if (p < source.length && source[p].isDigit()) unsupported("Legacy octal string escape"); result?.append('\u0000') }
                    in '1'..'7' -> unsupported("Legacy octal string escape")
                    else -> result?.append(ch)
                }
            } else {
                if (ch == '\n' || ch == '\r') unsupported("Unclosed string")
                result?.append(ch)
            }
        }
        unsupported("Unclosed string")
    }

    companion object {
        private val controlHeaders = intArrayOf(ScanTags.IF, ScanTags.WHILE, ScanTags.FOR, ScanTags.SWITCH, ScanTags.CATCH, ScanTags.WITH)
        private val regexTags = intArrayOf(
            ScanTags.EQUAL, ScanTags.COMMA, ScanTags.RETURN, ScanTags.CASE, ScanTags.BANG, ScanTags.AND, ScanTags.OR,
            ScanTags.QUESTION, ScanTags.PLUS, ScanTags.MINUS, ScanTags.STAR, ScanTags.CARET, ScanTags.AMP, ScanTags.PIPE, ScanTags.THROW,
            ScanTags.STRICT_EQUAL, ScanTags.STRICT_NOT_EQUAL, ScanTags.EQ, ScanTags.NE, '<'.code, '>'.code, ScanTags.LE, ScanTags.GE,
            '%'.code, '/'.code, ScanTags.POW, ScanTags.SHL, ScanTags.SHR, ScanTags.USHR, ScanTags.NULLISH,
            ScanTags.PLUS_ASSIGN, ScanTags.MINUS_ASSIGN, ScanTags.STAR_ASSIGN, ScanTags.SLASH_ASSIGN, ScanTags.MOD_ASSIGN,
            ScanTags.AMP_ASSIGN, ScanTags.PIPE_ASSIGN, ScanTags.CARET_ASSIGN, ScanTags.SHL_ASSIGN, ScanTags.SHR_ASSIGN,
            ScanTags.USHR_ASSIGN, ScanTags.POW_ASSIGN, ScanTags.AND_ASSIGN, ScanTags.OR_ASSIGN, ScanTags.NULL_ASSIGN,
            ScanTags.ARROW, ScanTags.TYPEOF, ScanTags.VOID, ScanTags.DELETE, ScanTags.NEW, '~'.code,
        )
        private val regexPrefix = setOf("=", "(", "[", ",", ":", ";", "return", "case", "else", "!", "&&", "||", "?", "{", "}", "+", "-", "*", "^", "&", "|", "throw", "==", "!=", "===", "!==", "<", ">", "<=", ">=", "%", "/", "**", "<<", ">>", ">>>", "??", "typeof", "void", "delete", "new", "in", "instanceof", "~")
        private val operators = listOf(">>>=", "&&=", "||=", "??=", "===", "!==", ">>>", "<<=", ">>=", "**=", "=>", "==", "!=", "<=", ">=", "++", "--", "&&", "||", "??", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "**", "?.", "...").groupBy { it[0] }
    }
}

internal sealed interface SyntaxNode
internal sealed interface Expr : SyntaxNode
internal data class Lit(val value: JsValue) : Expr {
    constructor(value: String) : this(JsString(value))
    constructor(value: Double) : this(JsNumber(value))
    constructor(value: Boolean) : this(JsBoolean(value))
}
internal data class Id(val name: String) : Expr {
    // Bound once by the enclosing function; captured and catch-shadowed names use lexical lookup.
    var slot: Int = -1
        internal set
    var free: Boolean = false
        internal set
    var globalSlot: Int = -1
        internal set
}
internal data class Unary(val op: String, val expr: Expr) : Expr
internal data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
internal val assignmentOperators = setOf("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=")
internal data class Conditional(val test: Expr, val yes: Expr, val no: Expr) : Expr
internal data class Member(val target: Expr, val key: Expr) : Expr
internal data class Call(val fn: Expr, val args: List<Expr>, val construct: Boolean = false) : Expr
internal data class Update(val op: String, val target: Expr, val prefix: Boolean) : Expr
internal data class ArrayExpr(val items: List<Expr>) : Expr
internal data class ObjectExpr(val items: List<Pair<String, Expr>>) : Expr
internal class FunctionExpr(
    val params: List<String>,
    val body: Block,
    val name: String? = null,
    private val source: String? = null,
    private val sourceStart: Int = -1,
    private val sourceEnd: Int = -1,
) : Expr {
    fun sourceText(): String? {
        if (source == null || sourceStart < 0 || sourceEnd !in sourceStart..source.length) return null
        if (sourceEnd - sourceStart > 65_536) unsupported("Function string size limit exceeded")
        return source.substring(sourceStart, sourceEnd)
    }
    val slots: Map<String, Int>
    val parameterSlots: IntArray
    val thisSlot: Int
    val argumentsSlot: Int
    val nameSlot: Int
    val usesArguments: Boolean
    val hasClosures: Boolean
    val hotCharacterPlan: HotCharacterPlan?
    init {
        val names = mutableSetOf<String>()
        val ids = mutableListOf<Id>()
        val catches = mutableSetOf<String>()
        var arguments = false
        var closures = false
        val pending = ArrayDeque<SyntaxNode>()
        pending.add(body)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            when (node) {
                is Id -> { ids.add(node); if (node.name == "arguments") arguments = true }
                is Vars -> node.items.forEach { names.add(it.first) }
                is TryStmt -> node.catchName?.let { catches.add(it) }
                is FunctionExpr -> closures = true
                else -> Unit
            }
            if (node !is FunctionExpr) syntaxChildren(node) { pending.add(it) }
        }
        val all = (params + listOf("this", "arguments") + listOfNotNull(name) + names).distinct()
        slots = all.withIndex().associate { it.value to it.index }
        parameterSlots = IntArray(params.size) { slots.getValue(params[it]) }
        thisSlot = slots.getValue("this")
        argumentsSlot = slots.getValue("arguments")
        nameSlot = name?.takeIf { it !in params && it !in names && it != "arguments" }?.let { slots.getValue(it) } ?: -1
        for (id in ids) if (id.name !in catches) {
            id.slot = slots[id.name] ?: -1
            id.free = id.slot < 0
        }
        usesArguments = arguments && "arguments" !in params
        hasClosures = closures
        hotCharacterPlan = HotCharacterPlan.compile(this)
    }
}
internal sealed interface Stmt : SyntaxNode
internal data class Block(val statements: List<Stmt>) : Stmt
internal data class ExpressionStmt(val expr: Expr) : Stmt
internal data class Vars(val items: List<Pair<String, Expr?>>) : Stmt
internal data class IfStmt(val test: Expr, val yes: Stmt, val no: Stmt?) : Stmt
internal data class ReturnStmt(val expr: Expr?) : Stmt
internal data class ThrowStmt(val expr: Expr) : Stmt
internal data class Loop(val init: Stmt?, val test: Expr?, val update: Expr?, val body: Stmt, val doFirst: Boolean = false) : Stmt
internal data class Jump(val isContinue: Boolean, val label: String?) : Stmt
internal data class Label(val name: String, val body: Stmt) : Stmt
internal data class TryStmt(val body: Stmt, val catchName: String?, val handler: Stmt?, val finalizer: Stmt?) : Stmt
internal data class SwitchStmt(val expr: Expr, val cases: List<Pair<Expr?, List<Stmt>>>) : Stmt
internal data object EmptyStmt : Stmt

internal class Parser(private val tokens: List<Token>, private val source: String? = null, private val budget: CompilationBudget = CompilationBudget()) {
    init { budget.reserveSyntax(tokens.size) }
    private var p = 0
    private var depth = 0
    private val t get() = tokens[p].text
    private fun take(): Token {
        if (tokens[p].kind == -1) unsupported("Unexpected end of transform definition")
        return tokens[p++]
    }
    private fun accept(s: String): Boolean = if (t == s && tokens[p].kind == 0) { p++; true } else false
    private fun expect(s: String) { if (!accept(s)) unsupported("Expected '$s' at ${tokens[p].offset}, got '$t'") }
    private fun identifier(): String {
        if (tokens[p].kind != 0 || !t.first().let { it.isLetter() || it == '_' || it == '$' }) unsupported("Expected identifier, got '$t'")
        return take().text
    }
    private inline fun <T> nested(body: () -> T): T {
        if (++depth > 128) unsupported("Parsing depth limit exceeded")
        try { return body() } finally { depth-- }
    }
    fun completeExpression(): Expr {
        val e = expression()
        if (tokens[p].kind != -1) unsupported("Unexpected token '$t'")
        validateDepth(e, budget)
        return e
    }
    fun expression(min: Int = 0): Expr = nested {
        var left = prefix()
        while (true) {
            if (t == "?" && min <= 2) {
                take(); val yes = expression(1); expect(":"); left = Conditional(left, yes, expression(2)); continue
            }
            val prec = precedence[t] ?: break
            if (prec < min) break
            val op = take().text
            left = Binary(op, left, expression(if (prec == 1 || op == "**") prec else prec + 1))
        }
        left
    }
    private fun prefix(): Expr {
        val token = take()
        val text = token.text
        if (token.kind == 1) return postfix(Lit(text))
        if (token.kind == 2) return postfix(Lit(numberLiteral(text)))
        if (token.kind == 3) return postfix(Lit(RegexValue(text)))
        val base: Expr = when (text) {
            "!", "~", "+", "-", "typeof", "void", "delete" -> return Unary(text, expression(14))
            "++", "--" -> return Update(text, expression(14), true)
            "new" -> {
                var fn = prefixNew()
                val args = if (accept("(")) arguments() else emptyList()
                return postfix(Call(fn, args, true))
            }
            "(" -> { val e = expression(); expect(")"); e }
            "[" -> {
                val items = mutableListOf<Expr>()
                while (!accept("]")) {
                    if (accept(",")) { items.add(Lit(Hole)); continue }
                    items.add(expression(1))
                    if (!accept(",")) { expect("]"); break }
                }
                ArrayExpr(items)
            }
            "{" -> {
                val items = mutableListOf<Pair<String, Expr>>()
                while (!accept("}")) {
                    val keyToken = take()
                    val key = if (keyToken.kind == 2) meow.nyx.quackjs.text(JsNumber(numberLiteral(keyToken.text))) else keyToken.text
                    expect(":"); items.add(key to expression(1))
                    if (!accept(",")) { expect("}"); break }
                }
                ObjectExpr(items)
            }
            "function" -> {
                val start = token.offset
                val name = if (t != "(") identifier() else null
                expect("("); val params = mutableListOf<String>()
                while (!accept(")")) {
                    params.add(identifier()); if (!accept(",")) { expect(")"); break }
                }
                val body = block()
                val endToken = tokens[p - 1]
                val end = endToken.offset + endToken.text.length
                FunctionExpr(params, body, name, source, start, end)
            }
            "true" -> Lit(true); "false" -> Lit(false); "null" -> Lit(JsNull)
            else -> {
                if (token.kind != 0 || text.firstOrNull()?.let { it.isLetter() || it == '_' || it == '$' } != true) unsupported("Unsupported expression '$text' at ${token.offset}")
                Id(text)
            }
        }
        return postfix(base)
    }
    private fun prefixNew(): Expr {
        var e: Expr = Id(identifier())
        while (true) e = when {
            accept(".") -> Member(e, Lit(identifier()))
            accept("[") -> { val key = expression(); expect("]"); Member(e, key) }
            else -> return e
        }
    }
    private fun postfix(initial: Expr): Expr {
        var e = initial
        while (true) e = when {
            accept(".") -> Member(e, Lit(identifier()))
            accept("[") -> { val key = expression(); expect("]"); Member(e, key) }
            accept("(") -> Call(e, arguments())
            !tokens[p].lineBreakBefore && accept("++") -> Update("++", e, false)
            !tokens[p].lineBreakBefore && accept("--") -> Update("--", e, false)
            else -> return e
        }
    }
    private fun arguments(): List<Expr> {
        val args = mutableListOf<Expr>()
        while (!accept(")")) {
            args.add(expression(1)); if (!accept(",")) { expect(")"); break }
        }
        return args
    }
    private fun block(): Block = nested {
        expect("{")
        val list = mutableListOf<Stmt>()
        while (!accept("}")) {
            if (tokens[p].kind == -1) unsupported("Unclosed block")
            list.add(statement())
        }
        Block(list)
    }
    private fun vars(): Vars {
        val list = mutableListOf<Pair<String, Expr?>>()
        do { val name = identifier(); list.add(name to if (accept("=")) expression(1) else null) } while (accept(","))
        return Vars(list)
    }
    private fun condition(): Expr { expect("("); val e = expression(); expect(")"); return e }
    fun statement(): Stmt = nested {
        when {
            t == "{" -> block()
            accept(";") -> EmptyStmt
            accept("var") -> vars().also { accept(";") }
            accept("let") || accept("const") -> unsupported("Block-scoped declarations are unsupported")
            accept("if") -> { val e = condition(); val yes = statement(); IfStmt(e, yes, if (accept("else")) statement() else null) }
            accept("return") -> ReturnStmt(if (tokens[p].lineBreakBefore || tokens[p].kind == -1 || t == ";" || t == "}") null else expression()).also { accept(";") }
            accept("throw") -> {
                if (tokens[p].lineBreakBefore) unsupported("Line terminator after throw")
                ThrowStmt(expression()).also { accept(";") }
            }
            accept("while") -> Loop(null, condition(), null, statement())
            accept("do") -> { val body = statement(); expect("while"); Loop(null, condition(), null, body, true).also { accept(";") } }
            accept("for") -> {
                expect("(")
                val init = when {
                    t == ";" -> null
                    accept("let") || accept("const") -> unsupported("Block-scoped declarations are unsupported")
                    accept("var") -> vars()
                    else -> ExpressionStmt(expression())
                }
                expect(";"); val test = if (t == ";") null else expression(); expect(";")
                val update = if (t == ")") null else expression(); expect(")"); Loop(init, test, update, statement())
            }
            accept("break") -> Jump(false, if (!tokens[p].lineBreakBefore && tokens[p].kind != -1 && t != ";" && t != "}") identifier() else null).also { accept(";") }
            accept("continue") -> Jump(true, if (!tokens[p].lineBreakBefore && tokens[p].kind != -1 && t != ";" && t != "}") identifier() else null).also { accept(";") }
            accept("try") -> {
                val body = block(); var name: String? = null; var handler: Stmt? = null
                if (accept("catch")) { expect("("); name = identifier(); expect(")"); handler = block() }
                val finalizer = if (accept("finally")) block() else null
                if (handler == null && finalizer == null) unsupported("Invalid try statement")
                TryStmt(body, name, handler, finalizer)
            }
            accept("switch") -> {
                val expr = condition(); expect("{"); val cases = mutableListOf<Pair<Expr?, List<Stmt>>>()
                while (!accept("}")) {
                    val key = if (accept("case")) expression(1) else { expect("default"); null }; expect(":")
                    val statements = mutableListOf<Stmt>()
                    while (t != "case" && t != "default" && t != "}") statements.add(statement())
                    cases.add(key to statements)
                }
                SwitchStmt(expr, cases)
            }
            accept("function") -> unsupported("Function declarations are unsupported")
            tokens.getOrNull(p + 1)?.text == ":" -> { val name = identifier(); expect(":"); Label(name, statement()) }
            else -> ExpressionStmt(expression()).also { accept(";") }
        }
    }
    companion object {
        private fun numberLiteral(text: String): Double {
            if (text.length > 1 && text[0] == '0' && text[1].isDigit()) unsupported("Legacy octal number literal")
            return when {
                text.startsWith("0x", true) -> text.drop(2).toLongOrNull(16)?.toDouble()
                text.startsWith("0b", true) -> text.drop(2).toLongOrNull(2)?.toDouble()
                text.startsWith("0o", true) -> text.drop(2).toLongOrNull(8)?.toDouble()
                else -> text.toDoubleOrNull()
            } ?: unsupported("Unsupported number '$text'")
        }
        private val precedence = mapOf("," to 0, "=" to 1, "+=" to 1, "-=" to 1, "*=" to 1, "/=" to 1, "%=" to 1, "&=" to 1, "|=" to 1, "^=" to 1, "<<=" to 1, ">>=" to 1, ">>>=" to 1,
            "||" to 3, "??" to 3, "&&" to 4, "|" to 5, "^" to 6, "&" to 7, "==" to 8, "!=" to 8, "===" to 8, "!==" to 8, "<" to 9, ">" to 9, "<=" to 9, ">=" to 9, "in" to 9, "instanceof" to 9,
            "<<" to 10, ">>" to 10, ">>>" to 10, "+" to 11, "-" to 11, "*" to 12, "/" to 12, "%" to 12, "**" to 13)
    }
}
