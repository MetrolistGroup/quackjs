# Lane 17: string split and array construction

## Hypothesis and scope

`split('')` needlessly grows a temporary list, creates a substring for every
UTF-16 unit, and copies the list into `JsArray`. Constructing its final mutable
list at the known length and sharing a fixed table of 256 immutable Latin-1
strings should reduce allocations for arbitrary character transforms.

Touched paths:

- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: character-split fast path,
  fixed one-unit string table, and direct ownership of the general split result.
- `src/commonTest/kotlin/meow/nyx/quackjs/StringSplitLane17Test.kt`: six common
  semantic, mutation/isolation, size, allocation and step regressions.
- `docs/performance-lanes/17-string-split-arrays.md`: this report.

The general `Runtime.array(List)` helper still copies borrowed lists. Array
storage, template copying, literals, join and splice are unchanged. Every split
owns its mutable storage. Only the fixed immutable one-unit strings are shared;
there is no input, player, transform or output cache.

For legal character-result sizes, virtual charges remain exactly two steps per
unit, two allocation units per newly extracted character, and `size + 1` for the
result array. A whole one-character input is still reused without an extraction
charge. Oversized character-result arrays are rejected before constructing the
list, rather than after constructing 65,536 entries. Limited splits charge only
the visited prefix.

## Real-player measurements

JVM 21.0.12.1, Linux x86_64, Ryzen 9 7950X3D, 32 logical CPUs, 512 MiB initial /
2 GiB maximum benchmark heap. The unchanged benchmark validates all 124 goldens
with Kotlin and full-player QuickJS, then checks every timed output. Cold is
compile plus the first signature/n pair; warm is 496 transforms including result
construction/JSON. No CPU affinity or power-policy control.

Each cell is **Kotlin cold / warm median milliseconds**:

| Player | Local unmodified runtime | Candidate run 1 | Candidate run 2 |
| --- | ---: | ---: | ---: |
| 4f38b487 | 112.642 / 51.998 | 112.443 / 53.215 | 113.708 / 49.482 |
| 10733253 | 141.225 / 45.227 | 131.837 / 43.482 | 123.074 / 43.397 |
| eb27e5fd | 121.662 / 42.616 | 120.861 / 42.928 | 122.363 / 41.464 |
| 5008be0a | 123.608 / 39.771 | 125.066 / 40.865 | 119.852 / 37.924 |

Execution order was candidate 1, local baseline, candidate 2. The shared
`baseline-benchmark-1.log` and `baseline-benchmark-2.log` were also inspected;
the local baseline was generally faster than those historical measurements.
Candidate 2 warm times improve 2.7–4.8% against the adjacent local baseline, but
QuickJS warm times also improve 1.1–5.8%. Candidate 1 has three small warm
regressions relative to the local baseline. **No reliable wall-time speedup is
established.** Cold differences are likewise too noisy to attribute to split.

A temporary JVM-only inspection test measured current-thread allocated bytes
for the same actual-player 496-transform/JSON batches, outside compile/setup.
It discarded 20 batches and took the median of 11, checking all outputs. This
is an allocation diagnostic, not a replacement timing benchmark or a retained
memory measurement:

| Player | Before bytes/batch | After bytes/batch | Reduction |
| --- | ---: | ---: | ---: |
| 4f38b487 | 121,077,320 | 119,490,248 | 1.31% |
| 10733253 | 86,643,888 | 84,665,136 | 2.28% |
| eb27e5fd | 79,302,560 | 77,446,688 | 2.34% |
| 5008be0a | 77,403,232 | 75,570,400 | 2.37% |

## Verification and reproducibility

All Gradle/test/benchmark work, including baseline swapping and allocation
inspection, ran under the shared `/tmp/quackjs-perf32.validation.lock` flock.
The task commands were:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/17-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/17-benchmark.log 2>&1
'
```

The adjacent baseline/candidate comparison used the same commands while copying
`17-Runtime-before.kt` and `17-Runtime-after.kt` into the worktree under that
same lock, with an EXIT trap restoring the candidate. Local artifacts are under
`/tmp/quackjs-perf32/`:

- `17-tests-initial.log`: initial test compilation failure from insufficient
  assertion type inference; fixed before any benchmark execution.
- `17-tests.log`, `17-benchmark.log`: first successful candidate suite/run.
- `17-local-baseline-tests.log`, `17-local-baseline-benchmark.log`: baseline
  runtime passes all six new regressions too.
- `17-paired-tests.log`, `17-benchmark-2.log`: second candidate suite/run.
- `17-PlayerAllocationInspectionLane17Test.kt`, `17-allocation-before.xml`,
  `17-allocation-after.xml`: temporary ThreadMXBean diagnostic and raw results.
  The diagnostic source was removed from the worktree afterward.
- `17-final-tests.log`: final retained suite, **55 tests, zero failures/errors,
  zero skips**, including common tests, all four player parity tests, the
  independent QuickJS oracle and shared-program concurrency.

New common tests cover all Latin-1 units, NUL, BMP units, lone and paired
surrogates, empty input, missing/undefined/null/non-string separators and
receivers, overlapping/trailing separators, zero/fractional/wrapped/negative/
NaN/infinite/string/boolean limits, coercion failures and regex rejection,
independent mutable split arrays, defensive copying of borrowed array lists,
exact allocation/step boundaries, 65,536-element bounds and bounded work on an
8-million-unit source with limit one. Existing rejection, mutation detection,
resource, template-isolation and concurrency tests remain enabled and pass.

## Recommendation and limits

Recommend integration as a small allocation reduction, not a proven latency
win. The production diff is limited to the split implementation and one fixed
string table in `Runtime.kt`; other Runtime lanes may need contextual conflict
resolution. No new array representation or changes to join/splice are required.
The table permanently retains 256 tiny strings instead of caching arbitrary
inputs. Virtual resource accounting deliberately still charges for reused
characters.

Not checked here: Android-host/device, Kotlin/Native, Apple, consumer builds,
live playback, retained heap, GC pause time or energy. Parent verification must
cover other platforms and rebenchmark any combined runtime changes.
