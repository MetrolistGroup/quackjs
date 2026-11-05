# Tokenless indexing

Base commit: `bfa3a87`. Measurements use the lane wrappers, the pinned Faraday
200-player corpus and JDK 21. The wrappers serialize builds and benchmarks on
the shared lock. Fixtures and goldens remain unchanged.

## Tokenless scan

`Lexer.scan` exposes a token's kind, integer tag and UTF-16 source span. It runs
the same comment, quoted-string, regex and template checks as `Lexer.next`.
`next` materializes the current token for parsing. The first index scan uses a
96-entry array window and materializes entries only when a retained target or
metadata check needs text. The window shares those retained tokens until their
slots wrap. Effect reference scans and hint-free landmark validation use the
same cursor. Selected definitions and initializer-kind proofs still use the existing bounded
parser.

Fixed tags use exact source comparisons. Non-ASCII identifiers cannot overlap
ASCII punctuation or the reserved tags. String literals stay opaque during
scanning, but escape validation still runs even for unused definitions.

The first scan still rejects more than 2,000,000 tokens, 512 brace/paren levels,
100,000 definitions, calls or mutations, and source above 8 Mi UTF-16 units.
Effect analysis still charges every scan and lookahead against its shared
2,000,000-token and 16 Mi-unit budget. The 150,000-token definition/call limits,
128-level selected-source bound and 64-level template bound remain unchanged.
Materializing a retained literal rereads its bounded source span for validation
and decoding. The window caches that result. Its decoded length cannot exceed
its source span.
No token-count or source-unit charge changes, and runtime guards, steps and
allocation charges remain unchanged.

Six new common tests cover offsets, decoded escapes, opaque nested templates,
all regex contexts, Unicode tag collisions, empty input, malformed unused
literals, scan/depth limits, window wrapping, aliases, escaped member writes,
native predicate invalidation, live table mutation and challenge lengths through
16,384 UTF-16 units. Existing concurrency tests exercise the resulting programs.

Verification on this implementation passed all 200 players in both modes,
18,600 sequential comparisons per mode, plus concurrency on eight distinct
primary configs per mode. `jvmTest` passed 161 tests, with zero failures, errors
or skips. Reports are `tokenless-index-tokenless-scan-verify.json` and
`tokenless-index-tokenless-scan.json` in the shared reports directory.

The two unchanged baselines have cold-time ratio quantiles 0.975 / 0.998 / 1.023
and first-call quantiles 0.952 / 0.999 / 1.052. Retained bytes match exactly.
Those differences define the noise floor for these measurements.

Full run, 200 players and five rounds. Ratios above one favor this lane.

```text
baseline-a -> tokenless-index-tokenless-scan; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     115.2       74.73      1.542    1.512     1.542    1.570         0  200
first call ms               460.0       302.8      1.514    1.297     1.515    1.820         0   25
warm n ns/op               195338      200593      0.968    0.918     0.973    0.997        34  200
warm sig ns/op               4141        4192      0.990    0.968     0.991    1.010         0  200
cold alloc B            160035552    61306576      2.602    2.521     2.613    2.657         0  200
warm n alloc B/op          237180      230314      1.025    1.012     1.027    1.040         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9078160     9243976      0.982    0.982     0.982    0.982         0  200
```

Keep this change for cold compilation. Its cold and first-call gains exceed the
baseline spread. Warm n time regressed about 3.3% by geometric mean, beyond the
baseline warm spread, and retained heap grew about 1.8%. Warm n allocation fell
about 2.5%; signature allocation did not change. No warm speedup is claimed.

## Source packing, rejected

The separate packing variant passed 164 JVM tests and 200/200 players in both
modes. It used UTF-16 LZ backreferences, retained exact function text, preserved
deferred errors and capped overlapping function-text copies at 8 Mi units.
Three common tests covered all UTF-16 units, isolated surrogates, empty and
repeated strings, late definitions, oversized function text and the aggregate
copy cap. It used no reflection or platform code.

The 20-player, two-round trial measured a 0.864 cold-time ratio and a 1.126
retained-heap ratio against tokenless scanning. The full 200-player, five-round
run confirmed the trade below. Cold time grew about 15% and cold allocation
about 13% for about 11% less retained heap. All 200 players regressed more than
5% in cold time. Reject packing and remove its code and experimental tests.
Its source drafts remain untracked under `.lane-tmp/`.

Reports: `tokenless-index-pack-source-verify.json`,
`tokenless-index-pack-source-trial.json` and `tokenless-index-pack-source.json`.

```text
tokenless-index-tokenless-scan -> tokenless-index-pack-source; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     74.73       85.88      0.870    0.857     0.870    0.882       200  200
first call ms               302.8       342.2      0.884    0.792     0.883    1.008        21   25
warm n ns/op               200593      196536      1.027    0.997     1.023    1.077         0  200
warm sig ns/op               4192        4134      1.022    0.986     1.022    1.062         0  200
cold alloc B             61306576    69039112      0.888    0.883     0.887    0.896       200  200
warm n alloc B/op          230314      228778      1.010    1.006     1.009    1.017         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9243976     8210624      1.126    1.125     1.126    1.127         0  200
```

## Drop index

After effect analysis, the runtime keeps one offset or rejection code per name.
It no longer retains the grouped assignment records, write-count map or mutation
set. Negative codes preserve ambiguous and externally mutated dependencies.
Missing names still fail as missing. `PlayerProgram.compile` clears the remaining
class/method assignment list after discovery and before publication.

Late lookups still parse the original source with the original token and depth
bounds. The change does not parse unused definitions eagerly. Function objects
retain the original source spans, so coercion returns exact UTF-16 function text
and oversized function text still fails only when requested. All index changes
finish before callers can share a program. Invocations only read the index and
own their newly parsed late dependencies.

Four new common tests cover release idempotence, exact rejection messages,
unsupported unselected definitions, unprobed branches, empty and non-ASCII input,
isolated surrogates, live aliases and oversized function text. Existing program
concurrency tests and the cohort concurrency checks run after index release.
The retained map has at most 100,000 entries, the existing definition bound.
No source, parse, execution or allocation budget charge changes.

A new JVM test shares one released program across four workers and forces late
function/state resolution, aliases, Unicode and exact function text on each
invocation.

The final trimming gate passed 200/200 players in both modes, including the
selected concurrency checks. `jvmTest` passed 166 tests with zero failures,
errors or skips. This includes the existing unprobed-branch allocation and
traversal-budget regressions. The new concurrent late-lookup test passed too.
Report: `tokenless-index-drop-index-verify.json`.

Full trimming run, 200 players and five rounds, compared with the kept scanner:

```text
tokenless-index-tokenless-scan -> tokenless-index-drop-index; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     74.73       74.32      1.005    0.975     1.008    1.023         0  200
first call ms               302.8       308.2      0.962    0.832     0.968    1.138        10   25
warm n ns/op               200593      196186      1.030    0.998     1.024    1.089         0  200
warm sig ns/op               4192        4127      1.030    0.993     1.031    1.070         0  200
cold alloc B             61306576    61290952      1.000    1.000     1.000    1.000         0  200
warm n alloc B/op          230314      228778      1.010    1.006     1.009    1.017         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9243976     4375224      2.114    2.097     2.114    2.130         0  200
```

Cumulative comparison against the original baseline:

```text
baseline-a -> tokenless-index-drop-index; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     115.2       74.32      1.549    1.506     1.553    1.589         0  200
first call ms               460.0       308.2      1.456    1.218     1.476    1.813         0   25
warm n ns/op               195338      196186      0.997    0.976     0.996    1.019         0  200
warm sig ns/op               4141        4127      1.019    0.983     1.020    1.055         0  200
cold alloc B            160035552    61290952      2.603    2.522     2.613    2.658         0  200
warm n alloc B/op          237180      228778      1.036    1.021     1.037    1.049         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9078160     4375224      2.076    2.060     2.076    2.091         0  200
```

Keep index trimming for its 52.7% reduction in retained heap from the scanner
variant. Its 0.5% cold-time change is within baseline noise, so there is no
incremental cold-speed claim. The first-call geometric ratio is 0.962, about
4% slower than the scanner variant, with ten of 25 paired players worse than
5%. Cumulatively, cold compile improves 1.549x and retained heap falls 51.8%.
Warm n timing is back within the original baseline spread; no warm n speedup
is claimed. Report: `tokenless-index-drop-index.json`.

The final code passed `compileKotlinLinuxX64` once through the offline lane
wrapper. No other Native task ran. `git diff --check` passed. Android and iOS
builds/tests, Native runtime behavior, live playback and separate 200-player
Node/QuickJS oracle reruns were not checked in this lane. Existing JVM tests
still include their independent six-player QuickJS checks.
