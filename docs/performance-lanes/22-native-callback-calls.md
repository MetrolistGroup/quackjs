# Lane22: native callback calls

## Hypothesis and scope

`Array.forEach` constructed a three-element argument list for every visited
index, and initialized reusable callback bindings once before `invoke`
immediately cleared and initialized them again. Reuse one traversal-local
argument buffer, and prepare only the raw reusable environment before invoking.
Both remain lazy until the first present element. General `Call` argument
construction and general function-frame pooling are unchanged.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: only the `forEach` native bridge.
- `src/commonTest/kotlin/meow/nyx/quackjs/NativeCallbackLane22Test.kt`: seven common regressions.
- `docs/performance-lanes/22-native-callback-calls.md`: this report.

The original frame-preparation allocation/step charges are preserved explicitly,
as are the per-callback argument charges inside `invoke`. This removes redundant
binding assignments, not their safety accounting. Slot clearing remains in the
shared `functionEnv`. Closures/`arguments` still disable frame reuse; observable
`arguments` arrays copy the buffer. Each recursive traversal owns a separate
buffer. Native methods do not expose or retain the internal list.

## Real-player measurements

Same standard `playerBenchmark`, JVM 21.0.12.1, 512 MiB initial / 2 GiB maximum
heap. Full Node/QuickJS golden verification passes before every benchmark.
Cold medians cover compile + first signature/n pair; warm medians cover 496
transforms. Times below are QuackJS milliseconds. No benchmark boundaries,
fixtures, goldens, dependencies, or Gradle configuration were changed.

Shared pre-lane reference and first candidate run:

| Player | Shared cold 1 / 2 | Shared warm 1 / 2 | Candidate 1 cold | Candidate 1 warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 57.042 / 53.073 | 121.925 | 48.271 |
| 10733253 | 135.474 / 134.351 | 48.456 / 45.793 | 122.385 | 44.444 |
| eb27e5fd | 135.206 / 138.892 | 47.711 / 43.456 | 123.363 | 39.086 |
| 5008be0a | 145.202 / 142.167 | 38.905 / 41.172 | 123.280 | 35.178 |

Since shared reference runs also had slower QuickJS timings, a local
baseline/candidate pair followed, under one uninterrupted validation lock. Only
`Runtime.kt` was swapped to its branch-start contents for the local baseline;
the new regression suite passed against both versions.

| Player | Local before cold | Candidate 2 cold | Local before warm | Candidate 2 warm | Warm change |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 113.759 | 116.087 | 50.552 | 48.196 | -4.66% |
| 10733253 | 123.358 | 119.739 | 42.971 | 40.161 | -6.54% |
| eb27e5fd | 123.971 | 122.059 | 41.039 | 39.733 | -3.18% |
| 5008be0a | 121.309 | 122.629 | 37.955 | 34.758 | -8.42% |

Cold changes range from -2.93% to +2.05%, so no cold gain is established. Warm
results are promising on all four players, but these are two candidate processes
and one local baseline process on a shared host, not a statistical guarantee.
The retained structural saving is one argument-list construction per nonempty
traversal rather than per visited element; no allocation profiler was run.

## Verification and logs

Every Gradle command used the shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/22-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/22-benchmark.log 2>&1
'
```

The local comparison used the same commands/flags, baseline first and restored
candidate second inside a single lock (with an EXIT trap restoring the candidate).
Logs:

- Shared references: `/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`.
- Candidate 1: `/tmp/quackjs-perf32/22-tests.log`, `22-benchmark.log`.
- Local baseline: `/tmp/quackjs-perf32/22-local-baseline-tests.log`, `22-local-baseline-benchmark.log`.
- Final candidate: `/tmp/quackjs-perf32/22-tests-final.log`, `22-benchmark-2.log`.
- Initial failed test setup: `/tmp/quackjs-perf32/22-tests-initial.log`. The new
  helper lacked the required n-class definition; corrected before measurements.

Final result: **56 JVM tests passed**, zero failures/skips, including existing
full-player parity (124 goldens), concurrency, resource/rejection tests, and seven
lane22 common regressions covering:

- Live mutation, filling/deleting holes, shrinking, shifting and length snapshot.
- Native callbacks, all three arguments, explicit/default `thisArg`, absent parameters.
- Retained arguments arrays and captured per-invocation locals.
- Recursive/nested callbacks, active-frame isolation and uncatchable depth limits.
- Throwing callbacks stopping traversal and unwinding invocation depth.
- Exact execution/allocation charges despite physical buffer reuse.
- Empty/all-hole traversal with large callback frames remaining lazy.

`git diff --check` also passes.

## Integration recommendation and limitations

Recommend integrating this small scoped patch, then remeasure the combined core.
The local warm improvements support it; the broader shared-baseline cold gains
should not be attributed to this patch.

Lane10's general argument work and lane11's frame pooling can conflict near
`invoke`/`functionEnv` or the existing `forEach` reuse setup. This patch does not
change either helper or general call sites. When combining, preserve lazy
preparation, copy-on-observation of arguments, per-traversal isolation and the
resource charges; the budget regression deliberately fixes the baseline charges.

Not checked here: Android, Kotlin/Native/Linux, Apple execution, consumer builds,
live playback, memory profiles, or hardware performance. No API or consumer
changes, output caching, general function pool, or publication was attempted.
