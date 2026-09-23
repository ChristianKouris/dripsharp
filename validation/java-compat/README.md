# Direct JavaCompat Differential

This validation project proves the authored compatibility runtime directly,
without exposing it as a standalone package. `JavaCompatOracle.java` executes
the named JDK contracts on a live JVM, while `Program.cs` compiles the durable
runtime sources into the probe assembly with
`DRIPSHARP_INTERNAL_JAVA_COMPAT` and executes the matching operations.

`TypeProvenance.tsv` is fail-closed provenance for every compiled top-level
compatibility type:

* `compat-type` is the exact non-generic C# type name.
* `jdk-contract` is the JDK type or nearest JDK contract replaced by the
  authored type. Internal adapter types cite the public JDK contract whose
  behavior they support.
* `targets` lists target manifests that request `:java-compat` and therefore
  internalize the source into their owning package. It is not a target-scope
  or completion statement.
* `proof-rows` names the focused observation rows that exercise the type. Every
  type must cite its dedicated `type-contract/<compat-type>` JVM/.NET
  availability row; types used by behavioral probes also cite those rows.

The probe rejects missing or extra compiled types, public type visibility,
unknown proof rows, and runtime source drift. The Clojure runner additionally
requires exact JVM/.NET trace equality and proves that the comparator detects a
deliberate perturbation.

## Recorded concrete cases (explicit local execution)

The separate case mode consumes the **same saved input file** in the JVM and
.NET processes. It does not replace the fixed scenarios or count type
availability as executed behavior. Registration in `case-families` is inert.
No new live checks are added to hosted workflows or default test discovery.

After checking available RAM/CPU, run a selected family or complete case from
the repository root:

```sh
export DRIPSHARP_WORKERS=22
export JAVA_TOOL_OPTIONS=-Xmx28g
clojure -J-Xmx28g -M -m dripsharp.java-compat-differential \
  --cases validation/java-compat/cases.tsv --family byte-buffer --case signed
```

Omit `--case` for the family, or both selectors for the whole recorded batch.
Selectors are exact and preserve all steps of each selected case. Empty
selections and unknown operations fail. The default input is `cases.tsv` beside
this README. `--output DIRECTORY` changes the artifact parent. Each invocation
creates a fresh `run-*` directory containing `input.tsv`, `java.tsv`,
`dotnet.tsv`, and `summary.edn`; failed builds still retain input and diagnostics.
The summary includes actual compiler, SDK, and runtime versions, host, first
semantic difference, and an exact replay command. For example:

```sh
clojure -J-Xmx28g -M -m dripsharp.java-compat-differential \
  --cases validation-output/java-compat-cases/run-EXAMPLE/input.tsv
```

Replay uses that recorded input, not a regenerated candidate. The authored
sample is deterministic (no random generator or seed). Each invocation compiles
the fixed Java runner once and builds the fixed .NET runner once for the entire
selected batch, then runs each once; it never compiles per case. It builds from
current sources on replay and records current versions rather than reusing a
potentially stale binary from an earlier invocation.

The Java contract comes from `contract.edn` (currently Java 17): both compiler
and runtime come from the matching JDK, and compilation uses `--release`.
The .NET contract comes from the probe project (`net10.0`); execution allows
patch roll-forward within .NET 10.0, checks the running runtime, and records the
SDK and actual runtime. This direct proof does not change PdfCarton's Java 25
oracle or its production `netstandard2.0` libraries.

Compile/build timeouts and case-run timeouts come from `contract.edn` (currently
120 seconds per build, 30 seconds per execution); version checks have a
10-second timeout each. Batches are limited to 1 MiB and 10,000 operations.
These bounds keep one invocation under six minutes of child-process budgets.
A timeout, failed compilation, nonzero exit/crash, missing output, malformed
output, duplicate/extra/missing observation, or runtime-contract mismatch is a
non-success result. Process errors retain their phase and cannot masquerade as
an expected Java exception. CLI exit codes are 0 for a match, 1 for a reported
execution/comparison failure, and 2 for invalid input or harness errors.

### Wire format and observations

Input is UTF-8 TSV with header `DRIPSHARP_DIFFERENTIAL_CASES_V1`. Each subsequent
line has `family`, `case`, `step`, `operation`, `expected-exception`, followed by
zero or more typed arguments. Identities and categories use ASCII letters,
digits, underscore, hyphen, and period. `(family, case, step)` must be unique;
file order is execution order. Cases have independent state, including when
interleaved. The expected category is checked on **both** implementations.

Values use canonical ASCII tokens instead of JSON numeric or Unicode scalar
conversion:

| Token | Meaning |
| --- | --- |
| `n:` | Null (also a void result or absent state) |
| `b:0080ff` | Exact bytes; `b:` is an empty byte array |
| `i:9007199254740993` | Exact decimal integer; no exponent or leading zero |
| `u:0000d8000061dc00` | UTF-16 code units, including unpaired surrogates; `u:` is an empty string |

The reusable transport admits arbitrary-width integers. Operation contracts
narrow them as needed: `long/hash` requires a signed 64-bit value. Unknown
operations, wrong argument types/counts, and out-of-domain integers are invalid
input rather than behavioral observations.

The initial operations are:

| Family | Operation | Arguments | Result and state |
| --- | --- | --- | --- |
| `byte-buffer` | `wrap` | bytes or null | Void; cursor, or absent if construction failed |
| `byte-buffer` | `get` | none | Signed byte; cursor after the operation |
| `byte-buffer` | `get-int` | none | Signed big-endian 32-bit integer; cursor after the operation |
| `string` | `hash` | UTF-16 string or null | JDK/JavaCompat string hash; absent state |
| `long` | `hash` | signed 64-bit integer | JDK/JavaCompat long hash; absent state |

A buffer case begins without a buffer; reads before a successful wrap therefore
observe a null reference. A failed wrap preserves any previous buffer. Exceptions
are observed at each operation and execution continues. `none`, `null-pointer`,
and `buffer-underflow` are the current semantic categories. The independent
adapters map only the relevant JDK/.NET exception classes to these categories;
unexpected exceptions escape as process failures. No exception text is compared.

Output has header `DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1` and exactly seven
columns: `family`, `case`, `step`, `operation`, `exception`, `result`, `state`.
Results and states use the same typed tokens. Exact ordered identities are
checked before comparing values, exception categories/timing, and state.
The byte-buffer observer reads the public cursor after success or failure; it
does not use a compatibility helper to compute the expected JVM value.

### Focused verification

Process-free comparator and orchestration tests (injected process functions):

```sh
clojure -M:test --namespace dripsharp.differential-cases-test
```

Explicit local live regression, including perturbations of real observations:

```sh
clojure -J-Xmx28g -M:test --namespace dripsharp.java-compat-cases-live
```

`java_compat_cases_live.clj` deliberately does not have the `_test.clj` suffix
used by default discovery. The process-free namespace starts no JVM/.NET child,
restore, build, or oracle, and requires no installed Java 17 or .NET runtime.
The existing `dripsharp.java-compat-differential-test` remains unchanged and
continues to verify the original fixed behavior and type-availability rows.
A bounded match is evidence for these cases, not complete Java equivalence.
