(ns dripsharp.proof-resume
  "Local PdfCarton proof recovery. Package artifacts may be reused; validators
  always execute again. This never supplies proof for a different product commit."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file Files Path StandardOpenOption FileVisitOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *run* nil)
(def ^:dynamic *generation-lock* false)

(def ^:private validator-files
  #{"src/dripsharp/pdfcube/family_packaging.clj"
    "src/dripsharp/pdfcube/family_workflows.clj"
    "src/dripsharp/pdfcube/pdfbox_differential.clj"
    "src/dripsharp/pdfcube/preflight_differential.clj"
    "src/dripsharp/pdfcube/preflight_corpus.clj"})

(def ^:private transient-names #{".git" "bin" "obj" "target" ".gradle"})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :proof-resume-failed))))

(defn- files-under [root]
  (cond
    (paths/regular-file? root) [(paths/absolute root)]
    (paths/directory? root)
    (with-open [stream (Files/walk (paths/absolute root) (make-array FileVisitOption 0))]
      (vec (filter paths/regular-file? (iterator-seq (.iterator stream)))))
    :else []))

(defn file-inputs
  "Content fingerprints, including explicit missing inputs. Directory roots
  include new files, so unknown source changes cannot silently reuse packages."
  [roots]
  (into (sorted-map)
        (mapcat (fn [root]
                  (if (paths/exists? root)
                    (map (fn [p] [(str p) (util/sha256-file p)]) (files-under root))
                    [[(str (paths/absolute root)) :missing]])))
        roots))

(defn- source-files [root relative]
  (let [base (paths/resolve-path root relative)]
    (filter
     (fn [^Path file]
       (let [path (str (.relativize root file))
             parts (str/split (str (.relativize base file)) #"[/\\]")]
         (and (not-any? transient-names parts)
              (not (contains? validator-files path))
              (not (str/starts-with? path "targets/pdfcube/validation/")))))
     (files-under base))))

(defn production-inputs
  "Explicit artifact boundary: source/runtime/mappings/upstream/vendor inputs,
  generated product bytes and commit, build options and installed toolchain.
  Only the named validators and validation fixtures are excluded; they rerun.
  Source repository HEAD is deliberately not an artifact input."
  [{:keys [workspace-root profile run-command! build-configuration]
    :or {run-command! process/run! build-configuration "Release"} :as options}]
  (let [root (paths/absolute workspace-root)
        command-output (fn [command]
                         (:output (run-command! {:command command :directory root})))
        files (mapcat #(source-files root %)
                      ["src" "runtime" "config" "maven" "targets/pdfcube" "vendor/pdfcube"
                       "research/pdfbox" "products/pdfcarton/src"
                       "products/pdfcarton/eng" "products/pdfcarton/.github"
                       "products/pdfcarton/README.md" "products/pdfcarton/LICENSE"
                       "products/pdfcarton/NOTICE" "README.md" "deps.edn"
                       "global.json" "NuGet.Config" "Directory.Build.props"
                       "Directory.Build.targets" "Directory.Packages.props"
                       "products/pdfcarton/global.json" "products/pdfcarton/NuGet.Config"
                       "products/pdfcarton/Directory.Build.props"
                       "products/pdfcarton/Directory.Build.targets"
                       "products/pdfcarton/Directory.Packages.props"])]
    {:schema-version 1
     :profile profile
     :build-configuration build-configuration
     :build-options (into (sorted-map)
                          (map (fn [[key value]] [key (util/sha256-text (pr-str value))]))
                          (select-keys options [:repository-commit :source-revision-id
                                                :build-properties :target-framework
                                                :runtime-identifier :worker-count]))
     :os (System/getProperty "os.name")
     :architecture (System/getProperty "os.arch")
     :java (System/getProperty "java.runtime.version")
     :environment (into (sorted-map)
                        (map (fn [[key value]] [key (util/sha256-text value)]))
                        (select-keys (into {} (System/getenv))
                                     ["DOTNET_ROOT" "MSBuildSDKsPath" "MSBUILD_EXE_PATH"
                                      "JAVA_HOME" "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS"
                                      "NUGET_PACKAGES"]))
     :dotnet (command-output ["dotnet" "--info"])
     :product-commit (str/trim (command-output ["git" "-C" "products/pdfcarton" "rev-parse" "HEAD"]))
     :files (file-inputs files)}))

(defn- encode [value]
  (walk/prewalk
   (fn [x]
     (cond
       (instance? Path x) (tagged-literal 'dripsharp/path (str x))
       (instance? java.io.File x) (tagged-literal 'dripsharp/path (str x))
       ;; Live Spoon models are not part of package/consumer evidence.
       (and (map? x) (some #{:java-model :public-surface-strategy :interface-method} (keys x)))
       (cond-> (dissoc x :public-surface-strategy :interface-method)
         (some #{:java-model} (keys x))
         (update :java-model #(select-keys % [:totals])))
       :else x))
   value))

(defn- read-state [file]
  (when (paths/regular-file? file)
    (try
      (edn/read-string {:readers {'dripsharp/path paths/path}} (slurp (str file)))
      (catch Exception _ nil))))

(defn- write-state! [file state]
  (let [text (pr-str (encode state))]
    ;; Fail closed rather than recording an unreadable successful result.
    (edn/read-string {:readers {'dripsharp/path paths/path}} text)
    (util/write-text! file (str text "\n"))))

(defn- stage-file [id]
  (paths/resolve-path (:root *run*) (str (util/sha256-text (pr-str id)) ".edn")))

(defn- record! [event]
  (swap! (:events *run*) conj event)
  (println "PdfCarton resume phase:" (pr-str event))
  (write-state! (paths/resolve-path (:root *run*) "summary.edn")
                {:run-id (:run-id *run*) :phases @(:events *run*)}))

(defn check!
  "Run a required validator freshly, retaining failure history in the phase log.
  Candidate Windows and published-feed Windows use distinct caller phase IDs."
  [id f]
  (if-not *run*
    (f)
    (let [started (System/nanoTime)]
      (try
        (let [result (f)]
          (record! {:phase id :status :passed :action :executed
                    :elapsed-ms (quot (- (System/nanoTime) started) 1000000)})
          result)
        (catch Throwable error
          (record! {:phase id :status :failed :action :executed
                    :error (.getMessage error)
                    :elapsed-ms (quot (- (System/nanoTime) started) 1000000)})
          (throw error))))))

(defn stage!
  "Persist one successful artifact boundary. Callers supply complete inputs and
  output roots. Failed stages rerun; changed inputs or missing/changed output
  bytes rerun. An empty output inventory cannot be reused."
  [id inputs output-roots f]
  (if-not *run*
    (f)
    (let [started (System/nanoTime)
          elapsed #(quot (- (System/nanoTime) started) 1000000)
          file (stage-file id)
          previous (read-state file)
          outputs (:outputs previous)
          reason (cond
                   (not= :passed (:status previous)) :no-successful-result
                   (not= inputs (:inputs previous)) :inputs-changed
                   (empty? outputs) :unverifiable-outputs
                   (not= outputs (file-inputs (:output-roots previous))) :outputs-changed-or-missing
                   :else :matching-inputs-and-artifacts)]
      (if (= reason :matching-inputs-and-artifacts)
        (do (record! {:phase id :status :passed :action :reused :reason reason :elapsed-ms (elapsed)})
            (:result previous))
        (do
          (record! {:phase id :status :running :action :executed :reason reason})
          (write-state! file {:status :running :inputs inputs})
          (try
            (let [result (f)
                  roots (vec (output-roots result))
                  outputs (file-inputs roots)]
              (when (or (empty? outputs) (some #{:missing} (vals outputs)))
                (fail! "Successful package stage has missing output artifacts" {:phase id}))
              (write-state! file {:status :passed :inputs inputs :output-roots roots
                                  :outputs outputs :result result})
              (record! {:phase id :status :passed :action :executed :elapsed-ms (elapsed)})
              result)
            (catch Throwable error
              (write-state! file {:status :failed :inputs inputs :error (.getMessage error)})
              (record! {:phase id :status :failed :action :executed :error (.getMessage error)})
              (throw error))))))))

(defn with-generation-lock!
  "Serialize ordinary generation with active resumable proofs. Reentrant for
  the same bound run and its worker callbacks; never creates another worker pool."
  [options f]
  (if (or *run* *generation-lock*)
    (f)
    (let [base (paths/resolve-path (paths/absolute (or (:workspace-root options) (paths/workspace-root)))
                                   "validation-output" "pdfcarton-proof-runs")
          _ (Files/createDirectories base (make-array FileAttribute 0))]
      (with-open [channel (FileChannel/open
                           (paths/resolve-path base "workspace.lock")
                           (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
        (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
          (when-not lock
            (fail! "Another proof owns the workspace generation paths" {}))
          (try
            (binding [*generation-lock* true] (f))
            (finally (.release lock))))))))

(defn pack!
  "Resume a completed independently reproduced package set, never a validator.
  The package implementation receives an invocation-isolated proof directory."
  [options f]
  (if-not *run*
    (with-generation-lock! options #(f options))
    (let [profile (:profile options)
          inputs (production-inputs options)
          id [:packages profile]
          proof-root (paths/resolve-path (:root *run*) "packages"
                                         (util/sha256-text (pr-str profile)))]
      (stage!
       id inputs
       (fn [result]
         (into (into [(:feed result)]
                     (keep #(get-in result [:boundary-report %])) [:edn :markdown])
               (keep :project-root)
               (conj (vec (get-in result [:verification :generation :dependency-emissions]))
                     (get-in result [:verification :generation :emission]))))
       #(f (assoc options :proof-root proof-root))))))

(defn with-run!
  "Execute or resume a named local proof. A workspace lock serializes shared
  generation paths; unique run roots preserve prior feeds and phase records."
  [{:keys [workspace-root run-id]} f]
  (if-not run-id
    (with-generation-lock! {:workspace-root workspace-root} f)
    (do
      (when-not (and (string? run-id) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,79}" run-id))
        (fail! "Resume run ID must be a simple local name" {:run-id run-id}))
      (let [base (paths/resolve-path (paths/absolute workspace-root) "validation-output" "pdfcarton-proof-runs")
            root (paths/resolve-path base run-id)
            _ (Files/createDirectories root (make-array FileAttribute 0))]
        (with-open [channel (FileChannel/open
                             (paths/resolve-path base "workspace.lock")
                             (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
          (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
            (when-not lock
              (fail! "Another resumable proof owns the workspace generation paths" {:run-id run-id}))
            (try
              (binding [*run* {:run-id run-id :root root
                               :events (atom (vec (:phases (read-state (paths/resolve-path root "summary.edn")))))}]
                (f))
              (finally (.release lock)))))))))
