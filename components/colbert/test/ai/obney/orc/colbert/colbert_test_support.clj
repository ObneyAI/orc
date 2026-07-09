(ns ai.obney.orc.colbert.colbert-test-support
  "Shared support for the JVM-ColBERT tests.

   - Resolves the encoder checkpoint directory via the REAL
     `model-store/resolve-model-dir`: an operator `-Dcolbert.model.path`
     override wins; otherwise the user cache
     (~/.cache/orc/colbert/answerai-colbert-small-v1/) serves, materialized by
     a one-time download on a fresh environment (the documented repo norm).
   - Exposes a fixture that pins `-Dcolbert.model.path` at the resolved
     directory for the duration, so every code path under test resolves the
     same checkpoint deterministically and never re-fetches.
   - Reads the golden fixtures (see resources/colbert_golden/PROVENANCE.md)
     from the test classpath."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ai.obney.orc.colbert.core.model-store :as model-store]))

(def model-path-property model-store/model-path-property)

(defn model-dir
  "Absolute path of the resolved encoder checkpoint directory. Honors an
   operator -Dcolbert.model.path override; otherwise resolves the user cache
   (downloading the artifacts once on a fresh environment)."
  ^String []
  (.getAbsolutePath (model-store/resolve-model-dir)))

(defn with-model-path
  "clojure.test fixture: pin -Dcolbert.model.path at the resolved model dir
   for the duration, restoring the previous value afterwards. An operator
   override, if set, is already the resolved dir — it wins unchanged."
  [f]
  (let [previous (System/getProperty model-path-property)]
    (System/setProperty model-path-property (model-dir))
    (try
      (f)
      (finally
        (if previous
          (System/setProperty model-path-property previous)
          (System/clearProperty model-path-property))))))

(defn read-golden
  "Parse a golden fixture from the test classpath (string keys preserved)."
  [file-name]
  (let [path (str "resources/colbert_golden/" file-name)
        res (io/resource path)]
    (when-not res
      (throw (ex-info (str "Golden fixture not on the test classpath: " path)
                      {:resource path})))
    (json/read-str (slurp res))))
