(ns dripsharp.java-io-microprogram
  "Explicit local translated stream and resource-cleanup differentials."
  (:require [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def batch-file "validation/microprograms/java-io/batch.edn")

(defn select-snapshot
  "Selects an independently executable operation prefix from an authored or
  recorded snapshot. Filtering never regenerates the recorded input."
  [snapshot case-id step]
  (when (and step (nil? case-id))
    (throw (ex-info "--step requires --case" {})))
  (let [rows (cases/read-cases (:input snapshot))
        selected (filterv #(and (or (nil? case-id) (= case-id (:case %)))
                                (or (nil? step) (= (str step) (:step %)))) rows)]
    (when (empty? selected)
      (throw (ex-info "No matching I/O operations" {:case case-id :step step})))
    (assoc snapshot :input (cases/write-cases selected))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [case-id step replay output-directory]
     :or {output-directory "validation-output/microprograms"}
     :as options}]
   (let [root (paths/workspace-root)
         snapshot (if replay (util/read-single-edn-string! (slurp replay))
                      (micro/read-batch root batch-file))
         selected (select-snapshot snapshot case-id step)
         _ (micro/validate-snapshot! selected)
         parent (paths/resolve-path root output-directory)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         file (Files/createTempFile parent "java-io-input-" ".edn" (make-array FileAttribute 0))]
     (util/write-text! file (str (pr-str selected) "\n"))
     (micro/verify! (assoc (dissoc options :case-id :step) :replay (str file))))))

(defn -main [& arguments]
  (try
    (let [options (loop [remaining arguments options {}]
                    (if (empty? remaining) options
                        (let [[flag value & more] remaining
                              key ({"--case" :case-id "--step" :step "--replay" :replay} flag)]
                          (when-not (and key value (not (contains? options key)))
                            (throw (ex-info "Usage: [--case ID [--step N]] [--replay SNAPSHOT]" {})))
                          (recur more (assoc options key value)))))
          result (verify! options)]
      (prn result)
      (shutdown-agents)
      (when-not (= :passed (:status result)) (System/exit 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :data (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
