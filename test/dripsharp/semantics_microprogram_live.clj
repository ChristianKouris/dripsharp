(ns dripsharp.semantics-microprogram-live
  "Explicit local executable tests, excluded from ordinary test discovery."
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.differential-cases :as cases]
            [dripsharp.paths :as paths]
            [dripsharp.semantics-microprogram :as semantics]))

(deftest translated-language-semantics-match-jvm-values-state-and-event-order
  (let [result (semantics/verify!)
        _ (println "Semantics batch:" (:status result) (:directory result))]
    (is (= :passed (:status result)) (pr-str result))
    (when (= :passed (:status result))
      (let [observed (into {} (map (juxt :case identity)
                                   (cases/read-observations
                                    (slurp (str (paths/resolve-path (:directory result) "java.tsv"))))))
            state #(semantics/text-value (:state (observed %)))
            value #(:result (observed %))]
        (is (= 42 (:matched result)))
        (is (= "array;index;rhs;receiver;left;right;call;|count=7" (state "order-0")))
        (is (= "and-left;or-left;or-right;condition;else;|count=5" (state "shortCircuit-0")))
        (is (= "long;Integer;Object;" (state "overloads-0")))
        (is (= "i:123" (value "overloads-0")))
        (is (= "null-pointer" (:exception (observed "unbox-0"))))
        (is (= "before-unbox;|count=1" (state "unbox-0")))
        (is (= "class-cast" (:exception (observed "cast-2"))))
        (is (= "before-cast;|count=1" (state "cast-2")))
        (is (= "child;child;parent;default;override;default;|count=6" (state "dispatch-0")))
        (is (= "generic-child;|count=1" (state "bridge-0")))
        (is (= "value!" (semantics/text-value (value "bridge-0"))))
        (is (= "try;return;finally;inner-finally;outer-finally;|count=5" (state "control-0")))
        (is (= "try;finally;inner-catch;inner-finally;outer-catch;outer-finally;|count=6" (state "control-1")))
        (is (= "try;return;finally;inner-finally;outer-catch;outer-finally;|count=6" (state "control-2")))
        (is (= "open-first;open-second;body;close-second;close-first;caught-body;cleanup-finally;|count=7"
               (state "cleanup-3")))
        (is (= "i:2" (value "cleanup-3")))
        (is (= (str "before-new;parent-static-field;parent-static-block;parent-after-block;"
                    "child-static-field;child-static-block;this-argument;super-argument;"
                    "parent-field;parent-block;parent-constructor;child-inspect-0;"
                    "child-field;child-constructor;delegating-constructor;after-new;child-inspect-4;")
               (state "initialization-0") (state "initialization-1")))))))

(deftest dispatch-and-operand-order-negative-controls-are-replayable
  (doseq [[case-id change expected-index expected-event actual-event]
          [["dispatch-0" {:file "DripSharp/Microprograms/SemanticsCases.cs"
                          :before "return base.Speak();" :after "return this.Speak();"}
            2 "parent" "child"]
           ["order-0" {:file "DripSharp/Microprograms/SemanticsCases.cs"
                       :before (str "combine(global::DripSharp.Microprograms.SemanticsCases.@event(\"left\",\n"
                                    "      x), global::DripSharp.Microprograms.SemanticsCases.@event(\"right\", y))")
                       :after (str "combine(global::DripSharp.Microprograms.SemanticsCases.@event(\"right\", y), "
                                   "global::DripSharp.Microprograms.SemanticsCases.@event(\"left\", x))")}
            4 "left" "right"]]]
    (testing case-id
      (let [result (semantics/verify! {:samples 0 :case-id case-id :perturbation change})
            replay (with-redefs [semantics/snapshot (fn [& _] (throw (AssertionError. "Replay regenerated input")))]
                     (semantics/verify! {:replay (:snapshot result)}))]
        (println "Semantics negative control:" case-id (:directory result))
        (doseq [run [result replay]]
          (is (= :failed (:status run)) (pr-str run))
          (is (= :semantic-difference (get-in run [:first-difference :kind])) (pr-str run))
          (is (= case-id (get-in run [:first-difference :identity :case])))
          (is (= {:index expected-index :oracle expected-event :probe actual-event}
                 (select-keys (:first-trace-difference run) [:index :oracle :probe]))))
        (is (= (:first-difference result) (:first-difference replay)))))))
