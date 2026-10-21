# Real-player verification

## Corpus and provenance

`fixtures/manifest.json` pins each complete, unmodified `base.js` by HTTPS URL,
byte count, SHA-256, and registry hints. Source license notices are retained.
The three local-registry players span different dispatcher constants and n
wrappers; the fourth comes from the latest public registry entry at capture.

| Player | Registry | Signature expression | n wrapper | Timestamp |
| --- | --- | --- | --- | --- |
| `4f38b487` | local | `Tl(48,5831,INPUT)` | `W_` | 20602 |
| `10733253` | local | `dS(7,2166,INPUT)` | `np` | 20699 |
| `eb27e5fd` | local, supplied fixture | `Su(11,3932,INPUT)` | `cg` | 20724 |
| `5008be0a` | public, latest entry at capture | `Gl(17,1452,INPUT)` | `Fl` | 20746 |

Capture date: 2026-10-20 UTC. The local Faraday registry was read from
`/mnt/work/projects/faraday/registry/player_configs.json`, commit
`1fe788e77ef9c67619fd1d890b55497c3fc61964` (415 entries). The public
`MetrolistGroup/faraday` **master** registry was fetched at commit
`060f1916cf5374e794bbad7528de055f47fa85dc` (464 entries). Both pinned public
registry downloads were checked against the hashes in the manifest. No
credentials, cookies, stream URLs, or private challenge material were used.
Player URLs use `player_ias.vflset/en_GB/base.js`; hints are variant-specific.

The corpus is about 11.7 MB, four full players rather than extracted lookalike
functions. Node checks that each player's entire signature-output vector and
n-output vector differ from the other players', not just their variable names.
Do not add aliased copies as extra coverage.

## Independent oracle

```sh
# Offline; requires Node. Checks hashes, repeats fresh contexts, compares goldens.
node scripts/player-oracle.mjs

# Deliberate regeneration using the complete original JS, never PlayerProgram.
node scripts/player-oracle.mjs --write

# Full Kotlin parity plus the original QuickJS oracle.
./gradlew jvmTest --rerun-tasks

# Force the independent native QuickJS cross-check even after core integration.
./gradlew jvmTest -PoracleOnly --rerun-tasks
```

`fixtures/goldens.json` records 16 signature and 15 n cases per player: **124
independently derived outputs**. Inputs are public synthetic challenges:
base64url alphabet/reversal, repeated/alternating characters, and xorshift32
seed `0x51a7c0de`. Signature lengths span 16–255, n lengths 15–128, with nearby
length boundaries and the Faraday `KdrqFlzJXl9EcCwlmEy` probe. The generator
uses fixed ordering and no clock, network, or Kotlin code. Very short n inputs
were deliberately excluded: the original older URL wrapper returns null when
its transform removes all characters. This corpus checks valid cipher inputs,
not the public API's malformed-input policy, Unicode semantics, or limits.

The oracle runs each **full original player**, injecting only two exports before
its unique `})(_yt_player);` trailer:

- Signature: evaluate the registry expression with the challenge as `INPUT`.
- n: `new g.<nClass>('https://x.googlevideo.com/videoplayback?n=' +
  encodeURIComponent(input), true).get('n')`.

The URL-wrapper route matches Faraday's `player-config-parser.ts` and
`cipher-process.ts`; the globals in `fixtures/oracle-globals.js` follow
InnerTubeX's `QuickJsEngine.setupYoutubeGlobals`. There is no extracted or
reimplemented transform in either oracle, and no catch-and-return-input/null
fallback. JS initialization errors, thrown transforms, missing outputs, and n
error sentinels fail verification. The familiar probe must change to a valid
base64url result. All expected values were generated with Node v26.11.1, then
cross-checked using `io.github.dokar3:quickjs-kt-jvm:1.0.15`.

Node executes twice in fresh VM contexts with 30-second evaluation timeouts.
QuickJS executes the full player with a 192 MiB native allocation limit,
30-second evaluation timeout, and a dedicated thread (8 MiB stack, 2 MiB
QuickJS stack limit). Its tests check a precompiled batch twice, reversed
challenges, and the first signature+n pair. The globals are SHA-256-pinned in
the goldens. These browser shims are sufficient for these players, not a
browser emulator. Node's VM is not a security sandbox; run only the pinned
corpus, not arbitrary untrusted scripts.

`PlayerParityTest` targets the `DESIGN.md` public API, compiles each original
source with its `PlayerHints`, checks `signatureTimestamp`, and compares every
output while reusing the program in forward/reverse/forward order. It never
uses Kotlin results to generate goldens. The separate published InnerTubeX
consumer also checks all four players with and without hints. Concurrency tests
exercise shared compiled programs. `-PoracleOnly` explicitly excludes the parity
and benchmark sources; that opt-in mode is not a library parity pass.

## Re-fetching a fixture

Files are checked in, so ordinary verification requires no network. To audit
a download without overwriting a tracked fixture, for example:

```sh
curl -fL --max-time 90 \
  https://www.youtube.com/s/player/4f38b487/player_ias.vflset/en_GB/base.js \
  -o /tmp/4f38b487.js
sha256sum /tmp/4f38b487.js
# Expected: 2d440d1c8fab8b79cc602a354cdbd3b52595478c69eb3ce0f795342128f6128a
cmp fixtures/4f38b487.js /tmp/4f38b487.js
```

Other URLs, hashes, pinned registry URLs, and registry hashes are in the
manifest. A live URL can disappear or change; a mismatch is a failure, not a
reason to silently regenerate expected values. Update provenance and inspect
oracle changes together when intentionally rotating the corpus.

## Opt-in comparison benchmark

```sh
# Requires the real core implementation. Not part of check/jvmTest.
./gradlew playerBenchmark --console=plain | tee /tmp/quackjs-benchmark.txt
```

The task uses only JVM test sources/dependencies and rejects `-PoracleOnly`.
Before printing any measurements it checks all original-player
goldens against both implementations. Every timed sample's complete output is
also compared outside the timer; mismatches abort the run, not get skipped.
There are no performance thresholds or unmeasured speed claims.

- **Cold program:** source text is already in memory. Fresh Kotlin
  `PlayerProgram.compile` with hints, then first signature (length 86) and n
  (length 19). QuickJS gets a fresh runtime/thread, globals, complete-player
  compile/evaluation and the same first pair. Native library loading and JVM
  process startup are outside this measure. Both exclude disposal. Three
  discarded warmups, **11 samples per implementation per player**.
- **Warm batch:** one compiled Kotlin program versus one initialized full
  QuickJS player. Same ordered set of 31 cases repeated 16 times (**496
  transforms/sample**). QuickJS's input array and batch bridge are installed
  and compiled to bytecode outside the timer; samples execute that bytecode,
  never reparse the player or a per-input eval string. This is a batched
  steady-state baseline favorable to QuickJS, rather than artificially
  charging full compilation on every call. Twenty discarded warmups,
  **31 samples per implementation per player**.
- Both warm paths include result-array construction and JSON serialization;
  QuickJS also includes one dispatcher/JNI round trip and result marshalling
  per batch. No input transfer or result equality check is timed. Cold paths
  include their first-call input setup. Setup and fixture I/O are excluded
  except where explicitly included in the cold measure.
- Implementations alternate first/second order on every paired sample.
  Results report median milliseconds, sample counts, batch size, JVM version,
  vendor/flags/heap, OS/architecture, available CPU count, and QuickJS settings.
  Use several separate JVM runs on an otherwise idle machine and record CPU
  model (`lscpu`), power policy, and commit with any published results. This
  small harness does not measure process startup, Android, native Kotlin,
  concurrent load, energy, or retained memory; GC and host scheduling remain
  possible sources of noise. There is no forced GC between samples.

## Historical checks performed before core integration

Environment: Node v26.11.1; Temurin OpenJDK 21.0.12.1+1-LTS; Linux
7.2.11-1-cachyos x86_64; AMD Ryzen 9 7950X3D, 32 available logical CPUs.

| Check | Result |
| --- | --- |
| `node scripts/player-oracle.mjs --write`, then read-only check | Passed: 4 players × 31 cases, repeated fresh contexts, all source/global hashes |
| `./gradlew jvmTest -PoracleOnly --rerun-tasks` | Passed: original full-player QuickJS output equals independent Node goldens |
| `./gradlew dependencies --configuration jvmRuntimeClasspath` | Only Kotlin stdlib/annotations; no QuickJS runtime dependency |
| `./gradlew playerBenchmark --dry-run` | Task configuration/dependencies resolved; not an execution or timing result |
| `./gradlew jvmTest --rerun-tasks` | Passed in announced oracle-only mode: core-dependent files excluded while commonMain has no Kotlin sources |
| `./gradlew playerBenchmark` | Correctly rejects missing commonMain core with an explicit integration error |
| Full Kotlin-vs-QuickJS benchmark | Not run: separate core lane has not supplied `PlayerProgram`/`PlayerHints`; **no comparative timing numbers available** |

These are historical lane results, not the final state. No production API stubs
were added.

## Final combined verification

After selecting ten implementation patches from the [32-lane experiment](performance-lanes/summary.md), final core `25e10da`:

- `./gradlew --no-daemon --max-workers=2 jvmTest testAndroidHostTest linuxX64Test --console=plain`: passed, **111 JVM, 103 Android-host and 103 Linux tests**, no skips/failures. Log `/tmp/quackjs-perf32/combined-tests.log`.
- `node scripts/player-oracle.mjs`: passed, all 124 existing goldens, hashes and fresh-context repetitions. No fixture expectations were changed.
- The new JVM acceptance test also checks **112 unseen seeded valid challenges** against full-player QuickJS, repeated/reverse order, and shared-program concurrency. Malformed n comparisons remain diagnostic because the oracle uses a URL wrapper; they are not reported as parity passes.
- `./gradlew --no-daemon --max-workers=2 assemble publishToMavenLocal --console=plain` and `python3 integrations/verify-quackjs-publication.py`: passed, six stdlib-only KMP publications; 72 Java-17 classes in JVM/Android artifacts, no JS/native payload. Log `/tmp/quackjs-perf32/combined-publication.log`.
- Two complete original benchmark executions plus the per-kind timing/allocation harness passed all output checks. The unchanged core was benchmarked before and after the candidate runs. [Results](benchmark.md) record both versions and preserve the older warm regression as historical evidence.
- The opt-in optimized Native extract workload passed twice; its timing differences do not establish a reliable Native speedup.
- A read-only review of the combined patch against `732ee13` found no actionable regressions. Log `/tmp/quackjs-perf32/combined-review.log`.
- The exact final **seven-file core** was synchronized into the isolated InnerTubeX module and verified by SHA-256. The public Kotlin API and InnerTubeX API stayed unchanged; existing API checks pass without a new baseline change in this round.
- InnerTubeX: 382 desktop, 375 Android-host and 29 harness tests, formatting, API checks, assembly and local publication passed; production graph contains no QuickJS. Log `/tmp/quackjs-perf32/combined-innertubex.log`.
- Published-artifact consumer passed all four players with and without hints (`/tmp/quackjs-perf32/combined-consumer.log`).
- Metrolist: 1,827 shared desktop tests (four existing opt-in skips), 1,226 Android-host tests and 52 server tests passed; Android debug APK and desktop assembly passed against the final local publication. Selected runtime graph and APK/DEX checks show no QuickJS/EJS payload. Log `/tmp/quackjs-perf32/combined-metrolist.log`.
- The regenerated InnerTubeX patch was applied to a fresh verified baseline copy; its staged tree exactly matched the tested migration. Proof digests are in `integrations/application-results.json`.

All heavy builds/benchmarks used the shared validation lock. Original implementation
logs remain under `/tmp/quackjs-combined-*.log`; this round's logs are under
`/tmp/quackjs-perf32/`. Machine-readable final counts and limitations are in
[`integrations/final-verification.json`](../integrations/final-verification.json).

See [integration instructions](integration.md) for exact consumer commands and
snapshot safety. These builds are not live CDN/device tests. Apple sources and
metadata compile on this host; simulator execution and final Apple linking were
not checked.
