(ns dripsharp.collection-microprogram
  "Local bounded collection sequences: one input for direct and translated paths."
  (:require [clojure.string :as str]
            [dripsharp.collection-cases :as collection]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(def batch-file "validation/microprograms/collections/batch.edn")

(def encode collection/encode)
(def decode collection/decode)

(defn snapshot
  ([] (snapshot 31 12))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 24))
     (throw (ex-info "Require signed 64-bit seed and 0..24 samples" {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) batch-file)
         rng (Random. (long seed))
         fixed (cases/read-cases (:input base))
         generated
         (mapv (fn [index]
                 (let [order (.nextInt rng 3)
                       operations
                       (loop [remaining (+ 8 (.nextInt rng 25)) result [] values {}]
                         (if (zero? remaining) result
                             (let [op (.nextInt rng 23)
                                   id (dec (.nextInt rng 5))
                                   value (dec (.nextInt rng 5))
                                   ;; HashMap.values().remove removes an unspecified matching
                                   ;; entry when values repeat. Admit only unique matches there.
                                   op (if (and (= order 0) (= op 4)
                                               (> (count (filter #{value} (vals values))) 1)) 18 op)
                                   values (case op
                                            0 (assoc values id value)
                                            2 (dissoc values id)
                                            3 (dissoc values id)
                                            4 (if (= 1 (count (filter #{value} (vals values))))
                                                (into {} (remove #(= value (val %)) values)) values)
                                            5 (if (contains? values id) (assoc values id value) values)
                                            6 (if (= value (get values id ::absent)) (dissoc values id) values)
                                            (19 20 21) {}
                                            values)]
                               (recur (dec remaining) (conj result [op id value]) values))))]
                   {:family "collections" :case (str "seed-" seed "-" index)
                    :step "0" :operation "run" :expected-exception "none"
                    :arguments [(str "i:" order) (encode operations)]})) (range samples))]
     (assoc base :generator {:id :collection-sequences :version 1 :seed seed :samples samples
                             :max-operations 32 :runtime (System/getProperty "java.version")}
            :input (cases/write-cases (into fixed generated))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id path]
     :or {seed 31 samples 12 path "both"} :as options}]
   (when-not (#{"both" "direct" "translated"} path)
     (throw (ex-info "Path must be both, direct, or translated" {:path path})))
   (let [record (if replay (util/read-single-edn-string! (slurp replay)) (snapshot seed samples))
         record (if case-id
                  (let [rows (filterv #(= case-id (:case %)) (cases/read-cases (:input record)))]
                    (when (empty? rows) (throw (ex-info "Unknown collection case" {:case case-id})))
                    (assoc record :input (cases/write-cases rows))) record)
         _ (micro/validate-snapshot! record)
         _ (compat/select-cases (:input record) {:family "collections"})
         parent (paths/resolve-path (paths/workspace-root) "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         directory (Files/createTempDirectory parent "collections-" (make-array FileAttribute 0))
         saved (paths/resolve-path directory "snapshot.edn")
         input (paths/resolve-path directory "input.tsv")
         _ (spit (str saved) (pr-str record))
         _ (spit (str input) (:input record))
         direct (when (#{"both" "direct"} path)
                  (compat/verify-cases! {:input-file (str input)}))
         translated (when (#{"both" "translated"} path)
                      (micro/verify! (assoc (dissoc options :seed :samples :case-id :path)
                                           :replay (str saved))))
         result {:status (if (every? #(= :passed (:status %)) (remove nil? [direct translated]))
                           :passed :failed)
                 :snapshot (str saved) :generator (:generator record)
                 :direct direct :translated translated
                 :first-trace-difference (or (collection/first-trace-difference direct)
                                             (collection/first-trace-difference translated))
                 :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.collection-microprogram --replay "
                              "'" (str/replace (str saved) "'" "'\"'\"'") "' --path " path)}]
     (spit (str (paths/resolve-path directory "summary.edn")) (pr-str result))
     result)))

(defn -main [& arguments]
  (try
    (let [options (loop [args arguments result {}]
                    (if (empty? args) result
                        (let [[flag value & more] args
                              key ({"--seed" :seed "--samples" :samples "--replay" :replay
                                    "--case" :case-id "--path" :path} flag)]
                          (when-not (and key value (not (contains? result key)))
                            (throw (ex-info "Usage: [--seed N --samples 0..24 | --replay SNAPSHOT] [--case ID] [--path both|direct|translated]" {})))
                          (recur more (assoc result key (if (#{:seed :samples} key) (parse-long value) value))))))
          _ (when (and (:replay options) (some #(contains? options %) [:seed :samples]))
              (throw (ex-info "Replay cannot regenerate inputs" {})))
          result (verify! options)]
      (prn result)
      (shutdown-agents)
      (when-not (= :passed (:status result)) (System/exit 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :data (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
