# Lane 31: independent Kotlin/Native verification

## Decision and scope

Retain an opt-in, allocation-sensitive Native workload; **no production
optimization or speedup claim**. Hypothesis: repeated compilation and invocation
of the existing real-player extract can expose Native interpreter/GC costs
without JVM intrinsics or changes to the full-player JVM benchmark. Profiling
was not reliable enough to justify a common-runtime change.

Exactly two new paths:

- `src/commonTest/kotlin/meow/nyx/quackjs/NativePerformanceLane31.kt`
- `docs/performance-lanes/31-native-performance-verification.md`

The workload calls the existing public API and `realPlayerSlice` from
`RealPlayerSlice.kt`. Its six input/signature/n triples are copied from the
existing independently checked `RealPlayerTest`, including Unicode, one-character
and empty inputs. No fixture, oracle, production source, export, dependency,
Gradle configuration, or existing JVM benchmark changed. The public entry
function exists only in test sources: Native's explicit `-entry` lookup requires
public visibility. It has no `@Test` annotation and adds no default test work.

**This times the 15,171-UTF-16-unit `eb27e5fd` dependency extract, not any complete
player.** Input lengths are 19, 25, 64, 9, 1 and 0 UTF-16 units. The other three
players have no Native timings here. Full-player JVM correctness below is
separate evidence, not full-player Native performance. There is no QuickJS
Native implementation or comparison in this lane.

## Reproduction and timing boundaries

From the repository root, after Gradle has downloaded Kotlin/Native 2.4.20:

```sh
mkdir -p /tmp/quackjs-perf32
flock /tmp/quackjs-perf32.validation.lock bash -eu -c '
  ./gradlew --no-daemon --max-workers=2 jvmTest linuxX64Test --console=plain \
    > /tmp/quackjs-perf32/31-tests.log 2>&1
  native="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-2.4.20"
  "$native/bin/konanc" -opt \
    -entry meow.nyx.quackjs.nativePerformanceLane31 \
    -o /tmp/quackjs-perf32/31-native-workload \
    src/commonMain/kotlin/meow/nyx/quackjs/*.kt \
    src/commonTest/kotlin/meow/nyx/quackjs/RealPlayerSlice.kt \
    src/commonTest/kotlin/meow/nyx/quackjs/NativePerformanceLane31.kt \
    > /tmp/quackjs-perf32/31-native-build.log 2>&1
  for run in 1 2; do
    /tmp/quackjs-perf32/31-native-workload.kexe \
      > /tmp/quackjs-perf32/31-native-run-$run.log 2>&1
  done
'
```

This deliberately uses an explicit compiler invocation, not a new Gradle task or
an ignored test that the Native runner cannot enable. The binary uses `-opt`
only and the toolchain's unchanged runtime/GC defaults. Source loading,
`trimIndent`, hints, vector construction and a checked full-vector preflight
are outside both timers. Both phases discard three warmups and retain eleven
samples; raw nanoseconds and the median are printed.

- **Cold program:** fresh `PlayerProgram.compile(source, hints)` plus the first
  signature/n pair and its two-element output list. Source is already in memory;
  this is not process startup, full-player loading or an empty-runtime measure.
- **Warm batch:** one precompiled program, all six pairs repeated sixteen times
  (192 transforms/sample). Includes per-call interpreter state, cloned tables,
  arrays/strings and output-list construction. No output cache, JSON bridge or
  serialization is involved. This repeats allocation-producing public API work;
  it does not count allocated bytes.
- Every warmup and measured sample compares **all** outputs with fixed expected
  strings after stopping the monotonic timer. A mismatch aborts the process.
  No forced GC, elapsed-time assertions or console I/O inside timed regions.
  Ordinary GC and scheduling can affect samples.

## Measurements

Baseline checkout: `732ee13` (production runtime unchanged from `a7e66f5`).
Measured 2026-10-21 on Linux `7.2.11-1-cachyos`, x86_64, AMD Ryzen 9 7950X3D,
32 logical CPUs, `powersave` governor; Kotlin/Native 2.4.20, compiler host
Temurin JRE `21.0.12.1+1-LTS`. Shared host, not claimed idle; all builds,
workload executions and profiling attempts used the shared validation flock.

Two separate unprofiled processes, same optimized binary; medians in ms:

| Extract | Run | Cold compile + first pair | Warm 192 transforms |
| --- | --- | ---: | ---: |
| `eb27e5fd` | 1 | 5.263164 | 40.104773 |
| `eb27e5fd` | 2 | 5.727121 | 42.265002 |

Logs: `/tmp/quackjs-perf32/31-native-run-{1,2}.log`,
`31-native-build.log` (no diagnostics), and `31-native-version.log`.
There is **no before/after candidate**: both runs measure unchanged production
code, and the run-to-run difference is not an optimization result. No per-player
full-source Native cold/warm gain has been established.

Ancillary Python `resource.getrusage(RUSAGE_CHILDREN)` measurements are in
`31-native-process-resources.log`: whole-process wall times 0.646581/0.700900 s,
and child high-water RSS 21,064 then 21,272 KiB. That RSS statistic is cumulative
across the sequential children; it includes runtime/workload overhead and is
neither allocation volume nor retained `PlayerProgram` memory. No allocation
reduction is claimed.

## Profiling limits

Both attempts were under the same shared lock, against the `-opt` binary:

```sh
flock /tmp/quackjs-perf32.validation.lock perf record \
  -e cpu-clock:u -F 199 -g -o /tmp/quackjs-perf32/31-native-perf.data \
  -- bash -c 'for i in 1 2 3 4 5; do /tmp/quackjs-perf32/31-native-workload.kexe || exit; done'
flock /tmp/quackjs-perf32.validation.lock gprofng collect app -p 1 \
  -o /tmp/quackjs-perf32/31-native-profile.er \
  /tmp/quackjs-perf32/31-native-workload.kexe
flock /tmp/quackjs-perf32.validation.lock gprofng display text \
  -header -overview /tmp/quackjs-perf32/31-native-profile.er
flock /tmp/quackjs-perf32.validation.lock gprofng display text \
  -limit 40 -functions /tmp/quackjs-perf32/31-native-profile.er
```

- `perf` could not open even `cpu-clock:u`: permission denied with
  `perf_event_paranoid=2`. No permissions or system settings were changed.
  Failure log: `31-native-profile.log`; no usable perf profile was produced.
- `gprofng` ran and the workload's output checks passed, but the collector warned:
  **"Collection interval timer period was changed (1000 -> 0); profile data may
  be unreliable"**. Only 0.059 s of sampled CPU was reported for a 0.709 s
  process. GC mark/sweep, `Runtime.property`, `Runtime.eval` and allocator symbols
  were present, but their percentages are not trustworthy hot-path attribution.
  Logs: `31-native-gprofng.log`, `31-native-gprofng-functions.log`,
  `31-native-gprofng-header.log`; raw experiment: `31-native-profile.er`.
  Profiled timings are excluded from the table.
- An initial internal entry point failed Native lookup; making this test-only
  function public fixed it. An exploratory `-opt -g` build warned that the flags
  are unsupported together. Neither that failed build nor the mixed-flags
  timings are retained as measurements; diagnostic logs are
  `31-native-build-initial-failure.log`, `31-native-build-mixed-flags.log` and
  `31-native-run-mixed-flags.log`.

## Correctness and integration recommendation

- `jvmTest linuxX64Test`: passed, **49 JVM / 43 Linux tests**, zero failures,
  errors or skips. Linux ran the entire common suite, including resource/step/
  allocation limits, unsupported-input rejection, mutation detection, UTF-16
  semantics and per-invocation isolation. JVM additionally exercised four
  full-player parity cases, the original QuickJS oracle and shared-program
  concurrency. Initial execution: `31-tests.log`.
- Final sources compiled on both targets; JVM passed again (`31-tests-final.log`).
  Linux was explicitly executed again, not just accepted up-to-date:
  `flock /tmp/quackjs-perf32.validation.lock ./gradlew --no-daemon --max-workers=2 linuxX64Test --rerun --console=plain`
  (`31-linux-final.log`), again 43 passes.
- Both plain Native processes checked the preflight, fourteen cold pairs and
  fourteen warm batches each. These checks are the workload's assert-based
  self-verification, not new default tests. `git diff --check` passed.

Recommend integrating the workload/report as a reproducible Native diagnostic,
not a runtime optimization. Unique lane-31 files should not conflict with other
lanes; future runtime changes can rerun the exact workload, but require new
measurements and correctness checks. No production caller behavior changes.

Not checked: Native allocation counts/retained heap, reliable Native hot-path
profile, full-player Native performance, Android/device or Apple execution,
concurrent Native load, energy, live CDN inputs or consumer builds. The existing
JVM comparison benchmark was neither modified nor rerun; these extracted Native
numbers cannot be compared with its full-player timings.
