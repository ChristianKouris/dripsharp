(ns dripsharp.regex-differential
  "Bounded Java 17 regex cases shared by direct JavaCompat, translated Java,
  and product-package differentials. Live execution is local-only."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.differential-cases :as differential-cases]
            [dripsharp.harness :as harness]
            [dripsharp.java-compat-differential :as java-compat]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64 Random]))

(def astral-cases
  [["regex/astral-capture/literal-plus" "FIND" 0 "(😀+)😈" "😀😀😈" ""]
   ["regex/astral-capture/codepoint-plus" "FIND" 0 "(\\x{1F600}+)😈" "😀😀😈" ""]
   ["regex/astral-capture/name-plus" "FIND" 0 "(\\N{GRINNING FACE}+)😈" "😀😀😈" ""]
   ["regex/astral-capture/singleton-class-plus" "FIND" 0 "([😀]+)😈" "😀😀😈" ""]
   ["regex/astral-replace/all" "REPLACE_ALL" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/first" "REPLACE_FIRST" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/last" "REPLACE_LAST" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "($0|$1)"]
   ["regex/astral-replace/all-mapped" "REPLACE_ALL_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]
   ["regex/astral-replace/first-mapped" "REPLACE_FIRST_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]
   ["regex/astral-replace/last-mapped" "REPLACE_LAST_MAPPED" 0 "(😀+)😈"
    "😀😀😈😈😍😍😀😀😈😈😍😍" "├{}┤"]])

(def fixed-cases
  [;; Pattern text, flags, quoting, literal escapes, and compile failures.
   ["regex/pattern/default-flags" "PATTERN" 0 "a(b)" "" ""]
   ["regex/pattern/unicode-implies-unicode-case" "PATTERN" 256 "a" "" ""]
   ["regex/pattern/all-flags" "PATTERN" 511 "a" "" ""]
   ["regex/pattern/unknown-flag" "PATTERN" 512 "a" "" ""]
   ["regex/quote/metacharacters" "QUOTE_PATTERN" 0 ".a[0]" ".a[0]" ""]
   ["regex/quote/embedded-end-marker" "QUOTE_PATTERN" 0 "a\\Eb" "a\\Eb" ""]
   ["regex/quote/direct-qe" "MATCHES" 0 "\\Q.a[0]\\E" ".a[0]" ""]
   ["regex/escape/octal" "MATCHES" 0 "\\0141" "a" ""]
   ["regex/escape/hex" "MATCHES" 0 "\\x61\\u0062" "ab" ""]
   ["regex/escape/codepoint" "MATCHES" 0 "\\x{1F600}" "😀" ""]
   ["regex/escape/unicode-name" "MATCHES" 0 "\\N{GREEK CAPITAL LETTER OMEGA}" "Ω" ""]
   ["regex/escape/unicode-name-table" "MATCHES" 0 "\\N{PILE OF POO}" "💩" ""]
   ["regex/escape/unicode-name-hangul" "MATCHES" 0 "\\N{HANGUL SYLLABLES AC00}" "가" ""]
   ["regex/escape/unicode-name-cjk" "MATCHES" 0 "\\N{CJK UNIFIED IDEOGRAPHS 4E00}" "一" ""]
   ["regex/escape/unicode-name-private" "MATCHES" 0 "\\N{PRIVATE USE AREA E000}" "" ""]
   ["regex/escape/control" "MATCHES" 0 "\\cJ" "\n" ""]
   ["regex/syntax/dangling" "MATCHES" 0 "*" "" ""]
   ["regex/syntax/unknown-escape" "MATCHES" 0 "\\y" "y" ""]

   ;; Compile flags and their inline equivalents.
   ["regex/flags/ascii-case" "MATCHES" 2 "Ä" "ä" ""]
   ["regex/flags/ascii-case-class" "MATCHES" 2 "[a-z]+" "ABC" ""]
   ["regex/flags/ascii-case-negated-class" "MATCHES" 2 "[^a]" "A" ""]
   ["regex/flags/ascii-case-literal" "MATCHES" 18 "a.+" "A.+" ""]
   ["regex/flags/unicode-case" "MATCHES" 66 "Ä" "ä" ""]
   ["regex/flags/unicode-case-class" "MATCHES" 66 "[α]+" "Α" ""]
   ["regex/flags/literal" "MATCHES" 16 ".+" ".+" ""]
   ["regex/flags/comments" "MATCHES" 4 "a # comment\n b" "ab" ""]
   ["regex/flags/comments-class" "MATCHES" 4 "[ a ]" "a" ""]
   ["regex/flags/unix-lines-dot" "MATCHES" 1 "." "\r" ""]
   ["regex/flags/dotall" "MATCHES" 32 ".+" "a\nb" ""]
   ["regex/flags/multiline-cr" "FIND" 8 "^b$" "a\rb" ""]
   ["regex/flags/unix-lines-cr" "FIND" 9 "^b$" "a\rb" ""]
   ["regex/flags/canonical-equivalence" "MATCHES" 128 "å" "å" ""]
   ["regex/flags/inline-enable-disable" "MATCHES" 0 "(?i:a)(?-i:b)" "Ab" ""]
   ["regex/flags/scoped-unicode-class" "MATCHES" 0 "(?U:\\w+)" "café" ""]

   ;; Character classes, properties, astral code points, and class algebra.
   ["regex/class/default-ascii-word" "MATCHES" 0 "\\w+" "café" ""]
   ["regex/class/unicode-word" "MATCHES" 256 "\\w+" "café" ""]
   ["regex/class/default-ascii-digit" "MATCHES" 0 "\\d" "٣" ""]
   ["regex/class/unicode-digit" "MATCHES" 256 "\\d" "٣" ""]
   ["regex/class/horizontal" "MATCHES" 0 "\\h+" " \t" ""]
   ["regex/class/vertical" "MATCHES" 0 "\\v+" "\n " ""]
   ["regex/class/union" "MATCHES" 0 "[a-d[m-p]]+" "camp" ""]
   ["regex/class/intersection" "MATCHES" 0 "[a-z&&[def]]+" "feed" ""]
   ["regex/class/subtraction" "MATCHES" 0 "[a-z&&[^m-p]]+" "lazy" ""]
   ["regex/class/nested-subtraction" "MATCHES" 0 "[[^/]&&[^\\p{Alnum}+.-]]" "_" ""]
   ["regex/class/astral-literal" "MATCHES" 0 "[😀😈]+" "😀😈" ""]
   ["regex/class/astral-negation" "MATCHES" 0 "[^😀]+" "😈" ""]
   ["regex/class/octal-escape" "MATCHES" 0 "[\\0141]" "a" ""]
   ["regex/class/unicode-escape-range" "MATCHES" 0 "[\\u0061-\\u0063]+" "abc" ""]
   ["regex/class/codepoint-escape" "MATCHES" 0 "[\\x{1F600}]" "😀" ""]
   ["regex/class/unicode-name" "MATCHES" 0 "[\\N{PILE OF POO}]" "💩" ""]
   ["regex/class/quoted" "MATCHES" 0 "[\\Q.+-\\E]+" ".+-" ""]
   ["regex/property/posix-ascii" "MATCHES" 0 "\\p{Lower}+" "abc" ""]
   ["regex/property/posix-unicode" "MATCHES" 256 "\\p{Lower}+" "é" ""]
   ["regex/property/posix-ascii-case" "MATCHES" 2 "\\p{Lower}+" "ABC" ""]
   ["regex/property/posix-unicode-case" "MATCHES" 258 "\\p{Lower}+" "É" ""]
   ["regex/property/java-whitespace" "MATCHES" 0 "\\p{javaWhitespace}" " " ""]
   ["regex/property/java-identifier" "MATCHES" 0 "\\p{javaJavaIdentifierStart}" "$" ""]
   ["regex/property/java-ideographic" "MATCHES" 0 "\\p{javaIdeographic}" "一" ""]
   ["regex/property/block" "MATCHES" 0 "\\p{InGreek}+" "Ω" ""]
   ["regex/property/block-equality" "MATCHES" 0 "\\p{blk=Emoticons}" "😀" ""]
   ["regex/property/script" "MATCHES" 0 "\\p{IsLatin}+" "é" ""]
   ["regex/property/script-equality" "MATCHES" 0 "\\p{script=Gothic}" "𐌰" ""]
   ["regex/property/script-iso-alias" "MATCHES" 0 "\\p{sc=Latn}+" "é" ""]
   ["regex/property/category" "MATCHES" 0 "\\p{Lu}+" "ΩA" ""]
   ["regex/property/category-equality" "MATCHES" 0 "\\p{gc=No}" "½" ""]
   ["regex/property/category-format" "MATCHES" 0 "\\p{Cf}" "‍" ""]
   ["regex/property/category-private" "MATCHES" 0 "\\p{Co}" "" ""]
   ["regex/property/category-quote" "MATCHES" 0 "\\p{Pi}" "“" ""]
   ["regex/property/category-symbol" "MATCHES" 0 "\\p{Sm}" "+" ""]
   ["regex/property/binary" "MATCHES" 0 "\\p{IsAlphabetic}+" "ΩA" ""]
   ["regex/property/binary-emoji" "MATCHES" 0 "\\p{IsEmoji}" "💩" ""]

   ;; Boundaries, line breaks, graphemes, grouping, and quantifier modes.
   ["regex/boundary/word" "FIND" 0 "\\bword\\b" "a word!" ""]
   ["regex/boundary/non-word" "FIND" 0 "\\Boo\\B" "zooom" ""]
   ["regex/boundary/input" "MATCHES" 0 "\\Aabc\\z" "abc" ""]
   ["regex/boundary/final-terminator" "FIND" 0 "abc\\Z" "abc\r\n" ""]
   ["regex/boundary/previous-match" "FIND" 0 "\\G." "abc" ""]
   ["regex/matcher/looking-at" "LOOKING_AT" 0 "ab" "abc" ""]
   ["regex/linebreak/unicode" "MATCHES" 0 "a\\Rb" "a\r\nb" ""]
   ["regex/grapheme/cluster" "FIND" 0 "\\X" "á" ""]
   ["regex/grapheme/regional-indicators" "FIND" 0 "\\X" "🇺🇸🇨🇦" ""]
   ["regex/grapheme/hangul" "MATCHES" 0 "\\X" "가" ""]
   ["regex/grapheme/prepend" "MATCHES" 0 "\\X" "؀A" ""]
   ["regex/grapheme/emoji-modifier" "MATCHES" 0 "\\X" "👍🏽" ""]
   ["regex/grapheme/emoji-zwj" "MATCHES" 0 "\\X" "👩‍❤️‍💋‍👨" ""]
   ["regex/grapheme/boundary" "FIND" 0 "a\\b{g}" "á" ""]
   ["regex/group/numeric-order" "FIND" 0 "(?<first>a)(b)(c)?" "ab" ""]
   ["regex/group/numeric-backref" "MATCHES" 0 "(?<first>a)(b)\\1\\2" "abab" ""]
   ["regex/group/named-backref" "MATCHES" 0 "(?<word>ab)-\\k<word>" "ab-ab" ""]
   ["regex/group/lookahead" "FIND" 0 "a(?=b)" "zab" ""]
   ["regex/group/lookbehind" "FIND" 0 "(?<=a)b" "zab" ""]
   ["regex/group/atomic" "MATCHES" 0 "(?>a|ab)c" "abc" ""]
   ["regex/quantifier/greedy" "FIND" 0 "a+" "aaaa" ""]
   ["regex/quantifier/reluctant" "FIND" 0 "a+?" "aaaa" ""]
   ["regex/quantifier/possessive" "MATCHES" 0 "a++a" "aaaa" ""]
   ["regex/quantifier/possessive-range" "MATCHES" 0 "a{2,3}+a" "aaaa" ""]

   ;; Matcher region/zero-width state, split, and replacement contracts.
   ["regex/matcher/zero-width-astral" "FIND" 0 "" "😀a" ""]
   ["regex/matcher/region-matches" "REGION" 0 "^b$" "abc" "1,2,matches"]
   ["regex/matcher/region-looking-at" "REGION" 0 "b" "abc" "1,3,lookingAt"]
   ["regex/matcher/region-find" "REGION" 0 "c" "abc" "1,3,find"]
   ["regex/split/positive-limit" "SPLIT" 0 ":" "boo:and:foo" "2"]
   ["regex/split/negative-limit" "SPLIT" 0 "o" "boo:and:foo" "-2"]
   ["regex/split/zero-limit" "SPLIT" 0 "o" "boo:and:foo" "0"]
   ["regex/split/positive-start" "SPLIT" 0 ":" ":a" "0"]
   ["regex/split/zero-width-start" "SPLIT" 0 "^" "abc" "0"]
   ["regex/split/captures-not-returned" "SPLIT" 0 "(,)" "a,b,c" "0"]
   ["regex/split/astral-zero-width" "SPLIT" 0 "" "😀a" "-1"]
   ["regex/replace/numeric" "REPLACE_ALL" 0 "(a)(b)" "abxab" "$2$1"]
   ["regex/replace/whole-match" "REPLACE_ALL" 0 "a" "aba" "<$0>"]
   ["regex/replace/unmatched-group" "REPLACE_ALL" 0 "(a)?b" "b" "<$1>"]
   ["regex/replace/zero-width-astral" "REPLACE_ALL" 0 "" "😀a" "-"]
   ["regex/replace/canonical-equivalence" "REPLACE_ALL" 128 "å" "åx" "X"]
   ["regex/replace/named" "REPLACE_FIRST" 0 "(?<left>a)(?<right>b)" "abxab" "${right}${left}"]
   ["regex/replace/escaped" "REPLACE_ALL" 0 "a" "a" "\\$\\\\"]
   ["regex/replace/append" "APPEND" 0 "(a)" "a-a" "<$1>"]
   ["regex/replace/missing-group" "REPLACE_ALL" 0 "a" "a" "$4"]
   ["regex/replace/quote" "QUOTE_REPLACEMENT" 0 "a$\\b" "" ""]])

(def generator-version 1)
(def default-seed 1701)
(def default-sample-size 12)
(def ^:private maximum-sample-size 64)

(def java17-fixed-cases
  ;; The product-package family intentionally runs on its separately pinned JDK
  ;; and retains newer properties such as IsEmoji. Direct JavaCompat uses the
  ;; Java 17 contract and therefore selects only cases defined by that JDK.
  (filterv #(not= "regex/property/binary-emoji" (first %)) fixed-cases))

(defn- choose [^Random random values]
  (nth values (.nextInt random (count values))))

(defn- generated-pattern [^Random random]
  (let [atom (choose random ["a" "b" "[ab]" "\\d" "\\w" "(?:ab)" "(a)?" ""])
        quantifier (if (empty? atom) "" (choose random ["" "+" "*" "?" "{1,2}"]))
        anchor (choose random [["" ""] ["^" ""] ["" "$"]])]
    (str (first anchor) atom quantifier (second anchor))))

(defn- generated-input [^Random random]
  (apply str (repeatedly (.nextInt random 9)
                         #(choose random ["a" "b" "0" "_" "é" "😀"]))))

(defn generated-cases
  "Returns a deterministic, bounded sample. Concrete strings and patterns are
  generated once here and are passed unchanged to both implementations."
  ([sample-size] (generated-cases default-seed sample-size))
  ([seed sample-size]
   (when-not (and (integer? seed)
                  (integer? sample-size)
                  (<= 6 sample-size maximum-sample-size))
     (throw (ex-info "Regex sample bounds are invalid"
                     {:kind :invalid-regex-sample
                      :seed seed :sample-size sample-size
                      :minimum 6 :maximum maximum-sample-size})))
   (let [random (Random. (long seed))
         coverage
         [["generated/sequence/find-reset-groups" "SEQUENCE" 0 "(a)?(b*)" "ab abb"
           "find;find;reset;find"]
          ["generated/sequence/zero-width-region" "SEQUENCE" 0 "" "😀a"
           "find;find;region=2:3;find;reset;find"]
          ["generated/split/limit" "SPLIT" 0 "[,:]" "a,b:c" "3"]
          ["generated/replace/escaping" "REPLACE_ALL" 0 "(a)" "aba" "\\$1"]
          ["generated/failure/pattern" "MATCHES" 0 "[" "a" ""]
          ["generated/failure/replacement" "REPLACE_ALL" 0 "(a)" "a" "${missing}"]]
         random-cases
         (mapv
          (fn [index]
            (let [pattern (generated-pattern random)
                  input (generated-input random)
                  flags (choose random [0 2 256 258])
                  [operation argument]
                  (choose random [["SEQUENCE" "find;find;reset;lookingAt;matches"]
                                  ["SPLIT" (str (choose random [-1 0 1 2 3]))]
                                  ["REPLACE_ALL" "X"]
                                  ["REPLACE_FIRST" "_"]])]
              [(format "generated/random/%03d" index)
               operation flags pattern input argument]))
          (range (- sample-size (count coverage))))]
     (into coverage random-cases))))

(defn write-manifest!
  "Writes the shared six-column regex manifest used by JVM and .NET runners."
  [^Path manifest cases]
  (let [encoder (Base64/getEncoder)
        encode (fn [value]
                 (.encodeToString encoder
                                  (.getBytes (str value) StandardCharsets/UTF_8)))]
    (util/write-text!
     manifest
     (apply str
            (map (fn [[id operation flags pattern input argument]]
                   (str id "\t" operation "\t" flags "\t" (encode pattern) "\t"
                        (encode input) "\t" (encode argument) "\n"))
                 cases)))
    manifest))

(defn- run-stage! [run-command! phase invocation]
  (let [execution (differential-cases/process-outcome run-command! phase invocation)]
    (when-not (= :ok (:status execution))
      (throw (ex-info "Regex differential process failed"
                      {:kind :regex-differential-process-failure
                       :execution execution})))
    execution))

(defn- decoded-observations [^Path output]
  (let [decoder (Base64/getDecoder)]
    (into {}
          (map (fn [line]
                 (let [[id status payload] (str/split line #"\t" -1)]
                   [id {:status status
                        :value (String. (.decode decoder payload)
                                        StandardCharsets/UTF_8)}])))
          (str/split-lines (slurp (str output))))))

(defn- validate-generated-observations! [^Path output]
  (let [observations (decoded-observations output)
        expected {"generated/failure/pattern"
                  {:status "ERROR"
                   :value "failure|MATCHES|pattern-syntax"}
                  "generated/failure/replacement"
                  {:status "ERROR"
                   :value "failure|REPLACE_ALL|illegal-argument"}}
        sequence-value (get-in observations
                               ["generated/sequence/find-reset-groups" :value])
        zero-width-value (get-in observations
                                 ["generated/sequence/zero-width-region" :value])]
    (when-not (and (= expected (select-keys observations (keys expected)))
                   (string? sequence-value)
                   (string? zero-width-value)
                   (str/includes? sequence-value ";-1,-1,~;2,2,")
                   (str/includes? sequence-value "|reset=ready|find=true:0,2,")
                   (str/includes? zero-width-value
                                  "find=true:0,0,|find=true:1,1,")
                   (str/includes? zero-width-value
                                  "|region=2:3=ready|find=true:2,2,")
                   (str/ends-with? zero-width-value
                                   "|reset=ready|find=true:0,0,"))
      (throw (ex-info "Generated regex observations lost required Java semantics"
                      {:kind :invalid-generated-regex-observations
                       :output (str output)
                       :observations
                       (select-keys observations
                                    ["generated/failure/pattern"
                                     "generated/failure/replacement"
                                     "generated/sequence/find-reset-groups"
                                     "generated/sequence/zero-width-region"])})))
    observations))

(defn verify-direct!
  "Runs the local Java 17 versus authored JavaCompat regex differential.
  This does not build any translated product."
  ([] (verify-direct! {}))
  ([{:keys [workspace-root run-command! seed sample-size]
     :or {run-command! process/run!
          seed default-seed
          sample-size default-sample-size}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         contract (java-compat/read-contract root)
         {:keys [java javac home]} (java-compat/java-tools contract)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "validation-output"
                                         "regex-differential"))
         classes (doto (paths/resolve-path proof-root "oracle-classes")
                   (Files/createDirectories (make-array FileAttribute 0)))
         generated (generated-cases seed sample-size)
         all-cases (into java17-fixed-cases generated)
         manifest (write-manifest! (paths/resolve-path proof-root "cases.tsv")
                                   all-cases)
         java-output (paths/resolve-path proof-root "java.tsv")
         dotnet-output (paths/resolve-path proof-root "dotnet.tsv")
         oracle (paths/resolve-path root "validation/regex-compat/RegexCompatOracle.java")
         project (paths/resolve-path root
                                     "validation/regex-compat/DripSharp.RegexCompat.DirectProbe.csproj")]
     (run-stage! run-command! :java-compile
                 {:directory root :timeout-ms 60000
                  :command [(str javac) "--release" "17" "-encoding" "UTF-8"
                            "-d" (str classes) (str oracle)]})
     (run-stage! run-command! :dotnet-build
                 {:directory root :timeout-ms 120000
                  :command ["dotnet" "build" (str project) "--nologo"
                            "--configuration" "Release" "--verbosity:minimal"
                            "--no-incremental" "-warnaserror"]})
     (run-stage! run-command! :java-observations
                 {:directory root :timeout-ms 30000
                  :command [(str java) "-cp" (str classes) "RegexCompatOracle"
                            (str manifest) (str java-output)]})
     (run-stage! run-command! :dotnet-observations
                 {:directory root :timeout-ms 30000
                  :command ["dotnet" "run" "--project" (str project)
                            "--configuration" "Release" "--no-build" "--no-restore"
                            "--" (str manifest) (str dotnet-output)]})
     (validate-generated-observations! java-output)
     (validate-generated-observations! dotnet-output)
     (let [comparison (differential/compare-results java-output dotnet-output)]
       (when-let [mismatch (:mismatch comparison)]
         (throw (ex-info "Authored JavaCompat regex behavior differs from Java 17"
                         {:kind :regex-differential-failed
                          :mismatch mismatch
                          :java-output (str java-output)
                          :dotnet-output (str dotnet-output)})))
       (let [summary {:status :passed
                      :java-release (:java-release contract)
                      :java-home (str home)
                      :generator {:id :bounded-regex-grammar
                                  :version generator-version
                                  :seed seed}
                      :fixed-cases (count java17-fixed-cases)
                      :generated-cases (count generated)
                      :observations (:matched comparison)
                      :proof-root (str proof-root)
                      :replay (str "DRIPSHARP_WORKERS=22 JAVA_TOOL_OPTIONS=-Xmx28g "
                                   "clojure -J-Xmx28g -M -m dripsharp.regex-differential "
                                   "--seed " seed " --sample-size " sample-size)}]
         (util/write-text! (paths/resolve-path proof-root "summary.edn")
                           (str (pr-str summary) "\n"))
         summary)))))

(defn -main [& arguments]
  (let [options (loop [remaining arguments result {}]
                  (if (empty? remaining)
                    result
                    (let [[flag value & more] remaining
                          key ({"--seed" :seed "--sample-size" :sample-size} flag)]
                      (when-not (and key value (not (contains? result key)))
                        (throw (ex-info "Usage: [--seed N] [--sample-size N]" {})))
                      (recur more (assoc result key (parse-long value))))))
        summary (verify-direct! options)]
    (prn summary)
    (shutdown-agents)))
