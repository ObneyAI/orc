(ns qp-3-live-verify
  "QP-3 LIVE verify — fresh mints surface on the next classify-behaviors
   call within the same session.

   C-Loop-2 LIVE proved mints persist in the descriptions read-model
   (get-description finds them) but explicitly deferred the ColBERT
   re-index pickup as a C-2b-1 followup. The model's R-Inject prepend
   PROMISES: 'the minted behavior will be retrievable on subsequent
   classify-behaviors calls — your contribution persists in the corpus
   for future tasks.' That promise was unmet until QP-3 because the
   re-index processor was threshold-gated (default 10 events), so a
   single mint waited for 9 unrelated description events.

   QP-3 added a separate processor subscribed only to
   :ontology/behavioral-subtree-minted that forces a rebuild
   immediately (bypassing the threshold).

   This verify proves the LOAD-BEARING end-to-end behavior:
     1. runner/start! brings up real Grain + seeds the corpus + builds
        the initial ColBERT index (real bridge — ragatouille).
     2. Mint a distinctive behavior (a domain-specific summary the
        seed corpus does not cover).
     3. Wait for the forced rebuild to complete (poll the reindex
        state read-model — :last-rebuild-timestamp bumps when
        :colbert/index-created lands).
     4. classify-behaviors with a task signature that the minted body's
        :summary semantically matches.
     5. Assert the minted target-id is in the :behaviors result.

   No mocks — real OpenRouter reranker, real ColBERT bridge, real
   event store."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [runner]))

(defn- distinctive-mint-body []
  ;; Same body shape as the C-Loop-2 verify — a domain unlikely to
  ;; overlap any seeded behavior so the reranker reliably scores it
  ;; highest on the matching task signature.
  {:capabilities ["correlates a SLEEP-pattern medical claim against a polysomnography reading"
                  "decodes ICD-10 sleep-apnea variants into a single graded severity score"]
   :strengths [{:trait "domain-specific terminology dictionary applied chunk-by-chunk"
                :good-when "evaluating diagnostic notes for sleep-disorder claims"
                :recommended-pattern "[:map-each {:from :chunks :as :chunk} [:llm ...]]"
                :confidence 0.7
                :evidence-count 1}]
   :weaknesses []
   :representative-uses ["graded-sleep-apnea polysomnography review"]
   :avoid-when ["non-medical text where domain-vocabulary doesn't apply"]
   :summary (str "Polysomnography-aware diagnostic-claim grading: a "
                 "behavior that grades sleep-medicine claim notes by "
                 "cross-referencing them against polysomnography study "
                 "data and ICD-10 sleep-disorder codes. Used when the "
                 "task needs both clinical-text comprehension and a "
                 "structured severity-scoring contract.")
   :version 1
   :consolidated-from-event-count 0})

(defn- reindex-state [ctx]
  ;; Use the read-model — :events-since-last-rebuild resets to 0 +
  ;; :last-rebuild-timestamp updates whenever a :colbert/index-created
  ;; event lands. Track those across the verify to detect rebuilds.
  (ontology/get-reindex-state ctx))

(defn verify!
  []
  (println "============================================================")
  (println " QP-3 LIVE Verify — mint → classify-behaviors roundtrip")
  (println "============================================================")
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY must be set" {})))
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))
        mint-name "qp3-live-verify-polysomnography-grader"
        body (distinctive-mint-body)]
    (try
      ;; --- Step 1: capture the pre-mint reindex state
      (println "\n--- Step 1: snapshot pre-mint reindex state")
      (let [pre-state (reindex-state ctx)
            pre-rebuild-ts (:last-rebuild-timestamp pre-state)
            _ (println "  pre-mint :index-built?     " (:index-built? pre-state))
            _ (println "  pre-mint :last-rebuild-ts  " pre-rebuild-ts)
            _ (println "  pre-mint events-since-rebuild" (:events-since-last-rebuild pre-state))

            ;; --- Step 2: dispatch the mint
            _ (println "\n--- Step 2: dispatch the mint")
            mint-result (cp/process-command
                          (assoc ctx :command
                                 {:command/name :ontology/mint-behavioral-subtree
                                  :command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :name mint-name
                                  :body body
                                  :provenance :human-authored}))
            _ (println "  mint dispatched; events emitted:"
                       (count (:command-result/events mint-result)))

            ;; Read the mint event back to get the target-id
            mint-events (into [] (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :types #{:ontology/behavioral-subtree-minted}}))
            mint-event (last (filter #(= mint-name (:name %)) mint-events))
            target-id (:target-id mint-event)
            _ (println "  minted target-id:" target-id)

            ;; --- Step 3: wait for the forced rebuild to land
            ;; ColBERT bridge is async and slow — poll for the
            ;; :colbert/index-created count to bump.
            _ (println "\n--- Step 3: wait for forced rebuild to complete")
            rebuild-deadline-ms 90000
            poll-interval-ms 2000
            rebuild-detected? (loop [elapsed 0]
                                (let [now-state (reindex-state ctx)
                                      now-ts (:last-rebuild-timestamp now-state)]
                                  (cond
                                    (and now-ts (not= now-ts pre-rebuild-ts))
                                    (do (println "  rebuild detected after" elapsed
                                                 "ms; new :last-rebuild-timestamp:" now-ts)
                                        true)
                                    (>= elapsed rebuild-deadline-ms)
                                    (do (println "  timeout after" elapsed
                                                 "ms; :last-rebuild-timestamp still:" now-ts)
                                        false)
                                    :else
                                    (do (Thread/sleep poll-interval-ms)
                                        (recur (+ elapsed poll-interval-ms))))))

            ;; --- Step 4: classify-behaviors with a matching task signature
            _ (println "\n--- Step 4: classify-behaviors with a matching task signature")
            task-signature (str "Review clinical notes for sleep apnea claims and produce a "
                                "severity score using ICD-10 sleep-disorder codes and "
                                "polysomnography study cross-references.")
            classify-result (ontology/classify-behaviors
                              ctx
                              {:task-signature task-signature
                               :threshold 0.0
                               :top-n 10})
            behaviors (:behaviors classify-result)
            _ (println "  classify-behaviors returned" (count behaviors) "candidate(s)")
            _ (doseq [b behaviors]
                (println "    -" (:behavior-id b)
                         "confidence:" (:confidence b)
                         "fresh-mint?:" (:was-fresh-mint? b)))

            ;; --- Step 5: assert the minted target-id is in the result
            minted-in-result? (some #(= target-id (:behavior-id %)) behaviors)
            pass? (and rebuild-detected? (boolean minted-in-result?))]

        (println "\n============================================================")
        (println " RESULT")
        (println "============================================================")
        (println "  rebuild-detected?     " rebuild-detected?)
        (println "  minted-in-result?     " (boolean minted-in-result?))
        (println)
        (println "  PASS:" pass?
                 (cond
                   (not rebuild-detected?)
                   "(forced rebuild never fired — investigate the on-behavioral-subtree-minted-force-rebuild processor)"

                   (not minted-in-result?)
                   "(rebuild fired but minted target-id absent from classify-behaviors — investigate the doc-building / search pipeline)"

                   :else
                   "(end-to-end loop works: mint → forced rebuild → ColBERT search retrieves the new behavior)"))
        {:pass? pass?
         :target-id target-id
         :rebuild-detected? rebuild-detected?
         :minted-in-result? minted-in-result?
         :behaviors behaviors})
      (finally
        (runner/stop!)))))

(comment
  (require '[qp-3-live-verify :as v] :reload)
  (v/verify!))
