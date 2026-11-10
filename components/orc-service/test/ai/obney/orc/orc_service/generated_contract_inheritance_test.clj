(ns ai.obney.orc.orc-service.generated-contract-inheritance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def contract
  [:map {:closed true :description "Complete measured result, preserving explicit empty values."}
   [:detail [:map {:closed true :description "Original measurement details."}
             [:count [:int {:description "Measured count; zero remains explicit."}]]
             [:active [:boolean {:description "False means explicitly inactive."}]]
             [:note [:maybe {:description "Null means explicitly withheld, not absent."} :string]]]]])
(def value {:detail {:count 0 :active false :note nil}})
(defn receive [{:keys [inputs]}] {:received (:summary inputs)})
(defn emitted [extra]
  [:sequence [:llm (merge {:instruction "Preserve the complete supplied packet as summary."
                          :reads [:packet] :writes [:summary]} extra)]
   [:final {:keys [:summary]}]])

(defn run-case! [tree]
  (let [dir (str (java.nio.file.Files/createTempDirectory "generated-contract-" (make-array java.nio.file.attribute.FileAttribute 0)) "/")
        requests (atom []) errors (atom []) primes (atom 0)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  _ (swap! requests conj request)
                  prime? (contains? (get-in request [:response_format :json_schema :schema :properties]) :code)
                  response (if prime?
                             {:reasoning "Execute the requested typed child."
                              :code (if (= 1 (swap! primes inc))
                                      (str "(emit-tree! " (pr-str tree) ")")
                                      "(final! {:summary (get-var :summary)})")}
                             value)
                  bytes (.getBytes (json/generate-string
                                    {:id (str (random-uuid)) :model "fixture/inherited-contract"
                                     :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string response)}}]
                                     :usage {:prompt_tokens 1 :completion_tokens 1}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [body (.getResponseBody exchange)] (.write body bytes)))
            (catch Throwable e
              (swap! errors conj (.getMessage e))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! :generated-contract
        {:provider :openrouter :model "fixture/inherited-contract" :output-mode :json-schema
         :config {:api-key "fixture" :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}})
      (h/with-async-test-context [ctx {:context {:llm-provider :generated-contract}
                                     :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite")}}]
        (let [sheet (bt/build-workflow! ctx
                      (bt/workflow "inherited-output-contract"
                        (bt/blackboard {:packet contract :summary contract :received contract})
                        (bt/sequence "compose"
                          (bt/repl-researcher "research" :model "fixture/inherited-contract"
                            :instruction "Emit the child, inspect its result, then return its typed summary."
                            :reads [:packet] :writes [:summary] :max-iterations 2
                            :options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}
                            :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inherited-contract"
                                  :sub-options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}})
                          (bt/code "consume" :fn "ai.obney.orc.orc-service.generated-contract-inheritance-test/receive"
                            :reads [:summary] :writes [:received]))))
              result (bt/execute ctx sheet {:packet value} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 4)
              events (h/read-all-events ctx)
              family (bt/get-execution-family ctx (:trace-id result))
              writes (mapv #(bt/value-log-resolve-writes ctx (:tenant-id ctx) (:tick-id %) %)
                           (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              proof {:path dir :sheet sheet :result result :events events :family family :writes writes
                     :requests @requests :errors @errors}]
          (spit (str dir "proof.edn") (pr-str proof))
          (println "GENERATED-CONTRACT-PROOF" dir)
          proof))
      (finally (.stop server 0)))))

(deftest absent-authored-output-is-inherited-without-null-seeding
  (let [{:keys [result events family writes requests sheet errors]} (run-case! (emitted {}))
        child (first (filter #(and (not= sheet (:sheet-id %)) (= :ai (:executor %))) family))
        declarations (filter #(and (= :sheet/key-declared (:event/type %)) (= (:sheet-id child) (:sheet-id %))) events)
        child-request (first (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests))]
    (is (empty? errors))
    (is (= :success (:status result)) (pr-str result))
    (is (= value (get-in result [:outputs :received])))
    (is (= contract (:schema (first (filter #(= :summary (:key %)) declarations)))))
    (let [start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                           (= (:tick-id child) (:tick-id %))) events))]
      (is (some? start) "Child start is present in canonical events")
      (is (contains? (:seed-sources start) :packet) "Inherited input has a canonical seed")
      (is (not (contains? (:seed-sources start) :summary)) "An absent output has no seed, including null"))
    (is (some #(= value (:summary %)) writes))
    (is (some #(= value (:received %)) writes))
    (is (str/includes? (json/generate-string (:response_format child-request)) "Measured count; zero remains explicit."))
    (is (str/includes? (str/join "\n" (map :content (:messages child-request))) "Original measurement details."))))

(deftest conflicting-inherited-description-is-rejected-before-child-dispatch
  (let [changed (assoc-in contract [2 1 2 1 1 :description] "A conflicting count meaning.")
        {:keys [requests]} (run-case! (emitted {:output-schemas {:summary changed}}))
        children (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests)]
    (is (empty? children) "Conflicting inherited meaning must never reach a child provider")
    (is (some #(str/includes? (pr-str (:messages %)) "Conflicting generated contract") requests))))

(deftest identical-inherited-and-duplicate-new-declarations-are-reused
  (let [tree [:sequence
              [:llm {:instruction "Copy the packet to draft." :reads [:packet] :writes [:draft]
                     :output-schemas {:draft contract :packet contract}}]
              [:llm {:instruction "Copy draft to summary." :reads [:draft] :writes [:summary]
                     :output-schemas {:draft contract :summary contract}}]
              [:final {:keys [:summary]}]]
        {:keys [result events family writes sheet requests errors]} (run-case! tree)
        child-sheet (:sheet-id (first (filter #(and (not= sheet (:sheet-id %)) (= :ai (:executor %))) family)))
        declarations (filter #(and (= :sheet/key-declared (:event/type %)) (= child-sheet (:sheet-id %))) events)]
    (is (empty? errors))
    (is (= :success (:status result)) (pr-str result))
    (is (= value (get-in result [:outputs :received])))
    (is (= {:packet contract :draft contract :summary contract}
           (into {} (map (juxt :key :schema) declarations))))
    (is (= 3 (count declarations)) "Each resolved key is declared only once")
    (is (= 2 (count (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests))))
    (is (some #(= value (:draft %)) writes))))

(deftest conflicting-duplicate-new-declarations-are-rejected-before-child-dispatch
  (let [tree [:sequence
              [:llm {:instruction "Copy packet." :reads [:packet] :writes [:draft]
                     :output-schemas {:draft contract}}]
              [:llm {:instruction "Copy draft." :reads [:draft] :writes [:summary]
                     :output-schemas {:draft :string}}]
              [:final {:keys [:summary]}]]
        {:keys [requests events sheet]} (run-case! tree)]
    (is (every? #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests))
    (is (some #(str/includes? (pr-str (:messages %)) "Conflicting generated contract for :draft") requests))
    (is (not-any? #(and (= :sheet/sheet-created (:event/type %)) (not= sheet (:sheet-id %))) events)
        "Conflicting declarations fail before even creating a child sheet")))
