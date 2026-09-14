(ns ai.obney.orc.orc-service.rr20-selector-corroboration-ranking-test
  "RR-20 delta fix (Finding 2 from inspection): the RR-20 issue's acceptance
   criterion is 'The selector prefers success-backed, occurrence-corroborated
   claims over bare emitted artefacts' — support-only reinforcement does NOT
   meet this: a bare artefact re-emitted three times (support 4) can outrank
   a shape corroborated by only ONE campaign verdict (support 3), and
   `harvest-body` returned the bare one. Orchestrator design decision,
   implemented exactly:

     (a) new mechanical `evidence-basis :campaign-verdict`, used ONLY on the
         occurrence-corroboration reinforcement delta;
     (b) `read-models/reinforce-claim` increments an optional, additive
         `:verdict-corroborations` claim field when the applied delta
         declares `:campaign-verdict`; forwarded onto the assembled
         `principle-entry` ONLY when positive; never forwarded through
         `interface/compact-strengths`;
     (c) `harvest/best-recommended-pattern` ranks `:verdict-corroborations`
         DESCENDING first, then `:confidence` descending, then
         `:last-reinforced-at`. `harvest-body` stays `[desc occurrences]`.

   This namespace tests (a)-(c) through PUBLIC interfaces: the real
   `:ontology/record-claim-deltas` command, `ontology/get-claims`,
   `ontology/get-description`, `harvest/harvest-body`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [malli.core :as m]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.evidence-guard :as guard]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- stub-predict-fixture [f]
  (with-redefs [llm/predict (fn [_provider _module _inputs _options]
                              {:outputs {:operations []}
                               :usage {:total-tokens 1}
                               :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr20-selector-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc n {:keys [handler-fn topics]}]
                      (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                              :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

;; -----------------------------------------------------------------------------
;; (a) the enum member exists and is admitted past the evidence guard.
;; -----------------------------------------------------------------------------

(deftest campaign-verdict-is-a-declared-evidence-basis-admitted-without-episodes
  (testing "schema: :campaign-verdict is a member of evidence-basis"
    (is (m/validate ontology-schemas/evidence-basis :campaign-verdict)))
  (testing "guard: a delta naming no episode and declaring :campaign-verdict is grounded by declared provenance"
    (is (contains? guard/declared-bases-admitted :campaign-verdict))
    (is (:grounded?
         (guard/evidence-verdict {} {:episodes [] :evidence-basis :campaign-verdict}))
        "admitted exactly like the other mechanical bases")))

;; -----------------------------------------------------------------------------
;; (b) reinforce-claim increments :verdict-corroborations, through the REAL
;; :ontology/record-claim-deltas command (a public boundary, not the private fn).
;; -----------------------------------------------------------------------------

(defn- add-claim!
  ([ctx class-id content] (add-claim! ctx class-id content "the-pattern"))
  ([ctx class-id content recommendation]
   (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :granularity :tree-class
            :target-identifier class-id
            :deltas [{:operation :add :kind :strength
                      :content content :recommendation recommendation
                      :episodes [] :from-legacy-corpus false
                      :evidence-basis :emitted-artifact-outcome}]
            :evidence-event-count 0
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)}))))

(defn- support-delta! [ctx class-id claim-id basis]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :granularity :tree-class
           :target-identifier class-id
           :deltas [{:operation :support :target-claim claim-id
                     :kind :strength :content "shape-x"
                     :recommendation "the-pattern"
                     :episodes [] :from-legacy-corpus false
                     :evidence-basis basis}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- claim-by-content [ctx class-id content]
  (first (filter #(= content (:content %)) (ontology/get-claims ctx :tree-class class-id))))

(deftest reinforce-claim-increments-verdict-corroborations-only-for-campaign-verdict-deltas
  (testing "a :support delta declaring :campaign-verdict increments :verdict-corroborations;
            an ordinary :support (e.g. :emitted-artifact-outcome) leaves it at 0/absent"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)]
        (add-claim! ctx class-id "shape-x")
        (let [added (claim-by-content ctx class-id "shape-x")]
          (is (= 2 (:support added)) "seeded at the initial support")
          (is (zero? (or (:verdict-corroborations added) 0))
              "a freshly-added claim has no verdict corroboration")
          (support-delta! ctx class-id (:claim-id added) :emitted-artifact-outcome)
          (let [after-ordinary-support (claim-by-content ctx class-id "shape-x")]
            (is (= 3 (:support after-ordinary-support)))
            (is (zero? (or (:verdict-corroborations after-ordinary-support) 0))
                "an ordinary re-emit support does NOT count as a verdict corroboration"))
          (support-delta! ctx class-id (:claim-id added) :campaign-verdict)
          (let [after-verdict (claim-by-content ctx class-id "shape-x")]
            (is (= 4 (:support after-verdict)))
            (is (= 1 (:verdict-corroborations after-verdict))
                "a :campaign-verdict support delta increments :verdict-corroborations")))))))

(deftest verdict-corroborations-surfaces-on-the-assembled-strength-entry-only-when-positive
  (testing "the assembled body's :strengths entry carries :verdict-corroborations only when > 0"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)]
        (add-claim! ctx class-id "shape-y")
        (let [added (claim-by-content ctx class-id "shape-y")
              strengths-before (:strengths (ontology/get-description ctx :tree-class class-id))
              entry-before (first (filter #(= "shape-y" (:trait %)) strengths-before))]
          (is (not (contains? entry-before :verdict-corroborations))
              "absent before any campaign-verdict reinforcement")
          (support-delta! ctx class-id (:claim-id added) :campaign-verdict)
          (let [strengths-after (:strengths (ontology/get-description ctx :tree-class class-id))
                entry-after (first (filter #(= "shape-y" (:trait %)) strengths-after))]
            (is (= 1 (:verdict-corroborations entry-after))
                "present and correct once corroborated")))))))

;; -----------------------------------------------------------------------------
;; (c) THE ACCEPTANCE CRITERION: a bare artefact re-emitted 3x (support 4)
;; must NOT outrank a shape corroborated by only ONE campaign verdict
;; (support 3) — harvest-body must return the corroborated shape.
;; -----------------------------------------------------------------------------

(deftest a-corroborated-shape-outranks-a-more-repeated-bare-artefact
  (testing "bare-x3 (support 4, no corroboration) vs corroborated-x1 (support 3,
            :verdict-corroborations 1): harvest-body returns the CORROBORATED one"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)]
        ;; The bare artefact: :add then two more :support (:emitted-artifact-outcome) -> support 4.
        (add-claim! ctx class-id "bare-shape" "bare-pattern")
        (let [bare (claim-by-content ctx class-id "bare-shape")]
          (support-delta! ctx class-id (:claim-id bare) :emitted-artifact-outcome)
          (support-delta! ctx class-id (:claim-id bare) :emitted-artifact-outcome))
        ;; The corroborated shape: :add then ONE :campaign-verdict support -> support 3.
        (add-claim! ctx class-id "corroborated-shape" "corroborated-pattern")
        (let [corroborated (claim-by-content ctx class-id "corroborated-shape")]
          (support-delta! ctx class-id (:claim-id corroborated) :campaign-verdict))
        (let [bare-final (claim-by-content ctx class-id "bare-shape")
              corroborated-final (claim-by-content ctx class-id "corroborated-shape")]
          (is (= 4 (:support bare-final)) "bare artefact has MORE raw support")
          (is (= 3 (:support corroborated-final)) "corroborated shape has LESS raw support")
          (is (zero? (or (:verdict-corroborations bare-final) 0)))
          (is (= 1 (:verdict-corroborations corroborated-final))))
        (let [desc (ontology/get-description ctx :tree-class class-id)
              body (harvest/harvest-body desc 12)]
          (is (= "corroborated-pattern" (:recommended-pattern body))
              "THE ACCEPTANCE CRITERION: harvest-body offers the CORROBORATED shape
               (lower raw support, 3) over the bare artefact re-emitted more times
               (higher raw support, 4) — verdict-corroborations is the primary sort
               key, ahead of confidence/support"))))))
