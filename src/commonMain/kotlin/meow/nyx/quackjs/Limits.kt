package meow.nyx.quackjs

/** Left-associative chains do not consume parser recursion, but still need an AST depth cap. */
internal fun validateDepth(root: Expr) {
    val pending = ArrayDeque<Pair<Any, Int>>()
    pending.addLast(root to 0)
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeLast()
        if (depth > 128) unsupported("Syntax tree depth limit exceeded")
        fun child(value: Any?) { if (value != null) pending.addLast(value to depth + 1) }
        when (node) {
            is Unary -> child(node.expr)
            is Binary -> { child(node.left); child(node.right) }
            is Conditional -> { child(node.test); child(node.yes); child(node.no) }
            is Member -> { child(node.target); child(node.key) }
            is Call -> { child(node.fn); node.args.forEach(::child) }
            is Update -> child(node.target)
            is ArrayExpr -> node.items.forEach(::child)
            is ObjectExpr -> node.items.forEach { child(it.second) }
            is FunctionExpr -> child(node.body)
            is Block -> node.statements.forEach(::child)
            is ExpressionStmt -> child(node.expr)
            is Vars -> node.items.forEach { child(it.second) }
            is IfStmt -> { child(node.test); child(node.yes); child(node.no) }
            is ReturnStmt -> child(node.expr)
            is ThrowStmt -> child(node.expr)
            is Loop -> { child(node.init); child(node.test); child(node.update); child(node.body) }
            is Label -> child(node.body)
            is TryStmt -> { child(node.body); child(node.handler); child(node.finalizer) }
            is SwitchStmt -> { child(node.expr); node.cases.forEach { (key, statements) -> child(key); statements.forEach(::child) } }
        }
    }
}
