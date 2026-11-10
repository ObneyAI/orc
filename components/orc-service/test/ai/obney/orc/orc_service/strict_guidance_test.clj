(ns ai.obney.orc.orc-service.strict-guidance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def entry-meaning "Evidence selected by the task (keep authored parentheses).")
(def schema-meaning "Complete records with exact typed source identities.")
(def record-schema
  [:map {:closed true :description "One complete source record."}
   [:identity [:map {:closed true}
               [:same [:string {:description "Keyword slot preserves source label."}]]
               ["same" [:int {:description "String slot preserves zero."}]]
               ["__sio_key_0" [:string {:description "Literal reserved-looking source field."}]]]]
   [:metrics [:map-of {:description "Dictionary indexed by original typed identities."}
              [:enum 0 "0" :same "same"] [:maybe :int]]]
   [:note {:optional true :description "Absence differs from present null."} [:maybe :string]]
   [:annotation {:optional true :description "An omitted annotation differs from a supplied literal string."} :string]])
(def contract [:map {:closed true}
               [:evidence {:description entry-meaning}
                [:vector {:description schema-meaning} record-schema]]
               [:a/name {:description "First namespaced field meaning."}
                [:enum {:description "First namespaced schema meaning."} "first"]]
               [:b/name [:enum {:description "Second namespaced identity."} "second"]]
               ["name" [:enum {:description "Literal string identity."} "string"]]
               [:name [:enum {:description "Literal keyword identity."} "keyword"]]
               [:__orc_field_0 [:enum {:description "Authored reserved-looking output name."} "reserved"]]])
(def record-value {:identity {:same "label" "same" 0 "__sio_key_0" "reserved"}
                   :metrics {0 0 "0" nil :same 11 "same" 21}})
(def value {:evidence [record-value (assoc record-value :note nil :annotation "Retain literal annotation.")]
            :a/name "first" :b/name "second" "name" "string" :name "keyword" :__orc_field_0 "reserved"})
(def wire-record {:identity {:__sio_key_1 "label" :__sio_key_2 0 :__sio_key_0 "reserved"}
                  :metrics [{:key {:type "number" :value 0} :value 0}
                            {:key {:type "string" :value "0"} :value nil}
                            {:key {:type "keyword" :value "same"} :value 11}
                            {:key {:type "string" :value "same"} :value 21}]})
(def wire-value {:evidence [(assoc wire-record :note {:presence "absent"} :annotation nil)
                           (assoc wire-record :note {:presence "present" :value nil}
                                              :annotation "Retain literal annotation.")]
                 :__orc_field_1 "first" :__orc_field_2 "second" :__orc_field_3 "string"
                 :__orc_field_4 "keyword" :__orc_field_0 "reserved"})
(defn receive [{:keys [inputs]}] {:received (:summary inputs)})
(defn emitted [extra]
  [:sequence [:llm (merge {:instruction "Preserve the complete supplied packet as summary."
                          :reads [:packet] :writes [:summary]} extra)]
   [:final {:keys [:summary]}]])

(defn run-case! [role inherited?]
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
                                      (str "(emit-tree! '" (pr-str (emitted {})) ")")
                                      "(final! {:summary (get-var :summary)})")}
                             wire-value)
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
        {:provider :openrouter :model "fixture/inherited-contract" :output-mode (when inherited? :json-schema)
         :config {:api-key "fixture" :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}})
      (h/with-async-test-context [ctx {:context {:llm-provider :generated-contract}
                                     :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite")}}]
        (let [sheet (bt/build-workflow! ctx
                      (bt/workflow "inherited-output-contract"
                        (bt/blackboard {:packet contract :summary contract :received contract})
                        (bt/sequence "compose"
                          (if (= role :emitted)
                          (bt/repl-researcher "research" :model "fixture/inherited-contract"
                            :instruction "Emit the child, inspect its result, then return its typed summary."
                            :reads [:packet] :writes [:summary] :max-iterations 2
                            :options (cond-> {:max-retries 0 :timeout-ms 10000} (not inherited?) (assoc :output-mode :json-schema))
                            :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inherited-contract"
                                  :sub-options (cond-> {:max-retries 0 :timeout-ms 10000} (not inherited?) (assoc :output-mode :json-schema))})
                            (bt/llm "ordinary" :instruction "Preserve complete packet as summary."
                              :reads [:packet] :writes [:summary]
                              :options (cond-> {:max-retries 0} (not inherited?) (assoc :output-mode :json-schema))
                              :retry {:max-attempts 1 :backoff-ms []}))
                          (bt/code "consume" :fn "ai.obney.orc.orc-service.strict-guidance-test/receive"
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


(deftest strict-guidance-uses-authored-meanings-and-paired-structure
  (doseq [[role inherited?] [[:ordinary false] [:ordinary true] [:emitted false] [:emitted true]]]
    (let [{:keys [result writes requests errors]} (run-case! role inherited?)
          child (first (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests))
          schema (get-in child [:response_format :json_schema :schema])
          desc (get-in schema [:properties :evidence :description])
          text (last (str/split (str/join "\n" (map :content (:messages child)))
                               #"Output contract \(JSON Schema\):"))]
      (is (empty? errors))
      (is (= :success (:status result)) (pr-str result))
      (is (= value (get-in result [:outputs :received])))
      (is (some #(= value (:received %)) writes))
      (is (not (contains? (first (get-in result [:outputs :received :evidence])) :annotation)))
      (is (= "Retain literal annotation." (get-in result [:outputs :received :evidence 1 :annotation])))
      (is (= #{:evidence :__orc_field_0 :__orc_field_1 :__orc_field_2 :__orc_field_3 :__orc_field_4}
             (set (keys (:properties schema)))))
      (doseq [[alias literal path meanings]
              [[:__orc_field_1 "first" [:summary :a/name] ["First namespaced field meaning." "First namespaced schema meaning."]]
               [:__orc_field_2 "second" [:summary :b/name] ["Second namespaced identity."]]
               [:__orc_field_3 "string" [:summary "name"] ["Literal string identity."]]
               [:__orc_field_4 "keyword" [:summary :name] ["Literal keyword identity."]]]]
        (let [property (get-in schema [:properties alias])]
          (is (= "string" (:type property)))
          (is (= [literal] (:enum property)))
          (is (str/includes? (:description property) (str "Original output path: " (pr-str path) ".")))
          (doseq [meaning meanings]
            (is (str/includes? (:description property) meaning))
            (is (str/includes? text meaning)))))
      (is (str/includes? desc entry-meaning))
      (is (str/includes? desc schema-meaning))
      (is (not (str/includes? desc "list of object with")) "No generated legacy structural summary")
      (doseq [meaning [entry-meaning schema-meaning "Keyword slot preserves source label."
                       "String slot preserves zero." "Literal reserved-looking source field."
                       "Dictionary indexed by original typed identities." "Absence differs from present null."
                       "An omitted annotation differs from a supplied literal string."] ]
        (is (str/includes? text meaning)))
      (is (= "array" (get-in schema [:properties :evidence :items :properties :metrics :type])))
      (is (= #{:__sio_key_0 :__sio_key_1 :__sio_key_2}
             (set (keys (get-in schema [:properties :evidence :items :properties :identity :properties]))))))))

(def control-proof (atom {}))

(deftest legacy-and-direct-authored-description-controls
  (let [board {:reply {:schema [:string {:description "Authored meaning (string examples)."}]}}
        spec (dissoc (executor/build-module {:name "control" :reads [] :writes [:reply]} board) :output-mapping)
        legacy-desc "Authored meaning (string examples). (string)"
        direct-desc "Caller owns this (list of object with {same: string, same: integer})."]
    (is (= legacy-desc (get-in spec [:outputs 0 :description])))
    (doseq [[mode direct?] [[:marker false] [:function false] [:strict true]]]
      (let [captured (atom [])
            request-spec (if direct? {:inputs [] :outputs [{:name :reply :spec :string :description direct-desc}]} spec)]
        (llm/register-provider! :guidance-control {:provider :openrouter :model "fixture/guidance" :config {:api-key "fixture"}})
        (with-redefs [http/post
                      (fn [_ opts]
                        (swap! captured conj (:body opts))
                        (future {:status 200 :body
                                 {:choices [{:finish_reason "stop" :message
                                             (if (= mode :function)
                                               {:role "assistant" :tool_calls [{:id "call" :type "function"
                                                 :function {:name "submit_response" :arguments "{\"reply\":\"ok\"}"}}]}
                                               {:role "assistant" :content (if (= mode :strict)
                                                  "{\"reply\":\"ok\"}" "[[ ## reply ## ]]\nok")})}]
                                  :usage {:prompt_tokens 1 :completion_tokens 1}}}))]
          (doseq [prepared (if direct? [request-spec]
                             [request-spec
                              (update request-spec :outputs
                                      #(mapv (fn [field] (dissoc field :orc/strict-description)) %))])]
            (is (= {:reply "ok"}
                   (llm/predict :guidance-control prepared {}
                     (cond-> {:use-function-calling? (= mode :function)}
                       (= mode :strict) (assoc :output-mode :json-schema)))))))
        (swap! control-proof assoc mode @captured)
        (when-not direct?
          (is (= 2 (count @captured)))
          (is (= (first @captured) (second @captured))
              "Complete serialized adapter body is byte-identical with/without provenance"))
        (let [wire (json/parse-string (first @captured) true) text (pr-str wire)]
          (is (str/includes? text (if direct? direct-desc legacy-desc)))
          (when (= mode :function)
            (is (= legacy-desc (get-in wire [:tools 0 :function :parameters :properties :reply :description]))))
          (when direct?
            (is (= direct-desc (get-in wire [:response_format :json_schema :schema :properties :reply :description])))))))))

(deftest invalid-strict-output-is-still-rejected
  (let [spec (dissoc (executor/build-module {:name "invalid" :reads [] :writes [:summary]}
                      {:summary {:schema contract}}) :output-mapping)]
    (llm/register-provider! :guidance-invalid {:provider :openrouter :model "fixture/guidance" :output-mode :json-schema :config {:api-key "fixture"}})
    (with-redefs [http/post (fn [_ _] (future {:status 200 :body
                      {:choices [{:finish_reason "stop" :message {:role "assistant" :content "{}"}}]}}))]
      (is (thrown? clojure.lang.ExceptionInfo (llm/predict :guidance-invalid spec {}))))))
