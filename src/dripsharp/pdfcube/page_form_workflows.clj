;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.page-form-workflows
  "Recorded page-manipulation/form workflows and semantic trace comparison."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util Base64 Random]))

(def generator-version 1)
(def templates [:split-and-merge :import-and-reorder :set-form-value-save-reopen])

(defn- ensure! [condition message]
  (when-not condition
    (throw (ex-info message {:kind :invalid-page-form-workflow}))))

(defn- identifier? [value]
  (and (string? value)
       (boolean (re-matches #"[a-z][a-z0-9-]{0,40}" value))))

(defn- text? [value]
  (and (string? value) (<= (count value) 1024)))

(defn validate!
  "Validates a bounded recorded workflow. Capability-dependent failures remain
  executable cases and are observations; validation only rejects malformed input."
  [{:keys [version template resources pages fields selection] :as workflow}]
  (ensure! (= generator-version version) "Unsupported workflow version")
  (ensure! (some #{template} templates) "Unknown workflow template")
  (ensure! (and (vector? resources) (<= 1 (count resources) 4)
                (vector? pages) (<= 2 (count pages) 6)
                (vector? fields) (= 2 (count fields))
                (vector? selection) (<= 1 (count selection) 6))
           "Workflow exceeds deterministic bounds")
  (doseq [items [resources pages fields]]
    (ensure! (and (every? #(identifier? (:id %)) items)
                  (= (count items) (count (set (map :id items)))))
             "Invalid or duplicate identifier"))
  (let [resource-ids (set (map :id resources))
        page-ids (set (map :id pages))
        field-types (set (map :type fields))]
    (ensure! (= #{:text :checkbox} field-types)
             "Exactly one text field and one checkbox are required")
    (doseq [{:keys [value]} resources]
      (ensure! (text? value) "Invalid resource value"))
    (doseq [{:keys [resource width height rotation content]} pages]
      (ensure! (and (resource-ids resource)
                    (integer? width) (<= 100 width 800)
                    (integer? height) (<= 100 height 800)
                    (#{0 90 180 270} rotation)
                    (text? content)
                    (every? #(<= 0 (int %) 127) content))
               "Invalid page model"))
    (doseq [{:keys [type page initial value]} fields]
      (ensure! (page-ids page) "Field references an absent page")
      (case type
        :text (ensure! (and (text? initial) (text? value)) "Invalid text field values")
        :checkbox (ensure! (and (boolean? initial) (boolean? value))
                           "Invalid checkbox values")))
    (ensure! (and (every? page-ids selection)
                  (= (count selection) (count (set selection))))
             "Selection must contain distinct existing pages"))
  workflow)

(defn generate [seed]
  (let [rng (Random. (long seed))
        template (nth templates (mod seed (count templates)))
        resource-count (+ 1 (.nextInt rng 2))
        page-count (+ 3 (.nextInt rng 2))
        resources (mapv (fn [index]
                          {:id (str "r" index)
                           :value (nth ["shared" "café-漢-😀" ""]
                                       (mod (+ seed index) 3))})
                        (range resource-count))
        pages (mapv (fn [index]
                      {:id (str "p" index)
                       :resource (:id (nth resources (mod index resource-count)))
                       :width (+ 200 (.nextInt rng 301))
                       :height (+ 200 (.nextInt rng 301))
                       :rotation (nth [0 90 180 270] (.nextInt rng 4))
                       :content (str "%page-p" index "-seed-" seed "\n")})
                    (range page-count))
        ids (mapv :id pages)
        selection (case template
                    :split-and-merge (vec (concat (subvec ids 1) [(first ids)]))
                    :import-and-reorder (vec (reverse (take (dec page-count) ids)))
                    ids)]
    (validate!
     {:version generator-version
      :seed seed
      :template template
      :resources resources
      :pages pages
      :fields [{:id "text-field" :type :text :page (first ids)
               :initial (str "before-" seed)
                :value (str "after café " seed)}
               {:id "check-field" :type :checkbox :page (second ids)
                :initial (.nextBoolean rng)
                :value (.nextBoolean rng)}]
      :selection selection})))

(defn read-record [file]
  (validate! (edn/read-string (slurp (str file)))))

(defn write-record! [file workflow]
  (spit (str file) (str (pr-str (validate! workflow)) "\n")))

(defn- encode [value]
  (.encodeToString (Base64/getEncoder) (.getBytes ^String value "UTF-8")))

(defn wire
  "Encodes one concrete input once for unchanged consumption by both probes."
  [workflow]
  (validate! workflow)
  (str/join
   "\n"
   (map #(str/join "\t" %)
        (concat
         [["model" generator-version (name (:template workflow))]]
         (for [{:keys [id value]} (:resources workflow)]
           ["resource" id (encode value)])
         (for [{:keys [id resource width height rotation content]} (:pages workflow)]
           ["page" id resource width height rotation (encode content)])
         (for [{:keys [id type page initial value]} (:fields workflow)]
           ["field" id (name type) page
            (if (= :text type) (encode initial) initial)
            (if (= :text type) (encode value) value)])
         [(into ["select"] (:selection workflow))]))))

(defn read-trace [file]
  (let [rows (mapv #(str/split % #"\t" -1)
                   (str/split-lines (slurp (str file))))]
    (when-not (and (seq rows)
                   (every? #(= 2 (count %)) rows)
                   (= (count rows) (count (set (map first rows)))))
      (throw (ex-info "Malformed page/form observation trace"
                      {:kind :invalid-page-form-observations
                       :path (str file)})))
    (into (sorted-map) rows)))

(defn difference [expected actual]
  (first
   (for [key (sort (distinct (concat (keys expected) (keys actual))))
         :when (not= (find expected key) (find actual key))]
     {:observation key
      :expected (get expected key ::missing)
      :actual (get actual key ::missing)})))

(defn expected-page-ids [{:keys [template pages selection]}]
  (if (= :set-form-value-save-reopen template)
    (mapv :id pages)
    selection))

(defn expected-observations [workflow]
  (let [pages (into {} (map (juxt :id identity) (:pages workflow)))
        page-observations
        (into
         {}
         (mapcat
          (fn [index id]
            (let [{:keys [width height rotation content]} (get pages id)
                  prefix (str "page/" index)
                  box (format "0.0,0.0,%d.0,%d.0" width height)
                  bytes (apply str
                               (map #(format "%02x" (bit-and 255 %))
                                    (.getBytes ^String content "US-ASCII")))]
              [[(str prefix "/id") id]
               [(str prefix "/media") box]
               [(str prefix "/crop") box]
               [(str prefix "/rotation") (str rotation)]
               [(str prefix "/content") bytes]]))
          (range) (expected-page-ids workflow)))
        form-observations
        (when (= :set-form-value-save-reopen (:template workflow))
          (let [fields (sort-by :id (:fields workflow))
                page-indices (zipmap (map :id (:pages workflow)) (range))]
            (into
             {"fields" "2"}
             (mapcat
              (fn [index {:keys [id type page value]}]
                (let [prefix (str "field/" index)
                      checkbox? (= :checkbox type)
                      rendered (if checkbox? (if value "Yes" "Off") value)]
                  [[(str prefix "/name") id]
                   [(str prefix "/type")
                    (if checkbox? "PDCheckBox" "PDTextField")]
                   [(str prefix "/value") rendered]
                   [(str prefix "/widgets") "1"]
                   [(str prefix "/widget/0/page") (str (page-indices page))]
                   [(str prefix "/widget/0/appearance")
                    (if checkbox? "subdictionary" "stream")]
                   [(str prefix "/widget/0/appearance-keys")
                    (if checkbox? "Off,Yes" "")]
                   [(str prefix "/widget/0/state")
                    (if checkbox? rendered "null")]]))
              (range) fields))))]
    (merge {"status" "ok"
            "template" (name (:template workflow))
            "pages" (str (count (expected-page-ids workflow)))}
           page-observations
           form-observations)))

(defn semantic-trace [trace]
  (dissoc trace "reader"))

(defn compare-traces [workflow traces]
  (let [expected (expected-observations workflow)]
    (or
     (some (fn [side]
             (when-let [mismatch
                        (difference expected
                                    (select-keys (get traces side)
                                                 (keys expected)))]
               (assoc mismatch :comparison [:model side])))
           [:java :dotnet])
     (some (fn [[left right]]
             (when-let [mismatch (difference (semantic-trace (get traces left))
                                             (semantic-trace (get traces right)))]
               (assoc mismatch :comparison [left right])))
           [[:java :dotnet]
            [:java-cross :dotnet-cross]
            [:java :dotnet-cross]
            [:dotnet :java-cross]]))))

(defn candidates [workflow]
  (let [selection (:selection workflow)
        simplify-field
        (fn [field]
          (case (:type field)
            :text (assoc field :initial "" :value "x")
            :checkbox (assoc field :initial false :value true)))]
    (filter
     (fn [candidate]
       (try (validate! candidate) true
            (catch Exception _ false)))
     (concat
      (when (< 1 (count selection))
        [(assoc workflow :selection (vec (butlast selection)))
         (assoc workflow :selection [(first selection)])])
      [(update workflow :fields #(mapv simplify-field %))]
      (for [index (range (count (:pages workflow)))]
        (update-in workflow [:pages index]
                   assoc :width 200 :height 200 :rotation 0
                   :content (str "%" (get-in workflow [:pages index :id]) "\n")))))))

(defn shrink!
  "Keeps the same first semantic difference under bounded valid reductions."
  [workflow failure evaluate! {:keys [max-attempts max-ms]
                               :or {max-attempts 12 max-ms 60000}}]
  (ensure! (and (nat-int? max-attempts) (<= max-attempts 100)
                (pos-int? max-ms) (<= max-ms 300000))
           "Invalid shrink budget")
  (let [signature #(select-keys % [:comparison :observation :expected :actual])
        deadline (+ (System/nanoTime) (* 1000000 max-ms))]
    (loop [best workflow
           remaining (seq (candidates workflow))
           attempts 0
           accepted 0]
      (let [remaining-ms (quot (- deadline (System/nanoTime)) 1000000)]
        (if (or (nil? remaining) (>= attempts max-attempts)
                (<= remaining-ms 0))
          {:workflow best :attempts attempts :accepted accepted}
          (let [candidate (first remaining)
                result (evaluate! candidate remaining-ms)
                same? (and (:observation result)
                           (= (signature failure) (signature result)))]
            (recur (if same? candidate best)
                   (if same? (seq (candidates candidate)) (next remaining))
                   (inc attempts)
                   (+ accepted (if same? 1 0)))))))))
