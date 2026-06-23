(ns ai.obney.orc.ontology.survey-subbehavior-test
  "EB2 — the SURVEY subbehavior as a delegatable ORC sheet.

   These durable tests lock the load-bearing DECISIONS the EB2 live verify
   validated, through the subbehavior's PUBLIC surface (its registry name, its
   `:reads`/`:writes` contract + blackboard schema, its persisted node config),
   never internal node text beyond the domain-agnostic / anti-emit-tree
   guarantees the prompt MUST carry. They are hermetic (no LLM): the real LLM
   reasoning quality is proven by the live verify
   (`development/src/eb2_survey_subbehavior_live_verify.clj`,
   `docs/build-timeline/live-verify/EB2-survey.md`).

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration
       (the EB1 pattern, first real subbehavior on it).
     - C1 (EB1 carry-forward): the `:profile` write declares a STRUCTURED
       `[:map …]` Malli schema (NEVER a bare `:map`), and that schema validates a
       real profile contract map — so the contract crosses `:delegate` parsed.
     - TERMINAL mode: the Survey node persists `:rlm {:recursive? false}` (the
       explicit opt-out of the recursive default — the live run proved this is
       load-bearing for terminal behavior).
     - V06 re-orchestration: `:granted-source` is baked per-medium (csv/sql), so
       the right specialist tools are bound — not a fork.
     - Contract keys: the DT2 frozen six PLUS the embed-worthy-field signal.
     - Domain-agnostic + medium-specialized + anti-emit-tree prompt."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.ontology.core.survey-subbehavior :as survey]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [clojure.string :as str]
            [malli.core :as m]))

(def csv-source {:name :crosswalk :type :csv :path "/tmp/eb2-fixture.csv"})
(def sql-source {:name :ipeds :type :sql :path "/tmp/eb2-fixture.db"})

;; A real-shaped profile contract map (the shape the live verify produced).
(def sample-profile
  {:entity-candidates ["Field of Study" "Occupation"]
   :identifying-keys {"Field of Study" ["CIP_Code"] "Occupation" ["SOC_Code"]}
   :scope-fields []
   :linking-keys ["CIP_Code" "SOC_Code"]
   :grain-signals ["each row is a CIP↔SOC crosswalk pair, not a unique entity"]
   :sample [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
             "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}]
   :embed-worthy-fields ["CIP_Title" "SOC_Title"]})

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent (EB1 pattern).
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Survey subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (survey/register-survey-subbehavior! ctx {:source csv-source})
            looked-up (survey/survey-sheet-id-for csv-source)
            id-2 (survey/register-survey-subbehavior! ctx {:source csv-source})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (rm/get-sheet-by-name ctx (survey/survey-subbehavior-name csv-source)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest per-medium-sources-get-distinct-sheet-ids-test
  (testing "csv and sql sources resolve to DISTINCT sheets (per-medium specialization)"
    (let [csv-id (survey/survey-sheet-id-for csv-source)
          sql-id (survey/survey-sheet-id-for sql-source)
          csv-id-b (survey/survey-sheet-id-for {:type :csv :path "/tmp/OTHER.csv"})]
      (is (not= csv-id sql-id)
          "different media must not collide on one sheet-id")
      (is (not= csv-id csv-id-b)
          "different source paths (baked into :granted-source) must not collide"))))

;; ---------------------------------------------------------------------------
;; C1 — the :profile write is a STRUCTURED schema, not a bare :map.
;; ---------------------------------------------------------------------------

(deftest profile-write-schema-is-structured-not-bare-map-test
  (testing "C1: the :profile blackboard schema is a STRUCTURED [:map …], never a bare :map"
    (h/with-async-test-context [ctx]
      (let [sid (survey/register-survey-subbehavior! ctx {:source csv-source})
            bb (rm/get-blackboard-by-key ctx sid)
            profile-schema (get-in bb [:profile :schema])]
        ;; The standalone schema value is a structured [:map …] vector form.
        (is (vector? survey/profile-contract-schema)
            "the profile contract schema must be a vector (structured) form")
        (is (= :map (first survey/profile-contract-schema))
            "the profile contract schema must be a [:map …] schema")
        (is (> (count survey/profile-contract-schema) 2)
            "a STRUCTURED [:map …] has field entries — a bare :map (the C1 failure) would not")
        (is (not= :map profile-schema)
            "the persisted :profile blackboard schema must NOT be the bare keyword :map (C1)")))))

(deftest profile-schema-validates-a-real-contract-map-test
  (testing "the structured profile schema accepts a real, model-variable profile map"
    (is (m/validate survey/profile-contract-schema sample-profile)
        "a real profile contract map (mixed value shapes) validates")
    (is (m/validate survey/profile-contract-schema
                    (assoc sample-profile :grain-signals "a prose string instead of a vector"))
        "field value-shapes are tolerated (the DT3/DT5 model-variance carry-forward)")
    (is (m/validate survey/profile-contract-schema {})
        "an (empty) map still validates the SHAPE — keys are optional; the live verify gates on content")
    (is (not (m/validate survey/profile-contract-schema "a json string"))
        "a STRING (the C1 failure mode) does NOT validate the structured map schema")))

;; ---------------------------------------------------------------------------
;; Contract keys: DT2 frozen six + the embed-worthy-field signal.
;; ---------------------------------------------------------------------------

(deftest contract-keys-are-dt2-frozen-plus-embed-signal-test
  (testing "the Survey profile contract = the DT2 frozen keys + the EB2 embed-field signal"
    (is (= (set dt/profile-contract-keys)
           (set (remove #{survey/embed-field-signal-key} survey/profile-contract-keys)))
        "the DT2-frozen profile keys are re-used verbatim (no drift)")
    (is (contains? (set survey/profile-contract-keys) survey/embed-field-signal-key)
        "the embed-worthy-field signal (EB7/P2 input) is part of the contract")
    (is (= :embed-worthy-fields survey/embed-field-signal-key)
        "the embed signal key is :embed-worthy-fields")))

;; ---------------------------------------------------------------------------
;; TERMINAL mode + V06 granted-source baked per-medium.
;; ---------------------------------------------------------------------------

(deftest survey-node-is-terminal-repl-researcher-test
  (testing "the Survey body is a :repl-researcher persisted in TERMINAL mode (:recursive? false)"
    (h/with-async-test-context [ctx]
      (let [sid (survey/register-survey-subbehavior! ctx {:source csv-source})
            nodes (rm/get-nodes-by-id ctx sid)
            rr (first (filter #(= :repl-researcher (:type %)) (vals nodes)))]
        (is (some? rr) "the Survey sheet has a :repl-researcher node")
        (is (= false (get-in rr [:rlm :recursive?]))
            "terminal mode is the EXPLICIT opt-out :recursive? false (the executor defaults
             a repl-researcher to recursive — this is load-bearing)")))))

(deftest granted-source-is-baked-per-medium-test
  (testing "V06 re-orchestration: :granted-source is baked with the medium's format + path"
    (h/with-async-test-context [ctx]
      (let [csv-sid (survey/register-survey-subbehavior! ctx {:source csv-source})
            sql-sid (survey/register-survey-subbehavior! ctx {:source sql-source})
            grant (fn [sid] (-> (rm/get-nodes-by-id ctx sid) vals
                                (->> (filter #(= :repl-researcher (:type %))) first)
                                (get-in [:rlm :granted-source])))]
        (is (= {:format :csv :path (:path csv-source)} (grant csv-sid))
            "csv source binds the csv specialist tools by format + path")
        (is (= {:format :sql :path (:path sql-source)} (grant sql-sid))
            "sql source binds the sql specialist tools by format + path")))))

(deftest survey-contract-is-goal-and-descriptor-in-profile-out-test
  (testing "the public contract: [:goal :source-descriptor] in, [:profile] out"
    (h/with-async-test-context [ctx]
      (let [sid (survey/register-survey-subbehavior! ctx {:source csv-source})
            nodes (rm/get-nodes-by-id ctx sid)
            rr (first (filter #(= :repl-researcher (:type %)) (vals nodes)))]
        (is (= [:goal :source-descriptor] (vec (:reads rr)))
            "the node reads the goal + the source descriptor")
        (is (= [:profile] (vec (:writes rr)))
            "the node's sole declared write is the :profile contract")))))

;; =============================================================================
;; MC-0 fix #5 — the survey :repl-researcher carries enough :max-iterations
;; =============================================================================
;; REGRESSION: 8 iterations was too tight for a DIRECTORY-of-workbooks source
;; (e.g. O*NET's ~50 sheets): the survey hit "max iterations reached without
;; final!" intermittently (:failed-at-survey). The fix raises :max-iterations to
;; give the directory case headroom (simple sources still finalize early). This
;; asserts the directory headroom STRUCTURALLY on the persisted node — pre-fix
;; (8) → RED.

(deftest survey-node-has-directory-headroom-max-iterations-test
  (testing "the Survey :repl-researcher carries :max-iterations >= 20 so a
            directory-of-workbooks source doesn't hit 'max iterations reached
            without final!' (8 was too tight)."
    (h/with-async-test-context [ctx]
      (let [sid (survey/register-survey-subbehavior! ctx {:source csv-source})
            nodes (rm/get-nodes-by-id ctx sid)
            rr (first (filter #(= :repl-researcher (:type %)) (vals nodes)))]
        (is (some? rr) "the Survey sheet has a :repl-researcher node")
        (is (integer? (:max-iterations rr))
            "the node carries an explicit :max-iterations bound")
        (is (>= (:max-iterations rr) 20)
            "the bound gives the directory-of-workbooks case headroom (>= 20)")))))

;; ---------------------------------------------------------------------------
;; Prompt: domain-agnostic, medium-specialized, anti-emit-tree (the C1 fix).
;; ---------------------------------------------------------------------------

(deftest survey-prompt-is-medium-specialized-test
  (testing "the prompt names the per-medium specialist tools (csv vs sql) and the embed signal"
    (let [csv-p (survey/survey-prompt :csv)
          sql-p (survey/survey-prompt :sql)]
      ;; The DT2 focused tool catalog is medium-specific — csv names sample-rows /
      ;; peek-columns; sql names list-tables. We assert the catalogs DIFFER.
      (is (str/includes? (str/lower-case sql-p) "list-tables")
          "the sql prompt names the sql specialist tool list-tables")
      (is (not= csv-p sql-p)
          "the per-medium tool catalog makes the csv and sql prompts differ")
      (is (str/includes? csv-p (name survey/embed-field-signal-key))
          "the prompt asks for the embed-worthy-field signal"))))

(deftest survey-prompt-forbids-emit-tree-and-requires-edn-test
  (testing "the prompt forbids emit-tree and requires real Clojure data (the C1 prompt fix)"
    (let [p (survey/survey-prompt :csv)]
      (is (str/includes? p "emit-tree!")
          "the prompt explicitly references emit-tree! (to forbid it)")
      (is (str/includes? (str/lower-case p) "do not call (emit-tree")
          "the prompt hard-forbids calling emit-tree!")
      (is (str/includes? (str/lower-case p) "json")
          "the prompt warns against returning a JSON string (the C1 failure mode)")
      (is (str/includes? p ":profile")
          "the prompt instructs nesting the whole contract under :profile (the sole write)"))))

(deftest survey-prompt-is-domain-agnostic-test
  (testing "the prompt carries NO vertical/domain knowledge (discipline 12)"
    (let [p (str/lower-case (str (survey/survey-prompt :csv) (survey/survey-prompt :sql)))]
      ;; The example domain (education / CIP-SOC / IPEDS) must NOT leak into the
      ;; generic Survey prompt. NOTE: "crosswalk" is intentionally NOT in this
      ;; list — it appears only inside the upstream V03 CSV tool's own docstring
      ;; as a GENERIC data-shape term ("two code columns that should become a
      ;; relationship edge"), describing any two-code file, not the education
      ;; vertical. EB2 itself bakes in none of these vertical terms.
      (doseq [leak ["cip" "soc" "ipeds" "opeid" "occupation" "institution" "degree"]]
        (is (not (str/includes? p leak))
            (str "the Survey prompt must not bake in the vertical term: " leak))))))
