(ns el2-grounded-rank-proto
  "THROWAWAY — EL-2 prototype (emergence loop, ADR 0015). NOT production code.

   Goal: prove the EL-2 fix BEFORE touching source.
   1. RULE OUT THE HARNESS: for each behavioral candidate the `refactor`
      case retrieves, show which SCOPE get-description actually returns the
      body from (the E3.5 trap: behavioral bodies land under :tree-fingerprint,
      NOT :behavioral-subtree — mint-behavioral-subtree emits target-type
      :tree-fingerprint). Confirm :avoid-when is non-nil at the right scope.
   2. Render the ENRICHED rerank payload for the `refactor` case — confirm each
      candidate now carries its :avoid-when.
   3. Run the REAL reranker with the enriched payload + an avoid-when-aware
      intent and confirm rename-move-symbol is DOWN-ranked below Code-building
      (the right parent wins) vs E4's force-fit.

   Real grain + real ColBERT + real reranker. Run:
     OPENROUTER_API_KEY=... clojure \\
       -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
       -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
       -M:dev -m el2-grounded-rank-proto"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.colbert.interface :as colbert]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def parent-name-by-id
  {#uuid "8ad38e72-05e4-3201-bac7-3ed9c54a2791" "Research"
   #uuid "abe10f3b-ab49-3916-ae30-0ca7abfcad96" "Extraction"
   #uuid "b3597aa7-3957-3734-90c6-531d27c08f67" "Analysis"
   #uuid "2bce84a1-d186-3892-8131-c19b59e4543e" "Synthesis"
   #uuid "1b1480f1-66b3-399a-8715-e5b8f023a71b" "Ideation"
   #uuid "abe48812-ad0f-3a27-a142-806558801fc0" "Design"
   #uuid "0f11dba5-c331-3cdf-81fa-8ee114fc224f" "Critique"
   #uuid "1361eafc-6a90-391a-97a9-b558118f1f57" "Validation"
   #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0" "Code-building"
   #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb" "Transformation"
   #uuid "01f78800-c0e7-34b3-bb09-a7a6a95a022d" "Classification"
   #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861" "Investigation"})

(defn derive-child-id [name parent]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str "mint:" name ":" parent) "UTF-8")))
(def code-building  #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0")
(def transformation #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb")
(def investigation  #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861")
(def children
  [["code-edit-dependency-wiring" transformation]
   ["performance-optimization"    transformation]
   ["documentation-writing"       code-building]
   ["bug-diagnosis"               investigation]
   ["rename-move-symbol"          code-building]])
(def child-id->name (into {} (for [[n p] children] [(derive-child-id n p) (str "child/" n)])))
(defn display-name [id]
  (or (parent-name-by-id id) (child-id->name id)
      (let [s (str id)] (str "OTHER/" (subs s 0 (min 16 (count s)))))))

(def rename-move-symbol-id (derive-child-id "rename-move-symbol" code-building))

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

;; The E4 force-fit case.
(def refactor-instruction
  "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.")

;; ============================================================================
;; The enrichment under test (mirrors what will go into apply-rerank).
;; ============================================================================
(defn coerce-target-id
  "Candidate :document-metadata :target-id round-trips through ColBERT JSON as
   a string; coerce to UUID when it parses as one (matches the descriptions
   read-model key shape for minted bodies)."
  [tid]
  (cond
    (uuid? tid) tid
    (string? tid) (try (java.util.UUID/fromString tid) (catch Exception _ tid))
    :else tid))

(defn fetch-body
  "Fetch a candidate's Living Description body, ROOT-CAUSING the scope.
   Behavioral + minted tree-class bodies land under :tree-fingerprint
   (mint-behavioral-subtree emits target-type :tree-fingerprint); tree-class
   seeds are dual-emitted under :tree-class too. We try the candidate's stated
   granularity first, then fall back to :tree-fingerprint (the universal store
   for minted/seeded bodies). Returns [body scope-hit]."
  [ctx granularity target-id]
  (let [tid (coerce-target-id target-id)
        try-scopes (->> [granularity :tree-fingerprint :tree-class]
                        (remove nil?)
                        distinct)]
    (loop [[s & more] try-scopes]
      (if (nil? s)
        [nil nil]
        (if-let [b (ont/get-description ctx s tid)]
          [b s]
          (recur more))))))

(defn compact-strengths [strengths]
  (->> strengths
       (take 3)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:good-when e) (assoc :good-when (:good-when e))
                       (:recommended-pattern e) (assoc :recommended-pattern (:recommended-pattern e)))))))

(defn compact-weaknesses [weaknesses]
  (->> weaknesses
       (take 3)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:avoid-when e) (assoc :avoid-when (:avoid-when e))
                       (:recommended-alternative e) (assoc :recommended-alternative (:recommended-alternative e)))))))

(defn enrich-candidate
  "Add :avoid-when (top-level vector + the per-weakness guards) + compact
   strengths/weaknesses from the candidate's body to the candidate map."
  [ctx c]
  (let [{:keys [granularity target-id]} (:document-metadata c)
        [body scope-hit] (fetch-body ctx granularity target-id)
        weaknesses (:weaknesses body)
        per-weakness-guards (->> weaknesses (keep :avoid-when) vec)
        avoid-when (vec (distinct (concat (:avoid-when body) per-weakness-guards)))]
    (cond-> c
      (seq avoid-when)            (assoc :avoid-when avoid-when)
      (seq (:strengths body))     (assoc :strengths (compact-strengths (:strengths body)))
      (seq weaknesses)            (assoc :weaknesses (compact-weaknesses weaknesses))
      true                        (assoc ::scope-hit scope-hit ::body-found? (some? body)))))

;; Behavioral intent with the avoid-when-aware HARD rule (mirrors the prompt edit).
(def el2-behavioral-intent
  (str "I'm classifying a task to find behavioral subtrees — reusable "
       "competencies (analysis / validation / research / design / etc.) "
       "— that compose into the structural shape this task needs. Return "
       "candidates whose recommended-pattern best fits what the task "
       "ACCOMPLISHES, not just what shape it has. Weight DOMAIN / subject-"
       "matter fit, not only structural shape. Higher fitness = better "
       "behavioral match."))

;; A STRONGER reranker prompt that puts the avoid-when rule front-and-center,
;; used to test whether the reranker CAN honor avoid-when if pushed hard.
(def hard-avoid-when-instruction
  "You are ranking candidate descriptions by their fitness for a caller's intent.

INPUTS DESCRIBED
- query, intent — as usual.
- candidates — a JSON vector. Each candidate may carry:
    content (summary), score (ColBERT sim), document-id, document-metadata,
    avoid-when (a vector of HARD disqualifier strings — contexts where this
      candidate is the WRONG choice even if its shape/summary looks similar),
    strengths, weaknesses.

HARD RULE — AVOID-WHEN IS A VETO, NOT A HINT.
For EACH candidate, read its avoid-when list FIRST, before its strengths.
If ANY avoid-when entry describes what the task is actually doing, the
candidate is DISQUALIFIED for the primary slot: assign fitness_score <= 0.25
and your reasoning MUST quote the matching avoid-when entry and say the task
matches it. Do NOT let a strong shape/summary match override a matching
avoid-when. Weight DOMAIN fit (what the task IS) over structural shape (what
the task LOOKS like).

PRODUCE a JSON string of a vector, descending by fitness_score. Each element:
  {\"document_id\":\"<echo>\",\"reasoning\":\"<concrete; quote avoid-when if it fired>\",\"fitness_score\":<0.0-1.0>}
Raw JSON only, starting with [ and ending with ]. Return ALL candidates.")

(defn -main [& _]
  (println "=== EL-2 PROTO: grounded domain rank (reranker reads :avoid-when) ===")
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))]

      ;; -- 0. RULE OUT HARNESS: confirm rename-move-symbol's body + :avoid-when
      ;;       is reachable, and at WHICH scope --
      (println "\n############ 0. RULE-OUT-HARNESS: scope of the guard body ############")
      (doseq [[n p] children]
        (let [id (derive-child-id n p)
              bf (ont/get-description ctx :tree-fingerprint id)
              bb (ont/get-description ctx :behavioral-subtree id)]
          (println (format "  %-30s :tree-fingerprint=%s  :behavioral-subtree=%s  avoid-when(tf)=%s"
                           n (some? bf) (some? bb)
                           (pr-str (:avoid-when bf))))
          (when (= n "rename-move-symbol")
            (println "    rename-move-symbol weaknesses avoid-when:")
            (doseq [w (:weaknesses bf)] (println "      -" (pr-str (:avoid-when w)))))))

      ;; -- 0b. RAW ColBERT pool (NO granularity filter, k=12) — is Code-building
      ;;        even RECALLED for the refactor task? --
      (println "\n############ 0b. RAW ColBERT recall pool for `refactor` (no filter, k=12) ############")
      (let [raw-pool (ont/search-descriptions ctx
                       {:query (build-sig refactor-instruction)
                        :granularity :all :k 12})]
        (println "  pool size:" (count raw-pool))
        (doseq [r raw-pool]
          (let [tid (-> r :document-metadata :target-id)
                uid (coerce-target-id tid)]
            (println (format "    - %-28s gran=%-20s score=%.3f"
                             (display-name uid)
                             (-> r :document-metadata :granularity)
                             (double (or (:score r) 0.0)))))))

      ;; -- 1. retrieve the raw behavioral candidates for the refactor case --
      (println "\n############ 1. RAW behavioral candidates for `refactor` (pre-enrich) ############")
      (let [raw (ont/search-descriptions ctx
                  {:query (build-sig refactor-instruction)
                   :granularity :behavioral-subtree
                   :rerank-with-intent el2-behavioral-intent
                   :k 5})]
        (println "  raw hits:" (count raw))
        (doseq [r raw]
          (let [tid (-> r :document-metadata :target-id)
                uid (coerce-target-id tid)]
            (println (format "    - %-28s gran=%s fit=%s"
                             (display-name uid)
                             (-> r :document-metadata :granularity)
                             (some-> (:fitness-score r) double)))))

        ;; -- 2. ENRICH + show :avoid-when present per candidate --
        (println "\n############ 2. ENRICHED payload (the EL-2 input-side fix) ############")
        (let [enriched (mapv #(enrich-candidate ctx %) raw)]
          (doseq [c enriched]
            (let [uid (coerce-target-id (-> c :document-metadata :target-id))]
              (println (format "    - %-28s scope-hit=%s body-found?=%s avoid-when=%s"
                               (display-name uid) (::scope-hit c) (::body-found? c)
                               (pr-str (:avoid-when c))))))
          (println "\n  --- FULL enriched candidate map for rename-move-symbol ---")
          (let [rms (first (filter #(= rename-move-symbol-id
                                       (coerce-target-id (-> % :document-metadata :target-id)))
                                   enriched))]
            (pp/pprint (dissoc rms ::scope-hit ::body-found?)))

          ;; -- 3. REAL reranker on the enriched payload --
          (println "\n############ 3. REAL reranker on ENRICHED payload ############")
          ;; strip the proto-only ::scope-hit / ::body-found? markers before send
          (let [clean (mapv #(dissoc % ::scope-hit ::body-found?) enriched)
                reranked (reranker/rerank! ctx {:query (build-sig refactor-instruction)
                                                :intent el2-behavioral-intent
                                                :candidates clean})
                by-id (into {} (map (juxt :document-id identity)) clean)]
            (println "  reranked order (top->bottom):")
            (doseq [[i r] (map-indexed vector reranked)]
              (let [orig (get by-id (:document-id r))
                    uid (coerce-target-id (-> orig :document-metadata :target-id))]
                (println (format "    #%d %-28s fit=%.3f"
                                 (inc i) (display-name uid) (double (or (:fitness-score r) 0.0))))
                (when (:reasoning r)
                  (println "        reasoning:" (subs (:reasoning r) 0 (min 260 (count (:reasoning r))))))))
            (let [order (mapv (fn [r] (coerce-target-id
                                        (-> (get by-id (:document-id r)) :document-metadata :target-id)))
                              reranked)
                  rms-rank (first (keep-indexed (fn [i id] (when (= id rename-move-symbol-id) (inc i))) order))
                  cb-rank  (first (keep-indexed (fn [i id] (when (= id code-building) (inc i))) order))]
              (println "\n  >>> VERDICT INPUT (default prompt) <<<")
              (println "  rename-move-symbol rank:" rms-rank "  Code-building rank:" cb-rank)
              (println "  Code-building BEATS rename child? ="
                       (boolean (and cb-rank (or (nil? rms-rank) (< cb-rank rms-rank))))))

            ;; -- 4. HARD avoid-when prompt: can the reranker be FORCED to honor
            ;;       avoid-when as a veto? Redef the private instruction + rebuild
            ;;       the workflow var, then rerank the SAME enriched payload. --
            (println "\n############ 4. SAME payload, HARD avoid-when-veto prompt ############")
            (let [hard-wf (ai.obney.orc.orc-service.interface/workflow "ontology-description-reranker-hard"
                            (ai.obney.orc.orc-service.interface/blackboard
                              {:query :string :intent :string
                               :candidates [:vector :map] :reranked-json :string})
                            (ai.obney.orc.orc-service.interface/llm "rerank"
                              :instruction hard-avoid-when-instruction
                              :reads [:query :intent :candidates]
                              :writes [:reranked-json]
                              :options {:max-retries 3 :retry-delay-ms [500 1500 3000]
                                        :use-function-calling? true}))]
              (with-redefs [reranker/reranker-workflow hard-wf]
                (let [reranked2 (reranker/rerank! ctx {:query (build-sig refactor-instruction)
                                                       :intent el2-behavioral-intent
                                                       :candidates clean})
                      order2 (mapv (fn [r] (coerce-target-id
                                             (-> (get by-id (:document-id r)) :document-metadata :target-id)))
                                   reranked2)
                      rms2 (first (keep-indexed (fn [i id] (when (= id rename-move-symbol-id) (inc i))) order2))]
                  (doseq [[i r] (map-indexed vector reranked2)]
                    (let [uid (coerce-target-id (-> (get by-id (:document-id r)) :document-metadata :target-id))]
                      (println (format "    #%d %-28s fit=%.3f" (inc i) (display-name uid)
                                       (double (or (:fitness-score r) 0.0))))
                      (when (:reasoning r)
                        (println "        reasoning:" (subs (:reasoning r) 0 (min 260 (count (:reasoning r))))))))
                  (println "\n  >>> VERDICT INPUT (hard prompt) <<<")
                  (println "  rename-move-symbol rank under HARD prompt:" rms2)
                  (println "  rename child DOWN-ranked (not #1)? =" (boolean (and rms2 (> rms2 1))))))))))
      (println "\n=== DONE ==="))
    (catch Throwable t
      (println "ERROR:" (.getMessage t))
      (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
