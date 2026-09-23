(ns dripsharp.regex-differential-test
  "Process-free checks. Live JVM/.NET execution uses explicit local commands."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-cases :as differential-cases]
            [dripsharp.microprogram :as microprogram]
            [dripsharp.paths :as paths]
            [dripsharp.regex-differential :as regex])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest fixed-product-cases-remain-reusable
  (let [ids (mapv first regex/fixed-cases)
        operations (set (map second regex/fixed-cases))]
    (is (= 116 (count regex/fixed-cases)))
    (is (= (count ids) (count (set ids))))
    (is (every? operations ["FIND" "REGION" "SPLIT" "REPLACE_ALL"
                            "REPLACE_FIRST" "QUOTE_REPLACEMENT"]))
    (is (= 115 (count regex/java17-fixed-cases)))
    (is (not-any? #(= "regex/property/binary-emoji" (first %))
                  regex/java17-fixed-cases))
    (is (= 10 (count regex/astral-cases)))))

(deftest generated-grammar-is-deterministic-bounded-and-behavioral
  (let [generated (regex/generated-cases 1701 12)
        replay (regex/generated-cases 1701 12)
        different (regex/generated-cases 1702 12)
        ids (mapv first generated)]
    (is (= generated replay))
    (is (not= generated different))
    (is (= 12 (count generated)))
    (is (= (count ids) (count (set ids))))
    (is (every? #(<= (count (nth % 3)) 16) generated))
    (is (every? #(<= (.length ^String (nth % 4)) 16) generated))
    (is (some #(= "SEQUENCE" (second %)) generated))
    (is (some #(str/includes? (nth % 5) "reset") generated))
    (is (some #(str/includes? (nth % 5) "region=") generated))
    (is (some #(= "SPLIT" (second %)) generated))
    (is (some #(str/starts-with? (first %) "generated/failure/pattern") generated))
    (is (some #(str/starts-with? (first %) "generated/failure/replacement") generated))
    (is (thrown? clojure.lang.ExceptionInfo (regex/generated-cases 1 5)))
    (is (thrown? clojure.lang.ExceptionInfo (regex/generated-cases 1 65)))))

(deftest manifest-retains-identical-concrete-input
  (let [file (Files/createTempFile "regex-cases" ".tsv"
                                   (make-array FileAttribute 0))
        cases (regex/generated-cases 1701 6)]
    (regex/write-manifest! file cases)
    (let [lines (str/split-lines (slurp (str file)))]
      (is (= 6 (count lines)))
      (is (every? #(= 6 (count (str/split % #"\t" -1))) lines)))))

(deftest timeout-is-process-evidence-not-a-regex-observation
  (let [error (try
                (#'regex/run-stage!
                 (fn [_]
                   (throw (ex-info "bounded timeout"
                                   {:kind :command-timeout :timeout-ms 25})))
                 :dotnet-observations
                 {:command ["probe"] :timeout-ms 25})
                nil
                (catch clojure.lang.ExceptionInfo value value))]
    (is (= :regex-differential-process-failure (:kind (ex-data error))))
    (is (= :timeout (get-in (ex-data error) [:execution :status])))
    (is (= :dotnet-observations
           (get-in (ex-data error) [:execution :phase])))))

(deftest generated-observations-have-independent-java-expectations
  (let [file (Files/createTempFile "regex-observations" ".tsv"
                                   (make-array FileAttribute 0))
        encoder (java.util.Base64/getEncoder)
        encode (fn [value]
                 (.encodeToString encoder
                                  (.getBytes value StandardCharsets/UTF_8)))
        observations
        [["generated/sequence/find-reset-groups" "OK"
          "4:find=true:0,2,YWI=;0,1,YQ==;1,2,Yg==|find=true:2,2,;-1,-1,~;2,2,|reset=ready|find=true:0,2,YWI=;0,1,YQ==;1,2,Yg=="]
         ["generated/sequence/zero-width-region" "OK"
          "6:find=true:0,0,|find=true:1,1,|region=2:3=ready|find=true:2,2,|reset=ready|find=true:0,0,"]
         ["generated/failure/pattern" "ERROR"
          "failure|MATCHES|pattern-syntax"]
         ["generated/failure/replacement" "ERROR"
          "failure|REPLACE_ALL|illegal-argument"]]]
    (spit (str file)
          (apply str (map (fn [[id status value]]
                            (str id "\t" status "\t" (encode value) "\n"))
                          observations)))
    (is (= 4 (count (#'regex/validate-generated-observations! file))))
    (spit (str file)
          (str "generated/failure/pattern\tERROR\t"
               (encode "failure|MATCHES|runtime") "\n"))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"lost required Java semantics"
         (#'regex/validate-generated-observations! file)))))

(deftest translated-template-is-bounded-and-uses-production-mappings
  (let [root (paths/workspace-root)
        snapshot (microprogram/read-batch
                  root "validation/microprograms/regex/batch.edn")
        rows (#'microprogram/validate-snapshot! snapshot)
        source (get-in snapshot [:sources "subjects/micro/RegexSequences.java"])
        runtime (slurp "runtime/DripSharp.JavaCompat.Java.Util.Regex.cs")
        mappings (slurp "src/dripsharp/java_library_mappings.clj")]
    (is (= 10 (count rows)))
    (is (= #{"sequence"} (set (map :operation rows))))
    (doseq [call ["matcher.find()" "matcher.reset()" "matcher.region("
                  "matcher.matches()" "compiled.split(" "matcher.replaceAll("
                  "Matcher.quoteReplacement("]]
      (is (str/includes? source call) call))
    (is (str/includes? runtime "internal JavaRegexMatcher Reset()"))
    (is (str/includes? mappings
                       "executable:java.util.regex.Matcher#reset()"))))
