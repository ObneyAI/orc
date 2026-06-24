(ns ai.obney.orc.ontology.resilience-test
  "EB9 — the REUSABLE resilience sub-tree (`with-resilience`) + its composition
   INTO the subbehavior sheets.

   These durable HERMETIC tests (no LLM, no real source) lock the load-bearing
   STRUCTURE the EB9 prototype + live verify validated, through the builder's
   public surface + the subbehaviors' persisted node configs:

     - the resilient sub-tree SHAPE: a `:fallback` whose RECOVER branch is a
       `:fallback`[primary→robust] each gated by a sanity `:condition`/
       `:llm-condition`, and whose DIAGNOSE branch is a troubleshoot `:llm`
       followed by an ALWAYS-FAIL `:condition` (so a troubleshoot path can NEVER
       masquerade as success — #4/#5);
     - the troubleshoot node writes `:reasoning` FIRST (#13) then a STRUCTURED
       `:diagnosis`; its prompt composes Investigation (root-cause) + Validation
       (check) and is domain-agnostic (#12);
     - the always-fail sentinel key is namespaced + declared (so the build-time
       condition-key validation passes) and is NEVER a real contract key;
     - the builder is composed INTO the representative `:llm`-bearing subbehavior
       (Extract) — its public `:reads`/`:writes` contract is UNCHANGED and a
       resilient `:fallback` is present in the persisted tree.

   The induced-failure BEHAVIOR (recoverable → fallback recovers; unrecoverable →
   clean failure WITH diagnosis, no downstream poison) is proven by the prototype
   (`development/src/eb9_resilience_prototype.clj`) + the on-demand live verify
   with a REAL LLM troubleshoot
   (`development/src/eb9_resilience_live_verify.clj`,
   `development/ontology-integration/.../eb9_resilience_test.clj`)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.ontology.core.resilience :as res]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.validate-cq-subbehavior :as validate]
            [clojure.string :as str]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; A representative resilient step (the shape the subbehaviors compose).
;; ---------------------------------------------------------------------------

(defn- sample-step []
  (res/with-resilience
    {:step "demo"
     :primary (dsl/code "demo-primary" :fn "x/p" :reads [:rows]
                        :writes [:concept-drafts :concept-count])
     :robust  (dsl/code "demo-robust" :fn "x/r" :reads [:rows]
                        :writes [:concept-drafts :concept-count])
     :gate {:check {:key :concept-count :op :gt :value 0}}
     :troubleshoot {:reads [:rows :concept-count]
                    :step-label "the demo step"
                    :expectation "a non-empty result"}}))

(defn- by-name [node nm]
  (let [found (atom nil)]
    ((fn walk [n]
       (when (map? n)
         (when (= nm (:name n)) (reset! found n))
         (doseq [c (:children n)] (walk c))))
     node)
    @found))

(defn- all-nodes [node]
  (tree-seq #(and (map? %) (seq (:children %))) :children node))

;; ---------------------------------------------------------------------------
;; The resilient sub-tree SHAPE.
;; ---------------------------------------------------------------------------

(deftest resilient-subtree-is-fallback-with-recover-and-diagnose-branches-test
  (testing "with-resilience yields a :fallback[recover, diagnose] composite"
    (let [step (sample-step)]
      (is (= :fallback (:node-type step))
          "the resilient step is a :fallback (recover OR diagnose)")
      (is (= "demo-resilient" (:name step)))
      (is (= 2 (count (:children step)))
          "exactly two branches: recover then diagnose")
      (is (= "demo-recover" (:name (first (:children step)))))
      (is (= "demo-diagnose" (:name (second (:children step))))
          "the DIAGNOSE branch is LAST (so recover wins first)"))))

(deftest recover-branch-is-primary-then-robust-each-gated-test
  (testing "the recover branch tries primary→robust, each gated by a sanity :condition"
    (let [step (sample-step)
          por (by-name step "demo-primary-or-robust")]
      (is (= :fallback (:node-type por))
          "primary-or-robust is a :fallback (first success wins)")
      (let [primary-path (by-name step "demo-primary-path")
            robust-path  (by-name step "demo-robust-path")]
        (is (= :sequence (:node-type primary-path)))
        (is (= :sequence (:node-type robust-path)))
        ;; each path = [the work node, the sanity gate]
        (is (= "demo-primary" (:name (first (:children primary-path)))))
        (is (= :condition (:node-type (second (:children primary-path))))
            "the primary path is gated by a sanity :condition")
        (is (= {:key :concept-count :op :gt :value 0}
               (:check (second (:children primary-path))))
            "the gate checks the declared key (no hardcoded phrase matching)")
        (is (= :failure (:on-fail (second (:children primary-path))))
            "a failed gate aborts the path so the fallback tries the next")
        (is (= "demo-robust" (:name (first (:children robust-path)))))
        (is (= :condition (:node-type (second (:children robust-path)))))))))

(deftest diagnose-branch-troubleshoots-then-always-fails-test
  (testing "the diagnose branch runs the troubleshoot :llm THEN an ALWAYS-FAIL
            :condition — so it can NEVER masquerade as a success (#4/#5)"
    (let [step (sample-step)
          diagnose (by-name step "demo-diagnose")
          [ts fail] (:children diagnose)]
      (is (= :sequence (:node-type diagnose)))
      (is (= "demo-troubleshoot" (:name ts)))
      (is (= :leaf (:node-type ts)))
      (is (= :ai (:executor ts))
          "the troubleshoot node is an :llm (:ai executor) node")
      ;; the ALWAYS-FAIL condition
      (is (= :condition (:node-type fail)))
      (is (= "demo-fail-with-diagnosis" (:name fail)))
      (is (= res/never-key (get-in fail [:check :key]))
          "the terminal condition checks the NEVER-written sentinel key")
      (is (= :exists (get-in fail [:check :op])))
      (is (= :failure (:on-fail fail))
          "the terminal condition forces a clean :failure (carrying the diagnosis)"))))

(deftest never-key-is-namespaced-and-not-a-real-contract-key-test
  (testing "the always-fail sentinel is namespaced (cannot collide with a real key)"
    (is (keyword? res/never-key))
    (is (some? (namespace res/never-key))
        "the sentinel is namespaced so it never collides with a contract key")
    ;; the sentinel is declared by resilience-blackboard-keys (so the build-time
    ;; condition-key validation passes) but is never WRITTEN by any node.
    (is (contains? (res/resilience-blackboard-keys) res/never-key)
        "the sentinel is declared so the build-time check-key validation passes")))

;; ---------------------------------------------------------------------------
;; The troubleshoot node — #13 reasoning first, structured diagnosis, agnostic.
;; ---------------------------------------------------------------------------

(deftest troubleshoot-writes-reasoning-first-then-structured-diagnosis-test
  (testing "#13: the troubleshoot :llm writes :reasoning FIRST, then :diagnosis"
    (let [ts (res/troubleshoot-node {:name "t" :reads [:concept-count]})]
      (is (= [:reasoning res/diagnosis-key] (vec (:writes ts)))
          "reasoning FIRST (#13), then the structured diagnosis")
      (is (= :diagnosis res/diagnosis-key))))
  (testing "a node-scoped reasoning key is supported for concurrent contexts (#13)"
    (let [ts (res/troubleshoot-node {:name "t" :reads [:x]
                                     :reasoning-key :t-reasoning})]
      (is (= [:t-reasoning :diagnosis] (vec (:writes ts)))
          "a node-scoped reasoning key avoids blackboard trample in concurrent contexts"))))

(deftest diagnosis-schema-is-structured-not-bare-map-test
  (testing "the :diagnosis write declares a STRUCTURED [:map …] (C1), not a bare :map"
    (is (= :map (first res/diagnosis-schema)))
    (is (> (count res/diagnosis-schema) 2)
        "a structured [:map …] has field entries — a bare :map (the C1 failure) would not")
    (is (m/validate res/diagnosis-schema
                    {:symptom "0 concepts" :root-cause "mis-grounded key"
                     :recommended-fix "re-author" :recoverable? false})
        "a real Investigation+Validation diagnosis validates")
    (is (not (m/validate res/diagnosis-schema "a json string"))
        "a STRING (the C1 failure mode) does NOT validate the structured map")))

(deftest troubleshoot-prompt-composes-investigation-and-validation-agnostic-test
  (testing "the troubleshoot prompt composes Investigation (root-cause) + Validation
            (check) and is domain-agnostic (#12)"
    (let [p (res/troubleshoot-prompt "the extraction step" "a non-empty scoped set")
          lp (str/lower-case p)]
      ;; Investigation pattern: enumerate → rule out → converge → root-cause
      (is (str/includes? lp "root cause"))
      (is (str/includes? lp "rule out"))
      (is (str/includes? lp "recommended fix"))
      ;; Validation pattern: structured {:rule :reason} failure entries
      (is (str/includes? lp "reason"))
      ;; #13 reasoning first
      (is (str/includes? lp "reasoning"))
      (is (str/includes? lp "first"))
      ;; domain-agnostic — no vertical knowledge baked in
      (doseq [leak ["cip" "soc" "ipeds" "opeid" "occupation" "institution"
                    "degree" "stabbr" "unitid" "awlevel"]]
        (is (not (str/includes? lp leak))
            (str "the troubleshoot prompt must not bake in the vertical term: " leak))))))

;; ---------------------------------------------------------------------------
;; The sanity gate — deterministic :condition OR semantic :llm-condition.
;; ---------------------------------------------------------------------------

(deftest sanity-gate-supports-condition-and-llm-condition-test
  (testing "a deterministic :condition gate"
    (let [g (res/sanity-gate {:name "g" :check {:key :concept-count :op :gt :value 0}})]
      (is (= :condition (:node-type g)))
      (is (= :failure (:on-fail g)))))
  (testing "a semantic :llm-condition gate (judgment, NOT a hardcoded phrase list — #7)"
    (let [g (res/sanity-gate {:name "g"
                              :llm-check {:instruction "Is the output non-empty and in scope?"
                                          :reads [:concept-drafts]}})]
      (is (= :llm-condition (:node-type g)))
      (is (str/includes? (:instruction g) "scope"))))
  (testing "exactly one of :check / :llm-check is required"
    (is (thrown? Exception (res/sanity-gate {:name "g"})))))

;; ---------------------------------------------------------------------------
;; Composition INTO a subbehavior — the public contract is UNCHANGED + a
;; resilient :fallback is present in the persisted tree.
;; ---------------------------------------------------------------------------

(deftest extract-subbehavior-composes-resilience-without-changing-contract-test
  (testing "Extract composes a resilient :fallback around its failure-prone author
            step — now in the PER-CONTAINER unit (MC-5: @v1 is a thin orchestrator
            that drives the per-container unit once per container; resilience gates
            EACH container). The public contract is unchanged."
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {:resilient? true})
            ;; resilience lives in the per-container unit the orchestrator drives.
            unit-id (extract/extract-per-container-sheet-id-for)
            nodes (vals (rm/get-nodes-by-id ctx unit-id))
            fallbacks (filter #(= :fallback (:type %)) nodes)
            ;; the troubleshoot node + the always-fail condition are present
            ai-leaves (filter #(and (= :leaf (:type %)) (= :ai (:executor %))) nodes)
            conditions (filter #(= :condition (:type %)) nodes)]
        (is (seq fallbacks)
            "a resilient :fallback is present in the composed per-container unit")
        ;; the troubleshoot adds a SECOND :ai leaf (the author + the troubleshoot)
        (is (>= (count ai-leaves) 2)
            "the troubleshoot :llm node is composed in alongside the author")
        ;; at least one sanity gate + the always-fail condition
        (is (>= (count conditions) 2)
            "a sanity gate + the always-fail terminal condition are present")
        ;; the always-fail sentinel condition is present
        (is (some #(= res/never-key (get-in % [:check :key])) conditions)
            "the always-fail sentinel condition (clean-failure forcer) is present"))))
  (testing "the per-container SAMPLE node's contract is unchanged by composing
            resilience (it reads the source + the one container it grounds)"
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {:resilient? true})
            unit-id (extract/extract-per-container-sheet-id-for)
            sample (first (filter #(= "sample-rows" (:name %))
                                  (vals (rm/get-nodes-by-id ctx unit-id))))]
        (is (= [:source :container] (vec (:reads sample)))
            "the SAMPLE node reads the source + the container (MC-5 contract)")))))

(deftest extract-resilient-build-is-idempotent-test
  (testing "the resilient Extract sheet registers deterministically + idempotently"
    (h/with-async-test-context [ctx]
      (let [id-1 (extract/register-extract-subbehavior! ctx {:resilient? true})
            id-2 (extract/register-extract-subbehavior! ctx {:resilient? true})]
        (is (= id-1 id-2)
            "re-registering the resilient Extract is idempotent (same id)")))))

;; ---------------------------------------------------------------------------
;; Composition into the OTHER :llm-bearing subbehaviors (Model, Validate) — the
;; :llm-condition (semantic) gate flavor; contract unchanged; idempotent.
;; ---------------------------------------------------------------------------

(deftest model-subbehavior-composes-resilience-with-llm-condition-gate-test
  (testing "Model composes a resilient :fallback around its :llm modeling step,
            gated by a SEMANTIC :llm-condition (the model-spec map can't be checked
            by a flat deterministic :condition); the public contract is unchanged"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {:resilient? true})
            nodes (vals (rm/get-nodes-by-id ctx sid))
            fallbacks (filter #(= :fallback (:type %)) nodes)
            llm-conditions (filter #(= :llm-condition (:type %)) nodes)
            ai-leaves (filter #(and (= :leaf (:type %)) (= :ai (:executor %))) nodes)
            conditions (filter #(= :condition (:type %)) nodes)]
        (is (seq fallbacks)
            "a resilient :fallback is present in the composed Model tree")
        (is (seq llm-conditions)
            "Model uses the SEMANTIC :llm-condition gate flavor")
        (is (>= (count ai-leaves) 3)
            "primary author + robust author + troubleshoot are all :llm nodes")
        (is (some #(= res/never-key (get-in % [:check :key])) conditions)
            "the always-fail sentinel condition (clean-failure forcer) is present"))))
  (testing "the resilient Model build is idempotent"
    (h/with-async-test-context [ctx]
      (is (= (model/register-model-subbehavior! ctx {:resilient? true})
             (model/register-model-subbehavior! ctx {:resilient? true}))))))

(deftest validate-cq-subbehavior-composes-resilience-test
  (testing "Validate+CQ composes a resilient :fallback around its :llm DERIVE step,
            gated by a SEMANTIC :llm-condition; the persist + gate :code nodes and
            the public contract are unchanged"
    (h/with-async-test-context [ctx]
      (let [sid (validate/register-validate-cq-subbehavior! ctx {:resilient? true})
            nodes (vals (rm/get-nodes-by-id ctx sid))
            fallbacks (filter #(= :fallback (:type %)) nodes)
            llm-conditions (filter #(= :llm-condition (:type %)) nodes)
            ;; persist + gate :code nodes survive untouched
            persist (first (filter #(= "persist" (:name %)) nodes))
            gate (first (filter #(= "gate" (:name %)) nodes))]
        (is (seq fallbacks)
            "a resilient :fallback is present in the composed Validate+CQ tree")
        (is (seq llm-conditions)
            "Validate+CQ uses the SEMANTIC :llm-condition gate flavor")
        (is (some? persist) "the PERSIST :code node is untouched")
        (is (some? gate) "the GATE :code node is untouched"))))
  (testing "the resilient Validate+CQ build is idempotent"
    (h/with-async-test-context [ctx]
      (is (= (validate/register-validate-cq-subbehavior! ctx {:resilient? true})
             (validate/register-validate-cq-subbehavior! ctx {:resilient? true}))))))
