# Integral numeric conformance

This explicit local family generates Java source templates and concrete inputs
once, then compares the original Java program with the same program translated
through the production Java library bundle. The native observers preserve exact
signed integers and UTF-16 strings; no JSON floating-point conversion is involved.
The library targets netstandard2.0 and its observer targets net10.0. The intended
JDK version comes from `validation/java-compat/contract.edn`, currently Java 17.

The default batch contains 950 deterministic records and 32 seeded combinations.
It covers byte, short, char, int and long boundaries, signed byte-array reads,
narrowing, widening, mixed widths, arithmetic and bitwise operations, all three
shifts with masked distances, division/remainder (including zero and minimum
value divided by minus one), unary operators, and constant/runtime overflow.
Compound array assignments evaluate a receiver, an index and a RHS that mutates
the same cell. Observations include their ordered trace and final cell, even
when the operation throws. Unary observations retain both returned and updated
values. Each invocation resets its observable state.

## Local commands

Check RAM and CPU first. Set `DRIPSHARP_WORKERS=22` and
`JAVA_TOOL_OPTIONS=-Xmx28g` for these commands, and run one live batch at a time:

```sh
# Deterministic boundaries plus a bounded generated sample (0..48 samples).
clojure -J-Xmx28g -M -m dripsharp.numeric-microprogram --seed 13 --samples 32

# A minimized regression: byte a=1; array()[index()] += rhs(1), where rhs sets a=7.
clojure -J-Xmx28g -M -m dripsharp.numeric-microprogram --samples 0 --case saved-byte-value

# Exact recorded input, source, observers and settings; never regenerates.
clojure -J-Xmx28g -M -m dripsharp.numeric-microprogram --replay PATH/snapshot.edn

# Direct authored IntegralDivide/IntegralRemainder helpers against JDK operators.
clojure -J-Xmx28g -M -m dripsharp.java-compat-differential --cases validation/java-compat/cases.tsv --family numeric

# Broader bounded direct-helper exploration; failures use the existing reducer.
clojure -J-Xmx28g -M -m dripsharp.differential-campaign --family numeric --seed 13 --count 10 --max-operations 32 --max-processes 100 --max-ms 180000 --shrink-attempts 50 --shrink-ms 10000

# Positive checks, independent boundary anchors, signedness fault and exact replay.
clojure -J-Xmx28g -M:test --namespace dripsharp.numeric-microprogram-live
```

The signedness negative control changes only a disposable generated array read
from signed to unsigned. The comparator must report `-128` versus `128` and
reproduce that difference from its captured snapshot. Other minimized cases are
`short-narrowing`, `char-narrowing`, `long-distance`, `intBinaryDiv-0`,
`longBinaryDiv-0`, `longBinaryRem-0`, and `constantOverflow-0`.

Each translated batch compiles/translates/builds once per side and executes all
records against those builds. The microprogram runner bounds every stage and
the total run to ten minutes. It retains the actual source/input, generator
version and seed, runtime versions, host, first difference and replay command
under `validation-output/microprograms`. Rejection, compilation/translation/build
failure, timeout, unexpected process failure and semantic mismatch remain
separate outcomes. This family does not automatically shrink Java source;
`--case` selects a single recorded input and the named regressions retain the
small triggering expressions in the authored generator.

Registry discovery is process-free. Live tests use the `_live.clj` suffix and
are never selected by ordinary test discovery or hosted workflows. The new
process-free tests only generate/validate records and inspect registry metadata;
they launch no JVM/.NET child, translator, build, or differential. No GitHub
workflow or schedule is added. A passing bounded batch is not a claim of complete
Java or PdfCarton equivalence.
