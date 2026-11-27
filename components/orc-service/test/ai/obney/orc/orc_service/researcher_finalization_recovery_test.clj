(ns ai.obney.orc.orc-service.researcher-finalization-recovery-test
  "Nested final-output errors remain recoverable before the research loop stops."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))

(def model "fixture/researcher-finalization-recovery")
(def last-proofs (atom []))
(def answer-schema
  [:map {:closed true :description "Complete native final answer, including negative and zero evidence."}
   [:ready? [:boolean {:description "Actual readiness, including false."}]]
   [:count [:int {:min 0 :description "Observed count, including zero."}]]
   [:comment [:maybe {:description "Explicit absence of a comment stays null."} :string]]
   [:evidence [:vector {:description "Complete evidence supporting the final answer."}
               [:map {:closed true :description "One native source observation."}
                [:source [:string {:description "Exact source identity."}]]
                [:accepted? [:boolean {:description "Observed acceptance, including false."}]]
                [:weight [:int {:min 0 :description "Actual evidence weight, including zero."}]]]]]
   [:note {:optional true} [:string {:description "Optional note may remain absent."}]]])
(def invalid-answer {:ready? false :count 0 :comment nil})
(def valid-answer
  {:ready? false :count 0 :comment nil
   :evidence [{:source "source-1" :accepted? false :weight 0}]})
(def programs [(str "(final! " (pr-str {:answer invalid-answer}) ")")
               (str "(final! " (pr-str {:answer valid-answer}) ")")])

(defn- start-http! [scripts]
  (let [requests (atom []) errors (atom []) pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  ordinal (count (swap! requests conj request))
                  _ (when-not (and (= model (:model request)) (<= ordinal 2))
                      (throw (ex-info "Unexpected local finalization dispatch" {:ordinal ordinal})))
                  fields (set (keys (get-in request [:response_format :json_schema :schema :properties])))
                  reply (cond-> {:code (nth scripts (dec ordinal))}
                          (contains? fields :reasoning)
                          (assoc :reasoning "Return the complete native answer under its declared nested contract."))
                  bytes (.getBytes (json/generate-string
                          {:id (str "finalization-recovery-" ordinal) :model model
                           :choices [{:finish_reason "stop" :message {:role "assistant"
                                      :content (json/generate-string reply)}}]
                           :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable error
              (swap! errors conj (ex-message error))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn- run-finalization!
  ([allowance] (run-finalization! allowance programs))
  ([allowance scripts]
  (let [directory (str (java.nio.file.Files/createTempDirectory "researcher-finalization-recovery-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        provider (keyword (str "finalization-recovery-" (random-uuid))) server (start-http! scripts)]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model model
         :config {:api-base (:url server) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "finalization-recovery-" (random-uuid))
                        (orc/blackboard {:answer answer-schema})
                        (orc/repl-researcher "return-typed-answer" :model model :writes [:answer]
                          :instruction "Return the complete native answer, preserving negative, zero, null and optional values."
                          :browser-tools [] :mcp-tools [] :max-iterations allowance
                          :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                          :rlm {:recursive? true :checkpointed? true :auto-classify? false
                                :timeouts {:provider-ms 10000 :iteration-ms 30000 :phase2-ms 30000 :campaign-ms 60000}})))
              tick (random-uuid)
              result (orc/execute ctx sheet {} :tick-id tick :timeout-ms 60000 :max-ticks 1 :llm-call-budget allowance)
              events (h/read-all-events ctx)
              completion (last (filter #(and (= tick (:tick-id %)) (= :sheet/node-execution-completed (:event/type %))) events))
              projected (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick completion)
              canonical-answer-writes (filterv #(and (= tick (:tick-id %)) (= :answer (:key %))
                                                      (= :sheet/execution-value-written (:event/type %))) events)
              iterations (filterv #(= :rlm/researcher-iteration-recorded (:event/type %)) events)
              proof {:directory directory :allowance allowance :result result :events events :projected projected
                     :canonical-answer-writes canonical-answer-writes :iterations iterations
                     :requests @(:requests server) :errors @(:errors server)}]
          (swap! last-proofs conj proof)
          (spit (str directory "/proof.edn") (pr-str proof))
          (println "RESEARCHER-FINALIZATION-RECOVERY-PROOF" allowance directory)
          proof))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (:pool server))
        (.awaitTermination ^java.util.concurrent.ExecutorService (:pool server) 10 TimeUnit/SECONDS))))))

(deftest nested-final-schema-error-reaches-next-request-before-valid-native-final-is-published
  (let [proof (run-finalization! 2)
        next-wire (pr-str (get-in proof [:requests 1 :messages]))]
    (is (empty? (:errors proof)) (pr-str (:errors proof)))
    (is (= 2 (count (:requests proof))))
    (is (= :success (get-in proof [:result :status])))
    (is (= valid-answer (get-in proof [:result :outputs :answer])))
    (is (= valid-answer (get-in proof [:projected :answer])) "Canonical parent output is the repaired native record")
    (is (= [valid-answer] (mapv :value (:canonical-answer-writes proof))) "Invalid nested candidate is never canonical")
    (is (= [:failure :success] (mapv #(get-in % [:iteration-record :status]) (:iterations proof))))
    (is (true? (:yielded? (first (filter #(= :rlm/researcher-resume-state-saved (:event/type %)) (:events proof)))))
        "Rejected final attempt checkpoints without ending the campaign")
    (is (true? (str/includes? next-wire "Blackboard schema validation failed for :answer")))
    (is (true? (str/includes? next-wire ":evidence")))
    (is (true? (str/includes? next-wire "missing required key")))
    (is (not (contains? (get-in proof [:projected :answer]) :note)) "Optional omission stays absent")
    (is (nil? (get-in proof [:projected :answer :comment])))
    (is (false? (get-in proof [:projected :answer :ready?])))
    (is (= 0 (get-in proof [:projected :answer :count])))))

(deftest exhausted-finalization-allowance-fails-without-publishing-invalid-nested-output
  (let [proof (run-finalization! 1)
        rejected (filter #(and (= :sheet/execution-value-rejected (:event/type %))
                               (= :answer (:key %)))
                         (:events proof))]
    (is (empty? (:errors proof)) (pr-str (:errors proof)))
    (is (= 1 (count (:requests proof))))
    (is (= :failure (get-in proof [:result :status])))
    (is (str/includes? (get-in proof [:result :error]) "Blackboard schema validation failed for :answer"))
    (is (= [invalid-answer] (mapv :value rejected)))
    (is (empty? (:canonical-answer-writes proof)))
    (is (not (contains? (:projected proof) :answer)))
    (is (= [:failure] (mapv #(get-in % [:iteration-record :status]) (:iterations proof))))))

(deftest runtime-function-in-typed-final-value-is-rejected-with-durable-repair-feedback
  (let [runtime-function-program (str/replace (second programs) ":comment nil" ":comment (fn [] :runtime-value)")
        proof (run-finalization! 2 [runtime-function-program (second programs)])
        next-wire (pr-str (get-in proof [:requests 1 :messages]))
        checkpoint (first (filter #(= :rlm/researcher-resume-state-saved (:event/type %)) (:events proof)))]
    (is (empty? (:errors proof)))
    (is (= 2 (count (:requests proof))) "Invalid runtime value cannot break checkpoint serialization or hide the repair request")
    (is (= :success (get-in proof [:result :status])))
    (is (= valid-answer (get-in proof [:projected :answer])))
    (is (= [valid-answer] (mapv :value (:canonical-answer-writes proof))))
    (is (= [:failure :success] (mapv #(get-in % [:iteration-record :status]) (:iterations proof))))
    (is (true? (:yielded? checkpoint)) "Bad final value is a nonterminal durable iteration")
    (is (true? (str/includes? next-wire "Blackboard schema validation failed for :answer")))
    (is (true? (str/includes? next-wire ":comment")))))

(deftest caught-invalid-final-attempt-clears-an-earlier-valid-capture-in-the-same-program
  (let [initial (assoc valid-answer :count 1)
        first-program (str "(do (final! " (pr-str {:answer initial}) ") "
                           "(try (final! " (pr-str {:answer invalid-answer}) ") (catch Exception e nil)))")
        proof (run-finalization! 2 [first-program (second programs)])]
    (is (empty? (:errors proof)))
    (is (= 2 (count (:requests proof))) "Catching the later refusal must not finalize the earlier stale candidate")
    (is (= [:success :success] (mapv #(get-in % [:iteration-record :status]) (:iterations proof)))
        "The first program catches the refusal; an uncaught evaluation error cannot explain the retry")
    (is (= :success (get-in proof [:result :status])))
    (is (= valid-answer (get-in proof [:projected :answer])))
    (is (= [valid-answer] (mapv :value (:canonical-answer-writes proof))) "Only the corrected second-turn value is canonical")))
