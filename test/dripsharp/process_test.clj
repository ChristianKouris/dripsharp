(ns dripsharp.process-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.process :as process]))

(deftest command-failure-is-explicit
  (testing "nonzero commands preserve their exit and merged output"
    (let [error (try
                  (process/run! {:command ["sh" "-c" "printf harness-failure >&2; exit 17"]
                                 :directory "."})
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (some? error))
      (is (= :command-failed (:kind (ex-data error))))
      (is (= 17 (:exit (ex-data error))))
      (is (= "harness-failure" (:output (ex-data error)))))))

(deftest display-command-redacts-secret-arguments-from-failures
  (let [secret "process-test-secret"
        display-command ["sh" "-c" "exit 17" "<redacted>"]
        error
        (try
          (process/run! {:command ["sh" "-c" "exit 17" secret]
                         :display-command display-command
                         :directory "."})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :command-failed (:kind (ex-data error))))
    (is (= display-command (:command (ex-data error))))
    (is (not (str/includes? (str (ex-message error) (ex-data error))
                            secret)))))

(deftest invalid-display-command-is-explicit
  (let [error
        (try
          (process/run! {:command ["true"]
                         :display-command ["true" "extra"]
                         :directory "."})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-display-command (:kind (ex-data error))))))

(deftest command-timeout-is-explicit-and-terminates-the-process
  (let [error (try
                (process/run! {:command ["sh" "-c" "printf started; while :; do :; done"]
                               :directory "."
                               :timeout-ms 50})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (some? error))
    (is (= :command-timeout (:kind (ex-data error))))
    (is (= 50 (:timeout-ms (ex-data error))))
    (is (= "started" (:output (ex-data error))))))

(deftest command-environment-can-be-removed-explicitly
  (let [name "DRIPSHARP_PROCESS_TEST_REMOVED"
        result
        (process/run!
         {:command
          ["sh" "-c"
           (str "if printenv " name " >/dev/null; "
                "then printf present; else printf absent; fi")]
          :directory "."
          :environment {name "host-controlled"}
          :unset-environment #{name}})]
    (is (= "absent" (:output result)))))

(deftest invalid-command-environment-removals-are-explicit
  (let [error
        (try
          (process/run! {:command ["true"]
                         :directory "."
                         :unset-environment #{"VALID" nil}})
          nil
          (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-command-environment-removals
           (:kind (ex-data error))))
    (is (= #{"VALID" nil}
           (:unset-environment (ex-data error))))))

(deftest java-tool-options-launcher-diagnostic-is-not-program-output
  (is (= "# contract\nrow\n"
         (process/without-java-tool-options-banner
          (str "Picked up JAVA_TOOL_OPTIONS: -Xmx28g\n"
               "# contract\nrow\n"))))
  (is (= "ordinary output"
         (process/without-java-tool-options-banner "ordinary output"))))

(defn- timeout-error
  [options]
  (try
    (process/run! options)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest denied-enumeration-preserves-timeout-and-primary-cleanup
  (doseq [denial [(RuntimeException. "Operation not permitted (sysctl failed)")
                  (SecurityException. "denied")]]
    (let [primary (atom nil)
          output-file (atom nil)
          snapshot @#'process/output-snapshot
          error
          (with-redefs [process/process-descendants
                        (fn [child]
                          (reset! primary child)
                          (throw denial))
                        process/output-snapshot
                        (fn [file]
                          (reset! output-file file)
                          (snapshot file))]
            (timeout-error
             {:command ["sh" "-c" "printf started; while :; do :; done"]
              :directory "." :timeout-ms 100}))
          data (ex-data error)]
      (is (= :command-timeout (:kind data)))
      (is (= 100 (:timeout-ms data)))
      (is (= "started" (:output data)))
      (is (false? (.isAlive ^Process @primary)))
      (is (true? (get-in data [:cleanup :primary :terminated?])))
      (is (= {:status :failed :error-class (.getName (class denial))}
             (get-in data [:cleanup :descendant-enumeration])))
      (is (= :unconfirmed (get-in data [:cleanup :descendant-cleanup])))
      (is (false? (.exists ^java.io.File @output-file))))))

(defn- with-child-fixture
  "The shell records only its own child's PID. The fixture never enumerates
  other processes, and the child has a finite lifetime if the test fails."
  [f]
  (let [pid-file (java.io.File/createTempFile "dripsharp-process-child-" ".pid")
        child (atom nil)
        child-handle (fn []
                       (or @child
                           (when-let [pid (parse-long (str/trim (slurp pid-file)))]
                             (let [handle (.orElse (java.lang.ProcessHandle/of pid) nil)]
                               (reset! child handle)
                               handle))))]
    (try
      (f pid-file child-handle)
      (finally
        (when-let [^java.lang.ProcessHandle handle (child-handle)]
          (.destroyForcibly handle)
          (.get (.onExit handle) 2 java.util.concurrent.TimeUnit/SECONDS))
        (.delete pid-file)))))

(defn- child-command
  [pid-file wait?]
  ["sh" "-c"
   (str "sleep 10 & child=$!; printf '%s' \"$child\" > \"$1\"; printf started; "
        (when wait? "wait \"$child\""))
   "process-test" (str pid-file)])

(deftest denied-enumeration-does-not-wait-for-inherited-output-handles
  (with-child-fixture
    (fn [pid-file child-handle]
      (let [primary (atom nil)
            start (System/nanoTime)
            error (with-redefs [process/process-descendants
                                (fn [child]
                                  (reset! primary child)
                                  (throw (SecurityException. "denied")))]
                    (timeout-error {:command (child-command pid-file true)
                                    :directory "." :timeout-ms 200}))
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
            data (ex-data error)
            ^java.lang.ProcessHandle child (child-handle)]
        (is (= :command-timeout (:kind data)))
        (is (= "started" (:output data)))
        (is (< elapsed-ms 3000) "must return before the inherited handle closes")
        (is (false? (.isAlive ^Process @primary)))
        (is (some? child))
        (when child (is (.isAlive child) "unknown descendant was not claimed stopped"))
        (is (= :unconfirmed (get-in data [:cleanup :descendant-cleanup])))))))

(deftest enumerated-descendants-are-still-terminated-on-timeout
  (with-child-fixture
    (fn [pid-file child-handle]
      (let [error
            ;; Supply the fixture's known handle to exercise real tree cleanup
            ;; even in sandboxes that forbid ProcessHandle.descendants().
            (with-redefs [process/process-descendants
                          (fn [_] [(child-handle)])]
              (timeout-error {:command (child-command pid-file true)
                              :directory "." :timeout-ms 200}))
            data (ex-data error)
            ^java.lang.ProcessHandle child (child-handle)]
        (is (= :command-timeout (:kind data)))
        (is (= "started" (:output data)))
        (is (true? (get-in data [:cleanup :primary :terminated?])))
        (is (= :complete (get-in data [:cleanup :descendant-enumeration :status])))
        (is (= [(.pid child)] (mapv :pid (get-in data [:cleanup :descendants]))))
        (is (every? :terminated? (get-in data [:cleanup :descendants])))
        (is (false? (.isAlive child)))))))

(deftest primary-exit-does-not-wait-for-inherited-output-handles
  (with-child-fixture
    (fn [pid-file child-handle]
      (let [start (System/nanoTime)
            result (process/run! {:command (child-command pid-file false)
                                  :directory "." :timeout-ms 200})
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (is (= 0 (:exit result)))
        (is (= "started" (:output result)))
        (is (< elapsed-ms 3000))
        (is (.isAlive ^java.lang.ProcessHandle (child-handle)))))))

(deftest cleanup-failures-and-waits-remain-explicit-and-bounded
  (let [attempted? (atom false)
        wait-args (atom nil)
        primary (proxy [Process] []
                  (destroyForcibly []
                    (reset! attempted? true)
                    (throw (SecurityException. "denied")))
                  (waitFor
                    ([] (throw (AssertionError. "unbounded wait")))
                    ([timeout unit]
                     (reset! wait-args [timeout unit])
                     false)))
        cleanup (with-redefs-fn
                  {#'process/process-descendants
                   (fn [_] (throw (SecurityException. "denied")))}
                  #(#'process/destroy-process-tree! primary))]
    (is @attempted?)
    (is (= {:status :failed :error-class "java.lang.SecurityException"}
           (:descendant-enumeration cleanup)))
    (is (= {:error-class "java.lang.SecurityException"}
           (get-in cleanup [:primary :destroy])))
    (is (false? (get-in cleanup [:primary :terminated?])))
    (is (<= 0 (first @wait-args) 1000))
    (is (= java.util.concurrent.TimeUnit/MILLISECONDS (second @wait-args)))))

(deftest output-capture-preserves-multibyte-text-across-buffer-boundaries
  (let [text (apply str (repeat 10000 "éλ"))
        result (process/run! {:command ["sh" "-c" "printf '%s' \"$1\"; printf '%s' \"$2\" >&2"
                                        "process-test" text "stderr"]
                              :directory "." :timeout-ms 1000})]
    (is (= (str text "stderr") (:output result)))))

(deftest output-read-failure-does-not-replace-the-timeout
  (let [secret "process-test-secret"
        display ["sh" "-c" "<redacted>"]
        error (with-redefs [process/output-snapshot
                            (fn [_] (throw (java.io.IOException. secret)))
                            process/process-descendants
                            (fn [_] (throw (SecurityException. secret)))]
                (timeout-error {:command ["sh" "-c" "while :; do :; done"]
                                :display-command display
                                :directory "." :timeout-ms 100}))
        data (ex-data error)]
    (is (= :command-timeout (:kind data)))
    (is (= display (:command data)))
    (is (= {:error-class "java.io.IOException"} (:output-error data)))
    (is (true? (get-in data [:cleanup :primary :terminated?])))
    (is (not (str/includes? (str (ex-message error) data) secret)))))

(deftest command-start-failure-remains-explicit
  (let [error (timeout-error {:command ["./no-such-dripsharp-process-test-command"]
                              :directory "." :timeout-ms 100})]
    (is (= :command-start-failed (:kind (ex-data error))))))
