# Lane 28: single-pass template copying

## Hypothesis and patch

Closed templates were recursively mapped into a temporary list/map, then copied
again into the mutable JavaScript container. Build the destination directly and
build the compile-time template map without an intermediate filtered map.

Production paths touched:
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: `copyTemplate` creates one
  destination list/map. Arrays still charge their full size before construction;
  nested arrays, objects and functions retain their individual allocation charges.
- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`: one-pass template-map
  construction; `templateSafe`, evaluation/depth checks and source mutation proofs
  are unchanged.

Regression paths:
- `src/commonTest/kotlin/meow/nyx/quackjs/TemplateCopyingLane28Test.kt`
- `src/jvmTest/kotlin/meow/nyx/quackjs/TemplateCopyingConcurrencyLane28Test.kt`

Function wrappers deliberately remain fresh. `JsFunction` carries a mutable
runtime-global `Env`; sharing it across calls is unsafe, and interning distinct
functions could change JavaScript identity. Immutable function code and primitive
leaf values continue to be reused, while containers and environments are private.

## Real-player measurements

Same benchmark and timing boundaries as the baseline, Temurin 21, Linux x86_64.
Each cell is **cold compile + first signature/n / warm 496-transform batch**, in
milliseconds. All full-player goldens passed before every benchmark.

| Player | Shared baseline 1 | Shared baseline 2 | Local baseline | Patch 1 | Patch 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 115.406 / 49.474 | 117.774 / 51.123 | 143.729 / 54.800 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 124.338 / 43.892 | 137.013 / 45.643 | 141.849 / 44.429 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 131.929 / 53.199 | 134.794 / 47.460 | 130.131 / 108.379 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 130.900 / 43.959 | 137.177 / 42.945 | 177.358 / 41.237 |

No stable wall-time gain is established. Patch 2 was particularly noisy: its
`eb27e5fd` QuickJS warm median also rose to 106.657 ms, versus 62.590 ms in the
local baseline and 52.410 ms in patch 1. Do not interpret this as a throughput win.

A temporary JVM inspection test measured current-thread allocated bytes for all
31 golden transforms of each actual player, checking every output. Medians of
31 samples after 100 warmup suites, baseline then patch in separate test JVMs:

| Player | Baseline bytes | Patch bytes | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 6,988,544 | 6,786,672 | 2.89% |
| 10733253 | 5,725,240 | 5,441,176 | 4.96% |
| eb27e5fd | 5,306,816 | 4,981,928 | 6.12% |
| 5008be0a | 5,128,384 | 4,861,520 | 5.20% |

These are whole-transform allocation measurements, not retained memory or a
container-only microbenchmark. The inspection test was removed from the patch;
its source and XML output remain in the log directory below.

## Verification and commands

All Gradle builds, tests, benchmarks and allocation measurements used the shared
`/tmp/quackjs-perf32.validation.lock` via `flock`, with `--no-daemon --max-workers=2`.

Initial patched validation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/28-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/28-benchmark.log 2>&1
'
```

Paired validation restored only the two production files to the lane baseline,
ran tests/allocation inspection and the real-player benchmark, then restored the
patch and repeated both, under one lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash /tmp/quackjs-perf32/28-paired-validation.sh
```

Logs and retained inspection artifacts in `/tmp/quackjs-perf32/`:
- `baseline-benchmark-{1,2}.log`: shared baselines.
- `28-tests.log`, `28-benchmark.log`: first successful patched validation.
- `28-local-baseline-tests.log`, `28-local-baseline-benchmark.log`.
- `28-paired-tests.log`, `28-benchmark-2.log`.
- `28-allocation-before.xml`, `28-allocation-after.xml`,
  `28-TemplateAllocationLane28InspectionTest.kt`.
- `28-tests-final.log`: final suite after removing the inspection test.
- `28-tests-fixture-failure.log`: initial new-fixture failures, corrected before
  successful validation; no production workaround was needed.

Checks: JVM common tests, 124 full-player Node/QuickJS goldens, existing real-player
concurrency, plus Lane28 regressions for nested object/array independence, empty
arrays, lazy aliases, live global reads after rebinding, distinct function
identity/environments, captured closures, exact nested allocation accounting and
excessive template depth rejection. Lane28 concurrency executes 1,280 signature/n
calls through one program on eight threads. The final suite has 54 passing tests.

The synthetic n fixture uses a pure long-input branch so discovery's repeated
probes do not consume the mutable state being tested by subsequent short calls.
Existing real-player tests still cover normal probes and arbitrary golden inputs.

## Recommendation and limitations

Recommend integrating as a small allocation reduction, not as a demonstrated
latency/throughput improvement. The production diff is five insertions and three
deletions. No new dependency, API, cache, option or supported-JS behavior.

Potential integration conflict: other lanes changing `Runtime.copyTemplate` or
`PlayerProgram.compile` need to preserve their budget checks and the unchanged
closed-template eligibility proof. No parser, source-analysis, fixture, benchmark,
Gradle or consumer files were changed.

Android, Kotlin/Native, iOS, retained-memory/energy measurements and consumer
verification were not run here. Parent review and final platform checks remain.
