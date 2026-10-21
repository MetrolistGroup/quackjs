# Lane 05: parser/token-list allocation

## Outcome

**No repeatable real-player win established; production experiment discarded.**
Retain the focused regressions and this report, not the speculative parser change.
The final production sources are byte-identical to the lane baseline (`732ee13`,
whose production code matches `4d74080`). No API, Lexer, FunctionExpr metadata,
fixture, oracle, or build configuration changes are retained.

## Hypothesis and scope

The attempted patch cached Parser's current token instead of repeatedly indexing
`tokens[p]`, and supplied an implicit EOF after the list ended. This allowed
SourceIndex's `targetExpression` and `method` to stop copying an already-collected
target list solely to append an EOF token. In `slice`, it also hoisted the delimiter
set and kept the preceding lexical context in a local instead of looking up the
last token on each iteration. Slice token/depth checks, complete-expression
validation, AST validation and function construction remained in place.

Attempted production paths, restored after measurement:

- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt` (Parser only)
- `src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt`

Final touched paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/ParserAllocationLane05Test.kt`
- `docs/performance-lanes/05-parser-allocation.md`

Reviewed callers included signature parsing in PlayerProgram, target and selected
initializer parsing in SourceIndex, Runtime definition resolution, n-method
selection, and the existing common/parity/concurrency tests. No streaming lexer,
parse cache, new language feature, or FunctionExpr metadata rewrite was attempted.

## Full-corpus measurement

Milliseconds, Kotlin medians; warm batches contain 496 transforms. The two before
runs are the shared fresh baselines. The after column is the **discarded candidate**,
not a claimed improvement in the final test/report-only commit.

| Player | Before 1 cold | Before 2 cold | Candidate cold | Before 1 warm | Before 2 warm | Candidate warm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 | 121.488 | 136.668 | 57.042 | 53.073 | 53.457 |
| 10733253 | 135.474 | 134.351 | 136.274 | 48.456 | 45.793 | 44.996 |
| eb27e5fd | 135.206 | 138.892 | 140.475 | 47.711 | 43.456 | 45.394 |
| 5008be0a | 145.202 | 142.167 | 137.736 | 38.905 | 41.172 | 39.159 |

All four full players and all 124 goldens passed in both Kotlin and QuickJS before
timing. The existing benchmark boundaries and settings were unchanged: 3 cold
warmups/11 samples, 20 warm warmups/31 samples, Temurin 21.0.12.1, 512 MiB initial
and 2 GiB maximum heap. QuickJS timings are also present in the raw logs.

Cold preparation improved only for 5008be0a, was slightly slower for two players,
and regressed for 4f38b487. This does not justify retaining the added cursor/EOF
state. Warm timing excludes parsing and cannot substantiate a parser improvement;
its movement is consistent with host/JIT/GC noise. Allocation counts were not
profiled. There was one candidate benchmark run, with no repeat to chase the
isolated favorable result. The restored production sources were not benchmarked
again because they are unchanged from the baseline.

## Verification and logs

Initial candidate validation, entirely under the shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/05-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/05-benchmark.log 2>&1
'
```

Both commands succeeded. Candidate tests: 55 passed, including six temporary
Lane05 tests. After discarding the production experiment and the implicit-EOF-only
test, final validation was:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/05-final-tests.log 2>&1
'
```

**Final result: 54 tests passed, zero skipped/failures/errors.** This includes five
Lane05 common regressions, 42 PlayerProgram tests, the common real-player slice,
four full-player parity tests, the QuickJS golden check and JVM concurrent calls.
Existing resource/mutation/unsupported-input coverage, including the shared
source-scan budget regression, passed.

Retained Lane05 checks cover:

- Complete-expression rejection of trailing or incomplete input.
- Slicing at comma, semicolon, closing parenthesis/brace and physical EOF, while
  retaining nested delimiters, opaque strings/regexes and division context.
- Selected-function lexical slots, duplicate parameters, arguments, named function
  references and a closure capturing a catch-shadowed local.
- Parser recursion, left-associated AST depth and slice nesting limits.
- The exact selected-definition token boundary: 150,000 accepted, 150,001 rejected.

Baseline logs:

- `/tmp/quackjs-perf32/baseline-benchmark-1.log`
- `/tmp/quackjs-perf32/baseline-benchmark-2.log`
- `/tmp/quackjs-perf32/baseline-tests.log`

Final XML results: `build/test-results/jvmTest/`. `git diff --check` passed and
`git diff --exit-code -- src/commonMain` confirmed no retained production changes.

## Integration and limits

Recommend integrating only the regression tests/report if useful. There is no
performance patch to combine and no production conflict with other lanes. The
new test filename includes Lane05 to avoid test-file collisions. A separately
profiled allocation optimization may still be worthwhile, but these timings do
not establish one.

Not checked: allocation/retained-memory profiles, Android, Linux Native, Apple,
consumer builds or device execution. No publication, external repository changes,
manual Codex review, dependency additions or benchmark/oracle changes were made.
Parent review and cross-platform verification remain separate.
