# Lane 15: focused expression execution plan (rejected)

## Hypothesis and scope

Compile strict binary/member subtrees to small evaluator closures after discovery,
dispatch specialization and identifier binding. This might avoid repeated AST
node dispatch on warm calls without a new VM or speculative type/global guards.

The prototype accepted literals, live identifier reads, strict binary operations
and member reads whose children also had plans. Assignments, updates, calls,
short-circuit/comma operators and other unsupported shapes retained the existing
evaluator. Eligible subtrees inside unsupported parents could still use plans.
Plans reused the runtime's identifier lookup, `binary` and `property` operations;
they did not copy coercion rules, capture mutable values or cache outputs.
Each child retained its original tick and complex-expression depth guard.

Temporary changes (all removed):

- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`: optional binary/member plans.
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: closure preparation and
  invocation, sharing the original lookup/operator/property/budget logic.
- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`: prepare plans bottom-up
  in the existing post-discovery binding traversal, before publishing a program.
- `src/commonTest/kotlin/meow/nyx/quackjs/ExpressionPlanLane15Test.kt`: three
  focused differential regressions.

The production diff was 45 added / 7 removed lines, not a parallel interpreter.
The only retained change is this report. No fixtures, benchmark boundaries,
configuration, public API or existing tests were changed.

## Measurements

Unchanged `playerBenchmark`, JVM 21.0.12.1, Linux amd64, 512 MiB initial / 2 GiB
maximum heap. All Gradle runs used the shared validation lock. Cold samples are
compile plus first signature/n; warm samples are batches of 496 transforms.
The harness uses 3/11 cold warmups/samples and 20/31 warm warmups/samples, checks
all four real-player golden suites before timing and checks every timed result.

Times below are Kotlin medians in milliseconds. Shared baseline runs preceded
this experiment. The same-worktree rollback control was measured **after** the
prototype, not before it. QuickJS medians are available in the raw logs.

### Cold compile plus first signature/n

| Player | Shared baseline 1 | Shared baseline 2 | Prototype | Rollback control |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 | 121.488 | 118.934 | 137.045 |
| 10733253 | 135.474 | 134.351 | 134.820 | 140.674 |
| eb27e5fd | 135.206 | 138.892 | 133.908 | 147.342 |
| 5008be0a | 145.202 | 142.167 | 131.988 | 137.542 |

### Warm batch

| Player | Shared baseline 1 | Shared baseline 2 | Prototype | Rollback control | Prototype vs control |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 57.042 | 53.073 | 62.071 | 55.104 | +12.6% |
| 10733253 | 48.456 | 45.793 | 51.890 | 47.913 | +8.3% |
| eb27e5fd | 47.711 | 43.456 | 50.451 | 43.984 | +14.7% |
| 5008be0a | 38.905 | 41.172 | 45.602 | 39.294 | +16.1% |

Warm times regressed against **both** shared baseline runs on every player, and
against the local rollback control by 8.3–16.1%. Host/JIT/GC noise prevents strong
causal claims from one prototype run, but there is no substantive warm-speed win
to justify retaining the change. Lower prototype cold medians do not establish a
cold improvement: that path gained plan construction work, and the control also
shows substantial cold-time variation.

A plausible cost is replacing interpreter dispatch with indirect closure calls
while retaining the necessary per-node guards and generic operators. This was
not profiled, so it is an explanation to investigate, not a measured attribution.

## Correctness and commands

The first test run failed because the new test helper supplied a source without
the mandatory n class. No benchmark ran from that failure. After correcting the
helper, all **52 JVM tests passed**, including:

- Four-player Node/QuickJS golden parity, hint-free compilation and the existing
  concurrent-call isolation test.
- Existing resource limits, unsupported operations, mutation rejection, live
  globals, catch/closure scopes and exception/fallback tests.
- Differential planned/interpreted arithmetic and member results/errors at every
  remaining-step boundary from 0 through 40, over numbers, strings, holes, null,
  NaN, infinity and unsupported object coercions.
- Side-effect order through unsupported parents, single target evaluation,
  short-circuit fallback, lexical scope and null-property errors.
- The same expression execution-depth rejection for a 260-node binary chain.

After removing the prototype and its temporary tests, `git diff --exit-code --
src` passed, all **49 original JVM tests passed**, and the unchanged benchmark
passed all goldens/result checks again.

Run from the assigned worktree, in order (each pair under one lock):

```sh
# Initial prototype run; failed in the test helper, so && skipped the benchmark.
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/15-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/15-benchmark.log 2>&1
'

# Corrected prototype.
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/15-tests-2.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/15-benchmark.log 2>&1
'

# Unmodified runtime, after rollback.
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/15-final-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/15-local-baseline-benchmark.log 2>&1
'
```

Other local evidence under `/tmp/quackjs-perf32/`:

- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared baseline.
- `15-rejected-expression-plan.patch`: discarded production prototype.
- `15-ExpressionPlanLane15Test.kt`: discarded focused regression source.
- `15-prototype-test-results/`: successful 52-test XML results.

These temporary evidence files are local artifacts, not committed dependencies.

## Recommendation and limits

**Do not integrate the prototype.** Keep the baseline evaluator and this negative
result. The approach is not ruled out in general, but a larger compiler or
weaker per-node budgets would exceed this experiment's scope.

There are no retained runtime integration conflicts. A future version would
interact with AST metadata, post-discovery binding, dispatcher specialization and
expression-budget accounting, so it would need remeasurement after other lanes.

Not checked: Android-host/device, Kotlin/Native/Linux execution, Apple targets,
consumer builds, live playback, memory profiling or CPU profiling. No production
behavior change remains, and no cross-platform speed claim is made.
