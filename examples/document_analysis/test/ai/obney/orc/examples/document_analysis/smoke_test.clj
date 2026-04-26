(ns ai.obney.orc.examples.document-analysis.smoke-test
  "Smoke tests for both styles of document_analysis. Mocked DSCloj —
   verifies the pipeline assembles, dispatches, and completes."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.examples.document-analysis.pipeline :as pipeline]
            [ai.obney.orc.examples.document-analysis.agentic :as agentic]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core :as dscloj]))

(defn- create-async-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        es-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str "/tmp/da-smoke-" (random-uuid)) :db-name "t"}))
        tenant-id (random-uuid)
        base-ctx {:event-store es-store :cache cache :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :test
                  :call-tool-fn (ds/call-tool-fn)}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors)))

(defn- stop-async-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es)))

(defmacro with-ctx [[ctx-sym] & body]
  `(let [~ctx-sym (create-async-context)]
     (try ~@body (finally (stop-async-context ~ctx-sym)))))

(defn- dispatch! [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)]
    (cp/process-command
      (assoc ctx :command
             {:command/id (random-uuid) :command/timestamp (time/now)
              :command/name :sheet/tick-tree :sheet-id sheet-id :tick-id tick-id
              :inputs inputs :options {:timeout-ms 20000}}))
    {:tick-id tick-id :promise p}))

(defn- wait-for [p]
  (let [r (deref p 20000 ::timeout)]
    (if (= ::timeout r) :timeout r)))

(defn- discover-pdf []
  (some->> (file-seq (clojure.java.io/file (System/getProperty "user.dir")
                                           "examples" "document_analysis" "sample" "input"))
           (filter #(.isFile %))
           (filter #(.endsWith (clojure.string/lower-case (.getName %)) ".pdf"))
           first
           .getAbsolutePath))

(deftest pipeline-builds-and-executes
  (testing "Style A pipeline runs end-to-end with mocked LLM"
    (with-ctx [ctx]
      (let [pdf (discover-pdf)
            _ (assert pdf "No sample PDF found")
            mock (fn [_ _ _ _]
                   {:outputs {:doc-summary "summary text"
                              :analysis {:report "# Title\n\nA paragraph."
                                         :key-dates []
                                         :key-entities []}}
                    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (with-redefs [dscloj/predict mock]
          (let [sid (dsl/build-workflow! ctx pipeline/workflow)
                {:keys [promise]} (dispatch! ctx sid
                                             {:documents [pdf]
                                              :criteria "criteria"})
                r (wait-for promise)]
            (is (not= :timeout r))
            (is (= :success (:status r)))
            (is (some? (-> r :outputs :docx-report)))
            (is (.exists (clojure.java.io/file (-> r :outputs :docx-report))))))))))

(deftest agentic-builds-and-executes
  (testing "Style B repl-researcher with :rlm true builds and runs"
    (with-ctx [ctx]
      (let [pdf (discover-pdf)
            _ (assert pdf)
            code "(final! {:analysis {:report \"# done\" :key-dates [] :key-entities []}
                          :docx-report \"/tmp/da-mocked.docx\"})"
            mock (fn [_ _ _ _]
                   {:outputs {:code code}
                    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (with-redefs [dscloj/predict mock]
          (let [sid (dsl/build-workflow! ctx agentic/workflow)
                {:keys [promise]} (dispatch! ctx sid
                                             {:documents [pdf] :criteria "x"})
                r (wait-for promise)
                events (vec (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:sheet/node-execution-completed}
                                      :tags #{[:sheet sid]}}))
                node-evt (first (filter #(= :success (:status %)) events))]
            (is (not= :timeout r))
            (is (= :success (:status r)))
            (is (some? node-evt))
            (is (true? (-> node-evt :rlm :enabled?)))
            (is (= :final! (-> node-evt :rlm :final-source)))))))))

(deftest agentic-context-not-leaked
  (testing "documents value is :context-key — root prompt sees only metadata"
    (with-ctx [ctx]
      (let [big-path (str "/tmp/" (apply str (repeat 800 "X")) "/SECRET.pdf")
            captured (atom [])
            mock (fn [_ _ inputs _]
                   (swap! captured conj inputs)
                   {:outputs {:code "(final! {:analysis {:report \"\" :key-dates [] :key-entities []} :docx-report \"\"})"}
                    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (with-redefs [dscloj/predict mock]
          (let [sid (dsl/build-workflow! ctx agentic/workflow)
                {:keys [promise]} (dispatch! ctx sid
                                             {:documents [big-path] :criteria "x"})
                _ (wait-for promise)
                bb-meta (:context (first @captured))]
            (is (string? bb-meta))
            (is (not (.contains ^String bb-meta "SECRET.pdf"))
                "Documents value must not leak in full into root prompt")
            (is (re-find #":documents" bb-meta))))))))
