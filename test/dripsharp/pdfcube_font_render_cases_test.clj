;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube-font-render-cases-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.pdfcube.font-render-cases :as cases]
            [dripsharp.pdfcube.font-render-differential :as campaign]
            [dripsharp.pdfcube.pdfbox-rendering-differential :as rendering]))

(deftest deterministic-bounded-unicode-content
  (let [records (mapv cases/generate (range 32))]
    (is (= records (mapv cases/generate (range 32))))
    (is (= (set (keys cases/fonts)) (set (mapcat #(map :font (:pages %)) records))))
    (is (= #{0 90 180 270} (set (mapcat #(map :rotation (:pages %)) records))))
    (is (= 8 (count (:pages (cases/regression)))))
    (is (some #(some (fn [c] (Character/isHighSurrogate ^char c)) (:text %))
              (:pages (cases/regression))))
    (doseq [record records]
      (is (= record (cases/validate! record)))
      (let [row (str/split (second (str/split-lines (cases/wire record))) #"\t" -1)]
        (is (= 17 (count row)))
        (is (= (:text (first (:pages record)))
               (String. (.decode (java.util.Base64/getDecoder) ^String (nth row 2)) "UTF-8"))))))
  (doseq [record [(assoc (cases/regression) :version 2)
                  (assoc (cases/regression) :pages [])
                  (assoc-in (cases/regression) [:pages 0 :font] :system-font)
                  (assoc-in (cases/regression) [:pages 0 :text] (apply str (repeat 65 "A")))
                  (assoc-in (cases/regression) [:pages 0 :size] 100)
                  (assoc-in (cases/regression) [:pages 0 :transform] [100 0 0 100 0 0])]]
    (is (thrown? clojure.lang.ExceptionInfo (cases/validate! record)))))

(deftest meaningful-differences-remain-exact
  (doseq [[expected actual] [[{"text" "0065,0301"} {"text" "00E9"}]
                             [{"points" "128512"} {"points" "65533"}]
                             [{"glyphs" "12,14"} {"glyphs" "14,12"}]
                             [{"x" "41A00000"} {"x" "41A00001"}]
                             [{"empty" ""} {}]]]
    (is (some? (cases/first-difference expected actual))))
  (is (nil? (cases/first-difference {"text" "D83D,DE00"} {"text" "D83D,DE00"})))
  (is (= {:rgb-channel-outlier 48 :alpha-channel-outlier 32
          :maximum-mean-absolute-error 10.0 :maximum-outlier-fraction 0.18}
         rendering/pixel-tolerances)))

(deftest observation-coverage-fails-closed
  (let [trace (into {"java/pages" "1"}
                    (for [key (conj campaign/page-observations "content")]
                      [(str "java/p0/" key) "value"]))]
    (is (= trace (campaign/validate-observations! trace ["java"] 1 true)))
    (doseq [invalid [(dissoc trace "java/p0/text-true")
                     (assoc trace "java/p0/glyphs-false" "")
                     (assoc trace "extra" "value")
                     (assoc trace "java/pages" "0")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (campaign/validate-observations! invalid ["java"] 1 true))))))
