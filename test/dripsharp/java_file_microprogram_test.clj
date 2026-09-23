(ns dripsharp.java-file-microprogram-test
  "Process-free input admission only. Live behavior is explicitly local."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-file-microprogram :as files]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]))

(deftest generated-input-is-bounded-repeatable-and-retains-boundary-values
  (let [root (paths/workspace-root)
        saved (files/snapshot root 20260922 8)
        rows (micro/validate-snapshot! saved)
        tokens (set (map (comp first :arguments) rows))]
    (is (= saved (files/snapshot root 20260922 8)))
    (is (= 323 (count rows)))
    (is (every? tokens ["n:" "u:" "u:0020" "u:00620061006400000070006100740068"]))
    (is (= 19 (count (set (map :operation rows)))))
    (is (string? (:dotnet-consumer saved)))
    (is (= rows (cases/read-cases (cases/write-cases rows)))))
  (is (= 779 (count (micro/validate-snapshot! (files/snapshot (paths/workspace-root) 0 32)))))
  (doseq [samples [-1 33 nil]]
    (is (thrown? clojure.lang.ExceptionInfo (files/snapshot (paths/workspace-root) 0 samples)))))
