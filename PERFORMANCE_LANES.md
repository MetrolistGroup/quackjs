# 32-lane performance experiment

User requested 32 GPT-6.1-sol implementation/verification lanes. Each lane is an
independent experiment on the same baseline, NOT permission to merge 32 patches
blindly. Parent will inspect reviews, combine compatible winners and benchmark
again. No pushes or modifications to original consumer repositories.

## Baseline and invariants

Core baseline: `4d74080` (`a7e66f5` runtime). See README.md and docs/benchmark.md.
Fresh pre-lane results on this host:
`/tmp/quackjs-perf32/baseline-benchmark-1.log`,
`/tmp/quackjs-perf32/baseline-benchmark-2.log`, and `baseline-tests.log`.
Four full players / 124 Node and QuickJS goldens. API:
`meow.nyx.quackjs.PlayerProgram.compile(playerJs, hints?)`,
`decipherSignature`, `transformN`, `signatureTimestamp`.

Production remains common Kotlin and stdlib only, Android/JVM/Linux/iOS.
No output caching, hardcoded player IDs/algorithms, benchmark input special cases,
native/JS-engine dependencies, public API changes, or weakening rejection,
resource budgets, mutation detection, JavaScript semantics, or concurrent-call
isolation. Do not remove/disable tests. Preserve probe and unsupported-input
behavior. Optimization must be justified for arbitrary matching code/inputs.
Do not broaden the supported JS subset as a performance shortcut.

Read the relevant core and all callers before editing. Include focused common
regressions for nontrivial logic. Prefer one small independent patch; abandon a
losing idea instead of committing speculative complexity. Keep work inside your
assigned subsystem; related minimal changes elsewhere are allowed, but report
them for integration conflicts. Do not rewrite unrelated files or reformat the
whole runtime. New regression test filenames should include your lane number to
avoid clashes. No UI tests. Conventional commits and working branches.

## Shared machine discipline (mandatory)

32 agents share 32 logical CPUs and 32 GiB RAM with other work. Serialize ALL
Gradle builds, benchmarks, profilers and heavy commands using the SAME lock:

```sh
flock /tmp/quackjs-perf32.validation.lock bash -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest --console=plain > /tmp/quackjs-perf32/LANE-tests.log 2>&1 &&
  ./gradlew --no-daemon --max-workers=2 playerBenchmark --console=plain > /tmp/quackjs-perf32/LANE-benchmark.log 2>&1
'
```

Replace LANE with your unique lane number. Never launch a Gradle daemon/build or
benchmark outside the lock; never kill others' processes or `gradle --stop`.
Set sufficiently long command timeouts for lock waits. Run once initially after
your patch, compare shared baseline; repeat only if promising or a fix needs it.
JVM parity/concurrency/common tests required. Parent runs final Android/Linux and
consumer verification. Native-specialist lane may run Linux tests under the lock.
Do not publish Maven Local snapshots in parallel. No changes to fixtures,
oracles, benchmark timing boundaries or Gradle config unless your task explicitly
owns verification tooling. A failed command is not a pass; inspect the logs.

Microbenchmarks/JFR may guide investigation, but retained performance claims must
include actual real-player results. Host noise is real; report neutral/negative
results honestly. Budget and unsupported-input tests must continue to pass.

## Deliverables

Commit your focused change and tests if justified (or just the honest report if
no safe win). Write `docs/performance-lanes/NN-name.md` with:
- hypothesis and exact touched paths;
- measured per-player cold/warm before and after (or why no gain established);
- commands/log paths, correctness and resource/concurrency regressions checked;
- whether you recommend integrating, and known interactions with other work;
- any work or platforms not checked.

Do not edit README.md, docs/benchmark.md, integrations/, or consumer snapshots.
Those are synchronized by the parent after selection. A clean automated review
is required but not sufficient for integration. Never claim measurements you did
not perform. Do not add dependencies or production configuration options.
