(ns dripsharp.numeric-microprogram-live
  "Explicit local JVM/generated C# checks, outside automatic test discovery."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.numeric-microprogram :as numeric]
            [dripsharp.paths :as paths]))

(defn- decode [token]
  (apply str (map #(char (Integer/parseInt (apply str %) 16))
                  (partition 4 (subs token 2)))))

(deftest numeric-results-exceptions-and-side-effects-match
  (let [result (numeric/verify!)]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [observed (into {} (map (juxt :case identity)
                                   (cases/read-observations
                                    (slurp (str (paths/resolve-path (:directory result) "java.tsv"))))))]
        (is (= 982 (:matched result)))
        (is (= "i:2" (:result (observed "saved-byte-value"))))
        (is (= "123:2" (decode (:state (observed "saved-byte-value")))))
        (is (= "i:-32768" (:result (observed "short-narrowing"))))
        (is (= "i:65535" (:result (observed "char-narrowing"))))
        (is (= "i:1" (:result (observed "long-distance"))))
        (is (= "i:-128" (:result (observed "signed-byte-read"))))
        (is (= "i:-9223372036854775808" (:result (observed "longBinaryDiv-0"))))
        (is (= "i:0" (:result (observed "longBinaryRem-0"))))
        (is (= "arithmetic" (:exception (observed "byteCompoundDiv-2"))))
        (is (= "123:7" (decode (:state (observed "byteCompoundDiv-2")))))
        (is (= "12:null" (decode (:state (observed "failure-0")))))
        (is (= "12:1" (decode (:state (observed "failure-1")))))))))

(deftest signed-byte-negative-control-is-detected-and-replayable
  (let [change {:file "DripSharp/Microprograms/NumericCases.cs"
                :before "return bytes[0];" :after "return unchecked((byte)bytes[0]);"}
        result (numeric/verify! {:samples 0 :case-id "signed-byte-read" :perturbation change})
        replay (with-redefs [numeric/snapshot (fn [& _] (throw (AssertionError. "Replay regenerated input")))]
                 (numeric/verify! {:replay (:snapshot result)}))]
    (doseq [run [result replay]]
      (is (= :failed (:status run)) (pr-str run))
      (is (= :semantic-difference (get-in run [:first-difference :kind])))
      (is (= "i:-128" (get-in run [:first-difference :oracle :result])))
      (is (= "i:128" (get-in run [:first-difference :probe :result]))))
    (is (= (:first-difference result) (:first-difference replay)))))
