(ns dripsharp.java-compat-cases-live
  "Explicit local live regression. Deliberately not an *_test.clj discovery file."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as java-compat]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]))

(defn- decode-string-token [token]
  (apply str
         (map #(char (Integer/parseInt (apply str %) 16))
              (partition 4 (subs token 2)))))

(defn- string-token [value]
  (str "u:" (apply str (map #(format "%04x" (int %)) value))))

(defn- write-observations [rows]
  (str cases/output-header "\n"
       (str/join "\n"
                 (map #(str/join "\t" (map % cases/output-keys)) rows))
       "\n"))

(defn- empty-path-directory-fault!
  "Runs the real probe and then substitutes the historical empty-path-as-current-
  directory mistake in its isolated trace. The untouched live trace must first
  match Java, so a process or harness failure cannot masquerade as detection."
  [{:keys [command] :as invocation}]
  (let [result (process/run! invocation)]
    (when (and (= "dotnet" (first command)) (some #{"--cases"} command))
      (let [[input output] (take-last 2 command)
            directory (.getParent (paths/path output))
            actual (slurp output)
            rows (cases/read-cases (slurp input))
            oracle (slurp (str (paths/resolve-path directory "java.tsv")))
            observations (cases/read-observations actual)
            target? #(and (= "file" (:family %))
                          (= "lexical" (:case %))
                          (= "1" (:step %))
                          (= "inspect" (:operation %)))
            targets (filterv target? observations)]
        (is (= :passed
               (:status (cases/compare-executions
                         rows {:status :ok :observations oracle}
                         {:status :ok :observations actual}))))
        (is (= 1 (count targets)))
        (spit (str (paths/resolve-path directory "dotnet-unperturbed.tsv")) actual)
        (spit output
              (write-observations
               (mapv (fn [row]
                       (if-not (target? row)
                         row
                         (let [fields (str/split
                                       (decode-string-token (:result row))
                                       #"\u0001" -1)]
                           (is (= 12 (count fields)))
                           (assoc row :result
                                  (string-token
                                   (str/join "\u0001"
                                             (-> fields
                                                 (assoc 4 "true")
                                                 (assoc 6 "true"))))))))
                     observations)))))
    result))

(deftest recorded-cases-match-live-runtimes-and-reject-perturbations
  (let [summary (java-compat/verify-cases!)
        directory (.getParent (paths/path (:input summary)))
        rows (cases/read-cases (slurp (:input summary)))
        oracle (slurp (str (paths/resolve-path directory "java.tsv")))
        probe (slurp (str (paths/resolve-path directory "dotnet.tsv")))
        execution (fn [text] {:status :ok :observations text})
        compare-probe #(cases/compare-executions rows (execution oracle) %)]
    (is (= :passed (:status summary)))
    (is (= 144 (:matched summary)))
    (is (= 26 (:cases summary)))
    (is (= {:status :passed :matched 144} (compare-probe (execution probe))))
    (is (not (paths/exists? (paths/resolve-path directory "file-sandboxes"))))
    (doseq [changed [(str/replace probe "i:-2147417597" "i:0")
                     (str/replace probe "buffer-underflow" "null-pointer")
                     (str/replace probe "get-int\tnone" "get-int\tbuffer-underflow")
                     ""]]
      (is (not= changed probe))
      (is (= :failed (:status (compare-probe (execution changed))))))
    (is (= :failed (:status (compare-probe {:status :process-failure :phase :probe
                                            :process {:exit 137}}))))))

(deftest empty-path-directory-substitution-is-detected-and-restored
  (let [options {:family "file" :case-id "lexical"}
        failed (java-compat/verify-cases!
                (assoc options :run-command! empty-path-directory-fault!))
        restored (java-compat/verify-cases! options)]
    (is (= :failed (:status failed)) (pr-str failed))
    (is (= :semantic-difference
           (get-in failed [:first-difference :kind])))
    (is (= {:family "file" :case "lexical" :step "1"
            :operation "inspect"}
           (get-in failed [:first-difference :identity])))
    (is (= :passed (:status restored)) (pr-str restored))
    (is (= 8 (:matched restored)))
    (println
     "Live empty-path negative control: treating the empty Java pathname as an existing directory was detected at file/lexical step 1; the restored direct probe matched all 8 focused operations.")))
