# Tokenless indexing

Base commit: `bfa3a87`. Measurements use the lane wrappers, the pinned Faraday
200-player corpus and JDK 21. The wrappers serialize builds and benchmarks on
the shared lock. Fixtures and goldens remain unchanged.

## Tokenless scan

`Lexer.scan` exposes a token's kind, integer tag and UTF-16 source span. It runs
the same comment, quoted-string, regex and template checks as `Lexer.next`.
`next` materializes the current token for parsing. The first index scan uses a
96-entry array window and materializes entries only when a retained target or
metadata check needs text. The window shares those retained tokens until their
slots wrap. Effect reference scans and hint-free landmark validation use the
same cursor. Selected definitions still use the existing bounded parser.

Fixed tags use exact source comparisons. Non-ASCII identifiers cannot overlap
ASCII punctuation or the reserved tags. String literals stay opaque during
scanning, but escape validation still runs even for unused definitions.

The first scan still rejects more than 2,000,000 tokens, 512 brace/paren levels,
100,000 definitions, calls or mutations, and source above 8 Mi UTF-16 units.
Effect analysis still charges every scan and lookahead against its shared
2,000,000-token and 16 Mi-unit budget. The 150,000-token definition/call limits,
128-level selected-source bound and 64-level template bound remain unchanged.
Materializing a retained literal rereads its bounded source span for validation
and decoding. The window caches that result. Its decoded length cannot exceed
its source span.
No token-count or source-unit charge changes, and runtime guards, steps and
allocation charges remain unchanged.

Six new common tests cover offsets, decoded escapes, opaque nested templates,
all regex contexts, Unicode tag collisions, empty input, malformed unused
literals, scan/depth limits, window wrapping, aliases, escaped member writes,
native predicate invalidation, live table mutation and challenge lengths through
16,384 UTF-16 units. Existing concurrency tests exercise the resulting programs.

Verification on this implementation passed all 200 players in both modes,
18,600 sequential comparisons per mode, plus concurrency on eight distinct
primary configs per mode. `jvmTest` passed 161 tests, with zero failures, errors
or skips. Reports are `tokenless-index-tokenless-scan-verify.json` and
`tokenless-index-tokenless-scan.json` in the shared reports directory.

The two unchanged baselines have cold-time ratio quantiles 0.975 / 0.998 / 1.023
and first-call quantiles 0.952 / 0.999 / 1.052. Retained bytes match exactly.
Those differences define the noise floor for these measurements.

Full run, 200 players and five rounds. Ratios above one favor this lane.

```text
baseline-a -> tokenless-index-tokenless-scan; failed 0 -> 0; missing from candidate: 0
metric                   base med    cand med  geomean x     p5 x  median x    p95 x  worse>5%    n
cold ms                     115.2       74.73      1.542    1.512     1.542    1.570         0  200
first call ms               460.0       302.8      1.514    1.297     1.515    1.820         0   25
warm n ns/op               195338      200593      0.968    0.918     0.973    0.997        34  200
warm sig ns/op               4141        4192      0.990    0.968     0.991    1.010         0  200
cold alloc B            160035552    61306576      2.602    2.521     2.613    2.657         0  200
warm n alloc B/op          237180      230314      1.025    1.012     1.027    1.040         0  200
warm sig alloc B/op          5339        5339      1.000    1.000     1.000    1.000         0  200
retained B                9078160     9243976      0.982    0.982     0.982    0.982         0  200
```

Keep this change for cold compilation. Its cold and first-call gains exceed the
baseline spread. Warm n time regressed about 3.3% by geometric mean, beyond the
baseline warm spread, and retained heap grew about 1.8%. Warm n allocation fell
about 2.5%; signature allocation did not change. No warm speedup is claimed.
