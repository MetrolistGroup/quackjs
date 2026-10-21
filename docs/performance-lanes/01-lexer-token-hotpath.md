# Lane 01: lexer token hot path

## Hypothesis and scope

Cold preparation scans over a million tokens per full player. Avoid comment-prefix
checks for non-slashes, Unicode classification for ASCII identifiers, per-token
single-character spelling strings, and map/list-iterator operator dispatch.
Keep the existing lexer and longest-match operator order.

Touched paths:
- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/Lane01LexerTest.kt`
- `docs/performance-lanes/01-lexer-token-hotpath.md`

The spelling table contains only 128 fixed ASCII strings, not tokens, source
slices, programs, challenge results, or player-specific data. Token objects and
source-dependent identifiers are still created normally. Non-ASCII identifiers
retain `Char.isLetter` / `Char.isLetterOrDigit` behavior. String decoding, numeric
scanning, regex/template parsing, caller interfaces, and all budgets are unchanged.

## Actual token investigation

A temporary JVM test used the actual baseline `Lexer.next(previous)` on every
fixture, then repeated the same scan after the patch. Counts exclude EOF:

| Player | UTF-16 units | Tokens | Single punctuation | Single identifiers | Multi-character operators |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 2,778,899 | 1,177,472 | 629,735 | 204,217 | 31,657 |
| 10733253 | 2,957,946 | 1,264,037 | 675,276 | 216,972 | 35,405 |
| eb27e5fd | 2,976,896 | 1,271,094 | 679,131 | 217,652 | 35,557 |
| 5008be0a | 2,968,897 | 1,269,013 | 679,606 | 217,420 | 33,502 |

Single-character punctuation/identifiers account for 70.6–70.8% of tokens.
No fixture identifiers were non-ASCII, so separate common regressions cover
Unicode. All four complete token-stream SHA-256 fingerprints matched before and
after. The fingerprint includes each token's kind, source offset, lexer position,
text length, and every text UTF-16 unit, including EOF, encoded as little-endian
32-bit integers. This is token-behavior investigation, not an allocation profile
or a lexer-only timing claim.

Investigation artifacts (outside the committed patch):
- `/tmp/quackjs-perf32/01-Lane01TokenInspectionTest.kt` (temporary harness)
- `/tmp/quackjs-perf32/01-token-baseline-gradle.log`
- `/tmp/quackjs-perf32/01-token-baseline.txt`
- `/tmp/quackjs-perf32/01-token-after.txt`
- `/tmp/quackjs-perf32/01-test-results-with-inspection/`

## Real-player measurements

Unmodified `playerBenchmark`, same JVM 21/heap/sample settings as the shared fresh
baseline. Every run verifies all four players against the goldens before timing.
Times are median milliseconds, **Kotlin / QuickJS**. Cold includes compile plus
first signature/n; warm is 496 transforms per batch.

| Player | Baseline 1 cold | Baseline 2 cold | After 1 cold | After 2 cold |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 279.818 | 121.488 / 265.894 | 114.178 / 266.094 | 123.996 / 274.334 |
| 10733253 | 135.474 / 284.865 | 134.351 / 277.667 | 118.478 / 259.643 | 137.897 / 300.003 |
| eb27e5fd | 135.206 / 273.167 | 138.892 / 281.623 | 135.666 / 278.291 | 129.792 / 278.777 |
| 5008be0a | 145.202 / 285.821 | 142.167 / 278.093 | 144.364 / 303.306 | 143.980 / 294.510 |

| Player | Baseline 1 warm | Baseline 2 warm | After 1 warm | After 2 warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 57.042 / 59.834 | 53.073 / 57.597 | 49.664 / 54.040 | 55.401 / 61.393 |
| 10733253 | 48.456 / 50.129 | 45.793 / 47.569 | 47.488 / 49.285 | 46.923 / 50.925 |
| eb27e5fd | 47.711 / 53.438 | 43.456 / 49.018 | 45.898 / 51.785 | 47.591 / 56.674 |
| 5008be0a | 38.905 / 41.128 | 41.172 / 43.121 | 42.722 / 44.194 | 43.338 / 47.388 |

The first run's cold improvement on the first two players did not consistently
repeat: run 2 is neutral for 4f38b487, slower for 10733253, faster for eb27e5fd,
and neutral for 5008be0a. No reliable overall cold win is established. Warm
results are mixed; 5008be0a's Kotlin warm batch is slower in both runs. QuickJS
also varies, but that does not prove the Kotlin difference is only host noise.
No warm-performance improvement or absence of regression is claimed.

## Commands and correctness

Every Gradle invocation, including the temporary baseline token inspection, ran
under `/tmp/quackjs-perf32.validation.lock` with `--no-daemon --max-workers=2`.
Initial investigation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --tests meow.nyx.quackjs.Lane01TokenInspectionTest --console=plain > /tmp/quackjs-perf32/01-token-baseline-gradle.log 2>&1
'
```

First post-patch verification (56 tests, including the temporary inspection test):

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/01-tests.log 2>&1 &&
  cp /tmp/quackjs-perf32/01-token-inspection-latest.txt /tmp/quackjs-perf32/01-token-after.txt &&
  cmp /tmp/quackjs-perf32/01-token-baseline.txt /tmp/quackjs-perf32/01-token-after.txt &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/01-benchmark.log 2>&1
'
```

After removing the temporary inspection test, the unchanged runtime/common tests
passed again: **55 tests, zero failures/errors/skips**, including all six new
common tests. The second full benchmark also passed all goldens and completed
successfully. Both validation exit files contain zero.

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/01-tests-final.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/01-benchmark-2.log 2>&1
'
```

Final JUnit reports: `build/test-results/jvmTest/`.
`git diff --check` also passed. The benchmark was repeated once because run 1's
first two cold results were promising; no further tuning or measurement runs
were made.

Focused common tests check all 65,536 UTF-16 code units against the original
identifier classification, numeric boundaries including Unicode digits, every
multi-character operator and longest-match overlaps, offsets/EOF, single-character
fallbacks, comments/string escapes, regex versus division contexts, opaque nested
templates, malformed input, and the existing token/template-depth limits.
The full suite also covers all four Node/QuickJS player goldens, repeated and
reordered calls, eight-thread concurrent use, resource rejection, mutation
rejection, and unsupported-input behavior. No fixtures, oracles, timing boundaries,
Gradle configuration, or existing tests were changed.

Shared baseline logs:
`/tmp/quackjs-perf32/baseline-benchmark-1.log` and
`/tmp/quackjs-perf32/baseline-benchmark-2.log`.

## Integration and limits

**Recommendation:** do not select this as an established performance winner yet.
The small patch removes source-level allocation/lookup work and has strong token
parity coverage, so it is retained as a reviewable candidate. Integrate only if
the parent's controlled paired/combined measurements confirm a useful gain;
otherwise skip it, especially given the latest player's warm results.

The change is confined to the lexer; no callers need integration changes.
Lane 02 also edits `Syntax.kt`, but this patch leaves its string-decoding block
untouched. Other lexer experiments must be combined manually rather than stacked
blindly. Only immutable spellings are shared; no lexer position or token object
is added to shared state.

Android, Linux/Native, iOS, consumer integrations, allocation/retained-memory
profiles, and hint-free compile timing were not checked in this lane. No Codex
review was run here; the parent owns review and cross-platform verification.
