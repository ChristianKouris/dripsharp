(ns dripsharp.numeric-microprogram-test
  "Process-free numeric template admission and coverage checks."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.differential-generation :as gen]
            [dripsharp.microprogram :as micro]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.numeric-microprogram :as numeric]
            [dripsharp.differential-registry :as registry]))

(deftest exact-bounded-numeric-inputs
  (let [snapshot (numeric/snapshot)
        rows (micro/validate-snapshot! snapshot)
        largest (micro/validate-snapshot! (numeric/snapshot Long/MIN_VALUE 48))]
    (is (= snapshot (numeric/snapshot)))
    (is (= 982 (count rows)))
    (is (<= (count largest) 1000))
    (is (some #(some #{"i:-9223372036854775808"} (:arguments %)) rows))
    (is (some #(some #{"i:9223372036854775807"} (:arguments %)) rows))
    (is (= #{"none" "arithmetic" "null-pointer" "index-out-of-bounds"}
           (set (map :expected-exception rows))))
    (is (= (set (keys (:operations snapshot))) (set (map :operation rows))))
    (is (= ["i:1" "i:1"] (:arguments (first rows))))
    (is (= :explicit-local-only (get-in (registry/registry) [:translated-microprograms :families "numeric" :execution])))
    (is (= true (:generated? (registry/select-family "numeric"))))
    (doseq [samples [-1 49]]
      (is (thrown? clojure.lang.ExceptionInfo (numeric/snapshot 13 samples))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (micro/validate-snapshot! (assoc-in snapshot [:operations "signedRead" :java-state] "bad.method"))))))

(deftest numeric-direct-generation-preserves-exception-intent
  (let [records (mapv #(gen/generate % {:families ["numeric"]}) (range 30))
        rows (mapcat :operations records)]
    (is (every? #(= % (gen/validate! %)) records))
    (is (= #{"int-div" "int-rem" "long-div" "long-rem"} (set (map :operation rows))))
    (is (= #{"none" "arithmetic"} (set (map :expected-exception rows))))
    (is (every? #(every? cases/value-token? (:arguments %)) rows))
    (is (thrown? clojure.lang.ExceptionInfo
                 (compat/select-cases
                  (cases/write-cases [{:family "numeric" :case "too-wide" :step "0"
                                       :operation "long-div" :expected-exception "none"
                                       :arguments ["i:9223372036854775808" "i:1"]}]) {})))))
