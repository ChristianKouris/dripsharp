(ns dripsharp.differential-generation
  "Process-free, bounded generation and reduction of recorded operation cases.
  Concrete tokens, stable step IDs and explicit handles survive PRNG changes."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.util :as util])
  (:import [java.util Random]))

(def generator-identity {:id :java-compat-operations :version 1})
(def default-limits {:max-operations 32 :max-value-units 32 :max-input-bytes 65536})

(defn- require! [condition message]
  (when-not condition (throw (ex-info message {:kind :invalid-generated-case}))))

(defn- limits! [limits]
  (require! (and (every? pos-int? (map limits (keys default-limits)))
                 (<= (:max-operations limits) 1000)
                 (<= (:max-value-units limits) 1024)
                 (<= (:max-input-bytes limits) 1048576))
            "Invalid generation limits")
  limits)

(defn- pick [^Random rng values] (nth values (.nextInt rng (count values))))
(defn- biased [^Random rng boundaries random-value]
  (if (< (.nextInt rng 4) 3) (pick rng boundaries) (random-value)))

(defn value
  "Boundary-biased exact wire token. Path values are Java pathname strings,
  including empty/NUL/whitespace, not public FileInfo adaptations. No filesystem
  calls or expectations are inferred from them."
  [^Random rng kind max-units]
  (require! (and (pos-int? max-units) (<= max-units 1024)) "Invalid value limit")
  (let [length #(biased rng [0 1 (min 4 max-units) max-units]
                        (fn [] (.nextInt rng (inc max-units))))]
    (case kind
      :integer (str "i:" (biased rng [Long/MIN_VALUE Long/MAX_VALUE -2147483648
                                      2147483647 -1 0 1 9007199254740993]
                                 #(.nextLong rng)))
      :bytes (str "b:" (apply str (repeatedly (length)
                                              #(format "%02x" (biased rng [0 1 127 128 255]
                                                                      (fn [] (.nextInt rng 256)))))))
      :string (str "u:" (apply str (repeatedly (length)
                                               #(format "%04x" (biased rng [0 32 47 92 127 255 55296 56320 65535]
                                                                       (fn [] (.nextInt rng 65536)))))))
      :path (let [path (pick rng ["" " " "." ".." "/" "a/b" "a\\b" " a " "a\u0000b" "//a//b"])]
              (str "u:" (apply str (map #(format "%04x" (int %)) (take max-units path)))))
      (throw (ex-info "Unknown value generator" {:kind :invalid-generator :value-kind kind})))))

(defn- transition
  "Small input-domain model only: construction, cursor bounds, exception intent.
  Never computes the observed byte/int/hash result used by the differential."
  [state {:keys [family operation arguments step]}]
  (if (= family "byte-buffer")
    (case operation
      "wrap" (if (= "n:" (first arguments))
               ["null-pointer" state]
               ["none" {:size (quot (- (count (first arguments)) 2) 2) :position 0 :creator step}])
      (let [width (case operation "get" 1 "get-int" 4)]
        (cond
          (nil? state) ["null-pointer" state]
          (> (+ (:position state) width) (:size state)) ["buffer-underflow" state]
          :else ["none" (update state :position + width)])))
    [(if (= "n:" (first arguments)) "null-pointer" "none") state]))

(defn generate
  "Produces one concrete case once; replay consumes :operations, not this fn.
  Native (family, case) runner state keys carry the explicit :handle identity."
  ([seed] (generate seed {}))
  ([seed options]
   (let [limits (limits! (merge default-limits options))
         rng (Random. (long seed))
         ;; Mix the first draw: adjacent java.util.Random seeds correlate in low bits.
         n (inc (int (mod (hash (.nextLong rng)) (:max-operations limits))))
         operations
         (loop [i 0 states {} rows []]
           (if (= i n)
             rows
             (let [family (pick rng ["byte-buffer" "byte-buffer" "string" "long"])
                   handle (str "h" (.nextInt rng 3))
                   key [family handle]
                   state (get states key)
                   operation (if (= family "byte-buffer")
                               (pick rng (if state ["wrap" "get" "get" "get-int"]
                                             ["wrap" "wrap" "get"])) "hash")
                   kind (case family "byte-buffer" :bytes "long" :integer
                              (pick rng [:string :path]))
                   arguments (if (contains? #{"get" "get-int"} operation) []
                                 [(if (and (not= family "long") (zero? (.nextInt rng 8)))
                                    "n:" (value rng kind (:max-value-units limits)))])
                   row {:family family :case handle :handle handle :step (str i)
                        :operation operation :arguments arguments
                        :requires (if (and state (contains? #{"get" "get-int"} operation))
                                    [(:creator state)] [])}
                   [exception next-state] (transition state row)]
               (recur (inc i) (assoc states key next-state)
                      (conj rows (assoc row :expected-exception exception
                                        :intent (if (= exception "none") :valid :invalid)))))))]
     {:schema-version 1 :generator generator-identity :seed seed
      :generator-runtime {:clojure (clojure-version) :prng "java.util.Random"
                          :java (System/getProperty "java.runtime.version")}
      :limits limits :operations operations})))

(defn validate!
  "Validates bounded records and construction/use relationships. Shrinking must
  preserve each surviving operation's original valid/invalid exception intent."
  [record]
  (let [{:keys [operations limits]} record]
    (require! (and (= 1 (:schema-version record)) (map? (:generator record))
                   (keyword? (get-in record [:generator :id]))
                   (pos-int? (get-in record [:generator :version])) (integer? (:seed record)))
              "Invalid recorded generator identity")
    (limits! limits)
    (require! (and (vector? operations) (seq operations)
                   (<= (count operations) (:max-operations limits))) "Invalid operation count")
    (require! (<= (count (.getBytes (pr-str record) "UTF-8")) (:max-input-bytes limits))
              "Recorded input exceeds byte limit")
    (require! (= (cases/read-cases (cases/write-cases operations))
                 (mapv #(select-keys % (conj cases/input-keys :arguments)) operations))
              "Recorded operation fields must match wire values exactly")
    (reduce
     (fn [{:keys [states seen] :as acc} row]
       (let [{:keys [family operation arguments handle step requires expected-exception intent]} row
             state (get states [family handle])
             byte? (= family "byte-buffer")
             read? (contains? #{"get" "get-int"} operation)
             token (first arguments)]
         (require! (and (= handle (:case row)) (string? handle)
                        (not (contains? seen step)) (vector? requires)
                        (= requires (if (and byte? read? state) [(:creator state)] []))
                        (every? seen requires)) "Invalid handle or construction dependency")
         (require! (case family
                     "byte-buffer" (if read? (empty? arguments)
                                       (and (= operation "wrap") (= 1 (count arguments))
                                            (or (= token "n:") (str/starts-with? token "b:"))))
                     "string" (and (= operation "hash") (= 1 (count arguments))
                                   (or (= token "n:") (str/starts-with? token "u:")))
                     "long" (and (= operation "hash") (= 1 (count arguments))
                                 (str/starts-with? token "i:")
                                 (<= Long/MIN_VALUE (bigint (subs token 2)) Long/MAX_VALUE))
                     false) "Invalid operation domain")
         (doseq [arg arguments]
           (when (contains? #{\b \u} (first arg))
             (require! (<= (quot (- (count arg) 2) (if (= \b (first arg)) 2 4))
                           (:max-value-units limits)) "Value exceeds unit limit")))
         (let [[exception next-state] (transition state row)]
           (require! (and (= expected-exception exception)
                          (= intent (if (= exception "none") :valid :invalid)))
                     "Candidate changes operation exception intent")
           (-> acc (assoc-in [:states [family handle]] next-state) (update :seen conj step)))))
     {:states {} :seen #{}} operations)
    record))

(defn write-record! [file record]
  (validate! record)
  (spit (str file) (str (pr-str record) "\n"))
  record)

(defn read-record [file]
  (require! (<= (.length (java.io.File. (str file))) 1048576) "Record exceeds 1 MiB")
  (validate! (util/read-single-edn-string! (slurp (str file)))))

(defn replay!
  "Executes persisted input without consulting its generator or seed."
  [file evaluate!]
  (evaluate! (read-record file)))

(defn failure-signature
  "Only semantic differences qualify. Stable identity, exception categories and
  differing fields prevent a new exception or harness failure replacing a bug."
  [result]
  (let [{:keys [kind identity expected-exception oracle probe]} (:first-difference result)]
    (when (and (= :failed (:status result)) (= :semantic-difference kind)
               (map? identity) (map? oracle) (map? probe))
      {:identity identity :expected-exception expected-exception
       :exceptions [(:exception oracle) (:exception probe)]
       :fields (filterv #(not= (get oracle %) (get probe %)) [:exception :result :state])})))

(defn- units [token]
  (let [width (if (str/starts-with? token "b:") 2 4)]
    (mapv #(apply str %) (partition width (subs token 2)))))

(defn- token-cost [token]
  (case (first token)
    \i [(count token) (abs (bigint (subs token 2)))]
    (\b \u) [(count token) (reduce + 0 (map #(Long/parseLong % 16) (units token)))]
    [0 0]))

(defn- complexity [record]
  (let [costs (map token-cost (mapcat :arguments (:operations record)))]
    [(count (:operations record)) (reduce + 0 (map first costs))
     (reduce + 0 (map second costs))
     (count (filter #(= "get-int" (:operation %)) (:operations record)))]))

(defn- smaller-tokens [token]
  (let [prefix (subs token 0 2)]
    (case (first token)
      \i (let [n (bigint (subs token 2))]
           (map #(str "i:" %) (distinct [0 (if (neg? n) -1 1) (quot n 2)])))
      (\b \u)
      (let [parts (units token) n (count parts) zero (if (= prefix "b:") "00" "0000")]
        (map #(str prefix (apply str %))
             (concat [[] (subvec parts 0 (quot n 2)) (subvec parts (quot n 2))]
                     (for [i (range n)] (vec (concat (subvec parts 0 i) (subvec parts (inc i)))))
                     (for [i (range n)] (assoc parts i zero)))))
      [])))

(defn candidates
  "Lazy reductions. Removing a constructor also removes its explicit users;
  domain validation subsequently rejects altered cursor/exception preconditions."
  [record]
  (let [ops (:operations record) n (count ops)
        removals
        (for [width (distinct (take-while pos? (iterate #(quot % 2) n)))
              start (range 0 n width)]
          (let [removed (set (map :step (subvec ops start (min n (+ start width)))))
                kept (reduce (fn [{:keys [gone rows]} op]
                               (if (or (gone (:step op)) (some gone (:requires op)))
                                 {:gone (conj gone (:step op)) :rows rows}
                                 {:gone gone :rows (conj rows op)}))
                             {:gone removed :rows []} ops)]
            (assoc record :operations (:rows kept))))
        values (for [i (range n) j (range (count (:arguments (nth ops i))))
                     token (smaller-tokens (get-in ops [i :arguments j]))]
                 (assoc-in record [:operations i :arguments j] token))
        simpler (for [i (range n) :when (= "get-int" (:operation (nth ops i)))]
                  (assoc-in record [:operations i :operation] "get"))]
    (concat removals values simpler)))

(def empty-counts {:generated 0 :executed 0 :rejected 0 :failed 0 :timed-out 0 :passed 0 :harness-failed 0})

(defn count-result [counts result]
  (cond
    (= :passed (:status result)) (-> counts (update :executed inc) (update :passed inc))
    (failure-signature result) (-> counts (update :executed inc) (update :failed inc))
    (or (= :timeout (get-in result [:first-difference :kind]))
        (= :timeout (get-in result [:first-difference :execution :status])))
    (update counts :timed-out inc)
    :else (update counts :harness-failed inc)))

(defn evaluate
  "Ordinary evaluator exceptions are harness failures, never semantic failures."
  [evaluate! record]
  (try (evaluate! record)
       (catch Exception error
         {:status :failed :first-difference {:kind :evaluator-error
                                             :message (.getMessage error)}})))

(defn shrink!
  "Greedy bounded reducer. evaluate! receives (record remaining-ms); live
  callbacks MUST cap every process timeout to remaining-ms and their cumulative
  process budget. No futures/background callbacks are left running. Pure tests
  may use a constant-time predicate. Attempts count invalid candidates too."
  [record original evaluate! {:keys [max-attempts max-ms now-ns]
                              :or {max-attempts 100 max-ms 10000 now-ns #(System/nanoTime)}}]
  (validate! record)
  (require! (and (nat-int? max-attempts) (pos-int? max-ms)) "Invalid shrink bounds")
  (let [signature (failure-signature original)
        _ (require! signature "Cannot shrink a non-semantic failure")
        deadline (+ (now-ns) (* 1000000 max-ms))]
    (loop [best record result original todo (seq (candidates record))
           attempts 0 accepted 0 counts empty-counts]
      (let [remaining (quot (- deadline (now-ns)) 1000000)
            reason (cond (>= attempts max-attempts) :attempt-limit
                         (<= remaining 0) :time-limit (nil? todo) :exhausted)]
        (if reason
          {:record best :result result :signature signature :attempts attempts
           :accepted accepted :stop reason :counts counts}
          (let [candidate (first todo)
                counts (update counts :generated inc)
                valid? (and (neg? (compare (complexity candidate) (complexity best)))
                            (try (validate! candidate) true (catch Exception _ false)))]
            (if-not valid?
              (recur best result (next todo) (inc attempts) accepted (update counts :rejected inc))
              (let [actual (evaluate #(evaluate! % remaining) candidate)
                    same? (= signature (failure-signature actual))]
                (recur (if same? candidate best) (if same? actual result)
                       (if same? (seq (candidates candidate)) (next todo))
                       (inc attempts) (+ accepted (if same? 1 0)) (count-result counts actual))))))))))
