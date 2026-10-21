# Lane 27: guarded per-character n-table callback

## Hypothesis and scope

All four real fixtures contain the same structural callback, with different
identifiers, dispatcher numbers, string-table offsets and alphabet generation:

```js
function(value, index, array) {
  this[pushKey](array[index] = alphabet[
    (alphabet[indexOfKey](value) - alphabet[indexOfKey](this[index])
      + index + remaining--) % alphabet[lengthKey]
  ])
}
```

The hot part is repeated interpreter descent and captured/key lookups, not the
arithmetic itself. Recognize exactly this tree once per parsed function and run
a narrow Kotlin plan. No player identifiers, offsets, alphabets, challenge
lengths, dispatcher values or output values are embedded in production code.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/HotCharacterPlan.kt`: 103-line recognizer,
  live guards and execution path.
- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`: immutable per-function plan.
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: invocation hook and internal
  access to the existing budgeted property operation.
- `src/commonTest/kotlin/meow/nyx/quackjs/HotCharacterPlanLane27Test.kt`: six
  common regressions.
- This report. No fixture, oracle, Gradle, integration or public API changes.

## Safety boundary

Only the exact single-expression callback is accepted. Method keys must be
literal strings or array reads with literal/numeric-XOR indexes. Keys are
checked against live lexical bindings on every invocation; no global resolution,
coercion, JS calls or writes happen during guards. A changed method key,
overridden array method, missing binding, wrong receiver/value/index/counter
type, extra statement, changed arithmetic or unsupported key falls back to the
existing interpreter before mutation.

The plan proves that the receiver, source and alphabet are arrays, the index is
an existing integer source index, the input is one UTF-16 unit, and the counter
is a Double. It does not assume alphabet contents are immutable or strings.
Native `indexOf` and `push`, and the existing property reader, retain hole,
Undefined, coercion, size, search-work, native-call allocation and call-depth
behavior. The counter write stays before the length/property reads. All array
reads precede both array writes, so receiver/source/alphabet aliases are allowed
and tested. Key expressions cannot depend on the written counter.

The omitted syntax/key evaluations are charged exactly (25 fixed syntax steps
plus 1/4/6 per key shape). Function frame setup and native calls remain in the
runtime. Near the expression-depth boundary the plan falls back to the normal
checks, conservatively reserving eleven complex-expression levels for XOR keys.
Unsupported exceptions remain uncatchable by JS. No mutable invocation state
lives in a plan.

## Measurements

OpenJDK Temurin 21.0.12.1, Linux amd64, same shared host. All heavy commands held
`/tmp/quackjs-perf32.validation.lock`. Times are milliseconds, not startup times.

Standard `playerBenchmark`, unchanged boundaries: cold = compile + first
signature/n pair, warm = 496 mixed transforms. Shared pre-lane baselines were
also inspected (`baseline-benchmark-1.log`, `baseline-benchmark-2.log`). For a
closer local control, temporarily disabled only plan creation and its invocation
hook, leaving an unused nullable FunctionExpr field so test sources still
compiled. Restored both before final verification. This control executes the
original interpreter, not a different player or cached output.

| Player | Shared baseline 2 cold / warm | Local control cold / warm | Final cold / warm | Local warm reduction |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 121.488 / 53.073 | 114.129 / 50.113 | 112.833 / 45.025 | 10.2% |
| 10733253 | 134.351 / 45.793 | 124.582 / 43.340 | 126.151 / 40.367 | 6.9% |
| eb27e5fd | 138.892 / 43.456 | 122.495 / 42.428 | 126.584 / 39.359 | 7.2% |
| 5008be0a | 142.167 / 41.172 | 122.199 / 37.753 | 121.898 / 34.366 | 9.0% |

No cold win established. Host noise is visible in QuickJS as well; another
candidate run before the local control had warm times 50.705 / 41.494 / 39.933 /
33.992. Do not treat percentage precision as a confidence interval.

An additional paired, same-JVM real-player **n-only** measurement alternated
fast/interpreter runs: 30 warmups, 31 samples, 240 transforms per sample. The
interpreter control inserts one EmptyStmt into the matching callback, preventing
recognition without changing its JS result. The source replacement matched
exactly one callback per player; files on disk were untouched. Every measured
result was checked against the real-player goldens outside timing.

| Player | Interpreter n batch | Plan n batch | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 57.162 | 49.109 | 14.1% |
| 10733253 | 49.561 | 45.836 | 7.5% |
| eb27e5fd | 47.202 | 43.245 | 8.4% |
| 5008be0a | 40.583 | 35.647 | 12.2% |

The first prototype retained interpreted key expressions and showed only about
2–5% n-only improvement; the retained version guards/evaluates those narrow keys
without recursively interpreting them, preserving their exact step charges.

## Verification and logs

Final commands:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/27-review-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/27-review-benchmark.log 2>&1
'
```

Both passed. Final suite: **55 tests, zero failures**, including full-player
124-golden Kotlin/QuickJS parity and concurrent independent calls. Lane tests
cover literal/indexed/XOR keys, all receiver/source/alphabet alias combinations,
sparse/mixed alphabets, missing matches, negative/huge/NaN/infinite counters,
150 seeded randomized callbacks, unsupported shapes/types/live keys/method
overrides, exact step and allocation exhaustion for both key families,
fractional-property rejection, array growth and expression-depth rejection, and
60 seeded randomized real-slice UTF-16 challenges against the interpreter.

Review follow-up tightened the plan's entry threshold from 246 to 245, reserving
one more level for the XOR-key shape. The added common regression compares
literal, indexed and XOR method keys with the interpreter at callback entry
depths 244–249, checking both successful results and depth-limit rejection.
The post-fix standard benchmark passed all goldens and measured cold/warm:

| Player | Post-review cold / warm |
| --- | ---: |
| 4f38b487 | 112.289 / 43.642 |
| 10733253 | 121.800 / 39.474 |
| eb27e5fd | 122.426 / 38.401 |
| 5008be0a | 121.910 / 33.534 |

These are another noisy host sample, not a claim that the guard fix improves
speed. The paired n-only experiment was not repeated for this guard-only fix.

Other evidence under `/tmp/quackjs-perf32/`:

- `27-local-baseline-benchmark.log`: disabled-plan standard control.
- `27-benchmark.log`: preceding retained candidate benchmark.
- `27-n-paired.xml`: paired n-only medians and successful test result.
- `27-NHotMeasurementLane27Test.kt`: temporary measurement source, removed from
  the final tree; run with locked `jvmTest --tests '*NHotMeasurementLane27Test'`.
- `27-n-paired.log`, `27-n-initial.xml`: paired harness run and initial prototype.
- `27-tests-initial.log`, `27-tests-fractional.log`,
  `27-tests-depth-fixture.log`: unsuccessful early fixture checks, corrected
  before final validation. These are not reported as passes.

`git diff --check` also passed.

## Recommendation and interactions

Recommend considering this small standalone optimization: paired real n-only
wins are consistent across four players, and the final unchanged benchmark
shows a warm gain. Rebenchmark the parent's selected combination before merging.
This competes with lane15's generic expression plans; do not stack overlapping
execution hooks blindly. FunctionExpr metadata, Runtime.invoke and property
visibility are the only shared integration points. Changes to the recognized
tree or interpreter accounting require updating its exact-budget regressions.

Android, Kotlin/Native Linux and iOS were not run here. No consumer verification,
Codex review, publication or integration was performed by this worker; those
remain with the parent. No wider JS subset, generalized expression compiler,
lookup-result cache or output cache was added.
