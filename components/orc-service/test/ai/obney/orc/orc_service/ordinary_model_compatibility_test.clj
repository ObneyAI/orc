(ns ai.obney.orc.orc-service.ordinary-model-compatibility-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.retained-ordinary-model-plan-test :as fixture]
            [hato.client :as http]
            [cognitect.anomalies :as anom])
  (:import [java.nio.file Files] [java.time Instant]
           [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress] [java.util.concurrent TimeUnit]))

(def proofs (atom []))
(defn accepted? [result] (nil? (::anom/category result)))

(defn with-owner [f]
  (let [staged (atom []) tenant (random-uuid) tick (random-uuid)
        dir (.toFile (Files/createTempDirectory "orc-model-compatibility-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
        context {:tenant-id tenant :auth-claims {} :llm-provider :unregistered-no-dispatch}
        saved (atom nil)]
    (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
      (h/with-async-test-context [ctx {:event-store-conn conn :context context}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "ordinary-compatibility-" (random-uuid))
                        (orc/blackboard {:source :string :answer :string})
                        (orc/llm "produce" :instruction "Copy the source."
                          :reads [:source] :writes [:answer]
                          :fallback-models [{:provider :unregistered-backup :model "fixture/backup"}]
                          :options {:max-retries 0})))
              node (first (filter #(= "produce" (:name %)) (orc/get-nodes-for-sheet ctx sheet)))
              authored (select-keys node [:provider :model :options :fallback-models :fallback-policy])]
          (h/run-and-apply! ctx (fixture/command :sheet/tick-tree
                                {:sheet-id sheet :tick-id tick :inputs {:source "original-source"}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [event (:event (first @staged))
                owner {:sheet-id sheet :tick-id tick :node-id (:node-id event)
                       :node-start-id (:event/id event) :exec-context {} :role :node
                       :logical-action-id (pr-str [tick (:node-id event) {}])}]
            (reset! saved (merge {:ctx-options {:event-store-conn conn :context context}
                                 :directory (.getAbsolutePath dir) :owner owner :authored authored}
                                (f ctx owner authored)))))))
    @saved))

(defn retain! [ctx owner authored plan]
  (let [owned (assoc owner :configuration-id (journal/retained-configuration-id plan))]
    (is (accepted? (h/run-and-apply! ctx (fixture/command :sheet/retain-model-plan
                     (assoc owned :plan-scope-id (journal/plan-scope-id owner authored) :plan plan)))))
    (journal/callbacks ctx owned (:candidates plan) (:policy plan))))

(defn future-plan []
  (assoc-in (fixture/plan-fixture :unregistered-no-dispatch "fixture/original")
            [:candidates 0 :profile :version] "future-profile-v2"))

(deftest exact-terminal-unknown-profile-replays-through-public-recovery
  (let [outcome {:status :success :outputs {:answer "original-source"}
                 :model "fixture/original"}
        saved (with-owner
                (fn [ctx owner authored]
                  (let [plan (future-plan) cb (retain! ctx owner authored plan)]
                    (is (accepted? ((:claim! cb) 0 (first (:candidates plan)))))
                    (is (accepted? ((:record! cb) {:candidate-index 0 :status :success
                                                  :advanced? false :outcome outcome})))
                    {:configuration-id (journal/retained-configuration-id plan)
                     :before (fixture/plan-history ctx (:tick-id owner))})))
        tick (get-in saved [:owner :tick-id])
        node (get-in saved [:owner :node-id])
        tenant (get-in saved [:ctx-options :context :tenant-id])
        calls (atom 0)]
    (with-redefs [http/post (fn [& _] (swap! calls inc)
                             (throw (ex-info "Recovery must not dispatch HTTP" {})))]
      (h/with-async-test-context
        [ctx (update-in (:ctx-options saved) [:context]
               assoc :orc/instance-id (random-uuid)
                     :orc/clock-fn #(.plusSeconds (Instant/now) 300))]
        (is (some :resumed? (orc/resume-in-progress! ctx)))
        (is (h/settle-until! #(= :completed (:status (orc/get-tick ctx tick))) :timeout-ms 10000))
        (let [events (orc/value-log-read-tick-events ctx tenant tick)
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= node (:node-id %))) events))
              writes (orc/value-log-resolve-writes ctx tenant tick completion)
              history (fixture/plan-history ctx tick)]
          (is (= :success (:status completion)))
          (is (= {:answer "original-source"} writes))
          (is (= #{(:configuration-id saved)} (set (map :configuration-id history))))
          (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) history))))
          (is (not-any? #(= :sheet/provider-call-reserved (:event/type %)) events))
          (is (zero? @calls))
          (is (empty? (orc/resume-in-progress! ctx)))
          (swap! proofs conj {:kind :unknown-terminal :saved (dissoc saved :ctx-options)
                              :events events :completion completion :canonical-writes writes
                              :http-calls @calls}))))))

(deftest incomplete-unknown-profile-still-refuses-before-resolution
  (with-owner
    (fn [ctx owner authored]
      (let [plan (future-plan) cb (retain! ctx owner authored plan)
            _ (is (accepted? ((:claim! cb) 0 (first (:candidates plan)))))
            calls (atom 0)
            result (try (journal/prepare-ordinary-call ctx owner authored
                          #(do (swap! calls inc) plan))
                        :unexpected-return
                        (catch clojure.lang.ExceptionInfo error
                          (select-keys (ex-data error) [:failure-kind :reason])))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (= {:failure-kind :model-plan-incompatible
                :reason :retained-profile-version-unsupported} result))
        (is (zero? @calls))
        (is (= 1 (count (set (map :configuration-id history)))))
        (swap! proofs conj {:kind :unknown-incomplete :result result :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(defn unknown-request-recovery-case [outcome expected-status expected-writes]
  (let [saved (with-owner
                (fn [ctx owner authored]
                  (let [plan (-> (future-plan)
                                 (assoc-in [:candidates 0 :options :max-retries] 1)
                                 (assoc-in [:candidates 0 :options :retry-delay-ms] 0))
                        cb (retain! ctx owner authored plan)
                        request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                                 :provider :unregistered-no-dispatch :requested-model "fixture/original"}]
                    (is (accepted? ((:claim! cb) 0 (first (:candidates plan)))))
                    (is (accepted? ((:record-request! cb) (assoc request :phase :started))))
                    (is (accepted? ((:record-request! cb)
                                    (assoc request :phase :completed :outcome outcome))))
                    {:configuration-id (journal/retained-configuration-id plan)})))
        tick (get-in saved [:owner :tick-id]) node (get-in saved [:owner :node-id])
        tenant (get-in saved [:ctx-options :context :tenant-id]) calls (atom 0)]
    (with-redefs [http/post (fn [& _] (swap! calls inc)
                             (throw (ex-info "Unknown profiles may only replay receipts" {})))]
      (h/with-async-test-context
        [ctx (update-in (:ctx-options saved) [:context]
               assoc :orc/instance-id (random-uuid)
                     :orc/clock-fn #(.plusSeconds (Instant/now) 300))]
        (is (some :resumed? (orc/resume-in-progress! ctx)))
        (is (h/settle-until! #(= :completed (:status (orc/get-tick ctx tick))) :timeout-ms 10000))
        (let [events (orc/value-log-read-tick-events ctx tenant tick)
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= node (:node-id %))) events))
              writes (orc/value-log-resolve-writes ctx tenant tick completion)
              history (fixture/plan-history ctx tick)]
          (is (= expected-status (:status completion)))
          (is (= expected-writes writes))
          (when (= :failure expected-status)
            (is (= :model-plan-incompatible (:failure-kind completion))))
          (is (= #{(:configuration-id saved)} (set (map :configuration-id history))))
          (is (= 2 (count (filter #(= :sheet/model-request-recorded (:event/type %)) history))))
          (is (not-any? #(= :sheet/provider-call-reserved (:event/type %)) events))
          (is (zero? @calls))
          (is (empty? (orc/resume-in-progress! ctx)))
          (swap! proofs conj {:kind :unknown-request :outcome outcome :saved (dissoc saved :ctx-options)
                              :events events :completion completion :canonical-writes writes
                              :http-calls @calls}))))))

(deftest exact-completed-physical-receipt-with-unknown-profile-replays
  (unknown-request-recovery-case
    {:status :success :outputs {:answer "original-source"} :model "fixture/original"}
    :success {:answer "original-source"}))

(deftest failed-physical-receipt-with-unknown-profile-does-not-admit-fresh-retry
  (unknown-request-recovery-case
    {:status :failure :error "Controlled temporary provider failure" :failure-kind :service-unavailable}
    :failure {}))

(deftest legacy-claim-during-resolution-refuses-a-new-effective-plan
  (with-owner
    (fn [ctx owner authored]
      (let [legacy [{:provider :old-provider :model "old-model" :options {}}]
            cb (journal/callbacks ctx owner legacy :temporary-transport)
            plan (fixture/plan-fixture :new-provider "new-model")
            result (try (journal/prepare-ordinary-call ctx owner authored
                          #(do (is (accepted? ((:claim! cb) 0 (first legacy)))) plan))
                        :unexpected-return
                        (catch clojure.lang.ExceptionInfo error
                          (select-keys (ex-data error) [:failure-kind :reason])))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (= {:failure-kind :model-plan-incompatible :reason :model-plan-ownership-lost} result))
        (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) history))
        (is (= #{(journal/configuration-id legacy :temporary-transport)}
               (set (map :configuration-id history))))
        (is (= 1 (count (filter #(= :sheet/model-candidate-claimed (:event/type %)) history))))
        (swap! proofs conj {:kind :legacy-before-plan :result result :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest current-command-refuses-legacy-claim-after-effective-plan-retention
  (with-owner
    (fn [ctx owner authored]
      (let [plan (fixture/plan-fixture :new-provider "new-model")
            prepared (journal/prepare-ordinary-call ctx owner authored (constantly plan))
            legacy [{:provider :old-provider :model "old-model" :options {}}]
            legacy-cb (journal/callbacks ctx owner legacy :temporary-transport)
            refused ((:claim! legacy-cb) 0 (first legacy))
            claimed ((get-in prepared [:callbacks :claim!]) 0 (first (:candidates plan)))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (= ::anom/conflict (::anom/category refused)))
        (is (accepted? claimed))
        (is (= #{(journal/retained-configuration-id plan)} (set (map :configuration-id history))))
        (is (= 1 (count (filter #(= :sheet/model-candidate-claimed (:event/type %)) history))))
        (swap! proofs conj {:kind :plan-before-legacy :refused refused :claimed claimed :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest legacy-claim-between-plan-handler-and-append-invalidates-plan-cas
  (with-owner
    (fn [ctx owner authored]
      (let [legacy [{:provider :old-provider :model "old-model" :options {}}]
            cb (journal/callbacks ctx owner legacy :temporary-transport)
            plan (fixture/plan-fixture :new-provider "new-model")
            inject? (atom true) append-results (atom []) real-append es/append
            result
            (with-redefs [es/append
                          (fn [store args]
                            (let [plan? (some #(= :sheet/model-plan-retained (:event/type %)) (:events args))]
                              (when (and plan? (compare-and-set! inject? true false))
                                ;; Real nested command wins after the plan handler
                                ;; read, before SQLite evaluates the outer CAS.
                                (is (accepted? ((:claim! cb) 0 (first legacy)))))
                              (let [result (real-append store args)]
                                (when plan? (swap! append-results conj result))
                                result)))]
              (try (journal/prepare-ordinary-call ctx owner authored (constantly plan))
                   :unexpected-return
                   (catch clojure.lang.ExceptionInfo error
                     (select-keys (ex-data error) [:failure-kind :reason]))))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (false? @inject?))
        (is (= 1 (count @append-results)))
        (is (= ::anom/conflict (::anom/category (first @append-results))))
        (is (= {:failure-kind :model-plan-incompatible :reason :model-plan-ownership-lost} result))
        (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) history))
        (is (= #{(journal/configuration-id legacy :temporary-transport)}
               (set (map :configuration-id history))))
        (swap! proofs conj {:kind :legacy-between-plan-read-and-append
                            :result result :append-results @append-results :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest retained-plan-between-legacy-handler-and-append-invalidates-claim-cas
  (with-owner
    (fn [ctx owner authored]
      (let [legacy [{:provider :old-provider :model "old-model" :options {}}]
            cb (journal/callbacks ctx owner legacy :temporary-transport)
            legacy-id (journal/configuration-id legacy :temporary-transport)
            plan (fixture/plan-fixture :new-provider "new-model")
            inject? (atom true) append-results (atom []) real-append es/append
            refused
            (with-redefs [es/append
                          (fn [store args]
                            (let [legacy? (some #(and (= :sheet/model-candidate-claimed (:event/type %))
                                                     (= legacy-id (:configuration-id %))) (:events args))]
                              (when (and legacy? (compare-and-set! inject? true false))
                                (retain! ctx owner authored plan))
                              (let [result (real-append store args)]
                                (when legacy? (swap! append-results conj result))
                                result)))]
              ((:claim! cb) 0 (first legacy)))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (false? @inject?))
        (is (= 1 (count @append-results)))
        (is (= ::anom/conflict (::anom/category (first @append-results))))
        (is (= ::anom/conflict (::anom/category refused)))
        (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) history))
        (is (= #{(journal/retained-configuration-id plan)} (set (map :configuration-id history))))
        (swap! proofs conj {:kind :plan-between-legacy-read-and-append
                            :refused refused :append-results @append-results :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest previously-created-mixed-configuration-history-refuses-new-preparation
  (with-owner
    (fn [ctx owner authored]
      (let [legacy [{:provider :old-provider :model "old-model" :options {}}]
            cb (journal/callbacks ctx owner legacy :temporary-transport)
            plan (fixture/plan-fixture :new-provider "new-model")
            inject? (atom true) real-append es/append
            ;; Reenact the previous handler's demonstrated narrow CAS type
            ;; filter while retaining public command/schema validation and the
            ;; real SQLite append. This is explicitly historical fixture setup,
            ;; not evidence of safe current or mixed-binary production dispatch.
            _ (with-redefs [es/append
                            (fn [store args]
                              (if (and (some #(= :sheet/model-plan-retained (:event/type %)) (:events args))
                                       (compare-and-set! inject? true false))
                                (do
                                  (is (accepted? ((:claim! cb) 0 (first legacy))))
                                  (real-append store
                                    (assoc-in args [:cas :types]
                                      #{:sheet/model-plan-retained :sheet/node-execution-started})))
                                (real-append store args)))]
                (journal/prepare-ordinary-call ctx owner authored (constantly plan)))
            resolver-calls (atom 0)
            result (try (journal/prepare-ordinary-call ctx owner authored
                          #(do (swap! resolver-calls inc) plan))
                        :unexpected-return
                        (catch clojure.lang.ExceptionInfo error
                          (select-keys (ex-data error) [:failure-kind :reason])))
            history (fixture/plan-history ctx (:tick-id owner))]
        (is (= 2 (count (set (map :configuration-id history)))))
        (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) history))))
        (is (= 1 (count (filter #(= :sheet/model-candidate-claimed (:event/type %)) history))))
        (is (= {:failure-kind :model-plan-incompatible :reason :historical-operation-ambiguous} result))
        (is (zero? @resolver-calls))
        (swap! proofs conj {:kind :historical-mixed-configuration
                            :setup :prior-narrow-cas-filter-reenacted-through-public-commands
                            :result result :resolver-calls @resolver-calls :history history})
        (orc/cancel! ctx (:tick-id owner))
        {}))))

(deftest existing-common-client-controls-survive-plan-and-supported-wire-mappings
  (let [requests (atom []) errors (atom [])
        controls {:do-sample false :tool-stream true :stream-options {:include-usage true}
                  :prompt-cache-key "synthetic-cache" :safety-identifier "synthetic-safety"
                  :request-id "synthetic-request" :user-id "synthetic-user"}
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange)) body (json/parse-string raw true)
                  primary? (= "fixture/primary" (:model body))
                  response (if primary? {:error {:message "controlled unavailable" :code 503}}
                             {:id "controlled-compatibility-response" :model "fixture/backup-resolved"
                              :choices [{:finish_reason "tool_calls"
                                         :message {:role "assistant" :content nil
                                                   :tool_calls [{:type "function" :function
                                                                 {:name "submit_response"
                                                                  :arguments "{\"answer\":\"original-source\"}"}}]}}]})
                  bytes (.getBytes (json/generate-string response) "UTF-8")]
              (swap! requests conj {:raw raw :body body :response response})
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange (if primary? 503 200) (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable error (swap! errors conj {:class (.getName (class error))}))
            (finally (.close exchange))))))
    (.start server)
    (try
      (doseq [[backend wire-controls]
              [[:zai {:do_sample false :tool_stream true :stream_options {:include_usage true}}]
               [:kimi {:prompt_cache_key "synthetic-cache" :safety_identifier "synthetic-safety"
                       :stream_options {:include_usage true}}]]]
        (let [primary (keyword (str "compat-primary-" (random-uuid)))
              backup (keyword (str "compat-backup-" (random-uuid)))
              dir (.toFile (Files/createTempDirectory "orc-client-controls-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))
              ;; Paired mode is OpenRouter-only; these existing adapters use legacy function mode.
              config {:provider backend
                      :config {:api-key "SYNTHETIC-NOT-SENT"
                               :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                               :timeout 5000}}
              before (count @requests)]
          (llm/register-provider! primary (assoc config :model "fixture/primary"))
          (llm/register-provider! backup (assoc config :model "fixture/backup"))
          (h/with-async-test-context
            [ctx {:event-store-conn {:type :sqlite :database-file (str dir "/events.sqlite")
                                    :maximum-pool-size 2}
                  :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary}}]
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow (str "existing-controls-" (random-uuid))
                            (orc/blackboard {:source :string :answer :string})
                            (orc/llm "produce" :instruction "Copy the source."
                              :reads [:source] :writes [:answer]
                              :fallback-models [{:provider backup :model "fixture/backup"}]
                              :options (merge {:max-retries 0 :max-tokens 256 :use-function-calling? true} controls))))
                  result (orc/execute ctx sheet {:source "original-source"}
                           :timeout-ms 20000 :max-ticks 1 :llm-call-budget 2)
                  events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                  retained (:plan (first (filter #(= :sheet/model-plan-retained (:event/type %)) events)))
                  actual (subvec (vec @requests) before)]
              (is (= :success (:status result)))
              (is (= "original-source" (get-in result [:outputs :answer])))
              (is (= 2 (count actual)))
              (is (= 2 (count (:candidates retained))))
              (is (= 2 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
              (doseq [candidate (:candidates retained)]
                (is (= controls (select-keys (:options candidate) (keys controls)))))
              (doseq [{:keys [body]} actual]
                (is (= wire-controls (select-keys body (keys wire-controls)))))
              ;; IDs remain accepted plan data even where this pinned adapter
              ;; does not serialize them. Retention is not a wire-support claim.
              (doseq [key (keys controls)]
                (is (not= (journal/retained-configuration-id retained)
                          (journal/retained-configuration-id
                            (assoc-in retained [:candidates 0 :options key] "CHANGED")))))
              (swap! proofs conj {:kind :existing-client-controls :backend backend
                                  :directory (.getAbsolutePath dir) :result result :events events
                                  :plan retained :requests actual :wire-controls wire-controls})))))
      (is (empty? @errors))
      (finally (.stop server 0) (.shutdownNow pool)
               (is (.awaitTermination pool 5 TimeUnit/SECONDS))))))
