(ns dripsharp.numeric-microprogram
  "Bounded integral Java templates and exact inputs; explicit local execution."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.util Random]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def types [["byte" -128 127] ["short" -32768 32767] ["char" 0 65535]
            ["int" Integer/MIN_VALUE Integer/MAX_VALUE]
            ["long" Long/MIN_VALUE Long/MAX_VALUE]])
(def operators [["add" "+"] ["sub" "-"] ["mul" "*"] ["div" "/"] ["rem" "%"]
                ["and" "&"] ["or" "|"] ["xor" "^"] ["shl" "<<"] ["shr" ">>"] ["ushr" ">>>"]])
(def unary [["neg" "-a"] ["pos" "+a"] ["complement" "~a"]
            ["preinc" "++a"] ["postinc" "a++"] ["predec" "--a"] ["postdec" "a--"]])

(defn templates []
  (vec
   (concat
    (for [[type] types [name op] operators compound? [false true]
          :let [method (str type (if compound? "Compound" "Binary") (str/capitalize name))
                rhs-type (if (= type "long") "long" "int")]]
      {:method method :type type :operator name :compound? compound?
       :body (if compound?
               (str type " a = (" type ")x; " rhs-type " b = (" rhs-type ")y; "
                    type "[] values = new " type "[]{a}; cells" type " = values; "
                    "return array" type "()[index()] " op "= rhs" type "(b);")
               (str type " a = (" type ")x; " type " b = (" type ")y; return a " op " b;"))})
    (for [[type] types [name expression] unary]
      {:method (str type "Unary" (str/capitalize name)) :type type :unary? true
       :body (str type " a = (" type ")x; long result = " expression "; trace = a; return result;")})
    (for [[type] types]
      {:method (str type "Cast") :type type
       :body (str type " a = (" type ")x; return a;")})
    [{:method "constantOverflow" :type "long"
      :body "int a = 2147483647 + 1; int b = -(-2147483648); long c = 9223372036854775807L + 1L; int d = 2147483647 * 2; return (a ^ b) + c + d;"}
     {:method "byteLongAdd" :type "byte"
      :body "byte a = (byte)x; a += y; return a;"}
     {:method "shortLongMultiply" :type "short"
      :body "short a = (short)x; a *= y; return a;"}
     {:method "intLongOr" :type "int"
      :body "int a = (int)x; a |= y; return a;"}
     {:method "intLongAdd" :type "long"
      :body "int a = (int)x; return a + y;"}
     {:method "signedRead" :type "byte"
      :body "byte[] bytes = new byte[]{(byte)x}; return bytes[0];"}
     {:method "longShift" :type "long"
      :body "return x >>> y;"}
     {:method "intShiftLong" :type "int"
      :body "return ((int)x) >>> y;"}
     {:method "failure" :type "byte"
      :body (str "cellsbyte = x == 0 ? null : new byte[]{1}; "
                 "return arraybyte()[index() + (int)x] += rhsbyte((int)y);")}])))

(defn- source [templates]
  (str "// SPDX-FileCopyrightText: 2026 Isak Sky\n// SPDX-License-Identifier: Apache-2.0\n"
       "package micro; public final class NumericCases {\nprivate static long trace; private static int active;\n"
       "public static String state() {\n"
       (apply str (map-indexed (fn [i [type]]
                                 (str "if (active == " (inc i) ") return trace + \":\" + (cells" type
                                      " == null ? \"null\" : \"\" + (long)cells" type "[0]);\n")) types))
       "return trace + \":\"; }\n"
       "private static int index() { trace = trace * 10 + 2; return 0; }\n"
       (apply str
              (for [[type] types :let [rhs-type (if (= type "long") "long" "int")]]
                (str "private static " type "[] cells" type ";\n"
                     "private static " type "[] array" type "() { trace = trace * 10 + 1; return cells" type "; }\n"
                     "private static " rhs-type " rhs" type "(" rhs-type " value) { trace = trace * 10 + 3; cells" type "[0] = 7; return value; }\n")))
       (apply str (for [{:keys [method body compound? type]} templates]
                    (str "public static long " method "(long x, long y) { trace = 0; active = "
                         (if (or compound? (= method "failure"))
                           (inc (.indexOf (mapv first types) type)) 0)
                         "; " body " }\n")))
       "}\n"))

(defn snapshot
  "Six deterministic pairs per template plus at most 48 seeded combinations.
  No arithmetic expectation is computed here; the JVM is the oracle."
  ([] (snapshot 13 32))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 48))
     (throw (ex-info "Require a signed 64-bit seed and 0..48 samples" {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) micro/default-batch)
         templates (templates)
         rng (Random. (long seed))
         row (fn [{:keys [method type operator compound?]} id x y]
               (let [divisor (if (= type "long") y
                                 (if compound? (unchecked-int y)
                                     (case type "byte" (unchecked-byte y) "short" (unchecked-short y)
                                           "char" (int (unchecked-char y)) (unchecked-int y))))]
                 {:family "numeric" :case id :step "0" :operation method
                  :expected-exception (cond (= method "failure") (if (zero? x) "null-pointer" "index-out-of-bounds")
                                            (and (#{"div" "rem"} operator) (zero? divisor)) "arithmetic"
                                            :else "none")
                  :arguments [(str "i:" x) (str "i:" y)]}))
         fixed (mapcat (fn [{:keys [method type operator] :as template}]
                         (let [[_ lo hi] (first (filter #(= type (first %)) types))
                               pairs (cond (= method "constantOverflow") [[0 0]]
                                           (= method "failure") [[0 1] [1 1]]
                                           (or (#{"shl" "shr" "ushr"} operator)
                                               (#{"longShift" "intShiftLong"} method))
                                           [[lo -1] [hi 0] [-1 31] [lo 32] [lo 63] [lo 64]]
                                           :else [[lo -1] [hi 1] [-1 0] [lo 65] [hi -65] [1 32]])]
                           (map-indexed (fn [i [x y]] (row template (str method "-" i) x y)) pairs))) templates)
         regressions (map (fn [[method id x y]]
                            (row (first (filter #(= method (:method %)) templates)) id x y))
                          [["byteCompoundAdd" "saved-byte-value" 1 1]
                           ["shortCompoundAdd" "short-narrowing" 32767 1]
                           ["charCompoundSub" "char-narrowing" 0 1]
                           ["longCompoundShl" "long-distance" 1 64]
                           ["signedRead" "signed-byte-read" 128 0]])
         generated (for [i (range samples)
                         :let [template (nth templates (.nextInt rng (dec (count templates))))
                               x (.nextLong rng) y (.nextLong rng)]]
                     (row template (str "seed-" seed "-" i) x y))]
     (assoc base :id "numeric" :generator {:id :numeric-templates :version 1 :seed seed :samples samples}
            :sources {"subjects/micro/NumericCases.java" (source templates)}
            :operations (into (sorted-map)
                              (for [{:keys [method]} templates]
                                [method {:java-type "micro.NumericCases" :java-method method
                                         :dotnet-type "DripSharp.Microprograms.NumericCases"
                                         :dotnet-method (str (str/upper-case (subs method 0 1)) (subs method 1))
                                         :java-state "state" :dotnet-state "State"
                                         :parameters ["long" "long"]}]))
            :input (cases/write-cases (vec (concat regressions fixed generated)))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id] :or {seed 13 samples 32} :as options}]
   (let [record (if replay (util/read-single-edn-string! (slurp replay)) (snapshot seed samples))
         record (if case-id
                  (let [rows (filterv #(= case-id (:case %)) (cases/read-cases (:input record)))]
                    (when (empty? rows) (throw (ex-info "Unknown numeric case" {:case case-id})))
                    (assoc record :input (cases/write-cases rows))) record)
         _ (micro/validate-snapshot! record)
         parent (paths/resolve-path (paths/workspace-root) "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         file (Files/createTempFile parent "numeric-" ".edn" (make-array FileAttribute 0))]
     (spit (str file) (pr-str record))
     (micro/verify! (assoc (dissoc options :seed :samples :case-id) :replay (str file))))))

(defn -main [& arguments]
  (try
    (let [options (loop [args arguments result {}]
                    (if (empty? args) result
                        (let [[flag value & more] args
                              key ({"--seed" :seed "--samples" :samples "--replay" :replay "--case" :case-id} flag)]
                          (when-not (and key value (not (contains? result key)))
                            (throw (ex-info "Usage: [--seed N --samples 0..48 | --replay SNAPSHOT] [--case ID]" {})))
                          (recur more (assoc result key (if (#{:seed :samples} key) (parse-long value) value))))))
          _ (when (and (:replay options) (some #(contains? options %) [:seed :samples]))
              (throw (ex-info "Replay cannot regenerate inputs" {})))
          result (verify! options)]
      (prn result)
      (shutdown-agents)
      (when-not (= :passed (:status result)) (System/exit 1)))
    (catch Exception error
      (prn {:status :failed :message (.getMessage error) :data (ex-data error)})
      (shutdown-agents)
      (System/exit 2))))
