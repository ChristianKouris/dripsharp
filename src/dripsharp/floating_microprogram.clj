(ns dripsharp.floating-microprogram
  "Bounded direct and translated floating-point cases over one recorded input."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(def batch-file "validation/microprograms/floating/batch.edn")

(defn- u [value]
  (str "u:" (apply str (map #(format "%04x" (int %)) value))))

(defn snapshot
  ([] (snapshot 73 12))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 24))
     (throw (ex-info "Require signed 64-bit seed and 0..24 samples" {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) batch-file)
         rng (Random. (long seed))
         generated (mapv (fn [i]
                           {:family "floating" :case (str "seed-" seed "-" i)
                            :step "0" :operation "run" :expected-exception "none"
                            :arguments [(u (nth ["root" "de" "ar"] (mod i 3)))
                                        (u (if (even? i) "convert" "text-float"))
                                        (u (if (even? i)
                                             (Double/toString (Double/longBitsToDouble (.nextLong rng)))
                                             (Float/toString (Float/intBitsToFloat (.nextInt rng)))))]})
                         (range samples))]
     (assoc base :generator {:id :floating-boundaries :version 1 :seed seed :samples samples
                            :runtime (System/getProperty "java.version")}
            :input (cases/write-cases (into (cases/read-cases (:input base)) generated))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id path]
     :or {seed 73 samples 12 path "both"} :as options}]
   (when-not (#{"both" "direct" "translated"} path)
     (throw (ex-info "Path must be both, direct, or translated" {:path path})))
   (let [record (if replay (util/read-single-edn-string! (slurp replay))
                    (snapshot seed samples))
         record (if case-id
                  (let [rows (filterv #(= case-id (:case %))
                                      (cases/read-cases (:input record)))]
                    (when (empty? rows)
                      (throw (ex-info "Unknown floating case" {:case case-id})))
                    (assoc record :input (cases/write-cases rows)))
                  record)
         _ (micro/validate-snapshot! record)
         _ (compat/select-cases (:input record) {:family "floating"})
         parent (paths/resolve-path (paths/workspace-root)
                                    "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         directory (Files/createTempDirectory parent "floating-"
                                               (make-array FileAttribute 0))
         saved (paths/resolve-path directory "snapshot.edn")
         input (paths/resolve-path directory "input.tsv")
         _ (spit (str saved) (pr-str record))
         _ (spit (str input) (:input record))
         direct (when (#{"both" "direct"} path)
                  (compat/verify-cases! {:input-file (str input)}))
         translated (when (#{"both" "translated"} path)
                      (micro/verify! (assoc (dissoc options :seed :samples :case-id :path)
                                           :replay (str saved))))
         result {:status (if (every? #(= :passed (:status %))
                                     (remove nil? [direct translated]))
                           :passed :failed)
                 :snapshot (str saved) :generator (:generator record)
                 :direct direct :translated translated
                 :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.floating-microprogram --replay "
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
                          (recur more (assoc result key (if (#{:seed :samples} key)
                                                        (parse-long value) value))))))
          _ (when (and (:replay options)
                       (some #(contains? options %) [:seed :samples]))
              (throw (ex-info "Replay cannot regenerate inputs" {})))
          result (verify! options)]
      (prn result)
      (shutdown-agents)
      (when-not (= :passed (:status result)) (System/exit 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :data (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
