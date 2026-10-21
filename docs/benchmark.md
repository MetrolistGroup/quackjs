# Measured performance

## Reproduce the JVM workloads

```sh
./gradlew playerBenchmark --console=plain
./gradlew lane32AcceptanceBenchmark --console=plain
```

Both tasks are opt-in, use JVM test-only dependencies and reject `-PoracleOnly`.
They verify the four full-player golden suites against Kotlin and QuickJS before
measurement, then compare every timed sample's complete output outside the timer.
A mismatch aborts the run. Neither implementation caches challenge outputs.
See [oracle methodology and corpus provenance](verification.md).

### JVM methodology

- **Cold program:** source already in memory; fresh hinted
  `PlayerProgram.compile` plus first signature (length 86) and n (length 19).
  QuickJS gets a fresh runtime/thread, globals, complete-player compilation and
  the same first pair. First-call input setup is included; process startup,
  native library loading, fixture I/O and disposal are excluded. Three discarded
  warmups, **11 samples per implementation/player**. Hint-free compilation is
  not timed.
- **Warm batch:** one compiled Kotlin program versus one initialized full
  QuickJS player; 31 cases repeated 16 times, **496 transforms/sample**. QuickJS's
  input array and batch bridge are installed/compiled outside timing; samples
  execute bytecode, not per-input eval strings. Twenty discarded warmups,
  **31 samples per implementation/player**. This is a batched steady-state
  baseline, not a charge for full-player initialization on every call.
- Both warm paths include result-array construction and JSON serialization;
  QuickJS also includes one dispatcher/JNI round trip and result marshalling per
  batch. Input transfer and equality checks are excluded. Implementations
  alternate first/second order on each paired sample. No forced GC or elapsed-time
  assertions. Results are median milliseconds, not individual-call percentiles.
- Tasks print JVM/vendor/flags/heap, OS/architecture, CPU count and QuickJS limits.
  Run separate JVM processes on an otherwise idle host; record CPU model, power
  policy and source commit. Serialize competing builds/benchmarks. These small
  harnesses do not measure process startup, concurrent throughput, retained memory,
  energy, live networking, Android hardware or Apple performance.

## Recorded JVM results

Core `25e10da`, measured 2026-10-21, after ten selected runtime optimizations.
Production remained common Kotlin/stdlib only, with no public API change,
fixture-specific constants, output caches or relaxed safety budgets. Historical
experiment reports remain recoverable from Git history.

Environment: Linux 7.2.11-1-cachyos x86_64, AMD Ryzen 9 7950X3D (32 logical CPUs),
Temurin 21.0.12.1, JVM heap 512 MiB initial / 2 GiB maximum. QuickJS-KT JVM 1.0.15,
192 MiB native heap, 2 MiB JS stack, dedicated 8 MiB thread. The observed governor
was `powersave` with `amd-pstate-epp`; no CPU pinning or policy control. Heavy
validation was serialized, but the shared host was not guaranteed idle. JIT, GC
and scheduling remain sources of noise.

### Unchanged control and optimized core

Each column is a separate JVM process. The control before and after the two
candidate runs uses the original `a7e66f5` runtime and the same benchmark.
All times are milliseconds. **Lower is better.**

| Player | Control cold before / after | Final cold run 1 / 2 | Control warm before / after | Final warm run 1 / 2 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 114.955 / 114.762 | 101.919 / 106.919 | 48.543 / 48.536 | 38.372 / 39.310 |
| 10733253 | 124.228 / 119.601 | 107.474 / 106.237 | 41.829 / 41.935 | 35.365 / 36.474 |
| eb27e5fd | 120.674 / 120.645 | 107.149 / 109.290 | 40.158 / 41.762 | 33.517 / 34.114 |
| 5008be0a | 119.919 / 122.344 | 108.462 / 112.073 | 36.408 / 36.248 | 30.269 / 31.140 |

Warm latency falls approximately **13–21% relative to the previous core** across
these measurements. Cold differences are smaller and more sensitive to noise.
These ranges are observations, not confidence intervals or universal guarantees.

### Optimized core versus QuickJS

Times below are **QuackJS / QuickJS**, measured in the same alternating sample
sequence in each run.

| Player | Run 1 cold | Run 2 cold | Run 1 warm batch | Run 2 warm batch |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 101.919 / 243.147 | 106.919 / 247.104 | 38.372 / 52.008 | 39.310 / 52.681 |
| 10733253 | 107.474 / 270.245 | 106.237 / 256.322 | 35.365 / 45.439 | 36.474 / 45.696 |
| eb27e5fd | 107.149 / 258.978 | 109.290 / 264.179 | 33.517 / 45.880 | 34.114 / 46.701 |
| 5008be0a | 108.462 / 259.651 | 112.073 / 261.753 | 30.269 / 38.630 | 31.140 / 39.294 |

Cold preparation is **2.3–2.5× faster**. Warm mixed-batch latency is **20–27% lower**
(approximately **1.25–1.37× throughput**) on all four players in both runs. This
is limited to this four-player dispatcher/table corpus, not proof of a universal
advantage or a guarantee for every individual signature/n length or platform.

### JVM allocation and unseen-input acceptance

`lane32AcceptanceBenchmark` generates 112 unseen valid challenges: two base64url
inputs per player/kind/length at lengths 16, 19, 32, 64, 86, 128 and 256, using
`java.util.Random(0x32acc3e7L)`. Inputs are distinct from stored goldens. Expected
outputs come only from full-player QuickJS. The preflight checks repeated/reverse
order and four-worker shared-program correctness, not concurrent throughput.
Malformed n comparisons remain diagnostics because that oracle uses a URL wrapper.

Each player/kind/length cell has 10 paired warmups, 15 paired samples and 16
transforms/sample. Both paths include result construction/JSON serialization;
compilation, input transfer and equality checks are excluded. Timing reports
median batch time divided by 16, not individual-call latency percentiles.
Optional `ThreadMXBean` accounting measures calling JVM-thread allocation, not
retained/peak heap or QuickJS native/worker memory; unsupported counters are
reported as unsupported, not zero. Bytes/op below are equal-weight means of the
seven length-group medians:

| Player | Signature bytes/op before → after | n bytes/op before → after |
| --- | ---: | ---: |
| 4f38b487 | 12,408.7 → 5,456.7 | 532,977.9 → 315,417.9 |
| 10733253 | 12,399.9 → 5,615.9 | 429,896.0 → 261,372.6 |
| eb27e5fd | 12,746.2 → 5,618.2 | 382,647.2 → 209,386.6 |
| 5008be0a | 12,631.9 → 5,623.8 | 404,948.9 → 246,271.1 |

Across these fixed equal-operation groups, **signature allocation falls 56% and
n allocation 41%**. Per-group changes vary. This is one allocation measurement
pair, not a GC-pause/energy or total-memory result. Structural reductions remove
assignment closures/return wrappers, small-call temporary lists, repeated callback
argument arrays and double copies of split/template containers. A guarded n
callback plan avoids repeated AST traversal without caching inputs/results or
assuming immutable tables.

## Kotlin/Native extract workload

This opt-in harness uses the 15,171-UTF-16-unit `eb27e5fd` **dependency extract**,
not full players. Reproduce on Linux x64 after Gradle downloads Kotlin/Native
2.4.20 (for example via `./gradlew linuxX64Test`):

```sh
set -eu
native="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.20"
out=$(mktemp -d)
"$native/bin/konanc" -opt \
  -entry meow.nyx.quackjs.nativePerformanceLane31 \
  -o "$out/native-workload" \
  src/commonMain/kotlin/meow/nyx/quackjs/*.kt \
  src/commonTest/kotlin/meow/nyx/quackjs/RealPlayerSlice.kt \
  src/commonTest/kotlin/meow/nyx/quackjs/NativePerformanceLane31.kt
for run in 1 2; do "$out/native-workload.kexe"; done
rm -r "$out"
```

The compiler uses `-opt` and unchanged GC defaults. Six independently checked
extract vectors include Unicode, one-character and empty inputs. Source loading,
hints/vector setup and a full-vector preflight are outside timing. Both phases
discard 3 warmups and retain 11 samples; every sample's complete outputs are
checked outside the timer, with no forced GC or timing thresholds.

Cold measures compile + first signature/n pair and output list. Warm measures
192 transforms against one compiled program, including interpreter state,
container copying and result-list construction, but no JSON bridge/serialization.
It measures elapsed time, not allocated bytes. The other three full players have
no Native timings. A control binary was run between two optimized-core runs:

| Version/run | Cold compile + first pair, ms | Warm 192 transforms, ms |
| --- | ---: | ---: |
| Final 1 | 8.407 | 36.345 |
| Original control | 5.030 | 37.239 |
| Final 2 | 4.706 | 36.133 |

Cold results are noisy and the warm difference is small. **No reliable Native
speedup or QuickJS-native comparison is claimed.** All output checks passed.
Profiling attempts did not yield trustworthy attribution: `perf` lacked
permissions and `gprofng` warned of an unreliable collection interval. No Native
allocation, retained-memory, energy, Android-device or Apple claim follows.

## Historical initial implementation

Before optimization, core `a7e66f5` was near warm parity rather than consistently
faster than QuickJS. Original two runs, milliseconds as **QuackJS / QuickJS**:

| Player | Run 1 cold | Run 2 cold | Run 1 warm batch | Run 2 warm batch |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 122.824 / 247.360 | 109.943 / 250.988 | 52.606 / 60.500 | 49.318 / 52.627 |
| 10733253 | 128.539 / 283.492 | 118.846 / 267.654 | 48.826 / 50.019 | 41.974 / 44.335 |
| eb27e5fd | 128.784 / 286.599 | 121.964 / 262.502 | 43.695 / 52.807 | 40.812 / 46.308 |
| 5008be0a | 131.262 / 282.611 | 120.715 / 261.300 | 43.529 / 41.087 | 37.239 / 38.626 |

The first run's `5008be0a` warm batch regressed relative to QuickJS. These older
runs are retained for context, not used as the paired controls for newer claims.
