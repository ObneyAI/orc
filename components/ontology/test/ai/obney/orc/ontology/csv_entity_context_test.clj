(ns ai.obney.orc.ontology.csv-entity-context-test
  "CSV-ENTITY-CONTEXT: actual authored stage, public SQLite/HTTP execution."
  (:require [clojure.test :refer [deftest is]] [cheshire.core :as json]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.sheets.csv-ontology :as csv]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]
           [java.nio.file Files] [java.util.concurrent Executors TimeUnit]))
(def model "fixture/csv-entity-context")
(def entities [{:name "Alpha" :source_columns ["alpha"] :description "Original Alpha entity."}
               {:name "Beta" :source_columns ["beta"] :description "Original Beta entity."}])
(def csv-data [{:alpha "a-one" :beta "b-one"} {:alpha "a-two" :beta "b-two"}])
(def last-proofs (atom []))
(defn definition-stage []
  (first (filter #(and (map? %) (= "generate-definitions" (:name %)))
                 (tree-seq coll? seq csv/csv-to-ontology-pipeline))))
(defn broken-topology [stage]
  ;; Preserve the exact old control mistake: two siblings where map-each starts
  ;; only its first direct child. Keep the corrected leaves otherwise unchanged.
  (if (= :sequence (get-in stage [:children 0 :node-type]))
    (assoc stage :children (get-in stage [:children 0 :children])) stage))
(defn pin [node]
  (cond-> node
    (= :leaf (:node-type node)) (assoc :model model :options {:output-mode :json-schema :max-retries 0 :max-tokens 1024})
    (:children node) (update :children #(mapv pin %))))
(defn run-stage! [negative?]
  (let [directory (str (Files/createTempDirectory "csv-entity-context-" (make-array java.nio.file.attribute.FileAttribute 0)))
        requests (atom []) inputs (atom []) errors (atom [])
        pool (Executors/newFixedThreadPool 2) server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        provider (keyword (str "csv-context-" (random-uuid)))]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [wire (json/parse-string (slurp (.getRequestBody exchange)) true)
                  n (count (swap! requests conj wire)) entity (nth entities (dec n))
                  reply {:definition-reasoning (str "CONTROLLED reasoning for " (:name entity))
                         :definition (str "Definition of " (:name entity))
                         :scope-note (str "Scope of " (:name entity)) :external-alignments [(str "ex:" (:name entity))]}
                  body (.getBytes (json/generate-string
                                    {:id (str "controlled-csv-" n) :model model
                                     :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string reply)}}]
                                     :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength body))
              (with-open [out (.getResponseBody exchange)] (.write out body)))
            (catch Throwable e (swap! errors conj (ex-message e)) (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! provider {:provider :openrouter :model model
                                      :config {:api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:llm-provider provider :tenant-id (random-uuid)}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [stage (definition-stage) stage (pin (if negative? (broken-topology stage) stage))
              definition (assoc csv/csv-to-ontology-pipeline :workflow-name (str "csv-entity-context-" (random-uuid)) :root-node stage)
              sheet (orc/build-workflow! ctx definition) predict llm/predict
              result (with-redefs [llm/predict (fn [p spec in & [opts]] (swap! inputs conj in) (predict p spec in opts))]
                       (orc/execute ctx sheet {:entities entities :csv-data csv-data :column-analysis [] :domain "Controlled domain"}
                                    :tick-id (random-uuid) :timeout-ms 30000 :max-ticks 1 :llm-call-budget 2))
              events (h/read-all-events ctx) tick (:trace-id result)
              completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= :map-each (:node-type %))) events))
              canonical (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick completion)
              io (mapv (fn [e] {:event e :inputs (orc/value-log-resolve-reads ctx (:tenant-id ctx) tick e)
                                :outputs (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick e)})
                       (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              proof {:directory directory :negative? negative? :result result :requests @requests :model-inputs @inputs :errors @errors
                     :canonical canonical :events events :node-io io}]
          (swap! last-proofs conj proof) (spit (str directory "/proof.edn") (pr-str proof))
          (println "CSV-ENTITY-CONTEXT-PROOF" negative? directory)
          proof))
      (finally (.stop server 0) (.shutdownNow pool) (is (.awaitTermination pool 10 TimeUnit/SECONDS))))))

(deftest two-entities-receive-their-own-context-and-complete-generated-definitions
  (let [p (run-stage! false) rows (get-in p [:canonical :definition-results])]
    (is (empty? (:errors p)))
    (is (= :success (get-in p [:result :status])) (pr-str (:result p)))
    (is (= 2 (count (:requests p))))
    (is (= entities (mapv :current-entity (:model-inputs p))))
    (is (= ["Alpha" "Beta"] (mapv :entity-name (:model-inputs p))))
    (is (= [["alpha"] ["beta"]] (mapv :source-columns (:model-inputs p))))
    (is (= [["a-one" "a-two"] ["b-one" "b-two"]] (mapv :sample-instances (:model-inputs p))))
    (is (= [["Beta"] ["Alpha"]] (mapv :related-entities (:model-inputs p))))
    (is (= ["Controlled domain" "Controlled domain"] (mapv :domain (:model-inputs p))))
    (is (= ["Alpha" "Beta"] (mapv :name rows)))
    (is (= ["Definition of Alpha" "Definition of Beta"] (mapv :definition rows)))
    (is (= ["Scope of Alpha" "Scope of Beta"] (mapv :scope-note rows)))
    (is (= [["ex:Alpha"] ["ex:Beta"]] (mapv :external-alignments rows)))
    (is (every? #(not-any? (set (keys %)) [:entities :csv-data :column-analysis :domain]) rows)
        "Global or another iteration's context must not leak into a collected entity")))

(deftest original-two-direct-child-topology-remains-an-observable-failure
  (let [p (run-stage! true)]
    (is (empty? (:errors p)))
    (is (= :failure (get-in p [:result :status])))
    (is (empty? (:requests p)) "The old second sibling is never executed")
    (is (not (contains? (:canonical p) :definition-results)))
    (is (some #(and (= :sheet/execution-value-rejected (:event/type %)) (= :definition-results (:key %))) (:events p)))))
