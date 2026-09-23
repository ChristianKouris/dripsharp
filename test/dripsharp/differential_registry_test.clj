(ns dripsharp.differential-registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.differential-generation :as generation]
            [dripsharp.differential-registry :as registry]
            [dripsharp.paths :as paths]
            [dripsharp.test-runner :as test-runner])
  (:import [java.nio.file Files]))

(deftest registry-is-process-free-local-and-complete
  (let [inventory (registry/registry)
        compat (:java-compat inventory)]
    (is (= :explicit-local-only (:execution inventory)))
    (is (= {"DRIPSHARP_WORKERS" "22" "JAVA_TOOL_OPTIONS" "-Xmx28g"}
           (:environment inventory)))
    (is (= 17 (:java-release compat)))
    (is (= 17 (get-in inventory [:pdfcarton :target-runtime-major])))
    (is (= :resolved-from-generated-project-input
           (get-in inventory [:pdfcarton :oracle-java-release])))
    (is (= generation/generated-families (:fixed-families compat)))
    (is (= generation/generated-families (:generated-families compat)))
    (doseq [flag ["--seed" "--count" "--max-operations" "--max-value-units"
                  "--max-input-bytes" "--max-processes" "--max-ms"
                  "--shrink-attempts" "--shrink-ms"]]
      (is (some #{flag} (:campaign-command compat)) flag))
    (is (= true (:generated? (registry/select-family "file"))))
    (is (= true (:generated? (registry/select-family "byte-buffer"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/select-family "not-registered")))))

(deftest live-workloads-are-not-default-clojure-tests
  (let [discovered (set (#'test-runner/all-test-namespaces))]
    (is (contains? discovered 'dripsharp.differential-registry-test))
    (doseq [live '[dripsharp.differential-generation-live
                   dripsharp.java-compat-cases-live
                   dripsharp.microprogram-live]]
      (is (not (contains? discovered live)) (str live)))))

(deftest hosted-workflows-do-not-admit-new-local-campaigns
  (let [root (paths/absolute ".github/workflows")
        files (with-open [stream (Files/list root)] (vec (.toArray stream)))
        forbidden ["dripsharp.differential-campaign"
                   "dripsharp.differential-generation-live"
                   "dripsharp.java-compat-cases-live"
                   "dripsharp.microprogram-live"
                   "products/pdfcarton/tests/DripSharp.PdfCarton.ReleaseSmoke"
                   "products/pdfcarton/tests/DripSharp.PdfCarton.Tests"]]
    (doseq [file files
            :let [contents (slurp (str file))]
            token forbidden]
      (is (not (str/includes? contents token)) (str file " contains " token)))))

(deftest triggered-proof-workflows-cancel-superseded-runs
  (doseq [file [".github/workflows/required-proof-ladders.yml"
                ".github/workflows/pdfcube-family-hosts.yml"]
          :let [contents (slurp file)]]
    (is (str/includes? contents "concurrency:") file)
    (is (str/includes? contents "github.event.pull_request.number || github.ref") file)
    (is (str/includes? contents "cancel-in-progress: true") file)))
