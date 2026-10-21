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
}
class UnsupportedPlayerException(message: String) : IllegalArgumentException(message)
```

## Architecture

The runtime lives in commonMain and targets Android, JVM, Linux and iOS. It indexes source and follows only the required transform dependencies rather than initializing the whole browser player. Numeric dispatchers are specialized; parsed code and safe constant templates are reused. Mutable containers and execution budgets remain private to each invocation, so compiled programs support concurrent callers without output caching.

`nClass` is a URL wrapper in the original player. QuackJS discovers the n-table update and executes that dependency slice, rather than emulating browser URL handling. Hint-free compilation requires unambiguous executable landmarks; unsupported or ambiguous source fails explicitly. Optimized plans retain live mutation guards and interpreter fallbacks, not fixture/player-ID assumptions.

Verification fixtures and full-player Node/QuickJS oracles are independent of the implementation. [Fixture provenance](fixtures/manifest.json), [verification methodology](docs/verification.md) and [measured benchmarks](docs/benchmark.md) define the evidence and its limits. [InnerTubeX migration](docs/integration.md) covers caller contracts; networking and URL encoding stay outside the core.
