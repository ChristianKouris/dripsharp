(ns dripsharp.semantics-microprogram-test
  "Process-free input admission. Executable evidence lives in *_live.clj."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-registry :as registry]
            [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.semantics-microprogram :as semantics]))

(deftest bounded-template-inputs-preserve-all-branches-and-exception-intent
  (let [snapshot (semantics/snapshot 19 0)
        rows (micro/validate-snapshot! snapshot)]
    (is (= snapshot (semantics/snapshot 19 0)))
    (is (= 30 (count rows)))
    (is (= (set (map :method semantics/templates)) (set (map :operation rows))))
    (is (= #{"none" "null-pointer" "class-cast"} (set (map :expected-exception rows))))
    (is (:isolate-cases? snapshot))
    (is (= :explicit-local-only (get-in (registry/registry) [:translated-microprograms :families "semantics" :execution])))
    (doseq [seed [0 19 Long/MIN_VALUE Long/MAX_VALUE]]
      (let [generated (semantics/snapshot seed 24)]
        (is (= generated (semantics/snapshot seed 24)))
        (is (= 54 (count (micro/validate-snapshot! generated))))))
    (doseq [[seed samples] [[0 -1] [0 25] [0 nil] [1.5 1]]]
      (is (thrown? clojure.lang.ExceptionInfo (semantics/snapshot seed samples))))))

(deftest focused-replay-retains-small-authored-source-and-recorded-input
  (let [record (semantics/snapshot 19 0)]
    (doseq [[case-id source] [["overloads-0" "BoxedOverload"]
                             ["initialization-0" "InitializationOrder"]]]
      (let [selected (semantics/select-case record case-id)
            path (str "subjects/micro/" source ".java")]
        (is (= {path (get-in record [:sources path])} (:sources selected)))
        (is (= [case-id] (mapv :case (micro/validate-snapshot! selected))))
        (is (= (filterv #(= case-id (:case %)) (cases/read-cases (:input record)))
               (cases/read-cases (:input selected))))))
    (is (thrown? clojure.lang.ExceptionInfo (semantics/select-case record "missing")))))
