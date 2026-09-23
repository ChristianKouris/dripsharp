;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube-document-workflows-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.document-workflow-differential :as live]
            [dripsharp.pdfcube.document-workflows :as w])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest generation-is-recorded-bounded-and-reproducible
  (doseq [seed (range 100)]
    (let [model (w/generate seed)]
      (is (= model (w/generate seed)))
      (is (= model (w/validate! model)))
      (is (= (w/wire model) (w/wire (read-string (pr-str model))))))))

(deftest prerequisites-and-references-are-enforced
  (let [model (w/generate 20)]
    (doseq [ops [[{:id "bad" :op :incremental}]
                 [{:id "bad" :op :load}]
                 [{:id "save" :op :full} {:id "edit" :op :shared :target "absent" :value "x"}]
                 [{:id "save" :op :full} {:id "invalid" :op :invalid-incremental}]]]
      (is (thrown? clojure.lang.ExceptionInfo (w/validate! (assoc model :operations ops)))))
    (is (thrown? clojure.lang.ExceptionInfo (w/validate! (assoc-in model [:pages 0 :resource] "absent"))))
    (doseq [candidate (w/candidates model)]
      (is (= candidate (w/validate! candidate))))))

(deftest comparator-detects-lost-updates-encoding-order-and-aliases
  (let [model (w/generate 20)
        expected (assoc (w/expected-checkpoints model)
                        "initial-save/page/0/content" "20200a"
                        "initial-save/page/1/resource-alias" (get (w/expected-checkpoints model) "initial-save/page/1/resource-alias"))
        cross (w/semantic-trace expected)
        traces {:java expected :dotnet expected :java-cross cross :dotnet-cross cross}]
    (is (nil? (w/compare-traces model traces)))
    (doseq [[key value] [["incremental-save/title" "u:"]
                         ["initial-save/page/0/content" "200a"]
                         ["initial-save/page/1/resource-alias" "99"]
                         ["initial-save/page/0/id" "u:00700031"]
                         ["incremental-save/prefix-preserved" "false"]]]
      (is (some? (w/compare-traces model (assoc-in traces [:dotnet key] value)))))
    (testing "agreement between broken writers cannot hide a lost title update"
      (let [lost (assoc expected "incremental-save/title" "u:")]
        (is (= [:model :java] (:comparison (w/compare-traces model (assoc traces :java lost :dotnet lost)))))))
    (is (some? (w/compare-traces model (assoc traces :dotnet-cross {}))))))

(deftest shrinking-keeps-the-failure-and-valid-references
  (let [model (w/generate 20)
        failure {:comparison [:java :dotnet] :observation "incremental-save/title" :expected "a" :actual "b"}
        evaluate (fn [m _] (when (and (some #(= :shared (:op %)) (:operations m))
                                      (some #(= :incremental (:op %)) (:operations m))) failure))
        result (w/shrink! model failure evaluate {:max-attempts 100 :max-ms 10000})]
    (is (pos? (:accepted result)))
    (is (<= (:attempts result) 100))
    (is (= (:workflow result) (w/validate! (:workflow result))))
    (is (< (count (:pages (:workflow result))) (count (:pages model))))
    (is (= failure (evaluate (:workflow result) 1000)))
    (is (zero? (:accepted (w/shrink! model failure (fn [_ _] nil) {:max-attempts 3 :max-ms 1000}))))))

(deftest fixed-shared-incremental-workflow-remains-independent-of-generator
  (let [fixed (w/read-record "validation/pdfcube-pdfbox-document-lifecycle/shared-incremental.edn")]
    (is (:nested fixed))
    (is (some #(= :incremental (:op %)) (:operations fixed)))
    (is (some #(= :shared (:op %)) (:operations fixed)))
    (is (< (count (set (map :resource (:pages fixed)))) (count (:pages fixed))))))

(deftest reopening-discards-unsaved-mutations-in-the-independent-model
  (let [model (assoc (w/generate 20) :operations
                     [{:id "base" :op :full}
                      {:id "discarded" :op :title :value "unsaved"}
                      {:id "close" :op :close}
                      {:id "load" :op :load}
                      {:id "copy" :op :full}])
        expected (w/expected-checkpoints (w/validate! model))]
    (is (= (get expected "base/title") (get expected "copy/title")))))

(deftest reducer-rejects-a-different-failure-at-the-same-observation
  (let [model (w/generate 20)
        failure {:comparison [:java :dotnet] :observation "incremental-save/title" :expected "a" :actual "b"}
        result (w/shrink! model failure (fn [_ _] (assoc failure :actual "different"))
                          {:max-attempts 4 :max-ms 1000})]
    (is (= model (:workflow result)))
    (is (= 4 (:attempts result)))
    (is (zero? (:accepted result)))))

(deftest process-failures-retain-the-concrete-input-and-replay
  (let [out (Files/createTempDirectory "workflow-failure-test-" (make-array FileAttribute 0))
        error (try
                (with-redefs-fn
                  {(ns-resolve 'dripsharp.pdfcube.document-workflow-differential 'prepare!)
                   (fn [& _] {:java [] :dotnet []})}
                  #(live/verify! {:output-directory (str out) :case-count 0 :baseline? false
                                  :package-fn (fn [_] {:identity {:id "fixture"}})
                                  :run-command! (fn [_] (throw (ex-info "fixture timeout" {:kind :command-timeout})))}))
                nil
                (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (try
      (is (= :command-timeout (:kind error)))
      (is (= 0 (:case error)))
      (is (paths/regular-file? (:input error)))
      (is (paths/regular-file? (:replay error)))
      (is (= (w/read-record "validation/pdfcube-pdfbox-document-lifecycle/shared-incremental.edn")
             (w/read-record (:input error))))
      (let [record (edn/read-string (slurp (str (.resolve (.getParent (.getParent (paths/path (:input error)))) "failure.edn"))))]
        (is (= :command-timeout (:kind record)))
        (is (= (:replay error) (:replay record))))
      (finally (harness/clean-directory! out) (Files/delete out)))))

(deftest malformed-observations-are-harness-failures
  (let [file (Files/createTempFile "workflow-observations-" ".tsv" (make-array FileAttribute 0))]
    (try
      (doseq [text ["" "missing-field\n" "id\tone\nid\ttwo\n"]]
        (spit (str file) text)
        (is (= :invalid-observations
               (try (w/read-trace file) nil
                    (catch clojure.lang.ExceptionInfo e (:kind (ex-data e)))))))
      (finally (Files/delete file)))))
