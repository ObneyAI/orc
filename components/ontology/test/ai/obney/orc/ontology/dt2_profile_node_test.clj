(ns ai.obney.orc.ontology.dt2-profile-node-test
  "DT2 — focused Profile node tests.

   Verifies the focused Profile node through its PUBLIC interfaces (the
   `profile-node-prompt` promotion seam + the orchestration's use of the focused
   path), NOT prompt-internal string assertions for their own sake:

     - The focused prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC: it does the ONE
       profiling job and carries NONE of the retired mega-prompt's modeling /
       grain / scope-decision / transform-design guidance, and NO industry
       knowledge (discipline 12).
     - The node works ACROSS MEDIUMS by binding the per-medium specialist tools
       at the leaf — the SAME prompt, a per-medium tool catalog pulled from the
       specialist tools' own docstrings.
     - Given a real source, the Profile node runs the FOCUSED path
       (:focused-prompt? true through run-node-session!) and emits a profile
       matching the frozen contract.
     - The frozen profile contract is WELL-FORMED on a CAPTURED REAL profile
       (captured from the DT2 live verify on the real CIP/SOC crosswalk CSV +
       the real IPEDS SQLite DB — see docs/build-timeline/live-verify/DT2-profile.md).
       No invented fixtures.

   Discipline #4: the REAL-LLM proof is the DT2-profile live verify; these tests
   pin the focused contract + the focused-path wiring deterministically so a
   regression is caught fast. The captured-real profiles below are VERBATIM node
   outputs from that live run."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]))

;; =============================================================================
;; CAPTURED REAL profiles (VERBATIM from the DT2 live verify — no invention).
;; See docs/build-timeline/live-verify/DT2-profile.md.
;; =============================================================================

(def ^:private captured-csv-profile
  "VERBATIM Profile-node output for the real cip_soc_crosswalk.csv (DT2 live
   verify). The model returned prose-string values for several fields this run —
   within the DT1-frozen contract, which freezes the KEY SET, not value shapes."
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
     "SOC_Code" "19-1011" "SOC_Title" "Animal Scientists"}
    {"CIP_Code" "01.0000" "CIP_Title" "Agriculture, General."
     "SOC_Code" "19-1012" "SOC_Title" "Food Scientists and Technologists"}]})

(def ^:private captured-sql-profile
  "VERBATIM Profile-node output for the real IPEDS output.db (DT2 live verify).
   Clean map/vector value shapes this run."
  {:entity-candidates
   ["Higher Education Institutions" "Educational Surveys" "States" "Geographic Regions"]
   :identifying-keys {"Higher Education Institutions" ["UNITID" "OPEID"]}
   :scope-fields ["STABBR" "SECTOR" "HLOFFER" "CONTROL" "LOCALE"]
   :linking-keys ["UNITID" "FIPS" "STABBR" "COUNTYNM"]
   :grain-signals ["UNITID repeated across years" "Institution splits by Survey Period"]
   :sample
   [{:UNITID 100654 :INSTNM "Alabama A & M University" :ADDR "4900 Meridian Street"
     :CITY "Normal" :STABBR "AL" :ZIP "35762"}
    {:UNITID 100663 :INSTNM "University of Alabama at Birmingham" :ADDR "701 S 20th St"
     :CITY "Birmingham" :STABBR "AL" :ZIP "35233"}]})

;; =============================================================================
;; The captured-real profiles match the frozen contract (well-formedness)
;; =============================================================================

(deftest captured-real-profiles-match-the-frozen-contract
  (testing "a real Profile output carries EXACTLY the frozen PRD-M2 key set"
    (doseq [p [captured-csv-profile captured-sql-profile]]
      (is (= (set dt/profile-contract-keys) (set (keys p)))
          "the captured real profile has exactly the frozen profile-contract keys")
      (doseq [k dt/profile-contract-keys]
        (is (some? (get p k)) (str k " is present + non-nil")))))

  (testing "the load-bearing characterization is CORRECT (adversarial — not just
            present): the codes that identify + link the entities are captured"
    ;; CSV crosswalk: the cross-source linking keys are the shared codes.
    (let [links (str (:linking-keys captured-csv-profile))]
      (is (str/includes? links "CIPCode") "CSV linking-keys names the CIP code")
      (is (str/includes? links "SOCCode") "CSV linking-keys names the SOC code"))
    ;; CSV grain signal: the many-to-many crosswalk (rows finer than entities).
    (is (str/includes? (str/lower-case (str (:grain-signals captured-csv-profile)))
                        "many-to-many")
        "CSV grain-signals captures the many-to-many crosswalk grain")
    ;; SQL: institution identity + goal-scoping candidate fields.
    (is (contains? (set (vals (:identifying-keys captured-sql-profile)))
                   ["UNITID" "OPEID"])
        "SQL identifying-keys names the institution identity columns")
    (is (some #{"STABBR"} (:scope-fields captured-sql-profile))
        "SQL scope-fields lists a real region-scoping candidate (state)")
    (is (some #{"UNITID"} (:linking-keys captured-sql-profile))
        "SQL linking-keys names the shareable cross-source institution code")))

;; =============================================================================
;; The focused prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC
;; =============================================================================

(def ^:private domain-goal
  "Build an ontology connecting fields/programs of study to occupations.")

(deftest profile-prompt-is-single-purpose
  (testing "the focused Profile prompt does the ONE profiling job and carries
            NONE of DT3/DT4's modeling / grain-strategy / transform-design /
            scope-DECISION guidance (the mega-prompt cross-concerns)"
    (let [p (str/lower-case (dt/profile-node-prompt domain-goal :csv))]
      ;; It IS a profiling prompt.
      (is (str/includes? p "profile"))
      (is (str/includes? p "characterize"))
      (is (str/includes? p "entity candidates"))
      ;; It does NOT instruct modeling / transform-authoring / grain-strategy /
      ;; scope-filtering — those are later focused nodes' jobs.
      (is (not (str/includes? p "grain-strategy")))
      (is (not (str/includes? p "canonical-row-filter")))
      (is (not (str/includes? p "breakdown-as-entity")))
      (is (not (str/includes? p "extraction-transform")))
      (is (not (str/includes? p "(fn [row]")))
      (is (not (str/includes? p "scope-filter")))
      (is (not (str/includes? p "uri-keying"))
          "no URI-keying guidance — modeling is DT3's job")
      ;; It explicitly tells the node NOT to do downstream work.
      (is (str/includes? p "no scope decision"))
      (is (str/includes? p "do not"))))

  (testing "the focused prompt is auditably FAR smaller than the retired
            mega-prompt (single-purpose, not a multi-concern wall of prose)"
    ;; The retired mega-prompt is structured-discovery-prompt over a base; even
    ;; just its structured-source augmentation dwarfs the focused profile prompt.
    (let [focused (dt/profile-node-prompt domain-goal :csv)]
      (is (< (count focused) 6000)
          "the focused profile prompt is small (a single-purpose prompt)"))))

(deftest profile-prompt-is-domain-agnostic
  (testing "the prompt carries NO industry/vertical knowledge (discipline 12) —
            the only domain reference is the runtime goal the caller passed; the
            prompt body itself names no CIP/SOC/IPEDS/education concepts"
    ;; Render with a NEUTRAL goal so any domain term would have to come from the
    ;; prompt BODY, not the goal.
    (let [p (str/lower-case
             (dt/profile-node-prompt "Characterize this dataset." :csv))]
      ;; These are VERTICAL/industry terms — none may appear in the prompt body.
      ;; (Generic data-shape terms like "crosswalk" / "foreign key" may appear via
      ;; a specialist tool's own docstring — they describe STRUCTURE, not a
      ;; domain, and are part of tool ergonomics, which discipline 12 permits.)
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings"]]
        (is (not (str/includes? p term))
            (str "the focused prompt body must not bake in the term: " term))))))

;; =============================================================================
;; The node works ACROSS MEDIUMS: a per-medium specialist tool catalog
;; =============================================================================

(deftest profile-prompt-binds-per-medium-tools-at-the-leaf
  (testing "the SAME profiling prompt names the medium's specialist tools — csv,
            sql, excel each get their own tool catalog (pulled from the real
            specialist docstrings), text points at the inline blackboard"
    (let [csv (dt/profile-node-prompt domain-goal :csv)
          sql (dt/profile-node-prompt domain-goal :sql)
          xls (dt/profile-node-prompt domain-goal :excel)
          txt (dt/profile-node-prompt domain-goal :text)]
      ;; csv specialist tools
      (is (str/includes? csv "peek-columns"))
      (is (str/includes? csv "sample-rows"))
      ;; sql specialist tools (different surface — schema/tables)
      (is (str/includes? sql "list-tables"))
      (is (str/includes? sql "table-schema"))
      (is (not (str/includes? sql "peek-columns"))
          "the sql catalog does NOT name csv-only tools")
      ;; excel specialist tools
      (is (str/includes? xls "list-sheets"))
      (is (str/includes? xls "sheet-columns"))
      ;; text — no source tools; points at inline content
      (is (str/includes? (str/lower-case txt) "inline"))
      (is (not (str/includes? txt "list-tables")))
      ;; The profiling INSTRUCTION (the one job) is the same across mediums.
      (doseq [p [csv sql xls txt]]
        (is (str/includes? (str/lower-case p) "entity candidates")
            "every medium gets the same profiling job")))))

;; =============================================================================
;; The Profile node runs the FOCUSED path + emits a profile matching the contract
;; =============================================================================

(deftest profile-node-runs-focused-path-and-emits-the-contract
  (testing "given a real source, the orchestration runs the Profile node through
            the FOCUSED path (:focused-prompt? true so the mega-prompt is NOT
            prepended) and the emitted profile matches the frozen contract.
            We stub run-node-session! to (a) assert the focused flag + the
            per-medium prompt and (b) return the CAPTURED REAL csv profile."
    (let [seen (atom nil)]
      (with-redefs [rlm-discovery/run-node-session!
                    (fn [_ctx {:keys [node-name instruction focused-prompt?] :as params}]
                      (when (= :profile node-name)
                        (reset! seen params)
                        ;; the focused path is taken for the Profile node
                        (is (true? focused-prompt?)
                            "Profile node runs with :focused-prompt? true")
                        ;; the prompt is the focused per-medium profile prompt
                        (is (str/includes? (str/lower-case instruction)
                                           "profile step"))
                        (is (str/includes? instruction "peek-columns")
                            "the csv source bound the csv tool catalog"))
                      (case node-name
                        :profile {:status :ok :output captured-csv-profile}
                        ;; fail fast after profile — this test is about the
                        ;; Profile node only; downstream isn't exercised here.
                        {:status :failed :error "downstream not under test"}))]
        (let [result (dt/run-discovery-tree!
                      {} {:ontology-id (random-uuid)
                          :source {:name :crosswalk :type :csv :path "/tmp/does-not-matter.csv"}
                          :goal domain-goal})]
          ;; The Profile node ran (and was the first node).
          (is (some? @seen) "the Profile node was invoked")
          (is (= :profile (:node-name @seen)))
          ;; Its emitted profile is on the blackboard as the frozen contract.
          (is (= captured-csv-profile (dt/node-output (:blackboard result) :profile))
              "the Profile node's emitted profile (the frozen contract) is on the blackboard"))))))
