(ns c-loop-2-live-verify
  "C-Loop-2 V1 LIVE verify — mint persistence on real Grain.

   Asserts the load-bearing persistence guarantee from the C-Loop-2 spec
   at the LAYER C-Loop-2 OWNS: the mint defcommand persists the new
   behavior in the descriptions read-model such that get-description
   finds it. ColBERT re-index pickup (the next layer for classify-
   behaviors retrieval) lives in C-2b-1 and has its own timing/threshold
   semantics — that's a separate downstream layer.

   Flow:
     1. runner/start! brings up real Grain + seeds the corpus
     2. Mint a distinctive behavior with stable target-id (C-Loop-2 D4)
     3. Verify the mint lands in :ontology/descriptions read-model
        via the public get-description API
     4. Verify the body's :scope was stamped :behavioral-subtree by
        the defcommand handler
     5. Verify re-mint with same (name, parent) is idempotent at the
        target-id layer (returns same UUID — D4 contract LIVE-proven)

   Out of scope (deferred to C-2b followup): full ColBERT re-index
   timing verification. See C-2b-followup-classify-after-mint-timing
   in docs/issues/c2d-followups/ for the open question of
   end-to-end mint→classify-behaviors candidate surfacing.

   No mocks — real Grain command processor, real read-model, real
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

(defn verify!
  []
  (println "============================================================")
  (println " C-Loop-2 V1 LIVE Verify — mint → classify roundtrip")
  (println "============================================================")
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY must be set" {})))
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))
        ;; Distinctive name to keep the corpus clean across re-runs.
        ;; Same name + parent → same UUID (C-Loop-2 D4), so re-runs
        ;; idempotently land at the same minted concept.
        mint-name "c-loop-2-live-verify-polysomnography-grader"
        body (distinctive-mint-body)]
    (try
      (println "\n--- Step 1: mint a distinctive behavior")
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/mint-behavioral-subtree
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :name mint-name
                :body body
                :provenance :human-authored}))
      (println "  → mint dispatched")

      ;; Read the audit event to confirm what target-id was assigned
      (Thread/sleep 200)
      (let [audit-events (filter #(= mint-name (:name %))
                                  (into [] (es/read (:event-store ctx)
                                                     {:tenant-id (:tenant-id ctx)
                                                      :types #{:ontology/behavioral-subtree-minted}})))
            mint-event (last audit-events)
            target-id (:target-id mint-event)]
        (println "  → minted target-id:" target-id)

        (println "\n--- Step 2: verify get-description finds the just-minted concept")
        (let [desc (ontology/get-description ctx :tree-fingerprint target-id)]
          (println "  desc :scope:" (:scope desc))
          (println "  desc :summary first 100:" (some-> desc :summary (subs 0 (min 100 (count (:summary desc))))))
          (let [persistence-ok? (some? desc)
                scope-ok? (= :behavioral-subtree (:scope desc))
                summary-ok? (= (:summary body) (:summary desc))]
            (println "  persistence-ok?" persistence-ok?)
            (println "  scope stamped :behavioral-subtree?" scope-ok?)
            (println "  summary roundtripped verbatim?" summary-ok?)

            (println "\n--- Step 3: re-mint with same name+parent — must yield same target-id (D4)")
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/mint-behavioral-subtree
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :name mint-name
                      :body body
                      :provenance :human-authored}))
            (Thread/sleep 200)
            (let [audit-events-2 (filter #(= mint-name (:name %))
                                          (into [] (es/read (:event-store ctx)
                                                             {:tenant-id (:tenant-id ctx)
                                                              :types #{:ontology/behavioral-subtree-minted}})))
                  second-mint-target (:target-id (last audit-events-2))
                  idempotent? (= target-id second-mint-target)
                  pass? (and persistence-ok? scope-ok? summary-ok? idempotent?)]
              (println "  second mint target-id:" second-mint-target)
              (println "  same target-id as first mint?" idempotent?)
              (println)
              (println "============================================================")
              (println " RESULT")
              (println "============================================================")
              (println "  persistence-ok?     " persistence-ok?)
              (println "  scope-ok?           " scope-ok?)
              (println "  summary-roundtrip?  " summary-ok?)
              (println "  D4-idempotent?      " idempotent?)
              (println)
              (println "  PASS:" pass?
                       (if pass?
                         "(mint persists via descriptions read-model; D4 idempotency holds LIVE)"
                         "(one or more contracts failed — investigate above)"))
              (println)
              (println "  NOTE: ColBERT-indexed classify-behaviors retrieval after a fresh mint")
              (println "  is downstream of this verify — see C-2b-followup-classify-after-mint-timing")
              (println "  for the open question of end-to-end re-index pickup.")
              {:pass? pass?
               :persistence-ok? persistence-ok?
               :scope-ok? scope-ok?
               :summary-ok? summary-ok?
               :idempotent? idempotent?
               :target-id target-id}))))
      (finally
        (runner/stop!)))))

(comment
  (require '[c-loop-2-live-verify :as v] :reload)
  (v/verify!))
