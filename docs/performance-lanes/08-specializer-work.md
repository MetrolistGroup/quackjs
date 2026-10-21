# Lane08: specialization work

## Result and recommendation

Keep only the small declaration-prefix rewrite: specialize declarations in source
order during the output traversal, instead of storing and retrieving them through
a structurally hashed `Map<Vars, Vars>`. This removes a temporary map and recursive
AST hashing, without adding a cache, duplicating syntax, or changing folding rules.

**No reliable end-to-end performance gain established.** The final benchmark was
slower across both Kotlin and the unchanged QuickJS control. Do not select this as
a measured performance winner; consider it only as a small work/allocation cleanup.
A larger immutable-write-metadata cache was tried and removed for lack of evidence.

Final touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Specialization.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/Lane08SpecializationTest.kt`
- `docs/performance-lanes/08-specializer-work.md`

## Hypothesis and scope

The old prefix map structurally hashed declaration expressions on insertion and
on every variable-statement lookup, even though only the initial, ordered sequence
of declarations needs special treatment. One top-level pass can do that work
without a map. Earlier initializers must still see later locals as uninitialized.

Write analysis, closure traversal, expression folding and all their execution
charges remain unchanged. `arguments` still disables specialization. Assignments,
updates, catch bindings, duplicate parameters and live globals keep their existing
handling. No table folding, no-op elimination, constant-operation memoization,
unsupported-subset expansion, API, fixtures, dependencies or Gradle changes.

## Real-player measurements

JVM medians in milliseconds. Cold means compile plus first signature/n pair; warm
means 496 transforms. Shared pre-lane baselines versus the **final prefix-only**
patch, with the existing benchmark boundaries and full golden validation:

| Player | Baseline 1 cold | Baseline 2 cold | Final cold | Baseline 1 warm | Baseline 2 warm | Final warm |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 | 121.488 | 231.022 | 57.042 | 53.073 | 63.665 |
| 10733253 | 135.474 | 134.351 | 215.922 | 48.456 | 45.793 | 74.809 |
| eb27e5fd | 135.206 | 138.892 | 184.235 | 47.711 | 43.456 | 71.156 |
| 5008be0a | 145.202 | 142.167 | 199.588 | 38.905 | 41.172 | 58.432 |

The unchanged QuickJS control also slowed substantially:

| Player | Baseline 2 QuickJS cold / warm | Final QuickJS cold / warm |
| --- | ---: | ---: |
| 4f38b487 | 265.894 / 57.597 | 339.012 / 78.882 |
| 10733253 | 277.667 / 47.569 | 395.179 / 62.051 |
| eb27e5fd | 281.623 / 49.018 | 404.916 / 62.697 |
| 5008be0a | 278.093 / 43.121 | 393.320 / 60.575 |

This is not a controlled estimate of the patch's effect. Host scheduling/JIT/GC
noise cannot be separated from the change with these runs. No warm improvement
is expected from removing compile-time map work.

Logs: `/tmp/quackjs-perf32/baseline-benchmark-{1,2}.log`,
`/tmp/quackjs-perf32/08-benchmark.log`.

## Abandoned metadata cache and profiling

The first experiment additionally cached each function's immutable written-name
set and syntax-node count on `FunctionExpr`, charging the full node count on every
specialization. Its initial benchmark was mixed; see `08-cache-benchmark.log`.
A paired baseline/cache experiment used JFR `settings=profile` on the benchmark
JVM, with no changes to fixtures or measured boundaries:

| Player | Baseline cold / warm | Cache + prefix cold / warm |
| --- | ---: | ---: |
| 4f38b487 | 133.962 / 56.678 | 140.926 / 59.584 |
| 10733253 | 143.768 / 48.533 | 135.616 / 51.565 |
| eb27e5fd | 151.887 / 48.541 | 134.447 / 50.416 |
| 5008be0a | 152.099 / 42.970 | 139.687 / 44.491 |

Specialization appeared in just **one execution sample in each recording**
(1,459 total samples before, 1,493 after). `PlayerProgram.compile` appeared in
535/588 samples; source indexing dominated the sampled compile stacks. The
specialization sample did not include the outer compile frame, so these counts
cannot provide an exact specialization share. They are too sparse to establish a
specializer improvement and do not justify retaining per-function cache state.
The cache and its `Syntax.kt` changes were removed.

Artifacts under `/tmp/quackjs-perf32/`:
`08-profile-{before,after}-benchmark.log`, `08-{before,after}.jfr`,
`08-profile-{before,after}-samples.json`, `08-profile.init.gradle`, and saved
`08-{Specialization,Syntax}-{before,after}.kt` experiment sources.

## Verification and commands

Every Gradle build, benchmark and JFR processing command held the shared
`/tmp/quackjs-perf32.validation.lock`. Final validation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/08-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/08-benchmark.log 2>&1
'
```

Both passed. All 52 tests passed: 42 existing common program regressions, three
Lane08 common tests, the real-player slice, JVM concurrent-call isolation, four
full-player parity tests, and the QuickJS oracle suite. The benchmark independently
validated all four players / 124 goldens before timing.

Lane08 compares unspecialized and specialized execution over repeated dispatcher
variants, forward reads, declaration order, parameter redeclarations, captured
writes, catch shadows, assignment-target evaluation and mutable globals. It also
checks the `arguments` bypass and that every write-analysis syntax node, including
nested closures, is charged on every variant. Existing rejection, resource-limit,
probe and concurrency regressions passed unchanged.

The initial new-test run failed because its test source lacked the required n
class, not because of an implementation failure; the source was corrected before
all subsequent passing runs. Log: `08-tests-initial.log`. Cache validation is saved
as `08-cache-tests.log`. Final `git diff --check` passed.

Profile runs used the same lock and Gradle flags, adding
`-I /tmp/quackjs-perf32/08-profile.init.gradle` and
`QUACKJS_LANE08_JFR=/tmp/quackjs-perf32/08-{before,after}.jfr`.
`jfr print --json --events jdk.ExecutionSample` also ran under that lock. Original
sources were temporarily copied in for the baseline and the patch restored with
an EXIT trap; no branch operations were used.

## Integration and limits

Only the declaration-prefix portion of `Specialization.kt` needs reconciliation
with other specialization work, including lane25. No final `Syntax.kt` or runtime
changes remain. Native, Android, iOS, hardware, retained-memory and consumer checks
were not run; the parent owns those and the automated review. No further caching
or AST rewrite complexity is recommended on this evidence.
