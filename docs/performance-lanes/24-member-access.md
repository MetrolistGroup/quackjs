# Lane 24: member access

## Decision

Do not integrate a runtime optimization from this experiment. Retain the focused
common regressions only. Literal-key metadata did not establish a real-player
speedup, and an unboxed numeric-index variant did not establish a gain either.
Production code is unchanged from `732ee13` (`a7e66f5` runtime).

## Hypothesis and scope

The first candidate attached immutable metadata to string-literal `Member` keys:
the canonical index and the receiver-driven native array/string method. Reads,
member calls and compound-assignment reads used the metadata instead of repeated
index parsing/native-method map lookup. Own array fields were still checked first;
null errors, UTF-16 indexing, holes, evaluation order and all existing step charges
were retained. Nothing cached a receiver, mutable field, shape, or global result.
It also returned an `Int` sentinel from `numericIndex` to avoid nullable boxing and
repeated conversion. A second candidate tried only that numeric change.

Experimental production paths, both restored before committing:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`

Retained paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/MemberAccessLane24Test.kt`
- `docs/performance-lanes/24-member-access.md`

The original `property`, `get`, `numericIndex`, reference writes, member calls,
AST construction/substitution/specialization, and direct `Runtime.get` test callers
were inspected. No public API, global resolution, fixtures, benchmark boundaries,
configuration or dependencies changed.

## Real-player measurements

JVM medians in milliseconds, with **cold / warm batch** in each cell. Cold is
compile plus first signature+n; warm is 496 transforms. Each completed run used
the unchanged full benchmark: 3 cold warmups/11 samples, 20 warm warmups/31 samples,
and all four players' 124 goldens validated against Kotlin and QuickJS first.

Shared pre-lane baseline:

| Player | Baseline 1 cold / warm | Baseline 2 cold / warm |
| --- | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 |

Literal metadata plus numeric sentinel candidate:

| Player | Candidate initial cold / warm | Local baseline cold / warm | Candidate paired cold / warm |
| --- | ---: | ---: | ---: |
| 4f38b487 | 114.296 / 50.906 | 111.461 / 50.592 | 115.328 / 51.234 |
| 10733253 | 121.001 / 44.151 | 121.391 / 42.588 | 146.573 / 46.596 |
| eb27e5fd | 141.345 / 43.075 | 122.699 / 42.015 | 128.370 / 41.716 |
| 5008be0a | 124.702 / 42.577 | 120.001 / 37.475 | 124.380 / 37.311 |

The local baseline and paired candidate ran consecutively under one lock, in
separate JVMs. Versus that baseline, paired warm changes were +1.3%, +9.4%, -0.7%
and -0.4%; cold was slower for every player. Host noise clearly mattered: paired
10733253 QuickJS warm time also rose from 45.706 to 49.572 ms. Even relative to
QuickJS there is no consistent gain. The initial apparent improvement over the
older shared baseline is not evidence of an optimization.

The numeric-only attempt passed tests, but its benchmark was interrupted with
`Gradle build daemon has been stopped: stop command received`. This lane never
issued a stop command. Partial results are **not a completed benchmark**:

| Player | Numeric-only cold / warm |
| --- | ---: |
| 4f38b487 | 123.142 / 59.923 |
| 10733253 | 128.208 / 43.897 |
| eb27e5fd | 120.976 / 41.669 |
| 5008be0a | not completed |

Those partial results do not establish a gain, so the numeric-only change was
also discarded rather than adding an unproven optimization.

## Verification and logs

Every Gradle invocation was serialized using
`flock /tmp/quackjs-perf32.validation.lock`. Commands within the lock:

```sh
./gradlew --no-daemon --max-workers=2 jvmTest --console=plain
./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain
```

Logs under `/tmp/quackjs-perf32/`:

- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared baseline.
- `24-tests.log`, `24-benchmark.log`: successful first complete candidate run.
- `24-local-baseline-tests.log`, `24-local-baseline-benchmark.log`: original
  production code with the four new tests; both passed.
- `24-tests-paired.log`, `24-benchmark-2.log`: successful paired candidate run.
- `24-numeric-tests.log`: numeric-only tests passed.
- `24-numeric-benchmark.log`: interrupted run, not a pass.
- `24-retained-tests.log`: final restored production code, all 53 tests passed.
- `24-tests-initial.log`, `24-tests-fixture-failure.log`,
  `24-tests-array-bound-failure.log`: early test-authoring failures, subsequently
  fixed (Kotlin assertions/parentheses, missing minimal n class, forbidden array
  index assignment in the test setup).

Final JVM suite includes 47 common tests, four real-player parity tests, the
QuickJS oracle suite and the concurrent-invocation test. New common checks cover:

- One member AST reused across distinct arrays, strings and objects, including
  live native-method overrides with a function, `null` and `undefined`, deletion,
  and invocation on another receiver.
- Canonical and noncanonical indices, holes versus explicit null, missing/outside
  indices, string UTF-16 surrogate units, and receiver-dependent `length`.
- Numeric negative zero, indices above the JVM small-integer cache, NaN,
  infinities, rejected fractional coercion, assignments/compound assignments,
  and rejected out-of-budget array indices.
- Null/undefined errors, unsupported native properties, and exact literal-key
  length step charging. Existing resource-limit/rejection tests also passed.

Rejected production patches are saved locally as
`24-literal-metadata-candidate.patch` and `24-numeric-candidate.patch` for inspection,
not integration.

## Integration and unchecked work

Recommend integrating the tests/report only. There are no retained production
conflicts; the unique Lane24 test filename avoids other lanes' additions.
Any future metadata implementation must retain live field precedence and key
length charges and remeasure its extra AST allocation/dispatch cost. This lane
made no shape cache, global-resolution change or generic inline-cache framework.

Android, Kotlin/Native Linux, iOS, consumer repositories, memory/energy and hardware
profiles were not checked. No performance claim is made for those platforms or
for the restored production implementation. Parent performs the automated review
and any final cross-platform/integration verification.
