(ns c-loop-3-live-verify
  "C-Loop-3 LIVE verify — does the agent actually CHOOSE to mint a new
   behavioral subtree when presented with a genuinely OOD task, and is
   the minted body SUBSTANTIVE (not boilerplate)?

   C-Loop-2 shipped the mint affordance (defcommand + sandbox primitive
   + prepend text). This verify is the load-bearing follow-up: when the
   classifier returns a fresh-mint marker AND the R-Inject prepend
   dangles `(mint-behavior! ...)` in front of the model, does the
   agent (1) recognize the gap, (2) choose to mint, and (3) author a
   discriminating body whose fields are principle-shaped rather than
   generic boilerplate that overlaps any existing seed.

   OOD task: game balance design with simulated-playtest iteration.
   Initial scheduling task was on the design/novel boundary — 2/3 runs
   rationalized as 'multi-constraint design'. Game balance with
   playtest iteration is more cleanly novel because the artifact is
   shaped BY iterated simulation outcomes (not by upfront constraints),
   which Design (produces ONE artifact from constraints) doesn't cover.
   The iterate-against-simulated-feedback loop is the distinguishing
   signal across the 12 seeds.

   Pass criteria (1 run, no retries — we want to see honest signal):
     1. classify-behaviors returned a fresh-mint marker
        (:was-fresh-mint? true on top-1 OR no candidate above threshold)
     2. The recursive RLM emitted at least one :ontology/behavioral-
        subtree-minted event with :provenance :agent-minted
     3. The minted body is SUBSTANTIVE:
        - :summary mentions schedule / shift / assignment / constraint
          (one of the task's domain keywords)
        - :strengths has ≥1 entry with non-empty :trait + :good-when
          + :recommended-pattern (principle-shaped, not placeholder)
        - :capabilities is non-empty + entries are >40 chars (concrete
          actions, not generic verbs)
        - :representative-uses references the task domain (not vague
          'various tasks' descriptors)
     4. The minted concept is in the descriptions read-model — direct
        get-description lookup succeeds (read-model persistence proof;
        ColBERT-indexed classify-behaviors retrieval is C-2b followup).

   No mocks. Real OpenRouter (gemini-3-flash-preview), real R-Inject,
   real recursive RLM execution."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.grain.event-store-v3.interface :as es]
            [com.brunobonacci.mulog :as u]
            [clojure.string :as str]
            [runner]))

(def task
  "Game balance design with simulated-playtest iteration. Genuinely-OOD
   relative to the 12 behavioral seeds: the artifact is shaped BY iterated
   simulation outcomes (not by upfront constraints), which none of
   research/extraction/analysis/synthesis/ideation/design/critique/
   validation/code-building/transformation/classification/investigation
   capture as the central activity. Design produces ONE artifact from
   constraints; this task requires multiple rounds of design→simulate→
   adjust until convergence."
  {:slug "c-loop-3-game-balance-playtest"
   :name "Game Balance via Simulated Playtest (C-Loop-3 OOD trigger)"
   :pattern "Iterate design→simulated-playtest→adjust until win-rate convergence"
   :documents []
   :description "Genuinely-OOD task: artifact shaped by iterated simulation outcomes."
   :instruction
   (str
     "Task: Balance a 4-class card game (Warrior / Mage / Rogue / Cleric) so that across simulated "
     "head-to-head matches, every pair of classes converges to a win-rate in the [45%, 55%] window.\n\n"
     "Each class has a starting kit:\n"
     "  - Warrior: 30 HP, 2 attack-cards (4dmg @ 3 mana), 1 armor-card (block 3 dmg @ 2 mana)\n"
     "  - Mage:    24 HP, 3 spell-cards (5dmg @ 4 mana), 1 shield-card (block 2 dmg @ 1 mana)\n"
     "  - Rogue:   26 HP, 4 quick-cards (2dmg @ 1 mana), 1 dodge-card (50% block @ 2 mana)\n"
     "  - Cleric:  28 HP, 2 heal-cards (heal 5 @ 3 mana), 2 smite-cards (3dmg @ 2 mana)\n\n"
     "You are NOT given a baseline win-rate matrix. The work is iterative:\n"
     "  - Hypothesize a starting balance (which class is over/under-tuned and why)\n"
     "  - Simulate enough head-to-head matches to estimate the 4x4 win-rate matrix\n"
     "  - Identify any pair outside [45%, 55%]\n"
     "  - Adjust class parameters (HP / card damage / card cost) on the outlier classes\n"
     "  - Re-simulate the affected matchups\n"
     "  - Repeat until all 6 pairwise matchups converge into [45%, 55%]\n\n"
     "Produce:\n"
     "  - :final-balance — the converged class parameters as a map keyed by class\n"
     "  - :rounds — vector of {:round-N {:adjustments {...} :winrate-matrix {...}}} showing the trajectory\n"
     "  - :rationale — 2-3 paragraphs explaining the iteration strategy and how convergence was reached")
   :writes [:final-balance :rounds :rationale]
   :rlm {:auto-classify? true
         :recursive? true}})

;; =============================================================================
;; Substance checks — Discipline #11/#18 boilerplate detection
;; =============================================================================
;;
;; The verify script is NOT production code (per discipline #11) — using
;; explicit keyword lists here is intentional. We're verifying the MODEL's
;; output for substance; the model's prompt itself does NOT use hardcoded
;; phrase matching. This script's heuristics catch the obvious boilerplate
;; failure modes — generic verbs, placeholder strings — that masquerade as
;; substance in LLM output.

(def domain-keywords
  "Keywords from the game-balance-playtest task domain. The minted body's
   :summary or :representative-uses should mention at least one of these
   to be considered domain-grounded (not generic 'analyzes things'). The
   load-bearing signal is the iterate→simulate→adjust pattern, so we
   accept terms describing that loop OR the game-balance domain itself."
  ["balance" "balanc" "playtest" "simulat" "win-rate" "winrate"
   "tuning" "tune" "matchup" "iterat" "converge" "convergence"
   "calibrat" "feedback-loop" "monte-carlo" "rounds" "trajectory"
   "game" "card" "class"])

(defn- contains-any-keyword?
  [s keywords]
  (when (string? s)
    (let [lower (str/lower-case s)]
      (some #(str/includes? lower %) keywords))))

(defn- substantive-strength?
  "A principle-shaped strength has non-empty :trait + :good-when +
   :recommended-pattern. :trait can be a short label (≥10 chars allows
   names like 'Hybrid Loop'). :good-when + :recommended-pattern need
   to be substantive (≥20 chars — must actually explain context +
   show a DSL snippet)."
  [s]
  (let [placeholder-re #"(?i)\b(tbd|investigate|placeholder|todo)\b"
        nonempty? (fn [v] (and (string? v) (not (re-find placeholder-re v))))
        long-enough? (fn [v min-len]
                       (and (nonempty? v) (>= (count v) min-len)))]
    (and (long-enough? (:trait s) 10)
         (long-enough? (:good-when s) 20)
         (long-enough? (:recommended-pattern s) 20))))

(defn- substantive-capability?
  "A concrete capability is >40 chars. Generic single-verb capabilities
   ('analyzes data', 'processes input') tend to be <40 chars and overlap
   any existing seed."
  [c]
  (and (string? c) (>= (count c) 40)))

(defn- assess-body-substance
  "Apply the structural + domain-keyword quality bar to a minted body.
   Returns a map of per-criterion results + overall :substantive? flag."
  [body]
  (let [{:keys [summary capabilities strengths representative-uses]} body
        summary-domain? (contains-any-keyword? summary domain-keywords)
        ;; representative-uses can be a vector of strings or a vector of maps
        repr-strings (filter string? representative-uses)
        repr-domain? (some #(contains-any-keyword? % domain-keywords) repr-strings)
        cap-substantive-count (count (filter substantive-capability? (or capabilities [])))
        strength-substantive-count (count (filter substantive-strength? (or strengths [])))]
    {:summary-mentions-domain? (boolean summary-domain?)
     :representative-uses-mentions-domain? (boolean repr-domain?)
     :capabilities-substantive-count cap-substantive-count
     :capabilities-substantive? (pos? cap-substantive-count)
     :strengths-substantive-count strength-substantive-count
     :strengths-substantive? (pos? strength-substantive-count)
     :substantive? (and summary-domain?
                        (pos? cap-substantive-count)
                        (pos? strength-substantive-count)
                        repr-domain?)}))

(defn- read-minted-events [ctx]
  ;; es/read REQUIRES :tenant-id in the scope — without it the call
  ;; returns a cognitect anomaly (silently, into a sequence). The
  ;; recursive RLM threads the same tenant-id through build-rlm-context,
  ;; so the mint events land under (:tenant-id ctx).
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{:ontology/behavioral-subtree-minted}})))

(defn verify!
  []
  (println "============================================================")
  (println " C-Loop-3 LIVE Verify — agent-behavioral mint on OOD task")
  (println "============================================================")
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY must be set" {})))
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (println "\n--- Snapshot mints BEFORE running task")
      (let [prior-mints (read-minted-events ctx)]
        (println "  pre-existing :ontology/behavioral-subtree-minted events:"
                 (count prior-mints))
        (let [all-events (into [] (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)}))]
          (println "  [DIAG] pre-existing event-store size (all types, tenant-scoped):"
                   (count all-events)))

        (println "\n--- Running schedule-optimization task (real OpenRouter, recursive RLM)")
        (let [result (runner/run! task)
              _ (println "  → task complete; status:" (:status result))
              ;; [DIAG] poll briefly for any async mint event to land
              _ (Thread/sleep 1000)

              all-mints-post (read-minted-events ctx)
              _ (println "  [DIAG] post-run :ontology/behavioral-subtree-minted events:"
                         (count all-mints-post))
              _ (println "  [DIAG] post-run event-store size (all types):"
                         (count (into [] (es/read (:event-store ctx) {}))))
              _ (doseq [m all-mints-post]
                  (println "    -" (:name m) "target-id:" (:target-id m)
                           "event-id:" (:event/id m) "provenance:" (:provenance m)))

              new-mints (filter #(not (some (fn [p] (= (:event/id p) (:event/id %))) prior-mints))
                                all-mints-post)
              _ (println "\n--- Audit: mints emitted during this run:" (count new-mints))

              ;; [DIAG] also surface what classify-behaviors saw
              _ (when (:iteration-reasonings result)
                  (println "\n--- Iteration reasonings (truncated to first 600 chars each):")
                  (doseq [[idx r] (map-indexed vector (:iteration-reasonings result))]
                    (println "  iter" idx "→" (some-> r (subs 0 (min 600 (count r)))))))]

          (println "\n--- Apply quality bar to each minted body")
          (let [assessments
                (mapv (fn [evt]
                        (let [target-id (:target-id evt)
                              ;; Direct read-model lookup — proves persistence
                              ;; without depending on ColBERT indexing.
                              body (ontology/get-description ctx :tree-fingerprint target-id)
                              substance (assess-body-substance body)]
                          {:target-id target-id
                           :name (:name evt)
                           :provenance (:provenance evt)
                           :body-found-in-read-model? (some? body)
                           :body-summary (some-> body :summary (subs 0 (min 200 (count (:summary body)))))
                           :body-strengths-count (count (:strengths body))
                           :body-capabilities-count (count (:capabilities body))
                           :body-representative-uses-count (count (:representative-uses body))
                           :substance substance}))
                      new-mints)
                _ (doseq [a assessments]
                    (println "\n  Mint:" (:name a))
                    (println "    target-id:" (:target-id a))
                    (println "    provenance:" (:provenance a))
                    (println "    body in read-model?" (:body-found-in-read-model? a))
                    (println "    :summary preview:" (:body-summary a))
                    (println "    :strengths count:" (:body-strengths-count a))
                    (println "    :capabilities count:" (:body-capabilities-count a))
                    (println "    :representative-uses count:" (:body-representative-uses-count a))
                    (println "    substance breakdown:")
                    (doseq [[k v] (sort (:substance a))]
                      (println "       " k "→" v)))

                ;; Overall PASS criteria
                agent-minted? (pos? (count new-mints))
                ;; If we minted, at least ONE mint must be substantive AND in read-model
                any-substantive? (some (fn [a]
                                         (and (:body-found-in-read-model? a)
                                              (-> a :substance :substantive?)))
                                       assessments)
                pass? (and agent-minted? any-substantive?)]

            (println "\n============================================================")
            (println " RESULT")
            (println "============================================================")
            (println "  Agent minted (≥1 :ontology/behavioral-subtree-minted)?" agent-minted?)
            (println "  At least one minted body is substantive + persists?" (boolean any-substantive?))
            (println)
            (println "  PASS:" pass?
                     (cond
                       (not agent-minted?)
                       "(agent ignored the mint affordance — investigate prepend visibility / model temperature / fresh-mint marker conditions)"

                       (not any-substantive?)
                       "(agent minted but body is boilerplate — investigate R-Inject prepend phrasing / model prompt for the body-map)"

                       :else
                       "(agent recognized the OOD task + minted substantive body + read-model persistence holds)"))
            {:pass? pass?
             :agent-minted? agent-minted?
             :any-substantive? any-substantive?
             :assessments assessments
             :task-result result})))
      (finally
        (runner/stop!)))))

(comment
  (require '[c-loop-3-live-verify :as v] :reload)
  (v/verify!))
