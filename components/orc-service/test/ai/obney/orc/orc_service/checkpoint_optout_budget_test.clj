(ns ai.obney.orc.orc-service.checkpoint-optout-budget-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.mixed-family-budget-fixture :as f]
            [ai.obney.orc.orc-service.mixed-family-budget-test :as transport])
  (:import [com.sun.net.httpserver HttpServer]
           [java.util.concurrent ExecutorService TimeUnit]))

;; The existing rich mixed-family graph is reused verbatim except for explicit
;; checkpoint opt-out. Native cap4 replaces cap5: the ordinary prefix plus prime
;; spends four slots, so the inline model call must not reach the transport.
(defn build-optout! [ctx model variant run]
 (let [build (fn [name node] (orc/build-workflow! ctx (orc/workflow (str run "-" name) (orc/blackboard f/board) node)))
       grandchild (build "extract-grandchild" (f/leaf model "extract" "Copy every complete initial and lookup source record without changing attribution, order, zero or false. For each claim select its freshest observed-at. Normalize selected quantities to whole units using their declared descriptions, then sum into facts.total." [:sources :lookup-sources :purpose] [:extract-reasoning :facts]))
       extraction (build "extract-specialist" (orc/delegate "nested-extract" :target-sheet-id grandchild :reads [:sources :lookup-sources :purpose] :writes [:extract-reasoning :facts]))
       audit (build "audit-specialist" (f/leaf model "audit" "Combine initial and lookup sources. Select freshest observed-at per claim in original source order. Identify selected records with confirmed false or review? true. all-confirmed is true exactly when no such records exist." [:sources :lookup-sources :purpose] [:audit-reasoning :audit]))
       research (build "reconcile-specialist"
         (orc/repl-researcher "reconcile" :model model
          :instruction (str "Reconcile facts and audit. Select freshest observed-at per claim from facts.records, preserving original order. Include complete selected records as evidence, their IDs, facts.total, audit.unconfirmed-ids and run-id in summary. "
             (if (= variant :inline)
              "Use the public direct (llm ...) function once inside Phase 1, not emit-tree!. The inline specialist must read facts, audit and run-id and write summary. Then final! a map with research-result containing its typed summary."
              "Use emit-tree! with an llm specialist reading facts, audit and run-id and writing summary, then a final leaf returning summary. Use the existing declared typed summary contract. After the tree returns, final! research-result as its typed summary."))
          :reads [:facts :audit :run-id :purpose] :writes [:research-result] :options f/options :max-iterations 10
          :rlm {:recursive? true :checkpointed? false :quantum {:max-iterations 1} :sub-model model :sub-options f/options
           :timeouts {:provider-ms 1200000 :iteration-ms 1500000 :campaign-ms 3600000}}))
       root (build "root"
        (orc/sequence "compose"
         (orc/code "read-only-lookup" :fn "ai.obney.orc.orc-service.mixed-family-budget-fixture/fixture-lookup" :reads [:missing-source-ids] :writes [:lookup-sources])
         (orc/parallel "independent-specialists" {:success-policy :all :failure-policy :any}
          (orc/delegate "extract-delegate" :target-sheet-id extraction :reads [:sources :lookup-sources :purpose] :writes [:extract-reasoning :facts])
          (orc/delegate "audit-delegate" :target-sheet-id audit :reads [:sources :lookup-sources :purpose] :writes [:audit-reasoning :audit]))
         (orc/llm-decision "route" :model model :instruction "Choose review when audit has unconfirmed IDs; otherwise choose ready." :reads [:audit] :writes [:route] :options f/options)
         (orc/delegate "research-delegate" :target-sheet-id research :reads [:facts :audit :run-id :purpose] :writes [:research-result])
         (f/leaf model "synthesize" "Return final-result with assessment equal to the full research-result and route equal to route. Do not change IDs, quantities, false/zero facts or attribution." [:research-result :route] [:final-reasoning :final-result])
         (orc/code "validate" :fn "ai.obney.orc.orc-service.mixed-family-budget-fixture/validate-result" :reads [:sources :lookup-sources :run-id :final-result] :writes [:validated])))]
  {:root root :extraction extraction :grandchild grandchild :audit audit :research research}))

(def last-proof (atom nil))

(deftest capped-checkpoint-optout-uses-durable-admission
  ;; BUDGET-7a: SharedLlmCallBudget applies even with campaign recovery disabled.
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-optout-budget-" (make-array java.nio.file.attribute.FileAttribute 0)))
        pack (first f/packs) run-id (str "conversation-optout-inline-" (random-uuid))
        tick-id (random-uuid) server (transport/start-server! pack run-id directory)]
    (println "OPTOUT-BUDGET-PROOF" (.getAbsolutePath directory)) (flush)
    (try
      (llm/register-provider! :checkpoint-optout-budget
        {:provider :openrouter :model "fixture/shared-model" :output-mode :json-schema
         :config {:api-key "fixture" :api-base (:url server) :timeout 30000}})
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite :database-file (str (io/file directory "store.sqlite")) :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :llm-provider :checkpoint-optout-budget :experiment/unit-divisor 1
                        :experiment/read-source (fn [id]
                          (or (first (filter #(= id (:id %)) (:lookup-sources pack)))
                              (throw (ex-info "Unknown fixture source" {:id id}))))}}]
        (let [sheets (build-optout! ctx "fixture/shared-model" :inline run-id)
              inputs (assoc (select-keys pack [:sources :purpose :missing-source-ids]) :run-id run-id)
              result (orc/execute ctx (:root sheets) inputs :tick-id tick-id :timeout-ms 90000 :max-ticks 1 :llm-call-budget 4)
              events (h/read-all-events ctx)
              by-id (into {} (map (juxt :event/id identity)) events)
              family (orc/get-execution-family ctx tick-id)
              writes (mapv #(orc/value-log-resolve-writes ctx (:tenant-id ctx) (:tick-id %) %)
                           (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              reservations (orc/get-provider-call-reservations ctx (:root sheets) tick-id)
              research-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                 (= (:research sheets) (:sheet-id %))) events))
              researcher (first (filter #(= :repl-researcher (:type %))
                                       (vals (get-in research-start [:execution-snapshot :nodes-by-id]))))
              cancellation (filter #(and (= :sheet/tick-cancelled (:event/type %))
                                          (= "LLM call budget exceeded: 4/4" (:reason %))) events)
              proof {:directory (str directory) :root tick-id :tenant (:tenant-id ctx) :sheets sheets
                     :result result :events events :family family :canonical-writes writes
                     :reservations reservations :requests @(:requests server) :server-errors @(:errors server)}]
          (reset! last-proof proof)
          (spit (io/file directory "proof.edn") (pr-str proof))
          (is (empty? (:server-errors proof)) "Loopback served only the original strict contracts")
          (is (false? (get-in researcher [:rlm :checkpointed?])) "The actual started researcher explicitly opts out")
          (is (= #{:audit :extract :decision :prime} (set (map :kind (:requests proof))))
              "Ordinary prefix plus actual researcher prime exhaust capacity")
          (is (= 4 (count (:requests proof))) "Inline call five never reaches HTTP")
          (is (some #(= (into (:sources pack) (:lookup-sources pack)) (get-in % [:facts :records])) writes)
              "Rich ordinary extraction completed before the denied inline call")
          (is (= 4 (count reservations)) "Opt-out calls retain durable accounting")
          (is (= 4 (count (filter #(= :ordinary (:admission-kind %)) reservations)))
              "No checkpoint campaign is synthesized to count the four calls")
          (is (every? #(not-any? (partial contains? %) [:campaign-sheet-id :campaign-tick-id :campaign-node-id :ownership-epoch]) reservations)
              "Generic admissions do not manufacture campaign ownership")
          (is (every? (fn [r]
                        (let [start (get by-id (:node-start-id r))]
                          (and (= :sheet/node-execution-started (:event/type start))
                               (= tick-id (:budget-tick-id r))
                               (= (select-keys r [:sheet-id :tick-id :node-id])
                                  (select-keys start [:sheet-id :tick-id :node-id]))
                               (= (or (:exec-context r) {})
                                  (select-keys (:inputs start)
                                    [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                     :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                                     :ai.obney.orc.orc-service.core.todo-processors/tick-iteration]))))) reservations)
              "Every admission retains the actual canonical node start and item context")
          (is (empty? (filter #(contains? #{:rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed
                                            :rlm/researcher-resume-state-saved} (:event/type %)) events))
              "Checkpoint opt-out still disables durable campaign recovery facts")
          (is (= :failure (:status result)))
          (is (= "LLM call budget exceeded: 4/4" (:error result)))
          (is (= #{tick-id (:tick-id research-start)} (set (map :tick-id cancellation)))
              "Both original root and active researcher child cancel durably")))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^ExecutorService (:pool server))
        (.awaitTermination ^ExecutorService (:pool server) 10 TimeUnit/SECONDS)))))
