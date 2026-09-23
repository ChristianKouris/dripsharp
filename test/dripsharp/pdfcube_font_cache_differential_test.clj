;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube-font-cache-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.font-cache-differential :as cache]))

(def observation
  {"selected" "temporary/.pdfbox.cache" "cache-write" "executed"
   "cache-present" "true" "cache-rows" "1" "cache-font" "LiberationSans"
   "cache-reloaded" "1" "pdf-created" "true" "pages" "1"
   "text" "Rm9udCBjYWNoZSBmYWxsYmFjawo=" "substitute" "LiberationSans"
   "side-effects" "font.pdf;ordinary-file;temporary/.pdfbox.cache"})

(deftest generated-cases-cover-both-properties-and-combinations
  (let [cases (cache/scenarios)]
    (is (= cases (cache/scenarios)))
    (is (= (set cache/settings) (set (map first cases))))
    (is (= (set cache/settings) (set (map second cases))))
    (is (= (count cases) (count (set cases))))
    (is (some (fn [[a b]] (and (= :valid a) (= :file b))) cases))
    (is (nil? (cache/setting-value "/case" :unset "home")))
    (is (= "" (cache/setting-value "/case" :empty "home")))
    (is (= " " (cache/setting-value "/case" :spaces "home")))
    (is (= "bad\u0000path" (cache/setting-value "/case" :invalid "home")))))

(deftest meaningful-differences-cannot-be-normalized-away
  (is (nil? (cache/assert-contract! [:unset :unset] observation observation observation {})))
  (doseq [[field value] [["selected" "home/.pdfbox.cache"] ["cache-present" "false"]
                        ["cache-rows" "0"] ["cache-reloaded" "-1"]
                        ["text" ""] ["pdf-created" "false"] ["pages" "0"]
                        ["side-effects" "font.pdf;ordinary-file;unexpected"]]]
    (testing field
      (is (thrown? clojure.lang.ExceptionInfo
                   (cache/assert-contract! [:unset :unset] observation observation
                                           (assoc observation field value) {})))))
  (testing "agreement alone is insufficient if neither side exercised a real font"
    (let [wrong (assoc observation "cache-rows" "0")]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cache/assert-contract! [:unset :unset] wrong wrong wrong {}))))))

(deftest empty-path-drift-has-an-exact-separate-contract
  (let [java25 (-> observation
                  (dissoc "cache-rows" "cache-font" "cache-reloaded")
                  (assoc "selected" (str java.io.File/separator ".pdfbox.cache")
                         "cache-write" "guarded-outside-scenario"
                         "cache-present" "false" "side-effects" "font.pdf;ordinary-file"))]
    (is (nil? (cache/assert-contract! [:unset :empty] observation java25 observation {})))
    (is (nil? (cache/assert-contract! [:empty :valid] observation java25 observation {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cache/assert-contract! [:valid :empty] observation java25 observation {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cache/assert-contract! [:unset :empty] observation observation observation {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cache/assert-contract! [:unset :empty] observation
                                         (assoc java25 "text" "changed") observation {})))))
