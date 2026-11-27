#!/usr/bin/env bb

(ns lint-base-changes
  "Require human review of the exact staged base change.
   Usage: bb scripts/lint_base_changes.bb [check [repository]]
          bb scripts/lint_base_changes.bb approve FINGERPRINT REVIEWER REASON [repository]"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.time Instant]))

(def review-guide "README.md#service-ownership-and-base-review")

(defn git-result [repository & args]
  (apply process/shell {:out :string :err :string :continue true}
         "git" "-C" (str repository) args))

(defn git [repository & args]
  (let [{:keys [exit out err]} (apply git-result repository args)]
    (when-not (zero? exit)
      (throw (ex-info "Cannot inspect Git state" {:arguments args :error (str/trim err)})))
    out))

(defn parent-commit [repository]
  (let [{:keys [exit out]} (git-result repository "rev-parse" "--verify" "--quiet" "HEAD")]
    (if (zero? exit)
      (str/trim out)
      (let [ref (str/trim (git repository "symbolic-ref" "HEAD"))
            result (git-result repository "show-ref" "--verify" "--quiet" ref)]
        (when-not (= 1 (:exit result))
          (throw (ex-info "Cannot identify the commit parent" {})))
        "unborn"))))

(defn sha256 [value]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (MessageDigest/getInstance "SHA-256")
                           (.getBytes value "UTF-8")))))

(defn proposal [repository]
  (let [root (str/trim (git repository "rev-parse" "--show-toplevel"))
        parent (parent-commit root)
        unmerged (git root "ls-files" "--unmerged" "-z")
        _ (when (seq unmerged)
            (throw (ex-info "Resolve unmerged index entries before base review" {})))
        options ["diff" "--cached" "--no-ext-diff" "--no-textconv" "--no-renames"
                 "--ignore-submodules=none" "--no-abbrev"]
        raw (apply git root (concat options ["--raw" "-z" "--" ":(top)bases/"]))
        paths (remove str/blank?
                      (str/split (apply git root (concat options ["--name-only" "-z" "--" ":(top)bases/"]))
                                 #"\u0000"))]
    {:root root :parent parent :paths (vec paths)
     :fingerprint (sha256 (str "orc-base-review-v1\u0000" parent "\u0000" raw))}))

(defn receipt-path [{:keys [root]}]
  (let [path (str/trim (git root "rev-parse" "--git-path" "orc-base-review.edn"))]
    (str (if (fs/absolute? path) path (fs/path root path)))))

(defn valid-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn approved? [{:keys [fingerprint parent] :as current}]
  (let [path (receipt-path current)]
    (when (fs/exists? path)
      (let [receipt (edn/read-string (slurp path))]
        (when-not (and (map? receipt) (= 1 (:version receipt))
                       (valid-text? (:reviewer receipt)) (valid-text? (:reason receipt))
                       (valid-text? (:approved-at receipt))
                       (string? (:fingerprint receipt))
                       (re-matches #"[0-9a-f]{64}" (:fingerprint receipt))
                       (valid-text? (:parent receipt)))
          (throw (ex-info "Invalid base review receipt; obtain explicit human approval again" {:path path})))
        (and (= fingerprint (:fingerprint receipt)) (= parent (:parent receipt)))))))

(defn show-proposal [{:keys [paths fingerprint]}]
  (doseq [path paths] (println "Base change:" (pr-str path)))
  (println "Review fingerprint:" fingerprint))

(defn check! [repository]
  (let [current (proposal repository)]
    (cond
      (empty? (:paths current))
      (do (println "OK: no staged base changes.") 0)

      (approved? current)
      (do (println "OK: exact staged base change has recorded human approval.") 0)

      :else
      (do
        (show-proposal current)
        (println "BLOCKED: staged base changes require human review.")
        (println "Review the base change using" review-guide)
        (println "The base is for wiring and infrastructure, not resolving circular dependencies by moving behavior into it.")
        (println "Reconsider the owning component. A necessary base change still requires explicit human approval.")
        (println "Review: git diff --cached -- bases/")
        (println "Only after human approval, record the displayed fingerprint:")
        (println "bb scripts/lint_base_changes.bb approve FINGERPRINT 'Human reviewer' 'Approved rationale'")
        1))))

(defn approve! [repository fingerprint reviewer reason]
  (when-not (and (string? fingerprint) (re-matches #"[0-9a-f]{64}" fingerprint)
                 (valid-text? reviewer) (valid-text? reason))
    (throw (ex-info "Approval requires the reviewed fingerprint, named human reviewer and rationale" {})))
  (let [current (proposal repository)]
    (when (empty? (:paths current))
      (throw (ex-info "There is no staged base change to approve" {})))
    (when-not (= fingerprint (:fingerprint current))
      (throw (ex-info "The staged base proposal changed; ask the human to review the new fingerprint" {})))
    (spit (receipt-path current)
          (str (pr-str {:version 1 :parent (:parent current) :fingerprint fingerprint
                        :reviewer reviewer :reason reason :approved-at (str (Instant/now))}) "\n"))
    (println "Recorded human approval for" fingerprint)
    (println "This receipt covers only this parent and staged base change; all other lints still apply.")
    0))

(defn -main [& args]
  (try
    (cond
      (empty? args) (check! ".")
      (and (= "check" (first args)) (<= 1 (count args) 2))
      (check! (or (second args) "."))
      (and (= "approve" (first args)) (<= 4 (count args) 5))
      (let [[_ fingerprint reviewer reason repository] args]
        (approve! (or repository ".") fingerprint reviewer reason))
      :else (throw (ex-info "Usage: lint_base_changes.bb [check [repository]] | approve FINGERPRINT REVIEWER REASON [repository]" {})))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error))
      1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
