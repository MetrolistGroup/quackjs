package meow.nyx.quackjs

/** Left-associative chains do not consume parser recursion, but still need an AST depth cap. */
internal fun validateDepth(root: Expr) {
    val pending = ArrayDeque<Pair<Any, Int>>()
    pending.addLast(root to 0)
    var nodes = 0
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        if (++nodes > 150_000) unsupported("Syntax tree node limit exceeded")
        if (depth > 128) unsupported("Syntax tree depth limit exceeded")
        syntaxChildren(node) { pending.addLast(it to depth + 1) }
    }
}
