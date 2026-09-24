(ns dripsharp.pdfcarton-preparation-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.target-execution :as execution]))

(def profiles ["pdfcube-io" "pdfcube-fontbox" "pdfcube-xmpbox"
               "pdfcube-pdfbox" "pdfcube-preflight"])
(def assemblies ["DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts"
                 "DripSharp.PdfCarton.Xmp" "DripSharp.PdfCarton"
                 "DripSharp.PdfCarton.Preflight"])
(def family-verification
  {:generation {:generation-profile {:profile "pdfcube-preflight"}
                :dependency-emissions (mapv #(hash-map :profile %) (butlast profiles))}
   :public-surface {:assemblies (mapv #(hash-map :assembly %) assemblies)}})

(defn sync-fixture [verification exact?]
  (let [calls (atom [])
        result
        (execution/synchronize!
         {:target :pdfcube
          :prerequisites-fn (fn [_] (swap! calls conj :preflight))
          :verify-fn (fn [{:keys [profile]}]
                       (swap! calls conj [:generate-build-audit profile]) verification)
          :proof-fn (fn [_] (swap! calls conj :two-independent-package-builds) :proof)
          :test-suites-fn (fn [_] (swap! calls conj :shipped-tests))
          :staging-cleanup-fn (fn [_] (swap! calls conj :cleanup))
          :repository-proof-fn (fn [_]
                                 (swap! calls conj :exact-commit-check)
                                 (if exact? {:repository-commit "exact"}
                                     (throw (ex-info "stale" {:reason :stale-generated-product-commit}))))
          :synchronize-fn (fn [_] (swap! calls conj :sync) {:changes []})})]
    {:calls @calls :result result}))

(deftest one-complete-family-generation-retains-every-audit-and-policy-stage
  (doseq [change [:production :version-only :unchanged :metadata]
          :let [exact? (contains? #{:unchanged :metadata} change)
                {:keys [calls result]} (sync-fixture family-verification exact?)]]
    (is (= 1 (get-in result [:precommit-proof :generation-invocations])))
    (is (= profiles (mapv :profile (get-in result [:precommit-proof :profiles]))))
    (is (= 1 (count (filter vector? calls))))
    (is (= (if exact? 2 1) (count (filter #{:shipped-tests} calls)))))
  ;; Each older per-profile call walked its complete dependency closure.
  (let [old-closures [["io"] ["io" "fonts"] ["xmp"]
                      ["io" "fonts" "pdf"] ["io" "fonts" "xmp" "pdf" "preflight"]]]
    (is (= 12 (count (mapcat identity old-closures))))
    (is (= 5 (count profiles)))))

(deftest incomplete-or-duplicate-family-evidence-fails-closed
  (doseq [bad [(update-in family-verification [:generation :dependency-emissions] pop)
               (update-in family-verification [:generation :dependency-emissions] conj {:profile "pdfcube-io"})
               (update-in family-verification [:public-surface :assemblies] pop)
               (assoc-in family-verification [:public-surface :assemblies 0 :assembly] "unexpected")]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"every declared profile exactly once"
                          (sync-fixture bad false)))))

(deftest exact-pass-rejects-managed-drift
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"did not synchronize cleanly"
       (with-redefs [execution/synchronize! execution/synchronize!]
         (execution/synchronize!
          {:target :pdfcube :prerequisites-fn (constantly :ready)
           :verify-fn (constantly family-verification)
           :proof-fn (constantly :proof) :test-suites-fn (constantly :tests)
           :staging-cleanup-fn (constantly :clean)
           :repository-proof-fn (constantly {:repository-commit "exact"})
           :synchronize-fn (constantly {:changes ["README.md"]})})))))
