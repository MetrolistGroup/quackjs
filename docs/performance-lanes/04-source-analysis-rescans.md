# Lane 04: SourceIndex analysis rescans

## Outcome

**Do not integrate a production optimization from this experiment.** No repeatable
real-player cold win was established. The candidate and its temporary regressions
were removed; this report is the only retained change. Production source remains
identical to baseline `732ee13` (runtime baseline `a7e66f5`).

## Hypothesis and scope

The write-analysis loop called `referenceBase`, then lexed the initializer's first
token again when no direct reference was found. Sharing that token and its lexer
would avoid duplicate decoding of opaque strings, templates and regexes without
adding a cache or changing alias/projection decisions. The candidate counted the
shared token toward the existing 1,024-token reference-path bound. All actual
lexing still used the same aggregate `ScanBudget`; no limits were increased.

Experimental paths, neither retained:

- `src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt`: only the later write loop
  and private `referenceBase` signature/body (8 insertions, 8 deletions).
- `src/commonTest/kotlin/meow/nyx/quackjs/SourceAnalysisLane04Test.kt`: three focused
  common tests.

Retained path: `docs/performance-lanes/04-source-analysis-rescans.md` only.

Inspected every `referenceBase`, `argumentReferences` and `definitionKind` caller,
mutation fixed-point propagation, public definition consumers in `PlayerProgram`
and `Runtime`, and existing common regressions. `referenceBase` is already called
once per write offset. The `argumentReferences` call sites mostly scan different
starting offsets, so exact-key memoization is not an obvious reuse opportunity.
`definitionKind` already caches proofs, deliberately avoids caching recursive
UNKNOWN results, and clears proofs when native String.split stability changes.
No additional context-sensitive memoization was attempted. The primary source
scan, dependency parsing API, mutation rules, fixtures and benchmark were untouched.

## Real-player measurements

Milliseconds, **Kotlin cold compile + first signature/n / warm 496-transform batch**.
Unmodified benchmark: 3 cold warmups + 11 samples, 20 warm warmups + 31 samples;
Temurin 21.0.12.1, 512 MiB initial / 2 GiB maximum JVM heap. All four full-player
Kotlin/QuickJS golden suites passed before each benchmark printed timings.

| Player | Shared baseline 1 | Shared baseline 2 | Local baseline | Candidate 1 | Candidate 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 126.505 / 51.188 | 113.877 / 51.870 | 125.892 / 57.724 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 134.938 / 45.788 | 123.218 / 44.884 | 137.787 / 50.167 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 132.260 / 42.634 | 132.114 / 47.926 | 158.985 / 52.266 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 130.834 / 41.128 | 143.346 / 42.841 | 167.971 / 68.254 |

Order of lane measurements: candidate 1, restored local baseline, candidate 2.
The initial two-player improvement justified the repeat, but did not reproduce.
The second candidate run also saw QuickJS cold medians move from local-baseline
265.068/272.280/279.345/274.338 to 274.593/287.689/305.226/308.876 ms. Together
with warm-path movement, this indicates substantial host variability. It does
not establish either a benefit or a reliable regression caused by this tiny
cold-only change. No further repeats were used to hunt for a favorable result.

## Verification and commands

Every Gradle invocation was serialized with
`flock /tmp/quackjs-perf32.validation.lock`; all commands exited successfully.
Initial candidate validation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/04-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/04-benchmark.log 2>&1
'
```

Separate serialized invocations of the same `playerBenchmark` command produced
`/tmp/quackjs-perf32/04-local-baseline-benchmark.log` after restoring baseline
SourceIndex and `/tmp/quackjs-perf32/04-benchmark-2.log` after reapplying the exact
candidate. Shared baselines are
`/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`.

The candidate passed **52 tests**, including three temporary Lane04 regressions:

- Escaped string literals and grouped primitive aliases stay primitive; opaque
  regex/template values remain conservatively unknown.
- Array/object containers, grouped aliases and projected origins propagate
  escapes; discarded native outer-array reversal does not mutate contents.
- Direct/aliased String mutation and multi-pass Array-to-String builtin blocking
  invalidate primitive array-length proofs.

The existing common suite also checked aggregate nested-call token/UTF-16 scan
budgets, recursive alias proof limits, shadowed/mutated read-only predicates,
primitive projections, unknown calls and externally mutated dependencies. JVM
parity, QuickJS oracle and concurrent-call isolation tests all passed.

The temporary test and candidate diff are archived locally as
`/tmp/quackjs-perf32/04-SourceAnalysisLane04Test.kt` and
`/tmp/quackjs-perf32/04-abandoned-candidate.patch`, not shipped in the repository.
After removing both, the final baseline worktree passed all **49 existing tests**:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/04-final-tests.log 2>&1
'
```

`git diff --check` also passed. No tests were disabled or removed from the baseline.

## Integration and unchecked work

Report-only: no source conflicts with lane03's primary scan or other lanes, no
cache retention, concurrency or supported-subset changes. Do not cherry-pick the
archived candidate as a demonstrated performance improvement.

Android, Kotlin/Native/Linux, iOS, consumer integrations, retained memory and
allocation profiles were not checked. Parent owns final platform verification.
No independent Codex review was run by this worker; parent reviews the complete
report locally.
