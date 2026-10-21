# 32-lane optimization result

All 32 requested GPT-6.1-sol implementation/verification lanes ran against
`732ee13` (runtime unchanged from `a7e66f5`). Ten production patches and two
verification harnesses were integrated. The final runtime is `25e10da`.
Production remains common Kotlin/stdlib only; no public API changes, challenge
output caches, fixture-specific constants, or relaxed safety budgets.

## Selection

| Lane | Experiment | Final disposition |
| --- | --- | --- |
| 01 | Lexer token hot path | Not applied: no reliable speedup |
| 02 | Quoted-string decoding | Not applied: no reliable speedup |
| 03 | Source-index recording | Applied: avoid unused target copies and deeper call retention, preserving counters |
| 04 | Source-analysis rescans | Worker discarded candidate |
| 05 | Parser allocation | Worker discarded candidate; automated review failed on tool authentication, no code imported |
| 06 | Landmark discovery | Not applied: more plumbing without demonstrated gain |
| 07 | n discovery planning | Worker discarded candidate |
| 08 | Specializer work | Not applied: inconclusive benefit |
| 09 | Assignment references | Applied: eliminate temporary references and capturing lambdas |
| 10 | Small-arity calls | Applied: fewer argument-list copies/allocations |
| 11 | Function-frame reuse | Worker discarded candidate |
| 12 | Global resolution | Worker discarded candidate |
| 13 | Control flow | Applied: remove return wrappers and statement iterators |
| 14 | Operator dispatch | Worker discarded candidate |
| 15 | Expression execution plans | Worker discarded candidate: regression |
| 16 | Loop execution | Worker discarded candidate |
| 17 | String split | Applied: avoid double copying; reuse immutable single-character strings |
| 18 | Array mutation | Applied: fewer splice/slice/unshift temporaries |
| 19 | Array join | Not applied: no established gain |
| 20 | String search | Not applied: extra budget machinery without established whole-workload gain |
| 21 | Numeric coercion | Worker discarded candidate |
| 22 | Native callbacks | Applied: reuse private callback arguments, avoid duplicate frame setup |
| 23 | Builtin construction | Not applied: tiny allocation saving without throughput evidence |
| 24 | Member access | Worker discarded candidate |
| 25 | Constant-table folding | Not applied: unresolved non-ASCII indexing bug, additional proof complexity/cold cost |
| 26 | Signature operation plan | Worker discarded candidate: little mixed-workload headroom |
| 27 | n character callback | Applied: narrow structural recognizer with live guards and interpreter fallback |
| 28 | Template copying | Applied: single-pass private container copies |
| 29 | Function metadata | Worker discarded candidate |
| 30 | n argument setup | Applied: avoid environments for literal/root arguments |
| 31 | Native benchmark | Verification harness retained; no production optimization from this lane |
| 32 | JVM acceptance/allocation | Verification harness retained; unseen QuickJS parity and per-kind measurements |

The numbered reports preserve each worker's original evidence, including failed
experiments and noisy samples. They are historical reports, not a claim that all
32 patches were applied or that every individual optimization independently wins
on latency. Tests for the selected changes were integrated; rejected experiments'
additional test files remain on their local branches.

Thirty lane reviews were clean. Lane05 had a review infrastructure failure;
lane25 retained a correctness finding. Every selected implementation lane passed
its review. A further read-only review of the **combined** patch against
`732ee13` found no actionable regression (`/tmp/quackjs-perf32/combined-review.log`).
The only manual cherry-pick resolution combined lane09's deleted Reference class
with lane13's ReturnedNull marker; neither old allocation class was retained.

## Measured combined result

See [benchmarks](../benchmark.md) for both candidate runs and unchanged controls
before and after them. Across four full players:

- Warm mixed-batch latency is **13–21% lower than the previous QuackJS core**.
- It is **20–27% lower than QuickJS**, or roughly **1.25–1.37× throughput**, in
  both final runs. No claim is made for all players/platforms or every individual
  signature length.
- Cold compile + first signature/n is **2.3–2.5× faster than QuickJS**.
- In the separate fixed per-kind/length workload, JVM calling-thread allocation
  falls **56% for signature and 41% for n**, averaged with equal operation counts
  across the tested player/length groups. This includes result serialization and
  is not peak/retained memory or QuickJS native memory.
- The small Native extract workload has only a small warm difference and noisy
  cold results. No reliable Native speedup claim is made.

## Verification

- 111 JVM, 103 Android-host, 103 Linux Native tests passed, no failures/skips.
- Existing 124 independent Node/QuickJS goldens remain unchanged. The new harness
  verifies 112 additional seeded unseen valid challenges across all four players,
  reverse/repeated order and shared-program concurrency. Malformed n results are
  explicitly diagnostic, not claimed oracle parity, because the oracle traverses
  a URL wrapper.
- Safety regressions cover mutation/aliasing, argument and reference evaluation
  order, retained closures/arguments, sparse/empty arrays, UTF-16, expression
  depth and exact resource-budget boundaries.
- Android/JVM/iOS/Linux publications build. Production metadata is stdlib-only;
  JVM/Android artifacts contain 72 Java-17 classes and no native/JS payload.
- The seven-file final core was synchronized into the isolated InnerTubeX
  migration, then formatting/API/build/publication checks and 382 desktop,
  375 Android-host and 29 harness tests passed. Published consumer tests passed.
- Metrolist's isolated shared tests, debug APK and desktop assembly passed using
  those final publications; the selected graph and APK contain no QuickJS/EJS.

Heavy builds, profilers and benchmarks used a shared `flock` to serialize work.
The host was not otherwise guaranteed idle. Final logs are in
`/tmp/quackjs-perf32/`; all timing tables are retained in this repository.
Consumer patches still target their captured dirty baselines. Original consumer
repositories were not edited or pushed.

Not checked: live CDN playback, Android hardware, Apple execution/final linking,
release shrinker/distributables, or universal historical/future player support.
