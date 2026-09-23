(ns dripsharp.java-file-microprogram-live
  "Explicit local JVM/translated and native FileInfo consumer verification."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-file-microprogram :as files]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util]))

(defn- read-edn [file] (util/read-single-edn-string! (slurp (str file))))

(deftest generated-file-flows-and-native-consumer
  (let [result (files/verify!)]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [rows (cases/read-observations
                  (slurp (str (paths/resolve-path (:directory result) "java.tsv"))))
            results (into {} (map (juxt (juxt :case :operation) identity) rows))
            processes (read-edn (paths/resolve-path (:directory result) "processes.edn"))]
        (is (= 323 (:matched result)))
        (is (= :passed (get-in result [:native-consumer :status])))
        (is (= "u:" (:result (results ["empty" "protected-super"]))))
        (is (= "u:00620061006400000070006100740068" (:result (results ["nul" "bound-reference"]))))
        (is (= "n:" (:result (results ["null" "field-return"]))))
        (is (= "null-pointer" (:exception (results ["null" "construct"]))))
        (is (= "i:0" (:result (results ["empty" "queries"]))))
        (is (= 1 (count (filter #(= :dotnet-build (:phase %)) processes))))
        (is (= 1 (count (filter #(= :native-consumer (:phase %)) processes))))))))

(deftest accidental-internal-export-is-detected-and-replayable
  (let [change {:file "DripSharp/Microprograms/DerivedFileBox.cs"
                :before "base.__JavaFile_Get(path)"
                :after "global::DripSharp.Runtime.JavaFileBridge.Import<global::DripSharp.Runtime.JavaFile>(base.Get(path))"}
        result (files/verify! {:samples 0 :perturbation change})
        replay (micro/verify! {:replay (:snapshot result)})]
    (doseq [run [result replay]]
      (is (= :failed (:status run)) (pr-str run))
      (is (= :semantic-difference (get-in run [:first-difference :kind])))
      (is (= "empty" (get-in run [:first-difference :identity :case])))
      (is (= "override-super" (get-in run [:first-difference :identity :operation])))
      (is (= "illegal-argument" (get-in run [:first-difference :probe :exception])))
      (is (= change (:perturbation (read-edn (:snapshot run))))))
    (is (= (:first-difference result) (:first-difference replay)))
    (println
     "Live translated-bridge negative control: exporting the internal empty JavaFile through FileInfo was detected at empty/override-super and reproduced from the saved snapshot.")))
