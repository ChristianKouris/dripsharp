(ns dripsharp.differential-generation
  "Process-free, bounded generation and reduction of recorded operation cases.
  Concrete tokens, stable step IDs and explicit handles survive PRNG changes."
  (:require [clojure.string :as str]
            [dripsharp.differential-cases :as cases]
            [dripsharp.util :as util])
  (:import [java.util Random]))

(def generator-identity {:id :java-compat-operations :version 6})
(def default-limits {:max-operations 32 :max-value-units 32 :max-input-bytes 65536})
(def generated-families
  "Families with an explicit generator/domain model. Kept separate from the
  larger fixed-case registry so registration never implies generation support."
  ["binary-data" "byte-array-input" "byte-array-output" "byte-buffer" "charset" "file"
   "filter-input" "filter-output" "long" "numeric" "random-access" "string"])

(defn- require! [condition message]
  (when-not condition (throw (ex-info message {:kind :invalid-generated-case}))))

(defn- limits! [limits]
  (require! (and (every? pos-int? (map limits (keys default-limits)))
                 (<= (:max-operations limits) 1000)
                 (<= (:max-value-units limits) 1024)
                 (<= (:max-input-bytes limits) 1048576))
            "Invalid generation limits")
  limits)

(defn- pick [^Random rng values] (nth values (.nextInt rng (count values))))
(defn- biased [^Random rng boundaries random-value]
  (if (< (.nextInt rng 4) 3) (pick rng boundaries) (random-value)))

(defn- utf16-token [value]
  (str "u:" (apply str (map #(format "%04x" (int %)) value))))

(defn value
  "Boundary-biased exact wire token. Path values are Java pathname strings,
  including empty/NUL/whitespace, not public FileInfo adaptations. No filesystem
  calls or expectations are inferred from them."
  [^Random rng kind max-units]
  (require! (and (pos-int? max-units) (<= max-units 1024)) "Invalid value limit")
  (let [length #(biased rng [0 1 (min 4 max-units) max-units]
                        (fn [] (.nextInt rng (inc max-units))))]
    (case kind
      :integer (str "i:" (biased rng [Long/MIN_VALUE Long/MAX_VALUE -2147483648
                                      2147483647 -1 0 1 9007199254740993]
                                 #(.nextLong rng)))
      :bytes (str "b:" (apply str (repeatedly (length)
                                              #(format "%02x" (biased rng [0 1 127 128 255]
                                                                      (fn [] (.nextInt rng 256)))))))
      :string (str "u:" (apply str (repeatedly (length)
                                               #(format "%04x" (biased rng [0 32 47 92 127 255 55296 56320 65535]
                                                                       (fn [] (.nextInt rng 65536)))))))
      :path (let [path (pick rng ["" " " "." ".." "/" "a/b" "a\\b" " a " "a\u0000b" "//a//b"])]
              (str "u:" (apply str (map #(format "%04x" (int %)) (take max-units path)))))
      (throw (ex-info "Unknown value generator" {:kind :invalid-generator :value-kind kind})))))

(defn- transition
  "Small input-domain model only: construction, cursor bounds, exception intent.
  Never computes the observed byte/int/hash result used by the differential."
  [state {:keys [family operation arguments step]}]
  (let [integer #(parse-long (subs % 2))
        byte-count #(quot (- (count %) 2) 2)]
    (case family
      "numeric"
      [(if (zero? (if (str/starts-with? operation "int-")
                    (unchecked-int (integer (second arguments)))
                    (integer (second arguments)))) "arithmetic" "none") state]

      "byte-buffer"
      (case operation
        "wrap" (if (= "n:" (first arguments))
                 ["null-pointer" state]
                 (let [size (byte-count (first arguments))]
                   ["none" {:creator step :active "root"
                            :views {"root" {:capacity size :limit size :position 0
                                            :mark nil :read-only false :order :big}}}]))
        (if (nil? state)
          ["null-pointer" state]
          (let [active (:active state) view (get-in state [:views active])
                integer #(parse-long (subs % 2))
                width (get {"get" 1 "get-at" 1 "put" 1 "put-at" 1
                            "get-short" 2 "get-short-at" 2 "put-short" 2 "put-short-at" 2
                            "get-int" 4 "get-int-at" 4 "put-int" 4 "put-int-at" 4
                            "get-long" 8 "get-long-at" 8 "put-long" 8 "put-long-at" 8}
                           operation)
                absolute? (str/ends-with? operation "-at")
                write? (str/starts-with? operation "put")
                index (when absolute? (integer (first arguments)))
                fits? (when width
                        (if absolute?
                          (and (not (neg? index)) (<= (+ index width) (:limit view)))
                          (<= (+ (:position view) width) (:limit view))))
                advance #(if absolute? % (update % :position + width))]
            (cond
              (= operation "capacity") ["none" state]
              (= operation "position") ["none" state]
              (= operation "limit") ["none" state]
              (= operation "remaining") ["none" state]
              (= operation "is-read-only") ["none" state]
              (= operation "select") ["none" (assoc state :active (case (first arguments)
                                                                    "u:0072006f006f0074" "root"
                                                                    "u:0076006900650077" "view"))]
              (= operation "order") ["none" (assoc-in state [:views active :order]
                                                      (if (= "u:006200690067" (first arguments)) :big :little))]
              (= operation "set-position")
              (let [value (integer (first arguments))]
                (if (or (neg? value) (> value (:limit view))) ["illegal-argument" state]
                    ["none" (-> state
                                (assoc-in [:views active :position] value)
                                (cond-> (and (some? (:mark view)) (> (:mark view) value))
                                  (assoc-in [:views active :mark] nil)))]))
              (= operation "set-limit")
              (let [value (integer (first arguments))]
                (if (or (neg? value) (> value (:capacity view))) ["illegal-argument" state]
                    ["none" (-> state
                                (assoc-in [:views active :limit] value)
                                (update-in [:views active :position] min value)
                                (cond-> (and (some? (:mark view)) (> (:mark view) value))
                                  (assoc-in [:views active :mark] nil)))]))
              (= operation "mark") ["none" (assoc-in state [:views active :mark] (:position view))]
              (= operation "reset") (if-let [mark (:mark view)]
                                      ["none" (assoc-in state [:views active :position] mark)]
                                      ["invalid-mark" state])
              (= operation "clear") ["none" (assoc-in state [:views active]
                                                      (assoc view :position 0 :limit (:capacity view) :mark nil))]
              (= operation "flip") ["none" (assoc-in state [:views active]
                                                     (assoc view :position 0 :limit (:position view) :mark nil))]
              (= operation "rewind") ["none" (assoc-in state [:views active]
                                                       (assoc view :position 0 :mark nil))]
              (= operation "duplicate") ["none" (assoc state
                                                        :views (assoc (:views state) "view"
                                                                      (assoc view :order :big))
                                                        :active "view")]
              (= operation "slice") (let [remaining (- (:limit view) (:position view))]
                                      ["none" (-> state
                                                  (assoc-in [:views "view"]
                                                            {:capacity remaining :limit remaining
                                                             :position 0 :mark nil
                                                             :read-only (:read-only view) :order :big})
                                                  (assoc :active "view"))])
              (= operation "read-only") ["none" (-> state
                                                    (assoc-in [:views "view"]
                                                              (assoc view :read-only true :order :big))
                                                    (assoc :active "view"))]
              width
              (cond
                (and write? (:read-only view)) ["read-only" state]
                (not fits?) [(if write? "buffer-overflow"
                                 (if absolute? "index-out-of-bounds" "buffer-underflow")) state]
                :else ["none" (update-in state [:views active] advance)])
              :else ["none" state]))))

      "binary-data"
      (case operation
        "open" ["none" {:size (byte-count (first arguments)) :position 0 :creator step}]
        (if (nil? state)
          ["null-pointer" state]
          (let [width (get {"read-byte" 1 "read-unsigned-byte" 1 "read-short" 2
                            "read-unsigned-short" 2 "read-int" 4 "read-long" 8}
                           operation)
                width (if (= operation "read-fully")
                        (integer (nth arguments 2)) width)
                remaining (- (:size state) (:position state))]
            (if width
              [(if (> width remaining) "eof" "none")
               (update state :position + (min width remaining))]
              ["none" state]))))

      "byte-array-input"
      (case operation
        "open" ["none" {:size (integer (nth arguments 2)) :position 0 :creator step}]
        (if (nil? state)
          ["null-pointer" state]
          (case operation
            "read-one" ["none" (update state :position #(min (:size state) (inc %)))]
            "read-buffer" (let [length (integer (nth arguments 2))]
                            ["none" (update state :position
                                            #(min (:size state) (+ % length)))])
            "skip" (let [count (integer (first arguments))]
                     ["none" (if (pos? count)
                               (update state :position #(min (:size state) (+ % count))) state)])
            ["none" state])))

      "filter-input"
      (case operation
        "open" ["none" {:size (byte-count (first arguments)) :position 0
                        :max-chunk (integer (second arguments))
                        :failure (integer (nth arguments 2)) :closed false :creator step}]
        (cond
          (nil? state) ["null-pointer" state]
          (= operation "close") ["none" (assoc state :closed true)]
          (:closed state) ["io" state]
          (= operation "read-one")
          (if (and (not= -1 (:failure state)) (>= (:position state) (:failure state)))
            ["io" state]
            ["none" (update state :position #(min (:size state) (inc %)))])
          (= operation "read-buffer")
          (let [length (integer (nth arguments 2))
                until-failure (if (= -1 (:failure state)) Long/MAX_VALUE
                                  (- (:failure state) (:position state)))]
            (if (and (pos? length) (<= until-failure 0))
              ["io" state]
              ["none" (update state :position
                              #(min (:size state)
                                    (+ % (max 0 (min length (:max-chunk state) until-failure)))))]))
          (= operation "skip")
          (let [count (integer (first arguments))]
            ["none" (if (pos? count)
                      (update state :position #(min (:size state) (+ % count))) state)])
          (= operation "reset") ["io" state]
          :else ["none" state]))

      "byte-array-output"
      (case operation
        "open" ["none" {:size 0 :creator step}]
        (if (nil? state)
          ["null-pointer" state]
          (case operation
            "write-one" ["none" (update state :size inc)]
            "write-buffer" ["none" (update state :size + (integer (nth arguments 2)))]
            "reset" ["none" (assoc state :size 0)]
            ["none" state])))

      "filter-output"
      (case operation
        "open" ["none" {:position 0 :failure (integer (first arguments))
                        :closed false :creator step}]
        (cond
          (nil? state) ["null-pointer" state]
          (= operation "close") ["none" (assoc state :closed true)]
          (:closed state)
          [(if (and (= operation "write-buffer")
                    (zero? (integer (nth arguments 2)))) "none" "io") state]
          (= operation "write-one")
          (if (and (not= -1 (:failure state)) (>= (:position state) (:failure state)))
            ["io" state] ["none" (update state :position inc)])
          (= operation "write-buffer")
          (let [length (integer (nth arguments 2))
                available (if (= -1 (:failure state)) Long/MAX_VALUE
                              (- (:failure state) (:position state)))
                written (max 0 (min length available))]
            [(if (< written length) "io" "none") (update state :position + written)])
          :else ["none" state]))

      "random-access"
      (case operation
        "open" ["none" {:size (byte-count (first arguments)) :position 0
                        :closed false :creator step}]
        (cond
          (nil? state) ["null-pointer" state]
          (= operation "close") ["none" (assoc state :closed true)]
          (:closed state)
          [(if (and (contains? #{"read-buffer" "read-fully"} operation)
                    (zero? (byte-count (first arguments)))) "none" "io") state]
          (= operation "read-one")
          ["none" (if (< (:position state) (:size state))
                    (update state :position inc) state)]
          (= operation "read-buffer")
          (let [length (integer (nth arguments 2))
                consumed (min length (max 0 (- (:size state) (:position state))))]
            ["none" (update state :position + consumed)])
          (= operation "read-fully")
          (let [length (byte-count (first arguments))
                remaining (max 0 (- (:size state) (:position state)))]
            [(if (> length remaining) "eof" "none")
             (update state :position + (min length remaining))])
          (= operation "write-buffer")
          (let [length (byte-count (first arguments))
                end (+ (:position state) length)]
            ["none" (assoc state :position end :size (max (:size state) end))])
          (= operation "seek")
          (let [position (integer (first arguments))]
            (if (neg? position) ["io" state] ["none" (assoc state :position position)]))
          (= operation "skip")
          (let [count (integer (first arguments))]
            ["none" (if (pos? count)
                      (update state :position +
                              (min count (max 0 (- (:size state) (:position state))))) state)])
          (= operation "set-length")
          (let [length (integer (first arguments))]
            (if (neg? length) ["io" state]
                ["none" (assoc state :size length :position (min length (:position state)))]))
          :else ["none" state]))

      [(if (= "n:" (first arguments)) "null-pointer" "none") state])))

(defn- generated-operation [^Random rng family state step max-units]
  (let [open? #(contains? #{"wrap" "open"} %)
        operation
        (case family
          "byte-buffer" (pick rng (if state ["wrap" "get" "get" "get-int"] ["wrap" "wrap" "get"]))
          "binary-data" (pick rng (if state ["open" "read-byte" "read-unsigned-byte"
                                             "read-short" "read-unsigned-short" "read-int"
                                             "read-long" "read-fully" "write-byte"
                                             "write-short" "write-int" "write-long"
                                             "write-buffer" "flush"]
                                      ["open" "open" "read-byte"]))
          "byte-array-input" (pick rng (if state ["open" "read-one" "read-buffer" "skip"
                                                  "available" "mark" "reset" "close"]
                                           ["open" "open" "read-one"]))
          "filter-input" (pick rng (if state ["open" "read-one" "read-buffer" "skip"
                                              "available" "mark-supported" "reset" "close"]
                                       ["open" "open" "read-one"]))
          "byte-array-output" (pick rng (if state ["open" "write-one" "write-buffer"
                                                   "size" "reset" "close"]
                                            ["open" "open" "write-one"]))
          "filter-output" (pick rng (if state ["open" "write-one" "write-buffer"
                                               "flush" "close"]
                                        ["open" "open" "write-one"]))
          "random-access" (pick rng (if state ["open" "read-one" "read-buffer" "read-fully"
                                               "write-buffer" "seek" "skip" "length"
                                               "position" "set-length" "close"]
                                        ["open" "open" "read-one"]))
          "numeric" (pick rng ["int-div" "int-rem" "long-div" "long-rem"])
          "charset" (pick rng ["decode" "stream" "string-ops"])
          "file" "inspect"
          "hash")
        bytes #(value rng :bytes max-units)
        small-int #(str "i:" (biased rng [-1 0 1 2 4 32] (fn [] (.nextInt rng 33))))
        arguments
        (case family
          "byte-buffer" (if (contains? #{"get" "get-int"} operation) []
                            [(if (zero? (.nextInt rng 8)) "n:" (bytes))])
          "binary-data"
          (case operation
            "open" [(bytes)]
            "read-fully" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                           [buffer "i:0" (str "i:" size)])
            "write-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                             [buffer "i:0" (str "i:" size)])
            ("write-byte" "write-short" "write-int") [(small-int)]
            "write-long" [(value rng :integer max-units)]
            [])
          "byte-array-input"
          (case operation
            "open" (let [data (bytes) size (quot (- (count data) 2) 2)] [data "i:0" (str "i:" size)])
            "read-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                            [buffer "i:0" (str "i:" size)])
            ("skip" "mark") [(small-int)] [])
          "filter-input"
          (case operation
            "open" (let [data (bytes) size (quot (- (count data) 2) 2)
                         failure (pick rng [-1 0 (quot size 2) size])]
                     [data (str "i:" (inc (.nextInt rng 4))) (str "i:" failure)])
            "read-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                            [buffer "i:0" (str "i:" size)])
            "skip" [(small-int)] [])
          "byte-array-output"
          (case operation
            "open" [(str "i:" (.nextInt rng 9))]
            "write-one" [(small-int)]
            "write-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                             [buffer "i:0" (str "i:" size)])
            [])
          "filter-output"
          (case operation
            "open" [(str "i:" (pick rng [-1 0 1 2 4 32]))]
            "write-one" [(small-int)]
            "write-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                             [buffer "i:0" (str "i:" size)])
            [])
          "random-access"
          (case operation
            "open" [(bytes) "u:00720077"]
            "read-buffer" (let [buffer (bytes) size (quot (- (count buffer) 2) 2)]
                            [buffer "i:0" (str "i:" size)])
            ("read-fully" "write-buffer") [(bytes)]
            ("seek" "skip" "set-length") [(small-int)] [])
          "numeric" [(value rng :integer max-units) (value rng :integer max-units)]
          "charset"
          (let [charset (utf16-token (pick rng ["UTF-8" "UTF-16" "UTF-16BE" "UTF-16LE"
                                                "US-ASCII" "ISO-8859-1" "windows-1252"]))
                data #(if (< max-units 2) "b:"
                          (value rng :bytes (quot max-units 2)))]
            (case operation
              "decode" (let [bytes (data)]
                         [charset (str "i:" (.nextInt rng 2))
                          (utf16-token (subs bytes 2))])
              "stream" (let [bytes (data)
                             plans (filterv #(<= (count %) max-units)
                                            ["1" "1,1" "1,2,1" "2,2,2"])]
                         [charset (utf16-token (subs bytes 2))
                          (utf16-token (pick rng plans))])
              "string-ops" (let [text (value rng :string max-units)
                                 size (quot (- (count text) 2) 4)
                                 begin (.nextInt rng (inc size))
                                 end (+ begin (.nextInt rng (inc (- size begin))))]
                             [text (str "i:" begin) (str "i:" end)])))
          "long" [(value rng :integer max-units)]
          "file" [(if (zero? (.nextInt rng 8)) "n:" (value rng :path max-units))]
          [(if (zero? (.nextInt rng 8)) "n:"
               (value rng (pick rng [:string :path]) max-units))])]
    {:family family :operation operation :arguments arguments :step step
     :constructor? (open? operation)}))

(defn- exact-bytes [^Random rng length]
  (str "b:" (apply str (repeatedly length
                                   #(format "%02x" (biased rng [0 1 127 128 255]
                                                           (fn [] (.nextInt rng 256))))))))

(defn- focused-specs [^Random rng family max-units]
  (let [size (min 16 max-units)
        bytes (exact-bytes rng size)
        data (exact-bytes rng (min 8 max-units))
        sentinel (exact-bytes rng (min 6 max-units))
        read-length (max 0 (min 4 (dec max-units)))]
    (case family
      "byte-buffer"
      [{:operation "wrap" :arguments [bytes]}
       {:operation "capacity" :arguments []}
       {:operation "limit" :arguments []}
       {:operation "position" :arguments []}
       {:operation "mark" :arguments []}
       {:operation "set-position" :arguments ["i:4"]}
       {:operation "reset" :arguments []}
       {:operation "order" :arguments ["u:006c006900740074006c0065"]}
       {:operation "get-short" :arguments []}
       {:operation "get-int-at" :arguments ["i:2"]}
       {:operation "put-int-at" :arguments ["i:4" "i:-2147483648"]}
       {:operation "set-position" :arguments ["i:4"]}
       {:operation "slice" :arguments []}
       {:operation "put" :arguments ["i:128"]}
       {:operation "select" :arguments ["u:0072006f006f0074"]}
       {:operation "get-at" :arguments ["i:4"]}
       {:operation "duplicate" :arguments []}
       {:operation "set-position" :arguments ["i:5"]}
       {:operation "select" :arguments ["u:0072006f006f0074"]}
       {:operation "read-only" :arguments []}
       {:operation "put" :arguments ["i:1"]}
       {:operation "select" :arguments ["u:0072006f006f0074"]}
       {:operation "set-limit" :arguments ["i:5"]}
       {:operation "set-position" :arguments ["i:5"]}
       {:operation "put" :arguments ["i:1"]}
       {:operation "get" :arguments []}
       {:operation "flip" :arguments []}
       {:operation "get-long" :arguments []}
       {:operation "clear" :arguments []}
       {:operation "put-long" :arguments ["i:-9223372036854775808"]}
       {:operation "rewind" :arguments []}
       {:operation "get-long" :arguments []}]

      "binary-data"
      [{:operation "open" :arguments [data]}
       {:operation "read-byte" :arguments []}
       {:operation "read-unsigned-byte" :arguments []}
       {:operation "read-short" :arguments []}
       {:operation "read-int" :arguments []}
       {:operation "read-long" :arguments []}
       {:operation "open" :arguments [data]}
       {:operation "read-byte" :arguments []}
       {:operation "read-long" :arguments []}
       {:operation "open" :arguments [data]}
       {:operation "read-fully" :arguments [sentinel "i:1" (str "i:" read-length)]}
       {:operation "write-byte" :arguments ["i:128"]}
       {:operation "write-short" :arguments ["i:-32768"]}
       {:operation "write-int" :arguments ["i:-2147483648"]}
       {:operation "write-long" :arguments ["i:-9223372036854775808"]}
       {:operation "write-buffer" :arguments [data "i:0" (str "i:" (min 3 size))]}
       {:operation "flush" :arguments []}]
      nil)))

(defn- sequence-rows [family specs]
  (:rows
   (reduce
    (fn [{:keys [state rows]} [index spec]]
      (let [operation (:operation spec) constructor? (contains? #{"wrap" "open"} operation)
            row {:family family :case "h0" :handle "h0" :step (str index)
                 :operation operation :arguments (:arguments spec)
                 :requires (if (and state (not constructor?)) [(:creator state)] [])}
            [exception next-state] (transition state row)]
        {:state next-state
         :rows (conj rows (assoc row :expected-exception exception
                                 :intent (if (= exception "none") :valid :invalid)))}))
    {:state nil :rows []} (map-indexed vector specs))))

(defn generate
  "Produces one concrete case once; replay consumes :operations, not this fn.
  Native (family, case) runner state keys carry the explicit :handle identity."
  ([seed] (generate seed {}))
  ([seed options]
   (let [families (or (:families options) (vec (remove #{"file"} generated-families)))
         _ (require! (and (vector? families) (seq families)
                          (every? (set generated-families) families))
                     "Invalid generated family selection")
         limits (limits! (merge default-limits (dissoc options :families)))
         rng (Random. (long seed))
         ;; Mix the first draw: adjacent java.util.Random seeds correlate in low bits.
         n (inc (int (mod (hash (.nextLong rng)) (:max-operations limits))))
         focused (when (= 1 (count families))
                   (focused-specs rng (first families) (:max-value-units limits)))
         operations
         (if focused
           (sequence-rows (first families) (take (:max-operations limits) focused))
           (loop [i 0 states {} rows []]
             (if (= i n)
               rows
               (let [family (pick rng families)
                     handle (str "h" (.nextInt rng 3))
                     key [family handle]
                     state (get states key)
                     generated (generated-operation rng family state (str i)
                                                    (:max-value-units limits))
                     operation (:operation generated)
                     arguments (:arguments generated)
                     row {:family family :case handle :handle handle :step (str i)
                          :operation operation :arguments arguments
                          :requires (if (and state (not (:constructor? generated)))
                                      [(:creator state)] [])}
                     [exception next-state] (transition state row)]
                 (recur (inc i) (assoc states key next-state)
                        (conj rows (assoc row :expected-exception exception
                                          :intent (if (= exception "none") :valid :invalid))))))))]
     {:schema-version 1 :generator generator-identity :seed seed
      :generator-runtime {:clojure (clojure-version) :prng "java.util.Random"
                          :java (System/getProperty "java.runtime.version")}
      :limits limits :operations operations})))

(defn- integer-token? [token]
  (and (string? token) (str/starts-with? token "i:")
       (<= Long/MIN_VALUE (bigint (subs token 2)) Long/MAX_VALUE)))

(defn- bytes-token? [token]
  (and (string? token) (str/starts-with? token "b:")))

(defn- utf16-text [token]
  (when (and (string? token) (re-matches #"u:(?:[0-9a-f]{4})*" token))
    (apply str (map #(char (Integer/parseInt (apply str %) 16))
                    (partition 4 (subs token 2))))))

(defn- hex-text? [token]
  (let [text (utf16-text token)]
    (and (some? text) (even? (count text))
         (boolean (re-matches #"[0-9a-f]*" text)))))

(defn- exact-buffer-range? [[buffer offset length]]
  (and (bytes-token? buffer) (integer-token? offset) (integer-token? length)
       (zero? (parse-long (subs offset 2)))
       (= (parse-long (subs length 2)) (quot (- (count buffer) 2) 2))))

(defn- buffer-range? [[buffer offset length]]
  (and (bytes-token? buffer) (integer-token? offset) (integer-token? length)
       (let [start (parse-long (subs offset 2))
             size (parse-long (subs length 2))
             capacity (quot (- (count buffer) 2) 2)]
         (and (not (neg? start)) (not (neg? size))
              (<= (+ start size) capacity)))))

(defn- operation-domain? [family operation arguments]
  (let [token (first arguments)]
    (case family
      "byte-buffer"
      (cond
        (= operation "wrap") (and (= 1 (count arguments))
                                  (or (= token "n:") (bytes-token? token)))
        (contains? #{"capacity" "position" "limit" "remaining" "is-read-only"
                     "mark" "reset" "clear" "flip" "rewind" "duplicate" "slice"
                     "read-only" "get" "get-short" "get-int" "get-long"} operation)
        (empty? arguments)
        (contains? #{"set-position" "set-limit" "get-at" "get-short-at" "get-int-at"
                     "get-long-at" "put" "put-short" "put-int" "put-long"} operation)
        (and (= 1 (count arguments)) (integer-token? token))
        (= operation "select") (or (= arguments ["u:0072006f006f0074"])
                                   (= arguments ["u:0076006900650077"]))
        (= operation "order") (or (= arguments ["u:006200690067"])
                                  (= arguments ["u:006c006900740074006c0065"]))
        (contains? #{"put-at" "put-short-at" "put-int-at" "put-long-at"} operation)
        (and (= 2 (count arguments)) (every? integer-token? arguments))
        :else false)
      "binary-data"
      (case operation
        "open" (and (= 1 (count arguments)) (bytes-token? token))
        "read-fully" (and (= 3 (count arguments))
                          (bytes-token? token)
                          (every? integer-token? (rest arguments))
                          (let [offset (parse-long (subs (second arguments) 2))
                                length (parse-long (subs (nth arguments 2) 2))
                                size (quot (- (count token) 2) 2)]
                            (and (not (neg? offset)) (not (neg? length))
                                 (<= (+ offset length) size))))
        "write-buffer" (buffer-range? arguments)
        ("write-byte" "write-short" "write-int" "write-long")
        (and (= 1 (count arguments)) (integer-token? token))
        ("read-byte" "read-unsigned-byte" "read-short" "read-unsigned-short"
                     "read-int" "read-long" "flush") (empty? arguments)
        false)
      "string" (and (= operation "hash") (= 1 (count arguments))
                    (or (= token "n:") (str/starts-with? token "u:")))
      "charset"
      (let [charset (utf16-text token)
            supported? (contains? #{"UTF-8" "UTF-16" "UTF-16BE" "UTF-16LE"
                                    "US-ASCII" "ISO-8859-1" "windows-1252"}
                                  charset)]
        (case operation
          "decode" (and (= 3 (count arguments)) supported?
                        (contains? #{"i:0" "i:1"} (second arguments))
                        (hex-text? (nth arguments 2)))
          "stream" (and (= 3 (count arguments)) supported?
                        (hex-text? (second arguments))
                        (boolean (re-matches #"[1-9][0-9]*(?:,[1-9][0-9]*)*"
                                             (or (utf16-text (nth arguments 2)) "!"))))
          "string-ops" (and (= 3 (count arguments)) (some? (utf16-text token))
                            (every? integer-token? (rest arguments))
                            (let [size (quot (- (count token) 2) 4)
                                  begin (parse-long (subs (second arguments) 2))
                                  end (parse-long (subs (nth arguments 2) 2))]
                              (<= 0 begin end size)))
          false))
      "numeric" (and (contains? #{"int-div" "int-rem" "long-div" "long-rem"} operation)
                     (= 2 (count arguments)) (every? integer-token? arguments))
      "long" (and (= operation "hash") (= 1 (count arguments)) (integer-token? token))
      "file" (and (= operation "inspect") (= 1 (count arguments))
                  (or (= token "n:") (str/starts-with? token "u:")))
      "byte-array-input"
      (case operation
        "open" (exact-buffer-range? arguments)
        "read-buffer" (exact-buffer-range? arguments)
        ("skip" "mark") (and (= 1 (count arguments)) (integer-token? token))
        ("read-one" "available" "reset" "close") (empty? arguments)
        false)
      "filter-input"
      (case operation
        "open" (and (= 3 (count arguments)) (bytes-token? token)
                    (every? integer-token? (rest arguments))
                    (pos? (parse-long (subs (second arguments) 2)))
                    (<= -1 (parse-long (subs (nth arguments 2) 2))
                        (quot (- (count token) 2) 2)))
        "read-buffer" (exact-buffer-range? arguments)
        "skip" (and (= 1 (count arguments)) (integer-token? token))
        ("read-one" "available" "mark-supported" "reset" "close") (empty? arguments)
        false)
      "byte-array-output"
      (case operation
        "open" (and (= 1 (count arguments)) (integer-token? token)
                    (not (neg? (parse-long (subs token 2)))))
        "write-one" (and (= 1 (count arguments)) (integer-token? token))
        "write-buffer" (exact-buffer-range? arguments)
        ("size" "reset" "close") (empty? arguments)
        false)
      "filter-output"
      (case operation
        "open" (and (= 1 (count arguments)) (integer-token? token)
                    (<= -1 (parse-long (subs token 2))))
        "write-one" (and (= 1 (count arguments)) (integer-token? token))
        "write-buffer" (exact-buffer-range? arguments)
        ("flush" "close") (empty? arguments)
        false)
      "random-access"
      (case operation
        "open" (and (= 2 (count arguments)) (bytes-token? token)
                    (= "u:00720077" (second arguments)))
        "read-buffer" (exact-buffer-range? arguments)
        ("read-fully" "write-buffer") (and (= 1 (count arguments)) (bytes-token? token))
        ("seek" "skip" "set-length") (and (= 1 (count arguments)) (integer-token? token))
        ("read-one" "length" "position" "close") (empty? arguments)
        false)
      false)))

(defn validate!
  "Validates bounded records and construction/use relationships. Shrinking must
  preserve each surviving operation's original valid/invalid exception intent."
  [record]
  (let [{:keys [operations limits]} record]
    (require! (and (= 1 (:schema-version record)) (map? (:generator record))
                   (keyword? (get-in record [:generator :id]))
                   (pos-int? (get-in record [:generator :version])) (integer? (:seed record)))
              "Invalid recorded generator identity")
    (limits! limits)
    (require! (and (vector? operations) (seq operations)
                   (<= (count operations) (:max-operations limits))) "Invalid operation count")
    (require! (<= (count (.getBytes (pr-str record) "UTF-8")) (:max-input-bytes limits))
              "Recorded input exceeds byte limit")
    (require! (= (cases/read-cases (cases/write-cases operations))
                 (mapv #(select-keys % (conj cases/input-keys :arguments)) operations))
              "Recorded operation fields must match wire values exactly")
    (reduce
     (fn [{:keys [states seen] :as acc} row]
       (let [{:keys [family operation arguments handle step requires expected-exception intent]} row
             state (get states [family handle])
             constructor? (contains? #{"wrap" "open"} operation)]
         (require! (and (= handle (:case row)) (string? handle)
                        (not (contains? seen step)) (vector? requires)
                        (= requires (if (and state (not constructor?)) [(:creator state)] []))
                        (every? seen requires)) "Invalid handle or construction dependency")
         (require! (operation-domain? family operation arguments) "Invalid operation domain")
         (doseq [[argument-index arg] (map-indexed vector arguments)]
           (when (and (contains? #{\b \u} (first arg))
                      (not (and (= family "charset") (zero? argument-index))))
             (require! (<= (quot (- (count arg) 2) (if (= \b (first arg)) 2 4))
                           (:max-value-units limits)) "Value exceeds unit limit")))
         (let [[exception next-state] (transition state row)]
           (require! (and (= expected-exception exception)
                          (= intent (if (= exception "none") :valid :invalid)))
                     "Candidate changes operation exception intent")
           (-> acc (assoc-in [:states [family handle]] next-state) (update :seen conj step)))))
     {:states {} :seen #{}} operations)
    record))

(defn write-record! [file record]
  (validate! record)
  (spit (str file) (str (pr-str record) "\n"))
  record)

(defn read-record [file]
  (require! (<= (.length (java.io.File. (str file))) 1048576) "Record exceeds 1 MiB")
  (validate! (util/read-single-edn-string! (slurp (str file)))))

(defn replay!
  "Executes persisted input without consulting its generator or seed."
  [file evaluate!]
  (evaluate! (read-record file)))

(defn failure-signature
  "Only semantic differences qualify. Stable identity, exception categories and
  differing fields prevent a new exception or harness failure replacing a bug."
  [result]
  (let [{:keys [kind identity expected-exception oracle probe]} (:first-difference result)]
    (when (and (= :failed (:status result)) (= :semantic-difference kind)
               (map? identity) (map? oracle) (map? probe))
      {:identity identity :expected-exception expected-exception
       :exceptions [(:exception oracle) (:exception probe)]
       :fields (filterv #(not= (get oracle %) (get probe %)) [:exception :result :state])})))

(defn- units [token]
  (let [width (if (str/starts-with? token "b:") 2 4)]
    (mapv #(apply str %) (partition width (subs token 2)))))

(defn- token-cost [token]
  (case (first token)
    \i [(count token) (abs (bigint (subs token 2)))]
    (\b \u) [(count token) (reduce + 0 (map #(Long/parseLong % 16) (units token)))]
    [0 0]))

(defn- complexity [record]
  (let [costs (map token-cost (mapcat :arguments (:operations record)))]
    [(count (:operations record)) (reduce + 0 (map first costs))
     (reduce + 0 (map second costs))
     (count (filter #(= "get-int" (:operation %)) (:operations record)))]))

(defn- smaller-tokens [token]
  (let [prefix (subs token 0 2)]
    (case (first token)
      \i (let [n (bigint (subs token 2))]
           (map #(str "i:" %) (distinct [0 (if (neg? n) -1 1) (quot n 2)])))
      (\b \u)
      (let [parts (units token) n (count parts) zero (if (= prefix "b:") "00" "0000")]
        (map #(str prefix (apply str %))
             (concat [[] (subvec parts 0 (quot n 2)) (subvec parts (quot n 2))]
                     (for [i (range n)] (vec (concat (subvec parts 0 i) (subvec parts (inc i)))))
                     (for [i (range n)] (assoc parts i zero)))))
      [])))

(defn candidates
  "Lazy reductions. Removing a constructor also removes its explicit users;
  domain validation subsequently rejects altered cursor/exception preconditions."
  [record]
  (let [ops (:operations record) n (count ops)
        removals
        (for [width (distinct (take-while pos? (iterate #(quot % 2) n)))
              start (range 0 n width)]
          (let [removed (set (map :step (subvec ops start (min n (+ start width)))))
                kept (reduce (fn [{:keys [gone rows]} op]
                               (if (or (gone (:step op)) (some gone (:requires op)))
                                 {:gone (conj gone (:step op)) :rows rows}
                                 {:gone gone :rows (conj rows op)}))
                             {:gone removed :rows []} ops)]
            (assoc record :operations (:rows kept))))
        values (for [i (range n) j (range (count (:arguments (nth ops i))))
                     token (smaller-tokens (get-in ops [i :arguments j]))]
                 (assoc-in record [:operations i :arguments j] token))
        simpler (for [i (range n) :when (= "get-int" (:operation (nth ops i)))]
                  (assoc-in record [:operations i :operation] "get"))]
    (concat removals values simpler)))

(def empty-counts {:generated 0 :executed 0 :rejected 0 :failed 0 :timed-out 0 :passed 0 :harness-failed 0})

(defn count-result [counts result]
  (cond
    (= :passed (:status result)) (-> counts (update :executed inc) (update :passed inc))
    (failure-signature result) (-> counts (update :executed inc) (update :failed inc))
    (or (= :timeout (get-in result [:first-difference :kind]))
        (= :timeout (get-in result [:first-difference :execution :status])))
    (update counts :timed-out inc)
    :else (update counts :harness-failed inc)))

(defn evaluate
  "Ordinary evaluator exceptions are harness failures, never semantic failures."
  [evaluate! record]
  (try (evaluate! record)
       (catch Exception error
         {:status :failed :first-difference {:kind :evaluator-error
                                             :message (.getMessage error)}})))

(defn shrink!
  "Greedy bounded reducer. evaluate! receives (record remaining-ms); live
  callbacks MUST cap every process timeout to remaining-ms and their cumulative
  process budget. No futures/background callbacks are left running. Pure tests
  may use a constant-time predicate. Attempts count invalid candidates too."
  [record original evaluate! {:keys [max-attempts max-ms now-ns]
                              :or {max-attempts 100 max-ms 10000 now-ns #(System/nanoTime)}}]
  (validate! record)
  (require! (and (nat-int? max-attempts) (pos-int? max-ms)) "Invalid shrink bounds")
  (let [signature (failure-signature original)
        _ (require! signature "Cannot shrink a non-semantic failure")
        deadline (+ (now-ns) (* 1000000 max-ms))]
    (loop [best record result original todo (seq (candidates record))
           attempts 0 accepted 0 counts empty-counts]
      (let [remaining (quot (- deadline (now-ns)) 1000000)
            reason (cond (>= attempts max-attempts) :attempt-limit
                         (<= remaining 0) :time-limit (nil? todo) :exhausted)]
        (if reason
          {:record best :result result :signature signature :attempts attempts
           :accepted accepted :stop reason :counts counts}
          (let [candidate (first todo)
                counts (update counts :generated inc)
                valid? (and (neg? (compare (complexity candidate) (complexity best)))
                            (try (validate! candidate) true (catch Exception _ false)))]
            (if-not valid?
              (recur best result (next todo) (inc attempts) accepted (update counts :rejected inc))
              (let [actual (evaluate #(evaluate! % remaining) candidate)
                    same? (= signature (failure-signature actual))]
                (recur (if same? candidate best) (if same? actual result)
                       (if same? (seq (candidates candidate)) (next todo))
                       (inc attempts) (+ accepted (if same? 1 0)) (count-result counts actual))))))))))
