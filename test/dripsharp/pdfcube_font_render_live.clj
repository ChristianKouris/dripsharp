;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube-font-render-live
  "Explicit local perturbation check using the runners from a successful run."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.font-render-cases :as cases]
            [dripsharp.pdfcube.font-render-differential :as differential]
            [dripsharp.pdfcube.pdfbox-rendering-differential :as rendering]
            [dripsharp.process :as process])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn verify!
  "Reuses immutable successful-run binaries, changes only the .NET writer's
  recorded text or position, and requires exact semantic and image differences."
  [run-directory]
  (let [root (paths/absolute run-directory)
        summary (edn/read-string (slurp (str (paths/resolve-path root "summary.edn"))))
        commands (edn/read-string (slurp (str (paths/resolve-path root "commands.edn"))))
        assets (paths/resolve-path (paths/workspace-root) "research/pdfbox")
        original (cases/read-record (paths/resolve-path root "case-0/input.edn"))
        record (assoc original :pages [(first (:pages original))])]
    (assert (= :passed (:status summary)))
    (assert (= rendering/pixel-tolerances (:tolerances summary)))
    (mapv
     (fn [[fault alter observation]]
       (let [out (Files/createTempDirectory root (str "fault-" (name fault) "-")
                                            (make-array FileAttribute 0))
             exchange (paths/resolve-path out "exchange")
             input (paths/resolve-path out "input.tsv")
             changed (paths/resolve-path out "perturbed.tsv")
             mutated (alter record)]
         (spit (str input) (str (cases/wire record) "\n"))
         (spit (str changed) (str (cases/wire mutated) "\n"))
         (cases/write-record! (paths/resolve-path out "input.edn") record)
         (cases/write-record! (paths/resolve-path out "perturbed.edn") mutated)
         (doseq [mode ["write" "observe"] side [:java :dotnet]]
           (let [result (process/run!
                         {:command (mapv str (concat (get commands side)
                                                     [mode (if (and (= side :dotnet) (= mode "write")) changed input)
                                                      assets exchange (paths/resolve-path out (name side))]))
                          :directory out :timeout-ms 60000})]
             (spit (str (paths/resolve-path out (str (name side) "-" mode ".log"))) (:output result))))
         (let [java (cases/read-trace (paths/resolve-path out "java/trace.tsv"))
               dotnet (cases/read-trace (paths/resolve-path out "dotnet/trace.tsv"))
               key-a (str "java/p0/" observation) key-b (str "dotnet/p0/" observation)
               error (try (differential/compare-output! out ["java" "dotnet"] 1 true)
                          nil (catch clojure.lang.ExceptionInfo e e))
               comparison (edn/read-string (slurp (str (paths/resolve-path out "comparison.edn"))))]
           ;; Both readers agree on each actual PDF. The writers must differ.
           (assert (= java dotnet))
           (assert (not= (get java key-a) (get java key-b)))
           (assert (= :behavioral-mismatch (:kind (ex-data error))))
           (assert (some #(pos? (get-in % [:pixels :maximum-channel-error] 0)) (:images comparison)))
           (assert (some #(let [path (get-in % [:diff :diff])]
                            (and path (paths/regular-file? path))) (:images comparison)))
           (assert (= (:tolerances summary) rendering/pixel-tolerances))
           (let [result {:fault fault :detected true :observation observation
                         :tolerances rendering/pixel-tolerances :proof-root (str out)}]
             (spit (str (paths/resolve-path out "fault-result.edn")) (pr-str result))
             (println (pr-str result)) result))))
     [[:text #(assoc-in % [:pages 0 :text] "cafe Z") "text-false"]
      [:placement #(update-in % [:pages 0 :x] + 12) "glyphs-false"]])))
