# Lane29: function metadata and global-slot binding

## Result

**No useful net performance gain established. Do not integrate the prototype.**
Production code is unchanged; this lane retains four common regressions and this
report. The extra retained metadata is not justified by the measurements.

## Hypothesis and scope

`FunctionExpr` already traverses each function body to collect identifiers,
declarations, catch names and closure/arguments flags. Compilation later walks
those bodies again to assign global cache slots. The prototype:

- Built the local-slot map directly, avoiding concatenated/distinct name lists,
  a separate declaration-name set and indexed/pair intermediates.
- Retained only free identifiers and immediate nested functions from the existing
  metadata walk. Global binding followed those references instead of revisiting
  every statement/expression in a function body. Specialized blocks still used
  their existing traversal.

Prototype production paths (both restored byte-for-byte to the starting tree):

- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`

Read the consumers in `Runtime.kt`, `SourceIndex.kt`, `Specialization.kt` and
`Limits.kt`. The candidate preserved slot ordering/counts and resource charging,
catch-name exclusion, nested lexical lookup, named-function binding and duplicate
parameter writes. It did not pool runtime functions, change parsing, mutate ASTs
after publication, or remove any validation traversal.

Retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/FunctionMetadataLane29Test.kt`
- `docs/performance-lanes/29-function-metadata.md`

A temporary JVM allocation inspection test was removed and saved at
`/tmp/quackjs-perf32/29-FunctionMetadataMeasurementLane29Test.kt`. The rejected
production patch is `/tmp/quackjs-perf32/29-candidate.patch`; exact source snapshots
and the comparison driver are under `/tmp/quackjs-perf32/29-*`.

## Full-player measurements

All values below are Kotlin medians in milliseconds. Unchanged benchmark:
11 cold samples after 3 warmups; 31 warm samples after 20 warmups; 496 transforms
per warm batch. Four players / 124 Node and QuickJS goldens passed before timing.
Same JVM/host configuration as the shared baseline: Temurin 21.0.12.1, Linux
x86_64, 32 logical CPUs, 512 MiB initial / 2 GiB maximum benchmark heap.

The local baseline and final candidate ran consecutively under one shared lock,
in separate JVMs:

| Player | Baseline cold | Candidate cold | Change | Baseline warm | Candidate warm | Change |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 113.157 | 115.680 | +2.23% | 53.285 | 54.324 | +1.95% |
| 10733253 | 122.920 | 119.733 | -2.59% | 41.629 | 45.314 | +8.85% |
| eb27e5fd | 122.694 | 121.162 | -1.25% | 41.023 | 44.527 | +8.54% |
| 5008be0a | 127.154 | 120.101 | -5.55% | 36.422 | 39.785 | +9.23% |

For context, the supplied pre-lane baselines and initial candidate were:

| Player | Shared baseline 1 cold / warm | Shared baseline 2 cold / warm | Initial candidate cold / warm |
| --- | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 121.433 / 53.659 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 126.897 / 46.635 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 143.938 / 50.290 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 137.055 / 39.217 |

The initial candidate compacted the collected identifier list in place; the final
candidate instead filtered into a new list so it would not retain backing-array
capacity for all local identifiers. Neither run establishes a net improvement.
There are no warm-runtime changes, so the warm differences cannot be confidently
attributed to metadata work rather than host/JIT/GC/code-layout variation. Cold
results also vary between runs and players.

A temporary `ThreadMXBean.getThreadAllocatedBytes` check measured compilation plus
one golden signature/n pair, with 8 warmup rounds across all players and 11 samples
per player. Median calling-thread allocated bytes (not retained heap):

| Player | Baseline bytes | Candidate bytes | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 187,732,472 | 187,573,568 | 0.0846% |
| 10733253 | 200,143,920 | 200,008,592 | 0.0676% |
| eb27e5fd | 201,185,392 | 201,071,256 | 0.0567% |
| 5008be0a | 201,635,944 | 201,501,632 | 0.0666% |

This saving is too small to justify the extra retained references and binding
path. No allocation or speed claim is made for the final, unchanged production
code.

## Verification and logs

All Gradle work used `flock /tmp/quackjs-perf32.validation.lock`, with
`./gradlew --no-daemon --max-workers=2 ... --console=plain`.

1. Initial `jvmTest` followed by `playerBenchmark`:
   `/tmp/quackjs-perf32/29-tests.log`, `29-benchmark.log`.
2. Local baseline and filtered-list candidate, each `jvmTest` followed by
   `playerBenchmark`, driven by:
   `flock /tmp/quackjs-perf32.validation.lock bash /tmp/quackjs-perf32/29-paired-validation.sh`.
   Logs: `29-baseline-tests.log`, `29-baseline-benchmark.log`,
   `29-candidate-tests.log`, `29-candidate-benchmark.log` in that directory.
   Allocation stdout: `29-baseline-allocation.xml`, `29-candidate-allocation.xml`.
3. Restored production and removed the temporary inspection test; final
   `jvmTest`: `/tmp/quackjs-perf32/29-final-tests.log`.

The retained suite has 53 passing JVM tests, including all four Lane29 cases,
full-player parity, eight-thread/32-task invocation isolation, existing allocation,
step/dependency/depth limits, unsupported-input rejection and mutation detection.
Lane29 specifically covers duplicate/missing parameters, hoisting and slot order,
catch-shadowed globals and captured locals, recursive named functions and `this`,
functions inside global object/array initializers, and shared identifier metadata
remaining unchanged across specialization.

## Recommendation and limitations

Integrate the regression coverage/report only, not the optimization experiment.
There are no production integration conflicts. The tests exercise metadata used
by specialization, lexical/global lookup and callback-frame reuse, so related
lanes should retain these semantics if combined. No fixtures, benchmark timing
boundaries, public API or build configuration changed.

Android, Kotlin/Native/Linux, iOS, consumers, retained heap and energy were not
measured. Parent owns cross-platform verification and the cumulative review.
