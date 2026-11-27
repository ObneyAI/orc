#!/usr/bin/env bb

(ns lint-service-components
  "Enforce the exact ORC *-service component allowlist.
   Usage: bb scripts/lint_service_components.bb [components-directory]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def service-interfaces
  (sorted-map "colbert-service" "colbert"
              "evaluation-service" "evaluation"
              "gepa-service" "gepa"
              "ontology-service" "ontology"
              "orc-service" "orc-service"))

(def allowed-services (set (keys service-interfaces)))

(defn service-component? [component]
  (boolean (and component (re-matches #".+-service(?:-v[0-9]+)?" component))))

(defn interface-name [component]
  (get service-interfaces component component))

(defn interface-directory [component]
  (str/replace (interface-name component) "-" "_"))

(defn unapproved-components [root]
  (when-not (fs/directory? root)
    (throw (ex-info "Components directory does not exist or is not a directory" {:path (str root)})))
  (->> (fs/list-dir root)
       (filter fs/directory?)
       (filter #(service-component? (str (fs/file-name %))))
       (remove #(contains? allowed-services (str (fs/file-name %))))
       (map str)
       sort
       vec))

(defn -main [& args]
  (try
    (when (> (count args) 1)
      (throw (ex-info "Usage: bb scripts/lint_service_components.bb [components-directory]" {})))
    (let [violations (unapproved-components (or (first args) "components"))]
      (if (seq violations)
        (do
          (doseq [path violations]
            (println (str path ": error: unapproved *-service component")))
          (println (str "Allowed service components: " (str/join ", " (keys service-interfaces))))
          (println "REQUIRED: review service ownership in README.md#service-ownership-and-base-review.")
          (println "Ask: Is there an obvious existing allowed service that owns this behaviour? Inspect the specs and interfaces.")
          (println "If ownership is unclear, report BLOCKED and ask a human. Do not guess, waive the lint or rename to evade it.")
          (println (format "FAIL: %d unapproved service component(s); existing components are not grandfathered." (count violations)))
          1)
        (do (println "OK: service component allowlist satisfied.") 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error))
      1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
