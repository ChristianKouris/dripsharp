(ns dripsharp.process
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File FileInputStream IOException]
           [java.lang Process ProcessBuilder ProcessHandle]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def ^:private cleanup-timeout-ms 1000)

(defn- process-descendants
  [^Process process]
  (with-open [descendants (.descendants (.toHandle process))]
    (vec (iterator-seq (.iterator descendants)))))

(defn- attempt-cleanup
  [f]
  (try
    (f)
    (catch Exception error
      ;; Do not copy arbitrary exception messages (possibly containing command
      ;; arguments) into diagnostics for a redacted command.
      {:error-class (.getName (class error))})))

(defn- remaining-ms
  [deadline]
  (max 0 (quot (- deadline (System/nanoTime)) 1000000)))

(defn- destroy-process-tree!
  [^Process process]
  (let [enumeration (attempt-cleanup #(process-descendants process))
        descendants (if (vector? enumeration) enumeration [])
        primary-destroy (atom nil)
        descendant-attempts
        (try
          (mapv (fn [^ProcessHandle descendant]
                  {:pid (.pid descendant)
                   :destroy (attempt-cleanup #(.destroyForcibly descendant))})
                (reverse descendants))
          (finally
            ;; Enumeration or an individual descendant failure must never
            ;; prevent the attempt to terminate the primary process.
            (reset! primary-destroy
                    (attempt-cleanup #(do (.destroyForcibly process) true)))))
        deadline (+ (System/nanoTime) (* cleanup-timeout-ms 1000000))
        primary-wait (attempt-cleanup
                      #(.waitFor process (remaining-ms deadline)
                                 TimeUnit/MILLISECONDS))
        descendant-results
        (mapv (fn [^ProcessHandle descendant attempted]
                (let [wait-result
                      (attempt-cleanup
                       #(when (.isAlive descendant)
                          (.get (.onExit descendant) (remaining-ms deadline)
                                TimeUnit/MILLISECONDS)))
                      terminated? (attempt-cleanup #(not (.isAlive descendant)))]
                  (cond-> (assoc attempted :terminated? (= true terminated?))
                    (map? wait-result) (assoc :wait-error wait-result)
                    (map? terminated?) (assoc :status-error terminated?))))
              (reverse descendants) descendant-attempts)]
    {:primary {:destroy @primary-destroy
               :terminated? (= true primary-wait)
               :wait-result primary-wait}
     ;; A snapshot of descendants is not proof that the whole tree stopped:
     ;; processes can fork or exit while enumeration is in progress.
     :descendant-enumeration (if (vector? enumeration)
                               {:status :complete}
                               (assoc enumeration :status :failed))
     :descendant-cleanup (if (and (vector? enumeration)
                                 (every? :terminated? descendant-results))
                           :enumerated-descendants-terminated
                           :unconfirmed)
     :descendants descendant-results}))

(defn- output-snapshot
  "Read at most the file's current byte length, even if a descendant keeps
  writing to its inherited output handle. No pipe reader remains blocked."
  [^File file]
  (with-open [input (FileInputStream. file)
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [remaining (.length file)]
        (when (pos? remaining)
          (let [n (.read input buffer 0 (int (min remaining (alength buffer))))]
            (when (pos? n)
              (.write output buffer 0 n)
              (recur (- remaining n)))))))
    (.toString output)))

(defn without-java-tool-options-banner
  "Removes the JVM launcher's standard JAVA_TOOL_OPTIONS diagnostic from
  merged process output while preserving the child program's exact stream."
  [output]
  (str/replace output
               #"(?m)^Picked up JAVA_TOOL_OPTIONS: [^\r\n]*(?:\r?\n|$)"
               ""))

(defn run!
  "Runs a command and returns its merged output. Throws on start, timeout, or
  exit failure. `timeout-ms` is optional and must be a positive integer.
  `display-command` may provide a same-length redacted command for results and
  errors when the executed command necessarily contains a secret argument.
  Output is a snapshot taken when the primary process finishes or times out.
  Timeout data includes cleanup evidence; descendant enumeration is a snapshot,
  never a guarantee that no process escaped it. Cleanup waits at most 1000 ms."
  [{:keys [command display-command directory timeout-ms environment
           unset-environment]}]
  (when-not (seq command)
    (throw (ex-info "Cannot run an empty command" {:kind :empty-command})))
  (when (and (some? timeout-ms)
             (not (and (integer? timeout-ms) (pos? timeout-ms))))
    (throw (ex-info "Command timeout must be a positive integer"
                    {:kind :invalid-command-timeout :timeout-ms timeout-ms})))
  (when (and display-command
             (not (and (sequential? display-command)
                       (= (count command) (count display-command)))))
    (throw (ex-info "Redacted display command must match the executed command"
                    {:kind :invalid-display-command})))
  (let [command (mapv str command)
        display-command (mapv str (or display-command command))
        builder (doto (ProcessBuilder. command)
                  (.directory (File. (str directory)))
                  (.redirectErrorStream true))]
    (when environment
      (when-not (and (map? environment)
                     (every? #(and (string? %) (string? %))
                             (mapcat identity environment)))
        (throw (ex-info "Process environment overrides must be string pairs"
                        {:kind :invalid-command-environment
                         :environment environment})))
      (doseq [[name value] environment]
        (.put (.environment builder) name value)))
    (when unset-environment
      (when-not (and (coll? unset-environment)
                     (every? string? unset-environment))
        (throw (ex-info "Process environment removals must be strings"
                        {:kind :invalid-command-environment-removals
                         :unset-environment unset-environment})))
      (doseq [name unset-environment]
        (.remove (.environment builder) name)))
    ;; File redirection avoids an uninterruptible pipe read when an unknown
    ;; descendant retains stdout. Snapshot length bounds collection, and the
    ;; temporary file is removed even when starting or running the command fails.
    (let [output-path (Files/createTempFile "dripsharp-process-" ".out"
                                           (make-array FileAttribute 0))
          output-file (.toFile output-path)]
      (try
        (.redirectOutput builder output-file)
        (let [process (try
                        (.start builder)
                        (catch IOException error
                          (throw (ex-info
                                  (str "Could not start command: "
                                       (str/join " " display-command))
                                  {:kind :command-start-failed
                                   :command display-command}
                                  error))))
              finished? (if timeout-ms
                          (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                          (do (.waitFor process) true))
              cleanup (when-not finished? (destroy-process-tree! process))
              captured (try
                         {:output (output-snapshot output-file)}
                         (catch IOException error
                           (if finished?
                             (throw error)
                             {:output ""
                              :output-error {:error-class (.getName (class error))}})))]
          (when-not finished?
            (throw (ex-info
                    (str "Command timed out after " timeout-ms " ms: "
                         (str/join " " display-command))
                    (merge {:kind :command-timeout :command display-command
                            :timeout-ms timeout-ms :cleanup cleanup}
                           captured))))
          (let [exit (.exitValue process)
                result (assoc captured :command display-command :exit exit)]
            (when-not (zero? exit)
              (throw (ex-info
                      (str "Command failed with exit " exit ": "
                           (str/join " " display-command))
                      (assoc result :kind :command-failed))))
            result))
        (finally
          (try
            (Files/deleteIfExists output-path)
            (catch IOException _
              ;; Windows may retain the file while an unconfirmed descendant
              ;; still has it open. Do not replace the timeout with that failure.
              (.deleteOnExit output-file))))))))
