;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.font-render-differential
  "Explicit local generated font/text and CPU rendering comparison."
  (:require [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.differential-campaign :as campaign]
            [dripsharp.harness :as harness]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.font-render-cases :as cases]
            [dripsharp.pdfcube.pdfbox-rendering-differential :as rendering]
            [dripsharp.process :as process]
            [dripsharp.target-execution :as target-execution]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
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
        project (paths/resolve-path consumer "FontRenderProbe.csproj")
        source (paths/resolve-path root "validation/pdfcube-font-render")
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
             [(paths/resolve-path source "FontRenderOracle.java")])
     root 600000)
    (copy! (paths/resolve-path source "FontRenderProbe.cs")
           (paths/resolve-path consumer "FontRenderProbe.cs"))
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
    {:java [java "-Xmx28g" "-Djava.awt.headless=true" "-cp" runtime-classpath
            "FontRenderOracle"]
     :dotnet [dotnet (paths/resolve-path
                      consumer "bin/Debug/net10.0/FontRenderProbe.dll")]}))

(defn- asset-hashes [root record]
  (into (sorted-map)
        (for [font (set (map :font (:pages record)))
              :let [path (cases/fonts font)]]
          [path (util/sha256-file (paths/resolve-path root path))])))

(defn- image-diff!
  "Retains a magnified-color difference image cropped to changed pixels."
  [expected actual output]
  (let [a (ImageIO/read (.toFile (paths/path expected)))
        b (ImageIO/read (.toFile (paths/path actual)))
        width (max (.getWidth a) (.getWidth b))
        height (max (.getHeight a) (.getHeight b))
        image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        pixel (fn [^BufferedImage img x y]
                (if (and (< x (.getWidth img)) (< y (.getHeight img)))
                  (.getRGB img x y) 0))
        bounds
        (reduce
         (fn [[left top right bottom :as bounds] [x y]]
           (let [av (pixel a x y) bv (pixel b x y)
                 error (reduce max
                               (for [shift [0 8 16 24]]
                                 (Math/abs (long (- (bit-and 255 (bit-shift-right av shift))
                                                    (bit-and 255 (bit-shift-right bv shift)))))))]
             (.setRGB image x y
                      (unchecked-int (bit-or 0xff000000
                                             (bit-shift-left (min 255 (* 4 error)) 16))))
             (if (= av bv) bounds
                 [(min left x) (min top y) (max right x) (max bottom y)])))
         [width height -1 -1]
         (for [y (range height) x (range width)] [x y]))]
    (when (<= 0 (nth bounds 2))
      (let [[left top right bottom] bounds]
        (ImageIO/write (.getSubimage image left top (inc (- right left)) (inc (- bottom top)))
                       "png" (.toFile (paths/path output)))))
    {:bounds bounds :diff (str output)}))

(def page-observations
  ["structure" "text-false" "text-true" "codepoints-false" "codepoints-true"
   "positions-false" "positions-true" "glyphs-false" "glyphs-true" "fonts" "image"])

(defn validate-observations!
  "Rejects missing, extra, or vacuous observations before comparing runtimes."
  [trace ids page-count generated?]
  (let [expected
        (set (concat (map #(str % "/pages") ids)
                     (for [id ids index (range page-count)
                           key (cond-> page-observations generated? (conj "content"))]
                       (str id "/p" index "/" key))))]
    (when-not (= expected (set (keys trace)))
      (throw (ex-info "Incomplete font/render observation trace"
                      {:kind :invalid-observations :expected expected :actual (set (keys trace))})))
    (doseq [id ids]
      (when (and generated? (not= (str page-count) (get trace (str id "/pages"))))
        (throw (ex-info "Generated page count differs" {:kind :behavioral-mismatch}))))
    (doseq [id ids index (range page-count)]
      (when (str/blank? (get trace (str id "/p" index "/glyphs-false")))
        (throw (ex-info "Expected rendered glyphs"
                        {:kind :behavioral-mismatch :observation (str id "/p" index "/glyphs-false")}))))
    trace))

(defn compare-output!
  "Exact extraction, glyph order/float bits, structure; unchanged raster tolerances.
  Compares both readers and both writers, retaining every image and focused diffs."
  [out ids page-count generated?]
  (let [roots (into {} (for [side [:java :dotnet]]
                         [side (paths/resolve-path out (name side))]))
        traces (into {} (for [[side root] roots]
                          [side (validate-observations!
                                 (cases/read-trace (paths/resolve-path root "trace.tsv"))
                                 ids page-count generated?)]))
        writer-view (fn [trace id]
                      (into (sorted-map)
                            (for [[key value] trace :when (str/starts-with? key (str id "/"))]
                              [(subs key (inc (count id))) value])))
        semantic
        (or (some-> (cases/first-difference (:java traces) (:dotnet traces))
                    (assoc :comparison :readers))
            (when generated?
              (some (fn [side]
                      (some-> (cases/first-difference
                               (writer-view (get traces side) "java")
                               (writer-view (get traces side) "dotnet"))
                              (assoc :comparison [:writers side])))
                    [:java :dotnet])))
        pairs (concat (for [id ids] [:java id :dotnet id])
                      (when generated?
                        [[:java "java" :java "dotnet"]
                         [:dotnet "java" :dotnet "dotnet"]]))
        images
        (mapv
         (fn [[reader-a writer-a reader-b writer-b index]]
           (let [id-a (str writer-a "-p" index) id-b (str writer-b "-p" index)
                 a (paths/resolve-path (get roots reader-a) id-a)
                 b (paths/resolve-path (get roots reader-b) id-b)
                 comparison [reader-a writer-a reader-b writer-b index]
                 result
                 (try
                   {:pixels (rendering/compare-rgba (str a ".rgba") (str b ".rgba"))}
                   (catch clojure.lang.ExceptionInfo error
                     {:failure (ex-data error)}))]
             (cond-> (assoc result :comparison comparison)
               (or semantic (:failure result))
               (assoc :diff
                      (image-diff!
                       (str a ".png") (str b ".png")
                       (paths/resolve-path out (str "diff-" (str/join "-" (map name (take 4 comparison)))
                                                   "-p" index ".png")))))))
         (for [pair pairs index (range page-count)] (conj (vec pair) index)))
        failure (or semantic (first (filter :failure images)))
        result {:observations (reduce + (map count (vals traces)))
                :images images :first-difference failure}]
    (spit (str (paths/resolve-path out "comparison.edn")) (pr-str result))
    (when failure
      (throw (ex-info "Font/text/rendering behavior differs"
                      {:kind :behavioral-mismatch :first-difference failure :proof-root (str out)})))
    result))

(defn verify!
  "Runs a fixed eight-page regression, optional original fixtures and bounded
  generated cases locally. A saved input is replayed without calling the generator."
  ([] (verify! {}))
  ([{:keys [workspace-root package-feed maven-manifest package-fn dotnet seed
            case-count replay-file output-directory max-ms run-command! fixtures?]
     :or {dotnet "dotnet" seed 22 case-count 4 max-ms 900000 fixtures? true
          output-directory "validation-output/font-render"
          run-command! process/run!
          package-fn #(target-execution/package! (assoc % :target :pdfcube))}}]
   (when-not (and (integer? seed) (nat-int? case-count) (<= case-count 100)
                  (pos-int? max-ms) (<= max-ms 3600000))
     (throw (ex-info "Invalid campaign bounds" {:kind :invalid-options})))
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         assets (paths/resolve-path root "research/pdfbox")
         out (Files/createTempDirectory
              (directory! (paths/resolve-path root output-directory))
              "run-" (make-array FileAttribute 0))
         budget (campaign/process-budget run-command! (+ 40 (* 4 (inc case-count))) max-ms)
         run! (:run-command! budget)
         log-index (atom 0)
         execute! (fn [command directory timeout]
                    (let [log (paths/resolve-path out (str "process-" (swap! log-index inc) ".log"))]
                      (try
                        (let [result (run! {:command (mapv str command) :directory directory :timeout-ms timeout})]
                          (spit (str log) (:output result)) result)
                        (catch Exception error
                          (spit (str log) (or (:output (ex-data error)) (.getMessage error)))
                          (throw error)))))
         records (if replay-file [(cases/read-record replay-file)]
                     (into [(cases/regression)] (map cases/generate (range seed (+ seed case-count)))))
         replay (fn [file]
                  (str "(require 'dripsharp.pdfcube.font-render-differential)\n"
                       "(dripsharp.pdfcube.font-render-differential/verify! "
                       (pr-str (cond-> {:replay-file (str file) :fixtures? false :dotnet (str dotnet)}
                                 package-feed (assoc :package-feed (str (paths/absolute package-feed)))
                                 maven-manifest (assoc :maven-manifest (str (paths/absolute maven-manifest))))) ")\n"))]
     (try
       (let [proof (if package-feed
                     (existing-proof root package-feed
                                     (or maven-manifest (paths/resolve-path root "target/maven-reactor-inputs-0.tsv")) run!)
                     (package-fn {:workspace-root root :profile "pdfcube-pdfbox" :run-command! run!}))
             source {:version (baseline/upstream-version :pdfcube) :revision (baseline/upstream-revision :pdfcube)}
             _ (spit (str (paths/resolve-path out "inputs.edn"))
                     (pr-str {:generator-version cases/generator-version :seed seed :records records
                              :package (:identity proof) :source source :tolerances rendering/pixel-tolerances}))
             commands (prepare! root out proof dotnet execute!)
             _ (spit (str (paths/resolve-path out "commands.edn"))
                     (pr-str (update-vals commands #(mapv str %))))
             fixture-result
             (when fixtures?
               (let [dir (directory! (paths/resolve-path out "fixtures"))
                     wire (paths/resolve-path dir "input.tsv")]
                 (spit (str wire) (str/join "\n" (map #(str (:id %) "\t" (:path %)) cases/fixtures)))
                 (spit (str (paths/resolve-path dir "assets.edn"))
                       (pr-str (mapv #(assoc % :sha256 (util/sha256-file (paths/resolve-path assets (:path %)))) cases/fixtures)))
                 (doseq [side [:java :dotnet]]
                   (execute! (concat (get commands side)
                                     ["fixtures" wire assets (paths/resolve-path dir "exchange")
                                      (paths/resolve-path dir (name side))]) root 60000))
                 (compare-output! dir (mapv :id cases/fixtures) 1 false)))
             results
             (mapv
              (fn [index record]
                (let [hashes (asset-hashes assets record)
                      _ (when (and (:assets record) (not= hashes (:assets record)))
                          (throw (ex-info "Replay font assets changed" {:kind :input-asset-mismatch})))
                      record (assoc record :assets hashes)
                      dir (directory! (paths/resolve-path out (str "case-" index)))
                      input (paths/resolve-path dir "input.edn")
                      wire (paths/resolve-path dir "input.tsv")
                      exchange (directory! (paths/resolve-path dir "exchange"))]
                  (cases/write-record! input record)
                  (spit (str wire) (str (cases/wire record) "\n"))
                  (spit (str (paths/resolve-path dir "replay.clj")) (replay input))
                  (doseq [mode ["write" "observe"] side [:java :dotnet]]
                    (execute! (concat (get commands side)
                                      [mode wire assets exchange (paths/resolve-path dir (name side))]) root 60000))
                  (compare-output! dir ["java" "dotnet"] (count (:pages record)) true)))
              (range) records)
             summary {:status :passed :cases (count records) :pages (reduce + (map #(count (:pages %)) records))
                      :original-fixtures (if fixtures? (count cases/fixtures) 0)
                      :observations (reduce + (map :observations (cond-> results fixture-result (conj fixture-result))))
                      :raster-comparisons (reduce + (map #(count (:images %)) (cond-> results fixture-result (conj fixture-result))))
                      :tolerances rendering/pixel-tolerances :package (:identity proof) :source source
                      :processes @(:used budget) :proof-root (str out)}]
         (spit (str (paths/resolve-path out "summary.edn")) (pr-str summary))
         (println (pr-str summary)) summary)
       (catch Exception error
         (spit (str (paths/resolve-path out "failure.edn"))
               (pr-str (merge {:kind :harness-error :message (.getMessage error)} (ex-data error))))
         (throw error))))))
