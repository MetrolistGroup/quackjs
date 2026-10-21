# Lane 23: builtin construction

## Hypothesis and change

Reuse immutable `Native` callables instead of reconstructing them for every
runtime or primitive property lookup. Follow the existing shared String/Number
constructor and array/string method pattern.

Retained paths:
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/BuiltinConstructionLane23Test.kt`
- `docs/performance-lanes/23-builtin-construction.md`

Date, Array and Object constructors, Math methods, and number/boolean `toString`
methods now live in the private companion. Math still receives a fresh mutable
field map for each runtime. Constructor results remain fresh mutable values.
Every budget charge and Date cache access uses the invoking Runtime, never the
runtime that originally resolved a callable. Receiver checks, constructor flags,
radix restrictions, allocation accounting and unsupported-input behavior remain
unchanged. No dependencies, API, fixtures or benchmark changes.

## Actual fixture usage

A temporary JVM inspection executed all 124 golden challenges through fresh
runtimes using the compiled plans, checked outputs, and inspected resolved global
names. Among Math/Date/Array/Object, **only Date** was resolved on these paths:
all four players' n transforms resolved Date and String; signature transforms
resolved none of these inspected constructors. Compiled date-cache sizes were
6, 5, 3 and 3 for 4f38b487, 10733253, eb27e5fd and 5008be0a respectively.

The inspection did not count primitive `toString` lookups. Those and the other
constructors have focused regression coverage, but no fixture throughput benefit
is attributed to them. No extra Math operations, date features, Error constructor
pooling, coercion changes or unrelated arithmetic optimizations were attempted.

## Real-player measurements

Unmodified `playerBenchmark`, JDK 21.0.12.1, Linux amd64. Medians in milliseconds;
cold means compile plus first signature/n pair, warm means 496 transforms.
The paired local baseline restored the HEAD runtime (identical to the shared
baseline runtime), then restored the candidate in the same worktree and lock.

| Player | Local before cold | After 1 cold | After 2 cold | Local before warm | After 1 warm | After 2 warm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 112.897 | 113.889 | 116.463 | 52.731 | 50.459 | 50.038 |
| 10733253 | 127.072 | 135.791 | 130.391 | 43.371 | 44.760 | 45.344 |
| eb27e5fd | 122.263 | 120.718 | 123.698 | 42.040 | 41.419 | 42.092 |
| 5008be0a | 120.183 | 120.997 | 123.458 | 37.288 | 39.276 | 38.519 |

For comparison, the two shared pre-lane baseline runs were:

| Player | Before cold, runs 1 / 2 | Before warm, runs 1 / 2 |
| --- | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 57.042 / 53.073 |
| 10733253 | 135.474 / 134.351 | 48.456 / 45.793 |
| eb27e5fd | 135.206 / 138.892 | 47.711 / 43.456 |
| 5008be0a | 145.202 / 142.167 | 38.905 / 41.172 |

**No material throughput win established.** The apparent improvement against the
older shared baseline also appears in the local unmodified runtime. Paired cold
times were slightly worse; warm times were mixed. QuickJS timings also moved
between runs. Treat these timing differences as host/JIT noise, not a speedup.

### Real-player allocation check

The temporary inspection also measured current-thread JVM allocated bytes for
16 repetitions of all golden cases, after five warm batches, median of seven
samples. Both candidate runs produced the same allocation counts:

| Player | Before bytes / 496 transforms | After bytes / 496 transforms | Saved bytes |
| --- | ---: | ---: | ---: |
| 4f38b487 | 120,821,248 | 120,811,648 | 9,600 |
| 10733253 | 86,284,032 | 86,274,432 | 9,600 |
| eb27e5fd | 78,933,888 | 78,924,288 | 9,600 |
| 5008be0a | 77,041,536 | 77,031,936 | 9,600 |

This is only 40 bytes per n invocation on this JVM (240 n calls per batch), about
0.008% to 0.0125% of total allocation. It confirms a small construction saving,
not a significant real-player performance improvement.

## Verification and logs

Final `jvmTest`: **54 tests, zero failures/errors/skips**. This includes the five
Lane23 common regressions, all existing budget and mutation/rejection tests,
full-player Kotlin/Node golden parity, full-player QuickJS oracle parity, and the
32-task concurrent-call test. Both candidate benchmarks and the local baseline
benchmark passed all 124 goldens before reporting timings.

Focused checks cover:
- Math replacement, deletion, added mutable fields and null-valued fields without
  leaking across calls, compilation probes or separate programs;
- shared number/boolean methods using the invocation receiver, with invalid
  receiver and non-decimal-radix rejection;
- borrowed Math, primitive and Object.assign callables charging the invoking
  runtime's step budget;
- a shared Date callable honoring the invoking runtime's precompiled/local date
  caches and allocation budget, without modifying the donor runtime's cache;
- independent Array/Object results and unchanged native-property mutation,
  primitive-boxing, oversized-array and Date call/construct restrictions.

All Gradle, benchmark and allocation-inspection work was serialized with
`flock /tmp/quackjs-perf32.validation.lock`. Commands:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/23-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/23-benchmark.log 2>&1
'
flock /tmp/quackjs-perf32.validation.lock bash /tmp/quackjs-perf32/23-paired-validation.sh
```

The retained local driver runs filtered inspection plus `playerBenchmark` on the
baseline and candidate, removes the temporary inspection test, then runs the full
`jvmTest` suite again. Exit statuses in `23-validation.exit` and
`23-paired-validation.exit` are both zero. Additional files under
`/tmp/quackjs-perf32/`:
- `23-local-baseline-benchmark.log`, `23-benchmark-2.log`, `23-final-tests.log`;
- `23-baseline-inspection.log/.xml`, `23-patched-inspection.log/.xml`;
- `23-initial-test-results/`, `23-BuiltinUsageLane23InspectionTest.kt`;
- `23-Runtime-baseline.kt`, `23-Runtime-patched.kt`;
- shared `baseline-benchmark-1.log` and `baseline-benchmark-2.log`.

## Integration recommendation and limits

Safe to integrate as a small allocation cleanup, but low priority for a
performance-only selection: the measured corpus benefit is tiny and there is no
established throughput gain. It touches only Runtime `get`, the private native
companion and builtin resolution, so check conflicts with other builtin/property
lanes. Rebenchmark any combined patch rather than adding lane timings together.

Not checked: Android, Kotlin/Native Linux, iOS, consumers, process-startup cost,
or arbitrary additional players. Static callables introduce a small one-time
initialization cost; they do not share mutable JavaScript fields or runtime state.
Automated Codex review is left to the parent as instructed.
