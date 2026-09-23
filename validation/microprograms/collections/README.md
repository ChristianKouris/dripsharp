# Collection conformance sequences

This explicit local family executes the same saved commands against Java 17,
the authored JavaCompat runtime, and Java translated through the ordinary
Java-library bundle. It covers ArrayList through List and nested generic aliases,
HashMap and insertion/access-order LinkedHashMap through Map, HashSet through
Set, and ArrayDeque through Deque, plus the distinct LinkedList null contract through Deque. The translated library is netstandard2.0;
the direct and translated observers are net10.0. The wider PdfCarton oracle
baseline and product contract are unchanged.

## Bounded local commands

Check available RAM/CPU first. Run one batch at a time with the repository's
22-worker and 28 GiB heap settings:

```sh
export DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g
# Fixed regressions plus 12 deterministic generated sequences on both paths:
clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --seed 31 --samples 12
# Fixed-only focused examples:
clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --samples 0 --case collision-views-2
clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --samples 0 --case list-overloads-0
# Broader exploration is still bounded: at most 24 extra sequences, 32 commands each.
clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --seed 932 --samples 24
# Replay actual saved inputs and translated source; never regenerate from the seed:
clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --replay validation-output/microprograms/collections-EXAMPLE/snapshot.edn
```

`--path direct` or `--path translated` selects one path. `--case ID` retains an
entire sequence. The direct runner also accepts the authored `cases.tsv` using
`dripsharp.java-compat-differential --cases ... --family collections`. The family
registry lists the combined command without executing it.

Each path builds its fixed batch once. Per-process and batch timeouts come from
the existing direct and microprogram harnesses. Their budgets bound the combined
run to less than 16 minutes. Snapshots save generator version 1, seed, generation
runtime, actual commands, Java source and settings. Each child summary retains
actual runtime versions, host, build/process failures, and native observations.
The combined summary includes the first differing internal operation and full
post-operation trace. Replay rebuilds using current translator/runtime sources;
the direct oracle also uses the current authored Java subject, as other direct
families do. To replay only captured Java subject sources, use `--path translated`.

No new live workload is enrolled in hosted CI, scheduled, or default-discovered.
The process-free tests start no Java/.NET children. Existing required tests remain
in place; these bounded sequences do not claim complete collection equivalence.

## Commands and observations

Each input row supplies map kind `0` (HashMap), `1` (insertion order), or `2`
(access order), followed by a UTF-16-encoded `op,key,value;...` script. Every row
starts fresh state. Key/value IDs are -1 (Java null) or 0..3. All non-null custom
keys deliberately have hash 7; separately constructed keys with the same ID are
equal. List index operations additionally accept invalid indices through 9.
Admission rejects out-of-domain and oversized scripts before building.

| Opcode | Operation |
| --- | --- |
| 0–2 | Map put, get, remove |
| 3–6 | Retained key-view remove, value-view remove, entry setValue, entry-view remove |
| 7–10 | List add, remove(int), remove(Object), set through nested generic alias |
| 11–12 | Set add/remove with equal, colliding, duplicate and null keys |
| 13–17 | Deque addFirst, addLast, poll, remove(), remove(Object) |
| 18 | containsKey and containsValue, including present-null |
| 19–21 | Clear through retained key/value/entry views |
| 22 | Equal-size and changed-size collection inequality; LinkedList null insertion/removal through Deque |

After **every** command, including a categorized exception, the trace includes
ordinal, opcode, exception category, result, and all collection state. Null is
`N`, distinct from the bounded string values `0`..`3`. Map membership includes an
explicit presence bit; absent and present-null never collapse. The observer
walks entries to avoid touching access order while taking a snapshot. It records
map and view sizes, defined LinkedHashMap key order, list and generic-alias
contents, canonical set membership, deque contents, equality with copies, and
Java collection hashes. JDK expectations never use JavaCompat.

HashMap and HashSet iteration order is not compared. For HashMap value-view
removal, a value must have at most one matching entry: Java does not specify
which duplicate-valued entry is removed. Defined LinkedHashMap order allows that
operation even with duplicates. Live views are retained before mutations;
iteration is started afresh after each command. No fail-fast timing or
unspecified behavior after external structural mutation during iteration is
required. ArrayDeque null rejection, empty removal, and list index failures are
observed separately and execution continues with the resulting state.

`cases.tsv` retains the manually reduced failures `shrunk-string-hash`,
`shrunk-null-value`, `shrunk-null-removal`, and `shrunk-deque-null`: respectively
one map insertion, insertion plus containsValue, list insertion plus value
removal, and one null deque insertion. Broader collision/view/order sequences
remain alongside these regressions. Build failures are not behavioral passes.

## Focused verification

```sh
clojure -M:test --namespace dripsharp.collection-microprogram-test
# Explicit local live entry point (not an *_test.clj discovery name):
clojure -J-Xmx28g -M:test --namespace dripsharp.collection-microprogram-live
clojure -J-Xmx28g -M:test --namespace dripsharp.linked-hash-map-test
```
