(ns ai.obney.orc.ontology.cc3-assembly-properties-test
  "CC-3 — properties of the ASSEMBLED body that the propagated contract
   suite (cc3-body-assembly-test) does not guard, added by the implementer
   after mutation testing showed the gaps.

   TWO MEASURED BLIND SPOTS, each reproduced before this file existed:

     1. Replacing `derive-confidence` with a CONSTANT leaves the whole
        contract suite green (26/26). Its confidence test asserts only
        `(>= c1 c0)`, which a constant satisfies. But a constant
        confidence silently destroys the ranking every real consumer
        depends on.

     2. Deleting the support-ranking from `assemble-body` also leaves the
        contract suite green. Body ORDER is load-bearing: EL-2's
        `enrich-candidate-evidence` truncates with `(take 3)` and does NOT
        sort, so an unranked body hands the reranker whichever claims
        happened to be added first.

   So these tests assert the two things through the surfaces that actually
   consume them — EL-2's enrichment, EL-5's `avoid-strings`, and harvest's
   worked-DSL pick — rather than re-asserting the arithmetic.

   NOTE: R-Inject's principle render lives in the orc-service component and
   is therefore exercised out-of-band (see the CC-3 report), not here — an
   ontology test must not depend on orc-service."
  (:require [ai.obney.orc.ontology.core.todo-processors :as processors]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.commands :as commands]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m]))

(defn- with-evidence-guard-satisfied [f]
  (with-redefs [queries/evidence-verdict
                (fn [_ctx delta]
                  {:grounded? true :settled-by :deterministic
                   :episodes (vec (:episodes delta))})]
    (f)))

(use-fixtures :each with-evidence-guard-satisfied)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc3-props-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                        :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro ^:private with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- delta [op overrides]
  (merge {:operation op :kind :weakness :content "a claim"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))

(defn- record! [ctx target deltas]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :granularity :tree-class
            :target-identifier target
            :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- claim-id-for [ctx target content]
  (:claim-id (first (filter #(= content (:content %))
                            (ontology/get-claims ctx :tree-class target)))))

(defn- seed-three-tier-target!
  "A target carrying three strengths at DIFFERENT support levels (1 / 4 / 8),
   plus a guard claim and a weakness. Returns the target id."
  [ctx]
  (let [target (random-uuid)]
    (record! ctx target
             [(delta :add {:kind :capability :content "CAP-weak"})
              (delta :add {:kind :capability :content "CAP-strong"})
              (delta :add {:kind :guard :content "no verification command exists"})
              (delta :add {:kind :strength :content "S-weak"
                           :context-guard "gw-weak" :recommendation "[:llm {:weak true}]"})
              (delta :add {:kind :strength :content "S-mid"
                           :context-guard "gw-mid" :recommendation "[:llm {:mid true}]"})
              (delta :add {:kind :strength :content "S-top"
                           :context-guard "gw-top" :recommendation "[:llm {:top true}]"})
              (delta :add {:kind :weakness :content "W-only"
                           :context-guard "the edit produced no changes"
                           :recommendation "re-read and retry"})])
    (let [mid (claim-id-for ctx target "S-mid")
          top (claim-id-for ctx target "S-top")
          cap (claim-id-for ctx target "CAP-strong")
          guard (claim-id-for ctx target "no verification command exists")
          weak (claim-id-for ctx target "W-only")]
      (dotimes [_ 3] (record! ctx target [(delta :support {:target-claim mid})]))
      (dotimes [_ 7] (record! ctx target [(delta :support {:target-claim top})]))
      (dotimes [_ 2] (record! ctx target [(delta :support {:target-claim cap})]))
      (dotimes [_ 4] (record! ctx target [(delta :support {:target-claim guard})]))
      (dotimes [_ 4] (record! ctx target [(delta :support {:target-claim weak})])))
    target))

(deftest derived-confidence-strictly-separates-differently-supported-claims
  (with-test-ctx [ctx]
    (let [target (seed-three-tier-target! ctx)
          body (ontology/get-description ctx :tree-class target)
          conf (into {} (map (juxt :trait :confidence)) (:strengths body))]
      (is (= 3 (count conf)) "three strengths survive")
      (is (= [1 4 8] (sort (map :evidence-count (:strengths body))))
          "supporting episodes accumulate 1 / 4 / 8")
      (is (< (double (conf "S-weak")) (double (conf "S-mid")) (double (conf "S-top")))
          (str "confidence must be a STRICTLY increasing function of support — a "
               "constant (or any flat region) is what the contract suite's `>=` "
               "assertion cannot see, and it destroys every downstream ranking: "
               (pr-str conf)))
      (testing "and it stays inside the invariant's bounds at both extremes"
        (is (every? #(<= 0.0 (double (:confidence %)) 1.0)
                    (concat (:strengths body) (:weaknesses body))))
        (is (< (double (conf "S-top")) 1.0)
            "no finite amount of evidence reaches certainty")))))

(deftest the-assembled-body-is-ordered-by-earned-support
  (with-test-ctx [ctx]
    (let [target (seed-three-tier-target! ctx)
          body (ontology/get-description ctx :tree-class target)]
      (is (= ["S-top" "S-mid" "S-weak"] (mapv :trait (:strengths body)))
          "strengths come back best-supported first")
      (is (= ["CAP-strong" "CAP-weak"] (:capabilities body))
          "string sections are ranked too — capabilities are not insertion-ordered"))))

(deftest el2-enrichment-truncates-in-support-order-not-insertion-order
  (with-test-ctx [ctx]
    (let [target (seed-three-tier-target! ctx)
          enrich #'queries/enrich-candidate-evidence
          enriched (enrich ctx {:document-id "d1"
                                :document-metadata {:granularity :tree-class
                                                    :target-id (str target)}})]
      (is (= "S-top" (:trait (first (:strengths enriched))))
          (str "EL-2 truncates with (take 3) and does NOT sort, so the reranker "
               "sees whatever the body put first — that must be the "
               "best-supported claim, not the first one recorded"))
      (testing "and EL-5's negative signal reads both guard sources off it"
        (let [avoid (logic/avoid-strings enriched)]
          (is (some #{"no verification command exists"} avoid)
              "a :guard claim reaches the domain penalty via body-level :avoid-when")
          (is (some #{"the edit produced no changes"} avoid)
              "a weakness's context-guard reaches it via the per-weakness :avoid-when")))
      (testing "and EL-5's positive signal reads the good-whens"
        (let [pos (logic/positive-strings enriched)]
          (is (some #{"gw-top"} pos))
          (is (every? (complement str/blank?) pos))
          (is (not (some #{(:content enriched)} pos))
              "the assembled summary is NOT a positive signal (ADR 0026)"))))))

(deftest harvest-picks-the-best-supported-strengths-worked-dsl
  (with-test-ctx [ctx]
    (let [target (seed-three-tier-target! ctx)
          body (ontology/get-description ctx :tree-class target)
          harvested (queries/harvest-body body 42)]
      (is (= "[:llm {:top true}]" (:recommended-pattern harvested))
          "harvest sorts strengths by :confidence — derived confidence must make
           the best-supported claim win, or a harvested behavior ships the
           weakest worked example it has")
      (is (m/validate schemas/description-body (dissoc harvested :recommended-pattern))
          "a body harvested FROM an assembled body still satisfies the shipped schema"))))

(deftest assembly-over-a-legacy-body-keeps-the-graph-metadata-and-the-version-line
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-tree-class-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id target
                :body {:capabilities ["legacy cap"] :strengths [] :weaknesses []
                       :representative-uses [] :avoid-when []
                       :summary "legacy summary" :version 7
                       :consolidated-from-event-count 3
                       :parent-tree-id "parent-abc"
                       :scope :tree-class}}))
      (record! ctx target [(delta :add {:kind :capability :content "claim cap"})])
      (let [b (ontology/get-description ctx :tree-class target)]
        (is (= 8 (:version b))
            "the version line continues from the legacy body rather than restarting")
        (is (= "parent-abc" (:parent-tree-id b))
            "the SKOS parent is graph metadata, not knowledge — assembly must not drop it")
        (is (= :tree-class (:scope b))
            "the retrieval dimension survives assembly")
        (is (= ["claim cap"] (:capabilities b))
            "the KNOWLEDGE is replaced by what the claims say")
        (is (= 2 (count (ontology/get-description-history ctx :tree-class target)))
            "both write paths append to one history")
        (is (not= (:summary b) "legacy summary")
            "the summary is re-derived from claims")))))
