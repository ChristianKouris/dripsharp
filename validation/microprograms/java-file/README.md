# JavaFile values and the FileInfo boundary

This batch runs the original Java templates and their DripSharp translations
against identical recorded pathnames. The intended JVM contract is read from
`validation/java-compat/contract.edn` (Java 17); this does not change the PdfCarton
family oracle. The generated library targets `netstandard2.0`, and its native
observer and consumer target `net10.0`.

## Explicit local commands

Check available RAM/CPU first. These bounded commands use 22 workers and a
28 GiB maximum JVM heap; run one live batch at a time.

```sh
# Fixed regressions: 171 operations, one JVM compile and one .NET build.
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.microprogram \
  --batch validation/microprograms/java-file/batch.edn

# Fixed regressions plus eight generated inputs: 323 operations.
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.java-file-microprogram \
  --seed 20260922 --samples 8

# Broader bounded sample: at most 32 generated paths / 779 total operations.
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.java-file-microprogram \
  --seed 20260923 --samples 32

# Behavioral assertions plus intentional internal-export fault and exact replay.
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M:test --namespace dripsharp.java-file-microprogram-live
```

The live namespace has no `_test.clj` suffix and no workflow or default test
registration. The new `_test.clj` checks only input generation/admission without
starting JVM/.NET subprocesses or loading Spoon. No hosted job is added, and no
existing required proof or upstream suite is removed. Use the shared
[microprogram evidence/replay protocol](../README.md#evidence-and-replay) to replay
the exact `snapshot.edn` printed by a run, including its saved native consumer.
The seed is metadata; replay consumes the saved concrete input and sources.

## Internal Java observations

`FileFlows.flow` routes each input through constructors (including both parent
forms), private fields, return values, arrays, mutable generic lists, interfaces,
inherited methods, overrides with `super`, public/protected calls, generic
methods, lambda callbacks, bound references, and unbound references. File
results become pathname strings only after completing that route. Independent
native observers encode their UTF-16 code units; they do not use JavaCompat to
compute expectations. The query entry point also observes existence, directory,
readability and writability after an inherited File return.

The fixed input includes empty, literal whitespace, relative/ordinary paths,
`.` and `..`, embedded NUL, and a null File reference. Constructor operations
intentionally reject null pathnames; a null File reference remains null on
routes that accept it. Each path runs all routes, so early invalid exports cannot
hide behind a representable sample. The generator adds bounded combinations of
these values and random short names, always before either implementation runs.
Each operation constructs its own state and performs no filesystem writes.

## Native .NET observations

`NativeConsumer.cs` compiles against only the generated public API. Its typed
constructor calls, overrides, interface implementation, delegates, arrays and
`IList<FileInfo>` calls check the documented signatures as well as behavior.
It uses the same recorded pathname set in a separate process against the same
library build. Public contract assertions are deliberately not JVM parity
assertions:

- A newly supplied `FileInfo` imports its `FullName`; an exported representable
  Java pathname has the independently computed native `FullName`. Reimporting an
  exported object preserves the original internal Java pathname.
- Empty and NUL pathnames cannot be exported. Other host limitations are checked
  independently using the native `FileInfo` constructor. Exports throw
  `ArgumentException`, including before entry into an external override or
  delegate that would receive an unrepresentable File value.
- Null remains null. External public/protected overrides and an explicit
  interface callback are counted and must actually run. An external override
  taking a string may handle even an empty or NUL pathname without exporting it.
- Imported mutable lists and arrays retain their public object identity and
  share changes in both directions with translated code.

The native phase starts only after JVM/translated parity succeeds. A failing
consumer exits unsuccessfully and fails the batch at `:native-consumer`; it is
never counted as a successful comparison. Its assertion count is a normal log,
not a coverage or product-completion claim.

The live fault probe temporarily replaces a generated internal `super` call
with a call through the public FileInfo wrapper. Both execution processes still
succeed, but the first empty-path override operation reports `illegal-argument`
on .NET instead of the Java pathname result. The comparator must identify that
exact semantic difference on both the initial run and replay. This disposable
fault is saved as a perturbation, never as an authored production C# change.
