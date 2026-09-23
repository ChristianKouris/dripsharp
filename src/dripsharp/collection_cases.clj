(ns dripsharp.collection-cases
  "Process-free admission and encoding for bounded collection command scripts."
  (:require [clojure.string :as str]))

(defn encode [operations]
  (str "u:" (apply str (map #(format "%04x" (int %))
                             (str/join ";" (map #(str/join "," %) operations))))))

(defn text [token]
  (apply str (map #(char (Integer/parseInt (apply str %) 16))
                  (partition 4 (subs token 2)))))

(defn decode [token]
  (mapv #(mapv parse-long (str/split % #",")) (str/split (text token) #";")))

(defn valid-arguments?
  "Restrict the key/value domain so canonical observations cannot omit entries.
  HashMap value removal with duplicate matches is deliberately not admitted."
  [[order script :as arguments]]
  (and (= 2 (count arguments)) (#{"i:0" "i:1" "i:2"} order)
       (string? script) (<= (count script) 4096)
       (re-matches #"u:(?:[0-9a-f]{4})+" script)
       (re-matches #"(?:[0-9]|1[0-9]|2[0-2]),(?:-1|[0-9]),(?:-1|[0-3])(?:;(?:[0-9]|1[0-9]|2[0-2]),(?:-1|[0-9]),(?:-1|[0-3]))*"
                   (text script))
       (let [operations (decode script)]
         (and (<= 1 (count operations) 32)
              (loop [[[op id value] & more] operations values {}]
                (if (nil? op) true
                    (let [matches (filter #(= value (val %)) values)]
                      (and (or (#{8 10} op) (<= -1 id 3))
                           (not (and (= order "i:0") (= op 4) (> (count matches) 1)))
                           (recur more
                                  (case op
                                    0 (assoc values id value)
                                    (2 3) (dissoc values id)
                                    4 (if (= 1 (count matches)) (dissoc values (key (first matches))) values)
                                    5 (if (contains? values id) (assoc values id value) values)
                                    6 (if (= value (get values id ::absent)) (dissoc values id) values)
                                    (19 20 21) {}
                                    values))))))))))

(defn first-trace-difference [result]
  (let [difference (:first-difference result)]
    (when (= :semantic-difference (:kind difference))
      (let [expected (get-in difference [:oracle :result])
            actual (get-in difference [:probe :result])]
        (when (and (string? expected) (string? actual) (str/starts-with? expected "u:") (str/starts-with? actual "u:"))
          (let [left (str/split-lines (text expected))
                right (str/split-lines (text actual))]
            (first (keep (fn [index]
                           (when (not= (get left index) (get right index))
                             {:index index :oracle (get left index) :probe (get right index)
                              :identity (:identity difference)}))
                         (range (max (count left) (count right)))))))))))
