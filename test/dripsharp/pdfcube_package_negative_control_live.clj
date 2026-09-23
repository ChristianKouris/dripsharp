(ns dripsharp.pdfcube-package-negative-control-live
  "Explicit local public-package negative control; excluded from *_test.clj discovery."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.file FileVisitOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private cached-consumer
  "validation-output/pdfcube-font-cache/consumer")

(def ^:private canonical-source
  (str "using System;\n"
       "using DripSharp.PdfCarton.IO;\n"
       "internal static class Program\n"
       "{\n"
       "    private static int Main()\n"
       "    {\n"
       "        using var input = new RandomAccessReadBuffer(\n"
       "            new sbyte[] { 0, 127, -128, -1 });\n"
       "        var observed = new int[5];\n"
       "        for (var index = 0; index < observed.Length; index++)\n"
       "            observed[index] = input.Read();\n"
       "        Console.WriteLine(string.Join(\",\", observed));\n"
       "        return 0;\n"
       "    }\n"
       "}\n"))

(def ^:private signed-byte-fault-source
  (str/replace
   canonical-source
   "        Console.WriteLine(string.Join(\",\", observed));\n"
   (str "        observed[3] = unchecked((sbyte)observed[3]);\n"
        "        Console.WriteLine(string.Join(\",\", observed));\n")))

(defn- copy-tree! [^Path source ^Path destination]
  (with-open [entries (Files/walk source (make-array FileVisitOption 0))]
    (doseq [^Path entry (iterator-seq (.iterator entries))]
      (let [target (.resolve destination (.relativize source entry))]
        (if (Files/isDirectory entry (make-array java.nio.file.LinkOption 0))
          (Files/createDirectories target (make-array FileAttribute 0))
          (Files/copy entry target
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))))
  destination)

(defn- write-source! [^Path project source]
  (Files/writeString (paths/resolve-path project "Program.cs") source
                     (make-array OpenOption 0)))

(defn- run-consumer! [^Path project source]
  (write-source! project source)
  (process/run!
   {:command ["dotnet" "build" "FontCacheProbe.csproj" "--nologo"
              "--configuration" "Release" "--verbosity:minimal"
              "--no-restore" "--no-incremental" "-warnaserror"]
    :directory project :timeout-ms 300000})
  (-> (process/run!
       {:command ["dotnet" "--roll-forward" "LatestPatch"
                  (str (paths/resolve-path
                        project "bin" "Release" "net10.0"
                        "FontCacheProbe.dll"))]
        :directory project :timeout-ms 30000})
      :output
      str/trim))

(deftest public-package-signed-byte-fault-is-detected-and-restored
  (let [root (paths/workspace-root)
        source (paths/resolve-path root cached-consumer)
        assets-file (paths/resolve-path source "obj" "project.assets.json")
        _ (when-not (paths/regular-file? assets-file)
            (throw
             (ex-info
              (str "Run the local PdfCarton package/font-cache pilot first; "
                   "its package-reference consumer assets are missing")
              {:kind :missing-package-consumer-assets
               :path (str assets-file)})))
        project (copy-tree!
                 source
                 (Files/createTempDirectory
                  "pdfcarton-package-negative-control-"
                  (make-array FileAttribute 0)))
        project-file (slurp (str (paths/resolve-path project
                                                     "FontCacheProbe.csproj")))
        assets (slurp (str (paths/resolve-path project "obj"
                                               "project.assets.json")))
        package-identity
        (second (re-find #"\"(DripSharp[.]PdfCarton/[^\"]+)\"" assets))
        _ (is (str/includes? project-file
                             "<PackageReference Include=\"DripSharp.PdfCarton\""))
        _ (is (string? package-identity))
        baseline (run-consumer! project canonical-source)
        perturbed (run-consumer! project signed-byte-fault-source)
        mismatch (when-not (= baseline perturbed)
                   {:kind :semantic-difference
                    :operation :random-access-read
                    :expected baseline
                    :actual perturbed})
        restored (run-consumer! project canonical-source)]
    (is (= "0,127,128,255,-1" baseline))
    (is (= {:kind :semantic-difference
            :operation :random-access-read
            :expected "0,127,128,255,-1"
            :actual "0,127,128,-1,-1"}
           mismatch))
    (is (= baseline restored))
    (println
     (str "Live package negative control: signed-byte narrowing confused public "
          "RandomAccessReadBuffer byte 255 with EOF and was detected as a "
          "semantic mismatch; restoring the temporary consumer reproduced "
          baseline " from " package-identity "."))))
