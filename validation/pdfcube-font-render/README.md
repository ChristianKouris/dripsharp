# Local generated font, text and CPU rendering comparisons

This explicit local campaign complements the existing font/text and rendering
baselines and the complete adapted upstream suite. It uses the configured
PDFBox source oracle (currently PDFBox 3.0.8, JDK 25, Java 8 source target) and a
NuGet-only `net10.0` consumer of the `netstandard2.0` PdfCarton libraries. It does
not change the separate JavaCompat Java 17 contract or product completion rules.

After checking RAM/CPU capacity, run the eight-page fixed sample, two selected
original fixtures, and four generated single-page cases:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -e \
  '(require (quote dripsharp.pdfcube.font-render-differential))
   (dripsharp.pdfcube.font-render-differential/verify!
     {:package-feed "PATH/TO/LOCAL/FEED"
      :maven-manifest "target/maven-reactor-inputs-0.tsv"
      :seed 22 :case-count 4})'
```

The feed selects an existing package: its version and SHA-256 are recorded and
checked against the restored artifact. This does not claim clean generation or
that the selected package includes uncommitted product changes. Omitting the
feed invokes the existing clean package workflow. Dependencies may come from
the feed or the local NuGet cache; restore writes only into the run directory.
`:dotnet "/path/to/x64/dotnet"` selects an installed x64 host for the same sample.
Record both macOS ARM64 and x64 runs under the existing required-host policy.

`dripsharp.differential-registry/registry` lists the command, sample and bounds.
No live JVM/.NET differential, package build, or raster campaign is enrolled in
hosted CI or default test discovery. Process-free checks run with:

```sh
clojure -M:test --namespace dripsharp.pdfcube-font-render-cases-test \
  --namespace dripsharp.pdfcube-pdfbox-font-text-differential-test \
  --namespace dripsharp.pdfcube-pdfbox-rendering-differential-test \
  --namespace dripsharp.differential-registry-test
```

## Inputs and observations

The parent Clojure generator creates each concrete description once. Both
probes read identical TSV bytes, including base64 UTF-8 text, explicit font
path, size, position, page rotation, affine transform and clipping rectangle.
The generated pages are 240 by 160 points at scale 1. Sizes range from 12 to 24
points in generated samples. Text includes BMP Latin/Greek, combining marks,
Hebrew bidi order, supplementary emoji, Devanagari, Bengali, Gujarati and Latin
ligatures. Each record has at most twelve pages and 64 UTF-16 units per string.
The default sample covers all eight text categories; seeds vary content and
combinations of layout and graphics state. `:seed 100 :case-count 16` expands a
local sample; `:case-count 0` runs just the fixed sample and original fixtures.

Both writers embed the same complete font files without subsetting. Both
readers reopen **both** produced PDFs. Exact comparisons cover page count,
rotation/crop dimensions, annotations, font subtype/embedding/damage, decoded
content bytes, extraction with and without positional sorting, UTF-16 units,
Unicode code points, character codes, glyph callback order, glyph matrices,
displacements and exposed text positions. Floating observations retain float
bits, including signed zero. A null glyph Unicode mapping remains `null`.

Some upstream GSUB ligatures have no reverse Unicode mapping: PDFBox extraction
may omit them, including the fixed Gujarati conjuncts. Their empty extraction
is retained, compared exactly and never counted as evidence of text extraction
fidelity to the input string. The glyph callback still observes their codes,
matrices and advances, and the raster comparison observes their outlines.
Neither probe synthesizes replacement text or normalizes bidi, combining marks,
missing glyphs, whitespace, or positions.

The original `PDFBOX-4531-bidi-ligature-1.pdf` and
`PDFBOX-5747-unicode-surrogate-with-diacritic-reduced.pdf` fixtures exercise
embedded Arabic/Hebrew presentation ligatures and a supplementary mathematical
character with a combining mark. Their first pages are extracted and rendered
at scale 0.25. All raster dimensions are bounded to 512 pixels per side.

The existing `pdfbox-rendering-differential/compare-rgba` remains authoritative:
RGB outlier threshold 48, alpha threshold 32, maximum mean absolute channel
error 10.0, and maximum outlier fraction 0.18. These tolerances are unchanged.
Dimensions and semantic observations compare exactly. Four raster comparisons
per generated page compare both readers and both writers. A page-wide raster
tolerance alone can miss a small misplaced glyph; the exact glyph/text/content
observations independently detect such changes.

## Licensed assets

Assets are resolved only from the pinned PDFBox checkout; no system font name
is used for generated text. Each recorded case retains its font file hashes;
replay rejects changed bytes. The selected original PDFs also have recorded
hashes. Font files are reused from upstream, not copied into this repository:

| Font | Upstream checkout path | License evidence |
| --- | --- | --- |
| Liberation Sans | `pdfbox/src/main/resources/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf` | `pdfbox/src/main/appended-resources/META-INF/LICENSE` |
| Lohit Devanagari/Bengali/Gujarati | `fontbox/src/test/resources/ttf/Lohit-*.ttf` | SIL OFL 1.1; `fontbox/src/main/appended-resources/META-INF/LICENSE` |
| Josefin Sans Italic | `fontbox/src/test/resources/ttf/JosefinSans-Italic.ttf` | SIL OFL 1.1; same FontBox license file |
| Noto Emoji | `fontbox/target/fonts/NotoEmoji-Regular.ttf` | Google 2013, SIL OFL 1.1 in font name records 0/13/14; exact fixture URL in `fontbox/pom.xml` |

Missing assets are setup failures; there is no fallback to installed fonts.
Existing PDFBox fixture acquisition supplies Noto Emoji.

## Evidence, replay and negative checks

Every invocation creates a distinct directory under `validation-output/font-render`.
It retains the concrete EDN/TSV inputs, generator version/seed, package/upstream
and runtime versions, process logs, saved PDFs, exact traces, PNGs and RGBA files.
`comparison.edn` records the first differing observation and every pixel result.
On differences, cropped, amplified red-channel difference PNGs and their pixel
bounds are retained alongside both complete images. No failed sample is removed.
An input/build/process failure, timeout, or behavioral mismatch remains a
failure with its own diagnostic category.

Replay consumes the recorded input without regenerating from a seed:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M \
  validation-output/font-render/run-N/case-M/replay.clj
```

The saved replay includes the chosen package feed, Maven manifest when explicit,
and .NET host. `:replay-file "path/to/input.edn" :fixtures? false` also works.
Runners build once per invocation and are reused for that invocation only.
The default total budget is fifteen minutes, with sixty seconds per probe
process and a bounded process count. `:max-ms` is capped at one hour and
`:case-count` at 100. There is no automatic schedule or unbounded campaign.

A separate explicit check reuses the successful run's retained binaries and
creates fresh fault directories. It changes the .NET writer's text and x
position separately, checks that both readers agree on each resulting PDF,
and requires the writer differences and focused diff images to be detected:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g \
  -Sdeps '{:paths ["src" "test"]}' -M -e \
  '(require (quote dripsharp.pdfcube-font-render-live))
   (dripsharp.pdfcube-font-render-live/verify!
     "validation-output/font-render/run-N")'
```

These injected perturbations test the observer; they are not product defects.
A passing bounded sample is not a claim of complete PDFBox equivalence.
