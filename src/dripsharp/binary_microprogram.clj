(ns dripsharp.binary-microprogram
  "Bounded ByteBuffer/DataInput/DataOutput templates translated to C#."
  (:require [dripsharp.differential-cases :as cases]
            [dripsharp.microprogram :as micro]
            [dripsharp.paths :as paths]
            [dripsharp.util :as util])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Random]))

(defn- source []
  (str
   "// SPDX-FileCopyrightText: 2026 Isak Sky\n"
   "// SPDX-License-Identifier: Apache-2.0\n"
   "package micro;\n"
   "import java.io.ByteArrayInputStream;\n"
   "import java.io.ByteArrayOutputStream;\n"
   "import java.io.DataInputStream;\n"
   "import java.io.DataOutputStream;\n"
   "import java.nio.ByteBuffer;\n"
   "import java.nio.ByteOrder;\n"
   "public final class BinaryCases {\n"
   "private static String trace = \"\";\n"
   "public static String state() { return trace; }\n"
   "private static byte[] bytes(String value) { byte[] result = new byte[value.length()]; for (int i = 0; i < result.length; i++) result[i] = (byte)value.charAt(i); return result; }\n"
   "private static String hex(byte[] value) { char[] digits = \"0123456789abcdef\".toCharArray(); StringBuilder result = new StringBuilder(); for (byte item : value) { result.append(digits[(item >>> 4) & 15]); result.append(digits[item & 15]); } return result.toString(); }\n"
   "public static long parse(String input, int little) { byte[] data = bytes(input); ByteBuffer root = ByteBuffer.wrap(data); root.order(little == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN); root.position(1); root.mark(); ByteBuffer slice = root.slice(); slice.order(root.order()); int value = slice.getInt(); slice.put(0, (byte)(slice.get(0) ^ 255)); ByteBuffer duplicate = root.duplicate(); duplicate.position(root.position() + 1); root.reset(); trace = hex(data) + \"|\" + root.position() + \"|\" + slice.position() + \"|\" + duplicate.position() + \"|\" + root.limit() + \"|\" + slice.capacity(); return (((long)value) << 32) ^ (slice.getInt(0) & 0xffffffffL); }\n"
   "public static long data(long value, int width) throws Exception { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes); if (width == 1) output.writeByte((int)value); else if (width == 2) output.writeShort((int)value); else if (width == 4) output.writeInt((int)value); else output.writeLong(value); output.flush(); byte[] encoded = bytes.toByteArray(); DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded)); long decoded; if (width == 1) decoded = input.readByte(); else if (width == 2) decoded = input.readShort(); else if (width == 4) decoded = input.readInt(); else decoded = input.readLong(); trace = hex(encoded) + \"|0\"; return decoded; }\n"
   "}\n"))

(defn- byte-string-token [^Random rng]
  (str "u:" (apply str (repeatedly 9 #(format "%04x" (.nextInt rng 256))))))

(defn snapshot
  "Returns fixed boundary rows plus at most 24 seeded translated samples."
  ([] (snapshot 23 12))
  ([seed samples]
   (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE)
                  (integer? samples) (<= 0 samples 24))
     (throw (ex-info "Require a signed 64-bit seed and 0..24 samples"
                     {:kind :candidate-rejected})))
   (let [base (micro/read-batch (paths/workspace-root) micro/default-batch)
         rng (Random. (long seed))
         row (fn [case operation arguments]
               {:family "binary" :case case :step "0" :operation operation
                :expected-exception "none" :arguments arguments})
         fixed [(row "parse-big" "parse" ["u:000000010002000300040005000600070008" "i:0"])
                (row "parse-little" "parse" ["u:008000ff0001000200030004000500060007" "i:1"])
                (row "byte-min" "data" ["i:-128" "i:1"])
                (row "short-min" "data" ["i:-32768" "i:2"])
                (row "int-min" "data" ["i:-2147483648" "i:4"])
                (row "long-min" "data" ["i:-9223372036854775808" "i:8"])
                (row "long-max" "data" ["i:9223372036854775807" "i:8"])]
         generated (for [index (range samples)
                         :let [width (nth [1 2 4 8] (.nextInt rng 4))]]
                     (if (zero? (.nextInt rng 2))
                       (row (str "seed-" seed "-parse-" index) "parse"
                            [(byte-string-token rng) (str "i:" (.nextInt rng 2))])
                       (row (str "seed-" seed "-data-" index) "data"
                            [(str "i:" (.nextLong rng)) (str "i:" width)])))]
     (assoc base
            :id "binary"
            :generator {:id :binary-templates :version 1 :seed seed :samples samples}
            :sources {"subjects/micro/BinaryCases.java" (source)}
            :operations {"parse" {:java-type "micro.BinaryCases" :java-method "parse"
                                   :dotnet-type "DripSharp.Microprograms.BinaryCases"
                                   :dotnet-method "Parse" :java-state "state"
                                   :dotnet-state "State" :parameters ["string" "int"]}
                         "data" {:java-type "micro.BinaryCases" :java-method "data"
                                  :dotnet-type "DripSharp.Microprograms.BinaryCases"
                                  :dotnet-method "Data" :java-state "state"
                                  :dotnet-state "State" :parameters ["long" "int"]}}
            :input (cases/write-cases (vec (concat fixed generated)))))))

(defn verify!
  ([] (verify! {}))
  ([{:keys [seed samples replay case-id] :or {seed 23 samples 12} :as options}]
   (let [record (if replay (util/read-single-edn-string! (slurp replay))
                    (snapshot seed samples))
         record (if case-id
                  (let [rows (filterv #(= case-id (:case %))
                                      (cases/read-cases (:input record)))]
                    (when (empty? rows)
                      (throw (ex-info "Unknown binary case" {:case case-id})))
                    (assoc record :input (cases/write-cases rows)))
                  record)
         _ (micro/validate-snapshot! record)
         parent (paths/resolve-path (paths/workspace-root) "validation-output/microprograms")
         _ (Files/createDirectories parent (make-array FileAttribute 0))
         file (Files/createTempFile parent "binary-" ".edn" (make-array FileAttribute 0))]
     (spit (str file) (pr-str record))
     (micro/verify! (assoc (dissoc options :seed :samples :case-id) :replay (str file))))))

(defn -main [& arguments]
  (try
    (let [options (loop [args arguments result {}]
                    (if (empty? args) result
                        (let [[flag value & more] args
                              key ({"--seed" :seed "--samples" :samples
                                    "--replay" :replay "--case" :case-id} flag)]
                          (when-not (and key value (not (contains? result key)))
                            (throw (ex-info
                                    "Usage: [--seed N --samples 0..24 | --replay SNAPSHOT] [--case ID]"
                                    {})))
                          (recur more (assoc result key
                                             (if (#{:seed :samples} key)
                                               (parse-long value) value))))))
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
