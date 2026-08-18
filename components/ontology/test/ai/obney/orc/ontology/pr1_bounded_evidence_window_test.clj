(ns ai.obney.orc.ontology.pr1-bounded-evidence-window-test
  "PR-1 (ADR 0030, spec invariant BoundedReflectionEvidence) — the
   consolidator's evidence window is a token-budgeted, newest-first PURE
   selection.

   OBLIGATION PROVENANCE, stated rather than implied: contract-level
   invariants derive ZERO obligations from `allium plan` (known generator
   blindness, SIO-4b finding), so these tests are seeded from CC-21's banked
   measurements (evidence/cc21/CC21-MEASUREMENT.md, raw/anchors.edn — copied
   verbatim into fixtures/cc21_provider_anchors.edn) and ADR 0030, NOT from
   /propagate.

   The banked predictor (CC-21 §1, 10 real provider anchors):
     prompt chars ~ window EDN chars + 9,000     (±1.6% across all 10)
     per-target-type density 2.0–4.0 chars/token (the 2x spread that makes
                                                  an event-count bound wrong)

   Cycles:
     1. newest-first within budget, deterministic
     2. dense vs sparse — same event count, one over budget, one under
        (the event-count-bound refutation)
     3. the predictor pin (±1.6% envelope over the 10 real anchors +
        prompt-assembly drift pins)
     4. budget from event-sourced config with a DERIVED default
     5. regression — small windows select everything, byte-identical to the
        pre-PR-1 gather output"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.test-helpers :as th]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Banked fixture — the 10 REAL provider anchors from CC-21 (verbatim copy of
;; evidence/cc21/raw/anchors.edn). :chars is the window EDN chars, and
;; :provider-prompt-chars / :provider-prompt-tokens are what the provider
;; actually measured for the rendered reflection prompt.
;; =============================================================================

(def ^:private anchors
  (delay (edn/read-string
           (slurp (io/resource
                    "ai/obney/orc/ontology/fixtures/cc21_provider_anchors.edn")))))

(def ^:private anchor-target-type
  "Which granularity each P-E arm consolidated at (cc21_measure.clj:115-130):
   A/B/C are :node-instance targets, E is :node-type :repl-researcher."
  {"A" :node-instance "B" :node-instance "C" :node-instance "E" :node-type})

;; =============================================================================
;; Production-faithful window fixtures. Disjoint identifiers per domain (the
;; SJ-1 lesson): sheet/tick/node uuids are all distinct, and the dense and
;; sparse windows share NOTHING.
;; =============================================================================

(defn- uuid-n [prefix n]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str prefix "-" n) "UTF-8")))

(defn- sparse-observation
  "A plain node-execution observation as gather-recent-events shapes it after
   clean-event-for-llm + the CC-21b projection: shape-not-values, ~550-600
   chars pr-str — the corpus p50 is 568 B/obs (CC-21 §3)."
  [n]
  {:event/type :sheet/node-execution-completed
   :sheet-id (uuid-n "pr1-sparse-sheet" n)
   :tick-id (uuid-n "pr1-sparse-tick" n)
   :node-id (uuid-n "pr1-sparse-node" n)
   :node-type :pr1-sparse-node-kind
   :status :success
   :completion-kind :terminal
   :duration-ms (+ 900 n)
   :read-keys [:user-message :tool-context]
   :input-profile {:user-message {:kind :string :chars 240}
                   :tool-context {:kind :map :keys 3}}
   :write-keys [:answer]
   :write-profile {:answer {:kind :string :chars 310}}
   :usage {:prompt-tokens (+ 1200 n) :completion-tokens (+ 140 n)}
   :timestamp (str "2026-08-0" (inc (mod n 9)) "T10:0" (mod n 10) ":00Z")})

(defn- dense-observation
  "A :tree-class join-path observation (gather-recent-tree-class-events
   output shape). CC-21 §3 measured this window at ~8,000 B/obs with
   :top-candidates carrying 59.4% of the payload — mirrored here: ten
   reranked candidates with per-candidate reasoning strings."
  [n]
  (let [cand (fn [i]
               {:tree-id (uuid-n (str "pr1-dense-cand-" n) i)
                :label (str "candidate-tree-" i "-for-obs-" n)
                :score (/ (double (+ 40 i)) 100.0)
                :axis :structural
                :rerank-source :colbert
                :reasoning (apply str
                                  "Candidate " i " matches the task signature on its "
                                  "retrieval axis; structural overlap on the plan/act/verify "
                                  "loop with bounded tool budget and a repair arm; "
                                  (repeat 24 "score evidence detail segment; "))})]
    {:event/type :ontology/task-classified
     :assigned-tree-id (uuid-n "pr1-dense-class" 0)
     :source-sheet-id (uuid-n "pr1-dense-sheet" n)
     :source-tick-id (uuid-n "pr1-dense-tick" n)
     :confidence 0.87
     :was-fresh-mint? false
     :reasoning (apply str "Assigned because the task signature names the same "
                       "verbs and object domain as the class exemplars; "
                       (repeat 12 "classification rationale segment; "))
     :top-candidates (mapv cand (range 10))
     :behavioral-subtrees (mapv (fn [i] {:behavior-id (uuid-n (str "pr1-dense-beh-" n) i)
                                         :label (str "behavior-" i)
                                         :score 0.71})
                                (range 3))
     :execution {:status :success
                 :tree-fingerprint (str "pr1-dense-fp-" n)
                 :duration-ms (+ 20000 (* n 13))
                 :usage {:prompt-tokens 52000 :completion-tokens 900}
                 :trajectory (mapv (fn [i] {:event-type :node-execution-completed
                                            :status :success})
                                   (range 6))}
     :judge-scores [{:judge-name "grounding"
                     :judge-config {:type :grounding}
                     :score 0.83
                     :feedback (apply str "The answer cites the retrieved evidence "
                                      "for each claim it advances; "
                                      (repeat 10 "judge feedback segment; "))
                     :dimensions []
                     :emitted-at (str "2026-08-0" (inc (mod n 9)) "T11:00:00Z")}]
     :timestamp (str "2026-08-0" (inc (mod n 9)) "T10:5" (mod n 10) ":00Z")}))

;; =============================================================================
;; Cycle 1 — newest-first within budget, deterministic
;; =============================================================================

(deftest selection-is-newest-first-within-budget-and-deterministic
  (testing "select-evidence-window keeps the newest contiguous suffix whose predicted prompt tokens fit the budget; older events are excluded; the split is deterministic"
    (let [events (mapv sparse-observation (range 40))          ; chronological, newest LAST
          constants (consolidator/evidence-density-constants :node-type)
          ;; A budget that cuts mid-window: 40 sparse obs ≈ 40×~570 chars
          ;; + 9,000 overhead ≈ 31.8K chars ≈ 16K tokens at 1.99 — budget
          ;; 10,000 tokens forces a partial selection.
          budget 10000
          {:keys [selected excluded predicted-prompt-tokens] :as r}
          (consolidator/select-evidence-window events constants budget)]
      (is (seq selected) "a mid-window budget selects a non-empty suffix")
      (is (seq excluded) "a mid-window budget excludes the oldest events")
      (is (= events (into excluded selected))
          "excluded ++ selected partitions the window in chronological order — newest-first selection means the OLDEST events are the excluded ones")
      (is (<= predicted-prompt-tokens budget)
          "the selected window's predicted prompt tokens respect the budget")
      ;; Tightness probe: measure the augmented window under an UNBOUNDED
      ;; budget — probing with the same budget would just re-truncate it and
      ;; report the truncated window's tokens (the first RED of this cycle
      ;; caught exactly that harness mistake: 9777 <= 10000).
      (is (> (:predicted-prompt-tokens
               (consolidator/select-evidence-window
                 (into [(peek excluded)] selected) constants Long/MAX_VALUE))
             budget)
          "tightness: re-adding the newest excluded event overflows the budget — nothing was dropped gratuitously")
      (is (= r (consolidator/select-evidence-window events constants budget))
          "pure and deterministic: same inputs, same split"))))

(deftest under-budget-window-selects-everything
  (testing "a window whose whole prediction fits the budget is selected in full, nothing excluded"
    (let [events (mapv sparse-observation (range 5))
          constants (consolidator/evidence-density-constants :node-type)
          {:keys [selected excluded]} (consolidator/select-evidence-window
                                        events constants 100000)]
      (is (= events selected))
      (is (= [] excluded)))))

;; =============================================================================
;; Cycle 2 — dense vs sparse at the SAME event count: the event-count-bound
;; refutation. ADR 0030: "the 2x density spread means one event count either
;; wastes headroom or still times out."
;; =============================================================================

(deftest same-event-count-dense-overflows-sparse-fits
  (testing "20 dense (tree-class join-path, ~8KB/obs) events overflow a budget that 20 sparse (~570B/obs) events fit with room — an event-count bound cannot be right for both"
    (let [n 20
          dense (mapv dense-observation (range n))
          sparse (mapv sparse-observation (range n))
          budget 30000
          dense-r (consolidator/select-evidence-window
                    dense (consolidator/evidence-density-constants :tree-class) budget)
          sparse-r (consolidator/select-evidence-window
                     sparse (consolidator/evidence-density-constants :node-type) budget)]
      ;; sanity: the dense fixture really is dense (~8KB/obs, CC-21 §3)
      (is (> (count (pr-str (first dense))) 6000)
          "dense fixture observation carries a production-scale payload")
      (is (< (count (pr-str (first sparse))) 800)
          "sparse fixture observation is near the corpus p50")
      (is (seq (:excluded dense-r))
          "DENSE: the same 20 events do NOT fit — a count bound of 20 would still time out here")
      (is (= [] (:excluded sparse-r))
          "SPARSE: all 20 fit with headroom — a count bound sized for the dense window would waste this target's evidence")
      (is (= n (count (:selected sparse-r))))
      (is (< (count (:selected dense-r)) n)))))

(deftest per-type-density-changes-the-prediction
  (testing "the SAME byte payload predicts more tokens under the dense (:node-type, repl-researcher-measured 1.99 chars/token) constant than under the sparse-side (:tree-class 3.47) constant — selection is per-type density aware"
    (let [events (mapv sparse-observation (range 30))
          dense-const (consolidator/evidence-density-constants :node-type)
          sparse-const (consolidator/evidence-density-constants :tree-class)
          t-dense (:predicted-prompt-tokens
                    (consolidator/select-evidence-window events dense-const 1000000))
          t-sparse (:predicted-prompt-tokens
                     (consolidator/select-evidence-window events sparse-const 1000000))]
      (is (< t-sparse t-dense)
          "denser chars/token constant predicts MORE tokens for identical chars")
      (is (< 1.5 (/ (double t-dense) (double t-sparse)))
          "the banked ~2x spread is what separates the predictions"))))

;; =============================================================================
;; Cycle 3 — the CC-21 predictor PIN. If prompt assembly drifts, this test
;; goes red and the ±1.6% envelope must be RE-MEASURED (ADR 0030), not edited
;; back to green.
;; =============================================================================

(deftest cc21-predictor-envelope-holds-on-all-banked-anchors
  (testing "prompt chars = window EDN chars + 9,000 within ±1.6% (relative to the prediction) on all 10 real provider anchors"
    (let [overhead @#'consolidator/evidence-prompt-overhead-chars
          envelope @#'consolidator/evidence-predictor-envelope]
      (is (= 9000 overhead) "the banked CC-21 overhead constant")
      (is (= 0.016 envelope) "the banked CC-21 envelope")
      (doseq [{:keys [key w chars provider-prompt-chars]} @anchors]
        (let [predicted (+ chars overhead)
              rel (/ (Math/abs (double (- provider-prompt-chars predicted)))
                     (double predicted))]
          (is (<= rel envelope)
              (str "anchor " key " w=" w " drifted outside the ±1.6% envelope: "
                   "predicted " predicted " vs measured " provider-prompt-chars
                   " (" (format "%.4f" rel) ")")))))))

(deftest density-constants-derive-from-the-banked-anchors
  (testing "per-type chars/token constants are the conservative (floor-to-2-decimals MINIMUM) provider-measured densities, never invented"
    (let [floor2 (fn [x] (/ (Math/floor (* 100.0 x)) 100.0))
          density (fn [{:keys [provider-prompt-chars provider-prompt-tokens]}]
                    (/ (double provider-prompt-chars)
                       (double provider-prompt-tokens)))
          by-type (group-by #(anchor-target-type (:key %)) @anchors)
          min-density (fn [tt] (floor2 (apply min (map density (by-type tt)))))
          table @#'consolidator/evidence-density-chars-per-token]
      (is (= (min-density :node-type) (:node-type table))
          "node-type density = the E (repl-researcher) anchor floor, ~1.99")
      (is (= (min-density :node-instance) (:node-instance table))
          "node-instance density = the A/B/C anchor floor, ~3.17")
      ;; :tree-class has NO provider anchor; its constant is CC-21 §3's
      ;; fitted estimate for the join-path window (1,183,844 B -> 340,207
      ;; est tokens), floored: weaker provenance, stated not hidden.
      (is (= (floor2 (/ 1183844.0 340207.0)) (:tree-class table)))
      ;; :tree-fingerprint has NO measurement at all (CC-21 adjacent defect
      ;; B: 0 events carry the target) -> the global measured floor.
      (is (= (min-density :node-type) (:tree-fingerprint table))))))

(deftest prompt-assembly-drift-is-loud
  (testing "the instruction texts the +9,000-char overhead was measured under are pinned EXACTLY — editing either means the CC-21 envelope no longer describes production prompts and must be re-measured (then re-bank these lengths), never silently absorbed"
    (is (= @#'consolidator/banked-reflection-instruction-chars
           (count @#'consolidator/reflection-instruction))
        "reflection-instruction length drifted: re-measure the CC-21 envelope, then re-bank")
    (is (= @#'consolidator/banked-claim-reflection-instruction-chars
           (count @#'consolidator/claim-reflection-instruction))
        "claim-reflection-instruction length drifted: re-measure the CC-21 envelope, then re-bank")))

;; =============================================================================
;; Cycle 4 — budget from event-sourced config (the C-2a-3c seam pattern) with
;; a DERIVED default. The default's derivation INPUTS are asserted, not a
;; magic literal.
;; =============================================================================

(deftest default-budget-is-derived-not-invented
  (testing "default = min(provider-limit/2, largest clean anchor) and each input matches its banked source"
    (let [tokens (map :provider-prompt-tokens @anchors)
          ;; the smallest member of the measured FAILING class: E-w20, the
          ;; ~575-600K-token prompts that are timeout-dominated under the
          ;; 300s executor deadline (W39: 3/5 :timeout; W25 marker omission)
          failing-floor (apply max tokens)
          largest-clean (apply max (remove #(>= % failing-floor) tokens))]
      (is (= 1048576 rm/provider-context-token-limit)
          "the provider limit CC-21 proved verbatim from the rejection message")
      (is (= failing-floor rm/smallest-timeout-class-prompt-tokens)
          "the failing-class floor is the banked E-w20 anchor, not a guess")
      (is (= largest-clean rm/largest-clean-anchor-prompt-tokens)
          "the largest anchor below the failing class (A-w500) — accepted, answered, no recorded failure mode")
      (is (= (min (quot rm/provider-context-token-limit 2)
                  rm/largest-clean-anchor-prompt-tokens)
             rm/default-evidence-token-budget)
          "the default IS its derivation — min(half the context for prompt headroom under the chars/4 two-ceiling finding, the largest clean measured prompt)"))))

(deftest budget-comes-from-event-sourced-config
  (testing "get-evidence-token-budget returns the derived default until an :ontology/set-evidence-token-budget command overrides it (the C-2a-3c seam)"
    (th/with-test-context [ctx]
      (is (= rm/default-evidence-token-budget
             (ontology/get-evidence-token-budget ctx))
          "no config event -> the derived default")
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-evidence-token-budget
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :budget-tokens 50000}))
      (is (= 50000 (ontology/get-evidence-token-budget ctx))
          "the config event overrides the derived default"))))

;; =============================================================================
;; Cycle 5 — regression: a small window (under budget) selects EVERYTHING and
;; the gather output is byte-identical to the pre-PR-1 behaviour.
;; =============================================================================

(deftest degraded-context-falls-back-to-the-derived-default-loudly
  (testing "a cache-less context (the CC-21b test harness shape — :event-store + :tenant-id only) cannot reach the config read-model; gather must fall back to the DERIVED default budget instead of throwing (the CC-27 loud-fallback posture claims-for-inference already follows)"
    (th/with-test-context [full-ctx]
      (let [node-type :pr1-degraded-node
            events [(es/->event
                      {:type :sheet/node-execution-completed
                       :tags #{[:sheet (uuid-n "pr1-deg-sheet" 0)]}
                       :body {:sheet-id (uuid-n "pr1-deg-sheet" 0)
                              :tick-id (uuid-n "pr1-deg-tick" 0)
                              :node-id (uuid-n "pr1-deg-node" 0)
                              :node-type node-type
                              :status :success
                              :duration-ms 42}})]
            _ (es/append (:event-store full-ctx)
                         {:tenant-id (:tenant-id full-ctx) :events events})
            degraded-ctx {:event-store (:event-store full-ctx)
                          :tenant-id (:tenant-id full-ctx)}
            gather @#'consolidator/gather-recent-events
            window (gather degraded-ctx :node-type node-type)]
        (is (= 1 (count window))
            "the gather path still works without the read-model cache — bounded by the derived default")))))

(deftest small-windows-are-byte-identical-to-legacy-gather
  (testing "gather-recent-events on an under-budget target returns every cleaned observation in event-store order — exactly what take-last recent-window-size returned before PR-1"
    (th/with-test-context [ctx]
      (let [node-type :pr1-regression-node
            raw-events
            (mapv (fn [n]
                    (es/->event
                      {:type :sheet/node-execution-completed
                       :tags #{[:sheet (uuid-n "pr1-reg-sheet" n)]}
                       :body {:sheet-id (uuid-n "pr1-reg-sheet" n)
                              :tick-id (uuid-n "pr1-reg-tick" n)
                              :node-id (uuid-n "pr1-reg-node" n)
                              :node-type node-type
                              :status (if (zero? (mod n 3)) :failure :success)
                              :duration-ms (+ 50 n)
                              :inputs {:user-message (str "run the thing " n)}
                              :writes {:answer (str "did the thing " n)}}}))
                  (range 7))
            _ (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx) :events raw-events})
            gather @#'consolidator/gather-recent-events
            clean @#'consolidator/clean-event-for-llm
            window (gather ctx :node-type node-type)
            legacy (->> (es/read (:event-store ctx)
                                 {:types #{:sheet/node-execution-completed}
                                  :tenant-id (:tenant-id ctx)})
                        (into [])
                        (filter #(= node-type (:node-type %)))
                        (mapv clean))]
        (is (= 7 (count window)) "everything selected — the window is under budget")
        (is (= (pr-str legacy) (pr-str window))
            "byte-identical to the legacy (take-last recent-window-size) output for an under-budget window")))))
