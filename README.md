# QuackJS

Kotlin-only YouTube player signature deciphering and `n` transforms. Common
Kotlin and stdlib only: no QuickJS, JNI, WebView, network client, or JS engine.
Source: [MetrolistGroup/quackjs](https://github.com/MetrolistGroup/quackjs).

## Install with JitPack

Target release: **0.1.0**. Hosted artifacts and the coordinate below are not yet
verified; confirm the release's JitPack metadata before adopting it.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
// KMP commonMain dependencies:
implementation("com.github.MetrolistGroup:quackjs:0.1.0")
```

Targets: Android (min SDK 23), JVM (Java 17), Linux x64, iOS arm64 and iOS simulator
arm64. See [InnerTubeX migration](docs/integration.md) for consumer guidance.

## Use

```kotlin
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

// Hints for player 5008be0a, en_GB player_ias; fetch/cache JS in your HTTP layer.
val program = PlayerProgram.compile(
    playerJs, PlayerHints("Gl(17,1452,INPUT)", "Fl", 20746),
)
val signature = program.decipherSignature(encryptedSignature)
val n = program.transformN(nChallenge)
```

Cache by exact player variant and hints. Programs support concurrent calls.
`compile(playerJs)` can discover unambiguous landmarks without hints;
`signatureTimestamp` is nullable. Pass decoded challenges and query-encode outputs
once. Never interpolate challenges into expressions. Compilation and transforms
are synchronous; move CPU work off the UI thread.

## Scope and limits

This is a bounded dispatcher/table-player subset, **not general JavaScript** or
BotGuard/PO-token execution. Unsupported source, operations and exhausted budgets
throw `UnsupportedPlayerException`; fail closed, never return an encrypted URL as
if deciphering succeeded. Limits include 8 Mi UTF-16 source units, 16,384-unit
challenges and 65,536-unit outputs, not a hard wall-clock deadline.

Verification covers four complete players, not every historical/future player.
Live playback, Android hardware and Apple execution/final linking are unverified.
See [verification](docs/verification.md), [measured benchmarks](docs/benchmark.md),
[architecture](DESIGN.md) and [fixture provenance](fixtures/manifest.json).

Licensed under [GPL-3.0-only](LICENSE); retain [third-party notices](THIRD_PARTY_NOTICES.md).
