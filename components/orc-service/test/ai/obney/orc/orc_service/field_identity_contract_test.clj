(ns ai.obney.orc.orc-service.field-identity-contract-test
 (:require [clojure.test :refer [deftest is]]
           [cheshire.core :as json]
           [hato.client :as http]
           [ai.obney.grain.event-store-sqlite-v3.interface]
           [ai.obney.orc.llm.interface :as llm]
           [ai.obney.orc.orc-service.interface :as bt]
           [ai.obney.orc.orc-service.test-helpers :as h]))
(def left-schema [:map {:closed true} [:status [:enum {:description "Left record status."} "left"]]])
(def right-schema [:map {:closed true} [:status [:enum {:description "Right record status."} "right"]]])
(def identity-schema [:map {:closed true}
 [:a/name [:enum {:description "First namespaced identity."} "first"]]
 [:b/name [:enum {:description "Second namespaced identity."} "second"]]
 ["name" [:enum {:description "Literal string key identity."} "string"]]
 [:name [:enum {:description "Literal keyword key identity."} "keyword"]]
 [:__orc_field_0 [:enum {:description "Authored name resembling an internal alias."} "reserved"]]
 [:nested [:map {:closed true}
           [:same [:enum {:description "Nested keyword identity."} "nested-keyword"]]
           ["same" [:enum {:description "Nested string identity."} "nested-string"]]
           ["__sio_key_0" [:enum {:description "Nested authored reserved name."} "nested-reserved"]]]]])
(defn literal-response [schema]
 (if (contains? schema :enum) (first (:enum schema))
  (into {} (map (fn [[k child]] [k (literal-response child)])) (:properties schema))))
(defn consume [{:keys [inputs]}] {:received inputs})
(deftest flattened-field-identities-survive-real-runtime
 (let [calls (atom [])
       expected {:left {:status "left"} :right {:status "right"}
                 :identities {:a/name "first" :b/name "second" "name" "string" :name "keyword" :__orc_field_0 "reserved"
                  :nested {:same "nested-keyword" "same" "nested-string" "__sio_key_0" "nested-reserved"}}}]
  (llm/register-provider! :field-identity {:provider :openrouter :model "fixture/identity" :output-mode :json-schema :config {:api-key "fixture"}})
  (with-redefs [http/post
    (fn [_ opts]
     (let [request (json/parse-string (:body opts) true)
           props (get-in request [:response_format :json_schema :schema :properties])
           response (into {} (map (fn [[k schema]] [k (literal-response schema)]) props))]
      (swap! calls conj request)
      (future {:status 200 :body {:id "identity" :model "fixture/identity"
       :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content (json/generate-string response)}}]
       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}}))) ]
   (h/with-async-test-context [ctx {:context {:llm-provider :field-identity} :event-store-conn {:type :sqlite :database-file (str (java.nio.file.Files/createTempDirectory "identity-contract-" (make-array java.nio.file.attribute.FileAttribute 0)) "/store.sqlite")}}]
    (let [board {:left left-schema :right right-schema :identities identity-schema
                 :received [:map {:closed true} [:left left-schema] [:right right-schema] [:identities identity-schema]]}
          child (bt/build-workflow! ctx (bt/workflow "identity-child" (bt/blackboard board)
                  (bt/llm "produce" :instruction "Return the declared literal values." :reads [] :writes [:left :right :identities]
                   :options {:max-retries 0} :retry {:max-attempts 1 :backoff-ms []})))
          root (bt/build-workflow! ctx (bt/workflow "identity-root" (bt/blackboard board)
                 (bt/sequence "sequence"
                  (bt/delegate "delegate" :target-sheet-id child :reads [] :writes [:left :right :identities])
                  (bt/code "consume" :fn "ai.obney.orc.orc-service.field-identity-contract-test/consume" :reads [:left :right :identities] :writes [:received]))))
          r (bt/execute ctx root {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 1)]
     (is (= :success (:status r)) (pr-str r))
     (is (= expected (get-in r [:outputs :received])))
     (is (= 1 (count @calls)))
     (is (= 8 (count (get-in (first @calls) [:response_format :json_schema :schema :properties]))))
     (let [events (bt/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id r))
           completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
           writes (map #(bt/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id r) %) completions)]
      (is (some #(= expected (:received %)) writes))))))))
