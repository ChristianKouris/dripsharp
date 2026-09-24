(ns dripsharp.proof-resume-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.proof-resume :as resume]
            [dripsharp.util :as util])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temporary-root []
  (Files/createTempDirectory "dripsharp-proof-resume-" (make-array FileAttribute 0)))

(defn- run-fixture [root inputs calls validator]
  (resume/with-run!
    {:workspace-root root :run-id "recovery"}
    #(let [artifact (paths/resolve-path root "package-feed" "candidate.nupkg")
           proof (resume/stage!
                  :packages inputs (fn [_] [(paths/resolve-path root "package-feed")])
                  (fn [] (swap! calls inc)
                    (util/write-text! artifact "candidate")
                    {:artifact artifact :identity {:sha256 (util/sha256-file artifact)}}))]
       (resume/check! :identity-comparator (fn [] (validator proof)))
       (resume/check! :profile-selector (fn [] (validator proof)))
       proof)))

(deftest checker-repairs-preserve-expensive-package-stage
  (let [root (temporary-root)
        calls (atom 0)
        inputs {:production "source-a" :version "1" :product-commit "commit-a"}
        fail (fn [_] (throw (ex-info "injected comparator failure" {})))]
    (is (thrown-with-msg? Exception #"comparator" (run-fixture root inputs calls fail)))
    (is (= 1 @calls))
    (let [proof (run-fixture root inputs calls identity)]
      (is (instance? Path (:artifact proof)))
      (is (= 1 @calls)))
    (let [events (-> (paths/resolve-path root "validation-output/pdfcarton-proof-runs/recovery/summary.edn")
                     str slurp edn/read-string :phases)
          comparator (filter #(= :identity-comparator (:phase %)) events)
          selector (filter #(= :profile-selector (:phase %)) events)
          reused (filter #(= :reused (:action %)) events)]
      (is (= [:failed :passed] (mapv :status comparator)))
      (is (= [:passed] (mapv :status selector)))
      (is (= 1 (count reused))))))

(deftest selector-repair-reruns-selection-and-remaining-checks
  (let [root (temporary-root) packages (atom 0) selection (atom 0)
        attempt (fn [broken?]
                  (resume/with-run!
                    {:workspace-root root :run-id "selector"}
                    #(let [artifact (paths/resolve-path root "feed" "candidate.nupkg")]
                       (resume/stage! :packages {:source "a"} (fn [_] [artifact])
                                      (fn [] (swap! packages inc) (util/write-text! artifact "package")))
                       (resume/check! :profile-selector
                                      (fn [] (swap! selection inc)
                                        (when broken? (throw (ex-info "selector mismatch" {}))))))))]
    (is (thrown? Exception (attempt true)))
    (attempt false)
    (is (= 1 @packages))
    (is (= 2 @selection))))

(deftest relevant-inputs-and-output-loss-invalidate
  (let [root (temporary-root) calls (atom 0)
        inputs {:production "source-a" :version "1" :product-commit "commit-a"}]
    (run-fixture root inputs calls identity)
    (doseq [key [:production :version :product-commit :toolchain :build-options]]
      (run-fixture root (assoc inputs key "changed") calls identity))
    (is (= 6 @calls))
    (run-fixture root inputs calls identity)
    (Files/delete (paths/resolve-path root "package-feed/candidate.nupkg"))
    (run-fixture root inputs calls identity)
    (is (= 8 @calls))
    (util/write-text! (paths/resolve-path root "package-feed/unexpected.nupkg") "other")
    (run-fixture root inputs calls identity)
    (is (= 9 @calls))))

(deftest failed-artifact-stages-and-unreadable-results-never-reuse
  (let [root (temporary-root) calls (atom 0)
        attempt (fn [broken?]
                  (resume/with-run!
                    {:workspace-root root :run-id "failed"}
                    #(resume/stage! :package {} (fn [_] [(paths/resolve-path root "candidate")])
                                    (fn [] (swap! calls inc)
                                      (when broken? (throw (ex-info "pack failure" {})))
                                      (util/write-text! (paths/resolve-path root "candidate") "bytes")))))]
    (is (thrown? Exception (attempt true)))
    (attempt false)
    (is (= 2 @calls))))

(deftest fresh-windows-phases-never-reuse-mac-or-other-feed-evidence
  (let [root (temporary-root) packages (atom 0) windows (atom [])
        run (fn [phase environment fail?]
              (resume/with-run!
                {:workspace-root root :run-id "windows"}
                #(do
                   (resume/stage! :packages {:actual-sha "a"} (fn [_] [(paths/resolve-path root "candidate")])
                                  (fn [] (swap! packages inc)
                                    (util/write-text! (paths/resolve-path root "candidate") "package")))
                   (resume/check! phase
                                  (fn [] (swap! windows conj environment)
                                    (when fail? (throw (ex-info "Windows unavailable" {}))))))))]
    (run :mac-candidate {:os "macOS"} false)
    (is (thrown? Exception (run :windows-candidate {:os "Windows" :arch "x64" :runtime "10" :tests "1"} true)))
    (run :windows-candidate {:os "Windows" :arch "x64" :runtime "10" :tests "2"} false)
    (run :windows-published {:os "Windows" :arch "x64" :runtime "10" :tests "2"} false)
    (is (= 1 @packages))
    (is (= 4 (count @windows)))))

(deftest run-isolation-and-workspace-exclusion
  (let [root (temporary-root)]
    (resume/with-run!
      {:workspace-root root :run-id "first"}
      #(is (thrown-with-msg? Exception #"owns the workspace"
                             (resume/with-run! {:workspace-root root :run-id "second"} identity))))
    (is (thrown? Exception (resume/with-run! {:workspace-root root :run-id "../escape"} identity)))))

(deftest ordinary-generation-cannot-clobber-active-resume
  (let [root (temporary-root)]
    (resume/with-run!
      {:workspace-root root :run-id "generation"}
      #(do
         (is (= :same-run (resume/with-generation-lock! {:workspace-root root} (constantly :same-run))))
         (binding [resume/*run* nil]
           (is (thrown-with-msg? Exception #"owns the workspace"
                                 (resume/with-generation-lock! {:workspace-root root} identity))))))))

(deftest package-results-retain-paths-without-live-spoon-or-strategy-functions
  (let [root (temporary-root) calls (atom 0)
        options {:workspace-root root :profile "pdfcube-preflight"}
        invoke (fn []
                 (resume/with-run!
                   (assoc options :run-id "pack")
                   #(resume/pack!
                     options
                     (fn [{:keys [proof-root]}]
                       (swap! calls inc)
                       (let [feed (paths/resolve-path proof-root "feed")
                             artifact (util/write-text! (paths/resolve-path feed "candidate.nupkg") "bytes")]
                         {:feed feed :artifact artifact
                          :boundary-report {:edn (util/write-text! (paths/resolve-path proof-root "release-evidence/boundary.edn") "{:passed true}")}
                          :verification {:generation {:java-model {:totals {:declarations 3} :launcher (Object.)}
                                                      :public-surface-strategy {:verify-compiled! identity}
                                                      :public-api-boundary {:selection-evidence [{:declaration-key "I#read()"
                                                                                                  :interface-method (Object.)}]}}}})))))]
    (with-redefs [resume/production-inputs (constantly {:source "a"})]
      (invoke)
      (let [resumed (invoke)]
        (is (= 1 @calls))
        (is (instance? Path (:artifact resumed)))
        (is (= {:totals {:declarations 3}} (get-in resumed [:verification :generation :java-model])))
        (is (nil? (get-in resumed [:verification :generation :public-surface-strategy])))
        (Files/delete (get-in resumed [:boundary-report :edn]))
        (invoke)
        (is (= 2 @calls))))))

(deftest production-classification-excludes-only-known-validators
  (let [root (temporary-root)
        source (paths/resolve-path root "src/dripsharp/translator.clj")
        checker (paths/resolve-path root "src/dripsharp/pdfcube/family_packaging.clj")
        fixture (paths/resolve-path root "targets/pdfcube/validation/input.pdf")
        options {:workspace-root root :profile "pdfcube-preflight"
                 :run-command! (fn [_] {:output "stable-toolchain-and-product-commit"})}]
    (doseq [file [source checker fixture]] (util/write-text! file "initial"))
    (let [before (resume/production-inputs options)]
      (is (not= before (resume/production-inputs (assoc options :build-configuration "Debug"))))
      (is (not= before (resume/production-inputs (assoc options :build-properties {"RepositoryCommit" "other"}))))
      (util/write-text! (paths/resolve-path root "Directory.Build.props") "changed build defaults")
      (is (not= before (resume/production-inputs options)))
      (Files/delete (paths/resolve-path root "Directory.Build.props"))
      (util/write-text! checker "fixed checker")
      (util/write-text! fixture "fixed fixture")
      (is (= before (resume/production-inputs options)))
      (util/write-text! source "changed translator")
      (is (not= before (resume/production-inputs options)))
      (util/write-text! source "initial")
      (util/write-text! (paths/resolve-path root "src/dripsharp/new_input.clj") "unknown new input")
      (is (not= before (resume/production-inputs options))))))
