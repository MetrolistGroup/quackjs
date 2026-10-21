# Lane18: array splice, slice and front mutations

## Hypothesis and patch

Avoid temporary `subList` views, `drop(2)` insertion copies, and collection-to-array
copies while keeping `JsArray.values: MutableList<Any?>` and its storage identity.

Touched paths:
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: copy slice/removed ranges
  directly into their final list; splice moves its tail once in the appropriate
  direction and reads inserted arguments directly. Single-element insertion and
  deletion use the existing list primitives; whole-array deletion uses `clear`.
  Unshift skips empty `addAll` and uses `add` for one argument. Shift already uses
  direct `removeAt(0)` and remains unchanged rather than adding a deque layer.
- `src/commonTest/kotlin/meow/nyx/quackjs/ArrayMutationLane18Test.kt`: six common
  regressions covering resize directions, sparse ranges, arity, coercion, aliases,
  size limits and exact existing resource charges.
- This report.

No output caching, source/fixture changes, new dependencies, new storage class,
public API changes, or changed allocation/step charges.

## Real-player measurements

JVM 21.0.12.1, Linux amd64, 512 MiB initial / 2 GiB maximum heap, unchanged full
benchmark. All four players and all 124 Kotlin/QuickJS goldens pass before timing.
Cold medians use 11 samples after 3 warmups; warm medians use 31 samples after 20
warmups, with 496 transforms per batch. Each benchmark is a separate JVM process.

Numbers below are **Kotlin cold / warm batch milliseconds**. Shared before runs
are the pre-lane baseline. Local before was rerun in this worktree, followed by
candidate run 2 under the same uninterrupted validation lock.

| Player | Shared before 1 | Shared before 2 | Local before | Candidate 1 | Candidate 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 115.616 / 50.073 | 116.117 / 48.728 | 113.743 / 50.204 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 122.402 / 44.208 | 119.844 / 41.706 | 120.434 / 40.924 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 125.269 / 42.142 | 120.410 / 41.007 | 120.228 / 38.712 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 121.899 / 39.844 | 118.611 / 35.993 | 121.797 / 35.726 |

QuickJS reference medians, also **cold / warm milliseconds**:

| Player | Local before | Candidate 1 | Candidate 2 |
| --- | ---: | ---: | ---: |
| 4f38b487 | 239.480 / 53.469 | 255.321 / 53.714 | 255.656 / 56.211 |
| 10733253 | 252.910 / 46.491 | 264.856 / 44.340 | 267.567 / 44.848 |
| eb27e5fd | 270.338 / 46.629 | 265.648 / 46.362 | 265.342 / 46.320 |
| 5008be0a | 263.780 / 41.542 | 267.865 / 38.427 | 271.132 / 39.437 |

Against local before, candidate 2 warm batches change by **+0.3%, -7.4%, -8.1%,
-10.3%**, respectively. Candidate 1 also improves the latter three. The first
player is neutral and the cold differences do not establish a reliable gain.
Shared-baseline differences overstate the gain because host/JIT conditions have
changed; even the local QuickJS medians vary. These are promising end-to-end warm
results, not a guarantee of speedups for every array size or program.

## Verification and reproduction

Every Gradle/benchmark command used the shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/18-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/18-benchmark.log 2>&1
'
```

For the paired check, the same commands ran under one lock, temporarily copying
`18-Runtime-before.kt` into place, then restoring `18-Runtime-after.kt` with an
EXIT trap. No branch operations or Gradle configuration changes were used.

Logs/artifacts in `/tmp/quackjs-perf32/`:
- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared before.
- `18-tests.log`, `18-benchmark.log`: first passing candidate suite/benchmark.
- `18-local-baseline-tests.log`, `18-local-baseline-benchmark.log`: paired before.
- `18-final-tests.log`, `18-benchmark-2.log`: final candidate suite/benchmark.
- `18-Runtime-before.kt`, `18-Runtime-after.kt`: exact compared runtime sources.
- `18-tests-initial.log`: initial test-compilation failure from three assertion
  generic types; fixed before any measurements. No failed run counted as a pass.

Final JVM suite: **55 tests, zero failures/skips**, including all six new common
regressions, existing unsupported-input/budget tests, full-player parity and
concurrent-call isolation. The new tests also pass on the unmodified runtime.
They check holes versus explicit undefined, shallow object/self references,
receiver-driven method aliases, independent return-array storage, negative and
fractional positions, omitted versus explicit undefined arguments, NaN/infinities,
65,536-element limits, and exact allocation/step boundaries including no-ops.
`git diff --check` passes.

## Recommendation and limits

Recommend integration, subject to parent review and combined benchmarking: this
removes temporary storage with modest code and promising warm improvements on
three players without changing array representation or budget semantics.

Interactions: other Runtime array/indexing or array-allocation lanes may overlap
at `slice`, `splice`, `unshift`, or the neighboring `array` helper. Reconcile those
hunks manually; do not blindly combine timings. Shift and multi-argument unshift
retain their existing list primitives. Bulk splice uses indexed linear moves;
large-array throughput relative to platform-native bulk copies was not separately
profiled. Temporary allocation reductions are from code inspection, not measured
allocation profiles.

Not checked: Android, Kotlin/Native/Linux, iOS, consumer builds, live playback,
hardware energy/memory, or final combined-lane performance. Parent owns those
platform/integration checks and the automated review; no worker review was run.
