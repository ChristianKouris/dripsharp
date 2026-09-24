(ns dripsharp.release-prerequisites
  "Cheap, read-only release readiness checks; never generates or publishes."
  (:require [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.product-repository :as repository])
  (:import [com.fasterxml.jackson.databind ObjectMapper]))

(defn- fail! [reason message data]
  (throw (ex-info message (assoc data :kind :release-prerequisite-failed :reason reason))))

(defn validate-tooling!
  "Checks the existing product-owned release tooling and exact-commit wiring."
  [product]
  (doseq [file [".github/workflows/nuget-release.yml" "eng/verify-release.sh"
                "eng/pack-release.sh" "eng/validate-release-packages.sh"
                "eng/bundle-release-packages.py" "eng/validate-release-packages.py"]]
    (when-not (paths/regular-file? (paths/resolve-path product file))
      (fail! :missing-release-tool "Required release tooling is missing"
             {:path (str (paths/resolve-path product file))}))
    (when (and (str/ends-with? file ".sh")
               (not (java.nio.file.Files/isExecutable (paths/resolve-path product file))))
      (fail! :release-tool-not-executable "Required release shell script is not executable"
             {:path (str (paths/resolve-path product file))})))
  (let [workflow (slurp (str (paths/resolve-path product ".github/workflows/nuget-release.yml")))
        prepare (second (re-find #"(?s)\n  prepare:\n(.*?)(?:\n  publish:|$)" workflow))]
    (doseq [property ["RepositoryCommit" "SourceRevisionId"]]
      (when-not (re-find (re-pattern (str "(?m)^      " property ": \\$\\{\\{ github\\.sha \\}\\}\\s*$"))
                         (or prepare ""))
        (fail! :missing-commit-property "Release prepare must stamp the triggering product commit"
               {:property property :path ".github/workflows/nuget-release.yml"})))
    {:exact-commit-properties ["RepositoryCommit" "SourceRevisionId"]}))

(defn- json [s]
  (.readValue (ObjectMapper.) ^String s java.util.Map))

(defn live!
  "Rechecks remote state immediately before an authorized dispatch. An empty
  protection_rules list is valid; this check never grants publication authority."
  [{:keys [workspace-root publication run-command!]
    :or {run-command! process/run!}}]
  (let [slug (:repository-slug publication)
        execute #(-> (run-command! {:command % :directory workspace-root :timeout-ms 30000}) :output)
        environment (json (execute ["gh" "api" (str "repos/" slug "/environments/release")]))
        workflow (json (execute ["gh" "api" (str "repos/" slug "/actions/workflows/nuget-release.yml")]))
        _ (when-not (= "active" (get workflow "state"))
            (fail! :inactive-release-workflow "The release workflow is not active" {:repository slug}))
        actions (json (execute ["gh" "api" (str "repos/" slug "/actions/permissions")]))
        _ (when-not (true? (get actions "enabled"))
            (fail! :actions-disabled "GitHub Actions is disabled for the product" {:repository slug}))
        packages (get-in publication [:nuget :packages])
        bundle (get-in publication [:nuget :bundle :package-id])
        ids (if bundle [bundle] (sort (keys packages)))]
    (doseq [id ids
            :let [version (get-in packages [id :version])
                  url (str "https://api.nuget.org/v3-flatcontainer/" (str/lower-case id)
                           "/" (str/lower-case version) "/" (str/lower-case id) "."
                           (str/lower-case version) ".nupkg")
                  status (str/trim (execute ["curl" "--silent" "--show-error" "--location"
                                             "--max-time" "20" "--output" "/dev/null"
                                             "--write-out" "%{http_code}" url]))]]
      (when-not (= "404" status)
        (fail! (if (= "200" status) :version-already-published :version-availability-unknown)
               "Release version must be confirmed unused before dispatch"
               {:package id :version version :http-status status})))
    {:repository slug :workflow-state (get workflow "state")
     :protection-rules (get environment "protection_rules")
     :deployment-branch-policy (get environment "deployment_branch_policy")
     :versions-unused true}))

(defn check!
  [{:keys [workspace-root target-contract run-command! live-release? windows-host
           windows-preflight-fn repository-prerequisites-fn]
    :or {run-command! process/run!
         repository-prerequisites-fn repository/verify-prerequisites!}}]
  (let [started (System/nanoTime)
        repository (repository-prerequisites-fn
                    {:workspace-root workspace-root :target-contract target-contract
                     :run-command! run-command!})
        pdfcarton? (= :pdfcarton (:product-family target-contract))
        tooling (when pdfcarton? (validate-tooling! (:product repository)))
        windows (when pdfcarton?
                  (if windows-preflight-fn
                    (windows-preflight-fn {})
                    (run-command!
                     {:command ["python3" "scripts/verify-pdfcarton-windows.py" "preflight"
                                "--host" (or windows-host (System/getenv "PDFCARTON_WINDOWS_HOST")
                                             "windows-test-vm")]
                      :directory workspace-root :timeout-ms 60000})))
        live (when live-release?
               (live! {:workspace-root workspace-root :publication (:publication repository)
                       :run-command! run-command!}))
        result {:repository repository :tooling tooling :windows windows :live live
                :elapsed-ms (/ (- (System/nanoTime) started) 1e6)}]
    (println "Release prerequisites passed:" (pr-str (select-keys result [:elapsed-ms])))
    result))
