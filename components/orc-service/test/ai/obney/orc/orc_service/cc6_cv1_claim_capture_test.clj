(ns ai.obney.orc.orc-service.cc6-cv1-claim-capture-test
  "CC-6 — CV-1's convergence capture writes a CLAIM OPERATION, not a body.

   CV-1 (ADR 0017) put a just-minted `:tree-class` into the searchable corpus at
   classify time so the next identical signature MATCHES it instead of
   scattering a fresh uuid. It did that by recording a whole provisional
   description body.

   After CC-5 that is a second writer on a slot the claim path owns. CC-3
   re-derives `:current` from the claim set on every claim event, so the very
   first consolidation would silently erase the signature CV-1 wrote — the
   clobbering the engine's single-writer fix eliminated elsewhere, reappearing
   inside the ontology.

   So the capture becomes an `:add` delta of kind `:representative-use`, and the
   signature reaches the corpus the same way every other insight does: through
   the assembled body. It gets past CC-4's guard on a DECLARED EVIDENCE BASIS
   (`:classification-signature`) rather than on judge evidence, because at
   classify time no judge has run — that is the forward conflict CC-4 recorded
   for this slice.

   Real grain throughout; every assertion reads the PROJECTION (or the event
   store) back, never a command's return value."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.task-classifier :as task-classifier]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc6-cv1-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :sheet-id (random-uuid)
     :tick-id (random-uuid)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- node []
  {:id (random-uuid)
   :name "cc6-cv1-node"
   :type :repl-researcher
   :instruction "summarize the attached document into three bullets"
   :reads [:doc] :writes [:summary]
   :rlm {:auto-classify? true}})

(defn- tree-class-body-writes
  "Every WHOLE-BODY description write aimed at this `:tree-class`. After CC-6
   the only writer of a tree-class body is the projection's own assembly, so
   this must stay EMPTY on the capture path."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:ontology/tree-description-updated}})
       (into [])
       (filterv #(and (= :tree-class (:target-type %))
                      (= class-id (:target-id %))))))

(defn- fresh-mint-result [class-id]
  {:assigned-tree-id class-id
   :confidence 0.0
   :top-candidates []
   :reasoning "minting fresh"
   :outcome :novel
   :was-fresh-mint? true
   :parent-tree-id nil
   :rerank-fallback? false})

(def ^:private no-behaviors
  {:behaviors [] :outcome :novel :rerank-fallback? false})

;; ===========================================================================
;; CYCLE 3 — the fresh-mint capture lands as a claim, and the class is still
;; retrievable through the ASSEMBLED body.
;; ===========================================================================

(deftest fresh-mint-capture-lands-as-a-claim-not-a-body
  (testing "the wedge's convergence capture records a :representative-use claim
            carrying the task signature; NO whole-body tree-class write happens;
            and the assembled description still carries the signature so the
            next identical signature can retrieve it"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            n (node)
            signature (task-classifier/build-task-signature n)]
        (with-redefs [ontology/classify-task (fn [_ _] (fresh-mint-result class-id))
                      ontology/classify-behaviors (fn [_ _] no-behaviors)]
          (tp/maybe-auto-classify-and-set-context n ctx))
        (let [cs (ontology/get-claims ctx :tree-class class-id)
              c (first cs)]
          (is (= 1 (count cs))
              "exactly one claim was captured for the freshly-minted class")
          (is (= signature (:content c))
              "and its content is the task signature the classifier keyed on")
          (is (= :representative-use (:kind c))
              "captured as a representative use — what this class was minted FOR")
          (is (false? (:legacy-provenance c))
              "it is NOT masquerading as the legacy corpus"))
        (is (empty? (tree-class-body-writes ctx class-id))
            "NO whole-body :tree-class description write — the projection's
             assembly is the only writer of that slot")
        (let [body (ontology/get-description ctx :tree-class class-id)]
          (is (some? body)
              "the class still has a description (assembled from its claim)")
          (is (clojure.string/includes? (:summary body) signature)
              "and the signature is in :summary — the field ColBERT indexes, so
               convergence still works"))))))

;; ===========================================================================
;; CYCLE 8 — the capture must still REACH the index.
;;
;; This is the one thing the migration could silently destroy. CV-1 exists so a
;; just-minted class is RETRIEVABLE by the next similar signature; retrievable
;; means present in the ColBERT `ontology-descriptions` index; and the index is
;; rebuilt off a counter that, before this slice, only `*-description-updated`
;; events moved. Move the writer onto claim events and leave the counter alone
;; and CV-1 keeps writing, keeps assembling a body, and never gets indexed —
;; a convergence regression with a fully green claim-side suite.
;; ===========================================================================

(deftest a-claim-write-drives-the-reindex-trigger
  (testing "a claim-delta write moves the reindex counter, so an assembled body
            reaches the ColBERT corpus exactly as a recorded body did"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            rm (requiring-resolve 'ai.obney.orc.ontology.core.read-models/get-reindex-state)]
        (is (zero? (:events-since-last-rebuild (rm ctx)))
            "control: the counter starts at zero")
        (with-redefs [ontology/classify-task (fn [_ _] (fresh-mint-result class-id))
                      ontology/classify-behaviors (fn [_ _] no-behaviors)]
          (tp/maybe-auto-classify-and-set-context (node) ctx))
        (is (pos? (:events-since-last-rebuild (rm ctx)))
            "the capture registered as index-relevant work")))))

(deftest a-match-captures-nothing
  (testing "THRASH CONTROL — a MATCH (:was-fresh-mint? false) records no claim
            and no body, exactly as CV-1 specified"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            matched (assoc (fresh-mint-result class-id)
                           :outcome :matched :was-fresh-mint? false :confidence 0.9)]
        (with-redefs [ontology/classify-task (fn [_ _] matched)
                      ontology/classify-behaviors
                      (fn [_ _] {:behaviors [] :outcome :matched :rerank-fallback? false})]
          (tp/maybe-auto-classify-and-set-context (node) ctx))
        (is (empty? (ontology/get-claims ctx :tree-class class-id))
            "a recurrence does not re-capture")
        (is (empty? (tree-class-body-writes ctx class-id))
            "and writes no body either")))))
