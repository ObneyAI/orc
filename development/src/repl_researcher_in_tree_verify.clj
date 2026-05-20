(ns repl-researcher-in-tree-verify
  "Live verification that the :repl-researcher node type works as a CHILD
   in a larger behavior tree — not just as the root node.

   The tree we build:

     [:sequence
       [:leaf :ai pre-process]          ; an ordinary LLM that pre-processes
       [:repl-researcher rlm-node]       ; the researcher in the middle
       [:leaf :ai post-process]]         ; an ordinary LLM that consumes
                                           the researcher's output

   We verify:
     - All THREE nodes execute in order
     - The repl-researcher node reads what pre-process wrote
     - The post-process node reads what the repl-researcher wrote
     - The final result contains the post-process node's outputs"
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/repl-in-tree-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "verify"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store :cache cache :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  :event-pubsub ps
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc proc-name {:keys [handler-fn topics]}]
                                (assoc acc proc-name
                                       (tp/start {:event-pubsub ps :topics topics
                                                  :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defn run! []
  (let [ctx (create-context)]
    (try
      (executor/setup-providers!)
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "RR In Tree"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)

            ;; Declare blackboard keys: input → pre-output → rr-output → post-output
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :raw-input :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :cleaned-input :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :researched-summary :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :final-report :string))

            ;; Root :sequence
            seq-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
            seq-id (-> seq-r :command-result/events first :node-id)

            ;; Child 0 — ordinary :ai leaf "pre-process"
            pre-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id :index 0))
            pre-id (-> pre-r :command-result/events first :node-id)
            _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id pre-id :ai :model "google/gemini-2.5-flash"))
            _ (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id pre-id
                                      "Clean the raw input: remove extra whitespace and normalize punctuation. Return only the cleaned text."))
            _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id pre-id [:raw-input] [:cleaned-input]))

            ;; Child 1 — :repl-researcher in the MIDDLE
            rr-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id :index 1))
            rr-id (-> rr-r :command-result/events first :node-id)
            _ (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                                      sheet-id rr-id
                                      (str "You are given :cleaned-input (the pre-processed text). "
                                           "Produce a one-paragraph :researched-summary that highlights the "
                                           "key facts. Keep it concise.")
                                      [:cleaned-input]
                                      [:researched-summary]
                                      []
                                      :model "google/gemini-2.5-flash"
                                      :max-iterations 3
                                      :rlm {:debug? true}))

            ;; Child 2 — ordinary :ai leaf "post-process" that READS the researcher's output
            post-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id :index 2))
            post-id (-> post-r :command-result/events first :node-id)
            _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id post-id :ai :model "google/gemini-2.5-flash"))
            _ (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id post-id
                                      "Wrap the :researched-summary as a brief executive report with the header 'REPORT:' on the first line."))
            _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id post-id [:researched-summary] [:final-report]))

            input-text (str "  On May 15,, 2026   OpenAI announced a partnership with Microsoft to "
                            "release a new model called  GPT-5.  The agreement was signed by Sam "
                            "Altman (CEO  of OpenAI) and Satya Nadella (CEO of Microsoft) in Seattle.")
            _ (println "\n=== REPL-RESEARCHER IN TREE — LIVE VERIFY ===")
            _ (println "Tree shape:")
            _ (println "  [:sequence")
            _ (println "    [:leaf :ai pre-process     reads [:raw-input]          writes [:cleaned-input]]")
            _ (println "    [:repl-researcher          reads [:cleaned-input]      writes [:researched-summary]]")
            _ (println "    [:leaf :ai post-process    reads [:researched-summary] writes [:final-report]]]")
            _ (println "\nNode IDs:")
            _ (println "  pre-process: " pre-id)
            _ (println "  repl-researcher:" rr-id)
            _ (println "  post-process:" post-id)

            t0 (System/currentTimeMillis)
            result (sheet/execute ctx sheet-id {:raw-input input-text} :timeout-ms 180000)
            elapsed (- (System/currentTimeMillis) t0)

            _ (println "\nElapsed:" elapsed "ms")
            _ (println "Result status:" (:status result))
            _ (println "\n--- Each node's outputs (from the result) ---")
            _ (println "Pre-process :cleaned-input:")
            _ (println "  " (let [s (str (get-in result [:outputs :cleaned-input]))]
                              (subs s 0 (min 200 (count s)))))
            _ (println "\nResearcher :researched-summary:")
            _ (println "  " (let [s (str (get-in result [:outputs :researched-summary]))]
                              (subs s 0 (min 300 (count s)))))
            _ (println "\nPost-process :final-report:")
            _ (println "  " (let [s (str (get-in result [:outputs :final-report]))]
                              (subs s 0 (min 400 (count s)))))

            ;; Now verify via event store: did all 3 nodes complete with :success?
            tick-id (:trace-id result)
            events (when tick-id
                     (into [] (es/read (:event-store ctx)
                                {:tags #{[:tick tick-id]}
                                 :tenant-id (:tenant-id ctx)})))
            node-completions (->> events
                                  (filter #(= :sheet/node-execution-completed (:event/type %))))
            pre-c (first (filter #(= pre-id (:node-id %)) node-completions))
            rr-c (first (filter #(= rr-id (:node-id %)) node-completions))
            post-c (first (filter #(= post-id (:node-id %)) node-completions))

            _ (println "\n--- Per-node completion events ---")
            _ (println "pre-process: status=" (:status pre-c))
            _ (println "repl-researcher: status=" (:status rr-c))
            _ (println "post-process: status=" (:status post-c))

            all-three-completed? (and (= :success (:status pre-c))
                                      (= :success (:status rr-c))
                                      (= :success (:status post-c)))
            _ (println "\n=== VERIFICATION ===")
            _ (println "All 3 nodes completed :success?" all-three-completed?)
            _ (println "Final :final-report present and non-empty?"
                       (boolean (seq (str (get-in result [:outputs :final-report])))))
            _ (println "Result :status :success?" (= :success (:status result)))]
        result)
      (finally (stop-context ctx)))))

(comment (run!))
