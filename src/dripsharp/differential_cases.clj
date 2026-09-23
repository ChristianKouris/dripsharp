(ns dripsharp.differential-cases
  "Exact, externally recorded inputs and operation-level differential results.
  This namespace is process-free; live runners opt in explicitly."
  (:require [clojure.string :as str]))

(def input-header "DRIPSHARP_DIFFERENTIAL_CASES_V1")
(def output-header "DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1")
(def identity-keys [:family :case :step :operation])
(def input-keys (conj identity-keys :expected-exception))
(def output-keys (into identity-keys [:exception :result :state]))

(defn- invalid! [kind message data]
  (throw (ex-info message (assoc data :kind kind))))

(defn value-token?
  "Canonical ASCII tokens preserve null, empty bytes/strings, arbitrary integers,
  and even unpaired UTF-16 surrogates. Operation contracts can narrow domains."
  [value]
  (boolean
   (and (string? value)
        (or (= "n:" value)
            (re-matches #"b:(?:[0-9a-f]{2})*" value)
            (re-matches #"u:(?:[0-9a-f]{4})*" value)
            (re-matches #"i:(?:0|-?[1-9][0-9]*)" value)))))

(defn read-cases
  "Parses the shared TSV wire format, rejecting ambiguous identities."
  [text]
  (let [[header & lines] (str/split-lines text)
        rows (mapv (fn [line]
                     (let [fields (str/split line #"\t" -1)]
                       (when-not (and (<= 5 (count fields))
                                      (every? #(re-matches #"[A-Za-z0-9_.-]+" %)
                                              (take 5 fields))
                                      (every? value-token? (drop 5 fields)))
                         (invalid! :invalid-case "Malformed case row" {:line line}))
                       (assoc (zipmap input-keys fields)
                              :arguments (vec (drop 5 fields)))))
                   lines)
        ids (map #(select-keys % [:family :case :step]) rows)]
    (when-not (and (= input-header header) (seq rows)
                   (= (count ids) (count (set ids))))
      (invalid! :invalid-case "Invalid case header, empty input, or duplicate step"
                {:header header :identities ids}))
    rows))

(defn write-cases [rows]
  (str (str/join "\n"
                 (cons input-header
                       (map #(str/join "\t" (concat (map % input-keys)
                                                    (:arguments %))) rows)))
       "\n"))

(defn read-observations [text]
  (let [[header & lines] (str/split-lines text)]
    (when-not (= output-header header)
      (invalid! :invalid-observation "Missing or invalid observation header"
                {:header header}))
    (mapv (fn [line]
            (let [fields (str/split line #"\t" -1)]
              (when-not (and (= 7 (count fields))
                             (every? #(re-matches #"[A-Za-z0-9_.-]+" %)
                                     (take 5 fields))
                             (every? value-token? (drop 5 fields)))
                (invalid! :invalid-observation "Malformed observation" {:line line}))
              (zipmap output-keys fields)))
          lines)))

(defn process-outcome
  "Runs an injected process function, preserving build/start/exit/timeout errors
  separately from observed Java exceptions. Does not launch anything itself."
  [run-command! phase command]
  (try
    (let [result (run-command! command)]
      (if (= 0 (:exit result))
        {:status :ok :phase phase :process result}
        {:status :process-failure :phase phase :process result}))
    (catch clojure.lang.ExceptionInfo error
      {:status (if (= :command-timeout (:kind (ex-data error)))
                 :timeout :process-failure)
       :phase phase :process (ex-data error)})))

(defn- checked-observations [rows side execution]
  (if-not (= :ok (:status execution))
    {:failure {:kind (:status execution) :side side
               :phase (:phase execution) :process (:process execution)}}
    (try
      (let [observations (read-observations (or (:observations execution) ""))
            expected (mapv #(select-keys % identity-keys) rows)
            actual (mapv #(select-keys % identity-keys) observations)]
        (cond
          (not= (count actual) (count (set actual)))
          {:failure {:kind :duplicate-observation :side side}}

          (not= expected actual)
          {:failure {:kind :observation-identities :side side
                     :expected expected :actual actual}}

          :else {:observations observations}))
      (catch clojure.lang.ExceptionInfo error
        {:failure (assoc (ex-data error) :side side)}))))

(defn compare-executions
  "Requires successful processes and exactly one ordered result per input step.
  Reports the first semantic difference, including failure operation/category
  and post-operation state. The expected exception is checked on both sides."
  [rows oracle probe]
  (let [left (checked-observations rows :oracle oracle)
        right (checked-observations rows :probe probe)]
    (if-let [failure (or (:failure left) (:failure right))]
      {:status :failed :first-difference failure}
      (or
       (first
        (keep (fn [[row expected actual]]
                (when (or (not= expected actual)
                          (not= (:expected-exception row) (:exception expected))
                          (not= (:expected-exception row) (:exception actual)))
                  {:status :failed
                   :first-difference
                   {:kind :semantic-difference
                    :identity (select-keys row identity-keys)
                    :expected-exception (:expected-exception row)
                    :oracle expected :probe actual}}))
              (map vector rows (:observations left) (:observations right))))
       {:status :passed :matched (count rows)}))))
