(ns dripsharp.differential-campaign
  "Explicit local generated campaigns and recorded replay; never test discovery."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [dripsharp.differential-generation :as gen]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn process-budget
  "Caps process admission and each timeout, including build and version probes.
  A shrink evaluation may tighten the deadline with :evaluation-deadline."
  [run-command! max-processes max-ms]
  (when-not (and (pos-int? max-processes) (pos-int? max-ms))
    (throw (ex-info "Invalid process budget" {:kind :invalid-budget})))
  (let [deadline (+ (System/nanoTime) (* 1000000 max-ms))
        evaluation-deadline (atom deadline)
        used (atom 0)]
    {:used used :deadline deadline :evaluation-deadline evaluation-deadline
     :run-command!
     (fn [command]
       (let [remaining (quot (- (min deadline @evaluation-deadline) (System/nanoTime)) 1000000)]
         (when (<= remaining 0)
           (throw (ex-info "Campaign time budget exhausted" {:kind :command-timeout})))
         (when (>= @used max-processes)
           (throw (ex-info "Campaign process budget exhausted" {:kind :process-budget})))
         (swap! used inc)
         (run-command! (assoc command :timeout-ms (min remaining (:timeout-ms command))))))}))

(defn- directory! [parent prefix]
  (Files/createDirectories (paths/path parent) (make-array FileAttribute 0))
  (Files/createTempDirectory (paths/path parent) prefix (make-array FileAttribute 0)))

(defn- replay-command [file]
  (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.differential-campaign --replay '"
       (str/replace (str file) "'" "'\"'\"'")
       "' --max-processes 20 --max-ms 180000 --shrink-attempts 0 --shrink-ms 1000"))

(def cli-usage
  (str "Expected --seed N --count N or --replay FILE; optional --family NAME, "
       "--output DIR, --max-operations N, --max-value-units N, "
       "--max-input-bytes N, --max-processes N, --max-ms N, "
       "--shrink-attempts N, --shrink-ms N"))

(defn parse-options
  "Parses the bounded local campaign CLI without starting child processes."
  [arguments]
  (loop [args arguments options {}]
    (if (empty? args)
      (cond-> options
        (some #(contains? options %) (keys gen/default-limits))
        (assoc :generation-limits
               (merge gen/default-limits (select-keys options (keys gen/default-limits))))

        (some #(contains? options %) (keys gen/default-limits))
        (#(apply dissoc % (keys gen/default-limits))))
      (let [[flag value & more] args
            key (get {"--seed" :seed "--count" :case-count "--family" :family
                      "--output" :output-directory "--replay" :replay-file
                      "--max-operations" :max-operations
                      "--max-value-units" :max-value-units
                      "--max-input-bytes" :max-input-bytes
                      "--max-processes" :max-processes "--max-ms" :max-ms
                      "--shrink-attempts" :shrink-attempts
                      "--shrink-ms" :shrink-ms} flag)]
        (when-not (and key value (not (contains? options key)))
          (throw (ex-info cli-usage {:kind :invalid-arguments})))
        (recur more (assoc options key (if (#{:family :output-directory :replay-file} key)
                                         value (parse-long value))))))))

(defn run!
  "Generates and records one input before either implementation sees it. Stops
  at the first failure, optionally shrinks semantic failures, and saves reports.
  Replay never calls the generator. All budgets include setup and both sides."
  [{:keys [seed case-count family output-directory replay-file max-processes max-ms
           shrink-attempts shrink-ms generation-limits run-command!]
    :or {seed 1 case-count 10 output-directory "validation-output/generated-cases"
         max-processes 200 max-ms 300000 shrink-attempts 100 shrink-ms 30000
         run-command! process/run!}}]
  (when-not (and (integer? seed) (pos-int? case-count) (<= case-count 1000)
                 (or (nil? family) (contains? compat/case-families family))
                 (pos-int? max-ms) (<= max-ms 3600000) (nat-int? shrink-attempts)
                 (<= shrink-attempts 10000) (pos-int? shrink-ms) (<= shrink-ms max-ms)
                 (pos-int? max-processes) (<= max-processes 10000))
    (throw (ex-info "Invalid campaign limits" {:kind :invalid-budget})))
  (let [root (paths/workspace-root)
        proof-root (directory! (paths/absolute output-directory) "campaign-")
        budget (process-budget run-command! max-processes max-ms)
        versions (atom {})
        counts (atom gen/empty-counts)
        records (atom [])
        ;; Persist the initial input before setup; failed builds retain it too.
        save! (fn [record]
                (let [directory (directory! proof-root "case-")
                      file (paths/resolve-path directory "case.edn")]
                  (gen/write-record! file record)
                  (swap! records conj (str file))
                  {:record record :file file :directory directory}))
        next-record! (fn [index]
                       (if replay-file
                         (gen/read-record replay-file)
                         (do (swap! counts update :generated inc)
                             (gen/generate (unchecked-add (long seed) (long index))
                                           (cond-> generation-limits
                                             family (assoc :families [family]))))))
        result
        (try
          (let [initial (save! (next-record! 0))
                runner (compat/prepare-case-runner!
                        {:root root :contract (compat/read-contract root) :build-root proof-root
                         :versions versions :run-command! (:run-command! budget)})
                execute! (fn [{:keys [record directory file]}]
                           (let [result ((:execute! runner) (:operations record) directory)
                                 result (assoc result :record (str file) :replay (replay-command file))]
                             (util/write-text! (paths/resolve-path directory "result.edn")
                                               (str (pr-str result) "\n"))
                             result))]
            (loop [index 0 saved initial]
              (let [result (execute! saved)]
                (swap! counts gen/count-result result)
                (cond
                  (gen/failure-signature result)
                  (let [reduction
                        (gen/shrink!
                         (:record saved) result
                         (fn [record remaining-ms]
                           (reset! (:evaluation-deadline budget)
                                   (+ (System/nanoTime) (* 1000000 remaining-ms)))
                           (execute! (save! record)))
                         {:max-attempts shrink-attempts :max-ms shrink-ms})
                        minimal-file (paths/resolve-path proof-root "minimal.edn")]
                    (gen/write-record! minimal-file (:record reduction))
                    {:status :failed :original result
                     :reduction (dissoc reduction :record) :minimal (str minimal-file)
                     :replay (replay-command minimal-file)})

                  (not= :passed (:status result)) result
                  (or replay-file (= (inc index) case-count)) {:status :passed}
                  :else (recur (inc index) (save! (next-record! (inc index))))))))
          (catch clojure.lang.ExceptionInfo error
            (let [result {:status :failed :first-difference (ex-data error)}]
              (if (#{:invalid-generated-case :invalid-case} (:kind (ex-data error)))
                (swap! counts update :rejected inc)
                (swap! counts gen/count-result result))
              result)))
        result (if (and (not= :passed (:status result))
                        (nil? (:replay result))
                        (first @records))
                 (assoc result :replay (replay-command (first @records)))
                 result)
        summary (merge result {:counts @counts :records @records :versions @versions
                               :host (util/current-host) :proof-root (str proof-root)
                               :processes @(:used budget)
                               :limits {:cases case-count :processes max-processes :ms max-ms
                                        :family family
                                        :shrink-attempts shrink-attempts :shrink-ms shrink-ms}})]
    (util/write-text! (paths/resolve-path proof-root "summary.edn") (str (pr-str summary) "\n"))
    summary))

(defn -main [& args]
  (try
    (let [options (parse-options args)
          summary (run! options)]
      (prn summary)
      (shutdown-agents)
      (System/exit (if (= :passed (:status summary)) 0 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :error (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
