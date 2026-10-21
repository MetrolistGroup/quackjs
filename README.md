# QuackJS

Kotlin-only YouTube player signature deciphering and `n` transforms. No QuickJS,
JNI, WebView, JavaScript engine, network client, or production dependency beyond
Kotlin stdlib.

Faraday's `sig`, `nClass`, and `sts` hints locate the transform dependencies.
QuackJS indexes the player, isolates the required functions, specializes numeric
dispatchers, and reuses parsed code and constant templates. Each call has its own
mutable state and execution budget. It does not initialize the whole player.

## Use

```kotlin
import meow.nyx.quackjs.PlayerHints
import meow.nyx.quackjs.PlayerProgram

// Download/cache the matching player variant in your existing HTTP layer.
val program = PlayerProgram.compile(
    playerJs,
    PlayerHints(
        signatureExpression = "Gl(17,1452,INPUT)",
        nClass = "Fl",
        signatureTimestamp = 20746,
    ),
) // Example hints are for player 5008be0a, en_GB player_ias.

val signature = program.decipherSignature(encryptedSignature)
val n = program.transformN(nChallenge)
```

Cache the compiled program by **exact player variant and hints**, not just video.
Programs support concurrent calls. Supply decoded challenges; encode outputs when
inserting them into URLs. Do not construct expressions from challenge strings.

`PlayerProgram.compile(playerJs)` can derive unambiguous Faraday-style landmarks
without a registry. `signatureTimestamp` is nullable when not present in source.
Unsupported source, dependencies, operations, or resource limits throw
`UnsupportedPlayerException`. Treat this as a failed decipher, never as permission
to return the original encrypted URL. Compilation and transforms are synchronous;
run CPU work off your UI thread.

## Install locally

```sh
./gradlew publishToMavenLocal
```

```kotlin
repositories { mavenLocal(); mavenCentral() }
// In the consuming KMP commonMain dependencies:
implementation("meow.nyx:quackjs:0.1.0-SNAPSHOT")
```

Published targets: Android (min SDK 23), JVM (Java 17), Linux x64, iOS arm64,
and iOS simulator arm64. Build requires JDK 21 and Android SDK 37.
These coordinates are **local artifacts, not a hosted release**.

## InnerTubeX and Metrolist-KMP

[Integration instructions](docs/integration.md) include complete QuickJS-free
InnerTubeX and companion Metrolist patches, exact baseline manifests, publication
checks, and a published-artifact consumer test. The migration was built and tested
in isolated copies; your original repositories were not changed.

InnerTubeX's patch includes a byte-identical source module so its default builds
work without assuming a nonexistent remote QuackJS release. Both modules publish
together. Metrolist selects that local publication through its existing Gradle
properties. Its unchanged default remote dependency still uses QuickJS until you
intentionally migrate the pin. Existing user changes must be reconciled before
applying the snapshot-specific patches.

## Verified scope and speed

- Four complete real players, including `5008be0a`, with **124 independent
  Node/QuickJS golden cases**, hinted and hint-free consumer tests, and concurrency
  tests. This is a dispatcher/table-player subset, **not general JavaScript** or
  a guarantee for every historical/future player.
- 111 JVM, 103 Android-host and 103 Linux Native library tests pass, including
  112 additional unseen challenges checked against QuickJS.
- Migrated InnerTubeX: 382 desktop, 375 Android-host and 29 harness tests pass.
  Metrolist shared tests, Android debug APK and desktop assembly pass.
- After the [32-lane optimization round](docs/performance-lanes/summary.md), two
  JVM runs show **2.3–2.5× faster cold preparation** and **20–27% lower warm-batch
  latency than QuickJS** on all four players. Warm latency is 13–21% lower than
  the previous core; measured JVM allocation falls 56% for signatures and 41%
  for n transforms. These are corpus-specific results, not a universal guarantee.

See [verification and benchmark methodology](docs/verification.md) and
[measured results](docs/benchmark.md). QuickJS is a test-only oracle dependency.

```sh
./gradlew jvmTest testAndroidHostTest linuxX64Test
node scripts/player-oracle.mjs
./gradlew playerBenchmark --console=plain
./gradlew lane32AcceptanceBenchmark --console=plain
```

Limits include 8 Mi UTF-16 source units, 16,384-unit challenges, 65,536-unit output
values, and bounded parser/dependency/execution/allocation budgets. These are
fail-closed limits, not a hard wall-clock deadline. Source and test fixture
provenance is recorded in `fixtures/manifest.json`; upstream fixture notices are
retained.

Not verified: live CDN playback, Android devices, Apple execution/linking, or
release packaging. No claim is made that this replaces BotGuard/PO-token execution.
