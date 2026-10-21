# Lane 13: control-flow allocation

## Hypothesis and scope

`Returned` wraps every explicit JavaScript return. Its payload can instead travel
on the existing Kotlin call stack: `null` is normal completion, a `Jump` AST node
is a jump, an immutable `ReturnedNull` encodes a JavaScript null return, and every
other value is a returned JavaScript value (including `Undefined`, false, zero,
and the empty string). Only `invoke` decodes this protocol. JavaScript cannot
produce a `Jump` AST node or the private marker as a value.

This removes the wrapper construction without adding mutable return state,
per-call carriers, exception-based returns, or retained payloads in environments.
The existing local `flow` in `try` keeps its pending value independently of nested
calls and finalizers. Existing catch/finally override and jump handling is left
intact. Block, variable-declaration, and switch-body statement lists use indexed
traversal instead of iterators. All execution/allocation budget calls remain.

Exact touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/ControlFlowLane13Test.kt`
- `docs/performance-lanes/13-control-flow-allocation.md`

No parser, AST, public API, benchmark, fixture, dependency, or configuration change.
`Runtime.exec`'s only non-runtime caller executes constant declaration prefixes
in n discovery and ignores the result; it needs no change.

## Measurements

Full `playerBenchmark` completed successfully. Times are medians in milliseconds,
shown as **Kotlin / QuickJS**, using the unchanged benchmark: cold 11 samples after
3 warmups; warm 31 samples after 20 warmups, 496 transforms per batch. Baseline is
the supplied pre-lane pair, not historical README numbers.

Cold compile + first signature/n:

| Player | Before 1 | Before 2 | Lane 13 |
| --- | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 279.818 | 121.488 / 265.894 | 121.302 / 268.542 |
| 10733253 | 135.474 / 284.865 | 134.351 / 277.667 | 119.009 / 263.732 |
| eb27e5fd | 135.206 / 273.167 | 138.892 / 281.623 | 126.260 / 271.357 |
| 5008be0a | 145.202 / 285.821 | 142.167 / 278.093 | 132.321 / 286.204 |

Warm batch:

| Player | Before 1 | Before 2 | Lane 13 |
| --- | ---: | ---: | ---: |
| 4f38b487 | 57.042 / 59.834 | 53.073 / 57.597 | 50.686 / 54.566 |
| 10733253 | 48.456 / 50.129 | 45.793 / 47.569 | 42.242 / 44.600 |
| eb27e5fd | 47.711 / 53.438 | 43.456 / 49.018 | 42.558 / 48.420 |
| 5008be0a | 38.905 / 41.128 | 41.172 / 43.121 | 39.117 / 43.000 |

Kotlin warm times are 2.1–7.8% lower than baseline 2, but `5008be0a` is 0.5%
slower than baseline 1. QuickJS timings also moved. This single after run against
shared earlier baselines does not establish a reliable throughput gain; no paired
baseline, repeat, or independent timing of the two changes was performed.

Raw baseline logs: `/tmp/quackjs-perf32/baseline-benchmark-1.log` and
`/tmp/quackjs-perf32/baseline-benchmark-2.log`. Lane logs:
`/tmp/quackjs-perf32/13-tests.log`, `/tmp/quackjs-perf32/13-benchmark.log`;
`/tmp/quackjs-perf32/13-validation.exit` contains `0`.

## Verification

Executed under the mandatory shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/13-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/13-benchmark.log 2>&1
'
```

**55 JVM tests passed, zero failures/errors/skips**, including all six new common
tests. Both the JVM parity suites and the benchmark passed all four full players'
124 saved Node/QuickJS goldens. `git diff --check` also passes.

Six common regression tests cover falsy/null/undefined returns, object/function
identity, nested recursion, reused callback frames, normal and overriding
finalizers, catch returns/rethrows, pending throws, switch/loop/label propagation,
break/continue overriding returns, declaration ordering, invalid jumps, and
uncatchable execution-budget failures. The full JVM suite also checks the four
real-player parity suites, QuickJS goldens, resource and unsupported-input tests,
and 32 concurrent callers of shared compiled players.

The initial test run failed because the new test helper supplied an empty source
to `SourceIndex`, which requires an n-class declaration. Its six failures are in
`/tmp/quackjs-perf32/13-tests-initial.log`; all existing tests passed. The helper
now supplies a minimal unused class declaration. This was a test setup fix, not
a runtime change.

## Integration and limits

Recommend integrating as a small allocation-reduction candidate after parent
review and combined rebenchmarking. Wrapper construction is removed without
introducing mutable completion state; the real-player results are encouraging,
but are not evidence of a universal speedup.

The production patch touches `Runtime.invoke` return decoding and `Runtime.exec`
statement traversal, which may overlap other runtime lanes. There is no Env,
function-frame, specialization, or player-discovery change to reconcile.

Android, Linux Native, iOS, consumer builds, allocation profiling, retained memory,
and hardware execution are not checked in this lane. No measured allocation-byte
or cross-platform throughput claim is made; wrapper construction is removed by
inspection. Parent owns automated review and final combined-platform validation.
