(ns dripsharp.release-prerequisites-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [dripsharp.release-prerequisites :as prerequisites]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest exact-commit-workflow-properties-are-required
  (let [root (Files/createTempDirectory "release-tools" (make-array FileAttribute 0))
        workflow (.resolve root ".github/workflows/nuget-release.yml")]
    (doseq [p [".github/workflows/nuget-release.yml" "eng/verify-release.sh"
               "eng/pack-release.sh" "eng/validate-release-packages.sh"
               "eng/bundle-release-packages.py" "eng/validate-release-packages.py"]]
      (let [f (.resolve root p)]
        (Files/createDirectories (.getParent f) (make-array FileAttribute 0))
        (spit (str f) "fixture")
        (.setExecutable (.toFile f) true)))
    (let [valid "jobs:\n  prepare:\n    env:\n      RepositoryCommit: ${{ github.sha }}\n      SourceRevisionId: ${{ github.sha }}\n  publish:\n"]
      (spit (str workflow) valid)
      (is (= 2 (count (:exact-commit-properties (prerequisites/validate-tooling! root)))))
      (doseq [property ["RepositoryCommit" "SourceRevisionId"]]
        (spit (str workflow) (str/replace valid property "MissingProperty"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"triggering product commit"
                              (prerequisites/validate-tooling! root)))))))

(deftest live-checks-respect-unprotected-environments-and-immutable-versions
  (doseq [status ["404" "200" "503"]]
    (let [requests (atom [])
          run! (fn [{:keys [command]}]
                 (swap! requests conj command)
                 {:output (cond
                            (= "curl" (first command)) status
                            (str/ends-with? (last command) "/release") "{\"protection_rules\":[]}"
                            (str/ends-with? (last command) "/permissions") "{\"enabled\":true}"
                            :else "{\"state\":\"active\"}")})
          options {:workspace-root (paths/workspace-root) :run-command! run!
                   :publication {:repository-slug "dripsharp/pdfcarton"
                                 :nuget {:bundle {:package-id "DripSharp.PdfCarton"}
                                         :packages {"DripSharp.PdfCarton" {:version "1.0.0"}}}}}]
      (if (= "404" status)
        (is (true? (:versions-unused (prerequisites/live! options))))
        (is (thrown? clojure.lang.ExceptionInfo (prerequisites/live! options))))
      (is (= 4 (count @requests)))
      (is (not-any? #(some #{"push" "run" "POST"} %) @requests)))))
