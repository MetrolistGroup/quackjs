# Real-player verification

## Reproduce

From this checkout, use the Gradle wrapper with JDK 21 and Android SDK 37; Node is
needed for the independent JS oracle. Fixtures are checked in, so oracle checks
need no network; dependency/toolchain downloads may still be required.

```sh
# Read-only offline oracle: hashes, fresh contexts and all stored goldens.
node scripts/player-oracle.mjs

# Full library tests, including Kotlin parity, concurrency and QuickJS oracle.
./gradlew --no-daemon --max-workers=2 jvmTest testAndroidHostTest linuxX64Test --console=plain

# Force a fresh JVM run, or run only the original QuickJS oracle.
./gradlew jvmTest --rerun-tasks
./gradlew jvmTest -PoracleOnly --rerun-tasks
```

`-PoracleOnly` excludes the Kotlin full-player parity and benchmark sources; it
is not a full library parity pass. Benchmarks are opt-in, not part of `jvmTest`:
see [commands, timing boundaries and measured results](benchmark.md).

## Opt-in Faraday latest-200 cohort

This larger corpus is independent of the five offline fixtures below. It pins
[MetrolistGroup/faraday](https://github.com/MetrolistGroup/faraday/tree/882624d59ccd921aa4cbf528e046ae61e137be47)
commit `882624d59ccd921aa4cbf528e046ae61e137be47`. All 438 registry players are
validated and have release records. Select the **200 distinct player hashes** by
release `generatedAt` descending, then player hash ascending (ASCII) on ties.
`generatedAt` records successful derivation/validation and may be newer than
`firstSeenAt`; neither config insertion order nor lexical hash order establishes
recency. The endpoints are `3f99f1e0` (`2026-10-22T18:07:26.380Z`) and
`9c9b2502` (`2026-08-31T13:07:22.286Z`).

[`fixtures/faraday-200/manifest.json`](../fixtures/faraday-200/manifest.json)
pins exact URLs, source SHA-256, release times, signature expressions, n wrappers,
timestamps and primary config hashes (including alias resolution). Provenance
includes the complete registry/config file hashes, each selected release file
hash and a digest of **all 438** release file hashes. The public git-object audit
checks registry/release URL/hash agreement and unique primary-or-alias hints.
It never reads the Faraday working tree, `.env`, cookies or playback credentials.
All 200 selected URLs are the original `player_ias.vflset/en_GB/base.js` variant.

### Ordinary read-only verification

Node 26 and JDK 21 were used for the recorded run. No additional dependencies are
introduced. Only the explicit download command needs public player HTTP access;
Gradle may still need its normal toolchain/dependency downloads.

```sh
node --test scripts/faraday-200.test.mjs
# Optional provenance audit against public objects in an existing Faraday clone:
node scripts/faraday-200.mjs provenance --faraday=/path/to/faraday

# Four downloads at a time, 90s timeout per attempt, at most three attempts,
# 100,000-byte minimum and 8 MiB maximum. Every cached/downloaded file is hashed.
node scripts/faraday-200.mjs download
# Read-only: execute every full original source twice in fresh Node VM contexts.
node scripts/faraday-200.mjs oracle
# Offline inputs: compile and compare with the independently derived stored outputs.
./gradlew --no-daemon --max-workers=2 faraday200Verification --console=plain
# Opt-in cross-engine check: full original sources in QuickJS, no Kotlin transforms.
./gradlew --no-daemon --max-workers=2 faraday200OracleVerification --console=plain
```

The ~593 MB source cache is ignored at `build/faraday-200/sources`; it is not
200 tracked JS fixtures. Keep it outside a directory removed by `gradlew clean`
if desired: pass the same absolute directory to Node `--cache=/path/to/cache`
and Gradle `-PfaradayCache=/path/to/cache`. Node reports default to
`<cache>/download-report.json` and `<cache>/oracle-report.json`; override with
`--report=/path/to/report.json`. The JVM reports default to
`<cache>/jvm-report.json` and `<cache>/quickjs-report.json`; override each invocation
with `-PfaradayReport=/path/to/report.json` (use different paths for the two tasks).

The JVM entrypoint loads **one source at a time**, checks its size/hash and
compiles it separately with hints and without hints. Each successful compilation
compares all 16 signature + 15 n outputs in forward/reverse/forward order on the
same program. The first eight successful **distinct primary configs per mode**
also run four simultaneous workers, each checking all 31 cases twice. Source, oracle,
compile and parity failures are recorded per player/mode; hint-free landmark
ambiguity has its own category and never counts as success. Every player gets a
status, the report is updated after each, and any failed mode makes the task
exit nonzero **after** the complete cohort. JVM/OS termination cannot guarantee
a complete report; check `completed == 200`. Default `jvmTest` neither downloads
nor executes this corpus.

`faraday200OracleVerification` reuses that streaming source/hash/golden loader and
the existing test-only `RealPlayer` exports and `QuickJsPlayer` limits. For each
player it initializes **two fresh full-source contexts**: the first compares a
compiled 31-case batch twice and reversed inputs; the second repeats all 31
forward. That is 124 exact output comparisons per player, **24,800 total**.
Initialization, missing/non-string/unequal outputs, thrown evaluations and disposal
failures cannot pass; each player records contexts, comparisons and errors. This
also checks same-engine reuse without duplicating player transformation logic.
It has the same exhaustive reporting/nonzero-exit rules as the Kotlin task.

The shared full-player oracle injects only the registry signature export and the
**original URL-wrapper n export** before the unique `})(_yt_player);` trailer,
using the existing `oracle-globals.js`. Shared deterministic inputs are identical
to the offline corpus: base64url/reversed/repeated/alternating/seeded boundary
lengths, including 86-character signatures and the Faraday n probe. It rejects
thrown, missing, non-string, unchanged or invalid/sentinel n results. There are
**6,200 independently derived stored outputs**, with source, manifest and globals
hashes binding their provenance. No Kotlin output, extracted transform, custom
replacement or catch-return-input path generates an expectation. Fresh contexts
must agree before regeneration succeeds. Each globals, full-player and transform
batch evaluation has a 30s timeout. **Node VM is not a security sandbox**; use
only the pinned public corpus in an appropriately isolated environment.

### Intentional metadata/golden regeneration

Ordinary verification never rewrites goldens. These commands are explicit
maintenance operations, not fixes for a failed download/oracle/parity check:

```sh
node scripts/faraday-200.mjs manifest --faraday=/path/to/faraday
node scripts/faraday-200.mjs oracle --write
```

Manifest reconstruction reads only the pinned commit and validates its known
registry/config hashes, count and cohort endpoints. Golden regeneration replaces
the file only after **all 200** sources and fresh-context oracle checks succeed.
A missing URL, wrong hash or failing oracle is a failure: do not change variants,
substitute another player, refresh to a later snapshot or accept new bytes.
After any production runtime change, rerun the ordinary commands against the
**unchanged** manifest/goldens; reuse the source cache. There is no expected-fail
allowlist or flag that turns known incompatibilities into passes.

### Historical baseline, 2026-10-22

Runtime baseline `1cd3770` includes the regex-after-`else` lexer fix but not the
subsequent n-transform compatibility fix. The actual network/oracle/JVM run gave:

| Check | Passed | Failed |
| --- | ---: | ---: |
| Exact downloads and SHA-256 | 200 | 0 |
| Full-source Node golden generation, two fresh contexts | 200 | 0 |
| Read-only Node golden verification, two fresh contexts | 200 | 0 |
| JVM hinted compile + all outputs/repeats | 176 | 24 |
| JVM hint-free compile + all outputs/repeats | 176 | 24 |

There were **no unavailable downloads**, no oracle failures, no parity failures
among compiled programs, and no hint-free landmark ambiguities. All 24 failures
in **both** modes were `UnsupportedPlayerException: Cannot isolate n transform:
Implicit array coercion is unsupported`, at compilation, for:

```text
0ed38f41 8f9b066a b6eda4df 52726943 e29a2e88 40dba4d7
24c7e547 6e539070 99afa6f1 adbaabd3 a9596022 73252d67
3dc7ccf6 f2999a12 c4fd1fc7 93572bf8 1f293754 92b29aca
ccb8b03c 7460dd14 b9553db2 48ca10b5 82f45653 12502845
```

The JVM task correctly failed, with all 200 statuses retained. Each mode checked
16,368 sequential outputs and passed concurrent reuse on eight successful
players with distinct primary configs (1,984 additional comparisons per mode).
These were runtime incompatibilities, not accepted verifier exceptions. That
baseline established **176/200 in both modes**, not 200/200 Kotlin compatibility.

Local exhaustive evidence is retained under `/tmp/quackjs-integration/faraday-200/`:
`download-report.json`, `oracle-generation-report.json`,
`oracle-readonly-report.json`, `jvm-baseline-report.json`, and their logs. These
local reports/cache are not shipped; the pinned manifest and goldens are tracked.
The five Node self-checks cover ordering/ties, primary/alias uniqueness,
registry/release provenance disagreement, source bounds/hash failures, oracle
failure rejection and exhaustive missing-source reporting without rewriting
goldens. A separate JVM negative check against an empty source cache reported
all 200 as failures in both modes and exited nonzero (`missing-source-report.json`).
All 117 default JVM tests also passed separately.

This is transform parity for synthetic valid challenges on this exact snapshot,
not live playback, all input semantics, general JS support or future-player
compatibility. The cohort has 200 distinct source SHA-256 values and 143 primary configs;
recency/distinctness refer to Faraday player hashes, not 200 unique algorithms.
No QuickJS cross-engine pass was run on that historical baseline. Production
remains common Kotlin + stdlib only; Node and existing JVM test dependencies are
verification tools, never runtime dependencies.

### Fixed runtime and cross-engine rerun, 2026-10-22

Fresh local run against source base `67d2c396abbcfb638cd86d594df2a2125f067462`,
after the implicit-array coercion and bounded replacement fixes, with **unchanged
manifest, shared inputs and goldens**:

| Check | Passed | Failed |
| --- | ---: | ---: |
| Cached source bounds + SHA-256 (593,469,875 bytes) | 200 | 0 |
| Full-source Node, read-only, two fresh contexts | 200 | 0 |
| JVM hinted compile + exact outputs/repeats/concurrency | 200 | 0 |
| JVM hint-free compile + exact outputs/repeats/concurrency | 200 | 0 |
| Full-source QuickJS vs Node goldens, two fresh contexts | 200 | 0 |

Each Kotlin mode made **18,600 sequential comparisons** (200 × 31 × 3 passes),
plus **1,984 concurrent comparisons** (8 distinct primary configs × 4 workers ×
2 passes × 31). Node checked 12,400 outputs; QuickJS checked **24,800 outputs in
400 full-source contexts**. Reports have all 200 statuses, with zero source,
oracle, compile, parity or landmark failures. This supersedes the historical
176/200 runtime result; there is no accepted-failure list.

Evidence is local at `/tmp/quackjs-cross-engine-67d2c39/`: `download-report.json`,
`node-report.json`, `jvm-report.json`, `quickjs-report.json` and corresponding
logs/exit files. The cache was reused from
`/mnt/work/projects/quackjs.worktrees/mvk06juv-472fba-1-faraday-200-verification/build/faraday-200`;
all 200 downloads were cache hits, not fresh network availability checks.
Node v26.11.1 and Temurin 21.0.12.1 were used. The five Node self-checks,
six-player read-only oracle and **149 JVM, 134 Android-host, 134 Linux** tests
passed (no skips). New JVM negatives reject initialization errors and missing,
null, thrown, unchanged, sentinel or mismatched oracle results.

An empty-cache QuickJS run exited **1**, reporting **0 passed / 200 source
failures**, not skips (`missing-source-report.json`). Reproduce with
`./gradlew faraday200OracleVerification -PfaradayCache="$(mktemp -d)"`;
expect a nonzero exit and verify `completed == failed == 200` in the printed
report path. Existing Node self-checks cover source corruption/hash bounds and
missing-source exhaustion without golden writes. The runtime remains the bounded
[dispatcher/replacement subset](../DESIGN.md), including rejection of regex
backreferences, lookarounds and multiple quantifiers. These are synthetic
transform comparisons on pinned sources, not live playback or future-JS support.

## CI

[Verification workflow](../.github/workflows/verification.yml) runs the five Node
self-checks, six-player read-only oracle and JVM/Android-host/Linux suites on
ordinary pushes/PRs, with JDK 21, Node 26 and Android SDK 37. It has read-only
GitHub permissions, no playback credentials and no large-corpus download.

Manually dispatch with `faraday200: true` for bounded downloads, read-only Node,
both Kotlin modes and QuickJS200. Only `.cache/faraday-200/sources` is cached,
keyed by the exact manifest hash, with no fallback key. Every restored source is
rehashed. JSON reports from `build/faraday-reports/*.json` are uploaded even on
failure; failed checks stay failed. Concurrency cancellation, 45/90-minute job
timeouts and 7/14-day artifact retention bound the runs. Local `actionlint`
validation passed; **the workflow itself has not been executed on GitHub**.
No branch-protection settings were changed.

## Corpus and provenance

[`fixtures/manifest.json`](../fixtures/manifest.json) pins each complete,
unmodified `base.js` by HTTPS URL, byte count, SHA-256 and registry hints. Upstream
[notices](../THIRD_PARTY_NOTICES.md) are retained. Original capture date: 2026-10-20 UTC; the `55de7e60` regression fixture was added on 2026-10-22 UTC.
The manifest includes public URLs and hashes for both pinned Faraday registries:
commits `1fe788e77ef9c67619fd1d890b55497c3fc61964` (415 entries) and
`060f1916cf5374e794bbad7528de055f47fa85dc` (464 entries).

| Player | Registry at capture | Signature expression | n wrapper | Timestamp |
| --- | --- | --- | --- | --- |
| `4f38b487` | local | `Tl(48,5831,INPUT)` | `W_` | 20602 |
| `10733253` | local | `dS(7,2166,INPUT)` | `np` | 20699 |
| `eb27e5fd` | local, supplied fixture | `Su(11,3932,INPUT)` | `cg` | 20724 |
| `5008be0a` | public, latest entry | `Gl(17,1452,INPUT)` | `Fl` | 20746 |
| `60869856` | public registry | `xD(28,4062,INPUT)` | `n7` | 20690 |
| `55de7e60` | live download, source landmarks | `zl(17,5122,INPUT)` | `Gx` | 20745 |

All six use `player_ias.vflset/en_GB/base.js`; hints are variant-specific. The
corpus contains 17,598,814 bytes of full players, not extracted lookalikes. Node
checks source hashes and requires at least three distinct output vectors per
transform; valid variants may share identical transforms. These six dispatcher/table players do not establish general JavaScript support
or compatibility with all archived/future players. No credentials, cookies,
stream URLs or private challenges are used.

The `55de7e60` fixture reproduces [Metrolist issue 816](https://github.com/MetrolistGroup/metrolist-kmp-issues/issues/816):
its n transform requires `Math.pow`. The regression checks hinted and hint-free
compilation against the full-player oracle. Common numeric cases and a separate
QuickJS comparison cover JS rounding ties, signed zeros, NaN/infinity, coercion,
32-bit multiplication and constants. The runtime also supports `log`, `log2`,
`sqrt`, `sign`, `clz32` and `exp`. `Math.random` remains unsupported: none of the
verified transforms requires it; no deterministic substitute is supplied.

## Independent oracle

[`goldens.json`](../fixtures/goldens.json) contains 16 signature and 15 n cases
per player: **186 independently derived outputs**. Synthetic inputs include the
base64url alphabet/reversal, repeated/alternating characters and xorshift32 seed
`0x51a7c0de`. Signature lengths span 16–255 and n lengths 15–128, including nearby
boundaries and the Faraday `KdrqFlzJXl9EcCwlmEy` probe. The deterministic generator
uses no clock, network or Kotlin results. Very short n inputs are excluded because
the older URL wrapper returns null when the transform removes all characters.

Both oracles execute the **full original player**, injecting only two exports
before its unique `})(_yt_player);` trailer:

- Signature: evaluate the registry expression with the challenge as `INPUT`.
- n: `new g.<nClass>('https://x.googlevideo.com/videoplayback?n=' +
  encodeURIComponent(input), true).get('n')`.

The URL-wrapper route follows Faraday's parser/runtime. The hash-pinned
[`oracle-globals.js`](../fixtures/oracle-globals.js) follows InnerTubeX's YouTube
globals. Neither oracle uses an extracted/reimplemented transform or a
catch-and-return-input/null fallback. Initialization errors, thrown transforms,
missing outputs and n error sentinels fail verification; the familiar probe must
change to a valid base64url result.

Stored values were generated with Node v26.11.1 and cross-checked using
`io.github.dokar3:quickjs-kt-jvm:1.0.15`. Node repeats fresh VM contexts with
30-second evaluation timeouts. QuickJS uses a 192 MiB native allocation limit,
30-second evaluation timeout, dedicated 8 MiB thread and 2 MiB JS stack limit.
Its tests repeat a precompiled batch, reversed challenges and the first pair.
The globals suffice for this corpus, not a browser emulator. **Node VM is not a
security sandbox**; run the pinned corpus, not arbitrary untrusted scripts.

`PlayerParityTest` compiles each full source with `PlayerHints`, checks its
timestamp and all stored outputs in forward/reverse/forward order. Additional
acceptance tests check **140 unseen seeded valid challenges** against full-player
QuickJS, repeated/reversed order and shared-program concurrency. Expected values
come only from the independent engines, never Kotlin. Malformed n comparisons
are diagnostic, not parity assertions: the URL-wrapper boundary differs from the
raw-string API. The golden corpus does not prove malformed-input, Unicode or
resource-limit semantics; dedicated common tests cover those separately.

## Updating or auditing fixtures

Ordinary verification must not rewrite expected outputs. Deliberate regeneration
uses only the original JS:

```sh
node scripts/player-oracle.mjs --write
```

Audit a live download without overwriting a tracked fixture:

```sh
set -eu
scratch=$(mktemp -d)
curl -fL --max-time 90 \
  https://www.youtube.com/s/player/4f38b487/player_ias.vflset/en_GB/base.js \
  -o "$scratch/4f38b487.js"
sha256sum "$scratch/4f38b487.js"
# Expected: 2d440d1c8fab8b79cc602a354cdbd3b52595478c69eb3ce0f795342128f6128a
cmp fixtures/4f38b487.js "$scratch/4f38b487.js"
rm -r "$scratch"
```

A live URL can disappear or change. A hash mismatch is a failure, not permission
to silently regenerate goldens. Review provenance and oracle changes together
when intentionally rotating the corpus.

## Publication checks

Build with JDK 21 and Android SDK 37. The checker validates all six KMP
publications, target links, artifact hashes, stdlib-only dependencies and Java 17
bytecode without JS/JNI payloads:

```sh
./gradlew publishToMavenLocal
python3 scripts/verify-publication.py \
  "$HOME/.m2/repository/com/github/MetrolistGroup/quackjs" 0.1.0
```

JitPack runs the checked-in `jitpack.yml` and uses its tag/commit as
`-PVERSION_NAME`. Use `com.github.MetrolistGroup.quackjs:quackjs:<version>` for the
KMP root, not the repository's aggregate POM. To verify downloaded artifacts,
pass their Maven group directory and version to the same checker. Sources and
LICENSE are attached as `-sources.jar` for every publication. Source variants are
omitted from Gradle metadata because JitPack rewrites their classifier URLs
incorrectly; the Maven source attachments remain downloadable. Native metadata
also has an unclassified JAR alias so JitPack's rewritten URLs retain the same
bytes and hashes.

Local publication checks do not prove hosted availability. Before adoption, check
JitPack metadata and resolve each required platform variant in a fresh external
consumer without Maven Local or source substitution; inspect production graphs
and artifacts for unwanted JS/native payloads. See [migration guidance](integration.md).

### Release 0.1.0

On 2026-10-21, [JitPack](https://jitpack.io/#MetrolistGroup/quackjs/0.1.0)
reported `ok` for tag `0.1.0` at `733732d`. All six hosted publications were
downloaded and passed the checker above, including source attachments and
native metadata hashes. POMs retain GPL-3.0-only licensing information.

An isolated migrated InnerTubeX checkout resolved the hosted `0.1.0` dependency
without Maven Local, project substitution or vendored QuackJS. Fresh runs passed
382 desktop, 375 Android-host and 29 harness tests; API/style checks and assembly
also passed. Production graphs selected the Android AAR and JVM JAR, with no
QuickJS dependency. This validates the migrated backend, not a dependency change
in the original InnerTubeX repository. Apple compilation is not execution/linking.

## Recorded scope and limits

For core `25e10da`, the recorded combined run passed **111 JVM, 103 Android-host
and 103 Linux Native tests**, plus the read-only Node oracle. Safety coverage
includes mutation/aliasing, evaluation order, retained closures/arguments,
sparse/empty arrays, UTF-16 and resource-budget boundaries. These are historical
results, not a fresh test run or proof of the target hosted release.

Live CDN playback, Android hardware, Apple execution/final linking and release
shrinker/distributables were not checked. Linux-host metadata/source compilation
does not prove Apple binaries link or run. QuackJS is not a BotGuard/PO-token
runtime. Performance evidence is limited to the [measured workloads](benchmark.md).
