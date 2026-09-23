# Local font-cache differential

This focused sibling of `pdfbox-font-text-differential` compares the pinned
PDFBox source with a NuGet-only PdfCarton consumer. It is an explicit local
workflow; no live differential, package build, or smoke invocation is added to
GitHub CI or Clojure test discovery. The process-free comparator tests can run
with the ordinary Clojure tests.

After checking available RAM/CPU, run the family against existing local packages
and the Maven manifest produced by the normal PdfCarton build:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.font-cache-differential))
   (dripsharp.pdfcube.font-cache-differential/verify!
     {:package-feed "target/pdfcube-family-host-feed"
      :maven-manifest "target/maven-reactor-inputs-0.tsv"})'
```

The manifest resolves the configured PDFBox oracle JDK and original source,
resource, and dependency paths. The feed is an explicit choice of previously
built packages, whose version and SHA-256 are recorded; this mode does not claim
clean generation or verify that the package reflects uncommitted product edits.
Use `verify!` without options for the existing clean generation/package-consumer
workflow followed by this family. Allow sufficient resources for that larger
operation. Production libraries remain `netstandard2.0`, the consumer `net10.0`.
The optional `:dotnet` setting selects an installed .NET executable, including a
local x64 host; its actual architecture and runtime version are recorded.

Add `:case-index N` to replay one case from `inputs.edn`, including both orders.
The generator uses seed 80817 and version 1. The 24 bounded cases include the
existing smoke scenarios, every setting kind for both properties, and seeded
combinations beyond those fixed scenarios. Settings include absent properties,
empty strings, a literal whitespace directory, missing paths, an ordinary file,
an embedded NUL, and valid directories. Inputs are generated once outside both
implementations and encoded as UTF-8/Base64, preserving null versus empty and
NUL. Each child receives the same actual property values and font fixture.

Every case runs in fresh Java 17, configured PDFBox-oracle (currently Java 25),
and .NET processes, then repeats in reverse case order. Children run sequentially
with a 30-second timeout. Compilation and restore/build are bounded separately;
the whole comparison has a ten-minute budget after package preparation. The
fixed Java/.NET runners build once per invocation. The existing eight-case
`FontCacheRegression.RunAll` executes unchanged once in its own .NET process,
retaining its isolated assembly loads and full host discovery coverage.

For deterministic differential inputs, both probes bypass only the provider
constructor's host-font discovery through reflection. They initialize a fresh
real `FileSystemFontProvider`, scan the same upstream LiberationSans fixture,
and exercise the original cache selection, writing, and reload methods. They
install that provider into the real mapper before creating a standard-font PDF
through public APIs. Assertions compare the selected cache path, cache row/font
and reload count, actual substitute font, PDF creation, reopened page count,
extracted text, and the complete relative file side effects. This seam does not
claim to test operating-system font enumeration; the existing smoke regression
continues to exercise that path. No generated C# or upstream Java is patched.

JavaCompat retains its documented Java 17 pathname contract. Java 25 changed
`new File("").isDirectory()` to accept the current directory, while
`new File("", ".pdfbox.cache")` still resolves to the filesystem root. For cases
where an empty setting wins on Java 25, the observer asserts that exact selected
path and guards the cache write outside the scenario root. It still compares
public PDF behavior. Java 17 and the package must agree on all cache observations;
all other cases must agree across all three runtimes. This is an explicit
runtime difference, not a relaxed comparison or a product exclusion.

Evidence is written to `validation-output/pdfcube-font-cache`: actual encoded
inputs, fixture identity, runtime versions, per-process traces/logs, a summary,
and a first-difference or process-failure record on failure. Invalid inputs,
behavioral mismatches, build failures, process failures, and timeouts do not count
as passing cases. The temporary scenario directory is deleted in `finally`;
properties and launcher environment overrides are child-local and never change
the parent environment. No user font caches are deleted or modified.

Focused process-free checks:

```sh
clojure -M:test --namespace dripsharp.pdfcube-font-cache-differential-test
```
