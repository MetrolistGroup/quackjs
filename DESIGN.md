# QuackJS implementation contract

Goal: pure common Kotlin player.js signature + n deobfuscation, no JS engine/native/runtime dependencies. Use Faraday sig expression, nClass, sts clues to avoid evaluating whole players. Unsupported constructs must fail explicitly, never silently produce corrupt URLs. Bound untrusted input, parsing depth, execution steps, allocations. Compile once per player, reuse safely.

Public API (package `meow.nyx.quackjs`):

```kotlin
data class PlayerHints(val signatureExpression: String, val nClass: String, val signatureTimestamp: Int)
class PlayerProgram {
    companion object {
        fun compile(playerJs: String, hints: PlayerHints? = null): PlayerProgram
    }
    val signatureTimestamp: Int?
    fun decipherSignature(input: String): String
    fun transformN(input: String): String
    fun transformBatch(
        challenges: List<PlayerChallenge>,
        checkCancellation: () -> Unit = {},
    ): List<PlayerTransformResult>
}
class UnsupportedPlayerException(message: String) : IllegalArgumentException(message)
```

## Architecture

The runtime lives in commonMain and targets Android, JVM, Linux and iOS. It indexes source and follows only the required transform dependencies rather than initializing the whole browser player. Numeric dispatchers are specialized; parsed code and safe constant templates are reused. Mutable containers and execution budgets remain private to each invocation, so compiled programs support concurrent callers without output caching.

`nClass` is a URL wrapper in the original player. QuackJS discovers the n-table update and executes that dependency slice, rather than emulating browser URL handling. Hint-free compilation requires unambiguous executable landmarks; unsupported or ambiguous source fails explicitly. Optimized plans retain live mutation guards and interpreter fallbacks, not fixture/player-ID assumptions.

## Batches

`transformBatch` accepts at most 256 typed decoded challenges and returns one
success or failure per entry in request order. Its local memo keys include both
kind and input, and reuse failures as well as successes. Every cache miss uses
the ordinary single-transform path with independent mutable state and budgets;
no memo survives the call or is shared between concurrent batches. The entry cap
also bounds memo/result growth (each successful output retains the existing
65,536-unit limit). The batch has no separate wall-clock deadline.

An optional cancellation callback executes before every entry outside the
transform failure handler. Only `UnsupportedPlayerException` from transforms
becomes a result; cancellation and unexpected failures propagate. This keeps
coroutines and scheduling out of the stdlib-only runtime.

## Bounded string replacement

`String.replace` supports first-match literal searches, callbacks (match, captures,
UTF-16 offset, original string), and `$$`, `$&`, `` $` ``, `$'`, `$1` through `$99`
substitutions, including two-digit fallback and unmatched captures. Non-callable
replacements are stringified once before searching; callback results per call.
Regex replacement values, callback results, `String(regex)` and array joins use
the shared bounded string conversion and canonical `gims` flag order.

Regex literals use a common-Kotlin UTF-16 matcher, never the host regex engine.
Supported: literals, dot, classes/ranges and negation, `^`/`$`, capturing and `(?:)`
groups, and **one quantifier total**: `*`, `+`, `?` on a character atom, or `?` on a
group, optionally lazy. Escapes include `dDwWsS`, `nrtvf`, class `b`, `xHH`,
`uHHHH`, ASCII-letter control escapes, NUL without following digits, and legacy
identity escapes (including `\A`, excluding `\B`/`\k`). Flags are `gims` only;
`i` deliberately rejects non-ASCII pattern characters and input rather than
relying on platform-dependent Unicode folding. Dot/anchors use all four JS line
terminators. There is no Unicode/code-point mode.

Alternation, backreferences/octal escapes, lookarounds, word boundaries, counted
repeats, repeated groups other than `?`, multiple quantifiers and other flags fail
closed. Malformed patterns also throw uncatchable `UnsupportedPlayerException`.
Patterns are limited to 512 units, 128 instructions, 16 group levels/captures and
65,536 matches. Parsing, candidate searches, backtracking, capture copies and
output growth consume the invocation budgets; even quadratic searches abort at
the step/allocation limits. These restrictions are intentional, not a general JS
regex implementation. Default Error/TypeError/RangeError stringification fails
closed rather than returning a generic object tag; explicit own `toString`
overrides still work.

Verification fixtures and full-player Node/QuickJS oracles are independent of the implementation. [Fixture provenance](fixtures/manifest.json), [verification methodology](docs/verification.md) and [measured benchmarks](docs/benchmark.md) define the evidence and its limits. [InnerTubeX migration](docs/integration.md) covers caller contracts; networking and URL encoding stay outside the core.
