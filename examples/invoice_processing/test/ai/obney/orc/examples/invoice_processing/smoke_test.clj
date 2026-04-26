(ns ai.obney.orc.examples.invoice-processing.smoke-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.examples.invoice-processing.pipeline :as pipeline]
            [ai.obney.orc.examples.invoice-processing.agentic :as agentic]
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
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str "/tmp/inv-smoke-" (random-uuid)) :db-name "t"}))
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
              :inputs inputs :options {:timeout-ms 30000}}))
    {:tick-id tick-id :promise p}))

(defn- wait-for [p]
  (let [r (deref p 30000 ::timeout)]
    (if (= ::timeout r) :timeout r)))

(defn- discover-pdfs []
  (->> (file-seq (clojure.java.io/file (System/getProperty "user.dir")
                                       "examples" "invoice_processing" "sample" "input"))
       (filter #(.isFile %))
       (filter #(.endsWith (clojure.string/lower-case (.getName %)) ".pdf"))
       (mapv #(.getAbsolutePath %))))

(deftest pipeline-builds-and-executes
  (testing "Style A pipeline runs end-to-end with mocked LLM"
    (with-ctx [ctx]
      (let [pdfs (discover-pdfs)
            _ (assert (seq pdfs))
            ;; Alternating header / continuation per call so the merge logic
            ;; sees one realistic header per invoice plus a follow-up page.
            call-count (atom 0)
            mock (fn [_ _ _ _]
                   (let [n (swap! call-count inc)
                         header? (odd? n)]
                     {:outputs {:page-extract
                                (if header?
                                  {:vendor-name (str "Vendor-" n)
                                   :invoice-number (str "INV-" n)
                                   :date "2025-01-01" :due-date "2025-02-01"
                                   :subtotal 100.0 :tax 10.0 :total 110.0
                                   :line-items [{:description "header item"
                                                 :quantity 1.0 :unit-price 100.0
                                                 :amount 100.0}]}
                                  {:vendor-name "" :invoice-number ""
                                   :date "" :due-date ""
                                   :subtotal 0.0 :tax 0.0 :total 0.0
                                   :line-items [{:description "continuation item"
                                                 :quantity 2.0 :unit-price 50.0
                                                 :amount 100.0}]})}
                      :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}}))]
        (with-redefs [dscloj/predict mock]
          (let [sid (dsl/build-workflow! ctx pipeline/workflow)
                {:keys [promise]} (dispatch! ctx sid {:invoices pdfs})
                r (wait-for promise)]
            (is (not= :timeout r))
            (is (= :success (:status r)))
            (let [wb (-> r :outputs :workbook)]
              (is (string? wb))
              (is (.exists (clojure.java.io/file wb))))
            (let [result (-> r :outputs :result)]
              (is (pos? (count (:invoices result))))
              (is (number? (:total-amount result)))
              (is (every? #(string? (:vendor-name %)) (:invoices result))
                  "every invoice has a string vendor-name (no nils after schema tightening)"))))))))

(deftest agentic-builds-and-executes
  (testing "Style B repl-researcher with :rlm true runs end-to-end"
    (with-ctx [ctx]
      (let [pdfs (discover-pdfs)
            _ (assert (seq pdfs))
            code "(final! {:result {:invoices [{:vendor-name \"Acme\"
                                                :invoice-number \"INV-1\"
                                                :date \"2025-01-01\"
                                                :due-date \"2025-02-01\"
                                                :subtotal 100.0 :tax 10.0 :total 110.0
                                                :line-items []}]
                                    :total-amount 110.0
                                    :summary \"1 invoice\"}
                          :workbook \"/tmp/agentic-mock.xlsx\"})"
            mock (fn [_ _ _ _]
                   {:outputs {:code code}
                    :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (with-redefs [dscloj/predict mock]
          (let [sid (dsl/build-workflow! ctx agentic/workflow)
                {:keys [promise]} (dispatch! ctx sid {:invoices pdfs})
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
  (with-ctx [ctx]
    (let [big-path (str "/tmp/" (apply str (repeat 800 "X")) "/SECRET.pdf")
          captured (atom [])
          mock (fn [_ _ inputs _]
                 (swap! captured conj inputs)
                 {:outputs {:code "(final! {:result {:invoices [] :total-amount 0.0 :summary \"\"} :workbook \"\"})"}
                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
      (with-redefs [dscloj/predict mock]
        (let [sid (dsl/build-workflow! ctx agentic/workflow)
              {:keys [promise]} (dispatch! ctx sid {:invoices [big-path]})
              _ (wait-for promise)
              bb-meta (:context (first @captured))]
          (is (string? bb-meta))
          (is (not (.contains ^String bb-meta "SECRET.pdf"))
              "Invoice paths must not leak in full into root prompt")
          (is (re-find #":invoices" bb-meta)))))))
