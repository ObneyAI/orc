(ns ai.obney.orc.orc-service.finite-model-options-test
  "MODEL-CONTRACT-2: authored finite-node options reach the real HTTP boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def last-proof (atom nil))
(def routing {:only ["fixture-provider"] :allow_fallbacks false :data_collection "deny"})
(def authored-options
  {:output-mode :json-schema :max-tokens 32768 :max-retries 0 :timeout-ms 10000
   :extra-body {:provider routing}})

(defn- fixture-server []
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (Executors/newFixedThreadPool 2)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  model (:model body)
                  answer-key (if (str/includes? model "decision") :answer :result)
                  answer (= model "fixture/condition-allow")
                  output {answer-key answer}
                  strict? (contains? body :response_format)
                  tool? (contains? body :tools)]
              (swap! requests conj body)
              (if (str/ends-with? model "unavailable")
                (http/reply! exchange 503 "text/plain" "synthetic unavailable")
                (http/reply! exchange 200 "application/json"
                  (json/generate-string
                    {:id "finite-options-fixture" :model model
                     :choices [{:index 0 :finish_reason (if tool? "tool_calls" "stop")
                                :message (if tool?
                                           {:role "assistant" :content nil
                                            :tool_calls [{:id "fixture-call" :type "function"
                                                          :function {:name "submit_response"
                                                                     :arguments (json/generate-string output)}}]}
                                           {:role "assistant"
                                            :content (if strict? (json/generate-string output)
                                                       (str "[[ ## " (name answer-key) " ## ]]\n" answer
                                                            "\n[[ ## completed ## ]]"))})}]
                     :usage {:prompt_tokens 2 :completion_tokens 2 :total_tokens 4}}))))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor pool :requests requests
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn- finite-node [kind model options backups]
  (apply (if (= kind :condition) orc/llm-condition orc/llm-decision)
         (str (name kind) "-options")
         (mapcat identity
           (cond-> {:model model :instruction "Return whether the synthetic claim holds."
                    :reads [:claim] :options options}
             (= kind :decision) (assoc :writes [:answer])
             backups (assoc :fallback-models backups)))))

(defn- run-node [ctx kind suffix options backups budget]
  (let [model (str "fixture/" (name kind) "-" suffix)
        sheet (orc/build-workflow! ctx
                (orc/workflow (str (name kind) "-" suffix)
                  (orc/blackboard {:claim :string :answer :boolean})
                  (finite-node kind model options backups)))
        tick-id (random-uuid)
        result (orc/execute ctx sheet {:claim "Synthetic claim is false."}
                            :tick-id tick-id :max-ticks 1 :timeout-ms 30000 :llm-call-budget budget)
        events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
        completed (first (filter #(= :sheet/node-execution-completed (:event/type %)) events))]
    {:kind kind :result result :events events :completion completed
     :tick (orc/get-tick ctx tick-id)
     :writes (when completed (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick-id completed))}))

(deftest authored-finite-model-options-survive-primary-and-backup-dispatch
  (let [server (fixture-server) proofs (atom []) provider :finite-options-fixture]
    (try
      ;; No provider output-mode default: each explicit mode below must come
      ;; from the authored node rather than an inherited profile hiding loss.
      (llm/register-provider! provider {:provider :openrouter :model "fixture/default"
                                        :config {:api-base (:url server) :api-key http/fixture-token :timeout 10000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}}]
        (doseq [kind [:condition :decision] fallback? [false true]]
          (testing (str kind " fallback=" fallback?)
            (let [before (count @(:requests server))
                  backup (when fallback? [{:model (str "fixture/" (name kind) "-backup")
                                            :options {:max-tokens 65536}}])
                  proof (run-node ctx kind (if fallback? "unavailable" "primary") authored-options backup 2)
                  bodies (subvec @(:requests server) before)
                  completion (:completion proof)
                  attempts (filter #(= :sheet/provider-attempt-completed (:event/type %)) (:events proof))]
              (swap! proofs conj (assoc proof :requests bodies))
              (is (= (if fallback? 2 1) (count bodies)))
              (is (= (if fallback? [32768 65536] [32768]) (mapv :max_tokens bodies)))
              (doseq [body bodies]
                (is (= "json_schema" (get-in body [:response_format :type])))
                (is (true? (get-in body [:response_format :json_schema :strict])))
                (is (not (contains? body :tools)))
                (is (= (assoc routing :require_parameters true) (:provider body))))
              (is (= (count bodies) (count attempts)))
              (is (= :completed (get-in proof [:tick :status])))
              (is (= (if (= kind :condition) :failure :success) (get-in proof [:result :status])))
              (if (= kind :condition)
                (do (is (false? (:condition-answer completion)))
                    (is (nil? (:failure-kind completion)) "a valid false is not a provider error"))
                (is (= {:answer false} (:writes proof)))))))
        (testing "explicit marker mode wins over the finite-node function default"
          (let [before (count @(:requests server))
                proof (run-node ctx :condition "marker"
                        (-> authored-options (dissoc :output-mode) (assoc :use-function-calling? false)) nil 1)
                body (nth @(:requests server) before)]
            (swap! proofs conj proof)
            (is (not (contains? body :tools)))
            (is (not (contains? body :response_format)))
            (is (= 32768 (:max_tokens body)))
            (is (= routing (:provider body)))
            (is (false? (get-in proof [:completion :condition-answer])))))
        (testing "a shared host budget prevents the backup's physical call"
          (let [before (count @(:requests server))
                proof (run-node ctx :condition "budget-unavailable" authored-options
                        [{:model "fixture/condition-backup"}] 1)]
            (swap! proofs conj proof)
            (is (= :failure (get-in proof [:result :status])))
            (is (= 1 (- (count @(:requests server)) before)))))
        (testing "host deadline, ownership and accounting override authored lookalikes"
          (let [before (count @(:requests server))
                proof (run-node ctx :condition "allow"
                        (assoc authored-options :execution-deadline-ms 0 :tick-id (random-uuid)
                          :reserve-llm-call! nil :start-provider-attempt! nil :complete-provider-attempt! nil)
                        nil 1)
                attempts (filter #(= :sheet/provider-attempt-completed (:event/type %)) (:events proof))]
            (swap! proofs conj proof)
            (is (= :success (get-in proof [:result :status])))
            (is (= 1 (- (count @(:requests server)) before)))
            (is (= 1 (count attempts)))
            (is (= (get-in proof [:result :trace-id]) (:tick-id (first attempts)))))))
      (reset! last-proof @proofs)
      (finally (http/stop-server! server)))))
