# Lane 12: global resolution

## Outcome

**Do not integrate the Runtime experiment.** No reliable real-player speedup was
established, so the production change was discarded. Retained changes are five
common regression tests and this report. There is no production performance claim.

Retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/Lane12GlobalResolutionTest.kt`
- `docs/performance-lanes/12-global-resolution.md`

Experimental path, restored byte-for-byte to HEAD:
`src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`.

## Hypothesis and discarded change

A lexical read should not find an environment with `containsKey` and then hash
its name again to read the value. Once lexical shadowing is ruled out, captured
global reads might also benefit from the existing per-runtime global slot cache.

The candidate added an iterative `Env.lookup`, returning either the actual value
(including null/undefined) or the existing immutable `Missing` sentinel. Successful
non-null reads needed one map lookup. Runtime lexical reads stopped before the
global scope and fell through to `globalValue` only after checking shadowing.
`resolve` used the same lookup without allocating an empty map just to check it.

Direct local slots and the direct-global-function fast path were unchanged.
Function-local writes, `Env.owner`, global writes/revision invalidation, lazy
initializers, template copying, budgets and `source.definition` validation were
not changed. No shared mutable cache or dependency was introduced.

The small candidate passed correctness checks, but the benchmark evidence did
not justify retaining another lookup path. Its patch and both Runtime snapshots
remain locally available under `/tmp/quackjs-perf32/12-*` for inspection.

## Real-player measurements

Same JDK 21 and unmodified `playerBenchmark` boundaries as the shared baseline:
11 cold samples, 31 warm samples, 496 transforms per warm batch. Times below are
Kotlin medians in milliseconds; lower is better.

| Player | Shared baseline cold 1 / 2 | Candidate cold 1 | Shared baseline warm 1 / 2 | Candidate warm 1 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 134.028 | 57.042 / 53.073 | 51.506 |
| 10733253 | 135.474 / 134.351 | 154.637 | 48.456 / 45.793 | 44.737 |
| eb27e5fd | 135.206 / 138.892 | 131.160 | 47.711 / 43.456 | 41.361 |
| 5008be0a | 145.202 / 142.167 | 133.893 | 38.905 / 41.172 | 38.014 |

The first warm comparison looked promising, so an unmodified local baseline and
second candidate were run consecutively under one lock:

| Player | Local baseline cold | Candidate cold 2 | Local baseline warm | Candidate warm 2 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 119.159 | 128.981 | 52.427 | 53.732 |
| 10733253 | 136.347 | 152.452 | 44.020 | 52.708 |
| eb27e5fd | 147.698 | 166.638 | 43.311 | 54.427 |
| 5008be0a | 127.759 | 175.844 | 38.399 | 64.847 |

Candidate 1 differs from the local baseline by only about -4.5% to +1.6% warm,
while candidate 2 regresses on all four players. Host scheduling, JIT and unrelated
builds are uncontrolled. QuickJS also slowed in the second run, for example
5008be0a warm 41.220 -> 52.996 ms, so attributing the entire regression to this
patch would be unjustified. Neither a repeatable improvement nor a precise
regression size is established. No further speculative optimization was retained.

## Verification

The candidate's initial full `jvmTest` execution passed: 54 tests, zero skipped,
failures or errors. This includes the five new common tests, all four real-player
parity suites, the QuickJS oracle and 8-thread/32-task concurrency regression.
Every benchmark process independently passed all 124 full-player goldens before
timing. The candidate was restored after the local baseline benchmark; its
subsequent `jvmTest` was up-to-date against the initial passing execution.

The new tests cover:

- lazy aliases observing rebinding before first use, retained aliases keeping
  object identity, nested template state, and repeated independent calls;
- global-slot invalidation after null/undefined/value/function rebinding,
  calling the global reader after each rebind to exercise the cached path;
- lexical and catch shadowing with null/undefined while globals are cached;
- externally mutated aliases first used after compilation, preserving lazy
  `source.definition` mutation validation;
- circular initializers and the existing dependency-depth failure.

Existing execution/allocation, frame, call-depth, source/parser, unsupported-input
and uncatchable-budget failure tests also passed. After discarding the Runtime
candidate, final retained-tree `jvmTest` executed all 54 tests again: zero skipped,
failures or errors. `git diff --check` passed.

Review follow-up corrected the invalidation test to call global `read()` after
each value rebind, rather than a locally created closure that bypasses the cache.
Temporarily suppressing revision increments only for writes named `value` made
that focused test fail with `initial:initial:initial:initial:other` instead of
`initial:null:undefined:one:other`. Runtime was restored, and all 54 JVM tests
passed again. The first full-suite attempt received a Gradle daemon stop command;
the retry passed. All commands used the same shared lock. Benchmarks were not
repeated for this test-only correction.

## Commands and logs

All builds and benchmarks were serialized with
`flock /tmp/quackjs-perf32.validation.lock`. Initial commands:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/12-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/12-benchmark.log 2>&1
'
```

The local comparison used the same lock and Gradle flags, temporarily replacing
only Runtime with its HEAD snapshot, benchmarking it, then restoring the candidate
and running `jvmTest` and `playerBenchmark` again. An EXIT trap restored the
candidate even on failure. Both initial and paired driver exit statuses were 0.
The discarded candidate was then removed, and the retained tree passed a final
full `jvmTest` with the same lock and flags.

All log paths are under `/tmp/quackjs-perf32/`:

- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared before runs.
- `12-tests.log`, `12-benchmark.log`: initial candidate, both passed.
- `12-local-baseline-benchmark.log`: local unmodified Runtime, passed.
- `12-tests-final.log`, `12-benchmark-2.log`: restored candidate, passed.
- `12-tests-retained.log`: final retained-tree validation, passed.
- `12-validation.exit`, `12-paired-validation.exit`,
  `12-retained-validation.exit`: all 0.
- `12-test-results-initial/`: initial candidate's passing XML reports.
- `12-review-mutation-tests.log`, `12-review-mutation-test-results/`: expected
  focused assertion failure with the temporary revision-invalidation defect.
- `12-review-tests.log`: full-suite attempt interrupted by a daemon stop command.
- `12-review-tests-retry.log`, `12-review-tests-retry.exit`: 54 tests passed, exit 0.
- `12-abandoned-candidate.patch`, `12-Runtime-baseline.kt`,
  `12-Runtime-patched.kt`: discarded experiment for reproducibility.

## Integration and unchecked scope

Only the tests/report are recommended for integration. There is no production
conflict with local-write, binding, dispatch, source-index or template lanes.
If this experiment is revisited, any global-slot use must still follow lexical
shadowing checks and preserve runtime-local revision invalidation.

Not checked here: Android, Linux Native, Apple targets, consumers, device timing,
retained memory, or profiling. Parent owns combined/platform verification.
