(ns dripsharp.microprogram-test
  "Process-free orchestration checks. Live compilation is explicitly selected
  in dripsharp.microprogram-live, outside default *_test.clj discovery."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "microprogram-test-" (make-array FileAttribute 0)))

(defn- snapshot [] (micro/read-batch (paths/workspace-root) micro/default-batch))

(defn- read-edn [file] (util/read-single-edn-string! (slurp (str file))))

(defn- stage [command]
  (cond
    (= ["javac" "-version"] command) :javac-version
    (= ["dotnet" "--version"] command) :dotnet-version
    (= "javac" (first command)) :java-compile
    (= "clojure" (first command)) :translation
    (= "build" (second command)) :dotnet-build
    (= "--runtime-version" (last command)) (if (= "java" (first command)) :java-runtime :dotnet-runtime)
    (= "java" (first command)) :java
    :else :dotnet))

(defn- observations [input]
  (let [rows (cases/read-cases (slurp input))
        lines (for [row rows]
                (str/join "\t" (into (mapv row cases/identity-keys)
                                     [(:expected-exception row) "n:" "n:"])))]
    (str (str/join "\n" (cons cases/output-header lines)) "\n")))

(defn- fake-process [calls failure]
  (fn [{:keys [command timeout-ms] :as request}]
    (let [phase (stage command)]
      (swap! calls conj (assoc request :phase phase))
      (is (<= 1 timeout-ms 180000))
      (when (= phase (:phase failure))
        (throw (ex-info "injected diagnostic" {:kind (:kind failure) :output "retained compiler/runtime diagnostic"})))
      (when (= :translation phase)
        (spit (str (paths/resolve-path (nth command 6) "translation.edn"))
              (pr-str {:status :emitted :translator-version "test-version"})))
      (when (#{:java :dotnet} phase)
        (spit (last command) (cond-> (observations (nth command (- (count command) 2)))
                               (and (= :dotnet phase) (= :mismatch (:phase failure)))
                               (str/replace-first "n:\tn:" "i:999\tn:"))))
      {:exit 0 :command command
       :output (case phase
                 :javac-version "javac 17.test"
                 :dotnet-version "10.0.test"
                 :java-runtime "17.test"
                 :dotnet-runtime ".NET 10.test"
                 "")})))

(defn- run-fake
  ([] (run-fake {}))
  ([{:keys [failure snapshot-change] :as options}]
   (let [directory (temp-directory)
         input (paths/resolve-path directory "snapshot.edn")
         saved ((or snapshot-change identity) (snapshot))
         _ (spit (str input) (pr-str saved))
         calls (atom [])
         result (micro/verify!
                 (merge {:replay (str input) :output-directory (str directory)
                         :java-tools! (constantly {:home "/fake/jdk17" :java "java" :javac "javac"})
                         :run-command! (fake-process calls failure)}
                        (dissoc options :failure :snapshot-change)))]
     {:result result :calls @calls :snapshot saved})))

(deftest builds-once-and-runs-identical-records-with-independent-observers
  (let [{:keys [result calls snapshot]} (run-fake)
        phases (frequencies (map :phase calls))
        oracle (some #(when (= :java (:phase %)) (:command %)) calls)
        probe (some #(when (= :dotnet (:phase %)) (:command %)) calls)
        java-compile (some #(when (= :java-compile (:phase %)) (:command %)) calls)
        translation (some #(when (= :translation (:phase %)) (:command %)) calls)]
    (is (= :passed (:status result)))
    (is (= 15 (:matched result)))
    (is (= {:javac-version 1 :dotnet-version 1 :java-compile 1 :translation 1
            :dotnet-build 1 :java-runtime 1 :dotnet-runtime 1 :java 1 :dotnet 1} phases))
    (is (= (nth oracle (- (count oracle) 2)) (nth probe (- (count probe) 2))))
    (is (= (:input snapshot) (slurp (nth oracle (- (count oracle) 2)))))
    (is (= snapshot (read-edn (:snapshot result))))
    (is (some #(str/ends-with? % "/observer/MicroObserver.java") java-compile))
    (is (some #(str/ends-with? % "/sources/subjects/micro/Numeric.java") java-compile))
    (is (= "--emit" (nth translation 5)))
    (is (= 17 (get-in result [:versions :java-contract])))
    (is (= "test-version" (get-in result [:versions :translator])))
    (is (str/includes? (:replay result) (:snapshot result)))))

(deftest replay-captures-source-and-config-and-does-not-use-stale-builds
  (let [first-run (run-fake)
        captured (read-edn (get-in first-run [:result :snapshot]))
        second-run (run-fake {:replay (get-in first-run [:result :snapshot])})]
    (is (= captured (read-edn (get-in second-run [:result :snapshot]))))
    (is (not= (get-in first-run [:result :directory]) (get-in second-run [:result :directory])))
    (is (= 1 (count (filter #(= :translation (:phase %)) (:calls second-run))))))
  (let [{:keys [result]} (run-fake {:snapshot-change #(assoc-in % [:sources "subjects/micro/Numeric.java"] "changed source")})]
    (is (= "changed source" (get-in (read-edn (:snapshot result)) [:sources "subjects/micro/Numeric.java"])))))

(deftest accepted-templates-retain-failed-stages-and-never-count-as-behavior
  (doseq [[phase kind] [[:java-compile :command-failed]
                        [:translation :command-failed]
                        [:dotnet-build :command-failed]
                        [:java :command-start-failed]
                        [:dotnet :command-failed]
                        [:dotnet :command-timeout]]]
    (testing (str phase " " kind)
      (let [{:keys [result calls snapshot]} (run-fake {:failure {:phase phase :kind kind}})
            processes (read-edn (paths/resolve-path (:directory result) "processes.edn"))]
        (is (= :failed (:status result)))
        (is (= 0 (:matched result)))
        (is (= phase (:phase result)))
        (is (= phase (:phase (last calls))))
        (is (= snapshot (read-edn (:snapshot result))))
        (is (= "retained compiler/runtime diagnostic" (get-in (last processes) [:process :output])))
        (is (= (if (= :command-timeout kind) :timeout :process-failure) (:status (last processes))))))))

(deftest malformed-candidates-are-rejected-before-any-build
  (doseq [change [#(assoc % :input (str/replace (:input %) "\tmix\t" "\tunknown\t"))
                  #(assoc % :operations ["invalid"])
                  #(assoc-in % [:operations "mix"] nil)
                  #(assoc % :sources {"../outside.java" "source"})
                  #(assoc % :input (str/replace (:input %) "i:2147483647" "i:2147483648"))
                  #(assoc-in % [:configuration :project :target-framework] "net10.0")]]
    (let [{:keys [result calls]} (run-fake {:snapshot-change change})]
      (is (= :rejected (:status result)))
      (is (zero? (:matched result)))
      (is (empty? calls))
      (is (paths/regular-file? (paths/path (:snapshot result)))))))

(deftest semantic-mismatch-is-distinct-from-build-and-execution-failure
  (let [{:keys [result]} (run-fake {:failure {:phase :mismatch}})]
    (is (= :failed (:status result)))
    (is (= :semantic-difference (get-in result [:first-difference :kind])))
    (is (= "zero" (get-in result [:first-difference :identity :case])))))
