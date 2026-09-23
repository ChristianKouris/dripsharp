;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns dripsharp.pdfcube.font-render-cases
  "Bounded recorded content shared by the Java and package font/render probes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.util Base64 Random]))

(def generator-version 1)
(def fonts
  {:liberation "pdfbox/src/main/resources/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
   :emoji "fontbox/target/fonts/NotoEmoji-Regular.ttf"
   :devanagari "fontbox/src/test/resources/ttf/Lohit-Devanagari.ttf"
   :bengali "fontbox/src/test/resources/ttf/Lohit-Bengali.ttf"
   :gujarati "fontbox/src/test/resources/ttf/Lohit-Gujarati.ttf"
   :josefin "fontbox/src/test/resources/ttf/JosefinSans-Italic.ttf"})

(def examples
  [[:liberation "café Ω" "Ångström"]
   [:liberation "Á ë" "ö ñ"]
   [:liberation "אבג 12" "12 אבג"]
   [:emoji "😀😃" "😃😀"]
   [:devanagari "य़ज़क़" "क़य़"]
   [:bengali "ক্ষীরের" "ক্ষীর"]
   [:gujarati "ક્ષજ્ઞત્તશ્ર" "ક્ષજ્ઞ"]
   [:josefin "office ffi" "affinity fi"]])

(def fixtures
  [{:id "arabic-ligatures"
    :path "pdfbox/src/test/resources/input/PDFBOX-4531-bidi-ligature-1.pdf"}
   {:id "surrogate-diacritic"
    :path "pdfbox/src/test/resources/input/PDFBOX-5747-unicode-surrogate-with-diacritic-reduced.pdf"}])

(defn- ensure! [condition message]
  (when-not condition
    (throw (ex-info message {:kind :invalid-font-render-input}))))

(defn validate! [{:keys [version pages] :as record}]
  (ensure! (= generator-version version) "Unsupported content version")
  (ensure! (and (vector? pages) (<= 1 (count pages) 12)) "Expected 1–12 pages")
  (doseq [{:keys [font text size x y rotation transform clip]} pages]
    (ensure! (contains? fonts font) "Unknown explicit font asset")
    (ensure! (and (string? text) (<= 1 (count text) 64)
                  (not (re-find #"[\p{Cc}]" text))) "Invalid bounded text")
    (ensure! (and (integer? size) (<= 8 size 32)
                  (integer? x) (<= 0 x 200)
                  (integer? y) (<= 0 y 140)
                  (#{0 90 180 270} rotation)) "Invalid size/position/rotation")
    (ensure! (some #{transform} [[1 0 0 1 0 0] [1 0.25 0 1 0 0]
                                [0 1 -1 0 180 0] [0.75 0 0 1.25 8 0]])
             "Unsupported bounded transform")
    (ensure! (some #(= clip %) [nil [8 8 224 144] [24 36 80 52]])
             "Unsupported bounded clip"))
  record)

(defn generate [seed]
  (let [rng (Random. (long seed))
        [font & texts] (nth examples (mod seed (count examples)))]
    (validate!
     {:version generator-version :seed seed
      :pages [{:font font :text (nth texts (.nextInt rng (count texts)))
               :size (nth [12 18 24] (.nextInt rng 3))
               :x (+ 12 (.nextInt rng 25)) :y (+ 50 (.nextInt rng 41))
               :rotation (nth [0 90 180 270] (.nextInt rng 4))
               :transform (nth [[1 0 0 1 0 0] [1 0.25 0 1 0 0]
                                [0 1 -1 0 180 0] [0.75 0 0 1.25 8 0]]
                               (.nextInt rng 4))
               :clip (nth [nil [8 8 224 144] [24 36 80 52]] (.nextInt rng 3))}]})))

(defn regression []
  (validate!
   {:version generator-version :seed nil
    :pages (mapv (fn [index [font text]]
                   {:font font :text text :size 18 :x 20 :y 70
                    :rotation (nth [0 90 180 270] (mod index 4))
                    :transform (if (odd? index) [1 0.25 0 1 0 0] [1 0 0 1 0 0])
                    :clip (if (= index 7) [24 36 80 52] [8 8 224 144])})
                 (range) examples)}))

(defn read-record [file] (validate! (edn/read-string (slurp (str file)))))
(defn write-record! [file record]
  (spit (str file) (str (pr-str (validate! record)) "\n")))

(defn wire [record]
  (validate! record)
  (str/join
   "\n"
   (cons "font-render\t1"
         (for [{:keys [font text size x y rotation transform clip]} (:pages record)]
           (str/join "\t"
                     (concat ["page" (fonts font)
                              (.encodeToString (Base64/getEncoder)
                                               (.getBytes ^String text "UTF-8"))
                              size x y rotation]
                             transform (or clip [0 0 240 160])))))))

(defn read-trace [file]
  (let [rows (mapv #(str/split % #"\t" -1) (str/split-lines (slurp (str file))))]
    (ensure! (and (seq rows) (every? #(= 2 (count %)) rows)
                  (= (count rows) (count (set (map first rows)))))
             "Malformed or duplicate observation trace")
    (into (sorted-map) rows)))

(defn first-difference [expected actual]
  (some (fn [key]
          (when (not= (find expected key) (find actual key))
            {:observation key
             :expected (get expected key ::missing)
             :actual (get actual key ::missing)}))
        (sort (into (set (keys expected)) (keys actual)))))
