(ns dripsharp.differential-generation-test
  "Process-free generator/reducer checks; live execution is explicit and local."
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-campaign :as campaign]
            [dripsharp.differential-cases :as cases]
            [dripsharp.differential-generation :as gen]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(defn row [step op args exception requires]
  {:family "byte-buffer" :case "h0" :handle "h0" :step step
   :operation op :arguments args :expected-exception exception
   :requires requires :intent (if (= exception "none") :valid :invalid)})

(def sample
  {:schema-version 1 :generator gen/generator-identity :seed 42 :limits gen/default-limits
   :operations [(row "0" "get" [] "null-pointer" [])
                (row "1" "wrap" ["b:00ff7f80"] "none" [])
                (row "2" "get" [] "none" ["1"])
                (row "3" "get" [] "none" ["1"])
                (row "4" "get-int" [] "buffer-underflow" ["1"])]})

(defn fault
  "Pure multi-operation fault: the stable read 3 observes a signed-byte defect."
  [record]
  (if (and (some #(= "3" (:step %)) (:operations record))
           (some #(some #{"ff" "80"} (map (partial apply str) (partition 2 (subs % 2))))
                 (mapcat :arguments (:operations record))))
    (let [identity (select-keys (first (filter #(= "3" (:step %)) (:operations record))) cases/identity-keys)]
      {:status :failed :first-difference
       {:kind :semantic-difference :identity identity :expected-exception "none"
        :oracle (assoc identity :exception "none" :result "i:-1" :state "i:1")
        :probe (assoc identity :exception "none" :result "i:255" :state "i:1")}})
    {:status :passed}))

(deftest seeded-bounded-generation-and-domain-validation
  (doseq [seed (range 100)]
    (let [record (gen/generate seed)]
      (is (= record (gen/generate seed)))
      (is (= record (gen/validate! record)))))
  (is (< 1 (count (set (map #(count (:operations (gen/generate %))) (range 10)))))))

(deftest file-family-generation-is-explicit-bounded-and-replayable
  (let [record (gen/generate 1 {:families ["file"] :max-operations 12})]
    (is (= record (gen/generate 1 {:families ["file"] :max-operations 12})))
    (is (<= 1 (count (:operations record)) 12))
    (is (every? #(and (= "file" (:family %))
                      (= "inspect" (:operation %))
                      (= [] (:requires %))
                      (contains? #{"none" "null-pointer"} (:expected-exception %)))
                (:operations record)))
    (is (= record (gen/validate! record)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gen/generate 1 {:families ["unknown"]})))))

(deftest boundary-values-preserve-exact-domains
  (doseq [kind [:bytes :integer :string :path]]
    (let [rng (Random. 42) tokens (repeatedly 100 #(gen/value rng kind 8))]
      (is (every? cases/value-token? tokens))
      (when (= kind :integer)
        (is (some #{"i:-9223372036854775808"} tokens))
        (is (some #{"i:9007199254740993"} tokens)))
      (when (= kind :path)
        (is (some #{"u:"} tokens))
        (is (some #{"u:006100000062"} tokens)))
      (when (= kind :string)
        (is (some #(re-find #"d800|dc00" %) tokens))))))

(deftest replay-ignores-generator-version-and-prng
  (let [file (Files/createTempFile "recorded-case-" ".edn" (make-array FileAttribute 0))
        historical (assoc sample :generator {:id :retired-generator :version 99})]
    (try
      (gen/write-record! file historical)
      (with-redefs [gen/generate (fn [& _] (throw (AssertionError. "Generator called")))
                    gen/value (fn [& _] (throw (AssertionError. "Value generator called")))]
        (is (= historical (gen/replay! file identity))))
      (finally (Files/deleteIfExists file)))))

(deftest dependencies-and-intent-are-not-repaired-away
  (is (= sample (gen/validate! sample)))
  (doseq [candidate [(update sample :operations #(vec (remove (fn [op] (= "1" (:step op))) %)))
                     (assoc-in sample [:operations 2 :requires] [])
                     (assoc-in sample [:operations 2 :handle] "missing")
                     (assoc-in sample [:operations 4 :expected-exception] "none")
                     (assoc-in sample [:operations 0 :intent] :valid)
                     (assoc-in sample [:operations 1 :arguments] ["b:"])]]
    (is (thrown? clojure.lang.ExceptionInfo (gen/validate! candidate))))
  (doseq [candidate (gen/candidates sample)
          :when (not-any? #(= "1" (:step %)) (:operations candidate))]
    (is (not-any? #(seq (:requires %)) (:operations candidate)))))

(deftest pure-fault-reduces-to-replayable-construction-and-use
  (let [result (gen/shrink! sample (fault sample) (fn [record _] (fault record))
                            {:max-attempts 200 :max-ms 1000})
        minimal (:record result)]
    (is (= :exhausted (:stop result)))
    (is (= 2 (count (:operations minimal))))
    (is (< (count (pr-str minimal)) (count (pr-str sample))))
    (is (= ["wrap" "get"] (mapv :operation (:operations minimal))))
    (is (= (gen/failure-signature (fault sample)) (gen/failure-signature (:result result))))
    (is (= minimal (gen/validate! minimal)))))

(deftest intentional-invalid-operation-can-remain-the-failing-observation
  (let [predicate (fn [record]
                    (if-let [op (first (filter #(= "4" (:step %)) (:operations record)))]
                      {:status :failed :first-difference
                       {:kind :semantic-difference :identity (select-keys op cases/identity-keys)
                        :expected-exception "buffer-underflow"
                        :oracle {:exception "buffer-underflow" :result "n:" :state "i:2"}
                        :probe {:exception "buffer-underflow" :result "n:" :state "i:3"}}}
                      {:status :passed}))
        result (gen/shrink! sample (predicate sample) (fn [record _] (predicate record))
                            {:max-attempts 200 :max-ms 1000})]
    (is (= ["wrap" "get-int"] (mapv :operation (get-in result [:record :operations]))))
    (is (= :invalid (:intent (last (get-in result [:record :operations])))))
    (is (= "buffer-underflow" (:expected-exception (last (get-in result [:record :operations])))))))

(deftest errors-and-unrelated-observations-cannot-minimize-a-semantic-failure
  (doseq [bad [{:status :failed :first-difference {:kind :process-failure :phase :dotnet-build}}
               {:status :failed :first-difference {:kind :process-failure :phase :probe}}
               {:status :failed :first-difference {:kind :timeout}}
               (assoc-in (fault sample) [:first-difference :probe :exception] "null-pointer")
               (assoc-in (fault sample) [:first-difference :identity :step] "other")
               (assoc-in (fault sample) [:first-difference :probe :state] "i:10")]]
    (is (= sample (:record (gen/shrink! sample (fault sample) (fn [_ _] bad)
                                        {:max-attempts 100 :max-ms 1000})))))
  (is (= sample (:record (gen/shrink! sample (fault sample)
                                      (fn [_ _] (throw (RuntimeException. "unexpected")))
                                      {:max-attempts 100 :max-ms 1000})))))

(deftest shrink-bounds-count-rejections-and-stop-without-evaluation
  (let [calls (atom 0) evaluate (fn [_ _] (swap! calls inc) (fault sample))]
    (is (= :attempt-limit (:stop (gen/shrink! sample (fault sample) evaluate
                                              {:max-attempts 0 :max-ms 1000}))))
    (is (zero? @calls))
    (let [clock (atom 0)]
      (is (= :time-limit (:stop (gen/shrink! sample (fault sample) evaluate
                                             {:max-ms 1 :now-ns #(swap! clock + 2000000)})))))
    (is (zero? @calls))
    (let [result (gen/shrink! sample (fault sample) evaluate {:max-attempts 1 :max-ms 1000})]
      (is (= 1 (:attempts result)))
      (is (= 1 (get-in result [:counts :rejected])))
      (is (zero? @calls)))))

(deftest input-and-generation-bounds
  (doseq [options [{:max-operations 0} {:max-value-units 2000} {:max-input-bytes 2000000}]]
    (is (thrown? clojure.lang.ExceptionInfo (gen/generate 1 options))))
  (is (thrown? clojure.lang.ExceptionInfo
               (gen/validate! (assoc-in sample [:limits :max-input-bytes] 5)))))

(deftest accounting-distinguishes-outcomes
  (let [results [{:status :passed} (fault sample)
                 {:status :failed :first-difference {:kind :timeout}}
                 {:status :failed :first-difference {:kind :process-failure}}]]
    (is (= {:generated 0 :executed 2 :passed 1 :failed 1 :timed-out 1 :rejected 0 :harness-failed 1}
           (reduce gen/count-result gen/empty-counts results)))))

(deftest process-budget-caps-admission-and-timeouts
  (let [commands (atom [])
        budget (campaign/process-budget #(do (swap! commands conj %) {:exit 0}) 2 1000)
        run (:run-command! budget)]
    (run {:timeout-ms 30000})
    (run {:timeout-ms 30000})
    (is (every? #(<= 1 (:timeout-ms %) 1000) @commands))
    (is (thrown? clojure.lang.ExceptionInfo (run {:timeout-ms 10})))
    (is (= 2 @(:used budget)))
    (reset! (:evaluation-deadline budget) 0)
    (is (= :command-timeout
           (try (run {:timeout-ms 10}) (catch clojure.lang.ExceptionInfo error (:kind (ex-data error))))))))

(deftest campaign-builds-once-and-records-every-evaluation
  (let [builds (atom 0) executions (atom 0)
        parent (Files/createTempDirectory "campaign-test-" (make-array FileAttribute 0))]
    (with-redefs [compat/prepare-case-runner!
                  (fn [_]
                    (swap! builds inc)
                    {:execute! (fn [rows directory]
                                 (swap! executions inc)
                                 (is (= rows (:operations (gen/read-record (paths/resolve-path directory "case.edn")))))
                                 {:status :passed})})]
      (let [summary (campaign/run! {:seed 1 :case-count 3 :output-directory (str parent)})]
        (is (= :passed (:status summary)))
        (is (= 1 @builds))
        (is (= 3 @executions))
        (is (= 3 (get-in summary [:counts :generated])))
        (is (= 3 (get-in summary [:counts :executed])))
        (with-redefs [gen/generate (fn [& _] (throw (AssertionError. "No generation during replay")))]
          (let [replay (campaign/run! {:replay-file (first (:records summary)) :output-directory (str parent)})]
            (is (= :passed (:status replay)))
            (is (= 0 (get-in replay [:counts :generated])))
            (is (= 1 (get-in replay [:counts :executed])))))))))

(deftest reducers-shrink-integers-and-utf16-and-simplify-operations
  (doseq [[family token expected] [["long" "i:-9223372036854775808" "i:0"]
                                   ["string" "u:d80000000061" "u:"]]]
    (let [record (assoc sample :operations
                        [{:family family :case "h0" :handle "h0" :step "0" :operation "hash"
                          :arguments [token] :requires [] :expected-exception "none" :intent :valid}])]
      (is (some #(= [expected] (get-in % [:operations 0 :arguments])) (gen/candidates record)))))
  (let [record (assoc sample :operations [(row "0" "get-int" [] "null-pointer" [])])
        simpler (first (filter #(= "get" (get-in % [:operations 0 :operation])) (gen/candidates record)))]
    (is (= simpler (gen/validate! simpler)))))

(deftest malformed-records-cannot-silently-change-wire-identities
  (is (thrown? clojure.lang.ExceptionInfo
               (gen/validate! (assoc-in sample [:operations 0 :step] 0)))))

(deftest failed-preparation-keeps-concrete-input-and-does-not-count-execution
  (let [parent (Files/createTempDirectory "campaign-failed-" (make-array FileAttribute 0))]
    (with-redefs [compat/prepare-case-runner!
                  (fn [_] (throw (ex-info "test compile failure"
                                          {:kind :case-process-failure
                                           :execution {:status :process-failure :phase :java-compile}})))]
      (let [summary (campaign/run! {:seed 42 :output-directory (str parent)})]
        (is (= 1 (get-in summary [:counts :generated])))
        (is (= 0 (get-in summary [:counts :executed])))
        (is (= 1 (get-in summary [:counts :harness-failed])))
        (is (= 42 (:seed (gen/read-record (first (:records summary))))))
        (is (= :java-compile (get-in summary [:first-difference :execution :phase])))))))
