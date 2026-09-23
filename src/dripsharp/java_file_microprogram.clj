(ns dripsharp.java-file-microprogram
  "Bounded local JavaFile template inputs. Expected internal behavior comes
  from the original Java execution; native FileInfo assertions are separate."
  (:require [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(def batch-file "validation/microprograms/java-file/batch.edn")
(def default-seed 20260922)

(defn- token [path]
  (if (nil? path) "n:"
      (str "u:" (apply str (map #(format "%04x" (int %)) path)))))

(defn snapshot
  "Adds at most 32 exploratory pathnames, each through the complete fixed
  template batch. Generation happens once, outside either implementation."
  [root seed samples]
  (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                 (integer? samples) (<= 0 samples 32))
    (throw (ex-info "Use an Int64 seed and 0–32 exploratory samples" {:kind :invalid-generated-case})))
  (let [base (micro/read-batch root batch-file)
        regression (cases/read-cases (:input base))
        ordinary (filterv #(= "normal" (:case %)) regression)
        nulls (filterv #(= "null" (:case %)) regression)
        rng (Random. (long seed))
        alphabet "abXYZ09 .\t"
        paths (repeatedly samples
                          #(let [tail (apply str (repeatedly (inc (.nextInt rng 16))
                                                             (fn [] (nth alphabet (.nextInt rng (count alphabet))))))]
                             (case (.nextInt rng 7)
                               0 nil 1 "" 2 " \t " 3 (str tail "\u0000" tail)
                               4 (str "relative/" tail) 5 (str "../" tail) tail)))
        generated (mapcat (fn [index path]
                            (map (fn [row]
                                   (-> row
                                       (assoc :case (str "generated-" index))
                                       (assoc-in [:arguments 0] (token path))))
                                 (if (nil? path) nulls ordinary)))
                          (range) paths)]
    (assoc base :input (cases/write-cases (concat regression generated))
           :generator {:id :java-file-templates :version 1 :seed seed :samples samples
                       :prng "java.util.Random"})))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples output-directory]
     :or {seed default-seed samples 8 output-directory "validation-output/microprograms"}
     :as options}]
   (let [root (paths/workspace-root)
         parent (paths/resolve-path root output-directory)
         saved (snapshot root seed samples)
         _ (micro/validate-snapshot! saved)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         file (Files/createTempFile parent "java-file-input-" ".edn" (make-array FileAttribute 0))]
     (util/write-text! file (str (pr-str saved) "\n"))
     (micro/verify! (assoc (dissoc options :seed :samples) :replay (str file))))))

(defn -main [& arguments]
  (try
    (let [options (loop [remaining arguments options {}]
                    (if (empty? remaining)
                      options
                      (let [[flag value & more] remaining
                            key ({"--seed" :seed "--samples" :samples} flag)
                            parsed (when value (parse-long value))]
                        (when-not (and key parsed (not (contains? options key)))
                          (throw (ex-info "Usage: [--seed INT64] [--samples 0..32]" {})))
                        (recur more (assoc options key parsed)))))
          result (verify! options)]
      (prn result)
      (shutdown-agents)
      (when-not (= :passed (:status result)) (System/exit 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :data (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
