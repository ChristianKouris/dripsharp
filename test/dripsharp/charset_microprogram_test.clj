(ns dripsharp.charset-microprogram-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.charset-microprogram :as charset]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]))

(deftest charset-snapshot-is-bounded-exact-and-process-free
  (let [snapshot (charset/snapshot 41 12)
        rows (micro/validate-snapshot! snapshot)
        generated (filter #(str/starts-with? (:case %) "seed-") rows)]
    (is (= snapshot (charset/snapshot 41 12)))
    (is (= 12 (count generated)))
    (is (<= (count rows) 1000))
    (is (= #{"decode" "stream" "string-ops"}
           (set (map :operation generated))))
    (is (= rows (compat/select-cases (:input snapshot) {:family "charset"})))
    (is (some #(= ["stream-split-utf8" "stream"] [(:case %) (:operation %)]) rows))
    (is (some #(= "u:d8000061dc00ffff" (first (:arguments %))) rows))))

(deftest zero-generated-samples-retain-fixed-regressions
  (let [rows (cases/read-cases (:input (charset/snapshot 9 0)))]
    (is (empty? (filter #(str/starts-with? (:case %) "seed-") rows)))
    (is (some #(= "utf8-malformed-report" (:case %)) rows))))
