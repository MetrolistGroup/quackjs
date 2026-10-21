# Lane30: runtime n-root argument setup

## Hypothesis and retained change

`NPlan.execute` used to create a binding map, `Env`, empty slot array, and environment entries for every argument, including literal constants and the exact root placeholder. Those two leaves need only the same evaluation tick and their existing value. Bypass binding setup for them; evaluate every other expression through the original runtime path, in the original order and after construction.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`: only `NPlan.execute`, eight added lines and one replaced line.
- `src/commonTest/kotlin/meow/nyx/quackjs/NRootRuntimeLane30Test.kt`: six focused common regressions.
- `docs/performance-lanes/30-n-root-runtime-setup.md`: this report.

No constructor/root template was retained or attempted. Successful discovery probes cannot prove that constructor dependencies, global writes, captured locals, or root/table aliases are disposable. `makeRoot` still invokes the constructor and binds a new setter on each invocation; neither functions nor mutable roots are shared between runtimes. The result is still read from the current root table after the transform, including table replacement. Discovery and its `nArguments` helper are unchanged, keeping the patch separate from Lane07.

This is not result caching or probe-derived argument folding. Only actual `Lit` nodes and identity with the private `RootArgument` node take the fast path. All other supported expressions, signatures, and n values retain their interpreter evaluation. Each bypassed leaf charges exactly its original `eval` tick; invocation allocation, call limits, string bounds, and all other execution budgets are untouched.

## Real-player measurements

Linux x86_64, Temurin 21.0.12.1, unchanged `playerBenchmark`; milliseconds, Kotlin cold compile + first signature/n and warm 496-transform batch. All four complete golden suites passed before every timing run. Each cell is **cold / warm**.

| Player | Shared before #1 | Shared before #2 | Local before | After #1 | After #2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 130.416 / 53.639 | 113.223 / 51.116 | 111.929 / 51.018 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 174.115 / 51.398 | 146.555 / 45.054 | 121.464 / 43.378 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 137.421 / 42.749 | 124.404 / 42.699 | 120.691 / 41.728 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 129.875 / 38.381 | 126.370 / 39.688 | 122.537 / 37.725 |

After #1 was the initial candidate run. Local before and after #2 were consecutive runs under one lock, with the production file restored from the baseline and then the candidate. Baseline plus the new correctness tests also passed. The host is noisy: the local-before QuickJS cold/warm results were 280.716/58.995, 310.412/53.061, 282.750/48.950, and 273.369/40.978; after #2 they were 242.825/53.281, 268.019/44.539, 268.141/46.606, and 268.262/38.679. These broad changes in the unchanged comparator make the Kotlin timing differences unsuitable as an attributable speedup claim.

### Allocation evidence

A temporary JVM inspection test used `com.sun.management.ThreadMXBean` on actual `transformN` calls for all 15 n goldens per player, checking every output. Eight warmup batches preceded eleven measured batches of 32 repetitions. Median allocated bytes per n call:

| Player | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 486,592.0 | 486,304.0 | 288 |
| 10733253 | 347,209.1 | 346,601.1 | 608 |
| eb27e5fd | 316,225.1 | 315,617.1 | 608 |
| 5008be0a | 308,459.7 | 308,139.7 | 320 |

The inspection confirmed that all four plans use the root leaf; two also have a literal numeric argument. Other numeric arguments are compound XOR expressions and deliberately retain ordinary evaluation. Savings are small (about 0.06–0.18% of total n-call allocation), with process-specific JIT escape decisions affecting exact byte counts. This supports a small allocation reduction, not a meaningful throughput win. The temporary inspection test was removed before final verification; its source and results remain in `/tmp/quackjs-perf32/30-NRootAllocationInspectionLane30Test.kt` and `30-allocation-{before,after}.xml`.

## Verification and commands

All Gradle work, including the inspection and paired benchmarks, used the mandatory shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/30-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/30-benchmark.log 2>&1
'
flock /tmp/quackjs-perf32.validation.lock bash /tmp/quackjs-perf32/30-paired-validation.sh
```

The paired script runs full `jvmTest` on baseline and candidate, benchmarks both, removes the inspection test, and runs final `jvmTest`. Final retained suite: **55 tests, zero failures/errors/skips**, including common semantics/resource tests, Node/QuickJS golden parity, and eight-thread real-player concurrent invocation isolation. The intermediate inspection suites had 56 passing tests each.

New counterexamples check:

- Constructor and setter global mutations happen on every call with fresh invocation globals.
- Constructor helper dependencies, captured table locals, back-references to the root, and array-held table aliases keep their identities.
- Input-derived value/length expressions and constructor-mutated global arguments remain live for empty, short, long, and UTF-16 inputs.
- Root `this`, zero arguments, arbitrary argument counts, literals, undefined, and `arguments.length` remain supported.
- A transform can replace its root table, and the returned result comes from that replacement.
- Input/value size limits and uncatchable execution-step failure remain enforced; a later invocation still succeeds.

The first test run failed because the new expected-value code used Kotlin string reversal, which repairs surrogate pairs unlike JavaScript array reversal. The expectation was corrected to reverse UTF-16 `Char` units. No runtime semantics were changed to satisfy that test.

Logs under `/tmp/quackjs-perf32/`:

- Shared `baseline-benchmark-{1,2}.log`.
- `30-tests-initial.log`, `30-tests.log`, `30-benchmark.log`.
- `30-local-baseline-tests.log`, `30-local-baseline-benchmark.log`.
- `30-paired-tests.log`, `30-benchmark-2.log`, `30-final-tests.log`.
- `30-allocation-{before,after}.xml`, `30-paired-validation.sh`, and `30-PlayerProgram-{before,after}.kt`.

`git diff --check` also passed. No fixture, oracle, benchmark boundary, Gradle, public API, or consumer change.

## Recommendation and limits

Recommend integrating only as a tiny, verified allocation cleanup. Do not advertise an established cold/warm speedup; the avoided setup is a very small fraction of real-player work. The six counterexamples are independently useful guards against unsafe constructor/root caching.

The change is confined to `NPlan.execute`, so it should be independent of Lane07 discovery changes and most runtime lanes. Any other argument fast path or expression-plan patch must preserve the leaf tick and live fallback when combining patches. No cancellation behavior was added or removed; existing bounded execution remains intact.

Android, Kotlin/Native, iOS, downstream consumers, retained memory, and energy were not checked here. Parent owns cross-platform verification and the combined benchmark. No pushes, PRs, merges, or worker-run Codex review.
