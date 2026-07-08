(ns convergence-capture-verify
  "CV-1 (ADR 0017) integration proof — the classify-TWICE-via-the-WEDGE loop.

   Drives the REAL wedge (orc-service maybe-auto-classify-and-set-context) end
   to end against real grain + real ColBERT + the real reranker. This exercises
   BOTH halves of the convergence fix together:
     Part 1 (capture): on a fresh-mint the wedge records a provisional
       :tree-class description whose searchable content is the task signature.
     Part 2 (gate decouple): classify-task matches/bundles on the UNGATED
       candidates, so a described below-gate class is matchable-for-accrual.

   Cycles proven (assert by READING the read-model / re-classifying BACK, never
   a bare return value):
     1. CONVERGENCE — two same-signature wedge calls (capture + reindex settle
        between) → 2nd MATCHES the 1st's runtime class (was-fresh-mint? false)
        and the occurrence total ACCRUES (climbs 1 → 2) while the total is still
        BELOW the retrieval gate (proves the decouple).
     2. NO OVER-MERGE — a genuinely DISTINCT signature is NOT swallowed into the
        first class (assigned id != A).
     3. THRASH-SAFE — the MATCH path records NO new :tree-class description
        (only fresh-mint captures); the description-event count for A does not
        grow on the match.

   We need a genuine STRUCTURAL fresh-mint to exercise capture, but the seed
   corpus force-fits most task shapes. Form-constrained creative authorship
   (haiku / iambic / cross-modal melody) has no structural seed, so the probe
   AUTO-SELECTS whichever OOD candidate actually fresh-mints as the subject.

   Run SOLO:
     clojure -J-Dcolbert.venv.path=<orc-main venv> \\
             -J-Dcolbert.bridge.script=<orc-main bridge> \\
             -M:dev -m convergence-capture-verify"
  (:require [runner]
            [litellm.router :as litellm-router]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.orc-service.core.todo-processors :as svc-tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.colbert.interface :as colbert]))

;; The seed corpus force-fits nearly every task shape (systematic reranker
;; shape-bias — E4 finding), so a NATURAL structural fresh-mint never occurs
;; against it. The actual bug (ADR 0017) is about RUNTIME-minted classes that
;; are not yet in the corpus, so we bootstrap a real ColBERT index of ONLY the
;; FAISS-floor synthetic padding (no force-fitting baseline seeds). This is the
;; honest runtime-emergence starting point: real grain + real ColBERT + real
;; reranker, a corpus with no pre-existing match for the subject — exactly the
;; state in which classify-task fresh-mints and the capture must make the class
;; retrievable for the next turn.
(defn bootstrap-padding-only! []
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        base {:provider :openrouter
              :model (:model runner/config)
              :config {:api-base "https://openrouter.ai/api/v1" :api-key api-key}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" (:model runner/config)))
                              (assoc base :model (:model runner/config))))
  (let [ctx ((requiring-resolve 'runner/create-context))]
    (println "Emitting synthetic padding (80 entries for FAISS clustering floor)…")
    ((requiring-resolve 'runner/emit-synthetic-padding!) ctx 80)
    (Thread/sleep 1500)
    (println "Building ColBERT index (padding-only baseline)…")
    (ont-tp/force-rebuild! ctx)
    (Thread/sleep 1500)
    (println "Index state:" (pr-str (ont/get-reindex-state ctx)))
    ctx))

;; OOD creative-authorship candidates — no structural seed covers form-
;; constrained authorship, so at least one should fresh-mint (top seed < the
;; bundle band). The probe picks the first that actually does.
(def ood-candidates
  [{:id (random-uuid)
    :type :repl-researcher
    :instruction "Produce a haiku (5-7-5 syllable structure) for EACH security audit finding that captures the essence of the vulnerability in evocative imagery while preserving technical accuracy, without naming the CVE category directly."
    :reads [:audit-findings]
    :writes [:finding-haikus]
    :rlm {:auto-classify? true}}
   {:id (random-uuid)
    :type :repl-researcher
    :instruction "Encode the first 20 digits of pi as a singable melody: map each digit to a scale degree or rest, choose note durations for rhythm, and explain the musical decisions (key, rhythm, resolution)."
    :reads [:digits]
    :writes [:melody :musical-explanation]
    :rlm {:auto-classify? true}}
   {:id (random-uuid)
    :type :repl-researcher
    :instruction "Write a chocolate-chip-cookie recipe where every numbered instruction step is rendered in iambic pentameter (10 syllables, unstressed-stressed), while keeping all cooking times, temperatures and physical actions accurate."
    :reads [:recipe-request]
    :writes [:metered-recipe]
    :rlm {:auto-classify? true}}])

;; A DISTINCT non-creative signature — used to prove the runtime creative class
;; does not over-merge unrelated tasks.
(def distinct-node
  {:id (random-uuid)
   :type :repl-researcher
   :instruction "Read the two contract drafts and produce a clause-by-clause comparison table highlighting the substantive legal differences."
   :reads [:contract-a :contract-b]
   :writes [:comparison-table]
   :rlm {:auto-classify? true}})

(defn sig-of [node] (tc/build-task-signature node))

(defn run-wedge
  "Drive the real wedge once; return its structural classifier envelope."
  [ctx node]
  (let [ctx+ (assoc ctx :sheet-id (random-uuid) :tick-id (random-uuid))
        out (svc-tp/maybe-auto-classify-and-set-context node ctx+)]
    (get-in out [:context :r05-classifier :structural])))

(defn settle
  "Force a ColBERT rebuild so a just-recorded description is searchable, then
   wait for the index count to stabilize (rules out back-to-back-reindex timing
   as a fake symptom)."
  [ctx]
  (ont-tp/force-rebuild! ctx)
  (Thread/sleep 2000)
  (letfn [(n [] (count (into [] (colbert/list-indexes ctx))))]
    (loop [p -1 s 0 i 0]
      (let [c (n)]
        (cond (and (= c p) (>= s 2)) c
              (>= i 12) c
              :else (do (Thread/sleep 1500) (recur c (if (= c p) (inc s) 0) (inc i))))))))

(defn total [ctx id] (rm/get-consolidation-total ctx :tree-class id))

(defn tree-class-desc-count
  "Count :ontology/tree-description-updated events on the :tree-class axis for a
   specific target-id — the reindex-thrash signal (read from the store)."
  [ctx id]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:ontology/tree-description-updated}})
       (into [])
       (filter (fn [e] (and (= :tree-class (:target-type e))
                            (= id (:target-id e)))))
       count))

(defn- pick-fresh-minting-subject
  "Classify each OOD candidate once (no dispatch) and return the first whose
   structural classify-task genuinely fresh-mints — that is the subject that
   exercises the runtime-mint → capture path."
  [ctx]
  (some (fn [node]
          (let [r (ont/classify-task ctx {:task-signature (sig-of node) :threshold 0.7})]
            (println (format "  probe candidate '%s…' fresh-mint?=%s outcome=%s conf=%.3f"
                             (subs (:instruction node) 0 40)
                             (:was-fresh-mint? r) (:outcome r)
                             (double (or (:confidence r) 0.0))))
            (when (:was-fresh-mint? r) node)))
        ood-candidates))

(defn -main [& _]
  (let [ctx (bootstrap-padding-only!)]
    (try
      (println "\n=== CV-1 CONVERGENCE-CAPTURE VERIFY (classify-twice via the WEDGE) ===")
      (println "\nselecting a genuinely fresh-minting OOD subject…")
      (let [subject (pick-fresh-minting-subject ctx)]
        (assert subject "no OOD candidate fresh-minted — cannot exercise the capture path")
        (println "  → subject:" (subs (:instruction subject) 0 50) "…")

        ;; --- CALL 1: fresh-mint + capture ---
        (let [s1 (run-wedge ctx subject)
              a  (:assigned-tree-id s1)]
          (println (format "\nCALL 1  fresh-mint?=%s  id=%s  conf=%.3f"
                           (:was-fresh-mint? s1) (str a) (double (or (:confidence s1) 0.0))))
          (assert (:was-fresh-mint? s1) "CALL 1 must fresh-mint the subject")
          ;; READ BACK: the capture recorded a searchable description.
          (let [desc (ont/get-description ctx :tree-class a)]
            (println "  captured :tree-class description :summary present?=" (some? (:summary desc)))
            (assert (some? (:summary desc)) "CAPTURE: a :tree-class description must be recorded on fresh-mint"))
          (println "  total(A) after call 1 =" (total ctx a) " (expect 1)")
          (assert (= 1 (total ctx a)) "counter ticked once on the fresh-mint assign")
          (println "  tree-class desc-count(A) =" (tree-class-desc-count ctx a) " (expect 1)")
          (assert (= 1 (tree-class-desc-count ctx a)) "exactly one capture on fresh-mint")

          ;; --- settle the reindex so A is retrievable on the next turn ---
          (println "\nsettling reindex (force-rebuild + stabilize)…")
          (settle ctx)

          ;; --- CYCLE 1: same signature again → MUST MATCH A, total accrues ---
          (let [descs-before (tree-class-desc-count ctx a)
                s2 (run-wedge ctx subject)
                b  (:assigned-tree-id s2)]
            (println (format "\nCALL 2  fresh-mint?=%s  id=%s  conf=%.3f  MATCHED-A?=%s"
                             (:was-fresh-mint? s2) (str b) (double (or (:confidence s2) 0.0))
                             (= a b)))
            (println (format "  (A total was 1 < retrieval-gate 3 — matching it PROVES the decouple)"))
            (assert (= a b) "CONVERGENCE: the 2nd identical-signature classify MUST match A (no scatter)")
            (assert (false? (:was-fresh-mint? s2)) "CONVERGENCE: the match is NOT a fresh-mint")
            (println "  total(A) after call 2 =" (total ctx a) " (expect 2 — ACCRUED)")
            (assert (= 2 (total ctx a)) "ACCRUAL: the occurrence total climbs on the match")
            ;; --- CYCLE 3: thrash-safe — the MATCH recorded NO new description ---
            (let [descs-after (tree-class-desc-count ctx a)]
              (println (format "  tree-class desc-count(A): before=%d after=%d (expect equal — THRASH-SAFE)"
                               descs-before descs-after))
              (assert (= descs-before descs-after) "THRASH-SAFE: a match records NO new :tree-class description")))

          ;; --- CYCLE 2: a DISTINCT signature is NOT over-merged into A ---
          (let [s3 (run-wedge ctx distinct-node)
                c  (:assigned-tree-id s3)]
            (println (format "\nDISTINCT  fresh-mint?=%s  id=%s  == A?=%s"
                             (:was-fresh-mint? s3) (str c) (= a c)))
            (assert (not= a c) "NO OVER-MERGE: a distinct signature is not swallowed into A"))))

      (println "\n=== ALL CV-1 CONVERGENCE ASSERTIONS PASSED ===")
      (catch Throwable t
        (println "\n!!! CV-1 VERIFY FAILED:" (ex-message t))
        (throw t))
      (finally
        ((requiring-resolve 'runner/stop-context) ctx)
        (try (colbert/stop-bridge!) (catch Throwable _ nil))
        (shutdown-agents)))))
