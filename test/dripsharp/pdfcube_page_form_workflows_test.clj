;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube-page-form-workflows-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.page-form-workflow-differential :as live]
            [dripsharp.pdfcube.page-form-workflows :as workflows])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest generation-is-bounded-recorded-and-covers-each-template
  (let [generated (mapv workflows/generate (range 120))]
    (is (= (set workflows/templates) (set (map :template generated))))
    (doseq [workflow generated]
      (is (= workflow (workflows/generate (:seed workflow))))
      (is (= workflow (workflows/validate! workflow)))
      (is (= (workflows/wire workflow)
             (workflows/wire (read-string (pr-str workflow))))))))

(deftest validation-retains-capability-cases-but-rejects-malformed-references
  (let [workflow (workflows/generate 20)]
    (testing "valid templates are not filtered by their requested workflow"
      (doseq [template workflows/templates]
        (is (= template
               (:template (workflows/validate!
                           (assoc workflow :template template)))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (workflows/validate!
                  (assoc workflow :selection ["absent"]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (workflows/validate!
                  (assoc-in workflow [:fields 0 :page] "absent"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (workflows/validate!
                  (assoc workflow :pages (vec (repeat 7 (first (:pages workflow))))))))))

(deftest comparator-checks-model-writers-and-reciprocal-cross-opens
  (let [workflow (workflows/generate 20)
        expected (assoc (workflows/expected-observations workflow)
                        "reader" "java")
        dotnet (assoc expected "reader" "dotnet")
        traces {:java expected
                :dotnet dotnet
                :java-cross expected
                :dotnet-cross dotnet}]
    (is (nil? (workflows/compare-traces workflow traces)))
    (doseq [[side key value]
            [[:dotnet "page/0/id" "wrong"]
             [:java-cross "fields" "0"]
             [:dotnet-cross "page/0/content" "different"]]]
      (is (some? (workflows/compare-traces
                  workflow (assoc-in traces [side key] value)))))
    (testing "matching writers cannot hide an incorrect page order"
      (let [broken (assoc expected "page/0/id" "wrong")]
        (is (= [:model :java]
               (:comparison
                (workflows/compare-traces
                 workflow (assoc traces :java broken :dotnet broken)))))))))

(deftest fixed-regressions-cover-every-template
  (let [files ["split-and-merge.edn" "import-and-reorder.edn"
               "set-form-value-save-reopen.edn"]
        records (mapv #(workflows/read-record
                        (str "validation/pdfcube-pdfbox-manipulation/" %))
                      files)]
    (is (= (set workflows/templates) (set (map :template records))))
    (is (every? #(= 2 (count (:fields %))) records))
    (is (every? #(some (fn [page]
                         (< 1 (count (filter (fn [candidate]
                                               (= (:resource page)
                                                  (:resource candidate)))
                                             (:pages %)))))
                       (:pages %))
                records))))

(deftest reduction-keeps-the-same-first-semantic-difference
  (let [workflow (workflows/generate 20)
        failure {:comparison [:java :dotnet]
                 :observation "page/0/id"
                 :expected "p0"
                 :actual "wrong"}
        result (workflows/shrink!
                workflow failure
                (fn [_ _] failure)
                {:max-attempts 12 :max-ms 10000})]
    (is (pos? (:attempts result)))
    (is (pos? (:accepted result)))
    (is (= (:workflow result)
           (workflows/validate! (:workflow result))))
    (is (= failure ((fn [_ _] failure) (:workflow result) 1000)))))

(deftest process-failure-retains-input-and-replay
  (let [out (Files/createTempDirectory
             "page-form-workflow-failure-" (make-array FileAttribute 0))
        error
        (try
          (with-redefs-fn
            {(ns-resolve
              'dripsharp.pdfcube.page-form-workflow-differential 'prepare!)
             (fn [& _] {:java [] :dotnet []})}
            #(live/verify!
              {:output-directory (str out)
               :case-count 0
               :baseline? false
               :package-fn (fn [_]
                             {:identity {:id "fixture"}
                              :verification
                              {:generation {:project-input {}}}})
               :run-command!
               (fn [_]
                 (throw (ex-info "fixture timeout"
                                 {:kind :command-timeout})))}))
          nil
          (catch clojure.lang.ExceptionInfo exception (ex-data exception)))]
    (try
      (is (= :command-timeout (:kind error)))
      (is (= 0 (:case error)))
      (is (paths/regular-file? (:input error)))
      (is (paths/regular-file? (:replay error)))
      (finally
        (harness/clean-directory! out)
        (Files/delete out)))))

(deftest malformed-observations-are-harness-failures
  (let [file (Files/createTempFile
              "page-form-observations-" ".tsv"
              (make-array FileAttribute 0))]
    (try
      (doseq [text ["" "missing-field\n" "id\tone\nid\ttwo\n"]]
        (spit (str file) text)
        (is (= :invalid-page-form-observations
               (try
                 (workflows/read-trace file)
                 nil
                 (catch clojure.lang.ExceptionInfo exception
                   (:kind (ex-data exception)))))))
      (finally (Files/delete file)))))
