# Translated Java microprograms

This local harness compares an original Java source batch with C# generated from
**the same saved sources** by DripSharp. It complements the direct authored
JavaCompat differential in `validation/java-compat`; it also exercises Spoon
resolution, resolved-symbol mappings, declaration/body emission, and the
production compatibility assets. It does not generate a PdfCarton package or
claim complete Java/PdfCarton equivalence.

## Run locally

Check available RAM/CPU first, as required by `AGENTS.md`. The repository setting
is 22 workers and a 28 GiB JVM heap. Run one bounded batch at a time:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.microprogram
```

Select another authored template batch with `--batch path/to/batch.edn`, or
choose an output parent with `--output DIRECTORY`. Each invocation creates its
own `run-*` directory under `validation-output/microprograms` by default. It
never reuses binaries from another source/configuration/runtime. Within the
batch, javac compiles all subjects and the native Java observer once, DripSharp
translates the subjects once, and dotnet builds the native observer and its
referenced generated library once. All concrete records then run against those
builds, in input order, once per side.

The JVM baseline comes from `validation/java-compat/contract.edn` (currently
Java 17); the separate PdfCarton family oracle's Java 25 setting is untouched.
JDK selection reuses the direct differential's resolver and honors
`DRIPSHARP_JAVA_HOME` when it matches that baseline. Compilation alone with
`--release` is not considered proof of the runtime version: both actual
execution versions are checked and recorded. The subject library uses the
ordinary `dripsharp.java-library/rule-bundle`, `netstandard2.0`, C# public
naming, warnings as errors, and authored compatibility assets. Only the
independent native observer executable targets `net10.0`.

## Templates, input, and observation

`batch.edn` names the Java sources, destination settings, native observer sources,
input file, and method bindings. Its generator ID/version/seed describe how the
concrete input was authored or generated. The included deterministic regressions
have no random seed. Generators must write concrete records before calling the
harness; neither implementation generates its own inputs.

Each operation binds one public static Java method to its generated public C#
method and gives its parameter types. The reusable reflection launchers accept
`int`, `long`, and `string` parameters and observe integer, long, string, or null
results. Adding templates with these signatures needs Java source and bindings,
not a handwritten C# subject. Broader parameter/result protocols require an
explicit observer extension. This is a harness capability, not a product
exclusion.

Inputs and observations use the shared versioned TSV format described in
[`../java-compat/README.md`](../java-compat/README.md). Signed integers remain
exact, including 64-bit values outside the IEEE-754 exact integer range.
Strings are UTF-16 code-unit hex, preserving null (`n:`), empty (`u:`), embedded
NUL, and unpaired surrogates. The native launchers parse and format values using
JDK/.NET primitives; they contain no subject algorithms, translated assertions,
or calls to JavaCompat formatting. The comparator requires one ordered
observation per input identity and reports the first differing operation.

The shipped methods create their own collection state for each call. A batch
runs in one fresh process per side; templates must not depend on previous cases
or retain state from earlier cases. An operation can bind optional `:java-state`
and `:dotnet-state` public static zero-argument observers on its subject type.
After either success or a categorized exception, the native launcher records
that getter's exact result as `state`; without a getter it records `n:`. Both
bindings must be present together. The numeric family uses this to expose
operand evaluation order and the final array cell. Within a method, mutations
and method calls are translated normally: the collection example adds and
replaces entries and calls a helper before returning a selected value.

The observer categorizes the relevant arithmetic, index-bounds, null-reference,
and illegal-argument exceptions. It unwraps invocation exceptions, records the
category at the failing operation, and continues with the next input. Unexpected exceptions,
missing methods, unsupported result types, crashes, and observer failures are
process failures. Exception messages are diagnostic text, not comparison values.

A batch may also name an optional `:dotnet-consumer` C# source containing
`NativeConsumer.Run(string inputFile)`. It compiles in the native observer
project against the same generated library. After successful JVM/translated
comparison, a separate process invokes it with the same saved input file.
Consumer assertions verify a .NET adaptation contract independently; a failed
consumer fails the batch at `:native-consumer`. Its source is part of the replay
snapshot. The [JavaFile batch](java-file/README.md) uses this for public
`FileInfo` signatures, rejection of unrepresentable exports, external dispatch,
and mutable collection aliasing.
The [numeric family](numeric/README.md) generates bounded integral templates,
checks exact results and side effects, and provides replayable signedness controls.
The [I/O batch](java-io/README.md) covers translated stream overloads, partial
failures, resource exception precedence, and native .NET `Stream` consumers.

## Evidence and replay

Every admitted run saves `snapshot.edn` with the literal source, input,
configuration, observers, operation bindings, generator metadata, and intended
Java release. Human-readable source/input copies, emitted C#, mapping and
translation diagnostics, native observations, `processes.edn`, and
`summary.edn` stay alongside it. The summary records actual JVM/.NET and
translator versions, host, elapsed time, and an exact replay command:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.microprogram \
  --replay validation-output/microprograms/run-EXAMPLE/snapshot.edn
```

Replay reads the recorded sources, settings, launchers, and inputs, even if the
authored batch has since changed. It rebuilds using the **current** DripSharp
translator and authored runtime and records the versions used. It is a failure
replay, not a historical toolchain archive or persistent build cache.

Admission rejects malformed bindings, unsupported argument encodings or ranges,
non-production destination settings, and oversized batches as `:rejected`, with
zero matches. Accepted source that fails javac, Spoon resolution, translation,
or the generated C# build is `:failed`, with phase and retained diagnostics.
A timeout or unsuccessful process cannot be counted as behavioral parity.
Successful execution with differing observations reports `:semantic-difference`.
The CLI exits nonzero for either rejection or failure.

Limits are 32 sources, 1 MiB per authored input file, a 4 MiB captured snapshot,
1,000 input records, 120 seconds for javac, 180 seconds each for translation and
dotnet build, 30 seconds per execution, and ten minutes total process time.
The translation worker is itself a timed child process. Resource sizing still
requires the preflight check above; the heap limit is not a reservation of RAM.

## Verification and CI cost

Process-free admission, failure-path, comparison, and build-reuse tests:

```sh
clojure -M:test --namespace dripsharp.microprogram-test \
  --namespace dripsharp.differential-cases-test
```

Explicit local live verification:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M:test --namespace dripsharp.microprogram-live
```

The live namespace deliberately lacks the `_test.clj` discovery suffix. No
workflow, default test selection, or existing proof-family registration invokes
it. Merely discovering the process-free test namespace starts no child JVM,
Spoon model, dotnet restore/build, or oracle. Its injected processes verify that
one build serves every record and that failures retain evidence.

The live tests prove numeric and collection parity; replace `left + right` with
`left - right` in a disposable **generated** C# file; require a semantic mismatch;
and replay that exact perturbation. The perturbation is recorded in the snapshot
and the original generated source is retained. Additional live failure probes
exercise invalid accepted Java source, an existing unsupported captured local
class, and an invalid generated-code perturbation. These expected failures must
be reported at their real stages. No generated C# modification is a durable
implementation or shipped source change.
