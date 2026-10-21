# Lane07: n discovery planning (no retained optimization)

## Hypothesis and scope

`discoverN` rebuilds fixed operator sets, `call`/`apply` literal keys, and the
`root.<mapKey>.n` comparison expression while walking bindings, calls, and
writes. Reusing these immutable planning intermediates could reduce compile-time
allocation without skipping JavaScript evaluation or changing discovery rules.

The experiment changed only:

- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`: replace per-visit
  operator sets with equivalent `when` branches, share immutable literal keys,
  and construct the root-write comparison expression once per discovery.
- `src/commonTest/kotlin/meow/nyx/quackjs/Lane07DiscoveryPlanningTest.kt`: three
  focused common regressions covering unary bindings and `.call` receivers,
  aliases/stale writes/repeated candidates, logical root tails/`.apply`, and
  input guards that happen to pass both discovery probes.

Both files were restored/removed after measurement. **The committed change is
this report only; all production and test sources match the lane baseline.**
The discarded production/test diff is preserved locally at
`/tmp/quackjs-perf32/07-experiment.patch`; the focused test is also saved as
`/tmp/quackjs-perf32/07-Lane07DiscoveryPlanningTest.kt`.

## Real-player results

Milliseconds, Kotlin medians. Before columns are the supplied shared baseline
runs, not additional runs performed by this lane. After is the discarded
experiment. Cold includes compile plus first signature+n; warm is 496 transforms.
The benchmark and its timing boundaries were unchanged.

| Player | Before cold 1 / 2 | After cold | Before warm 1 / 2 | After warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 138.345 | 57.042 / 53.073 | 57.367 |
| 10733253 | 135.474 / 134.351 | 144.094 | 48.456 / 45.793 | 46.125 |
| eb27e5fd | 135.206 / 138.892 | 137.471 | 47.711 / 43.456 | 47.673 |
| 5008be0a | 145.202 / 142.167 | 135.216 | 38.905 / 41.172 | 42.059 |

Relative to the mean of the two before medians, cold changed by +12.4%, +6.8%,
+0.3%, and -5.9%, respectively. This establishes **no consistent real-player
win**. The shared host and JVM/JIT/GC variation prevent attributing individual
changes to the patch. Warm execution was untouched and likewise varied. There
was no repeat benchmark, allocation profile, or claim of a reliable regression
or speedup.

Environment: Temurin 21.0.12.1+1-LTS, Linux x86_64, 32 logical CPUs, 512 MiB
initial/2 GiB maximum heap. Standard harness: cold 3 warmups/11 samples, warm
20 warmups/31 samples. All four full-player golden suites passed before timing;
every timed output also passed the harness comparison.

## Verification and logs

All Gradle commands were serialized under the mandatory shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --tests "meow.nyx.quackjs.Lane07DiscoveryPlanningTest" --console=plain > /tmp/quackjs-perf32/07-focused-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/07-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/07-benchmark.log 2>&1
'
```

- Focused Lane07 tests: 3 passed.
- Full JVM suite on the experiment: 52 passed, no failures/errors/skips. Includes
  existing ordered-call/stale-binding, mutation/alias, input-dependent control
  flow, ambiguous-candidate and resource-budget common tests; four-player parity,
  the independent QuickJS cross-check of the 124 Node goldens, and shared-program
  concurrency coverage.
- Test XML is preserved at `/tmp/quackjs-perf32/07-test-results/`.
- Shared before logs: `/tmp/quackjs-perf32/baseline-benchmark-1.log` and
  `/tmp/quackjs-perf32/baseline-benchmark-2.log`.
- After restoring the experiment, `git diff --exit-code -- src` passed. No final
  code rerun was needed for the report-only deliverable.

## Recommendation and limits

Do not integrate the experimental production patch: its end-to-end benefit was
not established. The report has no code integration conflicts. A future attempt
would overlap `PlayerProgram.kt` discovery/traversal work; the discarded patch
neither changed `makeRoot` nor attempted Lane30 runtime root optimization.

Fresh roots and all constructor/argument/guard evaluations were deliberately
preserved. Constructors, globals, aliases and candidate probes can mutate state;
reusing evaluated roots/environments or caching truth/substitution across writes
would require additional proof and invalidation. Successful probes alone cannot
justify discarding control flow or accepting ambiguous updates. No such semantic
relaxation was attempted.

Android, Kotlin/Native, Apple targets, consumers, real devices, live CDN players,
retained memory and allocation counts were not checked by this lane. No fixtures,
public API, resource budgets, runtime configuration or dependencies changed.
