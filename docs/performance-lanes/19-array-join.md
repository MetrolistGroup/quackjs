# Lane 19: array join builder capacity

## Hypothesis and scope

Start `StringBuilder` at the receiver's element count rather than the default
capacity. For a vector of one-UTF-16-unit strings joined with `""`, that is the
exact output size and avoids repeated backing-buffer growth. This is a capacity
hint, not an assumption about element types: the original coercion and length
checks still run for every element. No speculative direct-character path or
pre-scan is needed.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: one builder-constructor argument.
- `src/commonTest/kotlin/meow/nyx/quackjs/ArrayJoinLane19Test.kt`: focused regressions.
- `docs/performance-lanes/19-array-join.md`: this report.

Array construction and indexed/length writes already cap the element count at
65,536, so the initial capacity is bounded independently of strings in the array
or the separator. Sparse/null vectors can reserve more space than their actual
output, but never more than this initial bound. The existing incremental `Long`
length check remains before either append; no oversized output string is built.
Both the up-front traversal charge and final string-allocation charge remain
unchanged. No API, fixtures, exports, dependency or Gradle configuration changes.

## Measurements

Shared pre-lane medians are from
`/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`; candidate run 1 is
`/tmp/quackjs-perf32/19-benchmark.log`. Times are Kotlin milliseconds, with 11 cold
samples and 31 warm samples of 496 transforms, using the unchanged full-player
benchmark and timing boundaries.

| Player | Before cold, runs 1 / 2 | After cold, run 1 | Before warm, runs 1 / 2 | After warm, run 1 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 143.881 | 57.042 / 53.073 | 50.547 |
| 10733253 | 135.474 / 134.351 | 122.697 | 48.456 / 45.793 | 43.112 |
| eb27e5fd | 135.206 / 138.892 | 120.645 | 47.711 / 43.456 | 42.387 |
| 5008be0a | 145.202 / 142.167 | 128.690 | 38.905 / 41.172 | 37.165 |

The first run looks promising for warm batches, but one cold result regresses.
The shared baseline alone does not isolate this small change from host noise.

A serialized local-baseline/candidate-repeat follow-up was attempted using
`/tmp/quackjs-perf32/19-paired-validation.sh`. Baseline JVM tests passed, but its
benchmark was interrupted with `Gradle build daemon has been stopped: stop
command received`. The script exited 1 and restored the candidate via its EXIT
trap; the candidate repeat was therefore never reached. No stop command was
issued by this lane.

For transparency, the interrupted baseline printed the following diagnostic
medians; this is **not a successful full benchmark** or evidence of a speedup:

| Player | Interrupted local baseline cold (ms) | Warm batch (ms) |
| --- | ---: | ---: |
| 4f38b487 | 110.542 | 48.827 |
| 10733253 | 120.019 | 41.295 |
| eb27e5fd | 121.773 | 44.426 |
| 5008be0a | not completed | not completed |

These partial results are mixed against candidate run 1. **No reliable isolated
throughput gain is established.** The retained production change is only the
one-line capacity hint: it avoids growth for character vectors, but no allocated
byte count or retained-memory improvement was measured.

## Verification

All heavy commands use `flock /tmp/quackjs-perf32.validation.lock`:

```sh
./gradlew --no-daemon --max-workers=2 jvmTest --console=plain
./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain
```

Initial complete validation passed: 54 JVM tests, including all five new common
regressions, full-player parity, QuickJS oracle and eight-thread concurrency.
The full benchmark passed all 124 independent goldens and timed-output checks.
Logs: `/tmp/quackjs-perf32/19-tests.log` and
`/tmp/quackjs-perf32/19-benchmark.log` (`19-validation.exit` is 0).
Follow-up logs: `19-local-baseline-tests.log` (54 tests passed),
`19-local-baseline-benchmark.log` (interrupted), and `19-paired-validation.exit`
(1), all under `/tmp/quackjs-perf32/`. The restored runtime was byte-compared
against the candidate that passed the initial complete validation.

The focused common tests cover UTF-16 character vectors, holes, null/undefined,
empty and multi-unit strings, supported numeric/boolean/regex coercion,
unsupported numeric/object/array coercion (also for separators on empty arrays),
empty/single-element arrays, default/explicit separators, ignored `toString`
arguments, actual/invalid receivers, exact 65,536-unit output boundaries, large
separator/element rejection, and exact traversal/allocation charges. Existing JVM
parity, QuickJS oracle and shared-program concurrency tests are required too.

Initial verification found two test-fixture issues, corrected before the final
run: mixed-list assertion type inference and a missing source-index class anchor.
Logs are retained as `19-tests-initial.log` and `19-tests-fixture-failure.log`.

## Integration and limits

**Defer integration until the parent measures the combined candidate.** The
capacity change is small and preserves the checked path, but these measurements
do not justify calling it a performance winner. If only measured winners are
selected, skip this production change; the regression tests stand independently.

This touches only the join/toString builder line in `Runtime.kt`; other runtime
lanes need textual reconciliation, but no array representation or shared-state
changes are introduced.

Not checked here: Android, Kotlin/Native/Linux, Apple, consumer builds, devices,
retained-memory profiles or live playback. Parent owns cross-platform and
combined-patch verification. JVM timing on the shared host is noisy; capacity
sizing alone must not be described as a universal throughput improvement.
