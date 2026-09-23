# Local generated document lifecycle workflows

The existing lifecycle baseline and the generated workflow mode compare the
configured PDFBox 3.0.8 oracle (currently JDK 25, Java 8 source target) with a
NuGet-only `net10.0` consumer of the `netstandard2.0` PdfCarton libraries.
These are explicit local commands; no generated campaign, package build, or
new hosted CI job is enrolled by test discovery.

After checking RAM and CPU capacity, run the baseline, the fixed regression,
and four generated workflows:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.document-workflow-differential))
   (dripsharp.pdfcube.document-workflow-differential/verify!
     {:package-feed "target/pdfcube-family-host-feed"
      :maven-manifest "target/maven-reactor-inputs-0.tsv"
      :seed 20 :case-count 4})'
```

The feed explicitly selects a previously built local package. Its version and
SHA-256 are recorded and checked against the restored package; this does not
claim clean generation or that the package includes uncommitted product edits.
Dependencies can come from that feed or the existing local NuGet package cache.
Restore writes into the run directory, and the consumer has no project or
assembly references. Omitting `:package-feed` uses the existing clean package
workflow. The Maven discovery manifest selects upstream sources, dependencies,
resources and Java toolchain. `:dotnet` selects a host, including an installed
macOS x64 .NET executable. Runtime versions and architecture are recorded.

Every invocation creates a separate directory under
`validation-output/document-workflows`. It retains the concrete EDN workflow,
identical base64/UTF-8 wire input consumed by both implementations, seed and
generator version, runtime/upstream/package versions, four observation traces,
every saved PDF, process logs, and a summary or first failure. Replays use the
saved input, never regenerate it from a seed:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  path/to/run/case-0/replay.clj
```

The saved replay includes the feed, manifest when explicitly provided, host,
and input path. Set `:replay-file "path/to/input.edn"` directly to replay any
record, including `shared-incremental.edn`. `:baseline? false` omits an already
verified baseline during focused replay. `:case-count 0` runs just the fixed
regression; `:seed 100 :case-count 20` runs a larger bounded local sample.

## Workflow contract

Version 1 records one to eight resources and pages and at most 64 operations.
The generator makes two to five pages, flat or nested page trees, shared and
indirect resources, a standard Helvetica font, basic graphics and text content,
Unicode metadata and resource strings, filtered/unfiltered content streams, and
full saves with either default compression or no compression. Operations include
full and no-op saves, explicit close/load, shared-object, metadata and rotation
edits, incremental save, and a possible second revision. Invalid incremental
save on a newly created document is separately labeled; valid incremental saves
require a loaded source. Both probes explicitly mark modified COS dictionaries
for incremental writing.

The observer records page order, tree type/count/parent links, resource alias
relationships and indirectness, font resolution, decoded content bytes and
ordered individual content streams, filters, extracted text as UTF-16 units,
selected metadata, closed state and exception category/failing operation.
An incremental save must preserve every byte of the source prefix. Every saved
revision is reopened locally and on the other runtime. The comparator checks
both writers, both cross-opens, and each writer against the opposite reader.
A small independent model also checks saved updates so matching lost updates
cannot pass. Null and empty strings remain distinct.

Only serialization layout outside these semantic observations is ignored:
object numbers, cross-reference offsets, compressed byte representation,
whitespace between serialized PDF tokens, and automatically generated trailer
IDs. Decoded content whitespace, text encoding, page/stream order, identity
relationships, broken references, and metadata are not normalized. The baseline
retains its existing normalization; generated observations do not use it.

The campaign builds each fixed runner once and reuses it only in that invocation.
The default total budget is ten minutes, with 60 seconds per workflow process,
a process-count budget, at most twelve shrink evaluations, and a one-minute
shrink budget. `:max-ms` may be raised to at most one hour; `:case-count` is
limited to 100 and `:shrink-attempts` to 100. Timeouts, process/build failures,
invalid inputs and behavioral mismatches are distinct failures, never passes.

On a mismatch, reduction removes operations, pages, or resource objects and
their dependent references. Each candidate must retain valid close/load/save
prerequisites and the same differing observation, comparison and values. Failed
processes do not preserve a semantic mismatch. `reduced.edn`, shrink counts and
a replay command are retained alongside the original evidence.

The authored `shared-incremental.edn` is a fixed local regression independent of
future generator changes. It survives regeneration of the product repository.
No generated C# is edited. This bounded sample complements the complete adapted
upstream suite and existing package consumers; it does not establish complete
PDFBox equivalence.

Process-free generator, comparator and reducer checks:

```sh
clojure -M:test --namespace dripsharp.pdfcube-document-workflows-test
clojure -M:test --namespace dripsharp.pdfcube-pdfbox-document-lifecycle-differential-test
```
