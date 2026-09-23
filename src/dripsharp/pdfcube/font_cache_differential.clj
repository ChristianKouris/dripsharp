;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.font-cache-differential
  "Explicit local, fresh-process font-cache comparison. Never run by test discovery."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.maven :as maven]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64 Random]))

(def settings [:unset :empty :spaces :missing :file :invalid :valid])
(def seed 80817)
(def generator-version 1)

(defn scenarios
  "Fixed regressions plus bounded deterministic combinations, generated once."
  []
  (let [random (Random. seed)]
    (vec (distinct
          (concat [[:unset :empty] [:empty :empty] [:valid :empty]
                   [:unset :valid] [:missing :valid] [:unset :file]
                   [:unset :invalid] [:spaces :empty]]
                  (map #(vector % :unset) settings)
                  (map #(vector :unset %) settings)
                  (repeatedly 8 #(vector (nth settings (.nextInt random 7))
                                         (nth settings (.nextInt random 7)))))))))

(defn setting-value [root kind valid-directory]
  (case kind
    :unset nil
    :empty ""
    :spaces " "
    :missing (str (paths/resolve-path root "missing"))
    :file (str (paths/resolve-path root "ordinary-file"))
    :invalid "bad\u0000path"
    :valid (str (paths/resolve-path root valid-directory))))

(defn- encode [value]
  (if (nil? value) "-"
      (.encodeToString (Base64/getEncoder) (.getBytes ^String value "UTF-8"))))

(defn read-trace [path]
  (let [rows (mapv #(str/split % #"\t" -1) (str/split-lines (slurp (str path))))]
    (when (or (empty? rows) (some #(not= 2 (count %)) rows)
              (not= (count rows) (count (set (map first rows)))))
      (throw (ex-info "Malformed font-cache observation" {:path (str path)})))
    (into (sorted-map) rows)))

(defn- assert-equal! [expected actual context]
  (when-not (= expected actual)
    (throw (ex-info "Font-cache differential mismatch"
                    (assoc context :kind :font-cache-mismatch
                           :first-difference
                           (first (for [key (sort (distinct (concat (keys expected) (keys actual))))
                                        :when (not= (get expected key) (get actual key))]
                                    {:observation key :expected (get expected key) :actual (get actual key)})))))))

(defn assert-contract!
  "Keep JDK 25 empty-path drift explicit; never normalize it into a match."
  [scenario java17 java25 dotnet context]
  (assert-equal! java17 dotnet (assoc context :contract :java17-package))
  (assert-equal! {"cache-rows" "1" "cache-font" "LiberationSans"
                  "cache-reloaded" "1" "pdf-created" "true" "pages" "1"
                  "text" (encode "Font cache fallback\n") "substitute" "LiberationSans"}
                 (select-keys java17 ["cache-rows" "cache-font" "cache-reloaded"
                                      "pdf-created" "pages" "text" "substitute"])
                 (assoc context :contract :public-font-behavior))
  (let [[cache home] scenario
        empty-selected? (or (= :empty cache)
                            (and (not (#{:valid :spaces} cache)) (= :empty home)))]
    (if empty-selected?
      (assert-equal!
       (-> java17
           (dissoc "cache-rows" "cache-font" "cache-reloaded")
           (assoc "selected" (str File/separator ".pdfbox.cache")
                  "cache-write" "guarded-outside-scenario"
                  "cache-present" "false"
                  "side-effects" "font.pdf;ordinary-file"))
       java25 (assoc context :contract :jdk25-empty-parent-drift))
      (assert-equal! java17 java25 (assoc context :contract :pdfbox-java25)))))

(defn- directory! [path]
  (Files/createDirectories path (make-array FileAttribute 0))
  path)

(defn- copy! [source destination]
  (Files/copy (paths/path source) (paths/path destination)
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- prepare-scenario! [root]
  (harness/clean-directory! root)
  (doseq [name ["temporary" "home" "cache" " "]]
    (directory! (paths/resolve-path root name)))
  (spit (str (paths/resolve-path root "ordinary-file")) "not a directory"))

(defn- existing-package-proof [root feed manifest run-command!]
  (let [version (baseline/package-version :pdfcube "DripSharp.PdfCarton")
        artifact (paths/resolve-path (paths/absolute feed)
                                     (str "DripSharp.PdfCarton." version ".nupkg"))
        input (maven/project-by-id!
               (maven/read-reactor-manifest manifest)
               (str "org.apache.pdfbox:pdfbox:" (baseline/upstream-version :pdfcube)))
        revision (str/trim (:output (run-command!
                                    {:command ["git" "rev-parse" "HEAD"]
                                     :directory (paths/resolve-path root "research/pdfbox")
                                     :timeout-ms 10000})))]
    (when-not (= revision (baseline/upstream-revision :pdfcube))
      (throw (ex-info "PDFBox checkout differs from the pinned oracle" {:revision revision})))
    {:verification {:generation (harness/configuration root revision input)}
     :identity {:id "DripSharp.PdfCarton" :version version
                :sha256 (util/sha256-file artifact) :file (str artifact)}
     :feed (paths/absolute feed)}))

(defn verify!
  "Package and compare locally. package-fn can reuse a package proof produced in
  the same session. package-feed explicitly selects already built local packages
  and a Maven discovery manifest. case-index replays one recorded input pair."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command! case-index package-feed maven-manifest dotnet]
     :or {package-fn packaging/verify-package-consumption! run-command! process/run! dotnet "dotnet"}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         proof (if package-feed
                 (existing-package-proof root package-feed
                                         (or maven-manifest (paths/resolve-path root "target/maven-reactor-inputs-0.tsv"))
                                         run-command!)
                 (package-fn {:workspace-root root :profile "pdfcube-pdfbox" :run-command! run-command!}))
         generation (get-in proof [:verification :generation])
         project-input (:project-input generation)
         out (harness/clean-directory! (paths/resolve-path root "validation-output" "pdfcube-font-cache"))
         consumer (harness/clean-directory! (paths/resolve-path out "consumer"))
         _ (harness/clean-directory! (paths/resolve-path out "packages"))
         classes (harness/clean-directory! (paths/resolve-path out "classes"))
         resolve-path #(paths/absolute (paths/resolve-path root %))
         fixture (resolve-path "research/pdfbox/pdfbox/src/main/resources/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
         java17 (compat/java-tools (compat/read-contract root))
         oracle-home (resolve-path (get-in project-input [:java-toolchain :home]))
         java25 (paths/resolve-path oracle-home "bin" "java")
         dependencies (distinct (map #(resolve-path (:path %)) (:classpath-artifacts project-input)))
         classpath (str/join File/pathSeparator (map str dependencies))
         runtime-classpath (str/join File/pathSeparator
                                     (map str (concat [classes] dependencies
                                                      (map resolve-path (:resource-roots project-input)))))
         deadline (+ (System/nanoTime) (* 10 60 1000000000))
         execute! (fn [command directory timeout]
                    (let [remaining (quot (- deadline (System/nanoTime)) 1000000)]
                      (when-not (pos? remaining)
                        (throw (ex-info "Font-cache campaign exceeded ten minutes"
                                        {:kind :campaign-timeout})))
                      (run-command! {:command (mapv str command) :directory directory
                                     :timeout-ms (min timeout remaining)
                                     :unset-environment ["JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS"]})))
         project (paths/resolve-path consumer "FontCacheProbe.csproj")
         package-version (get-in proof [:identity :version])
         all-cases (scenarios)
         indices (if (some? case-index) [case-index] (vec (range (count all-cases))))
         results (atom {})
         work (.toPath (.getCanonicalFile
                        (.toFile (Files/createTempDirectory "pdfcarton-font-cache-" (make-array FileAttribute 0)))))]
     (try
       (when-not (every? #(and (integer? %) (<= 0 %) (< % (count all-cases))) indices)
         (throw (ex-info "Invalid font-cache replay index" {:case-index case-index})))
       (spit (str (paths/resolve-path out "inputs.edn"))
             (pr-str {:seed seed :generator-version generator-version :scenarios all-cases
                      :fixture (str fixture) :fixture-sha256 (util/sha256-file fixture)
                      :package (:identity proof)
                      :source {:version (baseline/upstream-version :pdfcube)
                               :revision (baseline/upstream-revision :pdfcube)}}))
       (let [versions {:java-compat (:output (execute! [(:java java17) "-version"] root 10000))
                       :pdfbox-oracle (:output (execute! [java25 "-version"] root 10000))
                       :dotnet (:output (execute! [dotnet "--info"] root 10000))}]
         (spit (str (paths/resolve-path out "versions.edn")) (pr-str versions)))
       (execute! (concat [(:javac java17) "-J-Xmx28g" "--release" "17" "-encoding" "UTF-8"
                          "-classpath" classpath "-d" classes]
                         (map resolve-path (concat (:production-sources project-input)
                                                   (:generated-production-sources project-input)))
                         [(resolve-path "validation/pdfcube-font-cache/FontCacheOracle.java")]) root 300000)
       (copy! (resolve-path "validation/pdfcube-font-cache/Program.cs") (paths/resolve-path consumer "Program.cs"))
       (copy! (resolve-path "targets/pdfcube/consumer-tests/ReleaseSmokePdfTests.cs")
              (paths/resolve-path consumer "ReleaseSmokePdfTests.cs"))
       (spit (str project)
             (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                  "<OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework>"
                  "<ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable>"
                  "<DefineConstants>PDFCARTON_PACKAGE_PROBE</DefineConstants>"
                  "<RestorePackagesPath>" (paths/resolve-path out "packages") "</RestorePackagesPath>"
                  "</PropertyGroup><ItemGroup><PackageReference Include=\"DripSharp.PdfCarton\" Version=\""
                  package-version "\" /></ItemGroup></Project>"))
       (execute! [dotnet "restore" project "--source" (:feed proof) "--ignore-failed-sources"
                  "-p:NuGetAudit=false"] consumer 180000)
       (execute! [dotnet "build" project "--no-restore" "--nologo" "-warnaserror"] consumer 180000)
       (let [dll (paths/resolve-path consumer "bin" "Debug" "net10.0" "FontCacheProbe.dll")
             commands {:java17 [(:java java17) "-Xmx28g" "-cp" runtime-classpath "FontCacheOracle"]
                       :java25 [java25 "-Xmx28g" "-cp" runtime-classpath "FontCacheOracle"]
                       :dotnet [dotnet dll]}]
         ;; Existing regression executes once, unchanged, in its own process.
         (execute! [dotnet dll "--smoke"] consumer 120000)
         (doseq [[order selected] [[:forward indices] [:reverse (reverse indices)]]
                 index selected]
           (let [[cache home :as scenario] (nth all-cases index)
                 input (paths/resolve-path out (str "input-" index ".tsv"))
                 context {:case index :input scenario :order order
                          :seed seed :generator-version generator-version
                          :replay (str "(dripsharp.pdfcube.font-cache-differential/verify! "
                                       (pr-str (cond-> {:case-index index :dotnet (str dotnet)}
                                                 package-feed (assoc :package-feed (str (paths/absolute package-feed)))
                                                 maven-manifest (assoc :maven-manifest (str (paths/absolute maven-manifest)))))
                                       ")")}
                 _ (spit (str input) (str (encode (setting-value work cache "cache")) "\t"
                                          (encode (setting-value work home "home"))))
                 observations
                 (into {}
                       (for [side [:java17 :java25 :dotnet]]
                         (let [trace (paths/resolve-path out (str (name order) "-" index "-" (name side) ".tsv"))]
                           (prepare-scenario! work)
                           (try
                             (let [result (execute! (concat (get commands side) [work input fixture trace]) work 30000)]
                               (spit (str trace ".log") (:output result)))
                             (catch clojure.lang.ExceptionInfo error
                               (spit (str trace ".log") (:output (ex-data error)))
                               (spit (str (paths/resolve-path out "failure.edn"))
                                     (pr-str (merge context {:side side :outcome (:kind (ex-data error))
                                                            :trace (str trace)})))
                               (throw error)))
                           [side (read-trace trace)])))]
             (try
               (assert-contract! scenario (:java17 observations) (:java25 observations) (:dotnet observations) context)
               (if (= order :forward)
                 (swap! results assoc index observations)
                 (doseq [side [:java17 :java25 :dotnet]]
                   (assert-equal! (get-in @results [index side]) (get observations side)
                                  (assoc context :contract :order-independence :side side))))
               (catch clojure.lang.ExceptionInfo error
                 (spit (str (paths/resolve-path out "failure.edn")) (pr-str (ex-data error)))
                 (throw error)))))
         (let [summary {:cases (count indices) :processes (* 6 (count indices))
                        :orders [:forward :reverse] :existing-smoke :passed
                        :seed seed :generator-version generator-version
                        :source {:version (baseline/upstream-version :pdfcube)
                                 :revision (baseline/upstream-revision :pdfcube)}
                        :package (:identity proof) :proof-root (str out)}]
           (spit (str (paths/resolve-path out "summary.edn")) (pr-str summary))
           (println "Font-cache differential passed:" (pr-str summary))
           summary))
       (catch Exception error
         (let [failure (paths/resolve-path out "failure.edn")]
           (when-not (paths/regular-file? failure)
             (spit (str failure)
                   (pr-str {:outcome (or (:kind (ex-data error)) :harness-error)
                            :message (.getMessage error)
                            :output (:output (ex-data error))}))))
         (throw error))
       (finally
         (harness/clean-directory! work)
         (Files/delete work))))))
