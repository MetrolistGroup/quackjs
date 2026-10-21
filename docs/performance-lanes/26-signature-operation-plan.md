# Lane 26: signature operation plan (not retained)

## Decision and scope

Do not integrate a production fast path from this lane. The existing interpreter
already spends roughly 4 microseconds per real signature in the diagnostic run.
A second execution/cost model is not justified by the small mixed-workload
headroom measured here. This is an abandoned exploration, **not a measured
optimization**; no direct-operation candidate was implemented or timed.

Retain the semantic regressions if useful to future signature work. Exact touched
paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/SignatureOperationPlanLane26Test.kt`
- `docs/performance-lanes/26-signature-operation-plan.md`

No production, public API, dependency, fixture, oracle, Gradle configuration,
benchmark boundary, or consumer changes. The temporary JVM inspection test was
removed; its source and results remain under `/tmp/quackjs-perf32/26-*`.

## Hypothesis and structural inspection

Start at the parsed Faraday signature expression and use the existing
`SourceIndex`, `Parser`, and numeric `specialize` pass. A signature-specific plan
could replace the proven split/helper/reverse/splice/swap/join sequence with
Kotlin operations while leaving unproven shapes on the interpreter. No hashes,
player identifiers, extra JS parser, or probe-derived operation inference are
needed or acceptable.

Inspected `PlayerProgram.compile`/`decipherSignature`, every runtime invocation and
specialization caller, syntax binding, source mutation checks, resource charging,
and parity/concurrency/benchmark consumers. The temporary inspection used the
existing runtime's specialized AST and helper function bodies, not regex matching.
All four fixture roots have classic helper sequences. Three use declarations and
blocks; `5008be0a` uses an ordered comma-expression chain with assignments. Helper
and built-in method names are indirect string-table reads, even after numeric
specialization. Those reads are deliberately not globally constant-folded.

The proof obligations are larger than recognizing a helper's name or a successful
compile probe:

- Match the complete root/helper bodies, parameter binding, local aliasing,
  return values, receiver/arguments use, and operation order. Extra helper effects
  must execute or force fallback, even if absent on the 64-unit compile challenge.
- Preserve live helper rebinding, mutable table keys and numeric globals between
  operations; cached initializers do not establish immutable JS values.
- JS `%` is a signed remainder. Negative and NaN keys are properties, not
  Kotlin indices. Fractional property coercion is currently rejected and must
  stay rejected. A classic swap on an empty array first grows index zero, then
  recomputes the second remainder using the new length. UTF-16 surrogate halves
  remain individual elements.
- Preserve argument/frame, lazy-global, split substring, splice removed-array,
  output and traversal charges, including discarded results. A `CharArray` alone
  cannot model undefined elements/property writes. Replaying interpreter calls
  preserves these details but gives up much of the proposed fast path's benefit.

This does not prove a compact safe implementation impossible. It is the reason
not to add a speculative execution framework before establishing worthwhile
headroom. The general interpreter remains the sole execution path.

## Diagnostic headroom measurement

One locked JVM inspection test compiled each complete player normally, checked
all outputs during timing, ran 20 warmups and 31 samples, and measured separate
16-repeat signature-only and n-only batches. These are diagnostic medians, not
changes to the official benchmark. They omit JSON serialization and run the two
kinds separately, so their sum is not an official mixed-batch measurement.

| Player | 256 signatures, ms | 240 n transforms, ms | Signature share of sum |
| --- | ---: | ---: | ---: |
| 4f38b487 | 0.963 | 51.797 | 1.82% |
| 10733253 | 0.943 | 45.122 | 2.05% |
| eb27e5fd | 0.964 | 42.474 | 2.22% |
| 5008be0a | 1.039 | 39.365 | 2.57% |

Even deleting all signature execution would save only about 1.8–2.6% of that
split workload. A real fast path must still split, mutate, join, initialize state,
and charge budgets. The shared full-benchmark baseline varies by more than this
between runs. This is headroom evidence, not proof that a signature-only workload
could not benefit or that a candidate has zero speedup.

Inspection command (under the mandatory shared lock):

```sh
./gradlew --no-daemon --max-workers=2 jvmTest \
  --tests '*Lane26SignatureInspectionTest' --console=plain
```

Logs/source: `/tmp/quackjs-perf32/26-inspection.log`,
`26-inspection-results/TEST-meow.nyx.quackjs.verification.Lane26SignatureInspectionTest.xml`,
`26-Lane26SignatureInspectionTest.kt`. Inspection passed, exit status 0.

## Full benchmark and correctness

Final `jvmTest`: **57 tests, zero failures/errors/skips**, including eight new
Lane26 common regressions, existing common/resource tests, all four real-player
parity suites, QuickJS oracle parity and concurrent-call isolation. Full
`playerBenchmark` passed all 124 full-player golden cases before timing.

Official medians below are milliseconds, Kotlin only. Baseline columns give the
two shared pre-lane runs. Lane26 uses identical production code: this is a
verification rerun, not an optimized before/after comparison.

| Player | Shared cold 1 / 2 | Lane26 cold | Shared warm 1 / 2 | Lane26 warm |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 124.786 / 121.488 | 145.837 | 57.042 / 53.073 | 69.294 |
| 10733253 | 135.474 / 134.351 | 160.126 | 48.456 / 45.793 | 44.500 |
| eb27e5fd | 135.206 / 138.892 | 130.838 | 47.711 / 43.456 | 42.045 |
| 5008be0a | 145.202 / 142.167 | 133.308 | 38.905 / 41.172 | 37.404 |

Host/JIT noise is visible even without a production patch. No gain or regression
is attributed to this lane. Cold remains compile plus first signature/n; warm
remains the original 496-transform serialized batch with 20 warmups/31 samples.

Final commands, serialized under the mandatory lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/26-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/26-benchmark.log 2>&1
'
```

Final logs: `/tmp/quackjs-perf32/26-tests.log`, `26-benchmark.log`,
`26-test-results/`, and `26-validation.exit` (0). Shared comparison logs:
`baseline-benchmark-1.log` and `baseline-benchmark-2.log` in the same directory.
Every Gradle/benchmark invocation in this lane used the shared lock.

The eight Lane26 common tests cover ordered classic operations; empty/short and
surrogate inputs; signed/NaN modulo and empty-array growth; splice truncation,
negative/infinite/NaN counts and returned removed elements; probe-equivalent but
input-dependent helper bodies; helper side effects, live rebinding/table keys and
numeric globals; aliases, receiver, arguments and return-value use; unsupported
operations/fractional property coercion/external mutation; and step, allocation,
challenge/output limits plus thrown helper errors. Resource failures cannot be
swallowed by player `catch` blocks.

Initial suite run failed two newly written tests, not existing tests:
`26-tests-initial.log` and `26-initial-test-results/`. The tests initially assumed
fractional property coercion was supported and assigned a lazy global before
reading it. Corrected the former to assert the existing rejection and explicitly
read the latter before mutation. No runtime behavior was changed to accommodate
the tests. The benchmark was not run after that failed suite.

Selected JS edge expectations were independently checked with
`node /tmp/quackjs-perf32/26-signature-edge-oracle.cjs` (28 assertions, pass;
`26-signature-edge-oracle.log`). These validate test expectations only, never
provide evidence for inferring operation semantics.

## Integration and unchecked work

Recommend the report and common regressions only; do not claim a performance
improvement. No production conflicts with other lanes. Future signature plans can
reuse these regressions, but must add plan-selection and forced-fallback parity
checks when an actual implementation exists.

No Android-host, Native, Apple, hardware, consumer, or live-network verification
was run in this lane. Parent owns final cross-platform/consumer checks. No own
Codex review was run; parent reviews the complete committed change.
