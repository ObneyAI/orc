(ns ai.obney.orc.orc-service.model-fallback-stream-test
  (:require [clojure.test :refer [deftest is]] [clojure.core.async :as a]
            [cheshire.core :as json]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.event-store-v3.interface :as es])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]))
(def proof (atom nil))
(defn drain [ch]
  (loop [xs []]
    (let [[v _] (a/alts!! [ch (a/timeout 30000)])]
      (if v (recur (conj xs v)) xs))))
(deftest stream-switches-only-before-output
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2) calls (atom []) results (atom [])]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler (handle [_ ex]
        (let [body (json/parse-string (slurp (.getRequestBody ex)) true) model (:model body)
              sse (fn [text] (str "data: " (json/generate-string {:model model :choices [{:index 0 :delta {:content text}}]}) "\n\n"))]
          (swap! calls conj model)
          (try
            (if (= model "fixture/startup")
              (do (.sendResponseHeaders ex 503 0) (.close (.getResponseBody ex)))
              (do
                (.set (.getResponseHeaders ex) "Content-Type" "text/event-stream")
                (.sendResponseHeaders ex 200 0)
                (with-open [out (.getResponseBody ex)]
                  (.write out (.getBytes (sse (if (= model "fixture/partial") "partial text" "[[ ## answer ## ]]\nbackup answer\n[[ ## completed ## ]]")) "UTF-8"))
                  (.flush out)
                  (when (= model "fixture/partial")
                    (.write out (.getBytes "data: {\"error\":{\"code\":503}}\n\n" "UTF-8")))
                  (when (= model "fixture/backup") (.write out (.getBytes "data: [DONE]\n\n" "UTF-8"))))))
            (catch java.io.IOException _) (finally (.close ex)))))))
    (.start server)
    (try
      (llm/register-provider! :stream-fallback-fixture {:provider :openrouter :model "fixture/startup"
        :config {:api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :api-key "fixture-only" :timeout 5000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :stream-fallback-fixture}}]
        (doseq [primary ["fixture/startup" "fixture/partial"]]
          (let [sheet (orc/build-workflow! ctx (orc/workflow primary
                        (orc/blackboard {:answer :string})
                        (orc/llm "answer" :model primary :fallback-models [{:model "fixture/backup"}]
                          :fallback-policy :all-transport :instruction "Answer" :reads [] :writes [:answer])))
                tick (random-uuid) {:keys [events-ch]} (orc/subscribe-execution ctx tick :llm-deltas? true :raw-deltas? true)
                done (runtime/register-completion! tick)
                sent (cp/process-command (assoc ctx :command {:command/name :sheet/tick-tree :command/id (random-uuid)
                       :command/timestamp (time/now) :sheet-id sheet :tick-id tick :inputs {} :options {:timeout-ms 30000}}))
                result (deref done 30000 ::timeout) envelopes (drain events-ch)
                events (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags #{[:tick tick]}}))
                completion (last (filter #(= :sheet/node-execution-completed (:event/type %)) events))]
            (swap! results conj {:primary primary :result result :envelopes envelopes :events events})
            (is (nil? (:cognitect.anomalies/category sent)))
            (is (= (if (= primary "fixture/startup") :success :failure) (:status result)) (pr-str result))
            (is (seq (filter #(= :llm-raw-delta (:orc.stream/type %)) envelopes)))
            (is (= (if (= primary "fixture/startup") :selected :output-exposed)
                   (get-in completion [:model-fallback :reason])) (pr-str completion))
            (when (= primary "fixture/partial")
              (is (= :transport-failure (:failure-kind completion)))
              (is (= 503 (get-in completion [:provider-evidence :transport :http-status])))))))
      (is (= ["fixture/startup" "fixture/backup" "fixture/partial"] @calls))
      (finally (reset! proof {:calls @calls :results @results}) (.stop server 0) (.shutdownNow pool)))))
