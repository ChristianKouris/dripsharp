# Translated streams and resource cleanup

This explicit local batch compiles the same `IoFlows.java` sources for Java and
DripSharp-generated C#. Its 80 deterministic operation records exercise emitted
calls and runtime behavior together. The Java baseline comes from the direct
JavaCompat contract (Java 17); it does not change the PdfCarton Java 25 oracle.
The generated library targets `netstandard2.0`; the native observer and consumer
run on `net10.0`.

After checking available RAM/CPU, run one bounded batch:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.java-io-microprogram
```

The live assertion suite also checks specific Java exception and mutation traces:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M:test --namespace dripsharp.java-io-microprogram-live
```

## Observations

The reusable [microprogram harness](../README.md) saves the concrete input,
original source, emitted source, runtime versions, generator version, process
outcomes, and exact replay command. Each template returns an ordered trace with
explicit operation, result, buffer, open, close, flush, and exception fields.
The independent native observers encode its UTF-16 units without calling a
compatibility helper to compute the expectation. Expected behavior comes from
executing the original Java source. Exception messages are controlled fixture
identifiers, not platform-generated exception text. No random inputs are used;
the recorded seed is null.

| Template | Behavior |
| --- | --- |
| `reads` | Single-byte, whole-buffer and slice overloads; signed conversions; zero-length and EOF; nested filters; failure before/after the first byte; exact buffer mutations. |
| `byte-array` | Byte-array input's distinct zero-length-at-EOF result and unchanged buffer regions. |
| `overloads` | Virtual whole/slice dispatch, `super.read`, zero-length override side effects, and caller mutation before a bulk override throws, directly and through a filter. |
| `writes` | Single-byte, whole-array, slice and empty writes through nested filters, including deterministic partial write failures. |
| `cleanup` | Return and exception paths through finally; body plus one/two close failures; close failure replacing a pending return; initializer failure; null resource; finally throwing over a body failure. |
| `referenced` | Java 9 existing-variable resources and nested resource scopes. |
| `wrapper-cleanup` | Nested output filters with all combinations of body, flush and close failures. |
| `suppression` | Ordered duplicate suppressed exceptions, defensive array copies, self-suppression and null rejection. |

Cleanup traces retain primary exception category and identity label, cause,
ordered recursive suppressed exceptions, and every close event. Java resource
lowering closes in reverse order, preserves the primary body/initializer
exception and attaches close failures. With no primary exception, close failure
propagates. The outer Java catch/finally executes after cleanup. Java suppressed
exceptions are separate from the .NET `InnerException` cause; translated
`getSuppressed()` reads their ordered snapshots.

`NativeConsumer.cs` calls the public translated `Consume(Stream)` API with a
non-seekable custom .NET stream that returns one byte for each two-byte request.
It independently checks counts, signed bytes, untouched buffer slots, EOF,
exactly-once disposal, native exception object identity, and suppression when
both read and disposal fail. These assertions run as a separately reported
consumer phase against the same generated library. A second assembly load also
checks that independent JavaCompat statics can observe the same suppression.

## Focus and replay

Every input identity names the template, case and step; the comparison reports
the first differing identity. Read/write steps re-execute a bounded prefix in
fresh local state, so a failing operation is independently executable:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.java-io-microprogram \
  --case reads-0-2 --step 2
```

To replay one operation from a saved failure, add
`--replay validation-output/microprograms/run-EXAMPLE/snapshot.edn` to that
command. The saved input and source are preserved; current translation/runtime
sources are rebuilt. Omit the filters to replay the complete snapshot, or use
the exact generic replay command in `summary.edn`. Empty selections fail before
execution. Native boundary assertions still run after a successful comparison.

Each invocation builds the fixed source batch once per runtime and follows the
existing harness timeouts (ten minutes total, 30 seconds per execution). It
makes no OS-failure assumptions and creates no schedules. The live namespace
has no `_test.clj` suffix and is not automatically discovered, registered as a
proof family, or referenced by a hosted workflow. No new CI process invocations,
matrices, or jobs are introduced. This bounded suite is regression evidence,
not a claim of complete Java or PdfCarton equivalence.
