(ns ai.obney.orc.ontology.s15-cq-runner-test
  "S15 — Competency-question runner with three-layer negation posture.

   Each deftest corresponds to one acceptance criterion from the slice +
   the prompt's required-coverage list. Adversarial cases are explicitly
   tagged; the load-bearing tests verify the three-way distinction
   (:pass / :fail / :unknown) holds at the EVENT layer, the COMMAND
   layer, and the public-interface QUERY layer.

   No mocks where mocks would hide a bug: the runner mechanics are
   verified through real Grain process-command + a controlled judge-fn
   (so the QUALITY of the verdict is decided OUTSIDE the runner — the
   runner's job is to ROUTE correctly + EMIT correctly + DERIVE the
   metric correctly). Real-LLM verdict quality is tested by the
   `s15_prototype` driver in development/src/ (the production prompt
   lives in cq-runner/judge-prompt-template, exercised by that driver).

   Test ontology fixture mirrors the prototype's adversarial graph:
   - 3 directors WITH oscar-related edges, 2 WITHOUT
   - films + genres (drama, sci-fi) — NO horror in graph
   - one director with a :retired edge, others with NO retirement facts
   - directors but no :collaborated-with edges (collaboration gap)"
  (:require [clojure.test :refer [deftest testing is]]
            ;; Required for schema registration
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Test context
;; =============================================================================

(defn- create-context []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/s15-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; Seed helpers
;; =============================================================================

(def ^:private test-ontology-id #uuid "5e0e5e0e-5e0e-5e0e-5e0e-5e0e5e0e5e0e")
(def ^:private alt-ontology-id #uuid "aa1aa1aa-1aa1-1aa1-1aa1-aa1aa1aa1aa1")

(defn- create-concept! [ctx ontology-id uri label]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/create-concept
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :uri uri
           :label label
           :description (str label " concept")
           :scope :custom
           :broader []
           :indicators []})))

(defn- create-relationship! [ctx ontology-id s p t]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/create-relationship
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :source-uri s
           :predicate p
           :target-uri t
           :confidence-class :extracted
           :properties {}})))

(defn- record-spec! [ctx ontology-id body]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body body})))

(defn- seed-adversarial-graph!
  "Build the prototype's adversarial graph in the given ontology-id."
  [ctx ontology-id]
  (doseq [[uri label] [["concept:dir/jane-roe"   "Jane Roe"]
                       ["concept:dir/john-doe"   "John Doe"]
                       ["concept:dir/sam-wei"    "Sam Wei"]
                       ["concept:dir/leo-bird"   "Leo Bird"]
                       ["concept:dir/mira-sun"   "Mira Sun"]
                       ["concept:film/red-dawn"  "Red Dawn"]
                       ["concept:film/blue-tide" "Blue Tide"]
                       ["concept:film/star-net"  "Star Net"]
                       ["concept:award/oscar"    "Academy Award"]
                       ["concept:role/director"  "Director"]
                       ["concept:genre/drama"    "Drama"]
                       ["concept:genre/sci-fi"   "Science Fiction"]]]
    (create-concept! ctx ontology-id uri label))
  (doseq [[s p t] [["concept:dir/jane-roe"  "directed" "concept:film/red-dawn"]
                   ["concept:dir/john-doe"  "directed" "concept:film/blue-tide"]
                   ["concept:dir/sam-wei"   "directed" "concept:film/star-net"]
                   ["concept:dir/jane-roe"  "won"      "concept:award/oscar"]
                   ["concept:dir/john-doe"  "won"      "concept:award/oscar"]
                   ["concept:dir/sam-wei"   "won"      "concept:award/oscar"]
                   ["concept:film/red-dawn"  "has-genre" "concept:genre/drama"]
                   ["concept:film/blue-tide" "has-genre" "concept:genre/drama"]
                   ["concept:film/star-net"  "has-genre" "concept:genre/sci-fi"]
                   ["concept:dir/jane-roe"  "has-role" "concept:role/director"]
                   ["concept:dir/john-doe"  "has-role" "concept:role/director"]
                   ["concept:dir/sam-wei"   "has-role" "concept:role/director"]
                   ["concept:dir/leo-bird"  "has-role" "concept:role/director"]
                   ["concept:dir/mira-sun"  "has-role" "concept:role/director"]
                   ["concept:dir/sam-wei"   "retired"  "concept:date/2020"]]]
    (create-relationship! ctx ontology-id s p t))
  (Thread/sleep 200))

;; =============================================================================
;; The 15 adversarial CQs (locked from the prototype's hand-review)
;; =============================================================================

(def adversarial-cqs
  "Three layers. The expected-verdict is the GROUND TRUTH the prototype
   verified by hand-review against the production judge prompt."
  [;; Layer 1 structural
   {:cq "Is there a Director concept?"            :verdict :pass :layer :layer-1-structural :judged? false}
   {:cq "Is there a Wombat concept?"              :verdict :fail :layer :layer-1-structural :judged? false}
   {:cq "Is there an Oscar concept?"              :verdict :pass :layer :layer-1-structural :judged? false}
   {:cq "Is there a Mira Sun concept?"            :verdict :pass :layer :layer-1-structural :judged? false}
   ;; Layer 2 semantic exists
   {:cq "Which directors won an Oscar?"           :verdict :pass    :layer :layer-2-semantic-exists  :judged? true}
   {:cq "Which directors directed a horror film?" :verdict :fail    :layer :layer-2-semantic-exists  :judged? true}
   {:cq "Are there any drama films in the graph?" :verdict :pass    :layer :layer-2-semantic-exists  :judged? true}
   {:cq "Which films are documentaries?"          :verdict :fail    :layer :layer-2-semantic-exists  :judged? true}
   {:cq "Which directors have a director role?"   :verdict :pass    :layer :layer-2-semantic-exists  :judged? true}
   ;; Layer 3 explicit unknown
   {:cq "Did director Leo Bird retire?"             :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}
   {:cq "Did director Mira Sun retire?"             :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}
   {:cq "Has Jane Roe won more Oscars than John Doe?" :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}
   {:cq "What is Leo Bird's birth year?"             :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}
   {:cq "Which directors have collaborated with each other?" :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}
   {:cq "Has Mira Sun directed any feature film?" :verdict :unknown :layer :layer-3-explicit-unknown :judged? true}])

(defn- expected-by-cq [cq-text]
  (first (filter #(= cq-text (:cq %)) adversarial-cqs)))

;; =============================================================================
;; Controlled judge — encodes the ground-truth verdict mapping
;; =============================================================================

(defn- counting-judge
  "Test judge: deterministically returns the prototype-validated verdict
   for each adversarial CQ. Records every invocation in `calls-atom` so
   tests can assert zero-LLM behavior for Layer 1.

   The :evidence string IS read by the judge — it asserts the
   evidence-text contains the expected closed-world enumeration
   markers. A bug where the runner stops passing evidence would fail
   the grounding test."
  [calls-atom]
  (fn [{:keys [question evidence]}]
    (swap! calls-atom conj question)
    (let [exp (expected-by-cq question)
          v (:verdict exp :unknown)
          ;; Return at least one evidence URI when the judge would have
          ;; one — for the 'evidence-uris must not be empty on a real
          ;; verdict' adversarial test. When verdict is :unknown the
          ;; judge legitimately returns NO evidence URIs but DOES name
          ;; the gap.
          eu (case v
               :pass ["concept:dir/jane-roe"]
               :fail []
               :unknown [])
          gaps (case v
                 :unknown ["retirement-status edges or birth-year attributes"]
                 nil)]
      ;; Defensive: make sure the evidence block we received is the
      ;; closed-world rendering (this catches a bug where the runner
      ;; passes empty evidence to the judge).
      (assert (and (string? evidence)
                   (or (clojure.string/includes? evidence "ALL CONCEPTS IN SCOPE")
                       (clojure.string/includes? evidence "ALL RELATIONSHIPS IN SCOPE")))
              (str "judge received non-closed-world evidence: "
                   (subs (str evidence) 0 (min 200 (count (str evidence))))))
      {:verdict v
       :reasoning (str "Controlled judge verdict for: " question)
       :evidence-uris eu
       :gaps gaps})))

(defn- raise-on-call-judge
  "Adversarial judge that throws if called. Used in Layer-1-only tests
   to PROVE zero LLM invocations."
  []
  (fn [_]
    (throw (ex-info "Judge MUST NOT be called on a Layer-1 question" {}))))

;; =============================================================================
;; AC1 — Routing: Layer 1 fires for structural shapes, dispatches to layer 2/3 otherwise
;; =============================================================================

(deftest layer-classification-routes-deterministically
  (testing "Layer 1 fires for 'Is there a X concept?'; everything else
            routes to layer-2/3 (semantic). Routing is purely
            structural — does NOT depend on graph content."
    (is (= :layer-1-structural
           (:layer (cqr/classify-cq-layer "Is there a Director concept?"))))
    (is (= "director"
           (:term (cqr/classify-cq-layer "Is there a Director concept?"))))
    (is (= :layer-1-structural
           (:layer (cqr/classify-cq-layer "Does a Wombat concept exist?"))))
    (is (= :layer-2-semantic-exists
           (:layer (cqr/classify-cq-layer "Which directors won an Oscar?"))))
    (is (= :layer-2-semantic-exists
           (:layer (cqr/classify-cq-layer "Did director Leo Bird retire?"))))))

;; =============================================================================
;; AC2 — Layer 1: zero LLM invocations + correct structural verdicts
;; =============================================================================

(deftest layer-1-zero-llm-invocations
  (testing "Layer-1 structural CQs are answered by the projection alone;
            the judge-fn is NEVER called. ADVERSARIAL: the judge is set
            to a fn that THROWS if invoked. Layer-1 verdicts must
            still succeed."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [judge (raise-on-call-judge)
            v1 (cqr/evaluate-cq {:cq-text "Is there a Director concept?"
                                 :ontology-id test-ontology-id
                                 :ctx ctx
                                 :judge-fn judge
                                 :hybrid-search-fn (fn [_ _] {:results []})
                                 :get-concepts-fn (fn [opts]
                                                    (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                 :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})
            v2 (cqr/evaluate-cq {:cq-text "Is there a Wombat concept?"
                                 :ontology-id test-ontology-id
                                 :ctx ctx
                                 :judge-fn judge
                                 :hybrid-search-fn (fn [_ _] {:results []})
                                 :get-concepts-fn (fn [opts]
                                                    (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                 :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})
            v3 (cqr/evaluate-cq {:cq-text "Is there an Oscar concept?"
                                 :ontology-id test-ontology-id
                                 :ctx ctx
                                 :judge-fn judge
                                 :hybrid-search-fn (fn [_ _] {:results []})
                                 :get-concepts-fn (fn [opts]
                                                    (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                 :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= :pass (:verdict v1)))
        (is (= :fail (:verdict v2)))
        (is (= :pass (:verdict v3))
            "Oscar matches via URI fragment (concept:award/oscar) even
             though label is 'Academy Award' — structural existence
             checks URI too")
        (is (false? (:judged-by? v1)))
        (is (= :layer-1-structural (:layer v1)))
        (is (false? (:judged-by? v2)))
        (is (false? (:judged-by? v3)))
        (is (seq (:evidence-uris v1))
            "Layer-1 verdict surfaces the matching URI as evidence")
        (is (empty? (:evidence-uris v2))
            "Layer-1 :fail has empty evidence-uris (no match by definition)")))))

;; =============================================================================
;; AC3 — Layer 2/3 grounding: judge receives closed-world evidence + uses URIs
;; =============================================================================

(deftest layer-2-judge-receives-closed-world-evidence
  (testing "Layer-2 CQs invoke the judge with a closed-world evidence
            block (concepts + relationships enumeration). ADVERSARIAL:
            the controlled judge ASSERTS the evidence contains
            'ALL CONCEPTS IN SCOPE' / 'ALL RELATIONSHIPS IN SCOPE'
            markers; a runner bug stripping the closed-world enumeration
            would fire that assertion."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [calls (atom [])
            judge (counting-judge calls)
            v (cqr/evaluate-cq {:cq-text "Which directors won an Oscar?"
                                :ontology-id test-ontology-id
                                :ctx ctx
                                :judge-fn judge
                                :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                                :get-concepts-fn (fn [opts]
                                                   (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= 1 (count @calls)) "Judge invoked exactly once for Layer-2 CQ")
        (is (= :pass (:verdict v)))
        (is (true? (:judged-by? v)))
        (is (= :layer-2-semantic-exists (:layer v)))
        (is (seq (:evidence-uris v))
            "ADVERSARIAL: a Layer-2 :pass verdict MUST carry non-empty
             :evidence-uris. Empty evidence on a :pass verdict means the
             judge ignored the evidence — that's a bug.")))))

;; =============================================================================
;; AC4 — Layer 3 explicit unknown verdict (the load-bearing assertion)
;; =============================================================================

(deftest layer-3-explicit-unknown-not-default
  (testing "Layer-3 CQs over knowledge GAPS produce :verdict :unknown
            specifically — NOT :fail (which would be the closed-world NO
            for a present-category check) and NOT :pass. The judge's
            three-way distinction is the product.

            Every Layer-3 case must verdict :unknown when the judge
            applies the production prompt's three-way distinction over
            the closed-world evidence."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [calls (atom [])
            judge (counting-judge calls)
            layer-3-cqs (filter #(= :layer-3-explicit-unknown (:layer %)) adversarial-cqs)]
        (is (= 6 (count layer-3-cqs))
            "Adversarial set carries ≥6 Layer-3 explicit-unknown CQs")
        (doseq [{:keys [cq]} layer-3-cqs]
          (let [v (cqr/evaluate-cq {:cq-text cq
                                    :ontology-id test-ontology-id
                                    :ctx ctx
                                    :judge-fn judge
                                    :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                                    :get-concepts-fn (fn [opts]
                                                       (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                    :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
            (is (= :unknown (:verdict v))
                (str "ADVERSARIAL: '" cq "' must verdict :unknown — not "
                     ":fail and not :pass. Verdict was: " (:verdict v)))
            (is (= :layer-3-explicit-unknown (:layer v))
                "Verdict-driven :layer label is :layer-3-explicit-unknown")
            (is (true? (:judged-by? v))
                "Layer-3 ALWAYS goes through the judge")
            (is (seq (:gaps v))
                (str ":gaps must name the missing fact-kind on :unknown — "
                     "no empty :gaps on a real :unknown verdict ('" cq "')"))))))))

;; =============================================================================
;; AC5 — Runner enforces non-default :unknown: a judge returning garbage RAISES
;; =============================================================================

(deftest invalid-judge-output-raises-not-falls-back
  (testing "ADVERSARIAL: if the judge returns a verdict that isn't
            :pass / :fail / :unknown, the runner RAISES — there is NO
            silent fallback to :unknown. round-3 Q7's explicit-unknown
            is a JUDGE OUTPUT, not a runner default. A swallowed-error
            path masking judge bugs is a failure-mode under test."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [bad-judge (fn [_] {:verdict :garbage
                               :reasoning "oops"
                               :evidence-uris []
                               :gaps []})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"invalid verdict"
             (cqr/evaluate-cq {:cq-text "Which directors won an Oscar?"
                               :ontology-id test-ontology-id
                               :ctx ctx
                               :judge-fn bad-judge
                               :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                               :get-concepts-fn (fn [opts]
                                                  (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                               :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})))))))

;; =============================================================================
;; AC5b — Ungrounded :pass verdict (judge returns :pass with empty URIs) RAISES
;; =============================================================================

(deftest pass-verdict-without-evidence-uris-raises
  (testing "ADVERSARIAL grounding guard: a judge that returns :pass with
            NO evidence-uris is hallucinating affirmation — the runner
            RAISES. A :pass verdict MUST be grounded in retrieved
            evidence. (:fail and :unknown legitimately carry no URIs —
            those are answers about absence; only :pass needs grounding.)"
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [ungrounded-judge (fn [_] {:verdict :pass
                                      :reasoning "I just know"
                                      :evidence-uris []
                                      :gaps []})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"NO evidence-uris"
             (cqr/evaluate-cq {:cq-text "Which directors won an Oscar?"
                               :ontology-id test-ontology-id
                               :ctx ctx
                               :judge-fn ungrounded-judge
                               :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                               :get-concepts-fn (fn [opts]
                                                  (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                               :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships}))))
      ;; Reciprocal: :fail and :unknown CAN have empty evidence-uris
      ;; (those are absence answers).
      (let [fail-empty-judge (fn [_] {:verdict :fail
                                      :reasoning "no horror films"
                                      :evidence-uris []
                                      :gaps []})
            v (cqr/evaluate-cq {:cq-text "Which directors directed a horror film?"
                                :ontology-id test-ontology-id
                                :ctx ctx
                                :judge-fn fail-empty-judge
                                :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                                :get-concepts-fn (fn [opts]
                                                   (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= :fail (:verdict v))
            ":fail with empty evidence-uris is LEGITIMATE — closed-world NO")
        (is (empty? (:evidence-uris v))))
      (let [unknown-empty-judge (fn [_] {:verdict :unknown
                                         :reasoning "graph silent"
                                         :evidence-uris []
                                         :gaps ["retirement edges"]})
            v (cqr/evaluate-cq {:cq-text "Did director Leo Bird retire?"
                                :ontology-id test-ontology-id
                                :ctx ctx
                                :judge-fn unknown-empty-judge
                                :hybrid-search-fn ai.obney.orc.ontology.core.retrieval/hybrid-search
                                :get-concepts-fn (fn [opts]
                                                   (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= :unknown (:verdict v))
            ":unknown with empty evidence-uris is LEGITIMATE — graph
             lacks the kind of facts needed")
        (is (seq (:gaps v))
            "But :unknown MUST name the gap — that's where the grounding
             obligation lives")))))

;; =============================================================================
;; AC6 — Production prompt: three-way distinction language present
;; =============================================================================

(deftest production-judge-prompt-encodes-three-way-distinction
  (testing "The production prompt explicitly distinguishes the closed-
            world NO (:fail) from the gap (:unknown). NOT a test-only
            string — the EXACT text that ships in production is what
            this assertion reads. Catches a 'tests passed but prod
            prompt is lazy' regression."
    (let [p cqr/judge-prompt-template]
      (is (re-find #"(?i):pass" p))
      (is (re-find #"(?i):fail" p))
      (is (re-find #"(?i):unknown" p))
      (is (re-find #"(?i)closed-world|closed world" p)
          "Prompt names the closed-world distinction")
      (is (re-find #"(?i)not a default|not a fallback" p)
          "Prompt EXPLICITLY refuses :unknown as a default")
      (is (re-find #"(?i)comforting middle" p)
          "Prompt EXPLICITLY names the comforting-middle failure mode")
      (is (re-find #"(?i)graph LACKS|lacks the" p)
          "Prompt distinguishes 'lacks the fact' as the :unknown signal")
      (is (re-find #"(?i):gaps|specific kind of fact" p)
          "Prompt asks the judge to name the missing fact-kind")
      (is (= p ontology/cq-runner-judge-prompt-template)
          "The public-interface re-export is the EXACT same string —
           consumers wiring a real LLM render the production prompt
           verbatim."))))

;; =============================================================================
;; AC7 — Full runner flow: events emitted via defcommand, projection populates
;; =============================================================================

(deftest evaluate-cqs-emits-events-and-populates-projection
  (testing "evaluate-cqs! reads the stored ORSD spec, evaluates each CQ,
            and dispatches one :ontology/record-cq-evaluation per CQ via
            cp/process-command. The projection accumulates the events.
            No bare es/append — every write flows through the command
            processor's schema gate."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (record-spec! ctx test-ontology-id
                    {:competency-questions (mapv :cq adversarial-cqs)})
      (Thread/sleep 100)
      (let [calls (atom [])
            result (ontology/evaluate-cqs!
                    {:ctx ctx
                     :ontology-id test-ontology-id
                     :judge-fn (counting-judge calls)})]
        (is (= 15 (count (:evaluated result))))
        ;; Layer-1 CQs in adversarial set: 4 (judge NOT called for those)
        (is (= 11 (count @calls))
            "Judge called for 11 non-Layer-1 CQs (4 Layer-1 skipped the LLM)")
        ;; Read-model surface
        (Thread/sleep 100)
        (let [history (ontology/get-cq-evaluations ctx test-ontology-id)]
          (is (= 15 (count history))
              "Projection holds all 15 evaluations"))
        (let [latest (ontology/get-cq-evaluation-latest ctx test-ontology-id)]
          (is (= 15 (count latest))
              "Latest-per-CQ also 15 (no duplicates yet)"))
        ;; Event-store double-check — belt + suspenders
        (let [events (->> (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:ontology/cq-evaluated}})
                          (into []))]
          (is (= 15 (count events))
              "Event store holds 15 :ontology/cq-evaluated events"))))))

;; =============================================================================
;; AC8 — Three-layer verdict coverage (every adversarial CQ's expected verdict)
;; =============================================================================

(deftest every-adversarial-cq-produces-expected-verdict
  (testing "End-to-end: each of the 15 adversarial CQs, when fed through
            the runner with the controlled judge applying the prototype-
            validated ground-truth mapping, produces the expected
            verdict + the expected :layer (verdict-driven, so Layer-3 is
            applied to :unknown verdicts) + the expected :judged-by?.
            This is the LOAD-BEARING test."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (record-spec! ctx test-ontology-id
                    {:competency-questions (mapv :cq adversarial-cqs)})
      (Thread/sleep 100)
      (let [calls (atom [])
            result (ontology/evaluate-cqs!
                    {:ctx ctx
                     :ontology-id test-ontology-id
                     :judge-fn (counting-judge calls)})
            verdicts (:evaluated result)]
        (doseq [v verdicts]
          (let [exp (expected-by-cq (:cq-text v))]
            (is (= (:verdict exp) (:verdict v))
                (str "Wrong verdict for: " (:cq-text v)))
            (is (= (:layer exp) (:layer v))
                (str "Wrong :layer for: " (:cq-text v)))
            (is (= (:judged? exp) (:judged-by? v))
                (str "Wrong :judged-by? for: " (:cq-text v)))))))))

;; =============================================================================
;; AC9 — Pass-rate / unknown-rate calculation correctness (separate metrics)
;; =============================================================================

(deftest graph-health-derives-pass-and-unknown-rates-separately
  (testing "graph-health derives pass-rate, unknown-rate, fail-rate from
            the latest verdict per CQ. ADVERSARIAL fixture: 3 pass + 2
            fail + 5 unknown → pass-rate 0.3, unknown-rate 0.5, fail-rate
            0.2 (all normalized over 10). Unknown-rate is a FIRST-CLASS
            metric — NOT folded into fail-rate."
    (with-test-ctx [ctx]
      ;; Spec with exactly 10 CQs in 3/2/5 ratio. We pick CQs from the
      ;; adversarial set so the controlled judge knows their verdicts.
      (let [cqs (vec (concat
                      ;; 3 pass
                      ["Which directors won an Oscar?"
                       "Are there any drama films in the graph?"
                       "Which directors have a director role?"]
                      ;; 2 fail
                      ["Which directors directed a horror film?"
                       "Which films are documentaries?"]
                      ;; 5 unknown
                      ["Did director Leo Bird retire?"
                       "Did director Mira Sun retire?"
                       "What is Leo Bird's birth year?"
                       "Which directors have collaborated with each other?"
                       "Has Mira Sun directed any feature film?"]))]
        (seed-adversarial-graph! ctx test-ontology-id)
        (record-spec! ctx test-ontology-id {:competency-questions cqs})
        (Thread/sleep 100)
        (let [calls (atom [])
              {:keys [graph-health]} (ontology/evaluate-cqs!
                                      {:ctx ctx
                                       :ontology-id test-ontology-id
                                       :judge-fn (counting-judge calls)})]
          (is (some? graph-health))
          (is (= 10 (:total-cqs graph-health)))
          (is (= 3 (:pass-count graph-health)))
          (is (= 2 (:fail-count graph-health)))
          (is (= 5 (:unknown-count graph-health)))
          (is (= 0.3 (:pass-rate graph-health))
              "Headline pass-rate = 3/10")
          (is (= 0.5 (:unknown-rate graph-health))
              "Unknown-rate = 5/10 — REPORTED AS ITS OWN METRIC, not
               folded into fail-rate")
          (is (= 0.2 (:fail-rate graph-health))
              "Fail-rate = 2/10 — ONLY closed-world :fail verdicts")
          (is (= 1.0 (+ (:pass-rate graph-health)
                        (:unknown-rate graph-health)
                        (:fail-rate graph-health)))
              "Three rates sum to 1.0 — they're disjoint outcomes")
          (is (some? (:last-evaluation-ts graph-health))
              "last-evaluation-ts surfaces the latest timestamp"))))))

(deftest unknown-improving-to-pass-moves-both-metrics
  (testing "ADVERSARIAL: a CQ moving from :unknown to :pass after
            facts are added produces BOTH an unknown-rate decrease AND
            a pass-rate increase. Catches a bug where unknown-rate
            silently collapses into fail-rate or pass-rate."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (record-spec! ctx test-ontology-id
                    {:competency-questions
                     ["Which directors won an Oscar?"      ;; pass
                      "Did director Leo Bird retire?"]})    ;; unknown
      (Thread/sleep 100)
      ;; Run 1
      (let [calls1 (atom [])
            _ (ontology/evaluate-cqs!
               {:ctx ctx :ontology-id test-ontology-id
                :judge-fn (counting-judge calls1)})
            _ (Thread/sleep 100)
            h1 (ontology/get-graph-health ctx test-ontology-id)]
        (is (= 0.5 (:pass-rate h1)))
        (is (= 0.5 (:unknown-rate h1)))
        ;; Now simulate "the grow cycle adds the missing fact" by
        ;; switching the judge to PASS for the formerly-unknown CQ.
        (let [pass-everything (fn [_] {:verdict :pass
                                       :reasoning "now answerable"
                                       :evidence-uris ["concept:dir/leo-bird"]
                                       :gaps []})
              _ (ontology/evaluate-cqs!
                 {:ctx ctx :ontology-id test-ontology-id
                  :judge-fn (fn [{:keys [question] :as args}]
                              (if (re-find #"(?i)retire" question)
                                (pass-everything args)
                                ((counting-judge (atom [])) args)))})
              _ (Thread/sleep 100)
              h2 (ontology/get-graph-health ctx test-ontology-id)]
          (is (= 1.0 (:pass-rate h2))
              "Pass-rate climbs as :unknown → :pass")
          (is (= 0.0 (:unknown-rate h2))
              "Unknown-rate drops to zero — the metric moved
               independently of fail-rate")
          (is (= 0.0 (:fail-rate h2))))))))

;; =============================================================================
;; AC10 — Scoping: granted ontology-id is the authoritative scope
;; =============================================================================

(deftest scope-respects-ontology-id
  (testing "The runner evaluates against the spec's ontology-id ONLY.
            A graph seeded under section-B is NOT visible from a
            section-A scoped runner — Layer-1 returns :fail, Layer-2/3
            evidence does not include section-B URIs."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      ;; Section B has its own concept: wombat (visible only under
      ;; alt-ontology-id)
      (create-concept! ctx alt-ontology-id "concept:animal/wombat" "Wombat")
      (Thread/sleep 100)
      ;; Wombat in section A — should be :fail.
      (let [v (cqr/evaluate-cq {:cq-text "Is there a Wombat concept?"
                                :ontology-id test-ontology-id
                                :ctx ctx
                                :judge-fn (raise-on-call-judge)
                                :hybrid-search-fn (fn [_ _] {:results []})
                                :get-concepts-fn (fn [opts]
                                                   (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= :fail (:verdict v))
            "Wombat exists in alt-ontology-id but is INVISIBLE to a
             test-ontology-id scoped Layer-1 check"))
      ;; Wombat in section B — should be :pass.
      (let [v (cqr/evaluate-cq {:cq-text "Is there a Wombat concept?"
                                :ontology-id alt-ontology-id
                                :ctx ctx
                                :judge-fn (raise-on-call-judge)
                                :hybrid-search-fn (fn [_ _] {:results []})
                                :get-concepts-fn (fn [opts]
                                                   (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                                :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships})]
        (is (= :pass (:verdict v))
            "Wombat visible under its own ontology-id grant")))))

;; =============================================================================
;; AC11 — Re-evaluation determinism for Layer 1
;; =============================================================================

(deftest layer-1-verdict-stable-across-runs
  (testing "Layer-1 verdicts are DETERMINISTIC across runs. A Layer-1
            verdict flipping between runs is a runner bug (e.g., a
            non-deterministic projection read). Adversarial: run the
            same Layer-1 CQ THREE times against an unchanged graph —
            all three verdicts must be identical."
    (with-test-ctx [ctx]
      (seed-adversarial-graph! ctx test-ontology-id)
      (let [opts {:cq-text "Is there a Director concept?"
                  :ontology-id test-ontology-id
                  :ctx ctx
                  :judge-fn (raise-on-call-judge)
                  :hybrid-search-fn (fn [_ _] {:results []})
                  :get-concepts-fn (fn [opts]
                                     (ai.obney.orc.ontology.core.read-models/get-concepts ctx opts))
                  :get-relationships-fn ai.obney.orc.ontology.core.read-models/get-relationships}
            verdicts (repeatedly 3 #(cqr/evaluate-cq opts))]
        (is (= [:pass :pass :pass] (mapv :verdict verdicts))
            "Layer-1 verdict is stable across runs")
        (is (apply = (mapv :evidence-uris verdicts))
            "Even :evidence-uris are stable for Layer-1 — pure
             projection read")))))

;; =============================================================================
;; AC12 — No spec / no CQs paths
;; =============================================================================

(deftest no-spec-returns-no-spec-reason
  (testing "evaluate-cqs! on an ontology-id with no recorded spec
            returns :reason :no-spec — does NOT mint events."
    (with-test-ctx [ctx]
      (let [{:keys [evaluated graph-health reason]}
            (ontology/evaluate-cqs!
             {:ctx ctx :ontology-id test-ontology-id
              :judge-fn (raise-on-call-judge)})]
        (is (= [] evaluated))
        (is (nil? graph-health))
        (is (= :no-spec reason))))))

(deftest spec-without-cqs-returns-no-cqs-reason
  (testing "A spec with no :competency-questions field returns
            :reason :no-cqs-in-spec — does NOT mint events."
    (with-test-ctx [ctx]
      (record-spec! ctx test-ontology-id
                    {:purpose "test" :scope "test"})
      (Thread/sleep 100)
      (let [{:keys [evaluated graph-health reason]}
            (ontology/evaluate-cqs!
             {:ctx ctx :ontology-id test-ontology-id
              :judge-fn (raise-on-call-judge)})]
        (is (= [] evaluated))
        (is (nil? graph-health))
        (is (= :no-cqs-in-spec reason))))))

;; =============================================================================
;; AC13 — Command schema gate: bad command shape produces ::anom/incorrect
;; =============================================================================

(deftest record-cq-evaluation-command-shape-gated
  (testing "The :ontology/record-cq-evaluation command has a Malli
            schema in interface/schemas.clj. A command carrying a
            :verdict value outside :pass / :fail / :unknown is REJECTED
            with ::anom/incorrect at cp/process-command time. No event
            lands; no projection state mutates."
    (with-test-ctx [ctx]
      (let [bad-result (cp/process-command
                        (assoc ctx :command
                               {:command/name :ontology/record-cq-evaluation
                                :command/id (random-uuid)
                                :command/timestamp (time/now)
                                :ontology-id test-ontology-id
                                :cq-index 0
                                :cq-text "test"
                                :verdict :garbage  ;; <-- not in enum
                                :reasoning ""
                                :evidence-uris []
                                :judged-by? false
                                :layer :layer-1-structural}))]
        (is (= ::anom/incorrect (::anom/category bad-result))
            "Schema rejection surfaces as ::anom/incorrect — the
             standard Grain command-processor schema-failure category")
        (is (nil? (:command-result/events bad-result)))
        (let [events (->> (es/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:ontology/cq-evaluated}})
                          (into []))]
          (is (zero? (count events))
              "Event store carries zero CQ-evaluated events"))))))
