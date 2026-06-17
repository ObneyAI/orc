(ns ai.obney.orc.ontology.dt5-requirements-cq-node-test
  "DT5 — graph-level Requirements / competency-question node tests.

   Verifies the focused CQ node through its PUBLIC interfaces (the `cq-node-prompt`
   promotion seam + the `requirements-cq-node!` orchestration), NOT prompt-internal
   string assertions for their own sake:

     - The focused CQ prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC: it does the ONE
       requirements job (derive CQs from goal ⨯ profiles) and carries NONE of the
       profiling / modeling / transform-design guidance, and NO industry knowledge
       (discipline 12).
     - The node DERIVES CQs from the goal ⨯ the profiles, PERSISTS them as the S14
       ORSD spec build!'s S15 exit-criterion reads (PRD M3 + M7 — round-trip through
       the REAL command → event → projection path, no bare append).
     - A consumer-supplied CQ set OVERRIDES/seeds the derived set (derivation
       skipped; supplied questions persisted).
     - An empty derivation surfaces honestly as :failed (Discipline #5; no false
       green — the gate must never have nothing to judge).

   Discipline #4: the REAL-LLM proof is the DT5-cq live verify; these tests stub the
   derivation session (via run-node-session! redef) so the orchestration + the
   persistence round-trip are tested deterministically over a REAL Grain in-memory
   event store. The profiles fed in are the CAPTURED REAL DT2 profiles (verbatim
   from docs/build-timeline/live-verify/DT2-profile.md) — no invented fixtures.
   Domain-agnostic: the prompt-body test renders with a neutral goal."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context (mirrors dt1 / s14 pattern)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dt5-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

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

;; =============================================================================
;; CAPTURED REAL DT2 profiles (VERBATIM — docs/build-timeline/live-verify/DT2-profile.md)
;; =============================================================================

(def ^:private captured-csv-profile
  {:entity-candidates
   "Academic Programs (CIP), Occupational Titles (SOC), Professional Occupations, Crosswalk/Alignment mappings."
   :identifying-keys
   "'CIPCode' (or 'CIP2020Code'), 'SOCCode' (or 'SOC2018Code')"
   :scope-fields "'CIPTitle', 'SOCTitle'"
   :linking-keys "'CIPCode', 'CIP2020Code', 'SOCCode', 'SOC2018Code'"
   :grain-signals
   "The dataset represents a many-to-many relationship mapping. Repeating keys in both CIP and SOC columns indicate that one program can lead to many occupations, and one occupation can be entered via many programs."
   :sample
   [{"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
     "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}]})

(def ^:private captured-sql-profile
  {:entity-candidates
   ["Higher Education Institutions" "Educational Surveys" "States" "Geographic Regions"]
   :identifying-keys {"Higher Education Institutions" ["UNITID" "OPEID"]}
   :scope-fields ["STABBR" "SECTOR" "HLOFFER" "CONTROL" "LOCALE"]
   :linking-keys ["UNITID" "FIPS" "STABBR" "COUNTYNM"]
   :grain-signals ["UNITID repeated across years" "Institution splits by Survey Period"]
   :sample
   [{:UNITID 100654 :INSTNM "Alabama A & M University" :STABBR "AL"}]})

(def ^:private domain-goal
  "Build an ontology connecting fields/programs of study to the occupations they prepare people for.")

;; =============================================================================
;; The focused CQ prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC
;; =============================================================================

(deftest cq-prompt-is-single-purpose
  (testing "the focused CQ prompt does the ONE requirements job and carries NONE
            of the profiling / modeling / transform-authoring guidance"
    (let [p (str/lower-case (dt/cq-node-prompt domain-goal))]
      ;; It IS a requirements/CQ prompt.
      (is (str/includes? p "competency question"))
      (is (str/includes? p "requirements step"))
      (is (str/includes? p "grounded"))
      (is (str/includes? p "goal-anchored"))
      ;; It does NOT instruct profiling / modeling / transform-authoring.
      (is (not (str/includes? p "grain-strategy")))
      (is (not (str/includes? p "canonical-row-filter")))
      (is (not (str/includes? p "(fn [row]")))
      (is (not (str/includes? p "uri-keying")))
      ;; It explicitly tells the node NOT to do the other nodes' work.
      (is (str/includes? p "do not profile"))
      (is (str/includes? p "do not mint")))))

(deftest cq-prompt-is-domain-agnostic
  (testing "the prompt carries NO industry/vertical knowledge (discipline 12) — the
            only domain reference is the runtime goal; the body names no domain"
    (let [p (str/lower-case (dt/cq-node-prompt "Answer the key questions about this dataset."))]
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings"]]
        (is (not (str/includes? p term))
            (str "the focused CQ prompt body must not bake in the term: " term))))))

(deftest cq-prompt-is-small-and-anchored-to-the-goal
  (testing "single-purpose (small) and the goal is interpolated so the CQs are
            anchored to the runtime goal, not the prompt body"
    (let [p (dt/cq-node-prompt domain-goal)]
      (is (< (count p) 6000) "the focused CQ prompt is small (single-purpose)")
      (is (str/includes? p domain-goal)
          "the runtime goal is carried into the prompt (CQs are goal-anchored)"))))

;; =============================================================================
;; string-cqs coercion is tolerant (value-shape variance) + honest on empty
;; =============================================================================

(deftest cqs-coercion-is-tolerant
  (testing "a model may emit CQs as a vector of strings, a newline-joined string,
            or a vector of {:question ...} maps — all coerce to clean strings"
    (is (= ["Q1?" "Q2?"]
           (dt/string-cqs ["Q1?" " Q2? " "" "Q1?"]))
        "vector of strings: trimmed, blanks dropped, deduped")
    (is (= ["A?" "B?"]
           (dt/string-cqs "A?\nB?\n\n"))
        "newline-joined string splits into questions")
    (is (= ["Which X link to Y?"]
           (dt/string-cqs [{:question "Which X link to Y?"}]))
        "vector of {:question ...} maps")
    (is (= [] (dt/string-cqs nil)) "nil -> [] (honest empty)")
    (is (= [] (dt/string-cqs [])) "[] -> []")))

;; =============================================================================
;; DERIVE → PERSIST round-trip: the CQs persist as the ORSD spec build! reads
;; =============================================================================

(deftest derived-cqs-persist-as-the-orsd-spec
  (testing "given the captured-real profiles, the node DERIVES CQs (session
            stubbed) and PERSISTS them as the S14 ORSD spec — the SAME spec
            build!'s S15 exit-criterion reads via ontology/get-ontology-spec"
    (with-ctx [ctx]
      (let [ontology-id (random-uuid)
            derived-cqs ["Which occupations does a given field of study prepare people for?"
                         "Which fields of study lead to a given occupation?"
                         "What is the title of each program (CIP) and occupation (SOC)?"]
            seen (atom nil)]
        (with-redefs [rlm-discovery/run-node-session!
                      (fn [_ctx {:keys [node-name instruction focused-prompt? extra-inputs] :as params}]
                        (reset! seen params)
                        (is (= :requirements node-name) "the CQ node session is named :requirements")
                        (is (true? focused-prompt?) "the CQ node runs the focused path")
                        (is (str/includes? (str/lower-case instruction) "competency question")
                            "the CQ derivation prompt is the focused requirements prompt")
                        (is (= [captured-csv-profile captured-sql-profile]
                               (:profiles extra-inputs))
                            "the profiles flow into the session as the :profiles input (graph-level)")
                        {:status :ok
                         :output {:competency-questions derived-cqs
                                  :rationale ["tests goal's program→occupation link; grounded in crosswalk linking-keys"
                                              "tests reverse link; grounded in crosswalk linking-keys"
                                              "tests titles; grounded in CIP/SOC title fields"]}})]
          (let [result (dt/requirements-cq-node!
                        ctx {:ontology-id ontology-id
                             :goal domain-goal
                             :profiles [captured-csv-profile captured-sql-profile]})]
            (is (some? @seen) "the derivation session ran")
            (is (= :ok (:status result)))
            (is (= :derived (:origin result)) "origin is :derived (no consumer CQs supplied)")
            (is (= derived-cqs (:competency-questions result))
                "the derived CQs are surfaced verbatim for HITL review")
            (is (= 3 (count (:rationale result)))
                "a per-CQ rationale is surfaced for HITL review")
            (is (true? (:spec-recorded? result)) "the CQs persisted (command succeeded)")
            ;; THE LOAD-BEARING ASSERTION: they persist as the ORSD spec build! reads.
            (Thread/sleep 100)
            (let [spec (ontology/get-ontology-spec ctx ontology-id)]
              (is (= derived-cqs (:competency-questions spec))
                  "the derived CQs ARE the ORSD spec's :competency-questions (S15 judges these)")
              (is (= domain-goal (:purpose spec))
                  "the goal is stamped as the spec :purpose when the spec had none"))))))))

;; =============================================================================
;; CONSUMER OVERRIDE: a supplied CQ set seeds/overrides the derived set
;; =============================================================================

(deftest supplied-cqs-override-derivation
  (testing "when the consumer supplies CQs, derivation is SKIPPED and the supplied
            questions are persisted as the ORSD spec (HITL override)"
    (with-ctx [ctx]
      (let [ontology-id (random-uuid)
            supplied ["My own CQ one?" "My own CQ two?"]
            derive-called? (atom false)]
        (with-redefs [rlm-discovery/run-node-session!
                      (fn [& _] (reset! derive-called? true)
                        {:status :ok :output {:competency-questions ["should-not-be-used"]}})]
          (let [result (dt/requirements-cq-node!
                        ctx {:ontology-id ontology-id
                             :goal domain-goal
                             :profiles [captured-csv-profile]
                             :cqs supplied})]
            (is (false? @derive-called?)
                "derivation session was NOT invoked — supplied CQs override")
            (is (= :ok (:status result)))
            (is (= :supplied (:origin result)))
            (is (= supplied (:competency-questions result)))
            (Thread/sleep 100)
            (is (= supplied (:competency-questions (ontology/get-ontology-spec ctx ontology-id)))
                "the supplied CQs persisted as the ORSD spec build! reads")))))))

;; =============================================================================
;; HONEST EMPTY: an empty derivation surfaces as :failed (no false green)
;; =============================================================================

(deftest empty-derivation-fails-honestly
  (testing "a derivation that produces NO questions surfaces as :failed — the
            exit gate must never have nothing to judge (Discipline #5)"
    (with-ctx [ctx]
      (let [ontology-id (random-uuid)]
        (with-redefs [rlm-discovery/run-node-session!
                      (fn [& _] {:status :ok :output {:competency-questions []}})]
          (let [result (dt/requirements-cq-node!
                        ctx {:ontology-id ontology-id
                             :goal domain-goal
                             :profiles [captured-csv-profile]})]
            (is (= :failed (:status result)))
            (is (str/includes? (:error result) "NO competency questions"))
            ;; nothing was persisted — the gate has nothing FALSE to judge.
            (Thread/sleep 100)
            (is (nil? (ontology/get-ontology-spec ctx ontology-id))
                "no spec recorded on an empty derivation (no false green)"))))))

  (testing "a failed derivation session surfaces honestly as :failed"
    (with-ctx [ctx]
      (with-redefs [rlm-discovery/run-node-session!
                    (fn [& _] {:status :failed :error "session blew up"})]
        (let [result (dt/requirements-cq-node!
                      ctx {:ontology-id (random-uuid)
                           :goal domain-goal
                           :profiles [captured-csv-profile]})]
          (is (= :failed (:status result)))
          (is (str/includes? (:error result) "session blew up")))))))

;; =============================================================================
;; The CQ contract is exposed + stable; required-arg guards hold
;; =============================================================================

(deftest cq-contract-and-guards
  (testing "the frozen CQ contract keys are exposed (downstream + tests reference
            them rather than re-typing)"
    (is (= [:competency-questions :rationale] dt/cq-contract-keys)))
  (testing "required-arg guards surface loudly (no silent degenerate path)"
    (with-ctx [ctx]
      (is (thrown? clojure.lang.ExceptionInfo
                   (dt/requirements-cq-node! ctx {:goal "g" :profiles [{}]}))
          "missing :ontology-id throws")
      (is (thrown? clojure.lang.ExceptionInfo
                   (dt/requirements-cq-node! ctx {:ontology-id (random-uuid) :profiles [{}]}))
          "missing :goal throws")
      (is (thrown? clojure.lang.ExceptionInfo
                   (dt/requirements-cq-node! ctx {:ontology-id (random-uuid) :goal "g"}))
          "missing :profiles (and no :cqs) throws"))))
