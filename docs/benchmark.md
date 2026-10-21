# Measured JVM performance

Measured on the combined core at `a7e66f5`, 2026-10-21, using
`./gradlew playerBenchmark --console=plain` in two separate JVM processes.
Linux x86_64, AMD Ryzen 9 7950X3D (32 logical CPUs), Temurin 21.0.12.1,
512 MiB initial / 2 GiB maximum JVM heap. No CPU pinning or power-policy control.
QuickJS-KT JVM 1.0.15, 192 MiB native heap, 2 MiB JS stack, dedicated 8 MiB thread.

All four full-player golden suites passed before timing. Cold medians use 11
samples after 3 warmups. Warm medians use 31 samples after 20 warmups, each
containing 496 transforms. No challenge-result cache is used by QuackJS.
Both warm paths build/serialize results; QuickJS runs a precompiled batch bridge.
See [methodology](verification.md#opt-in-comparison-benchmark) for the boundaries.

Times are milliseconds, shown as **QuackJS / QuickJS**.

| Player | Run 1 cold | Run 2 cold | Run 1 warm batch | Run 2 warm batch |
| --- | ---: | ---: | ---: | ---: |
| 4f38b487 | 122.824 / 247.360 | 109.943 / 250.988 | 52.606 / 60.500 | 49.318 / 52.627 |
| 10733253 | 128.539 / 283.492 | 118.846 / 267.654 | 48.826 / 50.019 | 41.974 / 44.335 |
| eb27e5fd | 128.784 / 286.599 | 121.964 / 262.502 | 43.695 / 52.807 | 40.812 / 46.308 |
| 5008be0a | 131.262 / 282.611 | 120.715 / 261.300 | 43.529 / 41.087 | 37.239 / 38.626 |

Cold preparation is consistently 2.0–2.3× faster here. Warm throughput is near
parity, with one 6% regression and improvements up to 21%. The smaller differences
are sensitive to host scheduling/JIT/GC; this does **not** establish a reliable
warm speed advantage. Warm Kotlin averages approximately 75–106 microseconds per
transform across these mixed signature/n batches, not a per-call latency percentile.

The first implementation was slower than QuickJS on warm batches. Removing
exception-based return flow, using indexed local slots, specializing numeric
dispatch branches, copying safe constant templates, and reusing nonescaping
callback frames brought it near parity without hardcoding player algorithms or
caching outputs.

Raw local logs: `/tmp/quackjs-benchmark-final.log` and
`/tmp/quackjs-benchmark-final-2.log`. Network, process startup, Android hardware,
Kotlin/Native performance, retained memory and energy were not measured. Faraday
hints are supplied during cold measurements; hint-free compilation is not timed.
