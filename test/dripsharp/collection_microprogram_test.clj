(ns dripsharp.collection-microprogram-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.collection-cases :as input]
            [dripsharp.collection-microprogram :as collection]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]))

(deftest bounded-concrete-sequences-are-replayable-without-processes
  (let [record (collection/snapshot 31 12)
        rows (cases/read-cases (:input record))]
    (is (= record (collection/snapshot 31 12)))
    (is (= 34 (count rows)))
    (is (= rows (micro/validate-snapshot! record)))
    (is (= rows (compat/select-cases (:input record) {:family "collections"})))
    (is (not= (:input record) (:input (collection/snapshot 32 12))))
    (doseq [row rows]
      (let [token (second (:arguments row))
            operations (collection/decode token)]
        (is (<= 1 (count operations) 32))
        (is (= token (collection/encode operations)))))
    (is (= (set (range 23))
           (set (map first (mapcat #(collection/decode (second (:arguments %))) rows)))))))

(deftest campaign-limits-reject-before-building
  (doseq [args [[0 -1] [0 25] [0 nil] [nil 1] [9223372036854775808N 1]]]
    (is (thrown? clojure.lang.ExceptionInfo (apply collection/snapshot args)))))

(deftest invalid-and-unspecified-inputs-are-rejected-before-execution
  (doseq [arguments [["i:3" (collection/encode [[0 0 0]])]
                     ["i:0" (collection/encode [[0 9 0]])]
                     ["i:0" (collection/encode [[23 0 0]])]
                     ["i:0" (collection/encode (repeat 33 [0 0 0]))]
                     ["i:0" (collection/encode [[0 0 1] [0 1 1] [4 0 1]])]]]
    (let [input (cases/write-cases [{:family "collections" :case "invalid" :step "0"
                                   :operation "run" :expected-exception "none"
                                   :arguments arguments}])]
      (is (thrown? clojure.lang.ExceptionInfo (compat/select-cases input {}))))))

(deftest trace-differences-retain-operation-index-and-post-failure-state
  (let [token (fn [text] (str "u:" (apply str (map #(format "%04x" (int %)) text))))
        failure {:first-difference {:kind :semantic-difference :identity {:case "example"}
                 :oracle {:result (token "0:ok|state\n1:null-pointer|unchanged\n")}
                 :probe {:result (token "0:ok|state\n1:none|changed\n")}}}]
    (is (= {:index 1 :identity {:case "example"}
            :oracle "1:null-pointer|unchanged" :probe "1:none|changed"}
           (input/first-trace-difference failure)))
    (is (nil? (input/first-trace-difference {:status :failed :phase :dotnet-build})))))
