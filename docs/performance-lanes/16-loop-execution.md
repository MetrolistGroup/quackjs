# Lane 16: interpreted Loop/Block execution

## Decision

**Reject the production optimization.** The smallest candidate was a seven-line
split of `Runtime.exec`: keep Block and ExpressionStmt in the small entry point,
move the remaining statement dispatch into `execComplex`, and keep every tick and
control-flow operation unchanged. This mirrors the existing eval/evalComplex
split and could make the frequently repeated loop-body path easier to inline.
The real-player results did not establish a repeatable benefit, so even that
small additional indirection was not justified. Runtime.kt is restored exactly
to this lane's baseline; only the focused common regressions and this report
are retained. There is no claimed performance improvement.

A numeric induction fast path was considered but not implemented: caching loop
bounds, coercion results, or the induction value would require guards for body
and closure writes, side effects in conditions and updates, catch shadowing,
Double precision, and exact mid-expression budget failure. The lower-risk
statement-dispatch experiment was sufficient to test a small alternative. No
bytecode engine, unrolling, input specialization, or new supported syntax.

## Scope

Read all Runtime statement dispatch and its callers: invoke, discovery's prefix
execution, recursive control-flow execution, AST construction, and dispatcher
specialization. No caller, export, configuration, fixture, or public API needed
changes.

Retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/Lane16LoopExecutionTest.kt`
- `docs/performance-lanes/16-loop-execution.md`

Experimented with, then restored:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`

The rejected patch can be reconstructed by comparing local snapshots
`/tmp/quackjs-perf32/16-Runtime-before.kt` and `16-Runtime-after.kt`.

## Real-player measurements

Unmodified playerBenchmark, Java 21.0.12.1, Kotlin JVM; medians in milliseconds.
Each cell is **cold compile + first signature/n / warm 496-transform batch**.
Shared baselines are the two pre-lane runs. Local baseline and candidate 2 ran
back-to-back under one validation-lock acquisition, using the same worktree and
new tests, with only Runtime.kt temporarily restored/reapplied.

| Player | Shared baseline 1 | Shared baseline 2 | Candidate 1 | Local baseline | Candidate 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 117.054 / 50.588 | 229.117 / 79.471 | 198.203 / 90.201 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 141.687 / 44.771 | 235.709 / 76.804 | 223.630 / 66.817 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 131.850 / 44.051 | 221.271 / 68.489 | 197.386 / 79.692 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 121.683 / 37.711 | 192.822 / 67.535 | 234.611 / 70.296 |

Candidate 1 warm times versus shared baseline 2 ranged from 8.4% lower to 1.4%
higher. Candidate 2 versus the local baseline ranged from 13.0% lower to 16.4%
higher, with three of four warm medians higher. Cold results also reversed for
some players. These comparisons are observations, not an established speedup or
slowdown: substantial host noise remained despite locking all heavy commands.
For example, QuickJS warm time for 4f38b487 moved from 55.177 in candidate 1 to
66.469 in the local baseline and 87.468 in candidate 2. QuickJS code was unchanged.
No microbenchmark or profiler result is used as an acceptance claim.

Logs, all under `/tmp/quackjs-perf32/`:

- Shared: `baseline-benchmark-1.log`, `baseline-benchmark-2.log`.
- Candidate 1: `16-tests.log`, `16-benchmark.log`.
- Paired unmodified runtime: `16-local-baseline-tests.log`,
  `16-local-baseline-benchmark.log`.
- Candidate 2: `16-benchmark-2.log`, `16-tests-final.log`.
- Initial failed test setup: `16-tests-fixture-failure.log`. SourceIndex requires
  an n-class assignment; the new minimal fixture was corrected before successful
  tests or any candidate benchmark. This was not counted as a pass.

## Verification

Every Gradle command used the shared lock and these flags:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/16-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/16-benchmark.log 2>&1
'
```

The second lock acquisition ran baseline jvmTest, baseline playerBenchmark,
candidate playerBenchmark, and candidate jvmTest in that order; source snapshots
were restored inside the lock with an exit trap. Final Runtime.kt restoration
was checked byte-for-byte against the tested baseline snapshot. No benchmark
code, timing boundaries, or Gradle configuration changed.

All **54 JVM tests passed** with both baseline and candidate runtimes:

- Five new common Lane16 tests cover dynamic bounds and test/body/update ordering;
  body writes to induction variables; continue, break, return, labeled break and
  switch propagation through nested blocks; while/do/for and non-block bodies;
  try/finally and thrown-value handling; string/null/NaN induction and Double
  precision at 2^53.
- Exact budget test checks all 0..7 remaining-step boundaries for a loop with a
  Block, side-effecting expression and break. It checks whether the mutation
  happened and that the budget is exhausted, rather than merely checking that an
  infinite loop eventually fails.
- Existing common budget, unsupported-operation, mutation and fallback rejection
  tests passed, as did JVM concurrency/call-isolation and all four full-player
  parity tests. Each benchmark also passed all 124 Node/QuickJS golden cases
  before printing timings.

## Integration and unchecked work

Recommend keeping the tests as extra guardrails for other execution lanes, but
**do not integrate the rejected Runtime split as a performance change**. The test
filename is lane-specific, and there is no retained production-file conflict.
Runtime changes from other lanes should preserve the newly asserted budget and
control-flow boundaries; no interaction with them was measured here.

Android, Linux Native, iOS, consumer repositories and the parent's combined
patch were not built or benchmarked. No further induction specialization or
profiler investigation was attempted after the small candidate lacked a
repeatable real-player gain. Parent review and final platform verification are
still required; this worker did not run its own Codex review.
