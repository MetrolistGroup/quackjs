# Lane09: allocation-free assignment references

## Hypothesis and scope

Assignments and updates previously created a `Reference` plus two capturing
read/write lambdas per evaluation, including bound local slots. Replace that
private helper with an inline read/compute/write helper so the target and old
value remain Kotlin locals across RHS evaluation. Bound slots still use their
frame array; lexical/global writes still go through `Env.set` (and its revision
invalidation); members still use `property`, `setIndex`, and `set` unchanged.
Simple `=` deliberately does not read the target before its RHS.

Exact touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/AssignmentReferencesLane09Test.kt`
- `docs/performance-lanes/09-assignment-references.md`

No fixtures, consumers, dependencies, configuration, or public API changed.

## Real-player measurements

All values are Kotlin medians in milliseconds. Cold is compile plus first
signature/n pair (11 samples after 3 warmups); warm is a 496-transform batch
(31 samples after 20 warmups). Every benchmark validated all four full players
against the existing goldens and QuickJS before timing.

Shared pre-lane baselines and initial candidate:

| Player | Baseline 1 cold / warm | Baseline 2 cold / warm | Initial patch cold / warm |
| --- | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 113.467 / 47.879 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 121.591 / 41.163 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 128.592 / 45.155 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 142.193 / 41.719 |

Because the initial results were mixed, a second experiment ran unmodified
`Runtime.kt` and then the patched version consecutively under one shared lock,
with the same new tests present in both builds:

| Player | Local baseline cold / warm | Patch cold / warm | Cold change | Warm change |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 125.439 / 58.222 | 129.070 / 50.941 | +2.9% | -12.5% |
| 10733253 | 133.837 / 43.741 | 136.007 / 44.203 | +1.6% | +1.1% |
| eb27e5fd | 155.651 / 45.840 | 133.872 / 42.250 | -14.0% | -7.8% |
| 5008be0a | 141.150 / 43.477 | 133.389 / 41.238 | -5.5% | -5.1% |

Lower is better. These are shared-host observations, not a claim that every
player or cold compilation improves. QuickJS timings also varied between runs.
The paired warm result favors the patch on three players; 10733253 is effectively
neutral/slightly worse. No isolated allocation count or GC reduction was measured.

Static JVM bytecode inspection (`javap -p -c`) confirms `evalComplex` has no
`Reference`, `Function0`, `Function1`, or captured `DoubleRef` use after the change.
The inline update callback does not introduce a replacement heap closure.

## Verification and commands

Every Gradle invocation used `--no-daemon --max-workers=2 --console=plain` and
ran inside `flock /tmp/quackjs-perf32.validation.lock`.

Initial validation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/09-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/09-benchmark.log 2>&1
'
```

Paired validation driver: `/tmp/quackjs-perf32/09-paired-validation.sh`.
Within a single lock it temporarily copied baseline `Runtime.kt` from HEAD,
ran `./gradlew --no-daemon --max-workers=2 jvmTest playerBenchmark --console=plain`,
restored the candidate, and reran the same tasks. An EXIT trap restored the
candidate; `/tmp/quackjs-perf32/09-paired-validation.exit` is `0`.

Logs:

- `/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`: shared before measurements.
- `/tmp/quackjs-perf32/09-tests-initial.log`: first attempt failed because the new
  test-only SourceIndex lacked its required n-class declaration. Fixed the test
  setup; this is not counted as a passing run.
- `/tmp/quackjs-perf32/09-tests.log`: 55 tests pass, including all 6 Lane09 tests,
  the existing common tests, JVM parity/oracle tests and concurrency test.
- `/tmp/quackjs-perf32/09-benchmark.log`: initial candidate, all goldens pass.
- `/tmp/quackjs-perf32/09-local-baseline.log`: paired baseline, tests and goldens pass.
- `/tmp/quackjs-perf32/09-paired-after.log`: paired candidate, 55 tests and goldens pass.
- `/tmp/quackjs-perf32/09-runtime-bytecode.txt`: static bytecode inspection output.

Focused regressions cover all supported assignment/compound operators on bound
slots, object members, numeric and string array keys; numeric prefix/postfix
results; receiver/key evaluation exactly once; target rebinding and old-value
reads before RHS mutation; null-target error timing; closure and catch-shadowed
writes; global-slot invalidation after assignment/compound/update; exact step
charges for local/member paths and string update coercion; array size/index
limits; and undeclared-target rejection before RHS effects. Existing allocation,
unsupported-input, mutation-detection, and concurrent-call isolation tests pass.
`git diff --check` also passes.

## Recommendation and limits

Recommend integrating this small allocation-removal patch, subject to the parent
review and combined benchmark. The reference/lambda removal is structural and
three paired warm workloads improved, but a universal timing gain is not proven.
Other Runtime lanes changing `evalComplex`, lexical slots, or member access/write
helpers need careful conflict resolution; this patch preserves those helpers and
budget calls rather than replacing them.

Android, Linux Native, iOS, and consumer verification were not run in this lane;
those remain parent work. No independent Codex review was run by this worker.
