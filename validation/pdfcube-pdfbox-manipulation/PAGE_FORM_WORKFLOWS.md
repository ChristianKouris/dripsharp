# Local generated page manipulation and form workflows

This explicit local campaign combines page splitting, merging, importing and
reordering with text and checkbox form values. It compares the configured
pinned PDFBox oracle (currently PDFBox 3.0.8 on the profile's JDK 25 toolchain)
with a `net10.0` consumer that references only the packaged `netstandard2.0`
PdfCarton libraries. Test discovery never starts a JVM, .NET process, package
build, or generated campaign, and no new hosted CI job runs this work.

After checking RAM and CPU capacity, run the existing affected baselines and
the three fixed plus three generated workflows:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.page-form-workflow-differential))
   (dripsharp.pdfcube.page-form-workflow-differential/verify!
     {:seed 20 :case-count 3})'
```

The default clean-package path reuses its package proof while running the
existing manipulation and interaction differentials before the generated
workflows. To focus on an already built local package, disable that integrated
baseline and select its feed and Maven discovery manifest explicitly:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.page-form-workflow-differential))
   (dripsharp.pdfcube.page-form-workflow-differential/verify!
     {:package-feed "target/pdfcube-family-host-feed"
      :maven-manifest "target/maven-reactor-inputs-0.tsv"
      :baseline? false :seed 20 :case-count 3})'
```

In focused feed mode, run the existing baselines separately when they have not
already been established for that exact package:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.pdfbox-manipulation-differential)
            (quote dripsharp.pdfcube.pdfbox-interaction-differential))
   (dripsharp.pdfcube.pdfbox-manipulation-differential/verify!)
   (dripsharp.pdfcube.pdfbox-interaction-differential/verify!)'
```

Each invocation gets a new directory under
`validation-output/page-form-workflows`. It records generator version, seed,
upstream revision, package identity and SHA-256, runtime versions, the exact EDN
and base64/UTF-8 wire input, process logs, PDFs, four semantic traces, and a
summary or first failure. A replay uses the recorded EDN rather than generating
another case:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  path/to/run/case-0/replay.clj
```

## Workflow and observation contract

Version 1 bounds each case to one to four resources, two to six pages, exactly
one text field and one checkbox, and one to six distinct selected pages. Pages
have generated distinguishable ASCII content, media/crop boxes and rotations.
Resources carry generated values and may be shared by several pages. Fields
have generated initial and replacement values and widgets attached to recorded
pages. Validation rejects malformed references and oversized inputs, but does
not discard a valid case because a runtime may not support its combination;
the failing operation and normalized exception category become observations.

The three templates are:

- `split-and-merge`: split into one-page documents and merge selected pieces in
  recorded order;
- `import-and-reorder`: import a recorded subset into a new document in the
  recorded order;
- `set-form-value-save-reopen`: change both field values, save, and reopen.

Both runtimes write their own result and reopen it. Then Java opens the .NET
result and .NET opens the Java result. The comparator checks page order and
content bytes, media/crop boxes, rotation, resource values, reference aliasing
and indirectness, sorted field names/types/values, widget page association,
normal-appearance shape and keys, appearance state, and exceptions. A small
independent model also checks expected success and page order so two matching
but incorrect writers cannot pass. Serialization offsets, object numbers and
trailer IDs are deliberately not compared.

The authored fixed records cover every template independently of generator
changes. The default generated seeds 20 through 22 cover all three templates.
The campaign builds each fixed runner once, uses 60-second per-workflow process
timeouts, a 15-minute default total process budget, at most 12 reductions, and
a one-minute reduction budget. Case count is capped at 100 and total time at one
hour. A semantic mismatch retains the original input, first difference, reduced
input when found, and exact replay. Process/build failures never count as
preserving a semantic mismatch.

Process-free generator, comparator and reducer verification:

```sh
clojure -M:test --namespace dripsharp.pdfcube-page-form-workflows-test
clojure -M:test --namespace dripsharp.pdfcube-pdfbox-manipulation-differential-test
clojure -M:test --namespace dripsharp.pdfcube-pdfbox-interaction-differential-test
```

This bounded family complements the adapted upstream suite and existing package
probes. Passing it is not a claim of complete PDFBox or PdfCarton equivalence.
