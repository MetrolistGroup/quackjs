# Lane 14: typed operator dispatch

## Decision

**Report only. Do not integrate the prototype.** No repeatable real-player gain
was established. Production sources and tests are unchanged from `732ee13`
(runtime baseline `a7e66f5`). This commit adds only this report.

## Hypothesis and scope

Decode operators once when constructing `Unary`/`Binary` nodes, then use enum
(integer-switch) dispatch in the evaluator. This could avoid repeated string
switches and the `dropLast(1)` allocation for compound assignments.

The discarded prototype touched exactly:

- `src/commonMain/kotlin/meow/nyx/quackjs/Syntax.kt`: a 42-entry internal operator
  enum, compound-to-binary mappings, and one decoded field per unary/binary node.
  Original operator strings remained available for diagnostics and cold analyses.
- `src/commonMain/kotlin/meow/nyx/quackjs/Runtime.kt`: enum dispatch in unary and
  binary evaluation, including comparisons and compound assignments. No changes
  to evaluation order, resource accounting, references, or update expressions.
- `src/commonTest/kotlin/meow/nyx/quackjs/OperatorDispatchLane14Test.kt`: six
  temporary focused tests, run against both baseline and prototype, then removed.

Parser, lexer, exports, fixtures, benchmark boundaries and configuration were
unchanged. The callers/analyses in `Specialization.kt`, `PlayerProgram.kt`, and
`SourceIndex.kt` continued using the original strings. Unsupported operators
still failed when evaluated, not when an unreachable AST node was constructed.
No JavaScript constructs were added.

## What Kotlin already generates

Inspected actual Kotlin 2.4.20 JVM output using `javap -c -p` before implementing
the prototype:

- Unary dispatch: `String.hashCode()` followed by a 7-key `lookupswitch` and
  equality guards.
- Binary control/assignment dispatch: the same pattern with 16 keys.
- Arithmetic/comparison dispatch: a 21-key `lookupswitch`, with additional
  3-key switches inside the comparison branch.

These are hash-based switches, not linear scans or freshly constructed sets.
`assignmentOperators` membership checks occur in cold analyses, not these hot
operator paths. Compound assignment does call `StringsKt.dropLast` at runtime.
The prototype replaced the operator hash/equality switches with enum ordinal
mapping and `tableswitch` instructions and removed that substring call. Better
bytecode shape alone did not establish a useful end-to-end improvement.

Disassemblies: `/tmp/quackjs-perf32/14-runtime-before.javap` and
`/tmp/quackjs-perf32/14-runtime-after.javap`.

## Full-player measurements

Unmodified `playerBenchmark`, Linux x86_64, Temurin 21.0.12.1, 512 MiB initial /
2 GiB maximum heap. Each process validated all four players and 124 golden cases
with Kotlin and QuickJS before timing. Cold: 3 warmups, 11 samples; warm:
20 warmups, 31 samples, 496 transforms per batch. No result caching.

All values below are **Kotlin median milliseconds**, paired entries are run 1 /
run 2. Shared baselines are the pre-lane logs specified in `PERFORMANCE_LANES.md`.
Local execution order was baseline 1, prototype 1, prototype 2, restored baseline 2.

### Cold compile + first signature/n

| Player | Shared baseline | Local baseline | Enum prototype |
| --- | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 123.423 / 143.433 | 117.161 / 179.362 |
| 10733253 | 135.474 / 134.351 | 144.850 / 136.848 | 136.999 / 191.286 |
| eb27e5fd | 135.206 / 138.892 | 135.512 / 130.189 | 128.637 / 196.216 |
| 5008be0a | 145.202 / 142.167 | 132.251 / 131.391 | 138.239 / 199.705 |

### Warm batch

| Player | Shared baseline | Local baseline | Enum prototype |
| --- | ---: | ---: | ---: |
| 4f38b487 | 57.042 / 53.073 | 62.627 / 57.830 | 51.129 / 74.391 |
| 10733253 | 48.456 / 45.793 | 45.390 / 45.786 | 44.615 / 80.920 |
| eb27e5fd | 47.711 / 43.456 | 44.498 / 43.739 | 43.266 / 75.917 |
| 5008be0a | 38.905 / 41.172 | 39.172 / 39.990 | 38.154 / 61.587 |

The first prototype run looked promising for the first player, but improved the
other local warm results by only about 2–3%. The repeat was substantially slower,
including the unchanged QuickJS control: prototype warm QuickJS medians changed
from 58.617/48.950/50.747/43.045 to 77.265/67.193/66.213/59.478 ms in player order.
Host contention clearly varied, so the second run is not evidence of an intrinsic
enum regression either. The first-player Kotlin/QuickJS warm ratio was 0.872 in
prototype run 1 versus 0.879 in restored baseline run 2. Selecting only the best
prototype result would overstate the evidence. There is no reproducible benefit
here sufficient to justify duplicated AST metadata and another operator mapping.

## Verification and reproduction

Every Gradle invocation, benchmark and disassembly used the shared lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/14-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/14-benchmark.log 2>&1
'
```

Additional invocations used the same flags and lock, with these log paths under
`/tmp/quackjs-perf32/`:

- `14-baseline-tests.log`, `14-baseline-benchmark.log`.
- `14-tests.log`, `14-benchmark.log`, `14-benchmark-2.log` (prototype).
- `14-restored-tests.log`, `14-baseline-benchmark-2.log` (production restored,
  temporary focused tests still present).
- `14-final-tests.log` (temporary tests removed, final unchanged production).
- `baseline-benchmark-1.log`, `baseline-benchmark-2.log` (shared pre-lane baseline).

Baseline and prototype each passed **55 tests, zero failures/errors/skips**:
42 existing common API/resource/unsupported-input tests, the common real-player
slice, four full-player parity tests, QuickJS oracle verification, JVM concurrent
invocation isolation, and six lane-specific tests. Focused coverage included
all supported unary/binary/compound operators, string/numeric comparisons,
coercion, right-associative powers, reference evaluation once and before the RHS,
short-circuit side effects, original unsupported error messages, rejection of
`**=`, `&&=`, `||=`, `??=`, and exact expression step-budget boundaries.

All four benchmark processes completed successfully with full golden parity.
After removing the prototype and temporary tests, **all 49 original JVM tests
passed**; `git diff --check` passed. The restored-baseline benchmark also passed.

Local experiment artifacts, not part of the commit:
`14-experiment.patch`, `14-OperatorDispatchLane14Test.kt`, and XML results in
`14-baseline-test-results/` and `14-candidate-test-results/` under the same temp
directory. They preserve the tested experiment without retaining speculative code.

## Interactions and limits

The abandoned change would overlap AST/evaluator lanes and add decoding work
when specialization/substitution constructs nodes. The retained report has no
runtime integration conflicts. Keep the existing string representation unless a
more controlled benchmark demonstrates a repeatable gain.

No Android/device, Kotlin/Native/Linux, iOS, consumer migration, retained-memory,
energy, JIT assembly or isolated dispatch microbenchmark measurements were made.
Enum dispatch was the only prototype; a separate hand-numbered opcode design was
not tested. No automated Codex review was run by this worker; review belongs to
the parent. Host load limits the performance conclusion to **no gain established**,
not proof that typed dispatch can never help.
