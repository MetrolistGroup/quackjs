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

Shared baseline Gradle targets JVM (Android compatible JVM bytecode) and native Linux/iOS. Runtime implementation belongs in commonMain. Verification fixtures and JVM oracle/benchmark are independent of implementation.

Local reference repositories, read only:
- `/mnt/work/projects/faraday/src/zemer/player-config-deriver.ts`: landmark hints.
- `/mnt/work/projects/faraday/registry/player_configs.json`: real configs.
- `/mnt/work/projects/innertubex/src/commonMain/kotlin/com/metrolist/innertubex/cipher/`: consumer contracts, QuickJS solver and globals.
- `/mnt/work/projects/faraday/src/zemer/cipher-runtime.ts`: JS oracle globals.

Real fixture `fixtures/eb27e5fd.js` downloaded from https://www.youtube.com/s/player/eb27e5fd/player_ias.vflset/en_GB/base.js. Hints: Su(11,3932,INPUT), cg, 20724. Player uses constant string table km, branched arithmetic dispatcher functions, helper object c5. nClass wraps URL parsing; slice down to actual n transform rather than emulate browser.

No push/PR. Conventional commits and branches. Never add UI tests. No secrets/credentials from sibling repos. Do not claim readiness or speed without real-player parity and measurements.
