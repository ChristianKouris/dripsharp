(ns dripsharp.charset-microprogram
  "Bounded direct and translated charset cases over one recorded input."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(def batch-file "validation/microprograms/charset/batch.edn")

(defn- u [value]
  (str "u:" (apply str (map #(format "%04x" (int %)) value))))

(def ^:private charsets ["UTF-8" "UTF-16" "UTF-16BE" "UTF-16LE"
                         "US-ASCII" "ISO-8859-1" "windows-1252"])

(defn- random-bytes [^Random rng]
  (let [length (nth [0 1 2 3 4 8 16] (.nextInt rng 7))]
    (apply str (repeatedly length #(format "%02x" (.nextInt rng 256))))))

(defn- random-chunks [^Random rng length]
  (if (zero? length) "1"
      (str/join ","
                (loop [remaining length chunks []]
                  (if (zero? remaining) chunks
                      (let [size (inc (.nextInt rng (min 4 remaining)))]
                        (recur (- remaining size) (conj chunks size))))))))

(defn- random-string [^Random rng]
  (let [length (.nextInt rng 17)
        boundaries [0 9 127 255 55296 56320 65535]]
    (apply str (repeatedly length
                           #(char (if (< (.nextInt rng 4) 3)
                                    (nth boundaries (.nextInt rng (count boundaries)))
                                    (.nextInt rng 65536)))))))

(defn snapshot
  ([] (snapshot 41 12))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 24))
     (throw (ex-info "Require signed 64-bit seed and 0..24 samples"
                     {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) batch-file)
         rng (Random. (long seed))
         generated
         (mapv
          (fn [index]
            (case (mod index 3)
              0 (let [charset (nth charsets (.nextInt rng (count charsets)))
                      bytes (random-bytes rng)]
                  {:family "charset" :case (str "seed-" seed "-" index)
                   :step "0" :operation "decode" :expected-exception "none"
                   :arguments [(u charset) (str "i:" (.nextInt rng 2)) (u bytes)]})
              1 (let [charset (nth charsets (.nextInt rng (count charsets)))
                      bytes (random-bytes rng)]
                  {:family "charset" :case (str "seed-" seed "-" index)
                   :step "0" :operation "stream" :expected-exception "none"
                   :arguments [(u charset) (u bytes) (u (random-chunks rng (quot (count bytes) 2)))]})
              2 (let [value (random-string rng) begin (.nextInt rng (inc (count value)))
                      end (+ begin (.nextInt rng (inc (- (count value) begin))))]
                  {:family "charset" :case (str "seed-" seed "-" index)
                   :step "0" :operation "string-ops" :expected-exception "none"
                   :arguments [(u value) (str "i:" begin) (str "i:" end)]})))
          (range samples))]
     (assoc base :generator {:id :charset-boundaries :version 1 :seed seed
                             :samples samples :max-bytes 16 :max-units 16
                             :runtime (System/getProperty "java.version")}
            :input (cases/write-cases
                    (into (cases/read-cases (:input base)) generated))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id path]
     :or {seed 41 samples 12 path "both"} :as options}]
   (when-not (#{"both" "direct" "translated"} path)
     (throw (ex-info "Path must be both, direct, or translated" {:path path})))
   (let [record (if replay (util/read-single-edn-string! (slurp replay))
                    (snapshot seed samples))
         record (if case-id
                  (let [rows (filterv #(= case-id (:case %))
                                      (cases/read-cases (:input record)))]
                    (when (empty? rows)
                      (throw (ex-info "Unknown charset case" {:case case-id})))
                    (assoc record :input (cases/write-cases rows)))
                  record)
         _ (micro/validate-snapshot! record)
         _ (compat/select-cases (:input record) {:family "charset"})
         parent (paths/resolve-path (paths/workspace-root)
                                    "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         directory (Files/createTempDirectory parent "charset-"
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
                 :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.charset-microprogram --replay "
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
