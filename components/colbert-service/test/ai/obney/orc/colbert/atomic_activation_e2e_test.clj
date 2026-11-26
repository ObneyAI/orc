(ns ai.obney.orc.colbert.atomic-activation-e2e-test
  "DET-E2E-117: event-sourced atomic activation under concurrent search."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.grain.event-store-v3.interface :as es]))

(use-fixtures :once support/with-model-path)

(def index-root-property "colbert.index.root")
(def ^:dynamic *ctx* nil)

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "colbert-atomic-activation-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-context-and-root [f]
  (let [previous (System/getProperty index-root-property)
        ctx (tu/create-test-context "colbert")]
    (System/setProperty index-root-property (str (temp-dir)))
    (try
      (binding [*ctx* ctx] (f))
      (finally
        (tu/stop-context ctx)
        (if previous
          (System/setProperty index-root-property previous)
          (System/clearProperty index-root-property))))))

(use-fixtures :each with-context-and-root)

(defn- create-index! [name prefix]
  (let [result (tu/process-command!
                *ctx*
                {:command/name :colbert/create-index
                 :collection [(str prefix " arbitration clause")
                              (str prefix " termination notice")]
                 :document-ids [(str prefix "-1") (str prefix "-2")]
                 :index-name name
                 :split-documents? false})]
    (is (nil? (:cognitect.anomalies/category result)))
    (get-in result [:command/result :index-id])))

(defn- result-id-set [results]
  (set (map #(or (:document_id %) (:document-id %)) results)))

(defn- descending? [xs]
  (every? (fn [[a b]] (>= (double a) (double b))) (partition 2 1 xs)))

(deftest det-e2e-117-atomic-colbert-rebuild-under-concurrent-search
  (testing "every reader observes one complete active artifact and failed activation preserves it"
    (let [old-id (create-index! "old-corpus" "old")
          new-id (create-index! "new-corpus" "new")
          alias "guidance"
          old-activation (colbert/activate-index! *ctx* alias old-id)
          _ (is (= :activated (get-in old-activation [:command/result :status]))
                (pr-str old-activation))
          old-result (colbert/search-active *ctx* {:alias alias :query "notice" :k 10})
          gate (promise)
          concurrent (mapv (fn [_]
                             (future @gate
                                     (colbert/search-active
                                      *ctx* {:alias alias :query "notice" :k 10})))
                           (range 16))]
      (deliver gate true)
      (let [new-activation (colbert/activate-index! *ctx* alias new-id)
            concurrent-results (mapv deref concurrent)
            new-result (colbert/search-active *ctx* {:alias alias :query "notice" :k 10})
            old-set #{"old-1" "old-2"}
            new-set #{"new-1" "new-2"}
            observed (mapv result-id-set
                           (concat [old-result] concurrent-results [new-result]))
            failed-id (random-uuid)
            failed (colbert/activate-index! *ctx* alias failed-id)
            after-failure (colbert/search-active
                           *ctx* {:alias alias :query "notice" :k 10})
            events (into [] (es/read (:event-store *ctx*)
                                     {:tenant-id (:tenant-id *ctx*)}))
            activations (filter #(= :colbert/index-activated (:event/type %)) events)
            failures (filter #(= :colbert/index-activation-failed (:event/type %)) events)
            batches (colbert/search-batch
                     *ctx* {:queries ["notice" "arbitration"] :index-id new-id :k 2})]
        (is (= :activated (get-in new-activation [:command/result :status])))
        (is (every? #{old-set new-set} observed)
            "no result may mix old-only and new-only document identities")
        (is (= old-set (first observed)))
        (is (= new-set (last observed)))
        (doseq [results (concat [old-result] concurrent-results [new-result after-failure])]
          (is (descending? (map :score results)))
          (is (= (range 1 (inc (count results))) (map :rank results))))
        (is (= 2 (count activations)))
        (is (= [old-id new-id] (mapv :index-id activations)))
        (is (= :failed (get-in failed [:command/result :status])))
        (is (= new-id (get-in failed [:command/result :active-index-id])))
        (is (= 1 (count failures)))
        (is (= failed-id (:index-id (first failures))))
        (is (= new-id (:index-id (colbert/get-active-index *ctx* alias))))
        (is (= new-set (result-id-set after-failure)))
        (is (= 2 (count batches)))
        (is (every? #(= 2 (count %)) batches))))))
