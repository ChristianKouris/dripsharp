;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.page-form-workflow-differential
  "Explicit local page manipulation/form-value campaign; no discovery-time work."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.differential-campaign :as campaign]
            [dripsharp.harness :as harness]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.page-form-workflows :as workflows]
            [dripsharp.pdfcube.pdfbox-interaction-differential :as interaction]
            [dripsharp.pdfcube.pdfbox-manipulation-differential :as manipulation]
            [dripsharp.process :as process]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- directory! [path]
  (Files/createDirectories (paths/path path) (make-array FileAttribute 0))
  (paths/path path))

(defn- copy! [from to]
  (Files/copy (paths/path from) (paths/path to)
              (into-array StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING])))

(defn- existing-proof [root feed manifest run-command!]
  (let [version (baseline/package-version :pdfcube "DripSharp.PdfCarton")
        artifact (paths/resolve-path (paths/absolute feed)
                                     (str "DripSharp.PdfCarton." version ".nupkg"))
        input (maven/project-by-id!
               (maven/read-reactor-manifest manifest)
               (str "org.apache.pdfbox:pdfbox:"
                    (baseline/upstream-version :pdfcube)))
        revision (str/trim
                  (:output
                   (run-command!
                    {:command ["git" "rev-parse" "HEAD"]
                     :directory (paths/resolve-path root "research/pdfbox")
                     :timeout-ms 10000})))]
    (when-not (= revision (baseline/upstream-revision :pdfcube))
      (throw (ex-info "Oracle checkout differs from pinned PDFBox"
                      {:revision revision})))
    {:verification {:generation (harness/configuration root revision input)}
     :identity {:id "DripSharp.PdfCarton"
                :version version
                :sha256 (util/sha256-file artifact)}
     :feed (paths/absolute feed)}))

(defn- prepare! [root out proof dotnet execute!]
  (let [input (get-in proof [:verification :generation :project-input])
        resolve-path #(paths/absolute (paths/resolve-path root %))
        home (resolve-path (get-in input [:java-toolchain :home]))
        suffix (if (str/starts-with?
                    (str/lower-case (System/getProperty "os.name")) "windows")
                 ".exe" "")
        java (paths/resolve-path home "bin" (str "java" suffix))
        javac (paths/resolve-path home "bin" (str "javac" suffix))
        dependencies (distinct
                      (map #(resolve-path (:path %))
                           (:classpath-artifacts input)))
        classes (directory! (paths/resolve-path out "classes"))
        consumer (directory! (paths/resolve-path out "consumer"))
        project (paths/resolve-path consumer "PageFormWorkflowProbe.csproj")
        source (paths/resolve-path root "validation/pdfcube-pdfbox-manipulation")
        classpath (str/join File/pathSeparator (map str dependencies))
        runtime-classpath
        (str/join File/pathSeparator
                  (map str
                       (concat [classes] dependencies
                               (map resolve-path (:resource-roots input)))))
        versions {:java-release (get-in input [:java-toolchain :release])
                  :java (:output (execute! [java "-version"] root 10000))
                  :dotnet (:output (execute! [dotnet "--info"] root 10000))}]
    (spit (str (paths/resolve-path out "versions.edn")) (pr-str versions))
    (execute!
     (concat [javac "-J-Xmx28g" "--release"
              (get-in input [:java-toolchain :release])
              "-encoding" "UTF-8" "-classpath" classpath "-d" classes]
             (map resolve-path
                  (concat (:production-sources input)
                          (:generated-production-sources input)))
             [(paths/resolve-path source "PageFormWorkflowOracle.java")])
     root 600000)
    (copy! (paths/resolve-path source "PageFormWorkflowProbe.cs")
           (paths/resolve-path consumer "PageFormWorkflowProbe.cs"))
    (spit
     (str project)
     (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
          "<OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework>"
          "<Nullable>enable</Nullable><RestorePackagesPath>"
          (paths/resolve-path out "packages")
          "</RestorePackagesPath></PropertyGroup><ItemGroup>"
          "<PackageReference Include=\"DripSharp.PdfCarton\" Version=\""
          (get-in proof [:identity :version])
          "\" /></ItemGroup></Project>"))
    (execute! [dotnet "restore" project "--source" (:feed proof)
               "--source" (paths/resolve-path
                           (System/getProperty "user.home") ".nuget/packages")
               "--ignore-failed-sources" "-p:NuGetAudit=false"]
              consumer 180000)
    (let [version (get-in proof [:identity :version])
          restored (paths/resolve-path
                    out "packages/dripsharp.pdfcarton" version
                    (str "dripsharp.pdfcarton." version ".nupkg"))]
      (when-not (= (get-in proof [:identity :sha256])
                   (util/sha256-file restored))
        (throw (ex-info "Restored package differs from selected feed artifact"
                        {:kind :package-mismatch}))))
    (execute! [dotnet "build" project "--no-restore" "--nologo" "-warnaserror"]
              consumer 180000)
    {:java [java "-Xmx28g" "-cp" runtime-classpath
            "PageFormWorkflowOracle"]
     :dotnet [dotnet (paths/resolve-path
                      consumer "bin/Debug/net10.0/PageFormWorkflowProbe.dll")]}))

(defn- evaluate! [out commands execute! workflow]
  (directory! out)
  (let [wire (paths/resolve-path out "input.tsv")
        exchange (directory! (paths/resolve-path out "exchange"))]
    (workflows/write-record! (paths/resolve-path out "input.edn") workflow)
    (spit (str wire) (str (workflows/wire workflow) "\n"))
    (let [traces
          (into
           {}
           (for [[side runtime mode]
                 [[:java :java "write"]
                  [:dotnet :dotnet "write"]
                  [:java-cross :java "cross"]
                  [:dotnet-cross :dotnet "cross"]]
                 :let [trace (paths/resolve-path out (str (name side) ".tsv"))]]
             (do
               (execute! (concat (get commands runtime)
                                 [mode wire exchange trace])
                         out 60000)
               [side (workflows/read-trace trace)])))
          difference (workflows/compare-traces workflow traces)]
      {:difference difference
       :observations (reduce + (map count (vals traces)))})))

(defn- baseline! [root proof run-command!]
  (when-not (:consumer-root proof)
    (throw (ex-info
            "Existing package-feed mode has no reusable baseline consumer; run the manipulation and interaction baselines separately or set :baseline? false"
            {:kind :baseline-consumer-unavailable})))
  {:manipulation
   (manipulation/verify! {:workspace-root root
                          :package-fn (fn [_] proof)
                          :run-command! run-command!})
   :interaction
   (interaction/verify! {:workspace-root root
                         :package-fn (fn [_] proof)
                         :run-command! run-command!})})

(defn verify!
  "Builds fixed runners once, optionally checks existing affected baselines, then
  runs three fixed templates and a bounded generated sample. Expensive work is
  explicit and local. Every run retains inputs, cross-open traces and replay."
  ([] (verify! {}))
  ([{:keys [workspace-root package-feed maven-manifest package-fn dotnet seed
            case-count replay-file output-directory max-ms shrink-attempts
            run-command! baseline?]
     :or {dotnet "dotnet"
          seed 20
          case-count 3
          max-ms 900000
          shrink-attempts 12
          output-directory "validation-output/page-form-workflows"
          run-command! process/run!
          baseline? true
          package-fn #(target-execution/package! (assoc % :target :pdfcube))}}]
   (when-not (and (integer? seed) (nat-int? case-count) (<= case-count 100)
                  (pos-int? max-ms) (<= max-ms 3600000)
                  (nat-int? shrink-attempts) (<= shrink-attempts 100))
     (throw (ex-info "Invalid campaign bounds" {:kind :invalid-options})))
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         budget (campaign/process-budget
                 run-command! (+ 40 (* 4 (+ case-count shrink-attempts 4))) max-ms)
         run! (:run-command! budget)
         out (Files/createTempDirectory
              (directory! (paths/resolve-path root output-directory))
              "run-" (make-array FileAttribute 0))
         log-index (atom 0)
         execute!
         (fn [command directory timeout]
           (let [log (paths/resolve-path
                      out (str "process-" (swap! log-index inc) ".log"))]
             (try
               (let [result (run! {:command (mapv str command)
                                   :directory directory
                                   :timeout-ms timeout})]
                 (spit (str log) (:output result))
                 result)
               (catch Exception error
                 (spit (str log)
                       (or (:output (ex-data error)) (.getMessage error)))
                 (throw error)))))
         fixed-files
         (mapv #(paths/resolve-path
                 root "validation/pdfcube-pdfbox-manipulation" %)
               ["split-and-merge.edn" "import-and-reorder.edn"
                "set-form-value-save-reopen.edn"])
         records (if replay-file
                   [(workflows/read-record replay-file)]
                   (into (mapv workflows/read-record fixed-files)
                         (map workflows/generate
                              (range seed (+ seed case-count)))))
         replay
         (fn [file]
           (str "(require 'dripsharp.pdfcube.page-form-workflow-differential) "
                "(dripsharp.pdfcube.page-form-workflow-differential/verify! "
                (pr-str
                 (cond-> {:replay-file (str file)
                          :dotnet (str dotnet)
                          :baseline? false
                          :shrink-attempts 0}
                   package-feed
                   (assoc :package-feed (str (paths/absolute package-feed)))
                   maven-manifest
                   (assoc :maven-manifest
                          (str (paths/absolute maven-manifest)))))
                ")"))]
     (try
       (let [proof
             (if package-feed
               (existing-proof
                root package-feed
                (or maven-manifest
                    (paths/resolve-path root "target/maven-reactor-inputs-0.tsv"))
                run!)
               (package-fn {:workspace-root root
                            :profile "pdfcube-pdfbox"
                            :run-command! run!}))
             source {:version (baseline/upstream-version :pdfcube)
                     :revision (baseline/upstream-revision :pdfcube)}
             _ (spit (str (paths/resolve-path out "inputs.edn"))
                     (pr-str {:generator-version workflows/generator-version
                              :seed seed
                              :workflows records
                              :package (:identity proof)
                              :source source}))
             baseline (when baseline? (baseline! root proof run!))
             commands (prepare! root out proof dotnet execute!)
             results
             (mapv
              (fn [index workflow]
                (let [directory (directory!
                                 (paths/resolve-path out (str "case-" index)))
                      input (paths/resolve-path directory "input.edn")
                      replay-file (paths/resolve-path directory "replay.clj")
                      _ (spit (str replay-file) (replay input))
                      result
                      (try
                        (evaluate! directory commands execute! workflow)
                        (catch Exception error
                          (throw
                           (ex-info (.getMessage error)
                                    (merge (ex-data error)
                                           {:case index
                                            :input (str input)
                                            :replay (str replay-file)})
                                    error))))]
                  (when-let [failure (:difference result)]
                    (spit (str (paths/resolve-path out "failure.edn"))
                          (pr-str {:kind :behavioral-mismatch
                                   :case index
                                   :first-difference failure}))
                    (let [attempt (atom 0)
                          shrunk
                          (workflows/shrink!
                           workflow failure
                           (fn [candidate remaining]
                             (reset! (:evaluation-deadline budget)
                                     (+ (System/nanoTime) (* remaining 1000000)))
                             (try
                               (:difference
                                (evaluate!
                                 (paths/resolve-path
                                  out (str "shrink-" (swap! attempt inc)))
                                 commands execute! candidate))
                               (catch Exception _ nil)
                               (finally
                                 (reset! (:evaluation-deadline budget)
                                         (:deadline budget)))))
                           {:max-attempts shrink-attempts :max-ms 60000})
                          reduced (paths/resolve-path out "reduced.edn")]
                      (workflows/write-record! reduced (:workflow shrunk))
                      (spit (str (paths/resolve-path out "shrink.edn"))
                            (pr-str (dissoc shrunk :workflow)))
                      (spit (str (paths/resolve-path out "replay.clj"))
                            (replay reduced)))
                    (throw (ex-info "Page/form workflow mismatch"
                                    {:kind :behavioral-mismatch
                                     :first-difference failure
                                     :proof-root (str out)})))
                  result))
              (range) records)
             summary {:status :passed
                      :templates (frequencies (map :template records))
                      :workflows (count results)
                      :observations (reduce + (map :observations results))
                      :baseline (if baseline :passed :not-run)
                      :source source
                      :package (:identity proof)
                      :processes @(:used budget)
                      :proof-root (str out)}]
         (spit (str (paths/resolve-path out "summary.edn"))
               (pr-str summary))
         (println (pr-str summary))
         summary)
       (catch Exception error
         (when-not (paths/regular-file? (paths/resolve-path out "failure.edn"))
           (spit (str (paths/resolve-path out "failure.edn"))
                 (pr-str
                  (merge (select-keys (ex-data error) [:case :input :replay])
                         {:kind (or (:kind (ex-data error)) :harness-error)
                          :message (.getMessage error)}))))
         (throw error))))))
