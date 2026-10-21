# Lane 20: checked string search

## Change and hypothesis

Multi-character `Runtime.find` checked and updated the runtime step counter for
every character comparison. Keep exact comparison costs in local integers until
a search returns, with both candidate scans and matching-prefix loops bounded by
the remaining execution budget. This removes repeated runtime counter updates
without allowing quadratic searches to escape the existing 2,000,000-step limit.
The algorithm remains budget-bounded naive search, not unbounded host substring
search or regex execution.

`startsWith` and `endsWith` now compare only the requested region and charge only
comparisons actually reached. Previously they charged the whole receiver, and
`endsWith` also copied `substring(0, position)`. Empty needles and impossible
regions need no character comparisons. No unrelated receiver suffix or unvisited
needle tail is charged. `indexOf`, `lastIndexOf`, `includes`, and nonempty
separator `split` share the optimized `find`; single-character and empty-needle
search algorithms are unchanged. All deferred search charges now exhaust the
shared counter before throwing on overflow, including the single-character path.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/StringSearchLane20Test.kt`
- `docs/performance-lanes/20-string-search.md`

No public API, dependencies, fixtures, benchmark boundaries, source scanning, or
build configuration changed. The execution-limit constant is shared by `tick`
and the bounded comparison loops; its value is unchanged.

## Correctness and resource checks

The reviewed-and-fixed patch passed **56 JVM tests**, including seven new common tests:

- Position defaults, undefined, negative/fractional positions, NaN and infinities.
- Empty needles, overlapping matches, UTF-16 surrogate pairs and lone surrogates.
- Early matches/mismatches with a 65,536-unit receiver and almost no budget left.
- Exact comparison counts across candidates, forward/backward failed searches,
  and success with exactly enough budget versus rejection one step earlier.
- Exhaustion inside a matching prefix, including `split`, and zero-budget checks.
- Caught exhaustion must leave no budget for a subsequent runtime step, including
  matching-prefix, first-character-miss, and single-character search paths.
- N discovery must reject a player after a search exhausts the shared budget,
  even if a later candidate could otherwise supply a valid transform.

The full suite also retains and passes source-scan limits, limited multi-character
split, quadratic-search rejection, uncatchable resource failures, full-player
parity, QuickJS oracle checks, independent calls, and JVM concurrency checks.
Every full benchmark passed all four full-player golden sets before timing.

No callbacks or interpreter evaluation occur inside `find`. Local costs never
grow beyond the remaining budget plus one: exhaustion throws before the next
unaffordable character comparison. Both successful and failed deferred charges
persist their spent budget. This matters because N discovery catches unsupported
candidate failures and continues using the same runtime. No new allocation or
mutable shared search state is introduced.

### Review fix: preserve charges after caught failures

The parent identified that oversized deferred `tick(cost)` calls threw before
recording their comparisons. Two new tests reproduced the bug before the fix:
a reused runtime accepted another step after failure, and N discovery compiled a
player after an almost-matching search had exhausted its shared budget.

Search charges now share `chargeSearch`, which exhausts the counter before
raising an oversized-cost failure. General precharged `tick` operations are
unchanged. Both regressions pass after the fix, along with the complete suite.
No changes to `PlayerProgram.kt` or discovery behavior were needed.

## Real-player measurements

Milliseconds; cold is compile plus first signature/n pair, warm is a 496-transform
batch. Standard benchmark settings: 3/11 cold warmups/samples and 20/31 warm.

Shared pre-lane baseline, two separate JVM processes:

| Player | Cold run 1 / 2 | Warm run 1 / 2 |
| --- | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 57.042 / 53.073 |
| 10733253 | 135.474 / 134.351 | 48.456 / 45.793 |
| eb27e5fd | 135.206 / 138.892 | 47.711 / 43.456 |
| 5008be0a | 145.202 / 142.167 | 38.905 / 41.172 |

Same-worktree baseline versus the **review-fixed** patch, each in a fresh JVM.
The baseline is the earlier local run, not a new paired run:

| Player | Baseline cold | Retained cold | Baseline warm | Retained warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 109.488 | 116.897 | 49.993 | 48.888 |
| 10733253 | 120.059 | 122.448 | 42.299 | 42.635 |
| eb27e5fd | 121.798 | 122.652 | 42.669 | 41.064 |
| 5008be0a | 121.055 | 124.450 | 36.737 | 36.766 |

No reliable overall real-player speed gain is established. Three warm results
are within 2.3% of the local baseline; eb27e5fd improved 3.8% in this run. Cold
medians were 0.7–6.8% slower. Shared baselines were slower even than the local
unmodified baseline, so comparing only against those would overstate gains.
These runs were serialized but not CPU pinned or contemporaneously paired, and
host/JIT/GC noise remains material.

### Diagnostic search shapes before the caught-failure fix

These diagnostics were not repeated after adding persistent failed charges.
A temporary, non-retained JVM test ran five warmups and eleven samples, each
containing ten independently budgeted runtimes. Values and arguments were
constructed outside timing. It checked every result. Source is preserved at
`/tmp/quackjs-perf32/20-StringSearchMeasurementLane20Test.kt`.

| Shape | Baseline ms | Retained ms |
| --- | ---: | ---: |
| 1,000 indexOf calls, matching 1,024-unit needle | 15.429 | 11.032 |
| One includes call, 2,000 `a`s versus 500 `a`s plus `b` | 11.012 | 8.201 |
| 20 indexOf calls, 40,000 first-character mismatches | 12.193 | 9.876 |
| 200 startsWith calls, one-unit needle / 8,192-unit receiver | 0.083 | 0.124 |
| 200 endsWith calls, one-unit needle / middle position 4,096 | 0.811 | 0.428 |

These are diagnostic medians, not production throughput claims. In particular,
short prefix checks were slower in this small measurement. An initial variant
batched per candidate through a shared matcher; it regressed first-character
misses, so the retained search batches costs locally across candidates instead.
Initial full benchmark logs describe that superseded variant, not the final code.

## Commands and logs

Every Gradle invocation, including diagnostic runs and temporary baseline swaps,
held `/tmp/quackjs-perf32.validation.lock`. The baseline swap restored the patch
before releasing the lock. Final review-fixed verification:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/20-review-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/20-review-benchmark.log 2>&1
'
```

Additional logs under `/tmp/quackjs-perf32/`:

- `baseline-benchmark-1.log`, `baseline-benchmark-2.log`: shared baseline.
- `20-local-baseline-benchmark.log`: local baseline using the unchanged Runtime.
- `20-review-regressions-before.log`, `20-review-regressions-before.xml`: focused
  `jvmTest --tests '*StringSearchLane20Test'` before the fix, with both new tests
  failing as expected. This was also run under the shared lock.
- `20-tests-retained.log`, `20-benchmark-retained.log`: pre-review batching patch.
- `20-tests.log`, `20-tests-final.log`: initial candidate full-suite passes.
- `20-benchmark.log`, `20-benchmark-2.log`: superseded candidate full benchmarks.
- `20-micro-baseline.log`, `20-micro-baseline.xml`: diagnostic baseline.
- `20-micro-retained.log`, `20-micro-retained.xml`: retained diagnostic results.
- `20-micro-after.*`, `20-micro-fixed.*`: superseded diagnostic variants.

The diagnostic command was `./gradlew --no-daemon --max-workers=2 jvmTest --tests '*StringSearchMeasurementLane20Test' --console=plain`, under the same lock.
The temporary measurement test was removed before final full-suite verification.
`git diff --check` also passed.

## Recommendation and integration risks

Recommend considering this small patch for exact prefix/suffix accounting,
elimination of the suffix-prefix copy, and lower measured multi-character search
overhead. Do not select it on a claimed general real-player throughput gain:
these measurements are effectively neutral overall. Parent should benchmark the
combined candidate again.

Only Runtime search methods and its step-limit constant overlap other runtime
lanes. Preserve the exact comparison accounting and local-budget bounds when
resolving conflicts with resource-accounting changes. No exports, callers,
fixtures, or configuration changes are required elsewhere.

Not checked: Android, Kotlin/Native/Linux, iOS, consumer repositories, hardware
allocation profiling, or a final combined-lane benchmark. No Codex review was run
by this worker; the parent owns the required re-review after this fix. Worst-case
search remains quadratic but stops at the unchanged execution limit.
