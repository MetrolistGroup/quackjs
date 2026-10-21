# Lane 02: lexer string decoding

## Hypothesis and scope

Unescaped quoted literals need one validated substring, not a builder append for
every UTF-16 unit. Keep the existing scan and escape decoder, but allocate a
builder only on the first backslash and append validated source spans between
escapes. This removes the builder from the unescaped path without a second scan.
No allocation profiler was run, so this is a description of the code change,
not a measured allocation reduction.

Exact touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`: only the quoted-string branch
  of `Lexer.next`; main token dispatch, comments, templates and regexes unchanged.
- `src/commonTest/kotlin/meow/nyx/quackjs/Lane02StringTest.kt`: five common tests.
- `docs/performance-lanes/02-lexer-string-decoding.md`: this report.

Read callers in `Lexer.tokens`, `PlayerProgram` and `SourceIndex`. No caller,
public API, dependency, fixture, oracle, benchmark or Gradle changes are needed.
The loop still checks every raw unit, rejects LF/CR, advances `position` exactly
as before, and uses the original escape validation/decoding switch. Token kind
and offset are unchanged. Spans are UTF-16 indices; NUL, surrogate pairs and lone
surrogates are copied without normalization. Existing identity escapes and raw
U+2028/U+2029 acceptance are preserved, not extended into new JS support.

## Real-player measurements

2026-10-21, same configured harness and JVM as the shared pre-lane runs:
Temurin 21.0.12.1+1-LTS, Linux amd64, 32 logical CPUs, 512 MiB initial / 2 GiB
maximum JVM heap. No CPU pinning or power-policy control. Before is the shared
baseline `4d74080` / core `a7e66f5`; after is this lane's string-only patch
on the documentation-only lane base `732ee13`.
Two after runs, both including complete Kotlin and QuickJS parity validation
before timing and output validation for every timed sample.

All numbers are **Kotlin / QuickJS median milliseconds**. Cold is compile with
hints plus the first signature+n pair, 11 samples after 3 warmups. Warm is 496
transforms on an already compiled program, 31 samples after 20 warmups.

### Cold

| Player | Before 1 | Before 2 | After 1 | After 2 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 279.818 | 121.488 / 265.894 | 119.169 / 260.878 | 156.249 / 300.810 |
| 10733253 | 135.474 / 284.865 | 134.351 / 277.667 | 140.989 / 286.992 | 146.717 / 299.975 |
| eb27e5fd | 135.206 / 273.167 | 138.892 / 281.623 | 147.726 / 318.512 | 149.383 / 311.406 |
| 5008be0a | 145.202 / 285.821 | 142.167 / 278.093 | 133.817 / 289.899 | 133.327 / 284.096 |

### Warm batch

| Player | Before 1 | Before 2 | After 1 | After 2 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 57.042 / 59.834 | 53.073 / 57.597 | 51.950 / 58.279 | 67.005 / 69.942 |
| 10733253 | 48.456 / 50.129 | 45.793 / 47.569 | 44.514 / 49.164 | 62.637 / 63.660 |
| eb27e5fd | 47.711 / 53.438 | 43.456 / 49.018 | 47.754 / 54.034 | 43.527 / 48.241 |
| 5008be0a | 38.905 / 41.128 | 41.172 / 43.121 | 39.360 / 42.295 | 41.500 / 44.398 |

The latest player improves in both cold runs, but the other players are mixed or
slower, and unchanged QuickJS and warm paths also move substantially. Run 1's
first/latest cold improvements warranted one repeat; run 2 does not confirm a
consistent corpus-wide win. **No reliable overall real-player speedup is
established.** Warm execution does not re-lex source, so no direct warm benefit
is expected. These are noisy standalone process medians, not a controlled A/B
or allocation measurement.

## Commands and correctness

Every Gradle invocation was serialized through the mandatory shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/02-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/02-benchmark.log 2>&1
'
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/02-benchmark-2.log 2>&1
'
```

Before logs: `/tmp/quackjs-perf32/baseline-benchmark-1.log` and
`/tmp/quackjs-perf32/baseline-benchmark-2.log`. All three lane commands succeeded;
`jvmTest` executed 54 tests, with zero failures, errors or skips. XML reports are
in this worktree's `build/test-results/jvmTest/`.

Checked:

- Both quote styles, empty/long literals, opaque punctuation, opposite quotes,
  raw controls/NUL, UTF-16 pairs and lone surrogates, raw U+2028/U+2029.
- Nonzero lexer start, whitespace, exact string offsets/positions, following
  punctuation/identifier and EOF boundaries.
- All explicit control escapes, escaped quotes/backslash/slash, identity
  escapes, mixed-case hex/Unicode, escaped NUL and surrogate units.
- LF, CR and CRLF continuations, consecutive escapes, long spans before/between/
  after escapes, malformed/truncated hex/Unicode, dangling backslash/quote,
  unclosed strings and raw LF/CR before and after the first escape.
- Legacy-octal rejection including a Unicode digit after `\0`.
- Existing common resource/rejection tests, including source size, nested source
  scan budgets (with a million-unit literal), syntax depth and runtime budgets.
- Four full-player suites / 124 Node goldens, independent original-player
  QuickJS oracle, reordered/repeated invocation parity, and shared-program
  concurrency/isolation test. Both benchmarks rechecked all full-player goldens.
- `git diff --check` passed.

## Recommendation and limits

Retain this small, correctness-verified candidate for the parent's comparison,
but **do not select it as a demonstrated performance winner yet**. Integrate only
if the parent's combined/controlled rerun supports the cold-path change; this
lane alone has insufficient timing evidence. It introduces no new decoding
rules or weaker budgets.

Lane 01 also edits `Lexer.next`; the logic is independent of dispatch changes,
but the parent must check nearby hunks and remeasure the combined method because
JIT/inlining effects can interact. No sibling files were edited and no automated
Codex review was run by this worker; review belongs to the parent.

Not checked: Android-host/device, Kotlin/Native Linux, Apple targets, external
consumers, hint-free benchmark, allocation/retained-memory/energy profiling or
controlled CPU scheduling. Parent owns cross-platform and final consumer checks.
