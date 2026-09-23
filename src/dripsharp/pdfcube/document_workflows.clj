;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.document-workflows
  "Recorded lifecycle models, semantic observations and prerequisite-aware reduction."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util Base64 Random]))

(def generator-version 1)
(defn- ensure! [condition message]
  (when-not condition (throw (ex-info message {:kind :invalid-workflow}))))
(defn- identifier? [s] (and (string? s) (boolean (re-matches #"[a-z][a-z0-9-]{0,40}" s))))
(defn- text? [s] (and (string? s) (<= (count s) 1024)))

(defn validate!
  "Valid cases obey save/close/load and reference prerequisites. The sole invalid
  operation is explicitly labeled and requires a new document without a source."
  [{:keys [version compression title nested resources pages operations] :as workflow}]
  (ensure! (= generator-version version) "Unsupported workflow version")
  (ensure! (and (#{:none :default} compression) (text? title) (boolean? nested)) "Invalid model")
  (ensure! (and (vector? resources) (<= 1 (count resources) 8)
                (vector? pages) (<= 1 (count pages) 8)
                (vector? operations) (<= 1 (count operations) 64)) "Workflow exceeds bounds")
  (doseq [items [resources pages operations]]
    (ensure! (and (every? #(identifier? (:id %)) items)
                  (= (count items) (count (set (map :id items))))) "Invalid or duplicate identifier"))
  (let [rs (set (map :id resources)) ps (set (map :id pages))]
    (doseq [r resources] (ensure! (text? (:value r)) "Invalid resource text"))
    (doseq [{:keys [resource width height content compressed]} pages]
      (ensure! (and (rs resource) (integer? width) (<= 100 width 800)
                    (integer? height) (<= 100 height 800) (boolean? compressed)
                    (text? content) (every? #(<= 0 (int %) 127) content)) "Invalid page reference or content"))
    (reduce
     (fn [{:keys [open source saved] :as state} {:keys [op target value]}]
       (case op
         :load (do (ensure! (and (not open) saved) "Load requires closed document and saved file")
                   {:open true :source true :saved true})
         :close (do (ensure! open "Close requires open document") (assoc state :open false))
         :full (do (ensure! open "Save requires open document") (assoc state :saved true :source false))
         :incremental (do (ensure! (and open source) "Incremental save requires loaded source")
                          ;; A second incremental write must load the latest revision first.
                          (assoc state :source false :saved true))
         :invalid-incremental (do (ensure! (and open (not source) (not saved)) "Invalid case must use a new document") state)
         :title (do (ensure! (and open (text? value)) "Invalid title edit") state)
         :shared (do (ensure! (and open (rs target) (some #(= target (:resource %)) pages) (text? value)) "Invalid shared edit") state)
         :rotate (do (ensure! (and open (ps target) (#{0 90 180 270} value)) "Invalid rotation") state)
         (ensure! false "Unknown operation")))
     {:open true :source false :saved false} operations)
    (ensure! (some #(#{:full :incremental} (:op %)) operations) "Workflow has no saved observation"))
  workflow)

(defn generate [seed]
  (let [rng (Random. (long seed))
        resource-count (inc (.nextInt rng 3))
        page-count (+ 2 (.nextInt rng 4))
        pick #(nth % (.nextInt rng (count %)))
        resources (mapv #(hash-map :id (str "r" %) :value (pick ["" "shared" "café-漢-😀"])) (range resource-count))
        pages (mapv (fn [i] {:id (str "p" i) :resource (:id (nth resources (mod i resource-count)))
                             :width (+ 200 (.nextInt rng 301)) :height (+ 200 (.nextInt rng 301))
                             :compressed (.nextBoolean rng)
                             :content (str "q\n0 0 m\n10 10 l S\nQ\nBT /F1 12 Tf 20 60 Td (Page " i " seed " seed ") Tj ET\n")})
                    (range page-count))
        cycle-ops (fn [prefix save]
                    (mapv (fn [suffix op] {:id (str prefix suffix) :op op})
                          ["-save" "-close" "-load"] [save :close :load]))]
    (validate!
     {:version generator-version :seed seed :compression (pick [:none :default])
      :title (pick ["" "Lifecycle" "café 漢 😀"]) :nested (.nextBoolean rng)
      :resources resources :pages pages
      :operations (vec (concat [{:id "invalid-new" :op :invalid-incremental}]
                               (cycle-ops "initial" :full)
                               (cycle-ops "noop" :full)
                               [{:id "shared-edit" :op :shared :target "r0" :value "updated 漢 😀"}
                                {:id "title-edit" :op :title :value (str "revision " seed " café")}
                                {:id "rotate-edit" :op :rotate :target "p0" :value (pick [90 180 270])}]
                               (cycle-ops "incremental" :incremental)
                               (when (.nextBoolean rng)
                                 (concat [{:id "title-again" :op :title :value "second update"}]
                                         (cycle-ops "second" (pick [:full :incremental]))))))})))

(defn read-record [file] (validate! (edn/read-string (slurp (str file)))))
(defn write-record! [file workflow] (spit (str file) (str (pr-str (validate! workflow)) "\n")))
(defn- encode [s] (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8")))
(defn wire
  "One concrete UTF-8/base64 input, shared unchanged by both executable probes."
  [workflow]
  (validate! workflow)
  (str/join "\n"
            (map #(str/join "\t" %)
                 (concat [["model" (name (:compression workflow)) (encode (:title workflow)) (:nested workflow)]]
                         (for [{:keys [id value]} (:resources workflow)] ["resource" id (encode value)])
                         (for [{:keys [id resource width height content compressed]} (:pages workflow)]
                           ["page" id resource width height (encode content) compressed])
                         (for [{:keys [id op target value]} (:operations workflow)]
                           (into ["op" id (name op)]
                                 (case op :title [(encode value)] :shared [target (encode value)]
                                       :rotate [target value] [])))))))

(defn read-trace [file]
  (let [rows (mapv #(str/split % #"\t" -1) (str/split-lines (slurp (str file))))]
    (when-not (and (seq rows) (every? #(= 2 (count %)) rows)
                   (= (count rows) (count (set (map first rows)))))
      (throw (ex-info "Malformed observation trace" {:kind :invalid-observations :path (str file)})))
    (into (sorted-map) rows)))

(defn difference [expected actual]
  (first (for [k (sort (distinct (concat (keys expected) (keys actual))))
               :when (not= (find expected k) (find actual k))]
           {:observation k :expected (get expected k ::missing) :actual (get actual k ::missing)})))

(defn semantic-trace [trace]
  (into (sorted-map) (remove (fn [[k _]] (some #(str/ends-with? k %) ["/closed" "/exception" "/written" "/prefix-preserved"])) trace)))

(defn utf16 [s] (str "u:" (apply str (map #(format "%04x" (int %)) s))))
(defn- checkpoint [workflow id state]
  (into {(str id "/title") (utf16 (:title state))
         (str id "/subject") "u:"
         (str id "/pages") (str (count (:pages workflow)))}
        (mapcat
         (fn [i p]
           (let [prefix (str id "/page/" i)
                 alias (first (keep-indexed (fn [j other] (when (= (:resource p) (:resource other)) j)) (:pages workflow)))]
             [[(str prefix "/id") (utf16 (:id p))]
              [(str prefix "/rotation") (str (get-in state [:rotations (:id p)] 0))]
              [(str prefix "/resource-alias") (str alias)]
              [(str prefix "/resource-indirect") "true"]
              [(str prefix "/resource-value") (utf16 (get-in state [:resources (:resource p)]))]]))
         (range) (:pages workflow))))

(defn expected-checkpoints
  "Small independent model catches lost edits even if both implementations agree.
  A load restores the last saved state, including when shrinking discards edits."
  [workflow]
  (:expected
   (reduce
    (fn [state {:keys [id op target value]}]
      (case op
        :title (assoc state :title value)
        :shared (assoc-in state [:resources target] value)
        :rotate (assoc-in state [:rotations target] value)
        :load (merge state (:snapshot state))
        :invalid-incremental (update state :expected merge {(str id "/exception") "state" (str id "/written") "0"})
        :close (assoc-in state [:expected (str id "/closed")] "true")
        (:full :incremental)
        (-> state
            (update :expected merge (checkpoint workflow id state)
                    (when (= :incremental op) {(str id "/prefix-preserved") "true"}))
            (assoc :snapshot (select-keys state [:title :resources :rotations])))
        state))
    {:title (:title workflow)
     :resources (into {} (map (juxt :id :value) (:resources workflow)))
     :rotations {} :expected {}}
    (:operations workflow))))

(defn compare-traces [workflow traces]
  (or (some (fn [side]
              (when-let [d (difference (expected-checkpoints workflow)
                                       (select-keys (get traces side) (keys (expected-checkpoints workflow))))]
                (assoc d :comparison [:model side]))) [:java :dotnet])
      (some (fn [[a b]] (when-let [d (difference (get traces a) (get traces b))]
                          (assoc d :comparison [a b]))) [[:java :dotnet] [:java-cross :dotnet-cross]])
      (some (fn [[own cross]] (when-let [d (difference (semantic-trace (get traces own)) (get traces cross))]
                                (assoc d :comparison [own cross]))) [[:java :dotnet-cross] [:dotnet :java-cross]])))

(defn candidates [workflow]
  (let [ops (:operations workflow) n (count ops)
        remove-pages (fn [gone]
                       (-> workflow
                           (update :pages #(vec (remove (comp gone :id) %)))
                           (update :operations #(vec (remove (fn [op] (and (= :rotate (:op op)) (gone (:target op)))) %)))))]
    (filter (fn [candidate] (try (validate! candidate) true (catch Exception _ false)))
            (concat
             (for [width (distinct (take-while pos? (iterate #(quot % 2) n))) start (range 0 n width)]
               (assoc workflow :operations (vec (concat (subvec ops 0 start) (subvec ops (min n (+ start width)))))))
             (for [p (:pages workflow)] (remove-pages #{(:id p)}))
             (for [r (:resources workflow)]
               (-> (remove-pages (set (map :id (filter #(= (:id r) (:resource %)) (:pages workflow)))))
                   (update :resources #(vec (remove (fn [x] (= (:id r) (:id x))) %)))
                   (update :operations #(vec (remove (fn [x] (and (= :shared (:op x)) (= (:id r) (:target x)))) %)))))))))

(defn shrink!
  "Keep the same comparison/observation failure under bounded reductions.
  evaluate! receives a candidate and remaining milliseconds; process failures
  never count as preserving a semantic failure."
  [workflow failure evaluate! {:keys [max-attempts max-ms] :or {max-attempts 20 max-ms 60000}}]
  (ensure! (and (nat-int? max-attempts) (<= max-attempts 100) (pos-int? max-ms) (<= max-ms 300000)) "Invalid shrink budget")
  (let [signature #(select-keys % [:comparison :observation :expected :actual])
        deadline (+ (System/nanoTime) (* 1000000 max-ms))]
    (loop [best workflow todo (seq (candidates workflow)) attempts 0 accepted 0]
      (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
        (if (or (nil? todo) (>= attempts max-attempts) (<= remaining 0))
          {:workflow best :attempts attempts :accepted accepted}
          (let [candidate (first todo)
                result (evaluate! candidate remaining)
                same? (and (:observation result) (= (signature failure) (signature result)))]
            (recur (if same? candidate best) (if same? (seq (candidates candidate)) (next todo))
                   (inc attempts) (+ accepted (if same? 1 0)))))))))
