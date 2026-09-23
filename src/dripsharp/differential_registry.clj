(ns dripsharp.differential-registry
  "Process-free inventory of explicit local differential entry points."
  (:require [clojure.edn :as edn]
            [dripsharp.differential-cases :as cases]
            [dripsharp.differential-generation :as generation]))

(def local-environment
  {"DRIPSHARP_WORKERS" "22"
   "JAVA_TOOL_OPTIONS" "-Xmx28g"})

(defn- recorded-operations []
  (->> (cases/read-cases (slurp "validation/java-compat/cases.tsv"))
       (group-by :family)
       (map (fn [[family rows]]
              [family (vec (sort (set (map :operation rows))))]))
       (into (sorted-map))))

(defn registry
  "Returns commands and selection metadata without running a differential."
  []
  (let [operations (recorded-operations)]
    {:schema-version 1
     :execution :explicit-local-only
     :environment local-environment
     :java-compat
     {:java-release (:java-release (edn/read-string (slurp "validation/java-compat/contract.edn")))
      :fixed-families (vec (keys operations))
      :generated-families generation/generated-families
      :fixed-command
      ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.java-compat-differential"
       "--cases" "validation/java-compat/cases.tsv" "--family" "FAMILY"]
      :campaign-command
      ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.differential-campaign"
       "--family" "FAMILY" "--seed" "1" "--count" "10"
       "--max-operations" "32" "--max-value-units" "32"
       "--max-input-bytes" "65536" "--max-processes" "200"
       "--max-ms" "300000" "--shrink-attempts" "100"
       "--shrink-ms" "30000"]
      :replay-command
      ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.differential-campaign"
       "--replay" "CASE.edn" "--max-processes" "20" "--max-ms" "180000"
       "--shrink-attempts" "0" "--shrink-ms" "1000"]}
     :translated-microprograms
     {:families {"semantics" {:execution :explicit-local-only
                               :generator {:id :semantics-templates :version 1}
                               :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.semantics-microprogram"
                                         "--seed" "19" "--samples" "12"]
                               :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.semantics-microprogram"
                                                    "--samples" "0" "--case" "initialization-0"]}
                 "floating" {:execution :explicit-local-only
                              :generator {:id :floating-boundaries :version 1}
                              :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.floating-microprogram"
                                        "--seed" "73" "--samples" "12"]
                              :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.floating-microprogram"
                                                   "--samples" "0" "--case" "root-round-19"]}
                 "charset" {:execution :explicit-local-only
                             :generator {:id :charset-boundaries :version 1}
                             :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.charset-microprogram"
                                       "--seed" "41" "--samples" "12"]
                             :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.charset-microprogram"
                                                  "--samples" "0" "--case" "stream-split-utf8"]}
                 "collections" {:execution :explicit-local-only
                                :generator {:id :collection-sequences :version 1}
                                :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.collection-microprogram"
                                          "--seed" "31" "--samples" "12"]
                                :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.collection-microprogram"
                                                     "--samples" "0" "--case" "collision-views-2"]}
                 "binary" {:execution :explicit-local-only
                            :generator {:id :binary-templates :version 1}
                            :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.binary-microprogram"
                                      "--seed" "23" "--samples" "12"]
                            :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.binary-microprogram"
                                                 "--samples" "0" "--case" "parse-little"]}
                 "numeric" {:execution :explicit-local-only
                            :generator {:id :numeric-templates :version 1}
                            :command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.numeric-microprogram"
                                      "--seed" "13" "--samples" "32"]
                            :regression-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.numeric-microprogram"
                                                 "--samples" "0" "--case" "saved-byte-value"]}}
      :fixed-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.microprogram"]
      :replay-command ["clojure" "-J-Xmx28g" "-M" "-m" "dripsharp.microprogram"
                       "--replay" "snapshot.edn"]}
     :pdfcarton
     {:oracle-java-release :resolved-from-generated-project-input
      :target-runtime-major
      (get-in (edn/read-string (slurp "targets/pdfcube/target.edn"))
              [:java :runtime-major])
      :target-frameworks {:libraries "netstandard2.0" :hosts "net10.0"}
      :families ["low-level" "document-lifecycle" "manipulation" "font-text"
                 "rendering" "interaction" "security" "printing"]
      :package-command ["clojure" "-J-Xmx28g" "-M:run" "differential"
                        "pdfcube" "pdfcube-pdfbox"]
      :generated-test-commands
      [["dotnet" "test"
        "products/pdfcarton/tests/DripSharp.PdfCarton.ReleaseSmoke/DripSharp.PdfCarton.ReleaseSmoke.csproj"
        "--configuration" "Release"]
       ["dotnet" "test"
        "products/pdfcarton/tests/DripSharp.PdfCarton.Tests/DripSharp.PdfCarton.Tests.csproj"
        "--configuration" "Release"]]}}))

(defn select-family
  "Returns one exact JavaCompat family and whether it supports generation."
  [family]
  (let [operations (get (recorded-operations) family)]
    (when-not operations
      (throw (ex-info "Unknown differential family"
                      {:kind :unknown-differential-family :family family})))
    {:family family
     :operations operations
     :generated? (boolean ((set generation/generated-families) family))
     :execution :explicit-local-only}))

(defn print-registry!
  [family]
  (prn (if family (select-family family) (registry))))
