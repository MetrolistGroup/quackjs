# Measured performance

## After the 32-lane experiment

Final core: `25e10da`, measured 2026-10-21. Ten production optimizations selected
from [32 independent lanes](performance-lanes/summary.md), plus two verification
harnesses. The original benchmark inputs and timing boundaries are unchanged.

Environment: Linux x86_64, AMD Ryzen 9 7950X3D (32 logical CPUs), Temurin
21.0.12.1, JVM heap 512 MiB initial / 2 GiB maximum. QuickJS-KT JVM 1.0.15,
192 MiB native heap, 2 MiB JS stack, dedicated 8 MiB thread. No CPU pinning or
power-policy control; shared host. Heavy work from these lanes was serialized
with `/tmp/quackjs-perf32.validation.lock`.

`./gradlew playerBenchmark --console=plain`: cold medians use 11 samples after
3 warmups; warm medians use 31 samples after 20 warmups, each with 496 mixed
signature/n transforms. Both implementations check all four golden suites before
measurement and every timed output afterward. QuickJS uses a precompiled batch
bridge. Neither implementation caches challenge outputs. Full boundaries are in
[verification](verification.md#opt-in-comparison-benchmark).

### Unchanged control and final core

Each column is a separate JVM process. The control before and after the two
candidate runs uses the original `a7e66f5` runtime, including the same benchmark.
All times are milliseconds. **Lower is better.**

| Player | Control cold before / after | Final cold run 1 / 2 | Control warm before / after | Final warm run 1 / 2 |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 114.955 / 114.762 | 101.919 / 106.919 | 48.543 / 48.536 | 38.372 / 39.310 |
| 10733253 | 124.228 / 119.601 | 107.474 / 106.237 | 41.829 / 41.935 | 35.365 / 36.474 |
| eb27e5fd | 120.674 / 120.645 | 107.149 / 109.290 | 40.158 / 41.762 | 33.517 / 34.114 |
| 5008be0a | 119.919 / 122.344 | 108.462 / 112.073 | 36.408 / 36.248 | 30.269 / 31.140 |

Warm latency falls approximately **13–21% relative to the previous core** across
these measurements. Cold latency also improves, but its smaller differences are
more sensitive to host noise. These ranges are observations, not confidence
intervals or universal guarantees.

### Final core versus QuickJS

Times below are **QuackJS / QuickJS**, measured in the same alternating sample
sequence in each run.

| Player | Run 1 cold | Run 2 cold | Run 1 warm batch | Run 2 warm batch |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 101.919 / 243.147 | 106.919 / 247.104 | 38.372 / 52.008 | 39.310 / 52.681 |
| 10733253 | 107.474 / 270.245 | 106.237 / 256.322 | 35.365 / 45.439 | 36.474 / 45.696 |
| eb27e5fd | 107.149 / 258.978 | 109.290 / 264.179 | 33.517 / 45.880 | 34.114 / 46.701 |
| 5008be0a | 108.462 / 259.651 | 112.073 / 261.753 | 30.269 / 38.630 | 31.140 / 39.294 |

Cold preparation is **2.3–2.5× faster**. Warm mixed-batch latency is **20–27% lower**
(approximately **1.25–1.37× throughput**) on all four players in both runs. This
is a consistent improvement on this corpus, not proof of a universal advantage.
Network, process startup and native library loading are outside these timings.
Cold measurements supply Faraday hints; hint-free compilation is not timed.

Raw logs in `/tmp/quackjs-perf32/`:
`parent-baseline-benchmark.log`, `combined-benchmark-1.log`,
`combined-benchmark-2.log`, `parent-baseline-after.log`.

### JVM allocation and unseen-input acceptance

`./gradlew lane32AcceptanceBenchmark --console=plain` generates 112 deterministic
unseen valid challenges across the four original players, checks their results
against full-player QuickJS, checks repeated/concurrent calls, and measures
signature and n separately at lengths 16, 19, 32, 64, 86, 128 and 256.
Malformed n comparisons are diagnostics, not parity assertions, because that
oracle goes through a URL wrapper. Existing goldens are also checked.

For each player/kind/length: 10 warmups, 15 samples, 16 transforms/sample, with
result construction/JSON serialization included. `ThreadMXBean` measures calling
JVM-thread allocation, not retained/peak heap or QuickJS's native/worker memory.
Bytes/op below are equal-weight means of the seven length-group medians:

| Player | Signature bytes/op before → after | n bytes/op before → after |
| --- | ---: | ---: |
| 4f38b487 | 12,408.7 → 5,456.7 | 532,977.9 → 315,417.9 |
| 10733253 | 12,399.9 → 5,615.9 | 429,896.0 → 261,372.6 |
| eb27e5fd | 12,746.2 → 5,618.2 | 382,647.2 → 209,386.6 |
| 5008be0a | 12,631.9 → 5,623.8 | 404,948.9 → 246,271.1 |

Across those fixed equal-operation groups, **signature allocation falls 56% and
n allocation 41%**. Per-group changes vary. This is one allocation measurement
pair, not a GC-pause/energy result. Logs: `parent-baseline-acceptance.log` and
`combined-acceptance.log`; full per-length output remains there.

The largest structural reductions remove assignment closures/return wrappers,
small-call temporary lists, repeated callback argument arrays, and double copies
of split/template containers. The guarded n callback plan avoids repeated AST
traversal without caching its inputs/results or assuming immutable tables.

### Kotlin/Native extract workload

The opt-in [Native harness](performance-lanes/31-native-performance-verification.md)
uses the 15,171-unit eb27e5fd **dependency extract**, not full players. `konanc -opt`,
unchanged GC defaults; 3 warmups, 11 samples; output checks outside timing.
A baseline binary run was placed between two final-core runs:

| Version/run | Cold compile + first pair, ms | Warm 192 transforms, ms |
| --- | ---: | ---: |
| Final 1 | 8.407 | 36.345 |
| Original control | 5.030 | 37.239 |
| Final 2 | 4.706 | 36.133 |

Cold results are noisy and the warm difference is small. **No reliable Native
speedup or QuickJS-native comparison is claimed.** All output checks passed.
Logs: `combined-native-{1,2}.log`, `parent-baseline-native.log`. Android hardware,
Apple execution, memory retention, energy and live playback are unmeasured.

## Historical initial implementation

Before this optimization round, core `a7e66f5` was near warm parity rather than
consistently faster than QuickJS. Original two runs, milliseconds as
**QuackJS / QuickJS**:

| Player | Run 1 cold | Run 2 cold | Run 1 warm batch | Run 2 warm batch |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 122.824 / 247.360 | 109.943 / 250.988 | 52.606 / 60.500 | 49.318 / 52.627 |
| 10733253 | 128.539 / 283.492 | 118.846 / 267.654 | 48.826 / 50.019 | 41.974 / 44.335 |
| eb27e5fd | 128.784 / 286.599 | 121.964 / 262.502 | 43.695 / 52.807 | 40.812 / 46.308 |
| 5008be0a | 131.262 / 282.611 | 120.715 / 261.300 | 43.529 / 41.087 | 37.239 / 38.626 |

Original logs: `/tmp/quackjs-benchmark-final.log` and
`/tmp/quackjs-benchmark-final-2.log`. These older runs are retained for context,
not used as the paired control for the new speedup claims.
