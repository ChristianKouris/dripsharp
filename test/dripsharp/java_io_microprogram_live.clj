(ns dripsharp.java-io-microprogram-live
  "Explicit local verification; excluded from *_test.clj auto-discovery."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.java-io-microprogram :as io]
            [dripsharp.paths :as paths]))

(defn- decode [token]
  (apply str (map #(char (Integer/parseInt (apply str %) 16))
                  (partition 4 (subs token 2)))))

(deftest translated-streams-and-cleanup-match-java
  (let [result (io/verify!)]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [rows (cases/read-observations
                  (slurp (str (paths/resolve-path (:directory result) "java.tsv"))))
            traces (into {} (map (fn [row] [[(:case row) (:step row)] (decode (:result row))]) rows))]
        (is (= 80 (:matched result)))
        (is (= :passed (get-in result [:native-consumer :status])))
        ;; Independent anchors make exception precedence and side effects explicit.
        (is (str/includes? (traces ["reads-0-2" "2"])
                           "read:source:2;result:1;buffer:42,127,42,42,42,42,;"))
        (is (= "return:open:first;open:second;body;close:second;close:first;finally;io:body/cause:cause/suppressed:io:close-second/cause:null/suppressed:;,io:close-first/cause:null/suppressed:;,;"
               (traces ["cleanup-2" "0"])))
        (is (str/includes? (traces ["cleanup-3" "0"])
                           "io:close-second/cause:null/suppressed:io:close-first/cause:null/suppressed:;,;"))
        (is (str/includes? (traces ["cleanup-5" "0"])
                           "init-failure;close:first;finally;io:init/cause:null/suppressed:io:close-first/cause:null/suppressed:;,;"))
        (is (str/ends-with? (traces ["cleanup-8" "0"])
                            "finally;io:finally/cause:null/suppressed:;"))
        (is (str/includes? (traces ["overloads-0" "0"])
                           "whole;slice:0:3;io:bulk-failure/cause:null/suppressed:;buffer:-1,42,42,;"))))))
