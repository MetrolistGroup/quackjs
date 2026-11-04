# Runtime folding: fromcharcode

Kept. The unconditional patch improves warm n throughput by 1.077 and signature
throughput by 1.060 in paired geometric means across 200 players. The baseline
repeat has warm n ratios of 0.983 at p5 and 1.020 at p95, with a 1.000 geometric
mean. Cold latency does not establish a gain. Cold allocation rises about 1.2%.

The number conversion returns an existing double below the primitive conversion
depth boundary. Single-argument fromCharCode reuses immutable Latin-1 characters;
other arities build the output without an intermediate list. Every path keeps
its original logical charges. Object conversion and runtime replacements still
run through the interpreter's normal calls.

A separate census wrapped the builtin in a JVM test process, reset counts after
compilation, and checked all 6,200 stored outputs. Both fast paths fired on
200/200 players. The wrapper preserved the builtin's function name and called the
original native body directly. It was absent from the benchmark and gates.

Validation: 200/200 players passed both hinted and hint-free verification;
158 JVM tests passed with zero failures, errors or skips. Focused common tests
cover empty and non-ASCII UTF-16 results, numeric wrapping and special values,
object side effects and aliases, runtime replacement, exact step/allocation
limits and the conversion-depth boundary. No fixtures or goldens changed.

Full benchmark, five rounds, using bench-lane runtime-folding-fromcharcode:

```text
baseline-a -> runtime-folding-fromcharcode; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     115.2       114.6      1.006    0.975     1.009    1.032         0  200
first call ms               460.0       452.9      0.997    0.946     0.993    1.050         2   25
warm n ns/op               195338      182716      1.077    1.056     1.074    1.113         0  200
warm sig ns/op               4141        3939      1.060    1.025     1.057    1.125         0  200
cold alloc B            160035552   161897744      0.988    0.986     0.989    0.990         0  200
warm n alloc B/op          237180      226480      1.062    1.035     1.058    1.108         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9078160     9078160      1.000    1.000     1.000    1.000         0  200
```

Reports are under /mnt/work/projects/quackjs-lanes/reports with the label above;
the verification report adds -verify. Android, iOS and runtime Native behaviour
were not checked for this idea.
