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

## Corpus and provenance

[`fixtures/manifest.json`](../fixtures/manifest.json) pins each complete,
unmodified `base.js` by HTTPS URL, byte count, SHA-256 and registry hints. Upstream
[notices](../THIRD_PARTY_NOTICES.md) are retained. Capture date: 2026-10-20 UTC.
The manifest includes public URLs and hashes for both pinned Faraday registries:
commits `1fe788e77ef9c67619fd1d890b55497c3fc61964` (415 entries) and
`060f1916cf5374e794bbad7528de055f47fa85dc` (464 entries).

| Player | Registry at capture | Signature expression | n wrapper | Timestamp |
| --- | --- | --- | --- | --- |
| `4f38b487` | local | `Tl(48,5831,INPUT)` | `W_` | 20602 |
| `10733253` | local | `dS(7,2166,INPUT)` | `np` | 20699 |
| `eb27e5fd` | local, supplied fixture | `Su(11,3932,INPUT)` | `cg` | 20724 |
| `5008be0a` | public, latest entry | `Gl(17,1452,INPUT)` | `Fl` | 20746 |

All four use `player_ias.vflset/en_GB/base.js`; hints are variant-specific. The
corpus is about 11.7 MB of full players, not extracted lookalikes. Node checks
that each player's signature-output and n-output vectors differ from the others.
These four dispatcher/table players do not establish general JavaScript support
or compatibility with all archived/future players. No credentials, cookies,
stream URLs or private challenges are used.

## Independent oracle

[`goldens.json`](../fixtures/goldens.json) contains 16 signature and 15 n cases
per player: **124 independently derived outputs**. Synthetic inputs include the
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
acceptance tests check **112 unseen seeded valid challenges** against full-player
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
