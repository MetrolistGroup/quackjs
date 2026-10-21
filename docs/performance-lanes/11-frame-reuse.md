# Lane 11: function-frame initialization and reuse

## Outcome

No production optimization retained. A small lean-initialization experiment was
correct but did not establish a real-player performance win. Keep the existing
nonescaping `forEach` reuse; do not add a general pool without better evidence.
This commit retains six focused regression tests and this report.

Baseline checkout: `732ee13`, whose runtime is `a7e66f5` (the extra commit only
adds `PERFORMANCE_LANES.md`).

## Hypothesis and scope

Fresh `Env` construction fills every slot with `Undefined` using a Kotlin loop.
The experiment stored a private, Undefined-only array on each `FunctionExpr` and
used `copyOf()` for each fresh frame. This replaced per-invocation fill work with
an array copy without sharing mutable invocation slots. It still allocated a
fresh `Env` and array, and left allocation/step charges, parameter assignment
order, `arguments`, names, and existing `forEach` resets unchanged. The cost was
one additional retained slot array per parsed function.

Inspected all `Env`, `functionEnv`, `invoke`, slot, `usesArguments`, and
`hasClosures` callers, including direct discovery frames in `PlayerProgram.kt`,
specialization, `.call`/`.apply`, exception scopes, and native callbacks.

A broader per-Runtime pool was considered, not implemented or measured. It would
need active-frame exclusion across recursion/native reentry, escape restrictions,
exception-safe release, correct lexical parents, and separate handling of direct
discovery frames. Regular calls must still consume their existing allocation
budget even when physically reused. There is no evidence here that the extra
pool/lease bookkeeping is worthwhile; the existing `forEach` reuse already covers
a frequent repeated-call path.

Exact retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/Lane11FrameReuseTest.kt`
- `src/jvmTest/kotlin/meow/nyx/quackjs/Lane11FrameConcurrencyTest.kt`
- `docs/performance-lanes/11-frame-reuse.md`

Experiment-only paths, restored byte-for-byte to HEAD:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`

The rejected production diff is saved locally as
`/tmp/quackjs-perf32/11-lean-frame-candidate.patch`.

## Real-player measurements

All times are Kotlin medians in milliseconds. Cold means compile plus first
signature/n pair; warm means 496 transforms. Unmodified benchmark boundaries:
3/11 cold warmups/samples, 20/31 warm warmups/samples. Every benchmark first passed
all four full-player golden suites in Kotlin and QuickJS.

Shared baseline reference, runs 1 / 2:

| Player | Cold baseline | Warm baseline |
| --- | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 57.042 / 53.073 |
| 10733253 | 135.474 / 134.351 | 48.456 / 45.793 |
| eb27e5fd | 135.206 / 138.892 | 47.711 / 43.456 |
| 5008be0a | 145.202 / 142.167 | 38.905 / 41.172 |

Execution order was candidate 1, local baseline, candidate 2:

| Player | Candidate 1 cold / warm | Local baseline cold / warm | Candidate 2 cold / warm |
| --- | ---: | ---: | ---: |
| 4f38b487 | 115.570 / 51.230 | 121.862 / 58.212 | 143.533 / 59.488 |
| 10733253 | 171.655 / 46.127 | 132.041 / 48.821 | 149.543 / 46.575 |
| eb27e5fd | 131.348 / 42.174 | 134.075 / 48.400 | 143.207 / 43.824 |
| 5008be0a | 151.997 / 39.611 | 136.921 / 42.781 | 145.745 / 45.423 |

The first warm run looked mildly promising against the shared baseline, so a
local comparison followed. Candidate 2 versus local baseline changed warm times
by +2.2%, -4.6%, -9.5%, and +6.2%; cold times rose 17.8%, 13.3%, 6.8%, and 6.4%.
Host noise also affected QuickJS (for example 5008be0a warm changed from 43.945 to
47.962 ms), so these are observations, not attributable speedup/regression claims.
The results do not justify extra retained metadata or a production change.

## Verification and commands

Every Gradle/benchmark command ran under
`flock /tmp/quackjs-perf32.validation.lock`, with `--no-daemon --max-workers=2`
and `--console=plain`. No dependency, fixture, benchmark, or build configuration
changes were made.

Initial candidate:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/11-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/11-benchmark.log 2>&1
'
```

After restoring production files to baseline, ran the same locked benchmark into
`/tmp/quackjs-perf32/11-local-baseline-benchmark.log`. Reapplied the candidate and
ran the initial command with outputs `11-final-tests.log` and
`11-benchmark-2.log`; the test task was up-to-date against the first candidate
run. Finally dropped the candidate and executed the locked `jvmTest` command into
`/tmp/quackjs-perf32/11-retained-tests.log` (tests actually executed).

Shared reference logs:
`/tmp/quackjs-perf32/baseline-benchmark-1.log` and
`/tmp/quackjs-perf32/baseline-benchmark-2.log`.

Both the candidate and final unchanged runtime passed **55 JVM tests**, with no
failures, errors, or skips. Coverage includes:

- New common tests: distinct fresh slot arrays, null versus Undefined, missing
  duplicate parameters, named-function and receiver bindings, recursive active
  locals, native reentry through exceptions/finally, returned/thrown closures,
  retained arguments, and explicit frame allocation/initialization step budgets.
- New JVM test: shared function code across 16 tasks / 512 independent runtimes,
  retaining closures, nulls, Undefined locals, and per-call input values.
- Existing public-API independence, all real-player Node/QuickJS parity tests,
  and concurrent compiled-player transforms.
- Existing function-frame allocation rejection, call-depth and traversal/step
  limits, fail-closed unsupported operations, and empty/sparse `forEach` callback
  regressions. No budget or rejection behavior was relaxed.

## Integration and limits

Recommend **against integrating the production candidate**. The retained tests
and report are independently useful and do not conflict with production edits in
other lanes; test filenames include Lane11. No production/API behavior changes.

Not checked: Android host/device, Kotlin/Native/Linux, Apple targets, consumer
builds, retained-memory profiling, or hardware/energy effects. No general pool
was benchmarked. Parent owns final cross-platform verification and combined-lane
measurements.
