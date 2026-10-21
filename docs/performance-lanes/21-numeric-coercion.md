# Lane21: numeric coercion

## Result and scope

**Do not integrate a production optimization from this lane.** Decimal parsing
and trimming are not hot in this n corpus, and the smallest stdlib conversion
candidate did not establish a real-player gain. Keep the independent regressions
and this report. Production sources are identical to baseline `732ee13`.

Retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/NumericCoercionLane21Test.kt`
- `docs/performance-lanes/21-numeric-coercion.md`

Investigated `numericString`, `number`, `int`, `uint`, `integer`, `relative`, and
`text` in `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`, their runtime
wrappers and callers, and the compile/discovery/specialization callers. No API,
fixture, oracle, Gradle, budget, or supported-subset changes remain.

## Hypothesis and profile

Hypothesis: decimal regex validation, JS-whitespace trimming, or repeated number
conversion might materially cost n transforms. Before changing behavior, a
**temporary**, removed JVM test ran all 15 n goldens for each player, 20 warmup
passes and 1,000 recorded passes (60,000 measured n transforms). JFR used the
profile configuration with 1 ms execution sampling; recording also includes
fixture loading, compilation, and warmups, so this is not an isolated steady-state
latency result.

Of 8,891 execution samples, 7,748 contained Runtime frames. Inclusive coercion
sample counts were 82 for `number` (1.1% of Runtime samples), 53 for `int` (0.7%),
and nine for `integer` (0.1%); these counts overlap. No sample included
`numericString` or `text`. Hash-map lookup and interpreter evaluation/reference
work dominated instead.

Separate temporary call counters, cleared after each player compilation, checked
all 60 n goldens once: **198,590 number calls, 198,155 with Double inputs, zero
String inputs**. The remaining 435 inputs were Undefined or other types. Each
output was checked against the independent goldens. Thus this corpus provides no
case for rewriting decimal grammar, skipping trim, or approximating integer math.
This observation does not imply arbitrary players or cold compilation never
coerce strings.

The only candidate measured was replacing `integer`'s explicit NaN/clamp checks
with `number(value).toInt()`. Kotlin's documented
[`Double.toInt()` contract](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/-double/to-int.html)
already truncates toward zero, maps NaN to zero, and saturates outside Int range.
This left double parsing, negative zero, ToInt32/ToUint32 wrap, unsupported
coercions, and all resource charging untouched. It passed correctness checks but
showed no useful throughput improvement, so it was reverted rather than adding a
performance claim below measurement noise.

## Full-player measurements

Same unmodified `playerBenchmark` boundaries, JVM heap/settings, and 124
Node/QuickJS goldens. Temurin 21.0.12.1, Linux x86_64, Ryzen 9 7950X3D,
32 logical CPUs. Values below are median milliseconds **Kotlin / QuickJS**;
cold is compile + first signature/n, warm is 496 transforms.

| Player | Local baseline cold | Candidate cold | Local baseline warm | Candidate warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 114.840 / 254.070 | 113.308 / 248.830 | 49.316 / 52.698 | 49.974 / 53.889 |
| 10733253 | 124.772 / 255.350 | 123.606 / 260.043 | 42.447 / 43.956 | 42.975 / 44.838 |
| eb27e5fd | 124.397 / 268.659 | 124.943 / 267.655 | 41.745 / 47.343 | 44.575 / 50.744 |
| 5008be0a | 121.313 / 266.603 | 132.253 / 273.588 | 36.540 / 38.816 | 37.512 / 39.066 |

Candidate warm results were 1.2–6.8% slower, with QuickJS also slowing in all
four warm comparisons. A single pair cannot establish a causal regression, but
there is no evidence of a gain and no reason to retain the change. Both local
runs were generally faster than the older shared baselines; the unchanged local
baseline demonstrates why that is not an optimization result. No second
candidate run was justified. No final-state speedup is claimed.

## Correctness and commands

Four new common tests cover 89 Node v26.11.1 coercion cases (each checked through
both String/primitive and resulting Double paths), exact double bits including
negative zero and decimal rounding ties, safe-integer boundaries, overflow and
underflow, signed/unsigned 32-bit wrap, NaN/Infinity, ASCII decimal grammar,
radix/sign/exponent rejection, all 25 JS whitespace characters, eight supported
number-to-string goldens, rejection of unsupported coercions, and coercion step
charging. Existing budget, isolation, concurrency, full-player Kotlin parity and
original QuickJS oracle tests are included in the full JVM suite.

All Gradle commands and the n-profile JFR extraction/processing used
`flock /tmp/quackjs-perf32.validation.lock`; builds respected other lanes' lock.
Commands inside that shared lock:

```sh
./gradlew --no-daemon --max-workers=2 jvmTest --tests '*NumericProfileLane21Test*' --console=plain
jfr print --json --events jdk.ExecutionSample /tmp/quackjs-perf32/21-before-n.jfr
./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain
./gradlew --no-daemon --max-workers=2 jvmTest --console=plain
```

Local investigation artifacts under `/tmp/quackjs-perf32/`:

- `21-profile.log`, `21-profile.xml`, `21-before-n.jfr`, `21-before-n.json`,
  `21-profile-summary.txt`, `21-NumericProfileTest.kt`: baseline n profile.
- `21-counts.log`, `21-counts.xml`, `21-counts-test.kt`,
  `21-instrumentation.patch`: exact n call-count experiment, removed afterward.
- `21-oracle.mjs`, `21-oracle.txt`: independent expected values generated with
  `node /tmp/quackjs-perf32/21-oracle.mjs`; double bits use DataView.getBigInt64,
  and expected conversions use `n|0`, `n>>>0`, and clamped `Math.trunc(n)`.
  `21-oracle-whitespace-text.log` records independent Node whitespace/text checks.
- `21-baseline-benchmark.log`: unchanged local baseline, all goldens passed.
- `21-integer-candidate.patch`, `21-tests.log`, `21-candidate-test-results/`,
  `21-benchmark.log`: candidate, 53 JVM tests passed without skips, all benchmark
  parity and timed-result checks passed.
- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared pre-lane results.
- `21-tests-initial.log`: initial regression-test setup failure (a SourceIndex
  without its required class); fixed the fixture before the passing suite and
  benchmark, not a production failure.
- `21-final-tests.log`, `21-final-test-results/`: final JVM suite after reverting
  the candidate: 53 tests passed, zero failures/errors/skips (exit status 0).

No production integration conflicts. The lane-numbered common test is additive
and can accompany other runtime lanes. No Android, Kotlin/Native, iOS, consumer,
live playback, or device runs were performed; those remain parent verification.
No custom decimal parser, approximate arithmetic, dependencies, or production
instrumentation were retained.
