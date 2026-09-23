# Unicode and charset conformance

This explicit local family sends the same recorded UTF-16 tokens, encoded byte
spelling, and chunk plan through Java 17, the authored JavaCompat runtime, and
the same Java source translated to a netstandard2.0 library. Native net10.0
observers encode results as UTF-16 code units, so isolated surrogates survive
transport without replacement.

Run the fixed regressions and a bounded generated sample after checking local
RAM and CPU:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.charset-microprogram --seed 41 --samples 12
```

Focused boundary replay is available with `--samples 0 --case CASE`, including
`stream-split-utf8`, `string-isolated-surrogates`, and
`utf8-malformed-report`. `--path direct` and `--path translated` select one
implementation path. The saved snapshot contains the actual inputs and source;
`--replay SNAPSHOT` does not regenerate from a seed.

The direct `charset` family is also registered with the bounded generated
campaign. That path records seed, generator/runtime versions, the first
difference, and an exact replay command, and applies the existing bounded
semantic reducer to byte spellings, chunk plans, and UTF-16 unit sequences:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.differential-campaign \
  --family charset --seed 41 --count 12 --max-processes 200 --max-ms 300000 \
  --shrink-attempts 100 --shrink-ms 30000
```

Decode observations distinguish replacement from reporting and retain the
failure operation, input position, remaining bytes, and output code units.
Streaming cases use a chunk-limited source and record reads, final consumption,
close/flush-at-EOF effects, and output units. String cases compare UTF-16
length, indexing, subsequences, code-point lookup/counting, and traversal for
empty, NUL, BMP, supplementary, and isolated-surrogate values.

UTF-8, BOM-aware UTF-16, fixed-endian UTF-16, US-ASCII, ISO-8859-1, and
Windows-1252 cover the standard and PdfCarton-used paths. Live JVM/.NET work is
not enrolled in hosted CI or default test discovery. Process-free checks use:

```sh
clojure -M:test --namespace dripsharp.charset-microprogram-test
```
