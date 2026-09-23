(ns dripsharp.differential-cases-test
  "Process-free checks only. Live cases use the explicit local CLI."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-compat-differential :as java-compat]
            [dripsharp.paths :as paths]))

(def input
  (str cases/input-header "\n"
       "byte-buffer\tsigned\t0\twrap\tnone\tb:ff\n"
       "byte-buffer\tsigned\t1\tget\tnone\n"
       "byte-buffer\tsigned\t2\tget\tbuffer-underflow\n"))

(def output
  (str cases/output-header "\n"
       "byte-buffer\tsigned\t0\twrap\tnone\tn:\ti:0\n"
       "byte-buffer\tsigned\t1\tget\tnone\ti:-1\ti:1\n"
       "byte-buffer\tsigned\t2\tget\tbuffer-underflow\tn:\ti:1\n"))

(defn execution [output] {:status :ok :observations output})
(defn compare-output [actual]
  (cases/compare-executions (cases/read-cases input)
                            (execution output) (execution actual)))

(deftest exact-value-transport
  (doseq [token ["n:" "b:" "b:0080ff" "u:" "u:0000d8000061dc00ffff"
                 "i:9223372036854775807" "i:-9223372036854775808"
                 "i:9007199254740993" "i:999999999999999999999999999999999999"]]
    (testing token
      (is (cases/value-token? token))
      (let [text (str cases/input-header "\nf\tc\t0\top\tnone\t" token "\n")]
        (is (= text (cases/write-cases (cases/read-cases text))))))))

(deftest malformed-input-and-unknown-operations-fail
  (doseq [token ["i:01" "i:-0" "i:1.0" "i:1e30" "b:f" "b:FF" "u:d80" "null" "u:😀"]]
    (is (not (cases/value-token? token))))
  (doseq [text ["" (str input "\nbyte-buffer\tother\t0\tget\tnone\n")
                (str input "byte-buffer\tsigned\t2\tget\tnone\n")]]
    (is (thrown? clojure.lang.ExceptionInfo (cases/read-cases text))))
  (doseq [text [(str/replace input "wrap" "unknown")
                (str/replace input "byte-buffer" "unregistered")
                (str/replace input "b:ff" "u:00ff")
                (str/replace input "buffer-underflow" "anything")
                (str cases/input-header "\nlong\tlarge\t0\thash\tnone\ti:9223372036854775808\n")]]
    (is (thrown? clojure.lang.ExceptionInfo (java-compat/select-cases text {})))))

(deftest selection-retains-complete-case-steps
  (let [text (slurp "validation/java-compat/cases.tsv")
        rows (java-compat/select-cases text {:family "byte-buffer" :case-id "signed"})]
    (is (= ["0" "1" "2" "3" "4"] (mapv :step rows)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (java-compat/select-cases text {:family "absent"})))))

(deftest file-cases-cover-lexical-uri-query-and-mutation-contracts
  (let [text (slurp "validation/java-compat/cases.tsv")
        rows (java-compat/select-cases text {:family "file"})
        operations (set (map :operation rows))]
    (is (= 51 (count rows)))
    (is (every? operations ["inspect" "absolute" "parent-child"
                            "file-parent-child" "uri" "equals" "to-path"
                            "create" "delete" "open" "list-count"]))
    (is (some #(= ["to-path" "invalid-path"]
                  [(:operation %) (:expected-exception %)]) rows))
    (is (some #(= ["create" "io"]
                  [(:operation %) (:expected-exception %)]) rows))))

(deftest stream-and-random-access-cases-cover-state-failure-and-close-contracts
  (let [text (slurp "validation/java-compat/cases.tsv")
        selected #(java-compat/select-cases text {:family %})
        rows (mapcat selected ["byte-array-input" "filter-input"
                               "byte-array-output" "filter-output" "random-access"])
        identities (set (map (juxt :family :case :operation :expected-exception) rows))]
    (is (= 76 (count rows)))
    (is (contains? identities ["filter-input" "short-read" "read-buffer" "none"]))
    (is (contains? identities ["filter-input" "injected-failure" "read-buffer" "io"]))
    (is (contains? identities ["filter-input" "eof" "read-one" "none"]))
    (is (contains? identities ["byte-array-input" "boundaries" "reset" "none"]))
    (is (contains? identities ["byte-array-output" "close-noop" "write-one" "none"]))
    (is (contains? identities ["filter-output" "zero-and-close" "write-one" "io"]))
    (is (contains? identities ["random-access" "boundaries" "read-fully" "eof"]))
    (is (contains? identities ["random-access" "read-only" "write-buffer" "io"]))))

(deftest compression-cases-cover-payload-chunk-and-failure-contracts
  (let [text (slurp "validation/java-compat/cases.tsv")
        rows (java-compat/select-cases text {:family "compression"})
        identities (set (map (juxt :case :operation :expected-exception) rows))]
    (is (= 12 (count rows)))
    (is (every? identities
                [["empty" "roundtrip" "none"]
                 ["repeated-257" "roundtrip" "none"]
                 ["incompressible-seed-5eed-v1" "roundtrip" "none"]
                 ["high-bit-128" "roundtrip" "none"]
                 ["boundary-255" "roundtrip" "none"]
                 ["boundary-256" "roundtrip" "none"]
                 ["boundary-257" "roundtrip" "none"]
                 ["boundary-1025" "roundtrip" "none"]
                 ["truncated-zlib-v1" "decode" "none"]
                 ["checksum-corrupt-zlib-v1" "decode" "io"]
                 ["payload-corrupt-zlib-v1" "decode" "io"]]))))

(deftest comparator-requires-values-state-and-exception-timing
  (is (= {:status :passed :matched 3} (compare-output output)))
  (doseq [actual [(str/replace output "i:-1" "i:255")
                  (str/replace output "buffer-underflow" "null-pointer")
                  (str/replace output "get\tnone" "get\tbuffer-underflow")
                  (str/replace output "n:\ti:1" "n:\ti:2")]]
    (let [result (compare-output actual)]
      (is (= :failed (:status result)))
      (is (= :semantic-difference (get-in result [:first-difference :kind])))
      (is (= "signed" (get-in result [:first-difference :identity :case])))))
  (testing "Both implementations throwing at the wrong step is not a match"
    (let [wrong (execution (str/replace output "buffer-underflow" "none"))]
      (is (= :failed (:status (cases/compare-executions (cases/read-cases input) wrong wrong)))))))

(deftest comparator-rejects-missing-extra-duplicate-and-reordered-observations
  (is (= :failed
         (:status (cases/compare-executions (cases/read-cases input)
                                            (execution output) {:status :ok}))))
  (let [[header a b c] (str/split-lines output)]
    (doseq [lines [[] [header] [header a b] [header a b c c]
                   [header a b c (str/replace c "signed" "extra")]
                   [header a c b] [header a (str/replace b "get" "unknown") c]]]
      (is (= :failed (:status (compare-output (str/join "\n" lines))))))))

(deftest process-failures-cannot-become-expected-exceptions
  (doseq [[kind status] [[:command-failed :process-failure]
                         [:command-start-failed :process-failure]
                         [:command-timeout :timeout]]
          phase [:java-compile :dotnet-build :oracle :probe]]
    (let [failure (cases/process-outcome
                   (fn [_] (throw (ex-info "simulated" {:kind kind :exit 137})))
                   phase {})
          result (cases/compare-executions (cases/read-cases input)
                                           (execution output) failure)]
      (is (= status (:status failure)))
      (is (= :failed (:status result)))
      (is (= phase (get-in result [:first-difference :phase])))))
  (is (= :process-failure
         (:status (cases/process-outcome (constantly {:exit 1}) :probe {})))))

(deftest local-runner-builds-once-and-shares-recorded-input
  (let [commands (atom [])
        input-paths (atom [])
        fake-run
        (fn [{:keys [command] :as invocation}]
          (swap! commands conj invocation)
          (cond
            (= "--cases" (nth command (- (count command) 3) nil))
            (let [[input output-file] (take-last 2 command)]
              (swap! input-paths conj input)
              (is (= 5 (count (cases/read-cases (slurp input)))))
              ;; Use the independently specified observation rows below.
              (spit output-file
                    (str cases/output-header "\n"
                         "byte-buffer\tsigned\t0\twrap\tnone\tn:\ti:0\n"
                         "byte-buffer\tsigned\t1\tget-int\tnone\ti:-2147417597\ti:4\n"
                         "byte-buffer\tsigned\t2\tget\tnone\ti:-1\ti:5\n"
                         "byte-buffer\tsigned\t3\tget\tbuffer-underflow\tn:\ti:5\n"
                         "byte-buffer\tsigned\t4\tget-int\tbuffer-underflow\tn:\ti:5\n"))
              {:exit 0 :output ""})

            (= "--runtime-version" (last command))
            {:exit 0 :output (if (= "dotnet" (first command)) ".NET 10.0.7" "17.0.19")}

            :else {:exit 0 :output "version"}))
        summary (with-redefs-fn
                  {#'java-compat/java-tools
                   (constantly {:home (paths/path "/fake-jdk") :java "java" :javac "javac"})}
                  #(java-compat/verify-cases!
                    {:family "byte-buffer" :case-id "signed" :run-command! fake-run}))]
    (is (= :passed (:status summary)))
    (is (= 1 (count (filter #(some #{"--release"} (:command %)) @commands))))
    (is (= 1 (count (filter #(some #{"build"} (:command %)) @commands))))
    (is (= 2 (count @input-paths)))
    (is (apply = (:input summary) @input-paths))
    (is (every? #(pos-int? (:timeout-ms %)) @commands))
    (is (= :not-run (:type-availability summary)))
    (is (str/includes? (:replay summary) (:input summary)))))

(deftest failed-compilation-retains-input-and-non-success-evidence
  (let [summary
        (with-redefs-fn
          {#'java-compat/java-tools
           (constantly {:home (paths/path "/fake-jdk") :java "java" :javac "javac"})}
          #(java-compat/verify-cases!
            {:family "string" :case-id "empty"
             :run-command!
             (fn [{:keys [command]}]
               (if (some #{"--release"} command)
                 (throw (ex-info "compiler error" {:kind :command-failed :exit 1}))
                 {:exit 0 :output "version"}))}))]
    (is (= :failed (:status summary)))
    (is (= :java-compile (get-in summary [:first-difference :execution :phase])))
    (is (= :process-failure (get-in summary [:first-difference :execution :status])))
    (is (= "empty" (:case (first (cases/read-cases (slurp (:input summary)))))))
    (is (paths/regular-file? (paths/resolve-path (.getParent (paths/path (:input summary)))
                                                 "summary.edn")))))
