# Lane10: small-arity call arguments

## Hypothesis and patch

Most interpreter calls have few arguments. Avoiding `map` iteration, using a
singleton list for one argument, and evaluating `.call`'s receiver separately
should reduce transient argument-list allocations without changing invocation.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: evaluate zero through three
  arguments directly; use an indexed, sized `ArrayList` for larger calls. The
  direct `.call` path evaluates its receiver first and builds only the tail,
  rather than mapping all arguments and then copying with `drop(1)`.
- `src/commonTest/kotlin/meow/nyx/quackjs/SmallArityCallsLane10Test.kt`: six common
  regressions covering both specialized and fallback paths.
- `docs/performance-lanes/10-small-arity-calls.md`: this report.

Every nonempty argument list remains invocation-local. No shared mutable
buffers, frame reuse changes, public API changes, dependencies, configuration,
fixtures, or benchmark changes. `invoke` still charges exactly `args.size + 1`
allocation units, and `functionEnv` still copies `arguments` snapshots. Indirect
`callMethod`, `.apply`, callbacks, closures, and n-plan construction are unchanged.

## Real-player measurements

Linux x86_64, Temurin 21.0.12.1, the unchanged `playerBenchmark`: 11 cold samples
after 3 warmups; 31 warm samples after 20 warmups; 496 transforms per warm batch.
Each benchmark passed all four full-player golden suites before timing.

Kotlin medians in milliseconds, each cell **cold compile+first signature+n /
warm batch**:

| Player | Shared baseline 1 | Shared baseline 2 | Local baseline | Patched 1 | Patched 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 57.042 | 121.488 / 53.073 | 118.333 / 50.389 | 115.450 / 51.078 | 149.867 / 60.866 |
| 10733253 | 135.474 / 48.456 | 134.351 / 45.793 | 122.460 / 43.610 | 122.214 / 44.747 | 218.049 / 79.800 |
| eb27e5fd | 135.206 / 47.711 | 138.892 / 43.456 | 126.517 / 43.048 | 136.207 / 45.276 | 226.127 / 77.541 |
| 5008be0a | 145.202 / 38.905 | 142.167 / 41.172 | 128.448 / 38.656 | 129.762 / 39.778 | 261.191 / 45.372 |

**No latency improvement established.** The first patched warm run is 1.4–5.2%
slower than the later local baseline. The second patched run has substantial
host interference: QuickJS also slows (for example, player 10733253 warm
45.499 -> 76.812 ms and cold 267.699 -> 394.476 ms). All samples, including the
bad repeat, are retained rather than selectively discarded.

A separate temporary JVM allocation inspection used `ThreadMXBean` current-thread
allocated bytes while executing actual player challenges and checking every
output. Each variant ran in a separate test JVM, with 30 warmup batches followed
by 11 samples of 496 transforms. These figures exclude result serialization and
are supplementary allocation measurements, not replacements for the benchmark.

| Player | Baseline bytes/transform | Patched bytes/transform | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 243,505 | 210,805 | 13.43% |
| 10733253 | 174,923 | 169,006 | 3.38% |
| eb27e5fd | 160,116 | 154,660 | 3.41% |
| 5008be0a | 156,286 | 150,550 | 3.67% |

This is one warmed allocation run per variant; it measures allocated bytes, not
retained heap, and does not establish the same reduction on other platforms.

## Verification and reproduction

All Gradle invocations, including baseline restoration and allocation inspection,
were serialized with `/tmp/quackjs-perf32.validation.lock`. Initial validation:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/10-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/10-benchmark.log 2>&1
'
```

Final validation repeated these tasks under the same lock, logging to
`10-tests-final.log` and `10-benchmark-2.log`. Both full JVM test runs passed:
55 tests, zero failures/skips, including 6 Lane10 tests, the 42 existing common
behavior/resource tests, full-player parity, QuickJS oracle, and eight-thread
concurrent invocation isolation.

Lane10 explicitly checks argument counts zero through five for normal and
`.call` invocations, callee/key/receiver evaluation order, thrown-argument
short-circuiting, absent/null/Undefined receivers and arguments, nested calls,
escaped argument snapshots and closures, callback captures, construction,
indirect `.call`, and exact allocation-budget exhaustion on every arity path.
`git diff --check` also passed.

Additional local artifacts, all in `/tmp/quackjs-perf32/`:

- `baseline-benchmark-{1,2}.log`: shared before measurements.
- `10-local-baseline-benchmark.log`: unmodified Runtime in this worktree.
- `10-Runtime-{baseline,patched}.kt`: exact compared implementations; the
  baseline came from `git show HEAD:src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`
  before the lane commit (base `732ee13`). No branch operations were used.
- `10-PlayerAllocationInspectionLane10Test.kt`: temporary measurement source,
  copied into JVM test sources only for inspection and removed afterward.
- `10-allocation-{before,after}.log` and `.xml`: successful inspection builds
  and byte-count results. Each ran
  `./gradlew --no-daemon --max-workers=2 jvmTest --tests '*PlayerAllocationInspectionLane10Test*' --console=plain`
  under the shared lock, with the corresponding Runtime file installed.

## Integration recommendation and limits

Recommend considering this small patch for its measured allocation reduction,
not claiming a speedup. Parent should repeat timings on the selected combined
patch under quieter load before treating latency as neutral or improved.

Likely textual conflicts: other lanes editing Runtime's `Call` branch. No
changes to `invoke`, `functionEnv`, native method implementations, parser,
specialization, or n-plan logic, so no intended semantic dependency on other
lanes. Keep all six regressions when combining patches.

Android, Kotlin/Native/Linux, iOS, consumer publication/integration, retained
memory, and energy were not checked. No output caching or shared argument
buffers were introduced. Automated Codex review is left to the parent.
