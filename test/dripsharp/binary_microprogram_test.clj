(ns dripsharp.binary-microprogram-test
  "Process-free admission and coverage for translated binary templates."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.binary-microprogram :as binary]
            [dripsharp.differential-cases :as cases]
            [dripsharp.differential-generation :as generation]
            [dripsharp.differential-registry :as registry]
            [dripsharp.microprogram :as micro]))

(deftest translated-binary-snapshot-is-bounded-and-replayable
  (let [snapshot (binary/snapshot)
        rows (micro/validate-snapshot! snapshot)]
    (is (= snapshot (binary/snapshot)))
    (is (= 19 (count rows)))
    (is (= #{"parse" "data"} (set (map :operation rows))))
    (is (= #{"i:1" "i:2" "i:4" "i:8"}
           (set (map (comp last :arguments)
                     (filter #(= "data" (:operation %)) rows)))))
    (is (= :explicit-local-only
           (get-in (registry/registry)
                   [:translated-microprograms :families "binary" :execution])))
    (doseq [samples [-1 25]]
      (is (thrown? clojure.lang.ExceptionInfo (binary/snapshot 23 samples))))))

(deftest direct-generated-binary-families-cover-state-and-failure-contracts
  (doseq [[family required exceptions]
          [["byte-buffer"
            #{"wrap" "capacity" "position" "limit" "mark" "reset" "clear"
              "flip" "rewind" "slice" "duplicate" "read-only" "select" "order"
              "get" "get-short" "get-int-at" "get-long" "get-at" "put"
              "put-int-at" "put-long" "set-position" "set-limit"}
            #{"none" "buffer-underflow" "buffer-overflow" "read-only"}]
           ["binary-data"
            #{"open" "read-byte" "read-unsigned-byte" "read-short" "read-int"
              "read-long" "read-fully" "write-byte" "write-short" "write-int"
              "write-long" "write-buffer" "flush"}
            #{"none" "eof"}]]]
    (let [record (generation/generate 23 {:families [family]})
          rows (:operations record)]
      (is (= record (generation/validate! record)))
      (is (= required (set (map :operation rows))))
      (is (= exceptions (set (map :expected-exception rows))))
      (is (every? #(every? cases/value-token? (:arguments %)) rows)))))
