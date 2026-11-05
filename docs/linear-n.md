# n rank plans derived from the AST

QuackJS executes the actual n slice with typed symbolic characters and a concrete input length. It derives character gathers and arithmetic over alphabet ranks. Derivation never supplies concrete challenge characters, samples outputs, or fits coefficients.

Each player has 201 thread-safe lazy slots, for lengths 0 through 200. A slot stores an immutable proof or a bounded rejection reason. Other lengths use the interpreter. Each execution owns its numeric buffer and output string. No challenge or result enters the cache.

## Proof rules

The existing interpreter performs concrete scalar operations, control flow, helper calls, mutations, alias resolution, exception handling and array traversal during analysis. Symbolic numbers have a separate type and cannot select numeric dispatcher specializations. Unknown operations stop analysis.

Alphabet snapshots contain only concrete one-unit strings. Each read observes the current array contents. A later mutation produces another snapshot. The analyzer replaces a search of a preceding alphabet lookup with its rank only when both complete snapshots match and contain unique entries. It keeps the lookup's range guard and normalizes negative zero to the positive zero returned by `indexOf`. Duplicate alphabets retain first-occurrence searches. Out-of-range, fractional and NaN ranks select fallback.

String comparisons may follow the unequal path with a checked inequality condition. Known throws follow the existing catch and finally machinery. Handler string addition must prove that it cannot trigger the interpreter's exception-sentinel check. It uses a length proof or an inequality guard against the challenge. An identical symbolic challenge on the right stops analysis. Unsupported operations remain uncatchable, including unknown methods on symbolic strings.

The analyzer proves `Number(array)` is NaN for a plain primitive array whose native default join contains a comma. A single symbolic character instead requires a per-call guard excluding ASCII digits and JavaScript whitespace. Other one-unit characters cannot satisfy the evaluator's numeric grammar. Coercion overrides disable this rule. It checks call and conversion depth, joined size, and all element types. It charges native `valueOf`, `toString` and `join`, their lookups, output allocation and numeric scanning. A common test compares the complete step and allocation charges with the interpreter.

Successful plans evaluate every lookup guard, including reads whose values do not reach the final output. They publish output only after all path conditions pass. A failed guard or analysis rejection executes the ordinary n slice in a fresh runtime. Symbolic state never escapes into an ordinary invocation.

## Resource bounds

Analysis retains the existing parsing, call-depth, expression-depth, value-size, 2,000,000-step and 2,000,000-allocation-unit limits. It charges full alphabet length for each symbolic search. Preflight adds the plan's instruction, condition and output buffers to the conservative source costs. It publishes a plan only if both totals fit the limits. A conservative preflight rejection selects interpretation and does not reject a valid challenge.

Construction has additional caps of 20,000 instructions, 65,536 alphabet units, 256 conditions and 65,536 condition references. A published proof has at most 8,192 instructions, output references, alphabet units and condition references combined. The 201 slots therefore retain at most 1,646,592 such units, plus fixed object overhead. Rejection messages have at most 160 units.

The first call at a length can perform one bounded analysis, one bounded plan attempt and one bounded interpreter fallback. Later calls can perform the last two. This adds bounded preparation work to the first call. It does not let the source exceed its original execution budget. For a published plan, preflight bounds analysis charges plus plan work by two million units. Adding a fallback gives at most four million logical step or allocation units per call, plus separately capped proof construction. A rejected analysis and fallback have the same upper bound. These are evaluator units, not CPU instructions or byte counts.

Common tests exercise infinite loops, growing arrays, instruction exhaustion, over-budget preflight, oversized literal output, mutations, aliases, overridden methods, content-dependent paths, duplicates, signed zero, empty inputs and non-ASCII UTF-16 units.
