# Translated language semantics

This explicit local family instantiates bounded Java templates with concrete
inputs, then runs the same sources on Java and through the ordinary DripSharp
Java library bundle. It targets `netstandard2.0` with a `net10.0` observer. The
Java baseline comes from `validation/java-compat/contract.edn` (Java 17).

Thirty fixed cases cover receiver/argument and array operand evaluation order,
single evaluation, saved compound-assignment values, short-circuit and ternary
branches, primitive/wrapper/reference overloads, boxing and null unboxing,
successful/null/failing casts, integer overflow, narrowing, UTF-16 characters,
and string conversion of null. Dispatch templates cover class and interface
calls, inherited defaults, explicit interface/class super calls, generic bridge
dispatch, and hidden fields. Control-flow templates cover return and throw
through nested finally/catch blocks, finalizer exceptions replacing a return,
and reverse resource cleanup with suppressed exceptions. Initialization covers
interleaved static fields/blocks, superclass-before-subclass initialization,
constructor delegation and argument evaluation, and virtual access to a derived
field before its initializer runs.

The default adds twelve seeded samples; `--samples` accepts 0..24. Branch
selectors stay in explicit domains. Value operands can span the signed int
range. Inputs are generated once outside either runtime. Native observers
record exact results, UTF-16 event traces, state, and exception categories,
including the last event before a failure. They do not call JavaCompat to
compute expectations. A mismatch additionally identifies the first differing
trace event; the underlying exact observations remain intact.

## Local commands

Check RAM/CPU and run one live batch at a time. Set `DRIPSHARP_WORKERS=22` and
`JAVA_TOOL_OPTIONS=-Xmx28g` before these commands:

```sh
# Fixed templates and bounded exploration.
clojure -J-Xmx28g -M -m dripsharp.semantics-microprogram --seed 19 --samples 12

# Focused authored source/input regressions.
clojure -J-Xmx28g -M -m dripsharp.semantics-microprogram --samples 0 --case overloads-0
clojure -J-Xmx28g -M -m dripsharp.semantics-microprogram --samples 0 --case initialization-0

# Replay exact recorded sources, inputs, observers and configuration.
clojure -J-Xmx28g -M -m dripsharp.semantics-microprogram --replay PATH/snapshot.edn

# Executable anchors and two negative controls, including exact replay.
clojure -J-Xmx28g -M:test --namespace dripsharp.semantics-microprogram-live

# Process-free admission and orchestration checks.
clojure -M:test --namespace dripsharp.semantics-microprogram-test --namespace dripsharp.microprogram-test
```

`--case` retains one recorded input and its independent source template.
`BoxedOverload.java` preserves the minimized wrapper-overload regression;
`InitializationOrder.java` preserves the small inheritance/initialization
regression. Other cases use the shared `SemanticsCases.java` template. There is
no unrestricted Java generator or automatic Java-source shrinker.

Each batch compiles/translates/builds once per side and starts a fresh process
for each input, so initialization cannot leak between cases. The harness bounds
each process and the total batch to ten minutes. Artifacts under
`validation-output/microprograms` retain exact source/input, seed and generator
version, runtime versions, host, process diagnostics, first difference, and a
replay command. Accepted templates that fail translation/build/execution stay
failed; they are never discarded as successful behavior checks.

Negative controls alter only disposable generated C#: one changes a superclass
call to virtual redispatch, and one reverses two side-effecting operands. Both
must produce a semantic mismatch at the expected event and reproduce it from
the saved snapshot without invoking the generator.

Registry discovery and ordinary `_test.clj` checks are process-free. Live tests
use `_live.clj`, require explicit local selection, and add no hosted workflow
or schedule. Passing this bounded family does not establish full Java or
PdfCarton equivalence.
