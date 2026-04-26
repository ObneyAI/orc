(ns ai.obney.orc.examples.contract-comparison.smoke-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.examples.contract-comparison.pipeline :as pipeline]
            [ai.obney.orc.examples.contract-comparison.agentic :as agentic]
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

(defn- ctx! []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        es-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str "/tmp/cc-" (random-uuid)) :db-name "t"}))
        tenant-id (random-uuid)
        base {:event-store es-store :cache cache :tenant-id tenant-id
              :command-registry (cp/global-command-registry)
              :query-registry (qp/global-query-registry)
              :dscloj-provider :test
              :call-tool-fn (ds/call-tool-fn)}
        procs (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                           (assoc acc n (tp/start {:event-pubsub ps :topics topics :handler-fn handler-fn :context base})))
                         {} @tp/processor-registry*)]
    (assoc base :event-pubsub ps :processors procs)))

(defn- stop! [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (pubsub/stop (:event-pubsub ctx))
  (kv/stop (:cache ctx))
  (es/stop (:event-store ctx)))

(defmacro with-ctx [[s] & body]
  `(let [~s (ctx!)] (try ~@body (finally (stop! ~s)))))

(defn- dispatch! [ctx sid inputs]
  (let [tid (random-uuid) p (runtime/register-completion! tid)]
    (cp/process-command (assoc ctx :command
                               {:command/id (random-uuid) :command/timestamp (time/now)
                                :command/name :sheet/tick-tree :sheet-id sid :tick-id tid
                                :inputs inputs :options {:timeout-ms 30000}}))
    {:promise p}))

(defn- wait [p] (let [r (deref p 30000 ::t)] (if (= ::t r) :timeout r)))

(defn- discover-pdfs []
  (->> (file-seq (clojure.java.io/file (System/getProperty "user.dir")
                                       "examples" "contract_comparison" "sample" "input"))
       (filter #(.isFile %))
       (filter #(.endsWith (clojure.string/lower-case (.getName %)) ".pdf"))
       (mapv #(.getAbsolutePath %))))

(deftest pipeline-builds-and-executes
  (with-ctx [ctx]
    (let [pdfs (discover-pdfs)
          _ (assert (>= (count pdfs) 2))
          mock (fn [_ _ _ _]
                 {:outputs {:result {:report "# diff" :section-diffs []
                                     :key-differences [] :summary "ok"}}
                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (with-redefs [dscloj/predict mock]
        (let [sid (dsl/build-workflow! ctx pipeline/workflow)
              {:keys [promise]} (dispatch! ctx sid {:contracts pdfs})
              r (wait promise)]
          (is (= :success (:status r)))
          (is (some? (-> r :outputs :result))))))))

(deftest agentic-builds-and-executes
  (with-ctx [ctx]
    (let [pdfs (discover-pdfs)
          _ (assert (seq pdfs))
          code "(final! {:result {:report \"# done\" :section-diffs []
                                  :key-differences [] :summary \"ok\"}})"
          mock (fn [_ _ _ _] {:outputs {:code code}
                              :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (with-redefs [dscloj/predict mock]
        (let [sid (dsl/build-workflow! ctx agentic/workflow)
              {:keys [promise]} (dispatch! ctx sid {:contracts pdfs})
              r (wait promise)
              evts (vec (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:sheet/node-execution-completed}
                                  :tags #{[:sheet sid]}}))
              evt (first (filter #(= :success (:status %)) evts))]
          (is (= :success (:status r)))
          (is (some? evt))
          (is (true? (-> evt :rlm :enabled?)))
          (is (= :final! (-> evt :rlm :final-source))))))))

(deftest agentic-context-not-leaked
  (with-ctx [ctx]
    (let [big (str "/tmp/" (apply str (repeat 800 "X")) "/SECRET.pdf")
          captured (atom [])
          mock (fn [_ _ inputs _]
                 (swap! captured conj inputs)
                 {:outputs {:code "(final! {:result {:report \"\" :section-diffs [] :key-differences [] :summary \"\"}})"}
                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (with-redefs [dscloj/predict mock]
        (let [sid (dsl/build-workflow! ctx agentic/workflow)
              {:keys [promise]} (dispatch! ctx sid {:contracts [big]})
              _ (wait promise)
              bb (:context (first @captured))]
          (is (string? bb))
          (is (not (.contains ^String bb "SECRET.pdf")))
          (is (re-find #":contracts" bb)))))))
