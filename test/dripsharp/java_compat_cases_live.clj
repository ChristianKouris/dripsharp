(ns dripsharp.java-compat-cases-live
  "Explicit local live regression. Deliberately not an *_test.clj discovery file."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as java-compat]
            [dripsharp.paths :as paths]))

(deftest recorded-cases-match-live-runtimes-and-reject-perturbations
  (let [summary (java-compat/verify-cases!)
        directory (.getParent (paths/path (:input summary)))
        rows (cases/read-cases (slurp (:input summary)))
        oracle (slurp (str (paths/resolve-path directory "java.tsv")))
        probe (slurp (str (paths/resolve-path directory "dotnet.tsv")))
        execution (fn [text] {:status :ok :observations text})
        compare-probe #(cases/compare-executions rows (execution oracle) %)]
    (is (= :passed (:status summary)))
    (is (= 17 (:matched summary)))
    (is (= 10 (:cases summary)))
    (is (= {:status :passed :matched 17} (compare-probe (execution probe))))
    (doseq [changed [(str/replace probe "i:-2147417597" "i:0")
                     (str/replace probe "buffer-underflow" "null-pointer")
                     (str/replace probe "get-int\tnone" "get-int\tbuffer-underflow")
                     ""]]
      (is (not= changed probe))
      (is (= :failed (:status (compare-probe (execution changed))))))
    (is (= :failed (:status (compare-probe {:status :process-failure :phase :probe
                                            :process {:exit 137}}))))))
