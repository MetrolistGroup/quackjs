# Lane 06: hint-free landmark discovery

## Hypothesis and change

Hint-free compilation needlessly compiled two regular expressions per raw candidate
and lexed the source once for landmark verification, then again for `SourceIndex`.
Reuse the regexes and, when every raw candidate names the same signature/class
pair, verify those candidates during the index's existing lexical pass. A raw
match alone is never accepted: at least one candidate must have all three exact
code-token locations proven before indexing can finish. If raw candidates disagree,
keep the separate lexical disambiguation pass before selecting a class.

A sorted cursor visits only candidate offsets instead of allocating and hashing
an `(offset, text)` pair for every source token. The original windows, patterns,
1,024-candidate limit, token budgets, signature validation, probes, ambiguity
checks and transform behavior are unchanged. Discovery state is per compile and
is not retained by compiled programs. No public API changes or dependencies.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/PlayerProgram.kt`: regex reuse, discovery
  fast path and shared exact-token matcher.
- `src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt`: optional internal matcher,
  checked during the existing scan and required to succeed before further indexing.
- `src/commonTest/kotlin/meow/nyx/quackjs/Lane06LandmarkDiscoveryTest.kt`: seven
  focused common regressions.
- `src/jvmTest/kotlin/meow/nyx/quackjs/verification/Lane06CompileMeasurementTest.kt`:
  hint-free full-player goldens, concurrent compiles, opt-in compile-only timings.
- This report.

## Measurements

All values are median milliseconds on the shared JVM host. Baseline production
source is `732ee13` (the experiment-instructions commit on the supplied core).
The existing benchmark was not modified. Its cold path supplies hints; therefore
it does **not** measure the intended hint-free optimization. Warm batches contain
496 transforms, as before. Each cell below is **QuackJS / QuickJS**.

| Player | Baseline run 1 cold | Baseline run 2 cold | Lane 06 cold |
| --- | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 279.818 | 121.488 / 265.894 | 144.031 / 276.362 |
| 10733253 | 135.474 / 284.865 | 134.351 / 277.667 | 152.587 / 297.604 |
| eb27e5fd | 135.206 / 273.167 | 138.892 / 281.623 | 163.837 / 309.831 |
| 5008be0a | 145.202 / 285.821 | 142.167 / 278.093 | 129.930 / 281.614 |

| Player | Baseline run 1 warm | Baseline run 2 warm | Lane 06 warm |
| --- | ---: | ---: | ---: |
| 4f38b487 | 57.042 / 59.834 | 53.073 / 57.597 | 57.214 / 62.690 |
| 10733253 | 48.456 / 50.129 | 45.793 / 47.569 | 53.772 / 56.867 |
| eb27e5fd | 47.711 / 53.438 | 43.456 / 49.018 | 44.850 / 50.211 |
| 5008be0a | 38.905 / 41.128 | 41.172 / 43.121 | 43.849 / 45.189 |

The new opt-in test measures **compile only**, not compile plus first transforms:
3 warmups, 11 samples, alternating hinted/hint-free order, fixture loading outside
timing, result/timestamp checks after timing. No output cache. This measurement
was run once before production edits and once afterward. The before invocation
selected only the measurement test class; the after invocation ran the full JVM
suite, so JIT history also differs.

| Player | Before hinted | Before hint-free | After hinted | After hint-free |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 203.157 | 212.046 | 134.502 | 137.457 |
| 10733253 | 196.965 | 198.829 | 159.127 | 163.181 |
| eb27e5fd | 229.158 | 206.305 | 167.547 | 169.445 |
| 5008be0a | 221.803 | 215.199 | 164.665 | 172.546 |

**No reliable end-to-end gain is established.** Hinted compilation also moved
substantially, sometimes beating hint-free compilation before the patch. The
shared-host noise and different JIT histories prevent attributing the raw declines
to this patch. The structural reduction is real: all four fixtures have one raw
candidate, so the extra discovery lex is removed; its endpoint was approximately
947k source units for 4f38b487 and 26k–174k for the other three. This is a general
single-pair condition, not a fixture-specific shortcut. No warm improvement is
expected or claimed.

## Commands, logs and correctness

Every Gradle invocation below was serialized with
`flock /tmp/quackjs-perf32.validation.lock bash -c '...'`, using
`./gradlew --no-daemon --max-workers=2`:

1. Before production edits:
   `QUACKJS_LANE06_MEASURE=1 ./gradlew --no-daemon --max-workers=2 jvmTest --tests '*Lane06CompileMeasurementTest' --console=plain`
   - `/tmp/quackjs-perf32/06-compile-before.log`
   - `/tmp/quackjs-perf32/06-compile-before.xml` (captured stdout/timings)
2. After production edits:
   `QUACKJS_LANE06_MEASURE=1 ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain`
   followed by
   `./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain`
   - `/tmp/quackjs-perf32/06-tests.log`
   - `/tmp/quackjs-perf32/06-compile-after.xml`
   - `/tmp/quackjs-perf32/06-benchmark.log`
3. After adding the explicit concurrent-compile and source-token-limit checks:
   `./gradlew --no-daemon --max-workers=2 jvmTest --console=plain`
   - `/tmp/quackjs-perf32/06-final-tests.log`: **59 tests, zero failures/errors**.
     The opt-in timing method returns without measuring in this final run.

Shared baseline logs:
`/tmp/quackjs-perf32/baseline-benchmark-1.log` and
`/tmp/quackjs-perf32/baseline-benchmark-2.log`.

Checked:

- All 124 full-player signature/n goldens with hint-free compilation, as well as
  existing repeated hinted parity tests and QuickJS oracle/golden validation.
- Existing concurrent transform tests; eight hint-free compiles on four worker
  threads, checking timestamps and first signature/n pairs.
- One valid landmark and repeated identical landmarks; missing landmarks;
  conflicting genuine signature/class landmarks remain ambiguous.
- Entire and partial comment/string/regex/template bait, including nested template
  interpolation, and conflicting bait both before and after a genuine landmark.
- Exact token starts (identifier suffix bait), candidate limit and the shared
  2,000,000-token scan budget.
- Existing mutation, execution/allocation/resource-limit and unsupported-input
  tests; `git diff --check`.

## Recommendation and integration notes

Recommend retaining this as a small, correctness-tested reduction in redundant
hint-free work, but **not selecting it as a measured performance winner yet**.
Parent should repeat matched hinted/hint-free measurements on a quieter host
before claiming a speedup, and reject it if the hinted path regresses reliably.

Integration overlaps are limited to `PlayerProgram.compile`/`derive` and the
`SourceIndex` constructor/main token loop. Indexing/lexer lanes must preserve
exact offsets, token kinds, the optional proof and its post-scan failure check.
No fixtures, existing benchmark boundaries, Gradle configuration, consumer files,
or other repositories were modified.

Not checked: Android-host/device, Kotlin/Native/Linux, Apple, live playback,
retained-memory profiling or a quieter-host repeat. The parent owns final platform
verification and the independent Codex review; this worker ran no Codex review.
