# Lane 25: constant string-table folding

## Hypothesis and scope

Replacing immutable `table[b ^ constant]` reads with string literals in numeric
specializations removes repeated global/member lookup and enables constant
arithmetic/comparison and string-helper folding. Challenge strings are never
constants.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/ConstantTables.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
- `src/commonMain/kotlin/meow/nyx/quackjs/Specialization.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/ConstantTableFoldingLane25Test.kt`
- this report

The indexing review fix touches ConstantTables, SourceIndex, the Lane25 tests and
this report. The built-in precedence follow-up touches Runtime, the Lane25 tests
and this report. No public API, dependency, fixture, benchmark, or build
configuration changes.

## Safety and review fix

`SourceIndex.definition` alone only validates player-scope initialization. The
proof additionally observes **every function body and unprobed branch during the
existing initial lexer pass**. It stores a read-only summary, keyed by binding
name and initializer offset. Looking up a proof never rescans the source.

Candidate discovery is deliberately limited to the first 4096 tokens. A binding
must first appear at an initializer starting with a literal string or array;
forward references, earlier aliases and shadowing conservatively disqualify it.
Later definitions use the general path. After discovery ends, checking existing
candidates continues through the entire source, including late mutations and
aliases. A first-character filter avoids processing unrelated tokens once
discovery has finished. Summary entries are retained only for matching unique
player-scope definitions; the initializer must still be a closed string array or
literal-string split before folding is allowed. Runtime also checks its existing
built-in resolver before consulting any source initializer, matching `resolve`'s
built-in precedence. Built-in names never qualify for source-table folding, and
the negative result is cached with other failed proofs.

Every use other than the initializer must be a non-writing numeric element read
or length read. Bare references reject aliases, argument/return escapes,
rebinding, and same-named parameters/locals/catches. Numeric elements are
primitive strings, so these reads cannot expose the array. Unrelated property
names are not binding references. Nested candidate reads conservatively
invalidate the outer candidate, without losing the inner candidate's checks.

Direct/parenthesized assignment, update and delete are rejected. Prefix mutation
context comes from SourceIndex's existing scanner. Unknown keys and method
access reject the proof because they could retrieve array mutators. The key
grammar accepts only literals and small, definitely numeric expressions,
including `b ^ constant`. More complex safe keys fall back. No conclusion depends
on compilation probes or player IDs/hashes.

Only primitive results enter the specialized AST. A missing or failed proof
leaves the ordinary member expression. Discovery and key buffers are bounded;
validation shares the initial source token/nesting limits instead of adding
per-table scans. Speculative closed evaluation still shares a separate normal
Runtime budget; failure retains the general path without exhausting the live
probe's budget or rejecting an unexecuted branch.

The first three full-player tables qualify. The fourth reuses its table name and
stays general. The summary is immutable after indexing, preserving concurrent
invocation isolation.

## Measurements

All times are JVM median milliseconds. Cold includes compile plus the first
signature and n transform (11 samples); warm is the unchanged 496-transform
batch (31 samples). No timing boundaries or inputs changed.

The final paired run compares the original pre-lane production code (`732ee13`)
with the reviewed implementation on the same host. The script temporarily
copies original files and removes the two new Kotlin files, then restores the
patch with a trap. No branch operations were used.

| Player | Original cold | Review-fix cold | Original warm | Review-fix warm | Warm change |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 113.586 | 114.796 | 49.966 | 46.555 | -6.8% |
| 10733253 | 124.301 | 124.980 | 42.230 | 40.375 | -4.4% |
| eb27e5fd | 123.502 | 128.120 | 41.633 | 39.438 | -5.3% |
| 5008be0a | 121.269 | 124.782 | 36.491 | 36.599 | +0.3% |

The previous retained patch measured cold 141.677 / 153.694 / 155.750 / 120.798
against its local baseline 111.054 / 121.447 / 119.344 / 120.688. The review's
27–31% regression on qualifying players was real: those per-table full-source
rescans are now removed. A first streaming experiment tracked every identifier
throughout the source and remained too expensive, so the retained version bounds
discovery and processes later references sparsely.

The preceding retained-code benchmark measured cold
116.652 / 125.436 / 128.424 / 124.918 and warm
45.475 / 39.325 / 37.667 / 35.519. Cold and warm host noise remain visible. The
final paired cold differences are +0.7–4.6 ms, not zero; no table-folding gain is
established on the fourth player. The much larger review regression is fixed,
but do not claim a universal cold-speed improvement.

The built-in precedence follow-up's full benchmark measured cold
113.781 / 124.128 / 123.702 / 122.638 and warm
45.273 / 39.471 / 38.482 / 36.067. This correctness fix was not given a new paired
baseline; differences from the preceding table are not claimed as a speed gain.

**Recommendation:** retain for parent integration benchmarking. The qualifying
players keep a roughly 4–7% warm improvement without the previous full-source
rescan penalty. Check the smaller residual cold cost and fourth-player fallback
in the combined patch; cold-only use is still not a demonstrated win. No
production tuning option or challenge/output cache was added.

## Verification and logs

Every Gradle invocation ran under
`flock /tmp/quackjs-perf32.validation.lock`, with `--no-daemon --max-workers=2
--console=plain`.

Final paired command:

```sh
flock /tmp/quackjs-perf32.validation.lock bash /tmp/quackjs-perf32/25-review-paired.sh
```

The script runs the unchanged `playerBenchmark` on the original implementation,
restores the patch, then runs `jvmTest` and `playerBenchmark` on the candidate.

- `/tmp/quackjs-perf32/25-review-retained-tests.log`: all 59 tests passed,
  including ten Lane25 tests, existing resource/unsupported-input checks,
  parity, QuickJS oracle, real-player slice and concurrent-call isolation.
- `/tmp/quackjs-perf32/25-review-retained-baseline-benchmark.log`: original
  implementation, all full-player goldens and timed outputs passed.
- `/tmp/quackjs-perf32/25-review-retained-benchmark.log`: final candidate, all
  four players and 124 goldens passed; all timed outputs checked too.
- `/tmp/quackjs-perf32/25-review-sparse-{tests,benchmark}.log`: preceding sparse
  implementation run.
- `/tmp/quackjs-perf32/25-review-{tests-fixed,benchmark}.log`: first streaming
  experiment, correct but too costly.
- `/tmp/quackjs-perf32/25-{tests,benchmark}-retained.log`: original Lane25 patch
  before the review fix; historical measurements above.
- `/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`: shared original baselines,
  not used to inflate the final paired comparison.
- `/tmp/quackjs-perf32/25-builtins-regression-before.log`: the new built-in
  precedence regression failed before the guard, returning a folded source
  literal instead of declining the optimization.
- `/tmp/quackjs-perf32/25-builtins-tests.log`: all 60 JVM tests passed after the
  guard, including eleven Lane25 tests and the existing parity, resource and
  concurrency checks.
- `/tmp/quackjs-perf32/25-builtins-benchmark.log`: full benchmark after the guard;
  all four players, 124 goldens and timed outputs passed. Both final commands
  used the same mandatory lock and Gradle flags documented above.

Regressions assert actual AST removal, constant helpers, arbitrary challenges
including UTF-16 surrogates, repeated independent calls, mutation and alias routes
in unobserved branches/closures/callees and the other transform, shadowing,
unknown key/content/dependency shapes, unseen dispatcher arguments, and lazy
fallback after speculative evaluation failures. Review regressions add multiple
simultaneous tables, nested table references, earlier escapes, no resurrection
after rebinding, bounded discovery, late-table fallback, and mutation/alias checks
that continue after discovery stops. The first review-test run exposed a test
setup that rebound a still-unresolved lazy global; it was corrected to resolve
the binding first, without changing runtime semantics. The built-in follow-up
covers all twelve runtime built-in names with otherwise eligible source arrays,
checks repeated failed-fold lookups, compares specialized NaN/Infinity reads to
the general interpreter for arbitrary short challenges, and preserves rejection
on the other built-ins in branches the compilation probe never executes.

## Integration interactions and omissions

SourceIndex, Runtime and specialization are shared hot files; reconcile with
other lanes rather than stacking patches blindly. No Android, Linux Native,
iOS, or consumer verification ran here. No Codex review was run by this worker;
the parent performs the required review.
