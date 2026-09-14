(ns ai.obney.orc.ontology.rr22-pattern-key-bindings-test
  "RR-22 — contract tests for `specs/ontology.allium` `OfferedPatternsAreUsable`:

     A pattern offered to a model as proven can actually be used: the code
     within it is present rather than elided, and what it reads and writes
     is declared, so binding it to a new task is a mechanical step rather
     than a reconstruction. A behavior remains evidence a model reasons
     with and never a pipeline it is compelled down.

   Contract tests are never weakened to pass. Seam-4: the real claim command,
   the assembled description read back through `ontology/get-description`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        cache-dir (str "/tmp/rr22-" (random-uuid))]
    {:event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
     :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
     :tenant-id (random-uuid) :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (pubsub/stop (:event-pubsub ctx)) (kv/stop (:cache ctx)) (es/stop (:event-store ctx))
  (let [f (java.io.File. (::cache-dir ctx))]
    (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f))))

(defmacro with-ctx [[s] & body] `(let [~s (create-context)] (try ~@body (finally (stop-context ~s)))))

(defn- bindings
  "RED-first seam: `ontology/pattern-key-bindings` does not exist until RR-22 lands."
  [source]
  (if-let [f (resolve 'ai.obney.orc.ontology.interface/pattern-key-bindings)]
    (f source)
    {:missing-fn 'ai.obney.orc.ontology.interface/pattern-key-bindings}))

(def ^:private pattern-source
  "[:sequence
  [:llm {:reads [:doc] :writes [:notes] :instruction \"list the claims\"}]
  [:code {:reads [:notes :glossary] :writes [:normalized] :fn (fn [{:keys [inputs]}] {:normalized (:notes inputs)})}]
  [:llm {:reads [:normalized] :writes [:summary] :instruction \"summarize\"}]
  [:final {:keys [:summary]}]]")

(deftest pattern-bindings-are-derived-from-the-exact-source
  (testing "external reads are keys read before any node wrote them; writes are every key written; outputs are the final's keys"
    (let [b (bindings pattern-source)]
      (is (= [:doc :glossary] (:reads b)) "notes and normalized are produced inside the pattern; doc and glossary come from outside")
      (is (= [:notes :normalized :summary] (:writes b)))
      (is (= [:summary] (:outputs b)))))
  (testing "`:from` on map-each / chunk-document / aggregate is a read; keys are declared once each"
    (let [b (bindings "[:sequence
                        [:chunk-document {:from :doc :writes [:chunks] :size 100}]
                        [:map-each {:from :chunks :writes [:findings]}
                          [:llm {:reads [:chunk :chunk :glossary] :writes [:finding]}]]
                        [:aggregate {:from :findings :writes [:merged]}]
                        [:final {:keys [:merged]}]]")]
      (is (= [:doc :chunk :glossary] (:reads b)) "the document fed to chunking is an external read; internal :from keys are not")
      (is (= [:chunks :findings :finding :merged] (:writes b)))
      (is (= [:merged] (:outputs b))))
    (is (= [:items :item] (:reads (bindings "[:map-each {:from :items :writes [:out]} [:llm {:reads [:item] :writes [:o]}]]")))
        "a pattern that starts by iterating an outside collection declares the collection (and the per-item key it expects)"))
  (testing "a pattern whose code was elided, or that does not parse, declares nothing rather than lying"
    (is (nil? (bindings "[:sequence [:code {:reads [:a] :writes [:b] :fn \"<inline-fn>\"}] [:final {:keys [:b]}]]")))
    (is (nil? (bindings "[:sequence [:llm {:reads [:a]")))
    (is (nil? (bindings nil)))))

(deftest assembled-strength-entry-carries-its-bindings-additively
  (testing "the assembled description's strength entry declares the pattern's bindings without changing any existing field"
    (with-ctx [ctx]
      (let [class-id (random-uuid)]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/record-claim-deltas
                 :command/id (random-uuid) :command/timestamp (time/now)
                 :granularity :tree-class :target-identifier class-id
                 :deltas [{:operation :add :kind :strength
                           :content "emits the shape-1 worked tree for tasks of this class"
                           :recommendation pattern-source
                           :episodes [] :from-legacy-corpus false
                           :evidence-basis :emitted-artifact-outcome}
                          {:operation :add :kind :strength
                           :content "routes before extracting" :recommendation nil
                           :episodes [] :from-legacy-corpus false
                           :evidence-basis :authored}]
                 :evidence-event-count 0
                 :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)}))
        (let [strengths (:strengths (ontology/get-description ctx :tree-class class-id))
              with-pattern (first (filter :recommended-pattern strengths))
              without (first (remove :recommended-pattern strengths))]
          (is (= pattern-source (:recommended-pattern with-pattern)) "the pattern is still offered as its exact source")
          (is (= [:doc :glossary] (:pattern-reads with-pattern)))
          (is (= [:notes :normalized :summary] (:pattern-writes with-pattern)))
          (is (= [:summary] (:pattern-outputs with-pattern)))
          (is (not (contains? without :pattern-reads)) "an entry without a pattern declares no bindings")
          (is (= #{:trait :confidence :evidence-count :recommended-pattern :first-observed-at :last-reinforced-at
                   :pattern-reads :pattern-writes :pattern-outputs}
                 (set (keys with-pattern)))
              "additive: exactly the pre-existing fields plus the three binding fields"))))))
