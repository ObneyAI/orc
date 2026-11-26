(ns ai.obney.orc.orc-service.emitted-grammar-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.core.rlm-dsl :as dsl]
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

(defn run-case! [programs]
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
                              :code (nth programs (dec (swap! primes inc)) "(final! {:summary (get-var :summary)})")}
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
                            :reads [:packet] :writes [:summary] :max-iterations 4
                            :options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}
                            :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inherited-contract"
                                  :sub-options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}})
                          (bt/code "consume" :fn "ai.obney.orc.orc-service.emitted-grammar-test/receive"
                            :reads [:summary] :writes [:received]))))
              result (bt/execute ctx sheet {:packet value} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 6)
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


;; MODEL-CONTRACT-23. Only HTTP responses are scripted; commands, SQLite,
;; researcher checkpoint feedback, generated compilation and consumer are real.
(deftest malformed-final-rejects-before-child-and-next-prime-recovers
  (let [valid (emitted {})
        malformed (update valid 2 conj '荣耀)
        program #(str "(emit-tree! '" (pr-str %) ")")
        original (program malformed)
        {:keys [result events requests writes sheet errors]} (run-case! [original (program valid)])
        prime? #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code)
        primes (filterv prime? requests)
        feedback (str/join "\n" (map :content (:messages (second primes))))
        children (filterv #(and (= :sheet/sheet-created (:event/type %)) (not= sheet (:sheet-id %))) events)]
    (is (empty? errors))
    (is (= :success (:status result)))
    (is (= value (get-in result [:outputs :received])))
    (is (some #(= value (:received %)) writes) "Typed consumer value is durable")
    (is (= [true true false true] (mapv prime? requests)) "No child HTTP before correction")
    (is (= 1 (count children)) "No rejected child sheet is created")
    (is (= 3 (count primes)))
    (is (str/includes? feedback "Invalid emitted tree at [2]"))
    (is (str/includes? feedback "expected exactly 1 options map"))
    (is (str/includes? feedback original) "Actual next prime retains complete original source")))

(defn structural-error [tree]
  (try (dsl/rlm-dsl->orc-dsl tree) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest fixed-operands-and-nested-shapes-are-validated-eagerly
  (doseq [tag [:llm :code :final :chunk-document :aggregate :map-each]
          :let [valid (if (= tag :map-each) [tag {} [:final {}]] [tag {}])]
          invalid [(conj valid 'ignored) (pop valid) (assoc valid 1 :not-a-map)]]
    (testing (pr-str invalid)
      (let [error (structural-error [:parallel [:sequence invalid]])]
        (is (= :invalid-emitted-tree (:failure-kind error)))
        (is (= [1 1] (:node-path error)))
        (is (string? (:expected error))))))
  (doseq [invalid [nil [] '(:final {}) [:unrecognized {}] :final]]
    (is (= :invalid-emitted-tree (:failure-kind (structural-error invalid)))))
  (is (= [2] (:node-path (structural-error [:sequence [:code {}] [:final {} 'ignored]])))
      "Complete grammar is checked before earlier nodes' semantic transformation")
  (is (= [1 2] (:node-path (structural-error [:sequence [:map-each {} [:final {} 'ignored]]])))))

(deftest valid-variadic-and-unicode-options-remain-intact
  (is (= '(sheet/sequence) (dsl/rlm-dsl->orc-dsl [:sequence])))
  (is (= '(sheet/parallel) (dsl/rlm-dsl->orc-dsl [:parallel])))
  (let [opts {:instruction "荣耀 — preserve names" :reads [:来源] :writes [:résultat]
              :options {:temperature 0.2} :custom-data '荣耀}
        transformed (dsl/rlm-dsl->orc-dsl [:llm opts])]
    (is (= opts (apply hash-map (rest transformed)))))
  (is (= '(sheet/sequence (sheet/parallel (final! {:keys [:荣耀]})) (final! {:keys []}))
         (dsl/rlm-dsl->orc-dsl [:sequence [:parallel [:final {:keys [:荣耀]}]] [:final {:keys []}]]))))
