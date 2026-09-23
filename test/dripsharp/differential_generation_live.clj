(ns dripsharp.differential-generation-live
  "Explicit local fault demonstration; excluded from *_test.clj discovery."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.differential-campaign :as campaign]
            [dripsharp.differential-cases :as cases]
            [dripsharp.differential-generation :as gen]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]))

(defn- signed-byte-test-fault!
  "Run the real process, then deliberately corrupt only successful negative
  byte observations. Keep unmodified output and prove actual runtimes matched.
  This is a harness test fault, not a claim of a JavaCompat product defect."
  [{:keys [command] :as invocation}]
  (let [result (process/run! invocation)]
    (when (and (= "dotnet" (first command)) (some #{"--cases"} command))
      (let [[input output] (take-last 2 command)
            directory (.getParent (paths/path output))
            actual (slurp output)
            rows (cases/read-cases (slurp input))
            oracle (slurp (str (paths/resolve-path directory "java.tsv")))
            observations (cases/read-observations actual)]
        (is (= :passed (:status (cases/compare-executions
                                 rows {:status :ok :observations oracle}
                                 {:status :ok :observations actual}))))
        (spit (str (paths/resolve-path directory "dotnet-unperturbed.tsv")) actual)
        (spit output
              (str cases/output-header "\n"
                   (str/join "\n"
                             (map (fn [row]
                                    (let [row (if (and (= "byte-buffer" (:family row))
                                                       (= "get" (:operation row))
                                                       (= "none" (:exception row))
                                                       (str/starts-with? (:result row) "i:-"))
                                                (assoc row :result (str "i:" (+ 256 (parse-long (subs (:result row) 2))))) row)]
                                      (str/join "\t" (map row cases/output-keys)))) observations)) "\n"))))
    result))

(deftest generated-live-fault-shrinks-and-replays-without-generation
  (let [summary (campaign/run! {:seed 42 :case-count 1 :family "byte-buffer"
                                :shrink-attempts 200
                                :shrink-ms 60000 :max-ms 180000 :max-processes 200
                                :run-command! signed-byte-test-fault!})
        original (gen/read-record (first (:records summary)))
        minimal (gen/read-record (:minimal summary))]
    (is (= :failed (:status summary)))
    (is (= 17 (count (:operations original))))
    (is (= 2 (count (:operations minimal))))
    (is (= ["wrap" "get"] (mapv :operation (:operations minimal))))
    (is (= ["b:80"] (:arguments (first (:operations minimal)))))
    (is (= "4" (:step (last (:operations minimal)))))
    (is (= (gen/failure-signature (:original summary))
           (get-in summary [:reduction :signature])))
    (is (= :exhausted (get-in summary [:reduction :stop])))
    (is (zero? (get-in summary [:reduction :counts :harness-failed])))
    (is (zero? (get-in summary [:reduction :counts :timed-out])))
    (with-redefs [gen/generate (fn [& _] (throw (AssertionError. "Replay called generator")))]
      (let [replay (campaign/run! {:replay-file (:minimal summary) :shrink-attempts 0
                                   :max-ms 180000 :run-command! signed-byte-test-fault!})]
        (is (= 0 (get-in replay [:counts :generated])))
        (is (= (get-in summary [:reduction :signature])
               (gen/failure-signature (:original replay)))))))
  (println "Live signed-byte test fault: the 17-operation byte-buffer sequence reduced to wrap(b:80), get; recorded replay verified."))
