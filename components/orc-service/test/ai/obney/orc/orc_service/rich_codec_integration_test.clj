(ns ai.obney.orc.orc-service.rich-codec-integration-test
 (:require [clojure.test :refer [deftest is]] [cheshire.core :as json] [hato.client :as http]
 [ai.obney.grain.event-store-sqlite-v3.interface]
 [ai.obney.orc.llm.interface :as llm] [ai.obney.orc.orc-service.interface :as bt]
 [ai.obney.orc.orc-service.test-helpers :as h]))
(def packet-schema
 [:map {:closed true}
  [:variant [:or {:description "An explicit ready or waiting record."}
             [:map {:closed true} [:kind [:enum :ready]] [:count :int]]
             [:map {:closed true} [:kind [:enum :waiting]] [:reason [:maybe :string]]]]]
  [:note {:optional true :description "May be absent, explicitly null, or literal text."} [:maybe :string]]])
(defn receive [{:keys [inputs]}] {:received (:packet inputs)})
(deftest union-and-presence-through-public-orc
 (let [response (atom nil) requests (atom [])]
  (llm/register-provider! :rich-codec {:provider :openrouter :model "fixture/rich" :output-mode :json-schema :config {:api-key "fixture"}})
  (with-redefs [http/post (fn [_ opts]
   (swap! requests conj (json/parse-string (:body opts) true))
   (future {:status 200 :body {:id "rich" :model "fixture/rich"
    :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content (json/generate-string @response)}}]
    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
   (h/with-async-test-context [ctx {:context {:llm-provider :rich-codec}
    :event-store-conn {:type :sqlite :database-file (str (java.nio.file.Files/createTempDirectory "rich-codec-" (make-array java.nio.file.attribute.FileAttribute 0)) "/store.sqlite")}}]
    (let [sheet (bt/build-workflow! ctx (bt/workflow "rich-codec" (bt/blackboard {:packet packet-schema :received packet-schema})
                  (bt/sequence "chain"
                   (bt/llm "produce" :instruction "Return the supplied variant and preserve note presence." :reads [] :writes [:packet]
                    :options {:max-retries 0} :retry {:max-attempts 1 :backoff-ms []})
                   (bt/code "receive" :fn "ai.obney.orc.orc-service.rich-codec-integration-test/receive" :reads [:packet] :writes [:received]))))]
     (doseq [[wire expected] [[{:variant {:kind "ready" :count 0} :note {:presence "absent"}} {:variant {:kind :ready :count 0}}]
                             [{:variant {:kind "waiting" :reason nil} :note {:presence "present" :value nil}} {:variant {:kind :waiting :reason nil} :note nil}]
                             [{:variant {:kind "ready" :count 0} :note {:presence "present" :value ""}} {:variant {:kind :ready :count 0} :note ""}]]]
      (reset! response wire)
      (let [r (bt/execute ctx sheet {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 1)
            events (bt/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id r))]
       (is (= :success (:status r)) (pr-str r))
       (is (= expected (get-in r [:outputs :received])))
       (is (some (fn [e] (and (= :sheet/node-execution-completed (:event/type e))
                    (= expected (:received (bt/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id r) e))))) events))))
     (is (= 3 (count @requests)))
     (is (every? #(= 2 (count (get-in % [:response_format :json_schema :schema :properties :note :anyOf]))) @requests)))))))

(def dictionary-schema
 [:map-of {:description "Evidence indexed by its exact source identifier." :min 1 :max 4}
  [:string {:description "Exact external identifier, including punctuation."}]
  [:map {:closed true} [:count :int] [:enabled :boolean]
   [:note {:optional true} [:maybe :string]]]])
(deftest dynamic-dictionary-through-public-orc
 (let [requests (atom [])
       expected {"a/b" {:count 0 :enabled false :note nil}
                 "a.b" {:count 1 :enabled true}
                 "" {:count 2 :enabled false :note "{\"literal\":true}"}}
       wire {:packet [{:key "a/b" :value {:count 0 :enabled false :note {:presence "present" :value nil}}}
                      {:key "a.b" :value {:count 1 :enabled true :note {:presence "absent"}}}
                      {:key "" :value {:count 2 :enabled false :note {:presence "present" :value "{\"literal\":true}"}}}]}]
  (llm/register-provider! :rich-dictionary {:provider :openrouter :model "fixture/rich" :output-mode :json-schema :config {:api-key "fixture"}})
  (with-redefs [http/post (fn [_ opts]
    (swap! requests conj (json/parse-string (:body opts) true))
    (future {:status 200 :body {:id "dictionary" :model "fixture/rich"
      :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content (json/generate-string wire)}}]
      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
   (h/with-async-test-context [ctx {:context {:llm-provider :rich-dictionary}
     :event-store-conn {:type :sqlite :database-file (str (java.nio.file.Files/createTempDirectory "rich-dictionary-" (make-array java.nio.file.attribute.FileAttribute 0)) "/store.sqlite")}}]
    (let [sheet (bt/build-workflow! ctx (bt/workflow "rich-dictionary"
                   (bt/blackboard {:packet dictionary-schema :received dictionary-schema})
                   (bt/sequence "chain"
                    (bt/llm "produce" :instruction "Return the evidence dictionary." :reads [] :writes [:packet]
                     :options {:max-retries 0} :retry {:max-attempts 1 :backoff-ms []})
                    (bt/code "receive" :fn "ai.obney.orc.orc-service.rich-codec-integration-test/receive" :reads [:packet] :writes [:received]))))
          r (bt/execute ctx sheet {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 1)
          events (bt/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id r))]
     (is (= :success (:status r)) (pr-str r))
     (is (= expected (get-in r [:outputs :received])))
     (is (some (fn [e] (and (= :sheet/node-execution-completed (:event/type e))
                            (= expected (:received (bt/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id r) e))))) events))
     (is (= 1 (count @requests)))
     (is (not (clojure.string/includes? (json/generate-string (first @requests)) "Return a valid JSON object.")))
     (is (= "array" (get-in (first @requests) [:response_format :json_schema :schema :properties :packet :type]))))))))

(deftest all-optional-record-remains-a-present-empty-record
 (doseq [[schema content] [[[:map {:closed true} [:note {:optional true} [:maybe :string]]] "{\"note\":{\"presence\":\"absent\"}}"]
                           [[:map {:closed true}] "{\"packet\":{}}"]]]
  (llm/register-provider! :empty-record {:provider :openrouter :model "fixture/rich" :output-mode :json-schema :config {:api-key "fixture"}})
  (with-redefs [http/post (fn [_ _]
   (future {:status 200 :body {:id "empty-record" :model "fixture/rich"
    :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content content}}]
    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))]
   (h/with-async-test-context [ctx {:context {:llm-provider :empty-record}
     :event-store-conn {:type :sqlite :database-file (str (java.nio.file.Files/createTempDirectory "empty-record-" (make-array java.nio.file.attribute.FileAttribute 0)) "/store.sqlite")}}]
    (let [sheet (bt/build-workflow! ctx (bt/workflow "empty-record"
                 (bt/blackboard {:packet schema :received schema})
                 (bt/sequence "chain"
                  (bt/llm "produce" :instruction "Return the record with no note." :reads [] :writes [:packet]
                   :options {:max-retries 0} :retry {:max-attempts 1 :backoff-ms []})
                  (bt/code "receive" :fn "ai.obney.orc.orc-service.rich-codec-integration-test/receive" :reads [:packet] :writes [:received]))))
          r (bt/execute ctx sheet {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 1)]
     (is (= :success (:status r)) (pr-str r))
     (is (= {} (get-in r [:outputs :received])) (pr-str r))
     (is (some (fn [e] (and (= :sheet/node-execution-completed (:event/type e))
                            (= {} (:received (bt/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id r) e)))))
               (bt/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id r)))))))))
