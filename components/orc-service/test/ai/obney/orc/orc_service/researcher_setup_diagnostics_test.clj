(ns ai.obney.orc.orc-service.researcher-setup-diagnostics-test
  "Public setup refusal→HTTP feedback→repair, including a durable quantum boundary."
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))
(def last-proofs (atom []))
(def model "fixture/researcher-setup-diagnostics")
(def canary "UNRELATED-HANDLER-AUTH-SOURCE-CANARY")
(def bad-child [:llm {:instructions "Wrong emitted option is intentionally not an alias."
                       :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                       :reads [:source] :writes [:child-reasoning :answer]}])
(def good-child [:llm {:instruction "Read the supplied source and return its exact reference."
                        :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                       :reads [:source] :writes [:child-reasoning :answer]}])
(def programs [(str "(emit-tree! '" (pr-str bad-child) ")")
               (str "(emit-tree! '" (pr-str good-child) ")")
               "(final! {:answer (get-var :answer)})"])
(defn- run-case! [{:keys [quantum? omit-explanation?]}]
  (let [directory (str (java.nio.file.Files/createTempDirectory "researcher-setup-diagnostics-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        requests (atom []) errors (atom []) refusal (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (Executors/newFixedThreadPool 2) provider (keyword (str "setup-diagnostic-" (random-uuid)))
        actual-command cp/process-command]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  n (count (swap! requests conj request))
                  _ (assert (<= n 4) "Bounded local provider control")
                  reply (case n
                          1 {:code (first programs) :reasoning "Expose actual setup refusal."}
                          2 {:code (second programs) :reasoning "Correct the reported field without alias coercion."}
                          3 {:child-reasoning "The exact source reference is present." :answer "source-1"}
                          4 {:code (last programs) :reasoning "Return completed child result."})
                  bytes (.getBytes (json/generate-string
                          {:id (str "setup-control-" n) :model model
                           :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string reply)}}]
                           :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj (ex-message e))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! provider {:provider :openrouter :model model
        :config {:api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "setup-refusal-" (random-uuid))
                        (orc/blackboard {:source [:string {:description "Exact native source reference."}]
                                         :child-reasoning [:string {:description "Scoped child rationale."}]
                                         :answer [:string {:description "Exact reference actually consumed by the admitted semantic child."}]})
                        (orc/repl-researcher "repair-setup" :model model :reads [:source] :writes [:answer]
                          :instruction "Build a typed LLM child, inspect actual setup failures, repair its contract, then return its result."
                          :browser-tools [] :mcp-tools [] :max-iterations 3
                          :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                          :rlm (cond-> {:recursive? true :checkpointed? true :auto-classify? false
                                        :timeouts {:provider-ms 10000 :iteration-ms 30000 :phase2-ms 30000 :campaign-ms 120000}}
                                 quantum? (assoc :quantum {:max-iterations 1})))))
              node-id (:id (first (filter #(= :repl-researcher (:type %)) (orc/get-nodes-for-sheet ctx sheet))))
              tick (random-uuid)
              result (with-redefs [cp/process-command
                      (fn [context]
                        (let [r (actual-command context)]
                          (if (and (= :sheet/set-node-instruction (get-in context [:command :command/name]))
                                   (:cognitect.anomalies/category r))
                            (let [r (cond-> r omit-explanation? (dissoc :error/explain))]
                              (reset! refusal r)
                              ;; Only augment the REAL schema refusal. These synthetic
                              ;; unrelated fields must never enter model-visible diagnostics.
                              (assoc r :raw-command {:auth canary} :authorization canary
                                     :source-values [canary] :unrelated-handler-data (Object.)))
                            r)))]
                       (orc/execute ctx sheet {:source "source-1"} :tick-id tick :timeout-ms 120000 :max-ticks 1 :llm-call-budget 4))
              events (h/read-all-events ctx)
              claims (orc/get-researcher-effect-claims ctx sheet tick node-id)
              outcomes (mapv #(executor/decode-checkpoint-value [] (:result %))
                             (filter #(and (= :generated-child (:kind %)) (= :completed (:status %))) claims))
              refused (first (filter :preflight-rejected? outcomes))
              diagnostic (:setup-diagnostic refused)
              next-wire (pr-str (:messages (second @requests)))
              iterations (mapv :iteration-record (filter #(= :rlm/researcher-iteration-recorded (:event/type %)) events))
              first-record (first iterations)
              child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %)) (not= tick (:tick-id %))) events)
              child-ticks (set (map :tick-id child-starts))
              child-completions (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                              (contains? child-ticks (:tick-id %))) events)
              parent (last (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= tick (:tick-id %))) events))
              projected (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick parent)
              proof {:directory directory :quantum? quantum? :omit-explanation? omit-explanation?
                     :result result :events events :requests @requests :errors @errors :refusal @refusal
                     :outcomes outcomes :diagnostic diagnostic :iterations iterations}]
          (spit (str directory "/proof.edn") (pr-str proof)) (swap! last-proofs conj proof)
          (println "RESEARCHER-SETUP-DIAGNOSTICS-PROOF" directory)
          (is (empty? @errors))
          (is (= 4 (count @requests)))
          (is (= :success (:status result)))
          (is (= "source-1" (get-in result [:outputs :answer]) (:answer projected)))
          (is (= 1 (count child-starts)) "Refused setup never starts a child tick")
          (is (= 1 (count child-completions)) "Corrected LLM child executes exactly once")
          (is (= :sheet/set-node-instruction (:command-name diagnostic)))
          (is (= :cognitect.anomalies/incorrect (:category diagnostic)))
          (is (= :generated-child-setup-rejected (:reason diagnostic)))
          (is (= (:cognitect.anomalies/message @refusal) (:message diagnostic))
              "Existing public refusal message remains available even without field details")
          (is (str/includes? next-wire (:cognitect.anomalies/message @refusal)))
          (is (str/includes? next-wire ":sheet/set-node-instruction"))
          (is (str/includes? next-wire ":cognitect.anomalies/incorrect"))
          (is (str/includes? next-wire ":generated-child-setup-rejected"))
          (is (str/includes? next-wire "REJECTED BEFORE EXECUTION"))
          (is (not (str/includes? next-wire canary)))
          (is (not (str/includes? (pr-str diagnostic) canary)))
          (is (every? #{:command-name :category :reason :message :error/explain} (keys diagnostic)))
          (if omit-explanation?
            (do (is (not (contains? diagnostic :error/explain)))
                (is (not (str/includes? next-wire "should be a string"))))
            (do (is (= (:error/explain @refusal) (:error/explain diagnostic)))
                (is (str/includes? next-wire ":instruction"))
                (is (str/includes? next-wire "should be a string"))
                (is (str/includes? (:error-excerpt first-record "") ":instruction")
                    "Affected field survives the bounded immutable iteration excerpt")))
          (when quantum?
            (is (some #(and (= :rlm/researcher-resume-state-saved (:event/type %))
                            (= 1 (:next-iteration %)) (true? (:yielded? %))) events)
                "Actual first quantum yields after durable setup outcome")
            (is (> (count (filter #(= :rlm/researcher-frontier-claimed (:event/type %)) events)) 1)
                "A later frontier supplies the actual repair request"))))
      (finally (.stop server 0) (.shutdownNow pool) (.awaitTermination pool 10 TimeUnit/SECONDS)))))
(deftest setup-refusal-field-details-reach-real-request-and-canonical-repair (run-case! {}))
(deftest setup-diagnostics-survive-real-quantum (run-case! {:quantum? true}))
(deftest setup-without-field-explanation-excludes-unrelated-handler-data (run-case! {:omit-explanation? true}))
