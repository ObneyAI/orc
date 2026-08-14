(ns ai.obney.orc.orc-service.deterministic-value-storage-e2e-test
  "Deterministic end-to-end coverage for structured and externally stored values."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.file-store.interface.protocol :as file-store]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.llm.interface :as llm]
            [litellm.router :as router]))

(def ^:private structured-schema
  [:map
   [:rows [:vector [:map [:id :int] [:tags [:vector :string]]]]]
   [:attributes [:map-of :keyword :string]]
   [:note {:optional true} :string]])

(def ^:private structured-value
  {:rows [{:id 1 :tags ["a" "b"]}
          {:id 2 :tags []}]
   :attributes {:source "fixture" :kind "nested"}})

(def ^:private large-value
  {:rows (mapv (fn [n] {:id n :tags [(apply str (repeat 128 (str n))) "stable"]})
               (range 12))
   :attributes {:source "large-fixture"}
   :note "preserve me exactly"})

(defrecord MemoryFileStore [objects]
  file-store/FileStore
  (start [this] this)
  (stop [this] this)
  (put-file [_ {:keys [file-id file-contents]}]
    (swap! objects assoc file-id file-contents)
    {})
  (get-file [_ {:keys [file-id]}]
    (get @objects file-id))
  (locate-file [_ {:keys [file-id]}]
    {:memory/key file-id}))

(defn echo-payload [{:keys [inputs]}]
  {:delegated (:payload inputs)})

(defn echo-row [{:keys [inputs]}]
  {:row (:row inputs)})

(defn copy-input [{:keys [inputs]}]
  {:output (:input inputs)})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-value-storage-e2e-test/"
       function-name))

(defn- writes-for-tick [ctx tick-id]
  (filterv #(= :sheet/execution-value-written (:event/type %))
           (h/read-tick-events ctx tick-id)))

(defn- trace-for [ctx result]
  (let [trace-id (:trace-id result)]
    (is (h/settle-until!
         #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                         [:query/result :trace]))))
    (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
            [:query/result :trace])))

(defn- copy-workflow [name]
  (sheet/workflow name
    (sheet/blackboard {:input structured-schema :output structured-schema})
    (sheet/code "copy" :fn (fq "copy-input") :reads [:input] :writes [:output])))

(defn- delegate-workflow! [ctx name target-id]
  (sheet/build-workflow!
   ctx
   (sheet/workflow name
     (sheet/blackboard {:input structured-schema :output structured-schema})
     (sheet/delegate "child" :target-sheet-id target-id
       :reads [:input] :writes [:output]))))

(defn- provider-number-workflow [name]
  (sheet/workflow name
    (sheet/blackboard
     {:academic-context [:map [:gpa :double]]})
    (sheet/llm "analyze-profile"
      :instruction "Return the supplied academic context."
      :writes [:academic-context]
      :options {:use-function-calling? true
                :retry-delay-ms 1})))

(defn- provider-boundary-workflow [name]
  (sheet/workflow name
    (sheet/blackboard {:answer :string})
    (sheet/llm "answer"
      :instruction "Return an answer."
      :writes [:answer]
      :options {:use-function-calling? true
                :force-tool-choice? true
                :max-retries 0
                :retry-delay-ms 1})))

(def ^:private optional-decision-schema
  [:map
   [:action [:enum :reply :invoke]]
   [:reply {:optional true} :string]
   [:capability {:optional true} :string]
   [:arguments {:optional true} [:map [:path :string]]]])

(defn- optional-decision-workflow [name function-calling?]
  (sheet/workflow name
    (sheet/blackboard {:decision optional-decision-schema})
    (sheet/llm "decide"
      :instruction "Choose one action and return only its applicable fields."
      :writes [:decision]
      :options {:use-function-calling? function-calling?
                :max-retries 0
                :retry-delay-ms 1})))

(defn- optional-null-workflow [name]
  (sheet/workflow name
    (sheet/blackboard
     {:decision [:map
                 [:action :keyword]
                 [:attention-updates {:optional true} [:vector :string]]
                 [:explanation {:optional true} :string]]
      :metadata [:map
                 [:request-id :string]
                 [:provider-note {:optional true} [:maybe :string]]]})
    (sheet/llm "normalize-null"
      :instruction "Return a decision and metadata."
      :writes [:decision :metadata]
      :options {:use-function-calling? true
                :max-retries 0
                :retry-delay-ms 1})))

(defn- execute-provider-values [ctx workflow-name provider-values]
  (let [remaining (atom provider-values)
        calls (atom 0)]
    [(with-redefs [llm/predict
                   (fn [_provider _module _inputs _options]
                     (swap! calls inc)
                     (let [provider-value (first @remaining)]
                       (swap! remaining #(if (next %) (next %) %))
                       {:outputs {:gpa provider-value}
                        :raw-response (str "provider response " provider-value)
                        :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                        :model "deterministic-provider"}))]
       (let [sheet-id (sheet/build-workflow! ctx (provider-number-workflow workflow-name))]
         (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                        sheet-id {})))
     @calls]))

(defn- execute-provider-number [ctx workflow-name provider-value]
  (first (execute-provider-values ctx workflow-name [provider-value])))

(defn- execute-provider-keyword-enum [ctx workflow-name provider-value]
  (let [modules (atom [])
        workflow
        (sheet/workflow workflow-name
          (sheet/blackboard
           {:decision [:map
                       [:outcome [:enum :changed :unchanged]]]})
          (sheet/llm "decide"
            :instruction "Return the learning decision."
            :writes [:decision]
            :options {:use-function-calling? true
                      :retry-delay-ms 1}))]
    [(with-redefs [llm/predict
                   (fn [_provider module _inputs _options]
                     (swap! modules conj module)
                     {:outputs {:outcome provider-value}
                      :raw-response (str "provider response " provider-value)
                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                      :model "deterministic-provider"})]
       (let [sheet-id (sheet/build-workflow! ctx workflow)]
         (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                        sheet-id {})))
     @modules]))

(def ^:private nested-keyword-enum-schema
  [:map
   [:action [:enum :invoke]]
   [:request [:map
              [:action [:= :beliefs]]
              [:scope [:enum :current :all]]]]
   [:items [:vector [:map [:action [:enum :invoke]]]]]
   [:choice [:or [:enum :invoke] :int]]
   [:constrained [:and :keyword [:enum :invoke]]]
   [:variant
    [:multi {:dispatch :type}
     [:invoke [:map
               [:type [:= :invoke]]
               [:scope [:enum :current :all]]]]]]
   [:string-choice [:enum "invoke" "skip"]]])

(defn- execute-nested-provider-keyword-enums
  [ctx workflow-name use-function-calling? provider-outputs]
  (let [calls (atom [])
        workflow
        (sheet/workflow workflow-name
          (sheet/blackboard {:decision nested-keyword-enum-schema})
          (sheet/llm "decide"
            :instruction "Return the nested decision."
            :writes [:decision]
            :options {:use-function-calling? use-function-calling?
                      :retry-delay-ms 1}))]
    [(with-redefs [llm/predict
                   (fn [_provider module _inputs options]
                     (swap! calls conj {:module module :options options})
                     {:outputs provider-outputs
                      :raw-response (pr-str provider-outputs)
                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                      :model "deterministic-provider"})]
       (let [sheet-id (sheet/build-workflow! ctx workflow)]
         (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                        sheet-id {})))
     @calls]))

(defn- failed-leaf-detail [ctx result]
  (let [trace (trace-for ctx result)
        leaf (some #(when (= :leaf (:node-type %)) %) (:node-traces trace))]
    (get-in (h/run-query ctx {:query/name :sheet/node-trace-detail
                              :trace-id (:trace-id result)
                              :trace-instance-id (:trace-instance-id leaf)})
            [:query/result])))

(deftest det-e2e-147-structured-provider-failure-evidence
  (testing "all structured outcomes remain distinct through execution and durable trace projection"
    (h/with-async-test-context [ctx]
      (let [execute-response
            (fn [suffix response]
              (with-redefs [router/completion (fn [& _] response)]
                (let [sheet-id (sheet/build-workflow!
                                ctx (provider-boundary-workflow
                                     (str "det-e2e-147-" suffix)))
                      result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                            sheet-id {})]
                  [result (failed-leaf-detail ctx result)])))
            usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}
            response (fn [id finish message]
                       {:id id :model "deterministic-model" :usage usage
                        :choices [{:finish-reason finish :message message}]})
            [valid valid-detail]
            (execute-response
             "valid"
             (response "resp-valid" "tool_calls"
                       {:tool-calls [{:function {:name "submit_response"
                                                 :arguments "{\"answer\":\"ok\"}"}}]}))
            [missing missing-detail]
            (execute-response "missing"
                              (response "resp-missing" "stop" {:content nil}))
            [malformed malformed-detail]
            (execute-response
             "malformed"
             (response "resp-malformed" "tool_calls"
                       {:tool-calls [{:function {:name "submit_response"
                                                 :arguments "{not-json"}}]}))
            [schema-invalid schema-detail]
            (execute-response
             "schema-invalid"
             (response "resp-schema" "tool_calls"
                       {:tool-calls [{:function {:name "submit_response"
                                                 :arguments "{\"answer\":42}"}}]}))
            [empty empty-detail]
            (execute-response "empty" {:id "resp-empty" :model "deterministic-model"
                                         :usage usage :choices []})
            [truncated truncated-detail]
            (execute-response "truncated"
                              (response "resp-truncated" "length" {:content nil}))]
        (is (= :success (:status valid)))
        (is (= "ok" (get-in valid [:outputs :answer])))
        (is (nil? (:failure-kind valid-detail)))

        (is (= :failure (:status missing)))
        (is (= :missing-forced-tool-call (:failure-kind missing-detail)))
        (is (= "resp-missing" (get-in missing-detail [:provider-evidence :response-id])))

        (is (= :failure (:status malformed)))
        (is (= :tool-call-parsing-failed (:failure-kind malformed-detail)))
        (is (= "submit_response"
               (get-in malformed-detail [:provider-evidence :tool-call-name])))

        (is (= :failure (:status schema-invalid)))
        (is (= :schema-validation-failed (:failure-kind schema-detail)))
        (is (= {:answer 42} (:rejected-outputs schema-detail)))

        (is (= :failure (:status empty)))
        (is (= :empty-provider-response (:failure-kind empty-detail)))

        (is (= :failure (:status truncated)))
        (is (= :missing-forced-tool-call (:failure-kind truncated-detail)))
        (is (true? (get-in truncated-detail [:provider-evidence :output-truncated?])))
        (is (= 10 (get-in truncated-detail [:provider-evidence :usage :total-tokens])))
        (is (not (contains? (:provider-evidence truncated-detail) :tool-arguments)))
        (is (not (contains? (:provider-evidence truncated-detail)
                            :raw-provider-response)))))))

(deftest det-e2e-148-optional-flattened-structured-outputs
  (testing "optional variants remain absent through both provider transports and durable projection"
    (h/with-async-test-context [ctx]
      (let [requests (atom [])
            execute-response
            (fn [suffix function-calling? message]
              (with-redefs [router/completion
                            (fn [_provider request]
                              (swap! requests conj request)
                              {:id (str "resp-" suffix)
                               :model "deterministic-model"
                               :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                               :choices [{:finish-reason (if function-calling? "tool_calls" "stop")
                                          :message message}]})]
                (let [sheet-id (sheet/build-workflow!
                                ctx
                                (optional-decision-workflow
                                 (str "det-e2e-148-" suffix)
                                 function-calling?))]
                  (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                 sheet-id {}))))
            marker-result
            (execute-response
             "marker-reply" false
             {:content "[[ ## action ## ]]\nreply\n[[ ## reply ## ]]\nDone."})
            tool-result
            (execute-response
             "tool-invoke" true
             {:tool-calls [{:function {:name "submit_response"
                                        :arguments (str "{\"action\":\"invoke\","
                                                        "\"capability\":\"files/read\","
                                                        "\"arguments\":{\"path\":\"/tmp/a\"}}")}}]})
            missing-required
            (execute-response
             "missing-action" false
             {:content "[[ ## reply ## ]]\nDone."})
            invalid-optional
            (execute-response
             "invalid-reply" true
             {:tool-calls [{:function {:name "submit_response"
                                        :arguments "{\"action\":\"reply\",\"reply\":42}"}}]})
            tool-request (some #(when (:tools %) %) @requests)
            marker-trace (trace-for ctx marker-result)
            tool-trace (trace-for ctx tool-result)
            marker-detail (failed-leaf-detail ctx marker-result)
            tool-detail (failed-leaf-detail ctx tool-result)]
        (is (= :success (:status marker-result)))
        (is (= {:action :reply :reply "Done."}
               (get-in marker-result [:outputs :decision])))
        (is (not (contains? (get-in marker-result [:outputs :decision]) :capability)))
        (is (= :success (:status marker-trace)))
        (is (= (:outputs marker-result) (:outputs marker-detail)))

        (is (= :success (:status tool-result)))
        (is (= {:action :invoke
                :capability "files/read"
                :arguments {:path "/tmp/a"}}
               (get-in tool-result [:outputs :decision])))
        (is (not (contains? (get-in tool-result [:outputs :decision]) :reply)))
        (is (= :success (:status tool-trace)))
        (is (= (:outputs tool-result) (:outputs tool-detail)))

        (is (= ["action"]
               (get-in tool-request [:tools 0 :function :parameters :required])))
        (is (= :failure (:status missing-required)))
        (is (= :failure (:status invalid-optional)))))))

(deftest det-e2e-149-optional-flattened-null-normalization
  (testing "provider null becomes absence only for optional non-nullable flattened entries"
    (h/with-async-test-context [ctx]
      (with-redefs [router/completion
                    (fn [_provider _request]
                      {:id "resp-optional-null"
                       :model "deterministic-model"
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                       :choices [{:finish-reason "tool_calls"
                                  :message
                                  {:tool-calls
                                   [{:function
                                     {:name "submit_response"
                                      :arguments
                                      (str "{\"action\":\"none\","
                                           "\"attention-updates\":null,"
                                           "\"explanation\":\"Nothing changed\","
                                           "\"request-id\":\"req-1\","
                                           "\"provider-note\":null}")}}]}}]})]
        (let [sheet-id (sheet/build-workflow!
                        ctx
                        (optional-null-workflow "det-e2e-149-optional-null"))
              result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                    sheet-id {})
              trace (trace-for ctx result)
              detail (failed-leaf-detail ctx result)]
          (is (= :success (:status result)))
          (is (= {:action :none :explanation "Nothing changed"}
                 (get-in result [:outputs :decision])))
          (is (not (contains? (get-in result [:outputs :decision])
                              :attention-updates)))
          (is (= {:request-id "req-1" :provider-note nil}
                 (get-in result [:outputs :metadata])))
          (is (= :success (:status trace)))
          (is (= (:outputs result) (:outputs detail))))))))

(deftest det-e2e-053-structured-nested-values
  (testing "nested vectors, maps, map-of values, and absent optional fields survive code, map-each, and delegate"
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow!
                      ctx
                      (sheet/workflow "det-e2e-053-child"
                        (sheet/blackboard {:payload structured-schema
                                           :delegated structured-schema})
                        (sheet/code "echo" :fn (fq "echo-payload")
                          :reads [:payload] :writes [:delegated])))
            parent-id (sheet/build-workflow!
                       ctx
                       (sheet/workflow "det-e2e-053-parent"
                         (sheet/blackboard {:payload structured-schema
                                            :delegated structured-schema
                                            :rows [:vector [:map [:id :int]
                                                             [:tags [:vector :string]]]]
                                            :row [:map [:id :int]
                                                  [:tags [:vector :string]]]
                                            :mapped [:vector [:map [:id :int]
                                                               [:tags [:vector :string]]]]})
                         (sheet/sequence "main"
                           (sheet/delegate "structured-child" :target-sheet-id child-id
                             :reads [:payload] :writes [:delegated])
                           (sheet/map-each "rows" :from :rows :as :row :into :mapped :parallel 2
                             (sheet/code "echo-row" :fn (fq "echo-row")
                               :reads [:row] :writes [:row])))))
            result (sheet/execute ctx parent-id
                                  {:payload structured-value
                                   :rows (:rows structured-value)})]
        (is (= :success (:status result)))
        (is (= structured-value (get-in result [:outputs :delegated])))
        (is (= (:rows structured-value) (get-in result [:outputs :mapped])))
        (is (not (contains? (get-in result [:outputs :delegated]) :note)))
        (is (= :success (:status (trace-for ctx result))))))))

(deftest det-e2e-054-file-store-canonical-values
  (testing "every canonical workflow value is externally stored and transparently resolvable"
    (let [objects (atom {}) store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-054"}
                        :orc/file-store store}}]
        (let [sheet-id (sheet/build-workflow! ctx (copy-workflow "det-e2e-054-file-store"))
              result (sheet/execute ctx sheet-id {:input large-value})
              writes (writes-for-tick ctx (:trace-id result))]
          (is (= :success (:status result)))
          (is (= large-value (get-in result [:outputs :output])))
          (is (seq writes))
          (is (every? #(and (:value-reference %) (not (contains? % :value))) writes))
          (is (= (count writes) (count @objects)))
          (doseq [write writes]
            (is (some? (value-log/resolve-source
                        ctx (:tenant-id ctx)
                        {:tick-id (:tick-id write) :event-id (:event/id write)})))))))))

(deftest det-e2e-055-tampered-value-reference
  (testing "changed stored bytes are rejected by integrity verification"
    (let [objects (atom {}) store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-055"}
                        :orc/file-store store}}]
        (let [sheet-id (sheet/build-workflow! ctx (copy-workflow "det-e2e-055-tamper"))
              result (sheet/execute ctx sheet-id {:input structured-value})
              output-write (some #(when (= :output (:key %)) %)
                                 (writes-for-tick ctx (:trace-id result)))
              file-id (get-in output-write [:value-reference :file-id])]
          (is (= structured-value (get-in result [:outputs :output])))
          (is (string? file-id))
          (swap! objects assoc file-id (byte-array [1 2 3]))
          (let [error (try
                        (value-log/resolve-source
                         ctx (:tenant-id ctx)
                         {:tick-id (:tick-id output-write) :event-id (:event/id output-write)})
                        nil
                        (catch Throwable t t))]
            (is (some? error))
            (is (re-find #"(?i)(size|hash|integrity|corrupt)" (ex-message error)))))))))

(deftest det-e2e-056-transitive-delegate-reference-resolution
  (testing "an externalized value crosses two delegate boundaries without descriptor leakage"
    (let [objects (atom {}) store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-056"}
                        :orc/file-store store}}]
        (let [child-id (sheet/build-workflow! ctx (copy-workflow "det-e2e-056-child"))
              middle-id (delegate-workflow! ctx "det-e2e-056-middle" child-id)
              parent-id (delegate-workflow! ctx "det-e2e-056-parent" middle-id)
              result (sheet/execute ctx parent-id {:input large-value})
              all-events (h/read-all-events ctx)
              refs (filter #(= :sheet/execution-value-referenced (:event/type %)) all-events)]
          (is (= :success (:status result)))
          (is (= large-value (get-in result [:outputs :output])))
          (is (not (contains? (:outputs result) :value-reference)))
          (is (>= (count refs) 2))
          (is (every? #(not (contains? % :value)) refs))
          (is (every? map? (map :source refs))))))))

(deftest det-e2e-057-concurrent-value-isolation
  (testing "concurrent externalized executions never cross values or references"
    (let [objects (atom {}) store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-057"}
                        :orc/file-store store}}]
        (let [sheet-id (sheet/build-workflow! ctx (copy-workflow "det-e2e-057-concurrent"))
              inputs (mapv #(assoc structured-value :note (str "execution-" %)) (range 10))
              gate (promise)
              runs (mapv (fn [input]
                           (future @gate (sheet/execute ctx sheet-id {:input input})))
                         inputs)]
          (deliver gate true)
          (let [results (mapv #(deref % 20000 ::timeout) runs)
                output-events (filter #(and (= :sheet/execution-value-written (:event/type %))
                                            (= :output (:key %)))
                                      (h/read-all-events ctx))
                file-ids (mapv #(get-in % [:value-reference :file-id]) output-events)]
            (is (not-any? #{::timeout} results))
            (is (= (repeat 10 :success) (map :status results)))
            (is (= inputs (mapv #(get-in % [:outputs :output]) results)))
            (is (= 10 (count output-events)))
            (is (= 10 (count (set file-ids))))
            (is (= (set inputs)
                   (set (map #(value-log/resolve-source
                               ctx (:tenant-id ctx)
                               {:tick-id (:tick-id %) :event-id (:event/id %)})
                             output-events))))))))))

(defn- normalized-trace [trace]
  {:status (:status trace)
   :inputs (:inputs trace)
   :outputs (:outputs trace)
   :nodes (mapv #(select-keys % [:node-id :node-name :node-type :status :inputs :outputs])
                (:node-traces trace))})

(deftest det-e2e-058-inline-file-store-equivalence
  (testing "storage placement changes neither public output nor observable trace semantics"
    (let [objects (atom {}) store (->MemoryFileStore objects)
          inline-result+trace
          (h/with-async-test-context [inline-ctx]
            (let [sheet-id (sheet/build-workflow! inline-ctx (copy-workflow "det-e2e-058-equivalence"))
                  result (sheet/execute inline-ctx sheet-id {:input large-value})]
              [result (trace-for inline-ctx result)]))]
      (h/with-async-test-context
        [file-ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-058"}
                             :orc/file-store store}}]
        (let [sheet-id (sheet/build-workflow! file-ctx (copy-workflow "det-e2e-058-equivalence"))
              file-result (sheet/execute file-ctx sheet-id {:input large-value})
              file-trace (trace-for file-ctx file-result)
              [inline-result inline-trace] inline-result+trace]
          (is (= (:status inline-result) (:status file-result)))
          (is (= (:outputs inline-result) (:outputs file-result)))
          (is (= (normalized-trace inline-trace) (normalized-trace file-trace)))
          (is (seq @objects)))))))

(deftest det-e2e-124-provider-output-normalization-and-rejection-evidence
  (testing "keyword enums are presented and decoded using canonical JSON spellings"
    (h/with-async-test-context [ctx]
      (let [[result modules]
            (execute-provider-keyword-enum
             ctx "det-e2e-124-keyword-enum" "changed")
            outcome-field (-> modules first :outputs first)]
        (is (= "outcome - one of: changed, unchanged"
               (:description outcome-field)))
        (is (= :success (:status result)))
        (is (= :changed (get-in result [:outputs :decision :outcome]))))))

  (testing "colon-prefixed EDN spellings are rejected and durably inspectable"
    (h/with-async-test-context [ctx]
      (let [[result modules]
            (execute-provider-keyword-enum
             ctx "det-e2e-124-colon-keyword-enum" ":changed")
            detail (failed-leaf-detail ctx result)]
        (is (= 2 (count modules)) "schema failure consumes the configured retry")
        (is (= :failure (:status result)))
        (is (= {:decision {:outcome ":changed"}}
               (:rejected-outputs detail)))
        (is (nil? (get-in result [:outputs :decision]))))))

  (testing "schema-equivalent JSON numbers become canonical before blackboard validation"
    (h/with-async-test-context [ctx]
      (let [result (execute-provider-number ctx "det-e2e-124-number" (long 3))]
        (is (= :success (:status result)))
        (is (= 3.0 (get-in result [:outputs :academic-context :gpa])))
        (is (double? (get-in result [:outputs :academic-context :gpa]))))))

  (testing "numeric strings fail without a canonical write and remain inspectable inline"
    (h/with-async-test-context [ctx]
      (let [result (execute-provider-number ctx "det-e2e-124-inline-rejected" "3.0")
            detail (failed-leaf-detail ctx result)
            events (h/read-tick-events ctx (:trace-id result))
            completion (some #(when (= :sheet/node-execution-completed (:event/type %)) %)
                             events)]
        (is (= :failure (:status result)))
        (is (nil? (get-in result [:outputs :academic-context])))
        (is (= {:academic-context {:gpa "3.0"}}
               (:rejected-outputs detail)))
        (is (empty? (:outputs detail)))
        (is (= "provider response 3.0"
               (:raw-response completion)))
        (is (not-any? #(and (= :sheet/execution-value-written (:event/type %))
                            (= :academic-context (:key %)))
                      events)))))

  (testing "file-store mode externalizes rejected evidence and rehydrates it through trace detail"
    (let [objects (atom {}) store (->MemoryFileStore objects)]
      (h/with-async-test-context
        [ctx {:context {:orc/value-storage {:type :file-store :prefix "det-e2e-124"}
                        :orc/file-store store}}]
        (let [result (execute-provider-number ctx "det-e2e-124-referenced-rejected" "3.0")
              events (h/read-tick-events ctx (:trace-id result))
              rejected (some #(when (= :sheet/execution-value-rejected (:event/type %)) %)
                             events)
              detail (failed-leaf-detail ctx result)]
          (is (= :failure (:status result)))
          (is (map? (:value-reference rejected)))
          (is (not (contains? rejected :value)))
          (is (= {:academic-context {:gpa "3.0"}}
                 (:rejected-outputs detail)))
          (is (seq @objects)))))))

(deftest det-e2e-125-provider-schema-failure-consumes-default-retry
  (testing "one schema-invalid response consumes the default retry and a valid retry succeeds"
    (h/with-async-test-context [ctx]
      (let [[result calls] (execute-provider-values
                            ctx "det-e2e-125-retry-success" ["3.0" (long 3)])]
        (is (= 2 calls))
        (is (= :success (:status result)))
        (is (= 3.0 (get-in result [:outputs :academic-context :gpa])))
        (is (= 4 (get-in result [:usage :total-tokens]))
            "usage accounts for both provider attempts"))))

  (testing "two invalid responses exhaust the default retry and persist only final evidence"
    (h/with-async-test-context [ctx]
      (let [[result calls] (execute-provider-values
                            ctx "det-e2e-125-retry-exhausted" ["first" "second"])
            detail (failed-leaf-detail ctx result)
            rejected-events (filter #(= :sheet/execution-value-rejected (:event/type %))
                                    (h/read-tick-events ctx (:trace-id result)))]
        (is (= 2 calls))
        (is (= :failure (:status result)))
        (is (= {:academic-context {:gpa "second"}}
               (:rejected-outputs detail)))
        (is (= 1 (count rejected-events)))
        (is (= 4 (get-in result [:usage :total-tokens]))
            "exhausted retry still accounts for both attempts")))))

(deftest det-e2e-129-orc-owned-llm-provider-boundary
  (let [providers (atom [])
        predict (fn [provider _spec _inputs _options]
                  (swap! providers conj provider)
                  {:outputs {:answer "structured answer"}
                   :raw-response "[[ ## answer ## ]]\nstructured answer"
                   :usage {:prompt-tokens 3 :completion-tokens 2 :total-tokens 5}
                   :model "adapter-model"})]
    (testing "the canonical key selects its provider and preserves public evidence"
      (h/with-async-test-context [ctx {:context {:llm-provider :selected-provider}}]
        (with-redefs [llm/predict predict]
          (let [sheet-id (sheet/build-workflow!
                          ctx (provider-boundary-workflow "det-e2e-129-canonical"))
                result (sheet/execute ctx sheet-id {})
                trace (trace-for ctx result)
                leaf (some #(when (= :leaf (:node-type %)) %) (:node-traces trace))]
            (is (= :selected-provider (first @providers)))
            (is (= :success (:status result)))
            (is (= "structured answer" (get-in result [:outputs :answer])))
            (is (= 5 (get-in result [:usage :total-tokens])))
            (is (= :success (:status leaf)))))))

    (testing "the removed key cannot select the value supplied under it"
      (h/with-async-test-context [ctx {:context {:dscloj-provider :removed-provider}}]
        (with-redefs [llm/predict predict]
          (let [sheet-id (sheet/build-workflow!
                          ctx (provider-boundary-workflow "det-e2e-129-removed-key"))
                result (sheet/execute ctx sheet-id {})]
            (is (= :success (:status result)))
            (is (= [:selected-provider :openrouter] @providers))
            (is (not-any? #{:removed-provider} @providers))))))))
(deftest det-e2e-150-nested-provider-keyword-enum-normalization
  (let [raw {:action "invoke"
             :request {"action" "beliefs" "scope" "current"}
             :items [{"action" "invoke"}]
             :choice "invoke"
             :constrained "invoke"
             :variant {"type" "invoke" "scope" "all"}
             :string-choice "invoke"}
        canonical {:action :invoke
                   :request {:action :beliefs :scope :current}
                   :items [{:action :invoke}]
                   :choice :invoke
                   :constrained :invoke
                   :variant {:type :invoke :scope :all}
                   :string-choice "invoke"}]
    (doseq [function-calling? [false true]]
      (testing (str "canonical values reach the projected blackboard via "
                    (if function-calling? "function calling" "marker output"))
        (h/with-async-test-context [ctx]
          (let [[result calls]
                (execute-nested-provider-keyword-enums
                 ctx
                 (str "det-e2e-150-" (if function-calling? "function" "marker"))
                 function-calling?
                 raw)]
            (is (= :success (:status result)))
            (is (= canonical (get-in result [:outputs :decision])))
            (is (= function-calling?
                   (get-in calls [0 :options :use-function-calling?])))
            (is (false? (get-in calls [0 :options :validate?]))))))))

  (testing "noncanonical nested values remain exact rejected-output evidence"
    (h/with-async-test-context [ctx]
      (let [raw {:action "invoke"
                 :request {:action "beliefs" :scope "current"}
                 :items [{:action "invoke"}]
                 :choice "invoke"
                 :constrained "invoke"
                 :variant {:type "invoke" :scope ":all"}
                 :string-choice "invoke"}
            [result calls]
            (execute-nested-provider-keyword-enums
             ctx "det-e2e-150-rejected" true raw)
            detail (failed-leaf-detail ctx result)]
        (is (= 2 (count calls)))
        (is (= :failure (:status result)))
        (is (= {:decision raw} (:rejected-outputs detail)))
        (is (nil? (get-in result [:outputs :decision])))))))
