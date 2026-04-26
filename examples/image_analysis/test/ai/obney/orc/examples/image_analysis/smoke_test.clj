(ns ai.obney.orc.examples.image-analysis.smoke-test
  "Smoke tests for both styles of the image_analysis port.

   Verifies that:
   - Both workflows build and dispatch through the async pipeline
   - The schema/round-trip layer accepts the schemas we defined
   - With a mocked DSCloj provider, both styles return :status :success

   Does NOT verify LLM output quality — that's GEPA + judges' job."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.examples.image-analysis.pipeline :as pipeline]
            [ai.obney.orc.examples.image-analysis.agentic :as agentic]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core :as dscloj]))

;; -----------------------------------------------------------------------------
;; Async test context
;; -----------------------------------------------------------------------------

(defn- create-async-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache-dir (str "/tmp/img-smoke-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :test}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps
                                         :topics topics
                                         :handler-fn handler-fn
                                         :context base-ctx})))
                     {}
                     @tp/processor-registry*)]
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
             {:command/id (random-uuid)
              :command/timestamp (time/now)
              :command/name :sheet/tick-tree
              :sheet-id sheet-id
              :tick-id tick-id
              :inputs inputs
              :options {:timeout-ms 15000}}))
    {:tick-id tick-id :promise p}))

(defn- wait-for [p]
  (let [r (deref p 15000 ::timeout)]
    (if (= ::timeout r) :timeout r)))

(defn- discover-sample-image
  "Discover the first .png/.jpg file under examples/image_analysis/sample/input/.
   Done via file-seq rather than a hard-coded filename so unusual unicode
   characters in the predict-rlm-original filename (e.g. U+202F narrow
   no-break space in macOS Screenshot names) don't break the test."
  []
  (let [dir (clojure.java.io/file (System/getProperty "user.dir")
                                  "examples" "image_analysis"
                                  "sample" "input")
        f (->> (file-seq dir)
               (filter #(.isFile %))
               (filter #(let [n (clojure.string/lower-case (.getName %))]
                          (or (.endsWith n ".png")
                              (.endsWith n ".jpg")
                              (.endsWith n ".jpeg"))))
               first)]
    (when f (.getAbsolutePath f))))

(def sample-image-path (delay (discover-sample-image)))

;; -----------------------------------------------------------------------------
;; Style A — Pipeline
;; -----------------------------------------------------------------------------

(deftest pipeline-builds-and-executes
  (testing "Style A pipeline builds and runs end-to-end with a mocked LLM"
    (with-ctx [ctx]
      (let [img @sample-image-path
            _ (assert img "Sample image not found under sample/input/")
            llm-call-count (atom 0)
            mock-llm (fn [_p _module _inputs _opts]
                       (let [n (swap! llm-call-count inc)]
                         {:outputs {:per-image-finding (str "obs-" n)
                                    :answer "synthesized answer"}
                          :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))]
        (with-redefs [dscloj/predict mock-llm]
          (let [sheet-id (dsl/build-workflow! ctx pipeline/workflow)
                {:keys [promise]} (dispatch! ctx sheet-id
                                             {:image-paths [img]
                                              :query "describe"})
                result (wait-for promise)]
            (is (not= :timeout result) "Pipeline should complete within timeout")
            (is (= :success (:status result)))
            (is (= "synthesized answer" (-> result :outputs :answer)))
            (is (pos? @llm-call-count) "Mocked LLM should have been called")))))))

;; -----------------------------------------------------------------------------
;; Style B — RLM-faithful repl-researcher
;; -----------------------------------------------------------------------------

(deftest agentic-builds-and-executes
  (testing "Style B repl-researcher with :rlm true builds and runs end-to-end"
    (with-ctx [ctx]
      ;; The mocked LLM returns Clojure code that uses the SCI-bound
      ;; image-paths and query symbols (RLM-mode :extra-bindings) and calls
      ;; (final! …) — validating both the build/dispatch plumbing and the
      ;; RLM-mode symbol-binding path end-to-end.
      (let [img @sample-image-path
            _ (assert img "Sample image not found under sample/input/")
            code "(final! {:answer (str \"saw \" (count image-paths) \" images for query: \" query)})"
            mock-llm (fn [_p _module _inputs _opts]
                       {:outputs {:code code}
                        :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (with-redefs [dscloj/predict mock-llm]
          (let [sheet-id (dsl/build-workflow! ctx agentic/workflow)
                {:keys [promise]} (dispatch! ctx sheet-id
                                             {:image-paths [img]
                                              :query "describe"})
                result (wait-for promise)
                ;; :rlm telemetry lives on the per-node completion event,
                ;; not on the top-level execute result (which aggregates
                ;; the whole tree).
                events (vec (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:sheet/node-execution-completed}
                                      :tags #{[:sheet sheet-id]}}))
                node-evt (first (filter #(= :success (:status %)) events))]
            (is (not= :timeout result) "Agentic should complete within timeout")
            (is (= :success (:status result)))
            (is (= "saw 1 images for query: describe"
                   (-> result :outputs :answer)))
            (is (some? node-evt))
            (is (true? (-> node-evt :rlm :enabled?)))
            (is (= :final! (-> node-evt :rlm :final-source)))))))))

(deftest agentic-context-not-leaked-test
  (testing "image-paths is :context-key — value lives in SCI but root prompt sees only metadata"
    (with-ctx [ctx]
      (let [;; A long synthetic path that's clearly distinguishable, used so
            ;; we can assert it doesn't show up in full inside the root
            ;; prompt's metadata blurb. The agentic node's
            ;; :max-context-preview-chars defaults to 600, so we need a
            ;; secret marker that's positioned beyond that cap.
            big-path (str "/tmp/" (apply str (repeat 800 "X")) "/SECRET.png")
            captured-prompts (atom [])
            mock-llm (fn [_p _module inputs _opts]
                       (swap! captured-prompts conj inputs)
                       {:outputs {:code "(final! {:answer \"ok\"})"}
                        :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (with-redefs [dscloj/predict mock-llm]
          (let [sheet-id (dsl/build-workflow! ctx agentic/workflow)
                {:keys [promise]} (dispatch! ctx sheet-id
                                             {:image-paths [big-path]
                                              :query "x"})
                _ (wait-for promise)
                first-prompt (first @captured-prompts)
                bb-meta (:context first-prompt)]
            (is (string? bb-meta))
            (is (not (.contains ^String bb-meta "SECRET.png"))
                "Full image-paths value must not leak into root prompt")
            (is (re-find #":image-paths" bb-meta)
                "Metadata blurb should still mention :image-paths by name")))))))
