# Regex matcher sequences

This explicit local batch translates one bounded `Pattern`/`Matcher` template
through the ordinary Java-library pipeline. It covers repeated `find`, `reset`,
regions, zero-width UTF-16 progression, capture null/empty distinctions, split
limits, replacement escaping, Unicode flags, and categorized compile or
replacement failures. The concrete input is recorded before either side runs.

After checking local RAM and CPU, run:

```sh
DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g \
  clojure -J-Xmx28g -M -m dripsharp.microprogram \
  --batch validation/microprograms/regex/batch.edn
```

The generated library remains `netstandard2.0`; only the native observers use
`net10.0`. This local command is not part of hosted test discovery or CI.
