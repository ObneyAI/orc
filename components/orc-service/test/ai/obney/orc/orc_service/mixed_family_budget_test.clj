(ns ai.obney.orc.orc-service.mixed-family-budget-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.mixed-family-budget-fixture :as f])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(def last-proof (atom nil))

(defn start-server! [pack run-id directory]
  (let [requests (atom []) errors (atom []) primes (atom 0) ordinals (atom 0)
        pool (Executors/newFixedThreadPool 4)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange))
                  body (json/parse-string raw true)
                  schema (get-in body [:response_format :json_schema :schema])
                  props (:properties schema)
                  result (f/expected (assoc pack :run-id run-id))
                  summary (:assessment result)
                  kind (cond (contains? props :code) :prime
                             (contains? props :records) :extract
                             (contains? props :all-confirmed) :audit
                             (= #{:route} (set (keys props))) :decision
                             (contains? props :assessment) :synthesis
                             (or (contains? props :summary) (contains? props :ids)) :inline)
                  ordinal (swap! ordinals inc)
                  _ (f/guard! (<= ordinal 12) "Fixture safety cap (above native cap)")
                  response (case kind
                    :prime {:reasoning "Execute fixture mechanism."
                            :code (if (= 1 (swap! primes inc))
                                    "(llm \"reconcile-subcall\" :instruction \"Select freshest observed-at per claim in original source order, preserve complete records and copy facts.total, audit.unconfirmed-ids and run-id.\" :reads [:facts :audit :run-id] :writes [:summary])"
                                    "(final! {:research-result (get-var :summary)})")}
                    :extract {:extract-reasoning "All source records retained."
                              :records (into (:sources pack) (:lookup-sources pack)) :total (:total summary)}
                    :audit {:audit-reasoning "Independent confirmation audit."
                            :unconfirmed-ids (:unconfirmed-ids summary) :all-confirmed (empty? (:unconfirmed-ids summary))}
                    :decision {:route (:route result)}
                    :synthesis {:final-reasoning "Carry forward exact typed result." :assessment summary :route (:route result)}
                    :inline (if (contains? props :summary) {:summary summary} summary)
                    (throw (ex-info "Unexpected fixture schema" {:props props})))
                  wire-response (json/generate-string
                                  {:id (str (random-uuid)) :model (:model body)
                                   :choices [{:finish_reason "stop" :message {:role "assistant"
                                              :content (json/generate-string (f/fixture-wire-value schema response))}}]
                                   :usage {:prompt_tokens 1 :completion_tokens 1}})
                  record {:ordinal ordinal :kind kind :request body :response (json/parse-string wire-response true)}
                  bytes (.getBytes wire-response "UTF-8")]
              (swap! requests conj record)
              (spit (io/file directory (str "http-" ordinal ".edn")) (pr-str record))
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable t
              (swap! errors conj {:message (.getMessage t) :data (ex-data t)})
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest warm-mixed-family-denies-sixth-continuation-provider-call
  ;; FamilyAdmissionCountsOrdinaryAndCampaignCalls: warm portion only.
  ;; audit/extract/decision + prime + inline spend five; continuation prime is denied.
  ;; Replies preserve the authored rich graph and contracts; no predictor replacement.
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-mixed-budget-" (make-array java.nio.file.attribute.FileAttribute 0)))
        pack (first f/packs) run-id (str "conversation-ops-inline-" (random-uuid))
        tick-id (random-uuid) server (start-server! pack run-id directory)]
    (println "MIXED-BUDGET-PROOF" (.getAbsolutePath directory)) (flush)
    (try
      (llm/register-provider! :mixed-family-budget
        {:provider :openrouter :model "fixture/shared-model" :output-mode :json-schema
         :config {:api-key "fixture" :api-base (:url server) :timeout 30000}})
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite :database-file (str (io/file directory "store.sqlite")) :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :llm-provider :mixed-family-budget :experiment/unit-divisor 1
                        :experiment/read-source (fn [id]
                          (or (first (filter #(= id (:id %)) (:lookup-sources pack)))
                              (throw (ex-info "Unknown fixture source" {:id id}))))}}]
        (let [sheets (f/build! ctx "fixture/shared-model" :inline run-id)
              inputs (assoc (select-keys pack [:sources :purpose :missing-source-ids]) :run-id run-id)
              result (orc/execute ctx (:root sheets) inputs :tick-id tick-id :timeout-ms 90000 :max-ticks 1 :llm-call-budget 5)
              events (h/read-all-events ctx)
              family (orc/get-execution-family ctx tick-id)
              writes (mapv #(orc/value-log-resolve-writes ctx (:tenant-id ctx) (:tick-id %) %)
                           (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              reservations (orc/get-provider-call-reservations ctx (:root sheets) tick-id)
              proof {:directory (str directory) :root tick-id :tenant (:tenant-id ctx) :sheets sheets
                     :result result :events events :family family :canonical-writes writes
                     :reservations reservations :requests @(:requests server) :server-errors @(:errors server)}]
          (reset! last-proof proof)
          (spit (io/file directory "proof.edn") (pr-str proof))
          (is (empty? (:server-errors proof)) "Loopback must have served the actual strict schemas")
          (is (= #{:audit :extract :decision :prime :inline}
                 (set (map :kind (take 5 (:requests proof))))) "Actual mixed prefix spends all five calls")
          (is (some #(= (into (:sources pack) (:lookup-sources pack)) (get-in % [:facts :records])) writes)
              "Ordinary extraction retains every rich record before the exhausted frontier")
          (is (= 5 (count (:requests proof))) "Native budget must deny actual continuation request six")
          (is (= 1 (count (filter #(= :prime (:kind %)) (:requests proof)))) "No second prime crosses HTTP")
          (is (not-any? #(= :synthesis (:kind %)) (:requests proof)) "Synthesis cannot dispatch after exhaustion")
          (is (= 5 (count reservations)) "All five ordinary/campaign admissions share the durable ledger")
          (is (= :failure (:status result)))
          (is (= "LLM call budget exceeded: 5/5" (:error result)))
          (is (boolean (some #(and (= :sheet/tick-cancelled (:event/type %))
                          (= tick-id (:tick-id %)) (= "LLM call budget exceeded: 5/5" (:reason %))) events))
              "Original root cancellation is durable")))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^ExecutorService (:pool server))
        (.awaitTermination ^ExecutorService (:pool server) 10 TimeUnit/SECONDS)))))
