(ns ai.obney.orc.ontology.dt4-transform-node-test
  "DT4 — focused Transform node tests (where grain + scope TAKE EFFECT).

   Verifies the focused Transform node through its PUBLIC interfaces (the
   `transform-node-prompt` promotion seam, the `normalize-grain-strategy`
   carry-forward, and the orchestration's use of the focused path), NOT
   prompt-internal string assertions for their own sake:

     - The focused prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC: it does the ONE
       transform-authoring job (translate the model-spec into a sample-validated
       per-row transform) and carries NONE of the retired mega-prompt's profiling
       / modeling / scope-DECISION guidance, and NO industry knowledge
       (discipline 12). It defers grain/scope DECISIONS to the prior nodes.
     - The DT3 carry-forward — :grain-strategy value-shape variance (bare keyword
       OR its string form) — is normalized by `normalize-grain-strategy`, so the
       transform-authoring reads the DECISION regardless of which shape arrived.
     - The CAPTURED REAL transform (the VERBATIM transform-source the node
       authored in the DT4 live verify on the real breakdown-heavy IPEDS
       completions table under a Louisiana-scoped goal — see
       docs/build-timeline/live-verify/DT4-transform.md) honors grain + scope:
       it keys the concept :uri from the model-spec's uri-keying-fields and emits
       per-row drafts whose shape the V20 apply-step + compile path accept.
     - The Transform node runs the FOCUSED path (:focused-prompt? true) and reads
       its predecessor's model-spec as the :model-spec inter-node input.

   Discipline #4: the REAL-LLM + full-scale proof is the DT4-transform live verify
   (the authored transform applied over the FULL source yields a SANE concept
   count honoring grain + scope — not a raw-row dump). These tests pin the focused
   contract + the carry-forward normalization + the focused-path wiring
   deterministically so a regression is caught fast. The captured-real transform
   below is the VERBATIM node output from that live run — no invention."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]))

;; =============================================================================
;; The grain-strategy carry-forward (DT3 value-shape variance) — normalization
;; =============================================================================

(deftest grain-strategy-normalization-tolerates-the-dt3-value-shape-variance
  (testing "the DT3 live verify documented :grain-strategy arriving as a bare
            keyword on some runs and as its STRING form on others; DT4 MUST
            normalize both to the frozen-enum keyword before branching on it"
    ;; both value-shapes map to the same enum keyword
    (is (= :canonical-row-filter (dt/normalize-grain-strategy :canonical-row-filter)))
    (is (= :canonical-row-filter (dt/normalize-grain-strategy ":canonical-row-filter")))
    (is (= :breakdown-as-entity (dt/normalize-grain-strategy :breakdown-as-entity)))
    (is (= :breakdown-as-entity (dt/normalize-grain-strategy ":breakdown-as-entity")))
    ;; a symbol form (model occasionally drops the colon) also normalizes
    (is (= :canonical-row-filter (dt/normalize-grain-strategy 'canonical-row-filter)))
    ;; whitespace-padded string form
    (is (= :breakdown-as-entity (dt/normalize-grain-strategy "  :breakdown-as-entity  ")))
    ;; the result is ALWAYS one of the frozen enum members (or nil)
    (doseq [v [:canonical-row-filter ":canonical-row-filter"
               :breakdown-as-entity ":breakdown-as-entity"]]
      (is (contains? dt/valid-grain-strategies (dt/normalize-grain-strategy v))
          (str "normalized " (pr-str v) " is a frozen grain-strategy"))))

  (testing "a value that is NOT one of the two frozen strategies normalizes to nil
            (a consumer can detect an unreadable decision rather than force-fit)"
    (is (nil? (dt/normalize-grain-strategy "nonsense")))
    (is (nil? (dt/normalize-grain-strategy nil)))
    (is (nil? (dt/normalize-grain-strategy "keep one canonical row per program")))))

;; =============================================================================
;; CAPTURED REAL transform (VERBATIM from the DT4 live verify — no invention).
;; See docs/build-timeline/live-verify/DT4-transform.md.
;; =============================================================================

(def ^:private captured-sql-transform-source
  "VERBATIM Transform-node output (the :transform-source string) for the real
   breakdown-heavy IPEDS completions table C2022_A (DT4 live verify, run 3), under a
   Louisiana-scoped goal, reading the DT3 model-spec (canonical-row-filter grain + a
   Louisiana scope). NO invention — this is exactly what the node emitted.

   It demonstrates BOTH halves of the DT4 live-verify finding (see
   docs/build-timeline/live-verify/DT4-transform.md):
     - STRUCTURE is correct: a pure (fn [row] ...) returning the contract shape; a
       SCOPE test that returns EMPTY drafts out of scope; a canonical-row GRAIN test
       (MAJORNUM=1) that collapses sub-rows to one program concept; a :uri keyed
       from identifying fields; demographic measures folded into :attributes.
     - FIELD GROUNDING was wrong: it accesses (get row \"unitid\") (string,
       lowercase) where the REAL C2022_A row key is the keyword :UNITID — so every
       row silently fell out of scope (the honest model-quality negative). The DT4
       NODE mechanics (apply-step + grain + scope) are proven correct at full scale
       by the corrected-field runs in the live-verify capture; the negative is the
       model not grounding field access in the real sampled rows."
  "(fn [row]\n  (let [la-ids #{\"158955\" \"159392\" \"160631\"}\n        unitid (get row \"unitid\")\n        ;; Scope filter: Louisiana institutions only\n        in-scope? (contains? la-ids (str unitid))\n        ;; Grain logic: :canonical-row-filter usually keeps majornum=1\n        ;; majornum=1 represents the primary major for a program\n        is-canonical? (= (str (get row \"majornum\")) \"1\")]\n    (if (and in-scope? is-canonical?)\n      (let [cip (get row \"cipcode\")\n            awlevel (get row \"awlevel\")\n            count (get row \"total_completions\")\n            uri (str \"program:\" unitid \":\" cip \":\" awlevel)\n            label (str \"Program \" cip \" (Level \" awlevel \") at Institution \" unitid)]\n        {:concept-drafts [{:uri uri\n                           :label label\n                           :evidence [(str \"Unit: \" unitid \", CIP: \" cip \", Awards: \" count)]\n                           :attributes {:total_completions count\n                                       :cipcode cip\n                                       :awlevel awlevel}}]\n         :relationship-drafts []})\n      {:concept-drafts []\n       :relationship-drafts []})))")

(deftest captured-real-transform-is-a-valid-grain+scope-enforcing-fn
  (testing "the captured-real node output is a pure (fn [row] ...) whose STRUCTURE
            enforces grain + scope: it returns the contract draft shape, returns
            EMPTY drafts for out-of-scope rows, applies a canonical-row grain test,
            and keys the :uri from identifying fields"
    (let [f (eval (read-string captured-sql-transform-source))
          ;; Evaluating the captured source (its own assumed key shape) over a row
          ;; in its own assumed shape produces a concept; over a non-matching row,
          ;; empty drafts — i.e. the SCOPE+GRAIN branching is real, not a stub.
          in-scope-row {"unitid" "158955" "majornum" "1" "cipcode" "01"
                        "awlevel" "5" "total_completions" "7"}
          out-of-scope-row {"unitid" "999999" "majornum" "1" "cipcode" "01"
                            "awlevel" "5" "total_completions" "7"}
          breakdown-row {"unitid" "158955" "majornum" "2" "cipcode" "01"
                         "awlevel" "5" "total_completions" "3"}
          r-in (f in-scope-row)
          r-out (f out-of-scope-row)
          r-bd (f breakdown-row)]
      ;; contract shape
      (is (map? r-in))
      (is (sequential? (:concept-drafts r-in)))
      (is (sequential? (:relationship-drafts r-in)))
      ;; SCOPE took effect: in-scope row produces a concept; out-of-scope is empty
      (is (seq (:concept-drafts r-in)) "in-scope canonical row yields a concept")
      (is (empty? (:concept-drafts r-out)) "out-of-scope row yields EMPTY drafts (scope enforced)")
      ;; GRAIN took effect: the breakdown (non-canonical) row yields empty drafts
      (is (empty? (:concept-drafts r-bd)) "breakdown (MAJORNUM!=1) row yields EMPTY drafts (canonical-row grain enforced)")
      ;; URI keyed from identifying fields + compile-required keys present
      (doseq [c (:concept-drafts r-in)]
        (is (some? (:uri c)) "concept carries :uri (keyed from identifying fields)")
        (is (some? (:label c)) "concept carries :label")
        (is (re-find #"158955" (str (:uri c))) ":uri is keyed by the identifying value")))))

;; =============================================================================
;; The focused Transform prompt is SINGLE-PURPOSE + DOMAIN-AGNOSTIC
;; =============================================================================

(def ^:private domain-goal
  "Build an ontology of programs of study for Louisiana students.")

(deftest transform-prompt-is-single-purpose
  (testing "the focused Transform prompt does ONLY transform-authoring and carries
            NONE of the prior nodes' jobs (re-profile / re-model / re-decide
            grain+scope) — it ENFORCES decisions already made"
    (let [p (str/lower-case (dt/transform-node-prompt domain-goal))]
      ;; It IS the transform-authoring step.
      (is (str/includes? p "transform"))
      (is (str/includes? p "(fn [row]"))
      (is (str/includes? p ":transform-source"))
      ;; It reads the model-spec (its predecessor's output) — does NOT re-model.
      (is (str/includes? p ":model-spec"))
      (is (str/includes? p "do not re-model")
          "explicitly told NOT to re-model")
      ;; It ENFORCES grain + scope (the model-spec decided them) — it does not
      ;; DECIDE them.
      (is (str/includes? p "grain"))
      (is (str/includes? p "scope"))
      (is (str/includes? p "canonical-row-filter"))
      (is (str/includes? p "breakdown-as-entity"))
      (is (str/includes? p "empty drafts")
          "told to return EMPTY drafts for out-of-scope / breakdown rows")
      (is (str/includes? p "uri-keying-fields")
          "keys the concept URI from the model-spec's identifying fields")
      ;; It must VALIDATE on a sample before scale (the node's name + job).
      (is (str/includes? p "validate"))
      (is (str/includes? p "sample"))
      ;; It is told the DT3 grain-strategy carry-forward: normalize the value shape.
      (is (str/includes? p "normalize"))
      (is (str/includes? p "string form"))
      ;; It is told the sandbox constraint (pure fn, resolve cross-table scope at
      ;; authoring time, no tool calls inside the fn).
      (is (str/includes? p "sandbox"))
      (is (str/includes? p "no tool calls"))))

  (testing "the focused Transform prompt is small (a single-purpose prompt, not the
            multi-concern mega-prompt)"
    (is (< (count (dt/transform-node-prompt domain-goal)) 6000)
        "the focused transform prompt is small")))

(deftest transform-prompt-is-domain-agnostic
  (testing "the prompt carries NO industry/vertical knowledge (discipline 12) —
            the only domain reference is the runtime goal the caller passed; the
            prompt body itself names no CIP/SOC/IPEDS/education concepts.
            Mirrors DT2/DT3."
    ;; Render with a NEUTRAL goal so any domain term would have to come from the
    ;; prompt BODY, not the goal.
    (let [p (str/lower-case (dt/transform-node-prompt "Author the transform for this dataset."))]
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings" "louisiana"
                    "fips" "stabbr" "demographic" "unitid"]]
        (is (not (str/includes? p term))
            (str "the focused transform prompt body must not bake in the term: " term))))))

;; =============================================================================
;; DT4-grounding — the prompt surfaces the REAL sampled-row key shape so the
;; model grounds field access verbatim (the honest-negative fix). The key shape
;; is computed MECHANICALLY from a real sample (no hardcoded domain field names)
;; and injected per-format: SQL/excel rows have KEYWORD keys, CSV rows STRING
;; keys. See docs/build-timeline/live-verify/DT4-grounding.md.
;; =============================================================================

(deftest key-shape-block-is-empty-without-a-sample
  (testing "with NO key-shape the prompt renders nothing extra (back-compat) —
            the grounding block only appears when a real sample is surfaced, so
            the domain-agnostic guarantee (rendered with no key-shape) holds"
    (is (= "" (dt/key-shape-block nil)))
    (is (= "" (dt/key-shape-block {})))))

(deftest transform-prompt-surfaces-exact-keyword-keys-for-sql
  (testing "given a real SQL sample (KEYWORD keys), the prompt names the EXACT
            keys verbatim and the keyword access idiom — the model can copy them
            instead of guessing/renaming/case-folding"
    (let [;; the EXACT shape sql sample-rows / query return: keyword keys.
          key-shape (dt/sample-row-key-shape
                     {:type :sql}
                     [{:UNITID 158662 :CIPCODE "01.0000" :MAJORNUM 1}])
          block (dt/key-shape-block key-shape)
          full (dt/transform-node-prompt "Build the ontology." key-shape)]
      (is (= :keyword (:key-type key-shape)))
      ;; the exact keys appear VERBATIM (correct case, keyword form)
      (is (str/includes? block ":UNITID"))
      (is (str/includes? block ":CIPCODE"))
      (is (str/includes? block ":MAJORNUM"))
      ;; the keyword access idiom is shown
      (is (str/includes? block "(:UNITID row)"))
      ;; a hard do-not-invent instruction is present
      (is (str/includes? (str/lower-case block) "verbatim"))
      (is (str/includes? (str/lower-case block) "do not"))
      ;; and the whole prompt carries it
      (is (str/includes? full ":UNITID")))))

(deftest transform-prompt-surfaces-exact-string-keys-for-csv
  (testing "given a real CSV sample (STRING keys), the prompt names the EXACT
            header keys verbatim and the STRING access idiom — the model can copy
            them instead of inventing a keyword variant (the run-4 negative)"
    (let [;; the EXACT shape csv sample-rows returns: string keys (zipmap header).
          key-shape (dt/sample-row-key-shape
                     {:type :csv}
                     [{"CIP_Code" "01.0000" "SOC_Code" "19-1011"}])
          block (dt/key-shape-block key-shape)
          full (dt/transform-node-prompt "Build the ontology." key-shape)]
      (is (= :string (:key-type key-shape)))
      ;; the exact header keys appear VERBATIM as STRINGS
      (is (str/includes? block "\"CIP_Code\""))
      (is (str/includes? block "\"SOC_Code\""))
      ;; the STRING access idiom is shown
      (is (str/includes? block "(get row \"CIP_Code\")"))
      (is (str/includes? full "\"CIP_Code\"")))))

(deftest sample-validation-rejects-an-empty-yield-transform
  (testing "a structurally-correct but MIS-GROUNDED transform (accesses the wrong
            key shape so EVERY in-scope sample row yields empty) is REJECTED at
            authoring time — caught here, not at full-scale apply (the false-empty
            failure mode from the honest negative)"
    ;; The real sample rows have KEYWORD keys (:UNITID); the transform accesses a
    ;; lowercase STRING key — exactly the run-3 mis-grounding — so it yields empty
    ;; for every row.
    (let [sample-rows [{:UNITID "158662" :CIPCODE "01" :MAJORNUM 1}
                       {:UNITID "158663" :CIPCODE "02" :MAJORNUM 1}]
          mis-grounded "(fn [row] (let [u (get row \"unitid\")] (if u {:concept-drafts [{:uri (str \"p:\" u) :label u}] :relationship-drafts []} {:concept-drafts [] :relationship-drafts []})))"
          v (dt/validate-transform-on-sample mis-grounded sample-rows)]
      (is (= :rejected (:status v)))
      (is (str/includes? (str/lower-case (:reason v)) "empty"))
      (is (zero? (:concept-yield v))
          "no concept-drafts came back from any sample row (the false-empty)"))))

(deftest sample-validation-accepts-a-grounded-transform
  (testing "a transform whose field access is GROUNDED in the real sample key
            shape (keyword keys) yields non-empty drafts on at least one sample
            row and is ACCEPTED"
    (let [sample-rows [{:UNITID "158662" :CIPCODE "01" :MAJORNUM 1}
                       {:UNITID "158663" :CIPCODE "02" :MAJORNUM 1}]
          grounded "(fn [row] (let [u (:UNITID row)] (if u {:concept-drafts [{:uri (str \"p:\" u) :label (str u)}] :relationship-drafts []} {:concept-drafts [] :relationship-drafts []})))"
          v (dt/validate-transform-on-sample grounded sample-rows)]
      (is (= :ok (:status v)))
      (is (pos? (:concept-yield v))
          "at least one sample row produced a concept-draft (grounded access)"))))

(deftest sample-validation-rejects-an-unevaluable-transform
  (testing "a transform that does not evaluate to a fn is rejected honestly (no
            false green) rather than silently passing"
    (let [v (dt/validate-transform-on-sample "(+ 1 2)" [{:UNITID 1}])]
      (is (= :rejected (:status v)))
      (is (some? (:reason v))))))

;; =============================================================================
;; The Transform node runs the FOCUSED path + reads the model-spec
;; =============================================================================

(def ^:private model-spec-for-wiring
  "A model-spec (the frozen DT3 contract) the Transform node reads as its
   :model-spec inter-node input. Grain-strategy as the STRING form on purpose —
   the wiring must thread it verbatim; the node normalizes it."
  {:entity-types
   [{:type "Program"
     :uri-keying-fields ["UNITID" "CIPCODE"]
     :grain-strategy ":canonical-row-filter"}]
   :scope-filter {:field "FIPS" :values ["22"]}
   :edges []})

(def ^:private a-transform-output
  "A transform-node output (the frozen transform contract) for the wiring test."
  {:transform-source
   "(fn [row] {:concept-drafts [{:uri (str \"prog:\" (:UNITID row) \"-\" (:CIPCODE row)) :label (str (:CIPCODE row)) :evidence [(pr-str row)]}] :relationship-drafts []})"
   :selector "C2022_A"})

(deftest transform-node-runs-focused-path-and-reads-the-model-spec
  (testing "the orchestration runs the Transform node through the FOCUSED path
            (:focused-prompt? true so the mega-prompt is NOT prepended), threads
            the predecessor MODEL-SPEC as the :model-spec inter-node input, and the
            emitted transform matches the frozen contract. We stub run-node-session!
            to assert the wiring + return a transform; the V20 apply-step +
            build! are stubbed away (their full-scale behavior is the live verify)."
    (let [seen (atom {})]
      (with-redefs [rlm-discovery/run-node-session!
                    (fn [_ctx {:keys [node-name instruction focused-prompt?
                                      extra-inputs] :as params}]
                      (swap! seen assoc node-name params)
                      (case node-name
                        :profile {:status :ok :output {:entity-candidates "x"}}
                        :model   {:status :ok :output model-spec-for-wiring}
                        :transform
                        (do
                          ;; the focused path is taken for the Transform node
                          (is (true? focused-prompt?)
                              "Transform node runs with :focused-prompt? true")
                          ;; the focused transform prompt body is used
                          (is (str/includes? (str/lower-case instruction)
                                             "transform step"))
                          ;; the predecessor model-spec is the inter-node input
                          (is (= model-spec-for-wiring (:model-spec extra-inputs))
                              "the model-spec is threaded to the Transform node")
                          {:status :ok :output a-transform-output})
                        {:status :failed :error "unexpected node"}))
                    ;; Stub the V20 apply-step + compile + build! so the test pins
                    ;; the NODE wiring (the full-scale apply is the live verify).
                    rlm-discovery/apply-extraction-transform!
                    (fn [{:keys [transform-source selector]}]
                      ;; the orchestration passed the node's authored transform +
                      ;; selector verbatim into the V20 apply-step (no fork)
                      (is (= (:transform-source a-transform-output) transform-source)
                          "the authored transform-source is handed to the V20 apply-step verbatim")
                      (is (= "C2022_A" selector)
                          "the model-spec/node selector is handed to the apply-step")
                      {:selector selector :rows-streamed 3 :rows-ok 3 :rows-errored 0
                       :windows 1 :errors-sample []
                       :concept-drafts [{:uri "prog:1-01" :label "01" :evidence ["r"]}]
                       :relationship-drafts []})
                    rlm-discovery/compile-discovery-source!
                    (fn [_ctx _oid _out] {:discovery-provenance {}})]
        (with-redefs [ai.obney.orc.ontology.core.deterministic-skeleton/build!
                      (fn [_ctx _params]
                        {:status :complete :concepts-count 1 :relationships-count 0
                         :graph-health {} :exit-criterion {}})]
          (let [result (dt/run-discovery-tree!
                        {} {:ontology-id (random-uuid)
                            :source {:name :ipeds :type :sql
                                     :path "/tmp/does-not-matter.db"}
                            :goal domain-goal})]
            ;; The Transform node ran (after profile + model).
            (is (some? (:transform @seen)) "the Transform node was invoked")
            (is (= :transform (:node-name (:transform @seen))))
            ;; Its emitted transform is on the blackboard as the frozen contract.
            (is (= a-transform-output (dt/node-output (:blackboard result) :transform))
                "the Transform node's emitted transform (frozen contract) is on the blackboard")
            (is (= (set dt/transform-contract-keys)
                   (set (keys (dt/node-output (:blackboard result) :transform))))
                "the transform output carries exactly the frozen transform-contract keys")
            ;; The run flowed through the V20 apply-step + reached build!.
            (is (= :complete (:status result))
                "the run reached build! (the V20 apply-step + compile + build! ran)")))))))

;; =============================================================================
;; The authored transform shape is V20-apply + compile compatible
;; =============================================================================
;; A transform the node authors must produce drafts the V20 apply-step accepts
;; (a map with sequential :concept-drafts / :relationship-drafts) AND that the
;; compile path accepts (every concept-draft carries :uri + :label). We eval the
;; wiring transform through the REAL V20 apply-step over a tiny in-memory row set
;; would require a source; instead we assert the V20 contract on the transform's
;; per-row output shape directly (the apply-step itself is V20-proven).

(deftest authored-transform-produces-v20-compatible-per-row-drafts
  (testing "a per-row transform of the shape the focused prompt demands produces a
            map with sequential drafts (V20 apply-step accepts) and every
            concept-draft carries :uri + :label (compile path accepts)"
    ;; Evaluate the wiring transform-source the same way the V20 apply-step does
    ;; (a plain reader/eval is sufficient to assert the OUTPUT shape; the sandbox
    ;; eval is exercised in the live verify).
    (let [f (eval (read-string (:transform-source a-transform-output)))
          out (f {:UNITID 101 :CIPCODE "01"})]
      (is (map? out) "per-row result is a map")
      (is (sequential? (:concept-drafts out)) ":concept-drafts is sequential")
      (is (sequential? (:relationship-drafts out)) ":relationship-drafts is sequential")
      (doseq [c (:concept-drafts out)]
        (is (some? (:uri c)) "every concept-draft carries :uri (compile requires it)")
        (is (some? (:label c)) "every concept-draft carries :label (compile requires it)")))))
