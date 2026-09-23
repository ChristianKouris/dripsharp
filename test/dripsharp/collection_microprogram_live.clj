(ns dripsharp.collection-microprogram-live
  "Explicit local live collection checks; excluded from default discovery."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.collection-microprogram :as collection]))

(deftest direct-and-translated-collection-sequences
  (let [result (collection/verify! {:seed 31 :samples 12})]
    (is (= :passed (:status result)) (pr-str result))
    (is (= 34 (get-in result [:direct :matched])))
    (is (= 34 (get-in result [:translated :matched])))))
