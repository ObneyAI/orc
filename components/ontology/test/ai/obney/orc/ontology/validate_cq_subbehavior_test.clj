(ns ai.obney.orc.ontology.validate-cq-subbehavior-test
  "EB8 — the VALIDATE+CQ subbehavior as a delegatable ORC sheet.

   These durable, HERMETIC tests lock the load-bearing STRUCTURE + CONTRACT +
   REUSE + the DETERMINISTIC persist→read-back + consumer-override logic, through
   the subbehavior's PUBLIC surface (its registry name, its `:reads`/`:writes`
   contract + blackboard schema, its persisted node config) PLUS the persist path
   over a REAL Grain in-memory store (the `record-ontology-spec` command + the
   `get-ontology-spec` read-back is local + non-blocking, so the persist-and-
   read-back assertion belongs on the fast brick gate) AND the gate path over a
   REAL store with a CONTROLLED judge-fn (the S15 runner's ROUTE/EMIT/DERIVE
   mechanics are deterministic; the verdict QUALITY is decided outside the runner).

   They carry NO real-LLM CQ derivation and NO real retrieve-then-judge LLM —
   those are multi-second/integration and drive the OpenRouter API (+ ColBERT/
   embeddings), so they live on the on-demand `:dev:test` integration lane
   (`development/ontology-integration/.../eb8_validate_cq_test.clj`) + the live
   verify (`development/src/eb8_validate_cq_subbehavior_live_verify.clj`,
   `docs/build-timeline/live-verify/EB8-validate-cq.md`), per the gate-hygiene rule
   (a test driving the real LLM / real ColBERT is an INTEGRATION test). The DERIVE
   `:llm` node's prompt-content + reuse + #13-ordering is asserted here from the
   pure prompt body; the derivation SESSION's real output quality is the live
   verify's job.

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration (the
       EB1-EB7 pattern). The Validate+CQ sheet is SOURCE-AGNOSTIC.
     - Node design: `:llm` DERIVE → `:code` PERSIST → `:code` GATE. The DERIVE node
       writes `:reasoning` FIRST (#13) and re-houses DT5's `cq-node-prompt` (#8).
     - The public contract: [:ontology-id :goal :profile (:consumer-cqs :judge-fn)]
       in, [:competency-questions :cq-verdict :graph-health] out; the verdict/
       graph-health writes declare STRUCTURED schemas, the CQ writes CONCRETE
       `[:vector :string]` (the `:llm`-node C1 per-field-type fix).
     - PERSIST round-trips through the REAL command → event → projection path: the
       CQs persist as the ORSD spec `get-ontology-spec` reads (discipline 7 — read
       the projection BACK; no bare append).
     - CONSUMER OVERRIDE: a supplied CQ set is persisted instead of the derived set.
     - Honest empty (#4/#5): no CQs → nothing persisted, an honest :failed.
     - GATE: the S15 retrieve-then-judge runner over a real store with a controlled
       judge — the per-CQ verdict (incl. first-class :unknown) is read back from the
       projection (discipline 7) + graph-health derived; SEMANTIC, not lints (#7)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.validate-cq-subbehavior :as vcq]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Real-Grain harness for the persist-and-read-back + gate assertions.
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb8-brick-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

;; ---------------------------------------------------------------------------
;; CAPTURED REAL DT2 profiles (VERBATIM — docs/build-timeline/live-verify/DT2-profile.md,
;; mirrored from dt5_requirements_cq_node_test) + a neutral goal.
;; ---------------------------------------------------------------------------

(def captured-csv-profile
  {:entity-candidates
   "Academic Programs (CIP), Occupational Titles (SOC), Professional Occupations, Crosswalk/Alignment mappings."
   :identifying-keys "'CIPCode' (or 'CIP2020Code'), 'SOCCode' (or 'SOC2018Code')"
   :scope-fields "'CIPTitle', 'SOCTitle'"
   :linking-keys "'CIPCode', 'CIP2020Code', 'SOCCode', 'SOC2018Code'"
   :grain-signals "A many-to-many mapping; one program leads to many occupations."
   :sample [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
             "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}]})

(def captured-sql-profile
  {:entity-candidates ["Higher Education Institutions" "States"]
   :identifying-keys {"Higher Education Institutions" ["UNITID" "OPEID"]}
   :scope-fields ["STABBR" "SECTOR"]
   :linking-keys ["UNITID" "FIPS" "STABBR"]
   :grain-signals ["UNITID repeated across years"]
   :sample [{:UNITID 100654 :INSTNM "Alabama A & M University" :STABBR "AL"}]})

(def the-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for.")

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Validate+CQ subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (vcq/register-validate-cq-subbehavior! ctx {})
            looked-up (vcq/validate-cq-sheet-id-for)
            id-2 (vcq/register-validate-cq-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (orm/get-sheet-by-name ctx (vcq/validate-cq-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest validate-cq-subbehavior-is-source-agnostic-test
  (testing "ONE Validate+CQ sheet serves every source + graph (it derives + gates
            the :ontology-id it is handed; bakes in no source path) — like EB3-EB7"
    (is (= "ontology-validate-cq/validate-cq@v1" (vcq/validate-cq-subbehavior-name))
        "the Validate+CQ registry name carries no source/medium/path tag")))

;; ---------------------------------------------------------------------------
;; Node design: :llm DERIVE → :code PERSIST → :code GATE; #13 reasoning FIRST.
;; ---------------------------------------------------------------------------

(deftest body-is-llm-derive-then-two-code-nodes-test
  (testing "the Validate+CQ body is :llm DERIVE → :code PERSIST → :code GATE (one
            :llm reasoning node, two deterministic :code nodes), no :rlm config"
    (h/with-async-test-context [ctx]
      (let [sid (vcq/register-validate-cq-subbehavior! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            llm-leaves (filter #(= :ai (:executor %)) leaves)
            code-leaves (filter #(= :code (:executor %)) leaves)]
        (is (= 1 (count llm-leaves))
            "exactly one :ai (:llm) leaf (the DERIVE node)")
        (is (= 2 (count code-leaves))
            "exactly two :code leaves (PERSIST + GATE — deterministic)")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node — derivation is single-turn :llm reasoning")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — Validate+CQ is a derive→persist→gate pipeline")))))

(deftest derive-node-writes-reasoning-first-and-reuses-dt5-prompt-test
  (testing "the DERIVE :llm node writes :reasoning FIRST (#13) and re-houses DT5's
            cq-node-prompt (#8 — no fork)"
    (h/with-async-test-context [ctx]
      (let [sid (vcq/register-validate-cq-subbehavior! ctx {})
            derive (first (filter #(= :ai (:executor %)) (vals (orm/get-nodes-by-id ctx sid))))]
        (is (= :reasoning (first (:writes derive)))
            "#13 — :reasoning is written FIRST in the DERIVE node's :writes")
        (is (= [:reasoning :competency-questions :rationale] (vec (:writes derive)))
            "the DERIVE node writes :reasoning, :competency-questions, :rationale")
        (is (= [:goal :profile] (vec (:reads derive)))
            "the DERIVE node reads the goal + the profile(s) it grounds in"))))
  (testing "the DERIVE prompt re-houses the DT5 CQ-derivation body VERBATIM through
            the promotion seam (the GROUNDED + GOAL-ANCHORED reasoning) and frames
            it for an :llm node (no tool session)"
    (let [p (str/lower-case (vcq/derive-prompt))]
      ;; it IS the DT5 CQ-derivation body (re-housed).
      (is (str/includes? p "competency question"))
      (is (str/includes? p "requirements step"))
      (is (str/includes? p "grounded"))
      (is (str/includes? p "goal-anchored"))
      ;; it carries the DT5 body verbatim (the promotion-seam output): assert a
      ;; distinctive goal-independent chunk of cq-node-prompt is present unchanged
      ;; (the goal slot differs — it carries the runtime sentinel here — so the
      ;; whole rendered body is not a substring, but the goal-free reasoning is).
      (is (str/includes? p (str/lower-case
                            "DERIVE THE COMPETENCY QUESTIONS (this is the whole job"))
          "the DT5 derivation reasoning is re-housed verbatim (no fork)")
      (is (str/includes? p (str/lower-case
                            "GROUNDED — every question must be answerable"))
          "the DT5 GROUNDED property is re-housed verbatim")
      ;; the :llm-node framing tells the model there is no tool session.
      (is (str/includes? p "you do not call any tools"))
      (is (str/includes? p "reasoning"))
      ;; #13: reasoning-first output framing names :reasoning before the questions.
      (is (< (.indexOf p "reasoning") (.indexOf p "competency-questions"))
          "the output framing names :reasoning before :competency-questions (#13)"))))

(deftest derive-prompt-is-domain-agnostic-test
  (testing "the DERIVE prompt carries NO industry/vertical knowledge (discipline 12)
            — the only domain reference is the runtime goal input"
    (let [p (str/lower-case (vcq/derive-prompt))]
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings"]]
        (is (not (str/includes? p term))
            (str "the DERIVE prompt body must not bake in the term: " term))))))

;; ---------------------------------------------------------------------------
;; The public contract + node :reads/:writes + structured/concrete schemas.
;; ---------------------------------------------------------------------------

(deftest persist-and-gate-node-contracts-test
  (testing "the PERSIST node reads [:ontology-id :goal :competency-questions
            :consumer-cqs] + writes [:competency-questions :persist-result]; the
            GATE node reads [:ontology-id :judge-fn] + writes [:cq-verdict :graph-health]"
    (h/with-async-test-context [ctx]
      (let [sid (vcq/register-validate-cq-subbehavior! ctx {})
            code-nodes (filter #(= :code (:executor %)) (vals (orm/get-nodes-by-id ctx sid)))
            by-fn (into {} (map (juxt :fn identity) code-nodes))
            persist (get by-fn "ai.obney.orc.ontology.core.validate-cq-subbehavior/persist-code")
            gate (get by-fn "ai.obney.orc.ontology.core.validate-cq-subbehavior/gate-code")]
        (is (some? persist) "the PERSIST node's :fn is persist-code")
        (is (some? gate) "the GATE node's :fn is gate-code")
        (is (= [:ontology-id :goal :competency-questions :consumer-cqs] (vec (:reads persist))))
        (is (= [:competency-questions :persist-result] (vec (:writes persist))))
        (is (= [:ontology-id :judge-fn] (vec (:reads gate))))
        (is (= [:cq-verdict :graph-health] (vec (:writes gate))))))))

(deftest verdict-and-health-writes-are-structured-not-bare-map-test
  (testing "the :cq-verdict + :graph-health writes declare STRUCTURED schemas; the
            CQ writes are CONCRETE [:vector :string] (the :llm-node C1 per-field fix)"
    (is (= :vector (first vcq/cq-verdict-schema))
        ":cq-verdict is a structured [:vector [:map …]]")
    (is (m/validate vcq/cq-verdict-schema
                    [{:cq-index 0 :cq-text "Is there a Director?" :verdict :pass}])
        "a real per-CQ verdict validates the structured verdict schema")
    (is (m/validate vcq/graph-health-schema
                    {:total-cqs 3 :pass-count 1 :unknown-count 1 :fail-count 1
                     :unknown-rate 0.33})
        "a real graph-health metric validates the structured schema")
    (is (m/validate vcq/graph-health-schema nil)
        "graph-health tolerates the honest-nil case (:maybe)")
    (is (= [:vector :string] vcq/competency-questions-schema)
        "the CQ write is a CONCRETE [:vector :string] (DSCloj-parseable, ORSD-shaped)")
    (is (m/validate vcq/competency-questions-schema ["Q1?" "Q2?"])
        "a vector of question strings validates")
    (is (not (m/validate vcq/competency-questions-schema "a json string"))
        "a STRING does not validate the concrete vector schema")))

;; ---------------------------------------------------------------------------
;; REUSE not fork: the persist + gate compose the proven machinery.
;; ---------------------------------------------------------------------------

(deftest validate-cq-reuses-proven-machinery-not-fork-test
  (testing "EB8 REUSES (does not fork) the proven fns — proven by their var being
            the real one (Discipline #8)"
    (is (var? #'dt/record-competency-questions!)
        "PERSIST reuses discovery-tree/record-competency-questions! (the record-ontology-spec command path)")
    (is (var? #'dt/cq-node-prompt)
        "DERIVE re-houses discovery-tree/cq-node-prompt (the DT5 CQ-derivation body)")
    (is (var? #'ontology/evaluate-cqs!)
        "GATE reuses ontology/evaluate-cqs! (the S15 three-layer retrieve-then-judge runner)")
    (is (var? #'ontology/get-ontology-spec)
        "the persist read-back reuses get-ontology-spec (discipline 7)")
    (is (var? #'ontology/get-cq-evaluation-latest)
        "the gate read-back reuses get-cq-evaluation-latest (discipline 7)"))
  (testing "the boundary is the orc-service INTERFACE (not core.dsl) — the
            poly-gate boundary-correctness rule"
    (h/with-async-test-context [ctx]
      (is (some? (vcq/register-validate-cq-subbehavior! ctx {}))
          "the sheet builds via the orc-service interface DSL boundary"))))

;; ---------------------------------------------------------------------------
;; profile normalization: one profile map OR a vector of profile maps.
;; ---------------------------------------------------------------------------

(deftest normalize-profiles-tolerates-map-or-vector-test
  (testing "normalize-profiles coerces a single profile map OR a vector of profile
            maps to a vector; drops nils/empties; honest [] for nothing usable"
    (is (= [captured-csv-profile] (vcq/normalize-profiles captured-csv-profile))
        "a single profile map → a 1-vector")
    (is (= [captured-csv-profile captured-sql-profile]
           (vcq/normalize-profiles [captured-csv-profile captured-sql-profile]))
        "a vector of profile maps → the vector")
    (is (= [captured-csv-profile]
           (vcq/normalize-profiles [captured-csv-profile nil {}]))
        "nils + empty maps dropped")
    (is (= [] (vcq/normalize-profiles nil)) "nil → [] (honest empty)")
    (is (= [] (vcq/normalize-profiles "")) "a non-map/non-seq → []")))

;; ---------------------------------------------------------------------------
;; PERSIST: the derived CQs round-trip to the ORSD spec get-ontology-spec reads.
;; Asserted by reading the projection back (Discipline #7) — over a REAL store.
;; ---------------------------------------------------------------------------

(deftest persist-derived-cqs-round-trip-to-the-orsd-spec-test
  (testing "given derived CQs, persist-cqs! records them as the S14 ORSD spec the
            S15 gate reads — read back via ontology/get-ontology-spec (#7, no bare
            append)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            derived ["Which occupations does a given field of study prepare people for?"
                     "Which fields of study lead to a given occupation?"
                     "What is the title of each program and occupation?"]
            result (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                          :derived-cqs derived})]
        (is (= :ok (:status result)))
        (is (= :derived (:origin result)) "origin is :derived (no consumer CQs)")
        (is (= derived (:competency-questions result))
            "the derived CQs are surfaced verbatim (HITL review)")
        (is (true? (:spec-recorded? result)) "the read-back confirmed the CQs landed")
        ;; THE LOAD-BEARING ASSERTION: read the projection back, not the return value.
        (Thread/sleep 100)
        (let [spec (ontology/get-ontology-spec ctx oid)]
          (is (= derived (:competency-questions spec))
              "the derived CQs ARE the ORSD spec's :competency-questions (S15 judges these)")
          (is (= the-goal (:purpose spec))
              "the goal is stamped as the spec :purpose when the spec had none"))))))

;; ---------------------------------------------------------------------------
;; CONSUMER OVERRIDE: a supplied CQ set is persisted instead of the derived set.
;; ---------------------------------------------------------------------------

(deftest consumer-cqs-override-the-derived-set-test
  (testing "when the consumer supplies CQs, they OVERRIDE the derived set — the
            supplied questions are persisted as the ORSD spec (HITL override)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            derived ["derived one?" "derived two?"]
            supplied ["My own CQ one?" "My own CQ two?" "My own CQ three?"]
            result (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                          :derived-cqs derived
                                          :consumer-cqs supplied})]
        (is (= :ok (:status result)))
        (is (= :supplied (:origin result)) "the supplied set overrides → :supplied")
        (is (= supplied (:competency-questions result))
            "the SUPPLIED questions are surfaced (NOT the derived ones)")
        (Thread/sleep 100)
        (is (= supplied (:competency-questions (ontology/get-ontology-spec ctx oid)))
            "the SUPPLIED CQs persisted as the ORSD spec build! reads (override held)")))))

;; ---------------------------------------------------------------------------
;; HONEST EMPTY: no CQs → nothing persisted, an honest :failed (no false green).
;; ---------------------------------------------------------------------------

(deftest empty-derivation-persists-nothing-and-fails-honestly-test
  (testing "no consumer set AND an empty derived set → NOTHING persisted, :failed —
            the exit gate must never have nothing to judge (Discipline #4/#5)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            result (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                          :derived-cqs []})]
        (is (= :failed (:status result)))
        (is (str/includes? (:error result) "NO competency questions"))
        (Thread/sleep 100)
        (is (nil? (ontology/get-ontology-spec ctx oid))
            "no spec recorded on an empty derivation (no false green)"))))
  (testing "persist-cqs! fails loudly without a granted scope (#5)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (vcq/persist-cqs! ctx {:ontology-id nil :derived-cqs ["q?"]}))))))

;; ---------------------------------------------------------------------------
;; GATE: the S15 retrieve-then-judge runner over a real store + controlled judge.
;; The per-CQ verdict (incl. first-class :unknown) is read back (#7) + health
;; derived. SEMANTIC validation (CQ/retrieve-grounded), NOT lints (#7/#12).
;; ---------------------------------------------------------------------------

(defn- seed-graph! [ctx oid]
  ;; A small real graph: a Director ROLE concept (so the Layer-1 "Is there a
  ;; Director concept?" existence CQ resolves :pass against a real concept whose
  ;; label IS "Director" — mirrors the S15 live verify's concept:role/director),
  ;; a director instance, and a won-Oscar edge (positive evidence) — enough for
  ;; the three-way verdict to be meaningful.
  (doseq [[uri label] [["concept:role/director" "Director"]
                       ["concept:dir/jane" "Jane Roe"]
                       ["concept:award/oscar" "Academy Award"]]]
    (cp/process-command
     (assoc ctx :command
            {:command/name :ontology/create-concept
             :command/id (random-uuid) :command/timestamp (time/now)
             :ontology-id oid :uri uri :label label
             :description (str label " concept")
             :scope :custom :broader [] :indicators []})))
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/create-relationship
           :command/id (random-uuid) :command/timestamp (time/now)
           :ontology-id oid :source-uri "concept:dir/jane"
           :predicate "won" :target-uri "concept:award/oscar"
           :confidence-class :extracted :properties {}}))
  (Thread/sleep 150))

(deftest gate-runs-the-s15-retrieve-then-judge-runner-and-reads-back-test
  (testing "run-gate! runs the S15 CQ runner over the persisted spec → per-CQ
            verdict + graph-health, READ BACK from the projection (#7); the
            three-way distinction (incl. first-class :unknown) holds; SEMANTIC"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (seed-graph! ctx oid)
            ;; persist a spec with a Layer-1 existence CQ (deterministic :pass),
            ;; a positive-evidence CQ (:pass via the judge), and a gap CQ (:unknown).
            _ (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                     :derived-cqs ["Is there a Director concept?"
                                                   "Which directors won an Oscar?"
                                                   "What is Jane Roe's birth year?"]})
            _ (Thread/sleep 100)
            ;; CONTROLLED judge — decides verdict quality OUTSIDE the runner (the
            ;; runner's job is ROUTE/EMIT/DERIVE). Positive-evidence → :pass;
            ;; a birth-year gap → first-class :unknown.
            judge (fn [{:keys [question]}]
                    (cond
                      (str/includes? (str/lower-case question) "won an oscar")
                      {:verdict :pass :reasoning "won-oscar edge present"
                       :evidence-uris ["concept:dir/jane"] :gaps []}
                      :else
                      {:verdict :unknown :reasoning "no birth-year facts in graph"
                       :evidence-uris [] :gaps ["birth-year"]}))
            result (vcq/run-gate! ctx {:ontology-id oid :judge-fn judge})
            verdicts (:cq-verdict result)
            by-text (into {} (map (juxt :cq-text :verdict) verdicts))]
        (is (= 3 (count verdicts)) "one latest verdict per CQ")
        ;; read back from the projection independently (discipline 7).
        (is (= (count (ontology/get-cq-evaluation-latest ctx oid)) (count verdicts))
            "the verdicts were read back from the projection (#7), not the return value")
        (is (= :pass (by-text "Is there a Director concept?"))
            "Layer-1 structural existence → :pass (deterministic, zero-LLM)")
        (is (= :pass (by-text "Which directors won an Oscar?"))
            "positive evidence → :pass (semantic, judge over retrieved evidence)")
        (is (= :unknown (by-text "What is Jane Roe's birth year?"))
            "a genuine knowledge gap → first-class :unknown (NOT silently dropped)")
        ;; graph-health surfaces unknown-rate as a first-class metric.
        (let [h (:graph-health result)]
          (is (= 3 (:total-cqs h)))
          (is (= 1 (:unknown-count h)) "the :unknown verdict is counted, not folded into fail")
          (is (pos? (:unknown-rate h)) "unknown-rate is a first-class metric"))))))

(deftest gate-runs-layer-1-only-spec-without-a-judge-test
  (testing "a spec of ONLY Layer-1 (structural existence) CQs is gated WITHOUT a
            judge-fn — deterministic zero-LLM; no NPE, real verdicts read back (#7)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (seed-graph! ctx oid)
            _ (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                     :derived-cqs ["Is there a Director concept?"
                                                   "Is there a Wombat concept?"]})
            _ (Thread/sleep 100)
            result (vcq/run-gate! ctx {:ontology-id oid})
            by-text (into {} (map (juxt :cq-text :verdict) (:cq-verdict result)))]
        (is (= 2 (count (:cq-verdict result))) "both Layer-1 CQs were evaluated (no judge needed)")
        (is (= :pass (by-text "Is there a Director concept?")) "Layer-1 existence → :pass")
        (is (= :fail (by-text "Is there a Wombat concept?")) "Layer-1 non-existence → :fail")))))

(deftest gate-surfaces-no-judge-boundary-honestly-test
  (testing "a spec with a NON-Layer-1 (semantic) CQ but NO judge-fn does NOT crash
            (the judge fn-value can't cross :delegate) — it surfaces the no-judge
            boundary honestly (:no-judge-non-layer1), no fabricated verdict (#4/#5)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (seed-graph! ctx oid)
            _ (vcq/persist-cqs! ctx {:ontology-id oid :goal the-goal
                                     :derived-cqs ["Which directors won an Oscar?"]})
            _ (Thread/sleep 100)
            result (vcq/run-gate! ctx {:ontology-id oid})] ;; NO judge-fn
        (is (= :no-judge-non-layer1 (:run-reason result))
            "the no-judge boundary is surfaced honestly")
        (is (empty? (:cq-verdict result))
            "no verdict was fabricated for the semantic CQ (no false green)")
        (is (empty? (ontology/get-cq-evaluation-latest ctx oid))
            "nothing was recorded for the un-judgeable CQ")))))

(deftest gate-requires-ontology-id-test
  (testing "run-gate! fails loudly without a granted scope (#5)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (vcq/run-gate! ctx {:ontology-id nil}))))))
