(ns dripsharp.microprogram
  "Explicit local JVM-versus-translated-C# microprogram batches.
  Loading this namespace neither loads Spoon nor starts a process."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def default-batch "validation/microprograms/batch.edn")
(def ^:private max-bytes (* 1024 1024))

(defn- read-edn [file]
  (util/read-single-edn-string! (slurp (str file))))

(defn- reject! [message data]
  (throw (ex-info message (assoc data :kind :candidate-rejected))))

(defn- relative-source? [value]
  (and (string? value)
       (re-matches #"(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\.java" value)))

(defn- typed-argument? [type token]
  (case type
    "string" (or (= "n:" token) (str/starts-with? token "u:"))
    (and (str/starts-with? token "i:")
         (let [value (bigint (subs token 2))]
           (case type
             "int" (<= Integer/MIN_VALUE value Integer/MAX_VALUE)
             "long" (<= Long/MIN_VALUE value Long/MAX_VALUE)
             false)))))

(defn validate-snapshot!
  "Admission checks only. Accepted Java source is never rejected because its
  compilation, resolution, translation, or generated build subsequently fails."
  [snapshot]
  (when-not (and (map? snapshot)
                 (= 1 (:schema-version snapshot))
                 (string? (:id snapshot)) (not (str/blank? (:id snapshot)))
                 (map? (:generator snapshot))
                 (some? (get-in snapshot [:generator :id]))
                 (contains? (:generator snapshot) :seed)
                 (pos-int? (get-in snapshot [:generator :version]))
                 (map? (:sources snapshot))
                 (<= 1 (count (:sources snapshot)) 32)
                 (every? relative-source? (keys (:sources snapshot)))
                 (every? string? (vals (:sources snapshot)))
                 (every? string? (map snapshot [:input :java-observer :dotnet-observer]))
                 (or (nil? (:dotnet-consumer snapshot)) (string? (:dotnet-consumer snapshot)))
                 (<= (count (.getBytes (pr-str snapshot) "UTF-8")) (* 4 max-bytes))
                 (pos-int? (:java-release snapshot))
                 (map? (:operations snapshot))
                 (seq (:operations snapshot)))
    (reject! "Invalid microprogram snapshot or batch exceeds size limits" {}))
  (let [config (:configuration snapshot)]
    (when-not (and (= 'dripsharp.java-library/rule-bundle (:destination-bundle config))
                   (= "netstandard2.0" (get-in config [:project :target-framework]))
                   (= "DripSharp.Microprograms" (get-in config [:project :assembly-name]))
                   (= "library" (get-in config [:output :project-directory]))
                   (= "DripSharp.Microprograms.csproj" (get-in config [:output :project-file]))
                   (true? (get-in config [:project :warnings-as-errors]))
                   (contains? (:destination-capabilities config) :java-compat))
      (reject! "Batch must use the production Java library bundle and settings" {})))
  (doseq [[operation entry] (:operations snapshot)]
    (when-not (and (map? entry)
                   (string? operation) (re-matches #"[A-Za-z0-9_.-]+" operation)
                   (every? #(and (string? %) (re-matches #"[A-Za-z_][A-Za-z0-9_.+]*" %))
                           (map entry [:java-type :java-method :dotnet-type :dotnet-method]))
                   (vector? (:parameters entry))
                   (every? #{"int" "long" "string"} (:parameters entry)))
      (reject! "Invalid native observer operation binding" {:operation operation})))
  (let [rows (try (cases/read-cases (:input snapshot))
                  (catch clojure.lang.ExceptionInfo error
                    (reject! "Invalid concrete input" {:cause (ex-data error)})))]
    (when (> (count rows) 1000)
      (reject! "Batch exceeds 1000 input records" {:count (count rows)}))
    (doseq [row rows]
      (let [entry (get-in snapshot [:operations (:operation row)])]
        (when-not (and entry
                       (contains? #{"none" "arithmetic" "index-out-of-bounds" "null-pointer" "illegal-argument"}
                                  (:expected-exception row))
                       (= (count (:parameters entry)) (count (:arguments row)))
                       (every? true? (map typed-argument? (:parameters entry) (:arguments row))))
          (reject! "Input does not match an observer operation" {:input row}))))
    rows))

(defn- bounded-text [file]
  (when (> (Files/size file) max-bytes)
    (reject! "Batch file exceeds 1 MiB" {:file (str file)}))
  (slurp (str file)))

(defn read-batch
  "Captures concrete sources, observers, production settings, and inputs.
  The Java baseline is resolved from the current direct JavaCompat contract."
  [root batch-file]
  (let [file (paths/resolve-path root batch-file)
        batch (read-edn file)
        directory (.getParent file)
        read-text #(bounded-text (paths/resolve-path directory %))]
    (when-not (and (vector? (:sources batch))
                   (<= 1 (count (:sources batch)) 32)
                   (every? relative-source? (:sources batch))
                   (= (count (:sources batch)) (count (distinct (:sources batch)))))
      (reject! "Batch requires 1–32 distinct Java source paths" {}))
    (merge batch
           {:sources (into (sorted-map) (map (fn [path] [path (read-text path)]) (:sources batch)))
            :configuration (util/read-single-edn-string! (read-text (:configuration batch)))
            :input (read-text (:input batch))
            :java-observer (read-text (:java-observer batch))
            :dotnet-observer (read-text (:dotnet-observer batch))
            :java-release (:java-release (read-edn (paths/resolve-path root "validation/java-compat/contract.edn")))}
           (when (:dotnet-consumer batch)
             {:dotnet-consumer (read-text (:dotnet-consumer batch))}))))

(defn- write-edn! [file value]
  (util/write-text! file (str (pr-str value) "\n")))

(defn- write-bindings! [directory snapshot side]
  (let [type-key (keyword (str (name side) "-type"))
        method-key (keyword (str (name side) "-method"))
        file (paths/resolve-path directory (str (name side) "-bindings.tsv"))]
    (util/write-text!
     file (str (str/join "\n" (for [[operation entry] (sort-by key (:operations snapshot))]
                                (str/join "\t" [operation (type-key entry) (method-key entry)
                                                (str/join "," (:parameters entry))]))) "\n"))
    file))

(defn emit-subjects!
  "Worker entry: ordinary resolved Spoon model and Java library emission.
  Only the subject sources enter Spoon; native observers never do."
  [root directory java-home]
  (let [snapshot (read-edn (paths/resolve-path directory "snapshot.edn"))
        _ (validate-snapshot! snapshot)
        source-root (paths/resolve-path directory "sources")
        input {:schema-version 1 :project-id (:id snapshot)
               :source-roots [source-root] :resource-roots []
               :production-sources (mapv #(paths/resolve-path source-root %) (sort (keys (:sources snapshot))))
               :generated-production-sources [] :production-resources []
               :java-toolchain {:home (paths/absolute java-home) :release (:java-release snapshot)
                                :preview-features? false}
               :project-dependencies [] :external-dependencies [] :classpath-artifacts []}
        model ((requiring-resolve 'dripsharp.spoon/build-resolved-model!) root input)
        config (:configuration snapshot)
        strategy ((requiring-resolve 'dripsharp.java-library/public-surface-strategy))
        surface ((:read! strategy) root {})
        selected ((:validate-selected! strategy) root surface model)]
    ((requiring-resolve 'dripsharp.concurrency/call-with-executor)
     (fn []
       (let [emission ((requiring-resolve 'dripsharp.java-project/emit-project!)
                       {:workspace-root root :target directory :project-input input
                        :resolved-model model :configuration config})]
         ((:validate-generated! strategy) selected emission))))
    (let [result {:status :emitted
                  :translator-version @(requiring-resolve 'dripsharp.java-project/translator-version)}]
      (write-edn! (paths/resolve-path directory "translation.edn") result)
      result)))

(def ^:private observer-project
  (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
       "<OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework>"
       "<Nullable>enable</Nullable><ImplicitUsings>disable</ImplicitUsings>"
       "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
       "</PropertyGroup><ItemGroup>"
       "<ProjectReference Include=\"../library/DripSharp.Microprograms.csproj\" />"
       "</ItemGroup></Project>\n"))

(defn- quote-shell [value]
  (str "'" (str/replace (str value) "'" "'\"'\"'") "'"))

(defn- exception-data [error]
  (cond-> {:message (.getMessage ^Exception error) :data (ex-data error)}
    (.getCause ^Exception error) (assoc :cause (exception-data (.getCause ^Exception error)))))

(defn- perturb! [directory {:keys [file before after] :as perturbation}]
  (when-not (and (string? file)
                 (re-matches #"(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\.cs" file)
                 (string? before) (seq before) (string? after) (not= before after))
    (throw (ex-info "Invalid generated-code perturbation" {:perturbation perturbation})))
  (let [source (paths/resolve-path directory "library" "src" file)
        text (slurp (str source))]
    (when-not (str/includes? text before)
      (throw (ex-info "Perturbation did not match generated code" {:file (str source) :before before})))
    (util/write-text! (paths/resolve-path directory "unperturbed.cs.txt") text)
    (util/write-text! source (str/replace-first text before after))))

(defn verify!
  "Builds one exact template batch once per side, then executes all its records.
  Each invocation has a fresh directory: no cross-source/runtime build cache.
  Replay reuses the recorded sources/input/config, rebuilding with today's
  translator and authored runtime. All stages, including translation, are
  child processes with timeouts. Returns non-success evidence without hiding it."
  ([] (verify! {}))
  ([{:keys [workspace-root batch-file replay output-directory run-command! java-tools! perturbation]
     :or {batch-file default-batch output-directory "validation-output/microprograms"
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         parent (paths/resolve-path root output-directory)
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         directory (Files/createTempDirectory parent "run-" (make-array FileAttribute 0))
         file #(paths/resolve-path directory %)
         snapshot-file (file "snapshot.edn")
         phase (atom :admission)
         versions (atom {})
         executions (atom [])
         started (System/nanoTime)
         ;; Bound total process time, as well as each individual stage.
         command! (fn [stage timeout command]
                    (reset! phase stage)
                    (let [remaining (- 600000 (quot (- (System/nanoTime) started) 1000000))]
                      (when-not (pos? remaining)
                        (throw (ex-info "Microprogram batch exceeded ten minutes"
                                        {:kind :command-timeout :phase stage})))
                      (let [execution (cases/process-outcome
                                       run-command! stage
                                       {:directory root :timeout-ms (min timeout remaining)
                                        :command (mapv str command)})]
                        (swap! executions conj execution)
                        (write-edn! (file "processes.edn") @executions)
                        (when-not (= :ok (:status execution))
                          (throw (ex-info "Microprogram process failed" {:execution execution})))
                        (get-in execution [:process :output]))))
         result
         (try
           (let [snapshot (if replay
                            (read-edn (paths/resolve-path root replay))
                            (read-batch root batch-file))
                 snapshot (cond-> snapshot perturbation (assoc :perturbation perturbation))
                 _ (write-edn! snapshot-file snapshot)
                 rows (validate-snapshot! snapshot)
                 _ (reset! phase :prepare)
                 _ (doseq [[path source] (:sources snapshot)]
                     (util/write-text! (file (str "sources/" path)) source))
                 _ (util/write-text! (file "input.tsv") (:input snapshot))
                 _ (util/write-text! (file "observer/MicroObserver.java") (:java-observer snapshot))
                 _ (util/write-text! (file "observer/Program.cs") (:dotnet-observer snapshot))
                 _ (when-let [consumer (:dotnet-consumer snapshot)]
                     (util/write-text! (file "observer/NativeConsumer.cs") consumer))
                 _ (util/write-text! (file "observer/Observer.csproj") observer-project)
                 _ (write-edn! (file "destination.edn") (:configuration snapshot))
                 bindings (into {} (map (fn [side] [side (write-bindings! directory snapshot side)]) [:java :dotnet]))
                 resolve-tools (or java-tools! (requiring-resolve 'dripsharp.java-compat-differential/java-tools))
                 {:keys [home java javac]} (resolve-tools snapshot)
                 _ (swap! versions assoc :java-contract (:java-release snapshot) :java-home (str home)
                          :library-contract "netstandard2.0" :observer-contract "net10.0")
                 _ (swap! versions assoc :javac (-> (command! :javac-version 10000 [javac "-version"])
                                                    process/without-java-tool-options-banner
                                                    str/trim)
                          :dotnet-sdk (str/trim (command! :dotnet-version 10000 ["dotnet" "--version"])))
                 _ (Files/createDirectories (file "classes") (make-array FileAttribute 0))
                 _ (command! :java-compile 120000
                             (into [javac "--release" (:java-release snapshot) "-encoding" "UTF-8"
                                    "-d" (file "classes") (file "observer/MicroObserver.java")]
                                   (map #(file (str "sources/" %)) (sort (keys (:sources snapshot))))))
                 _ (command! :translation 180000
                             ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.microprogram"
                              "--emit" directory home])
                 _ (swap! versions assoc :translator
                          (:translator-version (read-edn (file "translation.edn"))))
                 _ (when-let [change (:perturbation snapshot)]
                     (reset! phase :perturbation)
                     (perturb! directory change))
                 _ (command! :dotnet-build 180000
                             ["dotnet" "build" (file "observer/Observer.csproj") "--nologo"
                              "--configuration" "Release" "--verbosity:minimal" "-warnaserror"
                              "-m:1" "-p:BuildInParallel=false"])
                 java-command [java "-cp" (file "classes") "MicroObserver"]
                 dotnet-command ["dotnet" "--roll-forward" "LatestPatch"
                                 (file "observer/bin/Release/net10.0/Observer.dll")]
                 java-version (-> (command! :java-runtime 10000 (conj java-command "--runtime-version"))
                                  process/without-java-tool-options-banner
                                  str/trim)
                 dotnet-version (str/trim (command! :dotnet-runtime 10000 (conj dotnet-command "--runtime-version")))
                 _ (swap! versions assoc :java-runtime java-version :dotnet-runtime dotnet-version)
                 _ (when-not (and (str/starts-with? java-version (str (:java-release snapshot) "."))
                                  (str/starts-with? dotnet-version ".NET 10."))
                     (throw (ex-info "Executed runtime differs from recorded contract" {:kind :runtime-contract})))
                 observe (fn [side command]
                           (command! side 30000 (into command [(bindings side) (file "input.tsv") (file (str (name side) ".tsv"))])))
                 _ (observe :java java-command)
                 _ (observe :dotnet dotnet-command)
                 _ (reset! phase :comparison)
                 outcome (cases/compare-executions
                          rows {:status :ok :observations (slurp (str (file "java.tsv")))}
                          {:status :ok :observations (slurp (str (file "dotnet.tsv")))})
                 native (when (and (:dotnet-consumer snapshot) (= :passed (:status outcome)))
                          {:status :passed
                           :output (command! :native-consumer 30000
                                             (into dotnet-command ["--native-consumer" (file "input.tsv")]))})]
             (cond-> (merge {:batch (:id snapshot) :generator (:generator snapshot) :operations (count rows)} outcome)
               native (assoc :native-consumer native)))
           (catch Exception error
             {:status (if (and (= :admission @phase) (= :candidate-rejected (:kind (ex-data error))))
                        :rejected :failed)
              :matched 0 :phase @phase :diagnostic (exception-data error)}))
         summary (merge {:schema-version 1 :directory (str directory) :snapshot (str snapshot-file)
                         :host (util/current-host) :versions @versions
                         :elapsed-ms (quot (- (System/nanoTime) started) 1000000)
                         :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g clojure -J-Xmx28g -M -m dripsharp.microprogram --replay "
                                      (quote-shell snapshot-file))}
                        result)]
     (write-edn! (file "summary.edn") summary)
     summary)))

(defn -main [& arguments]
  (try
    (if (= "--emit" (first arguments))
      (let [[_ directory home] arguments]
        (prn (emit-subjects! (paths/workspace-root) (paths/absolute directory) home)))
      (let [options (loop [remaining arguments options {}]
                      (if (empty? remaining)
                        options
                        (let [[flag value & more] remaining
                              key ({"--batch" :batch-file "--replay" :replay "--output" :output-directory} flag)]
                          (when-not (and key value (not (contains? options key)))
                            (throw (ex-info "Usage: [--batch FILE | --replay SNAPSHOT] [--output DIRECTORY]" {})))
                          (recur more (assoc options key value)))))
            _ (when (and (:batch-file options) (:replay options))
                (throw (ex-info "Select batch or replay, not both" {})))
            result (verify! options)]
        (prn result)
        (when-not (= :passed (:status result)) (System/exit 1))))
    (shutdown-agents)
    (catch Exception error
      (prn {:status :failed :diagnostic (exception-data error)})
      (shutdown-agents)
      (System/exit 2))))
