# Lane03: SourceIndex first-pass recording

## Hypothesis and scope

Target matching copied a token list for every matched call, function header and
update, including calls later discarded by the class-depth filter. It also built
small membership lists while matching targets and mapped every assignment target
to strings just to recognize the class.

The change returns a start index into the existing 96-token deque, copying only
retained assignment/call targets. Updates and function headers need no target
copy. Prefix membership sets are shared, and class recognition compares the
three tokens directly. Once a class assignment establishes a depth, deeper call
records can be discarded: the final selected class depth can only decrease.
Calls at shallower depths, including calls before class discovery, remain.

All assignments and updates remain recorded, including nested builtin writes and
alias mutations. Class ambiguity and parameter/name shadowing still use the full
scan. A separate call counter includes discarded calls, preserving the 100,000
call limit. Other source, nesting, mutation, definition and rescan limits are
unchanged. Later effect-analysis algorithms, the lexer and the public API are
untouched.

Exact touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt`
- `src/commonTest/kotlin/meow/nyx/quackjs/SourceIndexLane03Test.kt`
- `docs/performance-lanes/03-source-index-recording.md`

## Verification and measurements

`jvmTest` passed: 56 tests, zero failures/errors/skips. This includes all seven
Lane03 regressions, 124 real-player Node/QuickJS goldens with hints, hint-free
landmark regressions, resource-budget regressions and the eight-thread
concurrent-call test. The benchmark also passed every full-player golden before
timing.

The initial test run failed only because the new grouped-reassignment test
expected a mutation error rather than the existing ambiguity error. The assertion
was corrected; no production change was needed. Initial log:
`/tmp/quackjs-perf32/03-tests-initial.log`; passing log:
`/tmp/quackjs-perf32/03-tests.log`.

Shared before measurements are the two fresh pre-lane runs supplied by the parent,
core `4d74080`, runtime `a7e66f5`:

- `/tmp/quackjs-perf32/baseline-benchmark-1.log`
- `/tmp/quackjs-perf32/baseline-benchmark-2.log`

Kotlin medians in milliseconds; warm batches contain 496 transforms:

| Player | Shared before cold 1 / 2 | After cold 1 | Shared before warm 1 / 2 | After warm 1 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 229.708 | 57.042 / 53.073 | 82.369 |
| 10733253 | 135.474 / 134.351 | 152.231 | 48.456 / 45.793 | 59.093 |
| eb27e5fd | 135.206 / 138.892 | 221.161 | 47.711 / 43.456 | 76.989 |
| 5008be0a | 145.202 / 142.167 | 148.841 | 38.905 / 41.172 | 41.618 |

The first after run establishes no improvement against the shared baseline.
Its QuickJS cold medians also rose to 393.717 / 378.085 / 428.600 / 305.813 ms,
versus 265.894–285.821 ms in the shared runs, indicating substantial host noise.
A second comparison therefore ran the baseline `732ee13` SourceIndex and then
the patch consecutively under one lock, in separate benchmark JVMs:

| Player | Local before cold | After cold 2 | Change | Local before warm | After warm 2 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 4f38b487 | 135.935 | 121.523 | -10.6% | 60.445 | 53.073 |
| 10733253 | 139.804 | 120.537 | -13.8% | 51.665 | 48.683 |
| eb27e5fd | 139.698 | 135.156 | -3.3% | 52.466 | 47.836 |
| 5008be0a | 141.663 | 132.010 | -6.8% | 50.140 | 39.033 |

Local QuickJS cold before/after controls were 266.061/275.682,
292.279/276.103, 288.218/312.191 and 296.146/286.292 ms respectively. The
second patch run also improved cold time against both shared baselines for
10733253 and 5008be0a; 4f38b487 and eb27e5fd were approximately within their
shared baseline ranges. This is promising cold-path evidence, not a reliable
universal speedup. Warm timing changes should be treated as noise: the patch
changes compilation, not transform execution. No warm-performance improvement
is claimed.

Commands, run from this worktree:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/03-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/03-benchmark-1.log 2>&1
'
```

The adjacent comparison temporarily copied the original SourceIndex from `732ee13`
into the worktree, then restored the exact tested patch; an exit trap restored the
patch even on failure. No branch was changed. Source snapshots for reproduction:
`/tmp/quackjs-perf32/03-SourceIndex-baseline.kt` and
`/tmp/quackjs-perf32/03-SourceIndex-patched.kt`.

```sh
flock /tmp/quackjs-perf32.validation.lock bash <<'SH'
file=src/commonMain/kotlin/meow/nyx/quackjs/SourceIndex.kt
trap 'cp /tmp/quackjs-perf32/03-SourceIndex-patched.kt "$file"' EXIT
cp /tmp/quackjs-perf32/03-SourceIndex-baseline.kt "$file" &&
./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/03-local-baseline-benchmark.log 2>&1 &&
cp /tmp/quackjs-perf32/03-SourceIndex-patched.kt "$file" &&
./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/03-benchmark-2.log 2>&1
SH
```

All three benchmark runs completed successfully with unchanged timing boundaries,
3 cold warmups / 11 cold samples and 20 warm warmups / 31 warm samples.

The seven Lane03 common regressions cover deque wraparound, grouped writes,
direct/computed/grouped/indirect/optional root calls, calls before class discovery,
shallower class discovery after deeper classes, shallow class ambiguity, nested
aliased builtin writes/updates/deletes, function-header shadowing, and both sides
of the 100,000-call limit for discarded nested calls.

## Integration recommendation and limits

Recommend integrating as a small cold-compilation/allocation candidate, subject
to the parent's combined-patch benchmark and review. The local paired run favors
the patch, but the noisy first run rules out a strong speed guarantee. Keep the
regressions and do not claim a warm win. No further experiments or dependencies
were added.

The patch overlaps Lane04 in `SourceIndex.kt`, but changes first-pass recording
and target matching rather than later effect analysis. Integrate carefully with
any lexer/scanner experiments, preserving the counter for every recognized call
and the monotonic class-depth invariant.

No Android, Linux Native, iOS, consumer or live-network verification was run in
this lane. No retained-heap or allocation-byte profiling was performed; reduced
target copying/retention is a code-path observation, not a measured byte claim.
