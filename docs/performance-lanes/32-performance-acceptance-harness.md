# Lane 32: independent performance acceptance harness

## Scope and recommendation

Hypothesis: a mixed signature+n batch hides which transform dominates warm time
and allocation. Add evidence and independent correctness coverage, not a runtime
optimization. Recommend integrating the verification tooling, **not** treating
this single noisy JVM run as a speedup or a malformed-n parity pass.

Baseline checkout: `732ee13` (lane instructions over the unchanged `4d74080`
core baseline). Exact touched paths:

- `src/jvmTest/kotlin/meow/nyx/quackjs/verification/Lane32AcceptanceTest.kt`
- `src/jvmTest/kotlin/meow/nyx/quackjs/verification/Lane32AcceptanceBenchmark.kt`
- `build.gradle.kts`: one new opt-in `lane32AcceptanceBenchmark` JavaExec task;
  exclude the two new core-dependent files in existing `oracleOnly` mode.
- This report.

No production, existing benchmark, fixture, oracle, consumer or dependency edits.
Runtime remains common Kotlin/stdlib only. Only the small Gradle addition may
conflict with other verification lanes; both new source filenames are lane-owned.

## Exact methodology

- Load the four hash-pinned full players through existing `PlayerFixtures`.
  Compile with manifest hints; assert the original Node goldens against both
  Kotlin and the full original QuickJS player before accepting new challenges.
- Generate **28 unseen inputs/player**: signature and n, lengths
  `16, 19, 32, 64, 86, 128, 256`, two distinct base64url strings per cell.
  `java.util.Random(0x32acc3e7L)` has a specified deterministic sequence, separate
  from the stored oracle's xorshift seed. Assert repeat generation, uniqueness,
  and no input overlap with the player's checked-in cases.
- Expected values are produced **only by full-player QuickJS**, held in memory,
  never written as goldens. Kotlin never generates an expected value. Check the
  oracle again in reverse order, Kotlin forward/reverse/forward, then the same
  compiled Kotlin program from four barrier-started workers, three rotated
  passes each (336 concurrent calls/player). Futures propagate errors; bounded
  waits and executor cleanup prevent silent worker failures.
- Check six malformed cases per kind separately: empty, one character,
  whitespace, URL punctuation, controls and BMP Unicode. Malformed signatures
  are parity assertions. Malformed n comparisons are explicitly **diagnostic**:
  the existing oracle uses `new g.<nClass>(URL + encodeURIComponent(input), true)`
  and may return null, unlike Kotlin's direct string API. Print both actual
  results and match status; do not time or mix these into valid-input evidence.
  Also assert both Kotlin APIs reject 16,385-unit inputs. Do not infer a new
  malformed-input contract or change production to match a different boundary.
- The separate main repeats all correctness/concurrency checks before timing.
  For each player/kind/length, repeat its two inputs eight times: **16
  transforms/sample**. Ten discarded paired warmups, 15 measured paired samples;
  alternate Kotlin/QuickJS execution order. Report median batch time divided by
  16 in microseconds/operation. These are throughput-derived averages, **not
  individual-call latency percentiles**. No timing/allocation thresholds.
- Both warm regions build result arrays and serialize JSON. Kotlin calls the
  public APIs; QuickJS runs the existing precompiled batch bridge. Player
  compilation, fixture reads, input transfer/bridge compilation and complete
  output equality checks are outside timing. Every sample is checked afterward;
  a valid-output mismatch aborts rather than contributing a measurement.
- Use test-only `java.lang.management.ManagementFactory` and the optional
  `com.sun.management.ThreadMXBean` extension. Enable supported accounting and
  sample the **current Kotlin/JVM thread** immediately outside each timed region.
  Report median bytes divided by 16; result arrays/serialization are included.
  Unsupported, policy-disabled or negative counters print `unsupported`, never
  zero. No QuickJS worker/native allocation, retained/peak heap, GC allocation
  rate or compile allocation claim. Counter reads/equality checks are not timed.
- One fresh benchmark JVM, Temurin 21.0.12.1+1-LTS, Linux
  7.2.11-1-cachyos amd64, Ryzen 9 7950X3D, 32 logical CPUs, `-Xms512m -Xmx2g`.
  Governor observed `powersave`, driver `amd-pstate-epp`; no pinning, policy
  changes or forced GC. Shared-host scheduling/JIT/GC remain confounders.
  QuickJS-KT 1.0.15: 192 MiB native limit, 2 MiB JS stack, dedicated 8 MiB thread,
  30-second evaluation timeout, unchanged from existing verification.

## Commands and outcomes

All builds, benchmarks and Node oracle runs used the mandatory shared lock.
From this worktree, successful main verification commands were:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/32-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/32-benchmark.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 lane32AcceptanceBenchmark --console=plain > /tmp/quackjs-perf32/32-acceptance-benchmark.log 2>&1 &&
  node scripts/player-oracle.mjs > /tmp/quackjs-perf32/32-node-oracle.log 2>&1
'
```

The first attempt asserted raw malformed-n parity and failed at whitespace in
`4f38b487`; preserved in `32-tests-initial.log` and
`32-malformed-initial-failure.xml`. This exposed the boundary limitation above,
not a valid base64url failure. The next attempt passed all 50 tests, but the
existing benchmark received an external `stop command` after two players;
`32-benchmark-stopped.log` is **not** a complete benchmark. Retried the last
three commands under the same lock, producing the complete successful logs
above and `32-validation.exit` = `0`. No processes were killed or stopped by
this lane.

Also ran, under the same lock:

```sh
./gradlew --no-daemon --max-workers=2 jvmTest -PoracleOnly --console=plain
./gradlew --no-daemon --max-workers=2 lane32AcceptanceBenchmark -PoracleOnly --console=plain
./gradlew --no-daemon --max-workers=2 jvmTest --console=plain
```

Logs respectively: `32-oracle-only-tests.log` (45 tests, zero failures/skips),
`32-oracle-only-rejection.log` (**expected nonzero**, explicit requires-core
message verified), `32-tests-final.log` (**50 tests, zero failures/skips**).
All log names here are under `/tmp/quackjs-perf32/`. The final full suite covers
existing golden parity, concurrency, common semantics, unsupported source and
execution/allocation/resource budgets, plus the new test. The read-only Node
oracle passed all 124 existing goldens and fresh-context repetitions. No golden
files were regenerated. `git diff --check` passed.

## Existing benchmark: before versus this lane

Milliseconds, Kotlin / QuickJS. Shared pre-lane runs are
`baseline-benchmark-1.log` and `baseline-benchmark-2.log`. Existing
`PlayerBenchmark.kt` and its boundaries are byte-for-byte unchanged.

| Player | Before cold run 1 | Before cold run 2 | This lane cold | Before warm run 1 | Before warm run 2 | This lane warm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 279.818 | 121.488 / 265.894 | 121.326 / 264.311 | 57.042 / 59.834 | 53.073 / 57.597 | 59.375 / 66.665 |
| 10733253 | 135.474 / 284.865 | 134.351 / 277.667 | 153.588 / 298.356 | 48.456 / 50.129 | 45.793 / 47.569 | 64.856 / 65.333 |
| eb27e5fd | 135.206 / 273.167 | 138.892 / 281.623 | 159.735 / 305.719 | 47.711 / 53.438 | 43.456 / 49.018 | 45.561 / 51.102 |
| 5008be0a | 145.202 / 285.821 | 142.167 / 278.093 | 141.623 / 289.152 | 38.905 / 41.128 | 41.172 / 43.121 | 39.663 / 42.638 |

**No runtime gain established or expected:** production is unchanged. In
particular, both engines slow down on `10733253`, illustrating shared-host noise.

## New per-kind evidence

Microseconds/op, Kotlin / QuickJS; bytes/op are Kotlin caller-thread allocation,
including result serialization. All 56 timing/allocation cells completed, every
sample checked. Allocation accounting was supported and enabled on this JVM.

| Player | Length | Signature us/op K / Q | n us/op K / Q | Signature bytes/op | n bytes/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 16 | 2.958 / 4.869 | 147.091 / 179.772 | 6824.0 | 333768.0 |
| 4f38b487 | 19 | 3.030 / 4.561 | 157.025 / 185.077 | 6987.0 | 341995.0 |
| 4f38b487 | 32 | 3.291 / 5.368 | 187.566 / 202.800 | 8005.0 | 384813.0 |
| 4f38b487 | 64 | 3.778 / 6.606 | 235.836 / 252.307 | 10603.0 | 462379.0 |
| 4f38b487 | 86 | 4.036 / 7.704 | 264.362 / 294.242 | 12540.0 | 514604.0 |
| 4f38b487 | 128 | 4.556 / 8.537 | 353.545 / 386.213 | 15727.0 | 659583.0 |
| 4f38b487 | 256 | 6.939 / 16.662 | 580.597 / 653.942 | 26175.0 | 1033703.0 |
| 10733253 | 16 | 2.489 / 3.451 | 141.604 / 151.916 | 6831.0 | 266923.0 |
| 10733253 | 19 | 2.447 / 3.397 | 140.129 / 146.389 | 6994.0 | 275875.0 |
| 10733253 | 32 | 2.729 / 3.207 | 163.492 / 176.734 | 8012.0 | 314144.0 |
| 10733253 | 64 | 3.463 / 5.258 | 210.960 / 225.316 | 10600.5 | 385196.5 |
| 10733253 | 86 | 3.749 / 5.573 | 240.704 / 263.875 | 12513.0 | 426748.5 |
| 10733253 | 128 | 4.443 / 7.440 | 261.902 / 301.104 | 15700.5 | 490704.5 |
| 10733253 | 256 | 6.909 / 12.823 | 508.149 / 688.446 | 26148.5 | 849680.5 |
| eb27e5fd | 16 | 2.518 / 2.558 | 131.142 / 150.102 | 7167.0 | 247009.0 |
| eb27e5fd | 19 | 2.535 / 2.611 | 134.769 / 157.776 | 7330.0 | 257852.0 |
| eb27e5fd | 32 | 2.723 / 3.991 | 161.057 / 185.877 | 8348.0 | 288553.0 |
| eb27e5fd | 64 | 3.357 / 5.301 | 193.075 / 220.010 | 10936.5 | 336439.0 |
| eb27e5fd | 86 | 3.816 / 5.534 | 209.560 / 243.120 | 12873.0 | 374447.5 |
| eb27e5fd | 128 | 4.491 / 7.487 | 280.035 / 339.598 | 16060.5 | 450883.0 |
| eb27e5fd | 256 | 6.532 / 12.995 | 449.203 / 696.526 | 26508.5 | 723347.0 |
| 5008be0a | 16 | 3.234 / 3.301 | 115.418 / 122.625 | 7055.0 | 216515.0 |
| 5008be0a | 19 | 2.808 / 2.814 | 112.063 / 120.924 | 7218.0 | 221406.0 |
| 5008be0a | 32 | 3.080 / 3.447 | 129.152 / 142.660 | 8236.0 | 258240.0 |
| 5008be0a | 64 | 3.930 / 5.733 | 175.921 / 194.567 | 10821.5 | 346558.0 |
| 5008be0a | 86 | 4.449 / 6.299 | 204.079 / 235.384 | 12758.0 | 394719.0 |
| 5008be0a | 128 | 5.299 / 8.543 | 268.429 / 318.479 | 15945.5 | 521346.0 |
| 5008be0a | 256 | 7.889 / 14.485 | 450.430 / 616.154 | 26391.5 | 875858.0 |

At length 19, n allocates about 221–342 KB/op versus signatures' 7.0–7.3 KB/op.
This identifies n as the larger allocation target for other lanes; it does not
measure individual runtime objects or prove a universal engine speed advantage.

## Malformed observations and remaining limits

All **24 malformed signature** comparisons passed. All **24 malformed n**
comparisons differed and are printed separately in the acceptance log:

- Empty and one-character n: original full URL wrapper returns null; Kotlin
  returns an empty string (eight observations total).
- Whitespace, punctuation, controls and BMP Unicode: original wrapper receives
  an encoded URL while Kotlin receives the raw string (16 observations). For
  example `4f38b487` whitespace produced QuickJS
  `n3eX7QSFYHQsCwCA4JmDxYt3GqHSr6HKmfi__rz1vZ3vvTfGLmWpkB8` versus Kotlin
  `UFNyymesXJY30sw`. This cross-boundary comparison does **not** establish raw
  malformed-n transform correctness or isolate a production defect.

No Kotlin output from these observations is used as an oracle. A future raw malformed-n
parity task needs an explicitly matched oracle/API boundary; this lane does not
alter the existing full-player oracle to manufacture agreement.

Not checked: Android, Linux Native, Apple, consumer builds, real playback,
unsupported allocation-accounting JVMs, Java 17 execution, malformed surrogate
pairs, maximum valid challenge sizes, independent Node regeneration for the new
inputs, repeated-process statistics, native/retained/peak memory, or throughput
under concurrent load. Concurrency here checks correctness only. Parent owns
cross-platform/consumer verification and the final local Codex review.
