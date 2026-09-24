(ns dripsharp.concurrency-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]))

(defn- live-threads-with-prefix [prefix]
  (->> (.keySet (Thread/getAllStackTraces))
       (filter #(.isAlive ^Thread %))
       (filter #(.startsWith (.getName ^Thread %) prefix))))

(deftest bounded-executor-preserves-order-and-uses-multiple-workers
  (let [threads (atom #{})
        result (concurrency/call-with-executor
                {:worker-count 4 :thread-prefix "dripsharp-test"}
                #(concurrency/mapv-ordered
                  :test
                  (fn [value]
                    (swap! threads conj (.getName (Thread/currentThread)))
                    (Thread/sleep (long (- 20 value)))
                    (* value value))
                  (range 16)))]
    (is (= (mapv #(* % %) (range 16)) result))
    (is (< 1 (count @threads)))
    (is (every? #(.startsWith ^String % "dripsharp-test-") @threads))
    (is (empty? (live-threads-with-prefix "dripsharp-test-")))))

(deftest single-worker-and-nested-work-stay-sequential
  (testing "single-worker operation remains available"
    (let [threads (atom [])]
      (is (= [1 2 3]
             (concurrency/call-with-executor
              {:worker-count 1}
              #(concurrency/mapv-ordered
                :single
                (fn [value]
                  (swap! threads conj (.getName (Thread/currentThread)))
                  value)
                [1 2 3]))))
      (is (= 1 (count (distinct @threads))))))
  (testing "worker tasks do not submit nested work to the same bounded pool"
    (is (= [[0 1] [1 2]]
           (concurrency/call-with-executor
            {:worker-count 2}
            #(concurrency/mapv-ordered
              :outer
              (fn [value]
                (concurrency/mapv-ordered :inner (fn [nested] (+ value nested)) [0 1]))
              [0 1]))))))

(deftest worker-failure-has-stable-context
  (let [error (try
                (concurrency/call-with-executor
                 {:worker-count 3}
                 #(concurrency/mapv-ordered
                   :failure-test
                   (fn [value]
                     (when (= 1 value)
                       (throw (ex-info "boom" {:value value})))
                     value)
                   [0 1 2]))
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :concurrent-phase-failed (:kind (ex-data error))))
    (is (= :failure-test (:phase (ex-data error))))
    (is (= 1 (:item-index (ex-data error))))
    (is (= "boom" (some-> error .getCause .getMessage)))
    (is (empty? (live-threads-with-prefix "dripsharp-worker-")))))


(deftest failed-translation-cancels-running-siblings-without-deadlock
  (let [arrived (java.util.concurrent.CountDownLatch. 2)
        interrupted (atom 0)
        error (try
                (concurrency/call-with-executor
                 {:worker-count 3 :thread-prefix "dripsharp-cancel-test"}
                 #(concurrency/mapv-ordered
                   :translation
                   (fn [index]
                     (if (zero? index)
                       (do
                         (when-not (.await arrived 2 java.util.concurrent.TimeUnit/SECONDS)
                           (throw (ex-info "siblings never started" {})))
                         (throw (ex-info "first translation failed" {})))
                       (do
                         (.countDown arrived)
                         (try
                           (Thread/sleep 10000)
                           (catch InterruptedException error
                             (swap! interrupted inc)
                             (throw error))))))
                   (range 3)))
                nil
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= "first translation failed" (some-> error .getCause .getMessage)))
    (is (= 0 (:item-index (ex-data error))))
    (is (= 2 @interrupted))
    (is (empty? (live-threads-with-prefix "dripsharp-cancel-test")))))
