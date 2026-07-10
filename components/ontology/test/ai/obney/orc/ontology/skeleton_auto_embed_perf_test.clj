(ns ai.obney.orc.ontology.skeleton-auto-embed-perf-test
  "PERF — the SKELETON's `auto-embed!` (deterministic_skeleton) is a SECOND embed
   path, distinct from `embed+index!`. The full O*NET build exercises it and it hit
   the SAME walls embed+index! had: single-item DJL inference + the O(n²)
   `get-concept-by-uri` projection (once per concept). This locks the fix — auto-embed!
   computes vectors ONCE (batched) and lands each via the command with the concept's
   identity metadata (:concept-id/:ontology-id/:scope) + precomputed :embedding, so the
   command neither re-embeds NOR projects per concept. DJL-FREE via an injected batch
   capability (runs on the fast gate)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as ds]
            [ai.obney.orc.ontology.core.colbert-indexer :as colbert-indexer]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/auto-embed-perf-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "t"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps ::dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)] (try ~@body (finally (stop-ctx ~sym)))))

(def ^:private concepts
  [{:uri "occupation/1" :label "Occupation One" :description "Does the first kind of work."}
   {:uri "occupation/2" :label "Occupation Two" :description "Does the second kind of work."}
   {:uri "occupation/3" :label "Occupation Three" :description "Does the third kind of work."}])

(defn- land-embedding!
  "Append a `:ontology/concept-embedded` event EXACTLY as the real embed-concept
   command tags it (only a `[:concept id]` tag, `:ontology-id` in the body) — the
   DJL-free way to simulate an ALREADY-embedded concept, so `auto-embed!`'s
   `concept-stream/reduce-concept-embeddings` fold sees it identically to a real
   first-pass embed."
  [ctx oid uri]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :ontology/concept-embedded
                         :tags #{[:concept (random-uuid)]}
                         :body {:uri uri
                                :ontology-id oid
                                :text-embedded (str "text for " uri)
                                :field-source "label+description"
                                :embedding (vec (repeatedly 8 #(double (rand))))
                                :model-id "test-model"
                                :embedded-at "2026-01-01T00:00:00Z"}})]}))

(deftest auto-embed-single-pass-with-metadata-test
  (testing "auto-embed! computes vectors ONCE via the injected batch and lands them
            through the command with identity metadata; landed vectors ARE the batch's
            output (no re-embed, no per-concept projection)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source!
               ctx oid {:status :emitted-drafts
                        :emitted-concepts concepts :emitted-relationships []})
            projected (vec (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {})))
            sentinels (into {} (map (fn [c] [(:uri c) (vec (repeatedly 384 #(double (rand))))]))
                            projected)
            calls (atom 0)
            fake-batch (fn [embeddable _opts]
                         (swap! calls inc)
                         {:embedded-count (count embeddable)
                          :embeddings (mapv (fn [c] {:uri (:uri c)
                                                     :embedding (get sentinels (:uri c))
                                                     :text-embedded (str "t:" (:uri c))})
                                            embeddable)})
            r (#'ds/auto-embed! ctx oid projected :heuristic fake-batch)
            embs (rm/get-all-concept-embeddings ctx {:ontology-id oid})]
        (is (= 3 (count projected)) "precondition: three concepts landed with :id")
        (is (every? :id projected) "projected concepts carry :id (the metadata path)")
        (is (= 1 @calls) "the batched compute ran EXACTLY ONCE (single pass)")
        (is (= 3 (:embedded-count r)) "all three embedded")
        (doseq [c projected]
          (is (= (get sentinels (:uri c)) (:embedding (get embs (:uri c))))
              (str "landed vector for " (:uri c)
                   " is the batch's precomputed vector — no re-embed")))))))

(deftest auto-embed-skips-already-embedded-test
  (testing "GC-12 — auto-embed! reads the already-embedded URI set from the
            :ontology/concept-embedded projection and embeds only the NEW concepts,
            so it does NOT re-embed what embed+index! already embedded (kills the 2×)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source!
               ctx oid {:status :emitted-drafts
                        :emitted-concepts concepts :emitted-relationships []})
            projected (vec (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {})))
            ;; ONE concept is ALREADY embedded (mirrors embed+index!'s first pass).
            already-uri "occupation/2"
            _ (land-embedding! ctx oid already-uri)
            ;; capture WHICH concepts the batch capability is asked to embed.
            batch-seen (atom nil)
            fake-batch (fn [embeddable _opts]
                         (reset! batch-seen (set (map :uri embeddable)))
                         {:embedded-count (count embeddable)
                          :embeddings (mapv (fn [c] {:uri (:uri c)
                                                     :embedding (vec (repeatedly 384 #(double (rand))))
                                                     :text-embedded (str "t:" (:uri c))})
                                            embeddable)})
            r (#'ds/auto-embed! ctx oid projected :heuristic fake-batch)]
        (is (= 3 (count projected)) "precondition: three concepts landed")
        (is (= 2 (:embedded-count r))
            "only the 2 NEW concepts are embedded — the already-embedded one is skipped")
        (is (= #{"occupation/1" "occupation/3"} @batch-seen)
            "the batch capability receives ONLY the NEW set (not the already-embedded uri)")
        (is (not (contains? @batch-seen already-uri))
            "the already-embedded concept is NOT handed to the batch (no re-embed)")))))

;; ---------------------------------------------------------------------------
;; ColBERT indexing at scale — the full O*NET build (36k concepts) stalled because
;; the bridge's 60s default create-index timeout can't fit a large PLAID index
;; (~14 min). auto-index! now surfaces a bridge TIMEOUT (or process-died) NON-fatally
;; so the build COMPLETES on the graph + embedding signals (ColBERT is a rebuildable
;; retrieval accelerator, not the graph). Every OTHER fault still propagates.
;; ---------------------------------------------------------------------------

(deftest auto-index-nonfatal-on-colbert-timeout-test
  (testing "a ColBERT bridge TIMEOUT is surfaced non-fatally (:reason
            :colbert-index-timeout), NOT thrown — the build completes"
    (with-redefs [colbert-indexer/index-concepts!
                  (fn [& _]
                    (throw (java.util.concurrent.TimeoutException.
                            "Bridge call timed out after 60000ms for method create_index")))]
      (let [r (#'ds/auto-index! {} (random-uuid)
                                [{:uri "occupation/1" :label "X" :description "y"}] 1)]
        (is (= false (:indexed? r)) "not indexed")
        (is (= :colbert-index-timeout (:reason r))
            "the timeout is surfaced as a NON-fatal reason, not a thrown exception"))))
  (testing "a bridge process-died is likewise non-fatal (a wrapped ex-info message)"
    (with-redefs [colbert-indexer/index-concepts!
                  (fn [& _] (throw (ex-info "Bridge process died unexpectedly" {})))]
      (let [r (#'ds/auto-index! {} (random-uuid)
                                [{:uri "occupation/1" :label "X" :description "y"}] 1)]
        (is (= :colbert-index-timeout (:reason r)) "process-died → non-fatal"))))
  (testing "any OTHER ColBERT fault still PROPAGATES (no blanket swallow, #5)"
    (with-redefs [colbert-indexer/index-concepts!
                  (fn [& _] (throw (ex-info "some unexpected colbert fault" {})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'ds/auto-index! {} (random-uuid)
                                     [{:uri "occupation/1" :label "X" :description "y"}] 1))
          "an unrecognized fault is NOT masked"))))

;; ---------------------------------------------------------------------------
;; SCALE-2 — the corpus-size guard is RETIRED. The index store streams
;; embeddings.bin through bounded buffers (no single int-indexed allocation),
;; so the 2 GiB artifact ceiling no longer exists — witnessed live at 59k
;; docs / 2.93 GiB artifact with exact planted-marker retrieval. No typed
;; artifact-size error is thrown anywhere anymore, and
;; index-concepts! attempts EVERY corpus size: these tests pin that a
;; formerly-skipped 35k corpus now indexes, alongside the 15k mid-size case.
;; The timeout + corpus-too-small non-fatal classes are UNTOUCHED (see
;; auto-index-nonfatal-on-colbert-timeout-test above). DJL-free:
;; create-index! is faked.
;; ---------------------------------------------------------------------------

(defn- guard-corpus [n]
  (mapv (fn [i] {:uri (str "c/" i) :label (str "Concept " i)
                 :description "some indexable content here"})
        (range n)))

(deftest colbert-index-does-not-skip-15k-corpus-test
  (testing "a 15,000-doc corpus is comfortably UNDER the 2 GiB-ceiling margin —
            index-concepts! must INDEX it (the SCALE-1 witness proved the JVM
            engine handles 36k cleanly; the guard must not refuse mid-size builds)"
    (let [calls (atom [])
          fake-id (random-uuid)]
      (with-redefs [colbert/create-index!
                    (fn [_ctx {:keys [collection]}]
                      (swap! calls conj (count collection))
                      fake-id)]
        (let [r (colbert-indexer/index-concepts!
                 {} (guard-corpus 15000)
                 {:colbert-fields [:label :description]
                  :auto-detect-colbert-fields false})]
          (is (= [15000] @calls) "create-index! was called ONCE with all 15k docs")
          (is (= fake-id (:index-id r)) "the index was actually built")
          (is (= 15000 (:document-count r)) "all 15k documents indexed")
          (is (nil? (:skipped-reason r)) "NOT skipped"))))))

(deftest colbert-index-does-not-skip-35k-corpus-test
  (testing "a 35,000-doc corpus — over the RETIRED 30k guard — must now be
            INDEXED, not skipped: the streamed index store has no artifact
            ceiling (witnessed at 59k docs / 2.93 GiB), so index-concepts!
            attempts every corpus"
    (let [calls (atom [])
          fake-id (random-uuid)]
      (with-redefs [colbert/create-index!
                    (fn [_ctx {:keys [collection]}]
                      (swap! calls conj (count collection))
                      fake-id)]
        (let [r (colbert-indexer/index-concepts!
                 {} (guard-corpus 35000)
                 {:colbert-fields [:label :description]
                  :auto-detect-colbert-fields false})]
          (is (= [35000] @calls) "create-index! was called ONCE with all 35k docs")
          (is (= fake-id (:index-id r)) "the index was actually built")
          (is (= 35000 (:document-count r)) "all 35k documents indexed")
          (is (nil? (:skipped-reason r)) "NOT skipped — the skip contract is dead"))))))
