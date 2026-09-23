(ns dripsharp.floating-microprogram-live
  "Explicit local execution, deliberately outside _test.clj discovery."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.floating-microprogram :as floating]
            [dripsharp.paths :as paths]))

(defn- decode [token]
  (apply str (map #(char (Integer/parseInt (apply str %) 16))
                  (partition 4 (subs token 2)))))

(deftest floating-contracts-match-java-in-explicit-locales
  (let [result (floating/verify! {:samples 24})]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [rows (into {} (map (juxt :case identity)
                               (cases/read-observations
                                (slurp (str (paths/resolve-path
                                             (get-in result [:translated :directory]) "java.tsv"))))))
            value #(decode (:result (rows %)))]
        (is (= 988 (get-in result [:direct :matched])))
        (is (= 988 (get-in result [:translated :matched])))
        (is (= "0|1" (value "root-round-19")))
        (is (= "-0" (value "de-pdf-1")))
        (is (= "2.675" (value "de-pdf-25")))
        (is (= "2,67" (value "de-0p00-25")))
        (is (= "؜-٠٫٠٠" (value "ar-0p00-1")))))))

(deftest exact-text-comparison-and-snapshot-replay-detect-negative-zero-loss
  (let [change {:file "DripSharp/Microprograms/FloatingCases.cs"
                :before "return global::DripSharp.Runtime.JavaCompat.StringValueOf(d);"
                :after "return global::DripSharp.Runtime.JavaCompat.StringValueOf(d == 0.0 ? 0.0 : d);"}
        result (floating/verify! {:path "translated" :samples 0 :case-id "de-text-double-1"
                                  :perturbation change})
        saved (get-in result [:translated :snapshot])
        replay (when saved
                 (with-redefs [floating/snapshot (fn [& _] (throw (AssertionError. "Replay regenerated input")))]
                   (floating/verify! {:path "translated" :replay saved})))]
    (doseq [run [result replay]]
      (is (= :failed (:status run)) (pr-str run))
      (is (= :semantic-difference (get-in run [:translated :first-difference :kind])))
      (is (= "-0.0" (decode (get-in run [:translated :first-difference :oracle :result]))))
      (is (= "0.0" (decode (get-in run [:translated :first-difference :probe :result])))))))
