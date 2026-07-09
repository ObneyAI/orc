(ns ai.obney.orc.colbert.legacy-artifact-propagation-test
  "Slice 3, cycle 5 (colbert side): legacy/unversioned index artifacts fail
   LOUD at the source — the Slice-2 :colbert-index-artifact-unreadable ex-info
   (thrown by index-store's reading layer) propagates out of BOTH `search` and
   `search-batch` unchanged, naming the path and the rebuild remedy. No
   auto-migration, no silent empty results. (The ontology seam catches it and
   degrades to 2-signal — pinned in the ontology component's
   retrieval-colbert-seam-test.)

   The legacy layout here mirrors a Python-era PLAID directory: real files,
   no index-meta.json format marker. No encoder needed — the read-model stub +
   the reading layer fire before any encoding."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]))

(defn- temp-dir ^java.io.File [label]
  (.toFile (java.nio.file.Files/createTempDirectory
            (str "colbert-legacy-test-" label)
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- legacy-plaid-dir
  "A directory shaped like the old Python-era PLAID artifact: content, but no
   orc-colbert-index format marker."
  ^java.io.File []
  (let [dir (temp-dir "legacy-plaid")
        plaid (io/file dir "colbert" "indexes" "my-index")]
    (.mkdirs plaid)
    (spit (io/file plaid "metadata.json") "{\"config\": {\"ncells\": 1}}")
    (spit (io/file plaid "0.residuals.pt") "not-really-a-tensor")
    dir))

(defn- unreadable-ex-from [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- assert-unreadable-ex [ex ^java.io.File dir via]
  (is (some? ex) (str via ": the legacy artifact throws"))
  (when ex
    (let [data (ex-data ex)]
      (is (= :colbert-index-artifact-unreadable (:error data))
          (str via ": the precise Slice-2 error key"))
      ;; load-index canonicalizes the path before reading (its cache key), so
      ;; the ex-info names the CANONICAL path (on macOS /var -> /private/var).
      (is (= (.getCanonicalPath dir) (:path data))
          (str via ": ex-data names the offending (canonical) path"))
      (is (string? (:remedy data)) (str via ": ex-data carries the rebuild remedy"))
      (is (re-find #"(?i)rebuild" (:remedy data)))
      (is (re-find #"(?i)unreadable" (ex-message ex))))))

(deftest legacy-artifact-propagates-loudly-from-search-and-search-batch
  (let [dir (legacy-plaid-dir)
        index-id (random-uuid)]
    (with-redefs [read-models/get-index
                  (fn [_ id]
                    (when (= id index-id)
                      {:index-id index-id
                       :index-path (.getAbsolutePath dir)
                       :status :active}))]
      (testing "search: the reading layer's ex-info propagates out unchanged"
        (assert-unreadable-ex
         (unreadable-ex-from
          #(operations/search {} {:query "q" :index-id index-id :k 3}))
         dir "search"))
      (testing "search-batch: same propagation (loud at the source, per query batch)"
        (assert-unreadable-ex
         (unreadable-ex-from
          #(operations/search-batch {} {:queries ["q1" "q2"] :index-id index-id :k 3}))
         dir "search-batch")))))

(deftest empty-unversioned-dir-is-also-unreadable
  (testing "a directory with NO version marker at all (not just legacy PLAID)"
    (let [dir (temp-dir "empty")
          index-id (random-uuid)]
      (with-redefs [read-models/get-index
                    (fn [_ _] {:index-id index-id
                               :index-path (.getAbsolutePath dir)
                               :status :active})]
        (assert-unreadable-ex
         (unreadable-ex-from
          #(operations/search {} {:query "q" :index-id index-id :k 3}))
         dir "search (empty dir)")))))
