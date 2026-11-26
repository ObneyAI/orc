(ns ai.obney.orc.orc-service.retained-inline-model-plan-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as p]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.researcher-models :as models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [litellm.router :as router]
            [ai.obney.grain.event-store-v3.interface :as es]))
(def proof (atom nil))
(def desired {:label "original-source" :count 2 :flags [true false]})
(def detail-schema [:map {:closed true :description "Exact source label and typed nested observation."}
                    [:label [:string {:description "Original source label, copied verbatim."}]]
                    [:count [:int {:description "Number of entries in flags."}]]
                    [:flags [:vector {:description "Ordered boolean observations."} :boolean]]])
(def code "(do (touch {}) (llm \"inline\" :instruction \"Copy the original source label with count 2 and flags [true false].\" :reads [:source] :writes [:detail]) (final! {:summary (get-var :detail)}))")
(defn response [body]
 (let [outer? (= "fixture/outer" (:model body))
       outputs (if outer? {:reasoning "Invoke one described inline specialist after the tool, then preserve its typed result." :code code} {:detail desired})
       tools? (seq (:tools body))
       content (if (:response_format body) (json/generate-string outputs)
                   (apply str (concat (mapcat (fn [[k v]] [(str "[[ ## " (name k) " ## ]]\n") (if (string? v) v (json/generate-string v)) "\n"]) outputs) ["[[ ## completed ## ]]\n"])))]
  {:id (if outer? "outer-response" "inline-response") :model (str (:model body) "-resolved")
   :choices [{:index 0 :finish_reason (if tools? "tool_calls" "stop")
              :message (if tools? {:role "assistant" :content nil :tool_calls [{:id "inline-call" :type "function" :function {:name "submit_response" :arguments (json/generate-string outputs)}}]} {:role "assistant" :content content})}]
   :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}}))
(deftest inline-inherits-paired-profile-through-configured-backup
 (let [out (or (System/getProperty "orc.probe.out") (str (java.nio.file.Files/createTempDirectory "retained-inline-" (make-array java.nio.file.attribute.FileAttribute 0)))) fixture (p/server! response)
       _ (assert (not (.exists (java.io.File. (str out "/inline-fresh-events.sqlite")))) "Fresh inline SQLite database required")
       outer (keyword (str "inline-outer-" (random-uuid))) primary (keyword (str "inline-primary-" (random-uuid))) backup (keyword (str "inline-backup-" (random-uuid)))
       tenant (random-uuid) tick (random-uuid) tool-count (atom 0) prefix-count (atom 0)
       config {:provider :openrouter :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 5000}}]
  (try
   (llm/register-provider! outer (assoc config :model "fixture/outer" :output-mode :json-schema))
   (llm/register-provider! primary (assoc config :model "fixture/prime" :output-mode :function-calling))
   (llm/register-provider! backup (assoc config :model "fixture/sub-backup"))
   (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str out "/inline-fresh-events.sqlite") :maximum-pool-size 2}
                                  :context {:tenant-id tenant :llm-provider outer :probe-effects prefix-count
                                            :call-tool-fn (fn [_ _ _] (swap! tool-count inc) {:ok true})}}]
    (let [sheet (orc/build-workflow! ctx
                  (orc/workflow (str "inline-retained-profile-" (random-uuid))
                   (orc/blackboard {:source [:string {:description "Source label supplied by the completed prefix."}] :detail detail-schema :summary detail-schema})
                   (orc/sequence "task"
                    (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!" :reads [] :writes [:source])
                    (orc/repl-researcher "research" :model "fixture/outer" :instruction "Use the typed inline specialist, preserving its result." :reads [:source] :writes [:summary]
                     :mcp-tools ["touch"] :tool-contracts {"touch" {:arguments [:map {:closed true}] :result [:map {:closed true} [:ok :boolean]] :checkpoint-safe? true}}
                     :options {:output-mode :json-schema :max-tokens 65536 :max-retries 0} :max-iterations 1
                     :rlm {:recursive? true :checkpointed? true :sub-provider primary :sub-model "fixture/prime"
                           :sub-options {:max-tokens 16384 :max-retries 0} :sub-fallback-models [{:provider backup :model "fixture/sub-backup"}]
                           :timeouts {:provider-ms 10000 :iteration-ms 30000 :campaign-ms 60000}}))))
          result (orc/execute ctx sheet {} :tick-id tick :timeout-ms 60000 :max-ticks 1 :llm-call-budget 3)
          events (orc/value-log-read-tick-events ctx tenant tick)
          bodies (mapv :body @(:requests fixture)) sub (filterv #(not= "fixture/outer" (:model %)) bodies)
          completed (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= :repl-researcher (:node-type %))) events))
          writes (when completed (orc/value-log-resolve-writes ctx tenant tick completed))]
     (reset! proof {:tenant tenant :tick-id tick :sheet-id sheet :result result :canonical-writes writes :events events :requests @(:requests fixture) :responses @(:responses fixture) :fixture-errors @(:errors fixture) :prefix-count @prefix-count :tool-count @tool-count})
     (is (empty? @(:errors fixture)))
     (is (= ["fixture/outer" "fixture/prime" "fixture/sub-backup"] (mapv :model bodies)))
     (is (= 2 (count sub)))
     (doseq [body sub]
      (is (= "auto" (:tool_choice body)))
      (is (= "submit_response" (get-in body [:tools 0 :function :name])))
      (is (true? (get-in body [:tools 0 :function :strict])))
      (is (= #{"detail"} (set (get-in body [:tools 0 :function :parameters :required]))))
      (is (= "object" (get-in body [:tools 0 :function :parameters :properties :detail :type])))
      (is (= "Exact source label and typed nested observation." (get-in body [:tools 0 :function :parameters :properties :detail :description])))
      (is (not (contains? body :response_format))))
     (is (= (get-in (first sub) [:tools 0 :function :parameters]) (get-in (second sub) [:tools 0 :function :parameters])))
     (is (= :success (:status result)) (pr-str result))
     (is (= desired (get-in result [:outputs :summary])))
     (is (= desired (:summary writes)))
     (is (= 1 @prefix-count))
     (is (= 1 @tool-count))
     (is (= 3 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))))
   (finally (p/stop-fixture! fixture)))))


(def proofs (atom []))
(defn inline-workflow [primary backup {:keys [quantum]}]
  (orc/workflow (str "retained-inline-" (random-uuid))
    (orc/blackboard {:source [:string {:description "Original source label."}]
                     :detail detail-schema :first detail-schema :summary detail-schema})
    (orc/sequence "task"
      (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!"
        :reads [] :writes [:source])
      (orc/repl-researcher "research" :model "fixture/outer"
        :instruction "Use the described inline specialist and preserve its native result."
        :reads [:source] :writes [:summary] :mcp-tools ["touch"]
        :tool-contracts {"touch" {:arguments [:map {:closed true}]
                                 :result [:map {:closed true} [:ok :boolean]] :checkpoint-safe? true}}
        :options {:output-mode :json-schema :max-tokens 65536 :max-retries 0}
        :max-iterations 3
        :rlm (cond-> {:recursive? true :checkpointed? true :sub-provider primary :sub-model "fixture/prime"
                       :sub-options {:max-tokens 16384 :max-retries 0}
                       :sub-fallback-models [{:provider backup :model "fixture/sub-backup"}]
                       :timeouts {:provider-ms 20000 :iteration-ms 60000 :campaign-ms 120000}}
               quantum (assoc :quantum quantum))))))

(defn register-inline! [fixture outer primary backup]
  (let [config {:provider :openrouter
                :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 10000}}]
    (llm/register-provider! outer (assoc config :model "fixture/outer" :output-mode :json-schema))
    (llm/register-provider! primary (assoc config :model "fixture/prime" :output-mode :function-calling))
    (llm/register-provider! backup (assoc config :model "fixture/sub-backup"))
    config))

(deftest inline-role-plan-and-backup-survive-quantum-with-distinct-next-input
  (let [outer-turn (atom 0) sub-turn (atom 0)
        first-code "(do (touch {}) (llm \"inline\" :instruction \"Return first observation\" :reads [:source] :writes [:detail]) (store! :first (get-var :detail)))"
        second-code "(do (llm \"inline\" :instruction \"Return the next observation from the first one\" :reads [:first] :writes [:detail]) (final! {:summary (get-var :detail)}))"
        next-value {:label "second-source" :count 3 :flags [false true true]}
        fixture (p/server! (fn [body]
                             (if (= "fixture/outer" (:model body))
                               (with-redefs [code (if (= 1 (swap! outer-turn inc)) first-code second-code)]
                                 (response body))
                               (with-redefs [desired (if (= 1 (swap! sub-turn inc)) desired next-value)]
                                 (response body)))))
        outer (keyword (str "inline-quantum-outer-" (random-uuid)))
        primary (keyword (str "inline-quantum-primary-" (random-uuid)))
        backup (keyword (str "inline-quantum-backup-" (random-uuid)))
        config (register-inline! fixture outer primary backup)
        tenant (random-uuid) tick (random-uuid) prefix-count (atom 0) tool-count (atom 0)
        store {:type :sqlite :database-file (str (p/fresh-directory) "/events.sqlite") :maximum-pool-size 2}
        common {:tenant-id tenant :llm-provider outer :probe-effects prefix-count
                :call-tool-fn (fn [_ _ _] (swap! tool-count inc) {:ok true})}
        first-context (atom nil) second-context (atom nil)
        starts (atom 0) held (promise) real-handler processors/execute-repl-researcher-node]
    (try
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store})
            _ (reset! first-context ctx) sheet (orc/build-workflow! ctx (inline-workflow primary backup {:quantum {:max-iterations 1}}))]
        (with-redefs [processors/execute-repl-researcher-node
                      (fn [context] (if (= 2 (swap! starts inc)) (do (deliver held true) nil) (real-handler context)))]
          (h/run-and-apply! ctx (p/command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}
                                                           :options {:timeout-ms 120000 :llm-call-budget 5}}))
          (is (= true (deref held 20000 :timeout)))
          (is (= 3 (count @(:requests fixture))))
          (is (= 1 @tool-count))
          (h/stop-async-context ctx) (reset! first-context nil))
        (llm/register-provider! primary (assoc config :model "fixture/changed-primary" :output-mode :json-schema))
        (llm/register-provider! backup (assoc config :model "fixture/changed-backup" :output-mode :json-schema))
        (let [ctx (h/create-async-test-context {:context (assoc common :orc/instance-id (random-uuid)
                                                                      :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
                                              :event-store-conn store})
              _ (reset! second-context ctx) resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 20000))
              result (runtime/durable-terminal-result ctx tick)
              events (orc/value-log-read-tick-events ctx tenant tick)
              plans (filter #(and (= :sheet/model-plan-retained (:event/type %)) (= :subcall (:role %))) events)
              requests (filter #(and (= :sheet/model-request-recorded (:event/type %)) (= :subcall (:role %))
                                      (= :completed (get-in % [:request :phase]))) events)
              bodies (mapv :body @(:requests fixture))
              sub (filterv #(not= "fixture/outer" (:model %)) bodies)
              successful (filter #(= 1 (get-in % [:request :candidate-index])) requests)]
          (is (= :success (:status result)) (pr-str result))
          (is (= next-value (get-in result [:outputs :summary])))
          (is (= ["fixture/outer" "fixture/prime" "fixture/sub-backup" "fixture/outer" "fixture/sub-backup"] (mapv :model bodies)))
          (is (= 1 (count plans)))
          (is (= 1 (count (set (map :configuration-id requests)))))
          (is (= 2 (count (set (map :logical-action-id requests)))))
          (is (= [desired next-value] (mapv #(get-in % [:request :outcome :result :outputs :detail]) successful)))
          (is (every? #(and (= "auto" (:tool_choice %)) (true? (get-in % [:tools 0 :function :strict]))) sub))
          (let [first-content (get-in (first sub) [:messages 0 :content])
                next-content (get-in (last sub) [:messages 0 :content])
                input-json (some-> (re-find #"(?m)^first: (.+)$" next-content) second)
                input-guide (some-> (second (str/split next-content #"Your input fields are:\n" 2))
                                    (str/split #"\n\nGiven the following inputs:" 2) first)]
            (is (str/includes? first-content "source: original-source"))
            (is (= desired (when input-json (json/parse-string input-json true))))
            (is (str/includes? input-guide "`first` (json object): Exact source label and typed nested observation."))
            (is (str/includes? input-guide "`label` (str): Original source label, copied verbatim."))
            (is (str/includes? input-guide "`count` (int): Number of entries in flags."))
            (is (str/includes? input-guide "`flags` (json array of bool): Ordered boolean observations.")))
          (is (= 5 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= 1 @prefix-count)) (is (= 1 @tool-count))
          (swap! proofs conj {:kind :inline-saved-role-plan :result result :events events :requests @(:requests fixture)})))
      (finally (when-let [ctx @first-context] (h/stop-async-context ctx))
               (when-let [ctx @second-context] (h/stop-async-context ctx))
               (p/stop-fixture! fixture)))))


(defn inline-checkpoint-cutpoint! [cutpoint]
  (let [fixture (p/server! response)
        outer (keyword (str "inline-recovery-outer-" (random-uuid)))
        primary (keyword (str "inline-recovery-primary-" (random-uuid)))
        backup (keyword (str "inline-recovery-backup-" (random-uuid)))
        _ (register-inline! fixture outer primary backup)
        tenant (random-uuid) tick (random-uuid) prefix-count (atom 0) tool-count (atom 0)
        store {:type :sqlite :database-file (str (p/fresh-directory) "/events.sqlite") :maximum-pool-size 2}
        common {:tenant-id tenant :auth-claims {} :llm-provider outer :probe-effects prefix-count
                :call-tool-fn (fn [_ _ _] (swap! tool-count inc) {:ok true})}
        first-context (atom nil) second-context (atom nil) target (atom nil)
        inline-id (atom nil) saved (atom nil) held (promise) real-command cp/process-command]
    (try
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store})
            _ (reset! first-context ctx) sheet (orc/build-workflow! ctx (inline-workflow primary backup {}))
            node (:id (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
        (reset! target node)
        ;; Let real execution reach the measured response; withhold ONLY later
        ;; candidate/effect/checkpoint incorporation. This is close/reopen,
        ;; never represented as a process-kill proof.
        (with-redefs [cp/process-command
          (fn [command-context]
            (let [c (:command command-context) name (:command/name c) same? (= node (:node-id c))]
              (cond
                (and same? (= :sheet/complete-node-execution name)) (do (deliver held true) {})
                (and same? (#{:sheet/checkpoint-researcher-iteration :sheet/record-researcher-iteration} name)) {}
                (and same? (= :physical-response cutpoint)
                     (or (and (= :subcall (:role c))
                              (#{:sheet/record-model-candidate :sheet/select-model-candidate} name)
                              (= 1 (or (:candidate-index c) (get-in c [:entry :candidate-index]))))
                         (and (= :sheet/complete-researcher-effect name)
                              (= @inline-id (:logical-action-identity c))))) {}
                :else
                (let [result (real-command command-context)]
                  (when (and same? (= :sheet/record-model-request name) (= :subcall (:role c))
                             (= :completed (get-in c [:request :phase])) (= 1 (get-in c [:request :candidate-index])))
                    (reset! inline-id (:logical-action-id c)))
                  result))))]
          (h/run-and-apply! ctx (p/command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}
                                                           :options {:timeout-ms 120000 :llm-call-budget 3}}))
          (is (= true (deref held 20000 :timeout)))
          (h/stop-async-context ctx) (reset! first-context nil))
        (h/with-async-test-context [inspect {:context common :event-store-conn store}]
          (let [before (orc/value-log-read-tick-events inspect tenant tick)
                physical (first (filter #(and (= :sheet/model-request-recorded (:event/type %)) (= :subcall (:role %))
                                              (= :completed (get-in % [:request :phase]))
                                              (= 1 (get-in % [:request :candidate-index]))) before))
                completed (filter #(and (= :rlm/researcher-effect-completed (:event/type %))
                                        (= @inline-id (:logical-action-identity %))) before)]
            (is (= {:detail desired} (get-in physical [:request :outcome :result :outputs])))
            (is (= 3 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) before))))
            (is (= (if (= :physical-response cutpoint) 0 1) (count completed)))
            (is (not-any? #(and (= :sheet/node-execution-completed (:event/type %)) (= node (:node-id %))) before))
            (reset! saved {:before before :physical physical})))
        (router/unregister! primary) (router/unregister! backup)
        (let [ctx (h/create-async-test-context {:context (assoc common :orc/instance-id (random-uuid)
                                                                      :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
                                              :event-store-conn store})
              _ (reset! second-context ctx) resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 15000))
              result (runtime/durable-terminal-result ctx tick)
              events (orc/value-log-read-tick-events ctx tenant tick)
              physical (filter #(and (= :sheet/model-request-recorded (:event/type %)) (= :subcall (:role %))
                                     (= :completed (get-in % [:request :phase]))
                                     (= 1 (get-in % [:request :candidate-index]))) events)
              completed (filter #(and (= :rlm/researcher-effect-completed (:event/type %))
                                      (= @inline-id (:logical-action-identity %))) events)
              prediction (get-in (first physical) [:request :outcome :result])]
          (is (= :success (:status result)) (pr-str result))
          (is (= desired (get-in result [:outputs :summary])))
          (is (= (get-in @saved [:physical :request :outcome :result]) prediction))
          (is (= {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13} (:usage prediction)))
          (is (= "fixture/sub-backup-resolved" (:model prediction)))
          (is (= 1 (count physical)))
          (is (= (get-in @saved [:physical :request :request-id]) (get-in (first physical) [:request :request-id])))
          (is (= (get-in @saved [:physical :configuration-id]) (:configuration-id (first physical))))
          (is (= 1 (count completed)))
          (when (= :physical-response cutpoint) (is (= 2 (:ownership-epoch (first completed)))))
          (is (= 3 (count @(:requests fixture))))
          (is (= 3 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= 1 @prefix-count)) (is (= 1 @tool-count))
          (is (h/settle-until! #(some (fn [e] (= :sheet/execution-traced (:event/type e)))
                                     (orc/value-log-read-tick-events ctx tenant tick)) :timeout-ms 5000))
          (let [before (count (orc/value-log-read-tick-events ctx tenant tick))]
            (is (not-any? :resumed? (orc/resume-in-progress! ctx)))
            (is (= before (count (orc/value-log-read-tick-events ctx tenant tick)))))
          (swap! proofs conj {:kind cutpoint :saved @saved :result result :events events
                              :requests @(:requests fixture) :prefix-count @prefix-count :tool-count @tool-count})))
      (finally (when-let [ctx @first-context] (h/stop-async-context ctx))
               (when-let [ctx @second-context] (h/stop-async-context ctx))
               (p/stop-fixture! fixture)))))

(deftest inline-physical-response-replays-before-current-provider-resolution
  (inline-checkpoint-cutpoint! :physical-response))

(deftest completed-inline-effect-bypasses-retained-profile-and-provider-resolution
  (inline-checkpoint-cutpoint! :effect-completed))

(deftest inline-frontier-advance-between-validation-and-cas-refuses-stale-receipt
  (p/staged-prime!
    (fn [ctx original primary _ _]
      (let [owner (assoc original :role :subcall :logical-action-id "inline-owned-request")
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            plan (p/profile-plan primary "fixture/inline-original")
            prepared (journal/prepare-inline-call ctx owner {:fallback-models []} (constantly plan))
            bound (:callbacks prepared) request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                                                 :provider primary :requested-model "fixture/inline-original" :phase :started}
            _ (is (nil? (:cognitect.anomalies/category ((:claim! bound) 0 (first (:candidates plan))))))
            _ (is (nil? (:cognitect.anomalies/category ((:record-request! bound) request))))
            read-real es/read gate (atom false) advanced (atom nil)
            result (with-redefs [es/read (fn [store options]
                      (when (and (contains? (:types options) :sheet/node-execution-started)
                                 (contains? (:types options) :sheet/model-request-recorded)
                                 (compare-and-set! gate false true))
                        (reset! advanced (p/frontier! ctx owner 2)))
                      (read-real store options))]
                     ((:record-request! bound) (assoc request :phase :completed
                                                :outcome {:status :success :result {:outputs {:detail desired}}})))
            events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
        (is (true? @gate)) (is (nil? (:cognitect.anomalies/category @advanced)))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (not-any? #(and (= :sheet/model-request-recorded (:event/type %))
                            (= :completed (get-in % [:request :phase]))) events))
        (swap! proofs conj {:kind :inline-late-frontier :events events :result result})))))

(deftest inline-plan-arriving-after-legacy-owner-validation-fences-old-bucket
  (p/staged-prime!
    (fn [ctx original primary _ _]
      (let [owner (assoc original :role :subcall :logical-action-id "new-inline-plan")
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            legacy-owner (dissoc (assoc owner :logical-action-id "old-inline-request") :ownership-epoch)
            candidates [{:provider primary :model "fixture/old" :options {}}]
            old (journal/callbacks ctx legacy-owner candidates :temporary-transport)
            plan (p/profile-plan primary "fixture/current") authored {:fallback-models []}
            reads (atom 0) retained (atom nil) read-real es/read
            result (with-redefs [es/read (fn [store options]
                      (when (and (= #{:sheet/tree-tick-started} (:types options)) (= 2 (swap! reads inc)))
                        (reset! retained (h/run-and-apply! ctx (p/command :sheet/retain-model-plan
                                           (assoc owner :configuration-id (journal/retained-configuration-id plan)
                                                        :plan-scope-id (journal/inline-plan-scope-id owner authored) :plan plan)))))
                      (read-real store options))]
                     ((:claim! old) 0 (first candidates)))
            events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
        (is (<= 2 @reads)) (is (nil? (:cognitect.anomalies/category @retained)))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
        (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) events))
        (swap! proofs conj {:kind :inline-nil-epoch-late-plan :events events :result result})))))

(deftest emitted-sibling-selection-keeps-global-campaign-cas
  (p/staged-prime!
    (fn [ctx parent primary _ _]
      (let [staged (atom []) child-sheet (orc/build-workflow! ctx
                                  (orc/workflow (str "inline-cas-child-control-" (random-uuid))
                                    (orc/blackboard {:value :string})
                                    (orc/llm "child" :instruction "not dispatched" :reads [] :writes [:value])))
            child-ticks [(random-uuid) (random-uuid)]
            options {:researcher-campaign-sheet-id (:sheet-id parent)
                     :researcher-campaign-tick-id (:tick-id parent)
                     :researcher-campaign-node-id (:node-id parent)
                     :researcher-campaign-exec-context {}}
            candidates (mapv #(hash-map :provider primary :model (str "fixture/child-" %) :options {}) (range 3))]
        (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
          (doseq [tick child-ticks]
            (h/run-and-apply! ctx (p/command :sheet/tick-tree {:sheet-id child-sheet :tick-id tick :inputs {} :options options})))
          (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
          (let [owners (mapv (fn [context]
                               {:sheet-id child-sheet :tick-id (get-in context [:event :tick-id])
                                :node-id (get-in context [:event :node-id]) :node-start-id (get-in context [:event :event/id])
                                :exec-context {} :role :subcall :logical-action-id "child-actual-request"}) @staged)
                bound (mapv #(journal/callbacks ctx % candidates :temporary-transport) owners)]
            (doseq [[callbacks index] (map vector bound [1 2])]
              (is (nil? (:cognitect.anomalies/category ((:claim! callbacks) index (nth candidates index)))))
              (is (nil? (:cognitect.anomalies/category ((:record! callbacks)
                         {:candidate-index index :provider primary :requested-model (:model (nth candidates index))
                          :status :success :advanced? false :outcome {:status :success :outputs {:value "done"}}})))))
            (is (nil? (:cognitect.anomalies/category ((:select! (first bound)) 1 (nth candidates 1)))))
            (is (nil? (:cognitect.anomalies/category ((:select! (second bound)) 2 (nth candidates 2)))))
            (is (= 2 (:start-index (journal/callbacks ctx (assoc (first owners) :logical-action-id "next-child-request") candidates :temporary-transport))))
            (let [before (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                              :types #{:sheet/model-candidate-selected}}))]
              (is (= [1 2] (mapv :candidate-index before)))
              (is (nil? (:cognitect.anomalies/category ((:select! (first bound)) 1 (nth candidates 1)))))
              (is (= before (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                                 :types #{:sheet/model-candidate-selected}}))))
              (swap! proofs conj {:kind :emitted-cross-child-selection-control :events before :owners owners}))))))))


(deftest old-inline-effect-without-journal-refuses-before-registry-and-fresh-claim
  (let [fixture (p/server! response)
        outer (keyword (str "inline-legacy-outer-" (random-uuid)))
        primary (keyword (str "inline-unregistered-primary-" (random-uuid)))
        backup (keyword (str "inline-unregistered-backup-" (random-uuid)))
        config {:provider :openrouter :model "fixture/outer" :output-mode :json-schema
                :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 10000}}
        _ (llm/register-provider! outer config)
        prefix-count (atom 0) tool-count (atom 0) staged (atom [])
        preflights (atom 0) real-preflight llm/preflight-provider!]
    (try
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str (p/fresh-directory) "/events.sqlite") :maximum-pool-size 2}
                                      :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider outer
                                                :probe-effects prefix-count :call-tool-fn (fn [_ _ _] (swap! tool-count inc) {:ok true})}}]
        (let [sheet (orc/build-workflow! ctx (inline-workflow primary backup {})) tick (random-uuid)
              _ (with-redefs [processors/execute-repl-researcher-node #(swap! staged conj %)]
                  (h/run-and-apply! ctx (p/command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}
                                                                   :options {:timeout-ms 120000 :llm-call-budget 3}}))
                  (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000)))
              node (get-in (first @staged) [:event :node-id])
              owner {:sheet-id sheet :tick-id tick :node-id node :ownership-epoch 1}
              ;; This is the original declared effect preimage, before any
              ;; retained profile or current provider default is involved.
              module {:inputs [{:name :source :spec [:string {:description "Original source label."}]}]
                      :outputs [{:name :detail :spec detail-schema :description "Exact source label and typed nested observation."}]
                      :instructions "Copy the original source label with count 2 and flags [true false]."}
              logical (effects/logical-action-identity
                        {:tick-id tick :node-id node :iteration-index 0 :generated-code-hash (effects/generated-code-hash code)
                         :kind :provider :target {:provider primary :model "fixture/prime" :name "inline"
                                                 :fallback-models [{:provider backup :model "fixture/sub-backup"}] :fallback-policy nil}
                         :arguments {:module module :inputs {:source "original-source"}
                                     :options {:validate? false :with-metadata? true :max-tokens 16384 :max-retries 0 :model "fixture/prime"}}})
              _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
              _ (is (nil? (:cognitect.anomalies/category
                            (h/run-and-apply! ctx (p/command :sheet/claim-researcher-effect
                               (merge owner {:iteration-index 0 :logical-action-identity logical
                                             :attempt-identity (effects/attempt-identity logical 1 0)
                                             :attempt-ordinal 0 :kind :provider :claimed-at (str (java.time.Instant/now))}))))))]
          (with-redefs [llm/preflight-provider! (fn [provider & args]
                          (when (#{primary backup} provider) (swap! preflights inc))
                          (apply real-preflight provider args))]
            (is (some :resumed? (orc/resume-in-progress! (assoc ctx :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))))
            (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 15000)))
          (let [events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
                completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= node (:node-id %))) events))
                result (runtime/durable-terminal-result ctx tick)
                inline-claims (filter #(and (= :rlm/researcher-effect-claimed (:event/type %))
                                             (= logical (:logical-action-identity %))) events)]
            (is (= :failure (:status result)))
            (is (= :model-plan-incompatible (:failure-kind completion)))
            (is (str/includes? (:error completion) "legacy-effective-profile-unretained"))
            (is (zero? @preflights))
            (is (= 1 (count inline-claims)) "the old claim is not followed by a fresh one")
            (is (not-any? #(and (= :sheet/model-plan-retained (:event/type %)) (= :subcall (:role %))) events))
            (is (= 1 (count @(:requests fixture))) "only the new outer prime request is needed")
            (is (= 1 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
            (swap! proofs conj {:kind :incomplete-legacy-inline-effect :events events :result result :logical logical
                                :preflights @preflights :requests @(:requests fixture)}))))
      (finally (p/stop-fixture! fixture)))))


(defn historical-inline-case! [mode]
  (p/staged-prime!
    (fn [ctx original primary backup staged]
      (let [prime? (= :prime-legacy-physical-retry mode)
            gap? (#{:legacy-physical-gap :legacy-failed-gap} mode)
            failed-gap? (= :legacy-failed-gap mode)
            conflicting? (= :legacy-conflicting-ordinal mode)
            competing? (= :legacy-competing-success mode)
            retry? (#{:legacy-physical-retry :prime-legacy-physical-retry :legacy-physical-gap
                       :legacy-failed-gap :legacy-conflicting-ordinal :legacy-competing-success} mode)
            role (if prime? :prime :subcall)
            owner (assoc original :role role :logical-action-id "historical-inline-request")
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            future? (#{:unknown-physical :unknown-terminal} mode)
            candidates (if future?
                         (:candidates (assoc-in (p/profile-plan primary "fixture/future-inline")
                                                [:candidates 0 :profile :version] "future-inline-v9"))
                         [{:provider primary :model "fixture/old-primary" :options {:max-retries 0}}
                          {:provider backup :model "fixture/old-backup" :options {:max-retries (cond gap? 2 retry? 1 :else 0)}}])
            plan {:version 1 :policy :temporary-transport :candidates candidates}
            index (if future? 0 1) authored {:fallback-models []}
            configuration (if future? (journal/retained-configuration-id plan)
                              (journal/configuration-id candidates :temporary-transport))
            call-owner (assoc owner :configuration-id configuration)
            prediction {:outputs (if prime? {:code p/code :reasoning p/reasoning} {:detail desired}) :usage {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}
                        :model "fixture/original-inline-resolved"
                        :provider-observations {:provider (name (:provider (nth candidates index)))
                                                :model "fixture/original-inline-resolved"
                                                :usage {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}}}
            outcome (if failed-gap?
                      {:status :failure :failure-kind :transport-failure :error "controlled later failure"}
                      {:status :success :result prediction :outputs (:outputs prediction) :usage (:usage prediction)
                       :model (:model prediction) :model-fallback {:policy :temporary-transport :candidate-index index :attempts []}})
            bound (journal/callbacks ctx call-owner candidates :temporary-transport)
            request {:request-id (random-uuid) :candidate-index index :attempt-ordinal (cond conflicting? 0 gap? 2 retry? 1 :else 0)
                     :provider (:provider (nth candidates index)) :requested-model (:model (nth candidates index))}
            resolves (atom 0) reservations (atom 0) dispatches (atom 0)]
        (when prime?
          (is (nil? (:cognitect.anomalies/category
                      (h/run-and-apply! ctx (p/command :sheet/claim-researcher-effect
                        (merge (select-keys owner [:sheet-id :tick-id :node-id :ownership-epoch])
                               {:iteration-index 0 :logical-action-identity (:logical-action-id owner)
                                :attempt-identity (effects/attempt-identity (:logical-action-id owner) 1 0)
                                :attempt-ordinal 0 :kind :provider :claimed-at (str (java.time.Instant/now))})))))))
        (when future?
          (is (nil? (:cognitect.anomalies/category
                      (h/run-and-apply! ctx (p/command :sheet/retain-model-plan
                         (assoc call-owner :plan-scope-id (journal/inline-plan-scope-id owner authored) :plan plan))))))
          (let [refused (try (journal/prepare-inline-call ctx owner authored (fn [] (swap! resolves inc) plan))
                             :unexpected-success (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= :model-plan-incompatible (:failure-kind refused)))
            (is (= :retained-profile-version-unsupported (:reason refused)))))
        (when-not future?
          (is (nil? (:cognitect.anomalies/category ((:claim! bound) 0 (first candidates)))))
          (is (nil? (:cognitect.anomalies/category
                      ((:record! bound) {:candidate-index 0 :provider primary :requested-model "fixture/old-primary"
                                        :status :failure :advanced? true
                                        :outcome {:status :failure :failure-kind :transport-failure
                                                  :model-fallback {:policy :temporary-transport :attempts []}}})))))
        (is (nil? (:cognitect.anomalies/category ((:claim! bound) index (nth candidates index)))))
        (when retry?
          (let [failed (assoc request :request-id (random-uuid) :attempt-ordinal 0)]
            (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc failed :phase :started)))))
            (is (nil? (:cognitect.anomalies/category ((:record-request! bound)
                         (assoc failed :phase :completed
                                :outcome (if competing?
                                           {:status :success :result (assoc prediction :model "fixture/competing-original")
                                            :outputs (:outputs prediction) :usage (:usage prediction)}
                                           {:status :failure :failure-kind :transport-failure
                                            :error "controlled old attempt failed"}))))))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc request :phase :started)))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc request :phase :completed :outcome outcome)))))
        (when (= :legacy-duplicate-identical mode)
          (let [before (count (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner)))]
            (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc request :phase :completed :outcome outcome)))))
            (is (= before (count (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner)))))))
        (when (#{:legacy-terminal :unknown-terminal} mode)
          (is (nil? (:cognitect.anomalies/category
                      ((:record! bound) {:candidate-index index :provider (:provider (nth candidates index))
                                        :requested-model (:model (nth candidates index)) :status :success
                                        :advanced? false :outcome outcome}))))
          (is (nil? (:cognitect.anomalies/category ((:select! bound) index (nth candidates index))))))
        (h/run-and-apply! ctx (p/command :sheet/resume-node-execution
                               (assoc (select-keys owner [:sheet-id :tick-id :node-id])
                                      :original-start-event-id (:node-start-id owner) :inputs {})))
        (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
        (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 2))))
        (let [current (assoc owner :ownership-epoch 2 :node-start-id (get-in (last @staged) [:event :event/id]))
              resolve-plan! (fn [] (swap! resolves inc) (throw (ex-info "Historical receipt must precede current registry" {})))
              prepared-original (if prime?
                         (journal/prepare-prime-call ctx current authored 0
                           (orc/get-researcher-effect-claims ctx (:sheet-id owner) (:tick-id owner) (:node-id owner))
                           resolve-plan! (fn [& _] (throw (ex-info "Historical logical ID must not be recomputed" {}))))
                         (journal/prepare-inline-call ctx current authored resolve-plan!))
              prepared (cond-> prepared-original
                         (= :legacy-duplicate-identical mode)
                         (update-in [:callbacks :request-receipts] #(vec (mapcat (fn [receipt] [receipt receipt]) %))))
              result (try
                       (with-redefs [llm/preflight-provider! (fn [& _] (swap! resolves inc) (throw (ex-info "No preflight on known response" {})))
                                     llm/predict (fn [& _] (swap! dispatches inc) (throw (ex-info "No fresh request on known response" {})))]
                       (models/predict {:fallback-models []} primary {:inputs [] :outputs [] :instructions "unused"} {}
                         {:with-metadata? true}
                         (merge ctx current {:researcher-ownership-epoch 2 :researcher-iteration 0
                                             :prepared-inline-model-call (when-not prime? prepared)
                                             :prepared-prime-model-call (when prime? prepared)
                                             :reserve-provider-call! (fn [_] (swap! reservations inc) nil)})
                         role (:logical-action-id owner)))
                       (catch clojure.lang.ExceptionInfo error
                         (assoc (ex-data error) :status :failure)))
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))
              history (filter #(contains? journal/event-types (:event/type %)) events)]
          (is (= (:logical-action-id owner) (:logical-action-id prepared)))
          (is (= configuration (:configuration-id prepared)))
          (if (or failed-gap? conflicting? competing?)
            (do (is (= :model-plan-incompatible (:failure-kind result)))
                (is (= :historical-operation-ambiguous (:reason result))))
            (do
              (is (= prediction (select-keys result (keys prediction))))
              (when (and (not (#{:legacy-terminal :unknown-terminal} mode)))
                (let [accounting (get-in (last (get-in result [:model-fallback :attempts]))
                                         [:provider-evidence :runtime-derived-recovery-accounting])]
                  (is (= (:request-id request) (:selected-request-id accounting)))
                  (is (= (:attempt-ordinal request) (:selected-attempt-ordinal accounting)))
                  (when gap?
                    (is (= 1 (:unrecorded-attempt-ordinal-count accounting)))
                    (is (false? (:usage-complete? accounting))))))))
          (is (zero? @resolves)) (is (zero? @dispatches)) (is (zero? @reservations))
          (is (= #{configuration} (set (map :configuration-id history))))
          (is (= #{(:logical-action-id owner)} (set (map :logical-action-id history))))
          (is (= (if retry? 2 1)
                 (count (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                      (= :completed (get-in % [:request :phase]))) history))))
          (is (= request (select-keys (:request (last (filter #(= :sheet/model-request-recorded (:event/type %)) history))) (keys request))))
          (when-not future? (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) history)))
          (swap! proofs conj {:kind mode :prepared (dissoc prepared :callbacks) :events events :result result}))))))

(deftest exact-historical-inline-physical-response-replays-with-original-configuration
  (historical-inline-case! :legacy-physical))
(deftest exact-historical-inline-terminal-outcome-replays-with-original-configuration
  (historical-inline-case! :legacy-terminal))
(deftest unfamiliar-inline-profile-refuses-fresh-work-but-replays-exact-physical-response
  (historical-inline-case! :unknown-physical))
(deftest unfamiliar-inline-profile-replays-exact-terminal-outcome
  (historical-inline-case! :unknown-terminal))

(deftest competing-inline-preparations-retain-one-role-plan-for-distinct-requests
  (p/staged-prime!
    (fn [ctx original primary _ _]
      (let [owner (assoc original :role :subcall)
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            authored {:fallback-models []}
            plans [(p/profile-plan primary "fixture/a") (p/profile-plan primary "fixture/b")]
            ready (java.util.concurrent.CountDownLatch. 2) release (promise)
            workers (mapv (fn [index plan]
                            (future (journal/prepare-inline-call ctx (assoc owner :logical-action-id (str "request-" index)) authored
                                       #(do (.countDown ready) @release plan)))) (range 2) plans)]
        (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
        (deliver release true)
        (let [prepared (mapv #(deref % 10000 ::timeout) workers)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
          (is (not-any? #{::timeout} prepared))
          (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
          (is (= 1 (count (set (map :configuration-id prepared)))))
          (is (= 1 (count (set (map :plan prepared)))))
          (is (= #{"request-0" "request-1"} (set (map :logical-action-id prepared))))
          (is (every? #(empty? (get-in % [:callbacks :request-receipts])) prepared))
          (swap! proofs conj {:kind :inline-competing-preparations :events events
                              :prepared (mapv #(dissoc % :callbacks) prepared)}))))))


(deftest exact-historical-inline-response-at-later-physical-retry-replays-without-dispatch
  (historical-inline-case! :legacy-physical-retry))


(deftest exact-historical-prime-response-at-later-physical-retry-replays-without-dispatch
  (historical-inline-case! :prime-legacy-physical-retry))


(deftest historical-inline-known-success-after-missing-earlier-attempt-replays-with-partial-accounting
  (historical-inline-case! :legacy-physical-gap))

(deftest historical-inline-failed-attempt-gap-refuses-before-unrecorded-request
  (historical-inline-case! :legacy-failed-gap))

(deftest historical-inline-conflicting-receipts-for-one-ordinal-refuse
  (historical-inline-case! :legacy-conflicting-ordinal))

(deftest historical-inline-competing-known-successes-refuse
  (historical-inline-case! :legacy-competing-success))


(deftest identical-inline-receipt-facts-replay-idempotently-with-original-identity
  (historical-inline-case! :legacy-duplicate-identical))


(deftest inline-plan-arriving-after-current-epoch-legacy-validation-fences-old-bucket
  (p/staged-prime!
    (fn [ctx original primary _ _]
      (let [owner (assoc original :role :subcall :logical-action-id "new-inline-plan")
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            legacy-owner (assoc owner :logical-action-id "old-inline-request")
            candidates [{:provider primary :model "fixture/old" :options {}}]
            old (journal/callbacks ctx legacy-owner candidates :temporary-transport)
            plan (p/profile-plan primary "fixture/current") authored {:fallback-models []}
            reads (atom 0) retained (atom nil) read-real es/read
            result (with-redefs [es/read (fn [store options]
                      (when (and (= #{:sheet/tree-tick-started} (:types options)) (= 2 (swap! reads inc)))
                        (reset! retained (h/run-and-apply! ctx (p/command :sheet/retain-model-plan
                                           (assoc owner :configuration-id (journal/retained-configuration-id plan)
                                                        :plan-scope-id (journal/inline-plan-scope-id owner authored) :plan plan)))))
                      (read-real store options))]
                     ((:claim! old) 0 (first candidates)))
            events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
        (is (<= 2 @reads)) (is (nil? (:cognitect.anomalies/category @retained)))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
        (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) events))
        (swap! proofs conj {:kind :inline-current-epoch-late-plan :events events :result result})))))



(deftest distinct-authored-inline-plans-remain-usable-and-replay-earlier-physical-response
  (p/staged-prime!
    (fn [ctx original primary _ staged]
      (let [owner (assoc original :role :subcall)
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            a (assoc owner :logical-action-id "authored-a-original")
            b (assoc owner :logical-action-id "authored-b-original")
            authored-a {:model "fixture/a" :fallback-models []}
            authored-b {:model "fixture/b" :fallback-models []}
            plan-a (p/profile-plan primary "fixture/a") plan-b (p/profile-plan primary "fixture/b")
            prepared-a (journal/prepare-inline-call ctx a authored-a (constantly plan-a))
            callbacks-a (:callbacks prepared-a)
            prediction {:outputs {:detail desired} :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                        :model "fixture/a-resolved"}
            request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                     :provider primary :requested-model "fixture/a"}]
        (is (nil? (:cognitect.anomalies/category ((:claim! callbacks-a) 0 (first (:candidates plan-a))))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks-a) (assoc request :phase :started)))))
        (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks-a)
                    (assoc request :phase :completed :outcome {:status :success :result prediction
                                                               :outputs (:outputs prediction) :usage (:usage prediction)})))))
        ;; The other authored configuration may create its own real role plan;
        ;; the first plan's valid journal is not unretained legacy work.
        (let [prepared-b (journal/prepare-inline-call ctx b authored-b (constantly plan-b))]
          (is (not= (:configuration-id prepared-a) (:configuration-id prepared-b)))
          (is (nil? (:cognitect.anomalies/category ((:claim! (:callbacks prepared-b)) 0 (first (:candidates plan-b)))))))
        (h/run-and-apply! ctx (p/command :sheet/resume-node-execution
                               (assoc (select-keys owner [:sheet-id :tick-id :node-id])
                                      :original-start-event-id (:node-start-id owner) :inputs {})))
        (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
        (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 2))))
        (let [current (assoc a :ownership-epoch 2 :node-start-id (get-in (last @staged) [:event :event/id]))
              prepared (journal/prepare-inline-call ctx current authored-a
                         #(throw (ex-info "Known authored plan must precede current registry" {})))
              calls (atom 0) reservations (atom 0)
              result (with-redefs [llm/preflight-provider! (fn [& _] (swap! calls inc) (throw (ex-info "No current preflight" {})))
                                   llm/predict (fn [& _] (swap! calls inc) (throw (ex-info "No fresh physical call" {})))]
                       (models/predict {:fallback-models []} primary {:inputs [] :outputs [] :instructions "unused"} {}
                         {:with-metadata? true}
                         (merge ctx current {:prepared-inline-model-call prepared :researcher-ownership-epoch 2
                                             :reserve-provider-call! (fn [_] (swap! reservations inc) nil)}) :subcall (:logical-action-id a)))
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
          (is (= prediction (select-keys result (keys prediction))))
          (is (zero? @calls)) (is (zero? @reservations))
          (is (= 2 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
          (is (= [(:request-id request)] (mapv #(get-in % [:request :request-id])
                    (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                   (= :completed (get-in % [:request :phase]))) events))))
          (swap! proofs conj {:kind :distinct-authored-inline-plans :events events :result result}))))))

(deftest unretained-current-epoch-inline-claim-fences-first-role-plan-in-reverse-order
  (p/staged-prime!
    (fn [ctx original primary _ _]
      (let [owner (assoc original :role :subcall :logical-action-id "legacy-current-epoch")
            _ (is (nil? (:cognitect.anomalies/category (p/frontier! ctx owner 1))))
            candidates [{:provider primary :model "fixture/old" :options {}}]
            old (journal/callbacks ctx owner candidates :temporary-transport)
            _ (is (nil? (:cognitect.anomalies/category ((:claim! old) 0 (first candidates)))))
            plan (p/profile-plan primary "fixture/current")
            result (h/run-and-apply! ctx (p/command :sheet/retain-model-plan
                         (assoc owner :logical-action-id "different-new-request"
                                      :configuration-id (journal/retained-configuration-id plan)
                                      :plan-scope-id (journal/inline-plan-scope-id owner {:fallback-models []}) :plan plan)))
            events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) events))
        (is (= 1 (count (filter #(= :sheet/model-candidate-claimed (:event/type %)) events))))
        (swap! proofs conj {:kind :inline-legacy-claim-before-role-plan :events events :result result})))))
