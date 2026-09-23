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

Inspect the process-free family and command registry before choosing a local
workload. An optional family name prints its exact operations and whether the
bounded generator supports it:

```sh
clojure -M:run differential-families
clojure -M:run differential-families random-access
```

The registry also records the translated-microprogram, PdfCarton package, and
generated .NET test entry points. Listing it starts no JVM, .NET process,
restore, build, package, generation, or differential execution.

After checking available RAM/CPU, run a selected family or complete case from
the repository root:

```sh
export DRIPSHARP_WORKERS=22
export JAVA_TOOL_OPTIONS=-Xmx28g
clojure -J-Xmx28g -M -m dripsharp.java-compat-differential \
  --cases validation/java-compat/cases.tsv --family byte-buffer --case signed
```

Use `--family file` for the complete recorded file/path regression family.

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
| `byte-buffer` | construction, cursor/limit, mark/reset, clear/flip/rewind, view, order, relative/absolute get/put operations | exact bytes, integers, view state, and exceptions | Backing bytes plus each view's position, limit, capacity, remaining count, order, and read-only flag |
| `binary-data` | `open`, primitive reads/writes, `read-fully`, `write-buffer`, `flush` | exact bytes and signed/unsigned integers | Input cursor, exact output bytes, and the complete sentinel buffer |
| `string` | `hash` | UTF-16 string or null | JDK/JavaCompat string hash; absent state |
| `long` | `hash` | signed 64-bit integer | JDK/JavaCompat long hash; absent state |
| `file` | `inspect` | pathname or null | Lexical path/name/parent, predicates, regular-file length, and hash; absent state |
| `file` | `absolute` | pathname or null | Absolute pathname; absent state |
| `file` | `parent-child`, `file-parent-child` | parent and child pathnames or null | Constructed pathname; absent state |
| `file` | `uri` | URI text or null | Constructed pathname or URI-validation exception; absent state |
| `file` | `equals` | two pathnames or null | Java equality result; absent state |
| `file` | `to-path` | pathname or null | NIO pathname or path-validation exception; absent state |
| `file` | `create`, `delete` | pathname or null | Mutation result or operation exception; absent state |
| `file` | `open` | pathname or null | First byte or file-opening exception; absent state |
| `file` | `list-count` | pathname or null | Child count, without comparing order, or null; absent state |

Stateful I/O families extend that same wire contract:

| Family | Operations | Observed state |
| --- | --- | --- |
| `byte-array-input` | `open`, `read-one`, `read-buffer`, `skip`, `available`, `mark`, `reset`, `mark-supported`, `close` | Relative cursor, available bytes, close invocation, and the complete sentinel buffer |
| `filter-input` | the corresponding filter operations | Controlled-source cursor/remaining bytes, closed state, and the complete sentinel buffer |
| `byte-array-output` | `open`, `write-one`, `write-buffer`, `size`, `reset`, `close` | Exact accumulated bytes, size, and close invocation |
| `filter-output` | `open`, `write-one`, `write-buffer`, `flush`, `close` | Exact partial output, closed state, and flush count |
| `random-access` | `open`, reads, `read-fully`, writes, `seek`, `skip`, `length`, `position`, `set-length`, `close` | File pointer, exact file bytes/length, and the complete sentinel buffer |

The filter adapters receive the recorded bytes, maximum read chunk, and failure
position. They deliberately expose short reads and failures after partial
progress. Byte-array and random-access cases separately preserve zero-length,
EOF, offset/length, seek, mark/reset, and concrete post-close contracts. Byte
results are unsigned `int` values while sentinel buffers retain signed storage
as exact byte tokens. `eof`, `index-out-of-bounds`, and `io` remain distinct.

A buffer case begins without a buffer; reads before a successful wrap therefore
observe a null reference. A failed wrap preserves any previous buffer. Slice,
duplicate, and read-only views share backing bytes while the trace retains each
view's independent cursor and bounds. Failed operations are followed by the same
state observation, so cursor movement on underflow, overflow, invalid mark, and
read-only writes is compared directly. Exceptions are observed at each operation
and execution continues. Semantic categories include `none`, `null-pointer`,
`buffer-underflow`, `buffer-overflow`, `read-only`, `invalid-mark`, `invalid-path`,
`illegal-argument`, `file-not-found`, and `io`. The independent adapters map
only the relevant JDK/.NET exception classes to these categories; unexpected
exceptions escape as process failures. No exception text is compared.

Output has header `DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1` and exactly seven
columns: `family`, `case`, `step`, `operation`, `exception`, `result`, `state`.
Results and states use the same typed tokens. Exact ordered identities are
checked before comparing values, exception categories/timing, and state.
The byte-buffer observer reads the public cursor after success or failure; it
does not use a compatibility helper to compute the expected JVM value.

The file family expands the literal `$ROOT` marker to the same sandbox pathname
beside the recorded `input.tsv` in both processes. Each process independently
creates the same directories, regular files, Unicode names, hidden name, and
missing paths, then removes the complete sandbox in a `finally` block. File
observations retain empty and NUL-containing pathnames through construction and
separately observe NIO, open, and create rejection. Directory length is omitted
because Java leaves non-regular-file length unspecified; regular-file length is
compared exactly. Directory listing order is never observed.

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

## Generated sequences and shrinking (explicit local workloads)

`dripsharp.differential-generation` generates concrete, bounded EDN records
outside both implementations. It uses a small versioned `java.util.Random`
generator and domain-aware reducer rather than a test.check shrink tree: the
reducer needs stable observation IDs, resource dependencies, and preserved
exception intent. No additional dependency or automatic campaign is installed.
The generator supports boundary-biased byte arrays, signed 64-bit integers,
UTF-16 strings (including isolated surrogates), pathname strings, and interleaved
variable-length operation sequences. Its state machines include byte-array
input, controlled filter input, and random access with construction/use handles,
close state, cursor movement, injected failures, partial EOF, and file growth.
The explicit `file` family generates
lexical constructor/query observations, including empty, whitespace, separator,
parent-segment, and embedded-NUL pathnames. Recorded file operations exercise
the controlled filesystem sandbox separately. Public `FileInfo` adaptation
remains defined in `doc/java-file-compatibility.md`.

The `charset` generator records the exact charset name, byte spelling,
replacement/reporting policy, UTF-16 units, and bounded chunk plan. It covers
UTF-8, UTF-16 variants, US-ASCII, ISO-8859-1, and Windows-1252. The combined
direct/translated runner and focused boundary replays are documented in
`validation/microprograms/charset/README.md`.

Each record includes the generator ID/version, seed, generation runtime,
limits, and the **actual operations and exact value tokens**. An explicit
`:handle` maps to the existing runner's `(family, case)` state key; `:requires`
names the successful constructor step used by a read. Step IDs never change
during reduction. A small Clojure domain model checks construction, cursor
bounds, and exception intent; it does not calculate byte/int/hash results.
Reads before construction, null construction, and underflow are intentional
invalid operations. Surviving operations must retain their original expected
exception and valid/invalid intent. Failed construction preserves earlier state.

After checking RAM/CPU, a focused local campaign is:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --seed 1 --count 10 \
  --max-operations 32 --max-value-units 32 --max-input-bytes 65536 \
  --max-processes 200 --max-ms 300000 \
  --shrink-attempts 100 --shrink-ms 30000
```

A broader, still bounded local exploration can use `--count 100
--max-processes 500 --max-ms 600000`. Live generation campaigns and live shrinking are **not**
part of hosted workflow registration, the process-free unit tier, or default
test discovery. All live examples here are opt-in local commands. No workflow,
schedule, runner matrix, or package/generation gate is added.

A deterministic file-only sample is selected explicitly and remains local:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --family file --seed 1 --count 10 \
  --max-operations 32 --max-value-units 32 --max-input-bytes 65536 \
  --max-processes 200 --max-ms 300000 \
  --shrink-attempts 100 --shrink-ms 30000
```

Stateful I/O and binary-buffer exploration use the same replay and shrink path,
for example:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --family filter-input --seed 4 --count 10
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --family random-access --seed 0 --count 10
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --family byte-buffer --seed 23 --count 10
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign --family binary-data --seed 23 --count 10
```

Each campaign builds its fixed Java and .NET runners once, with the current
Java 17/net10.0 contracts, then reuses that pair only within the campaign. It
stops at the first failed candidate. Defaults are 10 generated cases, 32
operations per case, 32 byte/code units per value, 64 KiB per record, 200 total
child processes, and five minutes including setup. Shrinking is bounded by 100
candidate attempts and 30 seconds, also within the campaign's total budget.
`--count`, `--max-processes`, `--max-ms`, `--shrink-attempts`, and `--shrink-ms`
control these budgets. Invalid candidates consume shrink attempts. Every child
process timeout is capped by the remaining campaign and shrink time; exhausting
the process budget prevents further launches. Pure evaluator callbacks must
terminate, and custom live adapters must honor the supplied remaining time.

The reducer removes chunks/operations (including users of removed constructors),
shrinks token values, and simplifies `get-int` to `get` where valid. It accepts
only the same semantic failure signature: stable family/case/step/operation,
expected and observed exception categories, and differing observation fields.
Build failures, crashes, timeouts, changed failure operations, unrelated
exceptions, and malformed observations cannot replace a semantic failure.
This is a bounded greedy reduction, not a claim of a globally smallest input.

Artifacts live under `validation-output/generated-cases/campaign-*` (override
with `--output DIR`). Every executed candidate has `case.edn`, the shared
`input.tsv`, both traces, and `result.edn`. `summary.edn` records versions, host,
limits, process count, and case outcomes; failed preparation still retains the
initial input. Semantic failures also retain the original comparison and
`minimal.edn`, the accepted reduced input and its last verified comparison.
Campaign and shrink counts are separate: `generated` counts proposed inputs,
`rejected` counts invalid/non-smaller inputs, `executed` counts completed
behavioral comparisons, `failed` counts semantic mismatches, `passed` counts
matches, `timed-out` counts timeouts, and `harness-failed` counts other execution
or observation failures. Replays have zero newly generated cases. Discarded
candidates and failed processes are never counted as behavioral executions.

Replay the actual saved record, even if its generator or PRNG has changed:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.differential-campaign \
  --replay validation-output/generated-cases/campaign-EXAMPLE/minimal.edn \
  --max-processes 20 --max-ms 180000 \
  --shrink-attempts 0 --shrink-ms 1000
```

The operation, value-unit, and serialized-input flags override the recorded
generation limits; the saved record remains authoritative during replay.
Preparation failures now retain that record and emit the same exact replay
form as semantic failures. Process output and phase diagnostics remain in the
result and summary rather than being treated as a skipped or passing case.

The printed replay command selects the saved input; no seed regeneration is
involved. Replay rebuilds current sources and records current runtime versions.
For reusable process-free callers, `generate`, `validate!`, `candidates`,
`shrink!`, `write-record!`, `read-record`, and `replay!` are in
`dripsharp.differential-generation`. `:generation-limits` on the campaign API
sets generation bounds. Unsupported operation families require their own
explicit domain contract, rather than guessing preconditions.

Process-free regression checks (no child processes):

```sh
clojure -M:test --namespace dripsharp.differential-generation-test \
  --namespace dripsharp.differential-cases-test
```

The explicit local live regression uses a deliberately injected **test fault**:
real Java/.NET results must first match, then successful negative byte results
are observed as unsigned. It reduces a generated 17-operation case to
`wrap(b:0080), get, get`, retaining the original failing step, and replays the
saved record with generation disabled. Unmodified traces are retained as
`dotnet-unperturbed.tsv`. This tests the reducer, not a known product defect:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M:test \
  --namespace dripsharp.differential-generation-live
```

The live file intentionally lacks the `_test.clj` suffix. The process-free
checks are deterministic and small; registration alone never launches a live
campaign. Bounded samples do not establish complete Java or PdfCarton equivalence.

## Translated binary template

The explicit `binary` microprogram complements the direct helper differential.
It translates authored Java that parses shared bytes through ordered ByteBuffer
views and round-trips primitive widths through DataOutputStream/DataInputStream.
The trace retains encoded bytes and independent root/slice/duplicate positions.
Each snapshot records the concrete source, exact inputs, generator version and
seed, and the ordinary microprogram snapshot provides exact replay.

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  -m dripsharp.binary-microprogram --seed 23 --samples 12
```

Focused deterministic replay without generated samples is available with
`--samples 0 --case parse-little`. This remains an explicit local workload and
is not registered in hosted or default test discovery.

## Collection sequences

The local [collection family](../microprograms/collections/README.md) records
bounded list/map/set/deque state machines, including collision keys, live views,
nulls, equality/hash, defined ordering, and overload-sensitive removal. Its
combined command feeds the identical saved input to this direct runner and the
actual translated Java batch. It is opt-in and adds no hosted live workload.
