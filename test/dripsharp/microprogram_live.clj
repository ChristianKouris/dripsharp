(ns dripsharp.microprogram-live
  "Explicit local tests only; never included in *_test.clj auto-discovery."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- read-edn [file] (util/read-single-edn-string! (slurp (str file))))

(defn- modified-snapshot! [change]
  (let [directory (Files/createTempDirectory "microprogram-live-" (make-array FileAttribute 0))
        file (paths/resolve-path directory "snapshot.edn")
        snapshot (micro/read-batch (paths/workspace-root) micro/default-batch)]
    (spit (str file) (pr-str (change snapshot)))
    (str file)))

(deftest original-and-translated-subjects-match-exact-observations
  (let [result (micro/verify!)]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [file #(paths/resolve-path (:directory result) %)
            java (cases/read-observations (slurp (str (file "java.tsv"))))
            dotnet (cases/read-observations (slurp (str (file "dotnet.tsv"))))
            observed (into {} (map (juxt :case identity) java))
            processes (read-edn (file "processes.edn"))]
        (is (= 15 (:matched result)))
        (is (= java dotnet))
        (is (= "n:" (:result (observed "null"))))
        (is (= "u:" (:result (observed "empty"))))
        (is (= "u:0000d8000061dfff" (:result (observed "utf16"))))
        (is (= "arithmetic" (:exception (observed "zero-divisor"))))
        (is (= "index-out-of-bounds" (:exception (observed "bounds"))))
        (is (= 1 (count (filter #(= :translation (:phase %)) processes))))
        (is (= 1 (count (filter #(= :dotnet-build (:phase %)) processes))))
        (is (str/includes? (slurp (str (file "library/src/DripSharp/Microprograms/Collections.cs")))
                           "JavaCompat.ListGet"))))))

(deftest generated-code-perturbation-is-observable-and-replayable
  (let [change {:file "DripSharp/Microprograms/Numeric.cs"
                :before "left + right" :after "left - right"}
        first-run (micro/verify! {:perturbation change})
        replay (micro/verify! {:replay (:snapshot first-run)})]
    (doseq [result [first-run replay]]
      (is (= :failed (:status result)) (pr-str result))
      (is (= :semantic-difference (get-in result [:first-difference :kind])))
      (is (= "overflow" (get-in result [:first-difference :identity :case])))
      (is (= change (:perturbation (read-edn (:snapshot result))))))
    (is (= (:first-difference first-run) (:first-difference replay)))))

(deftest accepted-source-translation-and-generated-build-failures-retain-evidence
  (doseq [[phase change options]
          [[:java-compile #(assoc-in % [:sources "subjects/micro/Numeric.java"] "not Java") {}]
           [:translation #(assoc-in % [:sources "subjects/micro/Captured.java"]
                                    (str "package micro; public final class Captured { "
                                         "public int value(int offset) { class Local { "
                                         "int get() { return offset; } } return new Local().get(); } }")) {}]
           [:dotnet-build identity {:perturbation {:file "DripSharp/Microprograms/Numeric.cs"
                                                  :before "left + right" :after "missingIdentifier"}}]]]
    (testing (name phase)
      (let [result (micro/verify! (assoc options :replay (modified-snapshot! change)))
            processes (read-edn (paths/resolve-path (:directory result) "processes.edn"))]
        (is (= :failed (:status result)) (pr-str result))
        (is (= phase (:phase result)))
        (is (zero? (:matched result)))
        (is (paths/regular-file? (paths/path (:snapshot result))))
        (is (seq (get-in (last processes) [:process :output])))
        (is (= phase (:phase (last processes))))))))
