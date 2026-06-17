(ns ai.obney.orc.ontology.dt3-model-node-test
  "DT3 — focused Model node tests (the KEYSTONE over-extraction fix).

   Verifies the focused Model node through its PUBLIC interfaces (the
   `model-node-prompt` promotion seam + the orchestration's use of the focused
   path), NOT prompt-internal string assertions for their own sake:

     - The focused Model prompt is SINGLE-PURPOSE: it makes ONLY the modeling
       decision (entity types, URI-keying, GRAIN strategy, SCOPE filter, edges)
       and carries NONE of the retired mega-prompt's profiling / transform-design
       / coverage guidance — and NO industry knowledge (discipline 12).
     - The two LOAD-BEARING decisions are well-formed on a CAPTURED REAL
       model-spec (captured from the DT3 live verify on the real breakdown-heavy
       IPEDS completions table + the real CIP/SOC crosswalk CSV — see
       docs/build-timeline/live-verify/DT3-model.md). This is the V17/V20
       over-extraction failure (raw-row dump, no scope) fixed as a guaranteed
       step:
         GRAIN — every entity-type carries a grain-strategy from the frozen enum
                 (canonical-row-filter / breakdown-as-entity), NEVER
                 one-concept-per-raw-row. URI-keying derives from identifying
                 fields.
         SCOPE — the scope-filter is keyed to a discovered field with the value
                 the GOAL named (scope from the runtime goal, not hardcoded).
     - The Model node runs the FOCUSED path (:focused-prompt? true) and reads its
       predecessor's profile TOLERANTLY (prose-STRING value shapes — the DT2
       value-shape variance — not only structured maps/vectors).

   Discipline #4: the REAL-LLM proof is the DT3-model live verify; these tests
   pin the focused contract + the focused-path wiring + tolerant reading
   deterministically so a regression is caught fast. The captured-real
   model-specs below are VERBATIM node outputs from that live run."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]))

;; =============================================================================
;; CAPTURED REAL model-specs (VERBATIM from the DT3 live verify — no invention).
;; See docs/build-timeline/live-verify/DT3-model.md.
;; =============================================================================

(def ^:private captured-sql-model-spec
  "VERBATIM Model-node output for the real breakdown-heavy IPEDS completions table
   C2022_A (DT3 live verify), under a Louisiana-scoped goal. The over-extraction
   trap: per-demographic + per-award-level sub-rows of one program. The node chose
   canonical-row-filter (one program per identity, NOT one-per-raw-row) + a FIPS
   scope (Louisiana = FIPS 22) keyed to a discovered field. grain-strategy came
   back as a STRING form of the enum keyword this run — read tolerantly."
  {:entity-types
   [{:type "Institution"
     :uri-keying-fields ["UNITID"]
     :grain-strategy ":canonical-row-filter"}
    {:type "EducationalProgram"
     :uri-keying-fields ["UNITID" "CIPCODE" "AWLEVEL" "MAJORNUM"]
     :grain-strategy ":canonical-row-filter"}]
   :scope-filter {:field "FIPS" :values ["22"]}
   :edges
   [{:source-type "Institution"
     :target-type "EducationalProgram"
     :predicate "awards"}]})

(def ^:private captured-csv-model-spec
  "VERBATIM Model-node output for the real CIP/SOC crosswalk CSV (DT3 live verify),
   fed the captured DT2 PROSE-STRING profile directly. Proves tolerant reading:
   the node consumed string-valued profile fields and emitted clean vector-of-maps
   with breakdown-as-entity grain (each code IS an entity in a crosswalk) + a
   CIP-family-01 scope from the goal."
  {:entity-types
   [{:type "CIP_Program"
     :uri-keying-fields ["CIP_Code"]
     :grain-strategy ":breakdown-as-entity"}
    {:type "SOC_Occupation"
     :uri-keying-fields ["SOC_Code"]
     :grain-strategy ":breakdown-as-entity"}]
   :scope-filter {:field "CIP_Code" :values ["01.*"]}
   :edges
   [{:source-type "CIP_Program"
     :target-type "SOC_Occupation"
     :predicate "prepares_for"}]})

;; The captured DT2 PROSE-STRING profile the Model node read tolerantly (VERBATIM
;; from DT2-profile.md). Several fields are prose STRINGS, not maps/vectors.
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

;; A tolerant reader of the grain-strategy: the live verify showed it come back
;; both as the bare keyword :canonical-row-filter AND as the STRING
;; ":canonical-row-filter" (value-shape variance — the frozen contract freezes
;; KEYS, not value shapes). A consumer normalizes to a keyword.
(defn- ->grain-kw [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword (str/replace v #"^:" ""))
    :else        nil))

;; =============================================================================
;; The captured-real model-specs match the frozen contract (well-formedness)
;; =============================================================================

(deftest captured-real-model-specs-match-the-frozen-contract
  (testing "a real Model output carries EXACTLY the frozen PRD-M2 key set"
    (doseq [m [captured-sql-model-spec captured-csv-model-spec]]
      (is (= (set dt/model-contract-keys) (set (keys m)))
          "the captured real model-spec has exactly the frozen model-contract keys")
      ;; :scope-filter MAY be nil (no goal scope), but here both goals state a
      ;; scope, so it must be present.
      (is (some? (:entity-types m)) ":entity-types present")
      (is (some? (:edges m)) ":edges present"))))

;; =============================================================================
;; GRAIN — the load-bearing over-extraction fix (adversarial, not just present)
;; =============================================================================

(deftest grain-strategy-is-the-over-extraction-fix
  (testing "EVERY entity-type carries a grain-strategy from the FROZEN enum — the
            node decided grain (never one-concept-per-raw-row). Read tolerantly:
            the enum may arrive as a keyword OR its string form."
    (doseq [m [captured-sql-model-spec captured-csv-model-spec]]
      (doseq [et (:entity-types m)]
        (is (contains? dt/valid-grain-strategies (->grain-kw (:grain-strategy et)))
            (str "entity-type " (:type et)
                 " carries a valid frozen grain-strategy (got "
                 (pr-str (:grain-strategy et)) ")")))))

  (testing "the breakdown-heavy SQL completions table (the V17/V20 over-extraction
            trap) is NOT modeled one-concept-per-raw-row: the program entity is
            keyed by its IDENTIFYING fields (collapsing demographic/award sub-rows
            to ONE node) and uses canonical-row-filter grain"
    (let [prog (some #(when (= "EducationalProgram" (:type %)) %)
                     (:entity-types captured-sql-model-spec))]
      (is (some? prog) "the program entity is modeled")
      (is (= :canonical-row-filter (->grain-kw (:grain-strategy prog)))
          "the program uses canonical-row-filter (keep one canonical row, drop breakdowns)")
      ;; URI-keying derives from the profile's identifying fields — so the same
      ;; program collapses to one node rather than one-per-raw-row.
      (is (some #{"UNITID"} (:uri-keying-fields prog))
          "program URI is keyed by the institution id (identifying field)")
      (is (some #{"CIPCODE"} (:uri-keying-fields prog))
          "program URI is keyed by the program code (identifying field)")))

  (testing "the crosswalk codes ARE entities in their own right: each side is keyed
            by its own code (breakdown-as-entity) so the many-to-many rows do NOT
            collapse the codes away"
    (doseq [et (:entity-types captured-csv-model-spec)]
      (is (= :breakdown-as-entity (->grain-kw (:grain-strategy et)))
          (str (:type et) " mints the code as its own entity"))
      (is (seq (:uri-keying-fields et))
          (str (:type et) " has URI-keying fields (keyed by its code)")))))

;; =============================================================================
;; SCOPE — derived from the GOAL, keyed to a discovered field (not hardcoded)
;; =============================================================================

(deftest scope-filter-honors-the-goal-and-is-keyed-to-a-field
  (testing "when the GOAL states a scope, the model-spec carries a scope-filter
            keyed to a discovered field with the value the goal named"
    ;; SQL: the Louisiana-scoped goal -> a state/region scope keyed to a real
    ;; field (FIPS 22 IS Louisiana). The node keyed scope to a discovered field,
    ;; not a hardcoded national dump (the V17/V20 miss).
    (let [sf (:scope-filter captured-sql-model-spec)]
      (is (map? sf) "SQL scope-filter is a structured filter")
      (is (some? (:field sf)) "SQL scope-filter names a field to key on")
      (is (seq (:values sf)) "SQL scope-filter carries the goal's scope value(s)"))
    ;; CSV: the Agriculture(CIP 01)-scoped goal -> a CIP-code scope.
    (let [sf (:scope-filter captured-csv-model-spec)]
      (is (map? sf) "CSV scope-filter is a structured filter")
      (is (some? (:field sf)) "CSV scope-filter names a field")
      (is (seq (:values sf)) "CSV scope-filter carries the goal's scope value(s)")
      ;; The scope VALUE traces to the goal (CIP family 01), not an invented one.
      (is (some #(str/includes? (str %) "01") (:values sf))
          "the CSV scope value is the CIP family the goal named (01), not invented"))))

;; =============================================================================
;; The focused Model prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC
;; =============================================================================

(def ^:private domain-goal
  "Build an ontology of programs of study for Louisiana students.")

(deftest model-prompt-is-single-purpose
  (testing "the focused Model prompt makes ONLY the modeling decision and carries
            NONE of the Profile node's re-profiling job nor DT4's transform-authoring"
    (let [p (str/lower-case (dt/model-node-prompt domain-goal))]
      ;; It IS the modeling step: grain + scope are its whole job.
      (is (str/includes? p "model"))
      (is (str/includes? p "grain"))
      (is (str/includes? p "scope"))
      (is (str/includes? p "canonical-row-filter"))
      (is (str/includes? p "breakdown-as-entity"))
      (is (str/includes? p "uri-keying"))
      ;; It reads the profile (its predecessor's output) — does NOT re-profile.
      (is (str/includes? p ":profile"))
      (is (str/includes? p "do not re-")
          "explicitly told NOT to re-profile")
      ;; It does NOT author the extraction transform (DT4's job).
      (is (not (str/includes? p "(fn [row]"))
          "no transform authoring — that is DT4")
      (is (str/includes? p "do not write any extraction code")
          "explicitly defers transform authoring")
      ;; It is told to read the profile TOLERANTLY (value-shape variance).
      (is (str/includes? p "tolerant"))
      (is (str/includes? p "string"))))

  (testing "the focused Model prompt is small (a single-purpose prompt, not the
            multi-concern mega-prompt)"
    (is (< (count (dt/model-node-prompt domain-goal)) 6000)
        "the focused model prompt is small")))

(deftest model-prompt-is-domain-agnostic
  (testing "the prompt carries NO industry/vertical knowledge (discipline 12) —
            the only domain reference is the runtime goal the caller passed; the
            prompt body itself names no CIP/SOC/IPEDS/education concepts.
            Mirrors DT2's profile-prompt-is-domain-agnostic."
    ;; Render with a NEUTRAL goal so any domain term would have to come from the
    ;; prompt BODY, not the goal.
    (let [p (str/lower-case (dt/model-node-prompt "Model this dataset."))]
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings" "louisiana"
                    "fips" "stabbr" "demographic"]]
        (is (not (str/includes? p term))
            (str "the focused model prompt body must not bake in the term: " term))))))

;; =============================================================================
;; The Model node runs the FOCUSED path + reads the profile TOLERANTLY
;; =============================================================================

(deftest model-node-runs-focused-path-and-reads-profile-tolerantly
  (testing "the orchestration runs the Model node through the FOCUSED path
            (:focused-prompt? true so the mega-prompt is NOT prepended), threads
            the predecessor PROSE-STRING profile as the :profile inter-node input,
            and the emitted model-spec matches the frozen contract. We stub
            run-node-session! to assert the wiring + return the CAPTURED REAL
            csv model-spec."
    (let [seen (atom {})]
      (with-redefs [rlm-discovery/run-node-session!
                    (fn [_ctx {:keys [node-name instruction focused-prompt?
                                      extra-inputs] :as params}]
                      (swap! seen assoc node-name params)
                      (case node-name
                        :profile {:status :ok :output captured-csv-profile}
                        :model   (do
                                   ;; the focused path is taken for the Model node
                                   (is (true? focused-prompt?)
                                       "Model node runs with :focused-prompt? true")
                                   ;; the focused model prompt body is used
                                   (is (str/includes? (str/lower-case instruction)
                                                      "model step"))
                                   ;; the predecessor PROSE-STRING profile is the
                                   ;; inter-node input the node reads
                                   (is (= captured-csv-profile (:profile extra-inputs))
                                       "the (prose-string) profile is threaded to the Model node")
                                   {:status :ok :output captured-csv-model-spec})
                        ;; fail fast after model — downstream not under test here.
                        {:status :failed :error "downstream not under test"}))]
        (let [result (dt/run-discovery-tree!
                      {} {:ontology-id (random-uuid)
                          :source {:name :crosswalk :type :csv
                                   :path "/tmp/does-not-matter.csv"}
                          :goal domain-goal})]
          ;; The Model node ran (after the Profile node).
          (is (some? (:model @seen)) "the Model node was invoked")
          (is (= :model (:node-name (:model @seen))))
          ;; Its emitted model-spec is on the blackboard as the frozen contract.
          (is (= captured-csv-model-spec (dt/node-output (:blackboard result) :model))
              "the Model node's emitted model-spec (the frozen contract) is on the blackboard")
          ;; And the grain + scope decisions survived onto the blackboard.
          (let [m (dt/node-output (:blackboard result) :model)]
            (is (every? #(contains? dt/valid-grain-strategies
                                    (->grain-kw (:grain-strategy %)))
                        (:entity-types m))
                "every entity carries a valid grain-strategy on the blackboard")
            (is (some? (:scope-filter m))
                "the scope-filter survived onto the blackboard")))))))
