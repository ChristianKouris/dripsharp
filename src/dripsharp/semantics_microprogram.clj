(ns dripsharp.semantics-microprogram
  "Bounded language-semantic templates, run explicitly and locally."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(def templates
  ;; These are input domains, not expected values/event traces. JVM execution
  ;; supplies every expectation. All branches have a fixed regression input.
  [{:method "order" :inputs [[1 2] [-1 0] [2147483647 1]]}
   {:method "shortCircuit" :inputs [[0 0] [0 1] [1 0] [1 1]]}
   {:method "overloads" :type "BoxedOverload" :inputs [[0 0] [128 0]]}
   {:method "unbox" :inputs [[0 0] [1 -2147483648] [1 2147483647]]}
   {:method "cast" :inputs [[0 0] [1 0] [2 0]]}
   {:method "conversion" :inputs [[0 128] [65535 255] [55296 -129] [2147483647 1]]}
   {:method "dispatch" :inputs [[0 0]]}
   {:method "bridge" :inputs [[0 0]]}
   {:method "control" :inputs [[0 7] [1 7] [2 7]]}
   {:method "cleanup" :inputs [[0 9] [1 9] [2 9] [3 9]]}
   {:method "initialization" :type "InitializationOrder" :inputs [[0 0] [1 0]]}])

(defn snapshot
  ([] (snapshot 19 12))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 24))
     (throw (ex-info "Require signed 64-bit seed and 0..24 samples" {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) micro/default-batch)
         sources (into (sorted-map)
                       (for [type ["SemanticsCases" "BoxedOverload" "InitializationOrder"]
                             :let [path (str "subjects/micro/" type ".java")]]
                         [path (slurp (str (paths/resolve-path (paths/workspace-root)
                                                             "validation/microprograms/semantics" path)))]))
         rng (Random. (long seed))
         row (fn [method id [x y]]
               {:family "semantics" :case id :step "0" :operation method
                :expected-exception (cond (and (= method "unbox") (zero? x)) "null-pointer"
                                          (and (= method "cast") (= x 2)) "class-cast"
                                          :else "none")
                :arguments [(str "i:" x) (str "i:" y)]})
         fixed (mapcat (fn [{:keys [method inputs]}]
                         (map-indexed #(row method (str method "-" %1) %2) inputs)) templates)
         generated (for [index (range samples)
                         :let [{:keys [method inputs]} (nth templates (.nextInt rng (count templates)))
                               [x y] (nth inputs (.nextInt rng (count inputs)))
                               ;; Branch selectors remain in their finite domains. Value
                               ;; operands may span int boundaries without changing validity.
                               y (if (#{"order" "overloads" "unbox" "conversion" "control" "cleanup"} method)
                                   (.nextInt rng) y)]]
                     (row method (str "seed-" seed "-" index) [x y]))]
     (assoc base :id "semantics" :generator {:id :semantics-templates :version 1 :seed seed :samples samples}
            :isolate-cases? true
            :sources sources
            :operations (into (sorted-map)
                              (for [{:keys [method type] :or {type "SemanticsCases"}} templates]
                                [method {:java-type (str "micro." type) :java-method method
                                         :dotnet-type (str "DripSharp.Microprograms." type)
                                         :dotnet-method (str (str/upper-case (subs method 0 1)) (subs method 1))
                                         :java-state "state" :dotnet-state "State"
                                         :parameters ["int" "int"]}]))
            :input (cases/write-cases (vec (concat fixed generated)))))))

(defn select-case
  "Retain exact recorded input and its independent source template for replay."
  [record case-id]
  (let [rows (filterv #(= case-id (:case %)) (cases/read-cases (:input record)))
        operations (select-keys (:operations record) (map :operation rows))
        sources (set (map #(str "subjects/" (str/replace (:java-type %) "." "/") ".java")
                          (vals operations)))]
    (when (empty? rows) (throw (ex-info "Unknown semantics case" {:case case-id})))
    (assoc record :input (cases/write-cases rows) :operations operations
           :sources (select-keys (:sources record) sources))))

(defn text-value [token]
  (when (and (string? token) (str/starts-with? token "u:"))
    (apply str (map #(char (Integer/parseInt (apply str %) 16)) (partition 4 (subs token 2))))))

(defn first-trace-difference [result]
  (let [{:keys [kind identity oracle probe]} (:first-difference result)]
    (when (= :semantic-difference kind)
      (let [left (some-> (:state oracle) text-value (str/split #";" -1))
            right (some-> (:state probe) text-value (str/split #";" -1))]
        (first (keep (fn [index]
                       (when (not= (get left index) (get right index))
                         {:identity identity :index index :oracle (get left index) :probe (get right index)}))
                     (range (max (count left) (count right)))))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id] :or {seed 19 samples 12} :as options}]
   (let [record (if replay (util/read-single-edn-string! (slurp replay)) (snapshot seed samples))
         record (if case-id (select-case record case-id) record)
         _ (micro/validate-snapshot! record)
         parent (paths/resolve-path (paths/workspace-root) "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         saved (Files/createTempFile parent "semantics-" ".edn" (make-array FileAttribute 0))]
     (spit (str saved) (pr-str record))
     (let [result (micro/verify! (assoc (dissoc options :seed :samples :case-id) :replay (str saved)))
           result (assoc result :first-trace-difference (first-trace-difference result))]
       (spit (str (paths/resolve-path (:directory result) "summary.edn")) (pr-str result))
       result))))

(defn -main [& arguments]
  (try
    (let [options (loop [args arguments result {}]
                    (if (empty? args) result
                        (let [[flag value & more] args
                              key ({"--seed" :seed "--samples" :samples "--replay" :replay "--case" :case-id} flag)]
                          (when-not (and key value (not (contains? result key)))
                            (throw (ex-info "Usage: [--seed N --samples 0..24 | --replay SNAPSHOT] [--case ID]" {})))
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
