(ns ai.obney.orc.orc-service.delegated-structured-contract-test
  "RECON-4: one strict HTTP contract crosses delegate and typed-consumer seams,
   then resolves identically after SQLite close/reopen with a fresh cache."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [litellm.router :as router]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private packet-schema
  [:map {:description "Typed result whose literal string field names are contractual."}
   ["choice" [:enum {:description "Selected disposition."
                      :descriptions {"accept" "Evidence supports proceeding."
                                     "defer" "More evidence is required."}}
              "accept" "defer"]]
   ["detail" [:maybe {:description "Required detail; null explicitly means unknown."} :string]]
   ["optional" {:optional true :description "Supplemental detail only when supplied."} :string]])

(def ^:private expected-packet {"choice" "accept" "detail" nil})

(defn receive-packet [{:keys [inputs]}]
  {:received (:packet inputs)})

(defn- completion [ctx tick-id node-id]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                       (= node-id (:node-id %)))
                 (bt/value-log-read-tick-events ctx (:tenant-id ctx) tick-id))))

(defn- evidence [ctx coordinates]
  (mapv (fn [[tick-id node-id]]
          (let [event (completion ctx tick-id node-id)]
            {:event-id (:event/id event)
             :tick-id (:tick-id event) :node-id (:node-id event)
             :reads (bt/value-log-resolve-reads ctx (:tenant-id ctx) tick-id event)
             :writes (bt/value-log-resolve-writes ctx (:tenant-id ctx) tick-id event)
             :write-sources (:write-sources event)}))
        coordinates))

(defn- node-id [ctx sheet-id node-name]
  (:id (first (filter #(= node-name (:name %)) (bt/get-nodes-for-sheet ctx sheet-id)))))

(deftest strict-contract-survives-delegate-consumer-and-cold-canonical-readback
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-delegated-contract-" (make-array java.nio.file.attribute.FileAttribute 0)))
        connection {:type :sqlite :database-file (str directory "/events.sqlite")}
        active (atom nil)
        requests (atom [])
        start! (fn [] (let [ctx (h/create-async-test-context
                                 {:event-store-conn connection
                                  :context {:llm-provider :delegated-strict-contract}})]
                       (reset! active ctx) ctx))
        stop! (fn [] (when-let [ctx @active]
                       (h/stop-async-context ctx) (reset! active nil)))]
    (llm/register-provider! :delegated-strict-contract
      {:provider :openrouter :model "controlled/model" :output-mode :json-schema
       :config {:api-key "synthetic-never-sent"}})
    (try
      (with-redefs [http/post
                    (fn [_ options]
                      (swap! requests conj (json/parse-string (:body options) true))
                      (future {:status 200
                               :body {:id "delegated-contract-response" :model "controlled/model"
                                      :choices [{:index 0 :finish_reason "stop"
                                                 :message {:role "assistant"
                                                           :content "{\"choice\":\"accept\",\"detail\":null,\"optional\":null}"}}]
                                      :usage {:prompt_tokens 5 :completion_tokens 4 :total_tokens 9}}}))]
        (let [ctx (start!)
              board {:packet packet-schema :received packet-schema}
              child (bt/build-workflow! ctx
                      (bt/workflow "recon-four-child" (bt/blackboard board)
                        (bt/llm "produce-contract" :reads [] :writes [:packet]
                          :instruction "Select the supported disposition and report its detail."
                          :retry {:max-attempts 1 :backoff-ms []}
                          :options {:max-retries 0 :max-tokens 256})))
              parent (bt/build-workflow! ctx
                       (bt/workflow "recon-four-parent" (bt/blackboard board)
                         (bt/sequence "consume-delegated-contract"
                           (bt/delegate "delegate-contract" :target-sheet-id child
                             :reads [] :writes [:packet])
                           (bt/code "typed-consumer"
                             :fn "ai.obney.orc.orc-service.delegated-structured-contract-test/receive-packet"
                             :reads [:packet] :writes [:received]))))
              result (bt/execute ctx parent {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)
              parent-tick (:trace-id result)
              delegate-id (node-id ctx parent "delegate-contract")
              delegate-event (completion ctx parent-tick delegate-id)
              child-tick (get-in delegate-event [:write-sources :packet :tick-id])
              coordinates [[child-tick (node-id ctx child "produce-contract")]
                           [parent-tick delegate-id]
                           [parent-tick (node-id ctx parent "typed-consumer")]]
              warm (evidence ctx coordinates)
              schema (get-in (first @requests) [:response_format :json_schema :schema])]
          (is (= :success (:status result)) (pr-str result))
          (is (= expected-packet (get-in result [:outputs :received])))
          (is (= 1 (count @requests)))
          (is (= ["accept" "defer"] (get-in schema [:properties :choice :enum])))
          (is (str/includes? (pr-str (first @requests)) "Evidence supports proceeding."))
          (is (some? child-tick) "delegate refers to the child's canonical value owner")
          (is (not= parent-tick child-tick))
          (is (= parent-tick (:parent-tick-id
                              (bt/value-log-tick-started-event ctx (:tenant-id ctx) child-tick))))
          (doseq [packet [(get-in warm [0 :writes :packet])
                          (get-in warm [1 :writes :packet])
                          (get-in warm [2 :reads :packet])
                          (get-in warm [2 :writes :received])]]
            (is (= expected-packet packet))
            (is (contains? packet "detail") "required null is present")
            (is (nil? (get packet "detail")))
            (is (not (contains? packet "optional")) "strict optional-null sentinel becomes absence")
            (is (= #{"choice" "detail"} (set (keys packet))) "declared string keys remain strings"))
          (stop!)
          (let [cold (start!)]
            (is (= warm (evidence cold coordinates))
                "fresh projections resolve the same event identities, values and cross-tick sources")
            (is (= :success (:root-status (bt/get-tick cold parent-tick)))))))
      (finally
        (stop!)
        (router/unregister! :delegated-strict-contract)
        (doseq [file (reverse (file-seq directory))] (.delete file))))))
