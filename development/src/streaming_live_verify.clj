(ns streaming-live-verify
  "Live end-to-end verification of ORC streaming against a real LLM.

   Needs OPENROUTER_API_KEY. Run:

     clj -M:dev -e \"(require 'streaming-live-verify) (streaming-live-verify/run!)\"

   Token-delta verification additionally needs the NEW DSCloj
   (predict-stream-v2) + litellm-clj (usage-in-stream) on the classpath —
   override via:

     clj -Sdeps '{:aliases {:local-llm {:override-deps
       {io.github.ObneyAI/DSCloj {:local/root \"../DSCloj\"}
        tech.unravel/litellm-clj {:local/root \"../litellm-clj\"}}}}}' \\
       -M:dev:local-llm -e \"(require 'streaming-live-verify) (streaming-live-verify/run!)\"

   With the stock (older) DSCloj the capability gate falls back to blocking
   execution: the run still verifies lifecycle/RLM streaming, just without
   :llm-fields events."
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [litellm.router :as litellm-router]
            [clojure.core.async :as async]
            [clojure.string :as str]))

(def model "google/gemini-3-flash-preview")

(defn- register-provider! []
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))]
    (litellm-router/register! :openrouter
                              {:provider :openrouter
                               :model model
                               :config {:api-base "https://openrouter.ai/api/v1"
                                        :api-key api-key}})))

(defn- print-envelope [{:orc.stream/keys [type] :keys [seq node-name node-id status iteration
                                                       kind index total reason error fields text
                                                       final? child-tick-id]}]
  (println (format "%4d %-24s %s"
                   seq (name type)
                   (str/join " "
                             (remove nil?
                                     [(or node-name (some-> node-id str (subs 0 8)))
                                      (when status (str "status=" (name status)))
                                      (when iteration (str "iter=" iteration))
                                      (when (and kind index) (format "%s %d/%d" (name kind) index total))
                                      (when child-tick-id (str "child=" (subs (str child-tick-id) 0 8)))
                                      (when fields (str "fields=" (pr-str (update-vals fields #(if (and (string? %) (> (count %) 40))
                                                                                                 (str (subs % 0 40) "…")
                                                                                                 %)))))
                                      (when text (str "delta=" (pr-str (if (> (count text) 30) (str (subs text 0 30) "…") text))))
                                      (when final? "FINAL")
                                      (when reason (str "reason=" (name reason)))
                                      (when error (str "error=" error))])))))

(defn- watch! [events-ch]
  (loop [types []]
    (if-let [e (async/<!! events-ch)]
      (do (print-envelope e)
          (recur (conj types (:orc.stream/type e))))
      types)))

(defn- build-llm-sheet! [ctx]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "Live Stream LLM"))
                     :command-result/events first :sheet-id)]
    (doseq [k [:question :answer]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                     :command-result/events first :node-id)
          leaf-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
                      :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :ai :model model))
      (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id leaf-id
                                                                 "Answer the question in 2-3 sentences."))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:question] [:answer]))
      sheet-id)))

(defn- build-rlm-sheet! [ctx]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "Live Stream RLM"))
                     :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :numbers [:vector :int]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :total :int))
    (let [node-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher))
                      :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                             sheet-id node-id
                             "Compute the sum of the input numbers. Compute directly in code and call (final! {:total <sum>}). Do not emit a tree for this trivial task."
                             [:numbers] [:total] []
                             :model model
                             :max-iterations 5
                             :rlm {:recursive? true}))
      sheet-id)))

(defn run-llm-scenario! [ctx]
  (println "\n=== Scenario A: :llm leaf with token-delta opt-in ===")
  (let [sheet-id (build-llm-sheet! ctx)
        {:keys [events-ch result] :as sub}
        (sheet/execute-stream ctx sheet-id {:question "Why do behavior trees beat state machines for agent orchestration?"}
                              :timeout-ms 120000
                              :llm-deltas? true)]
    (if (:cognitect.anomalies/category sub)
      (println "SUBSCRIBE FAILED:" sub)
      (let [types (watch! events-ch)
            r (deref result 1000 :no-result)]
        (println "result status:" (:status r) " usage:" (:usage r))
        (println "answer:" (some-> (get-in r [:outputs :answer]) (subs 0 (min 120 (count (get-in r [:outputs :answer]))))))
        (println "event types seen:" (frequencies types))
        {:status (:status r) :types types}))))

(defn run-rlm-scenario! [ctx]
  (println "\n=== Scenario B: recursive RLM node (live Phase 1 events) ===")
  (let [sheet-id (build-rlm-sheet! ctx)
        {:keys [events-ch result] :as sub}
        (sheet/execute-stream ctx sheet-id {:numbers [3 7 11 21]}
                              :timeout-ms 300000)]
    (if (:cognitect.anomalies/category sub)
      (println "SUBSCRIBE FAILED:" sub)
      (let [types (watch! events-ch)
            r (deref result 1000 :no-result)]
        (println "result status:" (:status r) " outputs:" (:outputs r) " usage:" (select-keys (:usage r) [:total-tokens]))
        (println "event types seen:" (frequencies types))
        {:status (:status r) :types types}))))

(defn run! []
  (register-provider!)
  (let [ctx (h/create-async-test-context)]
    (try
      (let [a (run-llm-scenario! ctx)
            b (run-rlm-scenario! ctx)]
        (println "\n=== VERDICT ===")
        (println "Scenario A status:" (:status a)
                 "| token streaming:" (if (some #{:llm-fields} (:types a)) "LIVE" "fallback (old DSCloj)"))
        (println "Scenario B status:" (:status b)
                 "| RLM live events:" (boolean (some #{:rlm-iteration-started} (:types b)))
                 "| code events:" (boolean (some #{:rlm-code-generated} (:types b)))))
      (finally
        (h/stop-async-context ctx)
        (shutdown-agents)))))
