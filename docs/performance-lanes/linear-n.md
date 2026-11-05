# n rank plans derived from the AST

QuackJS executes the actual n slice with typed symbolic characters and a concrete input length. It derives character gathers and arithmetic over alphabet ranks. Derivation never supplies concrete challenge characters, samples outputs, or fits coefficients.

Each player has 201 thread-safe lazy slots, for lengths 0 through 200. A slot stores an immutable proof or a bounded rejection reason. Other lengths use the interpreter. Each execution owns its numeric buffer and output string. No challenge or result enters the cache.

## Proof rules

The existing interpreter performs concrete scalar operations, control flow, helper calls, mutations, alias resolution, exception handling and array traversal during analysis. Symbolic numbers have a separate type and cannot select numeric dispatcher specializations. Unknown operations stop analysis.

Alphabet snapshots contain only concrete one-unit strings. Each read observes the current array contents. A later mutation produces another snapshot. The analyzer replaces a search of a preceding alphabet lookup with its rank only when both complete snapshots match and contain unique entries. It keeps the lookup's range guard and normalizes negative zero to the positive zero returned by `indexOf`. Duplicate alphabets retain first-occurrence searches. Out-of-range, fractional and NaN ranks select fallback.

String comparisons may follow the unequal path with a checked inequality condition. Known throws follow the existing catch and finally machinery. Handler string addition must prove that it cannot trigger the interpreter's exception-sentinel check. It uses a length proof or an inequality guard against the challenge. An identical symbolic challenge on the right stops analysis. Unsupported operations remain uncatchable, including unknown methods on symbolic strings.

The analyzer proves `Number(array)` is NaN for a plain primitive array whose native default join contains a comma. A single symbolic character instead requires a per-call guard excluding ASCII digits and JavaScript whitespace. Other one-unit characters cannot satisfy the evaluator's numeric grammar. Coercion overrides disable this rule. It checks call and conversion depth, joined size, and all element types. It charges native `valueOf`, `toString` and `join`, their lookups, output allocation and numeric scanning. A common test compares the complete step and allocation charges with the interpreter.

Successful plans evaluate every lookup guard, including reads whose values do not reach the final output. They publish output only after all path conditions pass. A failed guard or analysis rejection executes the ordinary n slice in a fresh runtime. Symbolic state never escapes into an ordinary invocation.

## Resource bounds

Analysis retains the existing parsing, call-depth, expression-depth, value-size, 2,000,000-step and 2,000,000-allocation-unit limits. It charges full alphabet length for each symbolic search. Preflight adds the plan's instruction, condition and output buffers to the conservative source costs. It publishes a plan only if both totals fit the limits. A conservative preflight rejection selects interpretation and does not reject a valid challenge.

Construction has additional caps of 20,000 instructions, 65,536 alphabet units, 256 conditions and 65,536 condition references. A published proof has at most 8,192 instructions, output references, alphabet units and condition references combined. The 201 slots therefore retain at most 1,646,592 such units, plus fixed object overhead. Rejection messages have at most 160 units.

The first call at a length can perform one bounded analysis, one bounded plan attempt and one bounded interpreter fallback. Later calls can perform the last two. This adds bounded preparation work to the first call. It does not let the source exceed its original execution budget. For a published plan, preflight bounds analysis charges plus plan work by two million units. Adding a fallback gives at most four million logical step or allocation units per call, plus separately capped proof construction. A rejected analysis and fallback have the same upper bound. These are evaluator units, not CPU instructions or byte counts.

Common tests exercise infinite loops, growing arrays, instruction exhaustion, over-budget preflight, oversized literal output, mutations, aliases, overridden methods, content-dependent paths, duplicates, signed zero, empty inputs and non-ASCII UTF-16 units.

## Reproduce

Run from this worktree with the unchanged pinned corpus and a read-only source cache:

```sh
/mnt/work/projects/quackjs-lanes/bin/gradlew-lane --offline \
  jvmTest faraday200Verification linearNDifferential \
  -PfaradayCache=/mnt/work/projects/quackjs-lanes/faraday-cache \
  -PfaradayReport=/mnt/work/projects/quackjs-lanes/reports/linear-n-ast-plan-verify.json \
  -PlinearReport=.lane-tmp/linear-n-differential.json
/mnt/work/projects/quackjs-lanes/bin/bench-lane linear-n-ast-plan
node scripts/faraday-200-compare.mjs \
  /mnt/work/projects/quackjs-lanes/reports/baseline-a.json \
  /mnt/work/projects/quackjs-lanes/reports/linear-n-ast-plan.json
/mnt/work/projects/quackjs-lanes/bin/gradlew-lane --offline compileKotlinLinuxX64
```

`linearNDifferential` reads and hashes every source. It generates 12 cases at every length from 0 through 200, or 2,412 per player. Nine cases use base64url characters, one uses malformed ASCII, one uses non-ASCII and surrogate units, and one mixes these domains. It compares the public path and each applicable direct plan with the original interpreter. Exception type and message must also agree. Expected exceptions count as comparisons and appear separately in the report. Any mismatch fails the task. The harness performs no timing.

The normal Faraday verifier independently checks the unchanged stored goldens in hinted and hint-free modes, including repeat order and concurrency. The benchmark checks every timed output against those goldens. Its `linearBuildMs` field records first-length plan derivation inside the cold compile-and-first-pair interval.

## Correctness and coverage

The final JVM suite passed 164 tests, including nine new common tests, with no failures or skips. The unchanged Faraday golden verifier passed all 200 players in both hinted and hint-free modes. The requested single `compileKotlinLinuxX64` task also passed. No Native test or executable ran.

The final differential harness completed 482,400 comparisons, or 2,412 cases per player, with zero mismatches. Plans ran directly on 482,346 cases. Eighteen cases failed a numeric-character guard and used the interpreter. The remaining 36 cases used interpretation at the three unproved cells and produced matching expected exceptions.

| Players | Lengths with a plan | Analysis fallback |
| --- | --- | --- |
| 197 | Every length 0 through 200 | None |
| `63cb72e9`, `08af30d9`, `94877f3c` | 0 and 2 through 200 | Length 1 reaches the exception-sentinel path |

This is 40,197 of 40,200 player-length cells, and all 200 players at every golden and benchmark length. [The coverage record](linear-n-coverage.json) lists every player, the 12 cells with numeric-character guards, report digests and observed bounds. Numeric guards admit a proven subdomain of their input length. Digits and whitespace use the interpreter for those cells.

The remaining analysis failure has one cause, the exception-sentinel path at length 1. It appeared in all 36 generated cases at those cells. The analyzer cannot publish a string-result proof on that path. The interpreter retains the existing explicit failure. Lengths above 200 use interpretation without another analysis or cache slot.

The largest observed plan had 6,090 instructions. The largest conservative bounds for source execution and the plan were 559,461 steps and 33,564 allocation units. The tests also force both preflight bounds beyond their limits and require rejection of the plan.

Malformed and Unicode cases use the original interpreter as the differential reference. Stored valid-challenge goldens remain independent engine evidence. This run does not add an independent full-source oracle for the generated malformed inputs.

## Full JVM benchmark

Kept. The final 200-player, five-round run passed every timed output check. Warm n throughput improved by 63.808 times as a paired per-player geometric mean, while allocation on the calling thread fell by 97.95% on the same ratio measure. The baseline repeats had warm n ratios between 0.983 and 1.020 at the fifth and ninety-fifth percentiles. The n gain exceeds that noise floor for every player.

Warm signature timing stayed within baseline noise. The cold median improved slightly, but its gain overlaps the baseline spread. Fresh-JVM first-call timing has no reliable gain; seven of 25 sampled players were more than five percent slower. Retained heap covers one compiled program after the first pair, rather than all 201 populated slots.

Plan derivation at length 19, inside the warmed-JVM cold interval, cost a median of 0.286 ms across per-player medians. The observed range was 0.216 to 0.456 ms. Other lengths' derivation costs were not timed.

The unmodified compare script printed:

```text
baseline-a -> linear-n-ast-plan; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     115.2       113.3      1.017    1.000     1.016    1.035         0  200
first call ms               460.0       461.4      0.986    0.930     0.998    1.032         7   25
warm n ns/op               195338        2581     63.808   40.832    67.832   85.313         0  200
warm sig ns/op               4141        4188      0.997    0.966     0.996    1.030         0  200
cold alloc B            160035552   160609640      0.996    0.996     0.996    0.997         0  200
warm n alloc B/op          237180        3977     48.825   30.886    49.319   67.885         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9078160     9097288      0.998    0.997     0.998    0.998         0  200
```

The full report is `/mnt/work/projects/quackjs-lanes/reports/linear-n-ast-plan.json`, SHA-256 `608f9af605d97c2b1f9223e3c14e6271e213c229acf299cf46604e45dc4da954`. The final golden verification report is `/mnt/work/projects/quackjs-lanes/reports/linear-n-ast-plan-verify.json`, SHA-256 `31a85e619a5122e5d9009b7786500f05fd1edf6917c70a83c1188c29e833cce9`. The compact coverage record binds the differential report and source fingerprint.

## Unverified

Android host or device execution, iOS compilation or execution, Native runtime behavior, a fresh full-source QuickJS200 run, live playback, and performance or retained heap after populating all length slots were not checked. The unchanged pinned goldens provide the independent valid-input oracle; generated malformed-input comparisons use the interpreter.
