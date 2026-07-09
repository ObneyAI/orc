(ns ai.obney.orc.ontology.classifier-test
  "Tests for C-2c-1 + C-2c-2: pure classify-task fn + event/command
   schemas + :ontology/assign-task-class defcommand handler.

   The classifier wraps `search-descriptions` with reranker-driven intent
   to assign a tree-class UUID to a task signature. Pure over strings —
   the caller (C-2c-2's executor wedge) handles all sheet→fingerprint
   lookup and command dispatch.

   These tests run with-redefs on `search-descriptions` so they exercise
   only the orchestration + threshold logic. Real LLM verification
   lives in C-2c-3."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2c-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}]
    base-ctx))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; RED #1 — :ontology/task-classified event schema validates
;; =============================================================================

(deftest task-classified-event-schema-validates
  (testing "A well-formed :ontology/task-classified event passes its schema"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.87
                 :top-candidates    [{:document-id "x" :score 0.87}
                                     {:document-id "y" :score 0.42}]
                 :reasoning         "This candidate's recommended pattern matches the task's chunked-extraction shape."
                 :classified-at     "2026-05-27T12:00:00Z"
                 :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (some? schema)
          ":ontology/task-classified schema should be registered")
      (is (m/validate schema event)
          (str "Event should validate. Explanation: "
               (when schema (pr-str (m/explain schema event)))))))

  (testing "Fresh-mint variant validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.42
                 :top-candidates    [{:document-id "rejected-1" :score 0.42}]
                 :reasoning         "No candidate met threshold; minted fresh task class."
                 :classified-at     "2026-05-27T12:00:00Z"
                 :was-fresh-mint?   true}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          "Fresh-mint event should also validate")))

  (testing "Missing :assigned-tree-id is rejected"
    (let [bad {:source-sheet-id  (random-uuid)
               :source-tick-id   (random-uuid)
               :source-node-id   (random-uuid)
               :confidence       0.5
               :top-candidates   []
               :reasoning        "x"
               :classified-at    "2026-05-27T12:00:00Z"
               :was-fresh-mint?  true}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (not (m/validate schema bad))
          "Missing :assigned-tree-id should fail validation")))

  (testing ":confidence outside [0.0, 1.0] is rejected"
    (let [bad-high {:source-sheet-id (random-uuid)
                   :source-tick-id (random-uuid)
                   :source-node-id (random-uuid)
                   :assigned-tree-id (random-uuid)
                   :confidence 1.5
                   :top-candidates []
                   :reasoning "x"
                   :classified-at "2026"
                   :was-fresh-mint? false}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (not (m/validate schema bad-high)))))

  (testing ":was-fresh-mint? must be boolean"
    (let [bad {:source-sheet-id (random-uuid)
               :source-tick-id (random-uuid)
               :source-node-id (random-uuid)
               :assigned-tree-id (random-uuid)
               :confidence 0.5
               :top-candidates []
               :reasoning "x"
               :classified-at "2026"
               :was-fresh-mint? "true"}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (not (m/validate schema bad))))))

;; =============================================================================
;; RED #2 — :ontology/assign-task-class command schema validates
;; =============================================================================

(deftest assign-task-class-command-schema-validates
  (testing "A well-formed :ontology/assign-task-class command passes its schema"
    (let [cmd {:source-sheet-id   (random-uuid)
               :source-tick-id    (random-uuid)
               :source-node-id    (random-uuid)
               :assigned-tree-id  (random-uuid)
               :confidence        0.87
               :top-candidates    [{:document-id "x" :score 0.87}]
               :reasoning         "Concrete fit description."
               :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      (is (some? schema)
          ":ontology/assign-task-class schema should be registered")
      (is (m/validate schema cmd)
          (str "Command should validate. Explanation: "
               (when schema (pr-str (m/explain schema cmd)))))))

  (testing "Command MUST NOT carry :classified-at (the handler stamps it on the event)"
    (let [with-stamp {:source-sheet-id (random-uuid)
                      :source-tick-id (random-uuid)
                      :source-node-id (random-uuid)
                      :assigned-tree-id (random-uuid)
                      :confidence 0.5
                      :top-candidates []
                      :reasoning "x"
                      :was-fresh-mint? true
                      :classified-at "should-not-be-here"}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      ;; Malli :map by default accepts extra keys, so this should still
      ;; validate. The semantic rule (handler stamps :classified-at) is
      ;; enforced by the C-2c-2 defcommand body, not by the schema.
      (is (m/validate schema with-stamp)
          "Schema is permissive about extra keys; :classified-at semantics enforced by handler"))))

;; =============================================================================
;; RED #3 — classify-task returns matched tree-id when top-1 ≥ threshold
;; =============================================================================

(deftest classify-task-returns-match-when-above-threshold
  (testing "Top-1 score ≥ threshold → :assigned-tree-id is the matched target-id and :was-fresh-mint? is false"
    (let [matched-uuid (random-uuid)
          fake-results [{:content "Document-analysis tree summary..."
                         :score 0.9
                         :rank 1
                         :document-id "tree-fingerprint::a"
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id matched-uuid
                                             :confidence 1.0
                                             :last-update "2026"}
                         :reasoning "Best fit for this kind of task."
                         :fitness-score 0.85}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] fake-results)]
        ;; :walk-down? false — this test exercises top-1 matching, not
        ;; the C-2d-2 walk-down algorithm. Walk-down behavior has its
        ;; own dedicated tests in walk_down_classifier_test.clj.
        (let [result (ontology/classify-task {}
                       {:task-signature "extract summary + dates from a long document"
                        :threshold 0.7
                        :walk-down? false})]
          (is (= matched-uuid (:assigned-tree-id result))
              ":assigned-tree-id is the matched candidate's target-id")
          (is (false? (:was-fresh-mint? result))
              ":was-fresh-mint? is false on a confident match")
          (is (= 0.85 (:confidence result))
              ":confidence reflects the top-1 fitness-score")
          (is (vector? (:top-candidates result))
              "Result carries :top-candidates")
          (is (= 1 (count (:top-candidates result)))
              "All candidates from search-descriptions are surfaced")
          (is (string? (:reasoning result))
              ":reasoning is a non-nil string"))))))

;; =============================================================================
;; RED #4 — classify-task mints fresh UUID when top-1 < threshold
;; =============================================================================

(deftest classify-task-mints-fresh-when-below-threshold
  (testing "Top-1 score < threshold → fresh UUID + :was-fresh-mint? true + rejected candidate retained"
    (let [rejected-uuid (random-uuid)
          fake-results [{:content "Weak match"
                         :score 0.5 :rank 1
                         :document-id "tf::weak"
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id rejected-uuid
                                             :confidence 0.5
                                             :last-update "2026"}
                         :reasoning "Only loosely related to the task."
                         :fitness-score 0.5}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] fake-results)]
        (let [result (ontology/classify-task {}
                       {:task-signature "novel task description"
                        :threshold 0.7})]
          (is (true? (:was-fresh-mint? result))
              "Fresh-mint flag set when no candidate passes threshold")
          (is (not= rejected-uuid (:assigned-tree-id result))
              ":assigned-tree-id is a fresh UUID, NOT the rejected match")
          (is (uuid? (:assigned-tree-id result))
              ":assigned-tree-id is still a UUID")
          (is (= 1 (count (:top-candidates result)))
              "Rejected candidate is preserved in :top-candidates for audit")
          (is (= 0.5 (:confidence result))
              ":confidence still reflects the top-1 score (for audit)"))))))

;; =============================================================================
;; RED #5 — classify-task mints fresh UUID when search returns empty
;; =============================================================================

(deftest classify-task-mints-fresh-when-search-empty
  (testing "search-descriptions returns [] → fresh UUID + :top-candidates [] + :was-fresh-mint? true"
    (with-redefs [ontology/search-descriptions (fn [_ctx _opts] [])]
      (let [result (ontology/classify-task {}
                     {:task-signature "anything"
                      :threshold 0.7})]
        (is (true? (:was-fresh-mint? result)))
        (is (uuid? (:assigned-tree-id result)))
        (is (= [] (:top-candidates result))
            ":top-candidates is empty vector when search returns []")
        (is (= 0.0 (:confidence result))
            ":confidence defaults to 0.0 when no candidates returned")))))

;; =============================================================================
;; RED #6 — string-fingerprint target coerced to stable UUID
;; =============================================================================

(deftest classify-task-coerces-string-fingerprint-to-stable-uuid
  (testing "When the matched candidate's :target-id is a string fingerprint, classify-task returns a deterministic UUID derived from it"
    (let [fingerprint "seed:tree:ChunkedExtraction"
          expected-uuid (java.util.UUID/nameUUIDFromBytes
                          (.getBytes fingerprint "UTF-8"))
          fake-results [{:content "Pattern" :score 0.95 :rank 1
                         :document-id (str ":tree-fingerprint:" fingerprint)
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id fingerprint
                                             :confidence 1.0
                                             :last-update "2026"}
                         :reasoning "Matches canonical 4-stage shape."
                         :fitness-score 0.95}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] fake-results)]
        (let [result (ontology/classify-task {}
                       {:task-signature "x" :threshold 0.7})]
          (is (= expected-uuid (:assigned-tree-id result))
              ":assigned-tree-id is the deterministic UUID derived from the fingerprint string")
          (is (false? (:was-fresh-mint? result))
              "Still considered a match — string fingerprints are legitimate tree-class targets"))))))

;; =============================================================================
;; RED #7 — classify-task retrieves BOTH the tree-fingerprint AND tree-class axes
;;
;; EL-1a (ADR 0015): classify-task now queries a granularity SET
;; #{:tree-fingerprint :tree-class}. Querying :tree-fingerprint alone left
;; every recorded :tree-class indexed-but-unreachable, so a second similar
;; task could never match the first's class — the scatter bug. The
;; :tree-class axis MUST be in the retrieval granularity for a recorded
;; class to be a candidate.
;; =============================================================================

(deftest classify-task-retrieves-tree-fingerprint-and-tree-class-axes
  (testing "classify-task retrieves a granularity SET covering BOTH :tree-fingerprint and :tree-class, and uses the classifier-intent string"
    (let [captured (atom nil)]
      (with-redefs [ontology/search-descriptions (fn [_ctx opts]
                                                    (reset! captured opts)
                                                    [])]
        (ontology/classify-task {} {:task-signature "x" :threshold 0.7})
        (let [g (:granularity @captured)]
          (is (set? g)
              ":granularity is a SET of axes (multi-axis retrieval)")
          (is (contains? g :tree-class)
              ":tree-class axis is queried (the EL-1a fix — recorded classes are now reachable)")
          (is (contains? g :tree-fingerprint)
              ":tree-fingerprint axis is still queried (no regression on exact-shape retrieval)"))
        (is (string? (:rerank-with-intent @captured))
            "An intent string is passed to the reranker")
        (is (re-find #"(?i)classify" (:rerank-with-intent @captured))
            "The classifier-intent string communicates that we're classifying")))))

;; =============================================================================
;; RED #8 — :parent-summary concatenated into the search signature
;; =============================================================================

(deftest classify-task-includes-parent-summary-in-signature
  (testing "When :parent-summary is provided, classify-task concatenates it into the :query passed to search-descriptions"
    (let [captured (atom nil)
          parent-summary "Parent tree is a chunked-extraction pipeline for legal documents."]
      (with-redefs [ontology/search-descriptions (fn [_ctx opts]
                                                    (reset! captured opts)
                                                    [])]
        (ontology/classify-task {}
          {:task-signature "extract per-section summary"
           :parent-summary parent-summary
           :threshold 0.7})
        (let [q (:query @captured)]
          (is (string? q))
          (is (re-find #"extract per-section summary" q)
              "Task signature is in the query")
          (is (re-find #"chunked-extraction pipeline for legal documents" q)
              "Parent summary text is also in the query")
          (is (re-find #"PARENT CONTEXT" q)
              "Query carries a clear PARENT CONTEXT marker so the reranker can reason about source"))))))

;; =============================================================================
;; RED #9 — classify-task tolerates absent :parent-summary
;; =============================================================================

(deftest classify-task-tolerates-nil-parent-summary
  (testing "When :parent-summary is absent or nil, classify-task proceeds with just the task signature"
    (let [captured (atom nil)]
      (with-redefs [ontology/search-descriptions (fn [_ctx opts]
                                                    (reset! captured opts)
                                                    [])]
        ;; absent
        (ontology/classify-task {}
          {:task-signature "novel task" :threshold 0.7})
        (is (= "novel task" (:query @captured))
            "Query is just the task-signature when :parent-summary is absent")

        ;; nil
        (reset! captured nil)
        (ontology/classify-task {}
          {:task-signature "another task"
           :parent-summary nil
           :threshold 0.7})
        (is (= "another task" (:query @captured))
            "Nil :parent-summary is treated the same as absent")))))

;; =============================================================================
;; RED #10 — invalid threshold rejected at entry
;; =============================================================================

(deftest classify-task-rejects-invalid-threshold
  (testing "Threshold out of [0.0, 1.0] is rejected with ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ontology/classify-task {}
                   {:task-signature "x" :threshold 1.5}))
        "Threshold > 1.0 throws ex-info")
    (is (thrown? clojure.lang.ExceptionInfo
                 (ontology/classify-task {}
                   {:task-signature "x" :threshold -0.1}))
        "Threshold < 0.0 throws ex-info"))

  (testing "Non-numeric threshold is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ontology/classify-task {}
                   {:task-signature "x" :threshold "0.7"}))))

  (testing "Missing :task-signature is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ontology/classify-task {} {:threshold 0.7})))))

;; =============================================================================
;; C-2c-2 RED #2 — defcommand :ontology/assign-task-class emits :ontology/task-classified
;; =============================================================================

(deftest assign-task-class-defcommand-emits-task-classified-event
  (testing "Dispatching :ontology/assign-task-class produces a :ontology/task-classified event with all body fields + a :classified-at stamp + a [:tick tick-id] tag"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned-tree-id (random-uuid)
            cmd {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id assigned-tree-id
                 :confidence 0.87
                 :top-candidates [{:document-id "x" :score 0.87}]
                 :reasoning "Matches chunked-extraction shape."
                 :was-fresh-mint? false}
            _result (cp/process-command (assoc ctx :command cmd))
            ;; Read back what landed in the event store
            events (->> (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:ontology/task-classified}})
                        (into []))
            event (first events)]
        (is (= 1 (count events))
            "Exactly one :ontology/task-classified event is in the store")
      (is (= :ontology/task-classified (:event/type event))
          "Event type is :ontology/task-classified")
      (is (= sheet-id (:source-sheet-id event))
          ":source-sheet-id passes through")
      (is (= tick-id (:source-tick-id event))
          ":source-tick-id passes through")
      (is (= node-id (:source-node-id event))
          ":source-node-id passes through")
      (is (= assigned-tree-id (:assigned-tree-id event))
          ":assigned-tree-id passes through")
      (is (= 0.87 (:confidence event))
          ":confidence passes through")
      (is (false? (:was-fresh-mint? event))
          ":was-fresh-mint? passes through")
      (is (string? (:classified-at event))
          ":classified-at stamped by the handler")
      (is (contains? (:event/tags event) [:tick tick-id])
          "Event tagged with [:tick tick-id] for cheap downstream query")
        (let [schema (get @schema-util/registry* :ontology/task-classified)]
          (is (m/validate schema event)
              (str "Emitted event must satisfy its registered schema. Explanation: "
                   (when schema (pr-str (m/explain schema event))))))))))

;; =============================================================================
;; C-2c-2 RED #3 — repl-researcher DSL passes :auto-classify? + threshold through
;;
;; The :rlm config on repl-researcher is already an arbitrary map per the
;; existing DSL — no DSL change required. This test locks in the contract
;; that :auto-classify? and :auto-classify-threshold inside :rlm survive
;; the DSL builder + emerge intact on the built node for the executor wedge
;; to read. If a future refactor strips unknown :rlm keys, this test fails.
;; =============================================================================

(deftest repl-researcher-rlm-config-carries-auto-classify-opts
  (testing ":auto-classify? true on :rlm config survives to the built node"
    (let [node ((requiring-resolve 'ai.obney.orc.orc-service.interface/repl-researcher)
                "test-node"
                :instruction "Process the document."
                :reads [:document]
                :writes [:summary]
                :rlm {:auto-classify? true})]
      (is (= true (-> node :rlm :auto-classify?))
          ":auto-classify? flag is preserved on the built node")))

  (testing ":auto-classify-threshold survives to the built node"
    (let [node ((requiring-resolve 'ai.obney.orc.orc-service.interface/repl-researcher)
                "test-node"
                :instruction "Process the document."
                :reads [:document]
                :writes [:summary]
                :rlm {:auto-classify? true :auto-classify-threshold 0.85})]
      (is (= 0.85 (-> node :rlm :auto-classify-threshold))
          ":auto-classify-threshold preserved on the built node")))

  (testing ":auto-classify? defaults to absent (legacy nodes unchanged)"
    (let [node ((requiring-resolve 'ai.obney.orc.orc-service.interface/repl-researcher)
                "test-node"
                :instruction "Process the document."
                :reads [:document]
                :writes [:summary]
                :rlm true)]
      (is (= true (:rlm node))
          ":rlm true (legacy boolean form) still produces :rlm true on the node")
      (is (nil? (-> node :rlm :auto-classify?))
          "No :auto-classify? key when not provided"))))

;; =============================================================================
;; C-2c-2 RED #4 — pure task-signature builder
;;
;; Lives next to classify-task in the task-classifier ns. Takes the node map
;; (or pieces of it) and returns the static-config signature string. Pure;
;; no I/O. The executor wedge calls this to build the :task-signature opt
;; passed to classify-task.
;; =============================================================================

(deftest build-task-signature-includes-instruction-reads-writes-tools
  (testing "Builds a signature string concatenating instruction + reads + writes + tools"
    (let [node {:instruction "Process the document and extract entities."
                :reads [:document]
                :writes [:entities :summary]
                :mcp-tools ["fetch_url" "summarize"]
                :browser-tools ["open" "snapshot"]}
          build-fn (requiring-resolve
                     'ai.obney.orc.ontology.core.task-classifier/build-task-signature)
          sig (build-fn node)]
      (is (string? sig)
          "Returns a string")
      (is (re-find #"Process the document and extract entities" sig)
          "Includes the instruction text")
      (is (re-find #"document" sig)
          "Includes the reads keyword name")
      (is (re-find #"entities" sig)
          "Includes the writes keyword names")
      (is (re-find #"summary" sig)
          "Includes all writes keywords")
      (is (re-find #"fetch_url" sig)
          "Includes mcp-tools names")
      (is (re-find #"open" sig)
          "Includes browser-tools names")))

  (testing "Builds a signature with minimal nodes (no tools)"
    (let [node {:instruction "Simple task."
                :reads [:x]
                :writes [:y]}
          build-fn (requiring-resolve
                     'ai.obney.orc.ontology.core.task-classifier/build-task-signature)
          sig (build-fn node)]
      (is (string? sig))
      (is (re-find #"Simple task" sig))
      (is (re-find #":x" sig))
      (is (re-find #":y" sig)))))

;; =============================================================================
;; C-2c-2 RED #5-7 — executor wedge (maybe-auto-classify-and-set-context)
;;
;; The wedge fn lives in orc-service's todo_processors.clj. It runs on every
;; node-execute-requested for an :rlm repl-researcher BEFORE
;; apply-ontology-context renders principles into the instruction. Behavior:
;;   - :context already set on node       → no-op, return node
;;   - :auto-classify? false (or absent)  → no-op, return node
;;   - else                                → call classify-task, dispatch
;;                                            :ontology/assign-task-class
;;                                            command, set :context on node
;;
;; All three branches tested below via with-redefs on classify-task.
;; =============================================================================

(defn- wedge-fn []
  (requiring-resolve
    'ai.obney.orc.orc-service.core.todo-processors/maybe-auto-classify-and-set-context))

(deftest wedge-no-op-when-auto-classify-off
  (testing "When :auto-classify? is false (or absent), the wedge returns the node unchanged + emits no event"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            classify-called? (atom false)
            node {:type :repl-researcher
                  :id node-id
                  :instruction "Process the document."
                  :reads [:document]
                  :writes [:summary]
                  :rlm {:auto-classify? false}}]
        (with-redefs [ontology/classify-task
                      (fn [_ctx _opts]
                        (reset! classify-called? true)
                        (throw (ex-info "should not be called" {})))]
          (let [result-node ((wedge-fn) node (assoc ctx
                                                    :sheet-id sheet-id
                                                    :tick-id tick-id))]
            (is (= node result-node)
                "Node returned unchanged when :auto-classify? is false")
            (is (false? @classify-called?)
                "classify-task was NOT invoked"))
          (let [classified-events (into [] (es/read (:event-store ctx)
                                                     {:tenant-id (:tenant-id ctx)
                                                      :types #{:ontology/task-classified}}))]
            (is (empty? classified-events)
                "No :ontology/task-classified events emitted")))))))

(deftest wedge-skips-when-manual-context-set
  (testing "When :context {:tree-id <uuid>} is set, the wedge skips classification regardless of :auto-classify?"
    (with-test-ctx [ctx]
      (let [manual-tree-id (random-uuid)
            classify-called? (atom false)
            node {:type :repl-researcher
                  :id (random-uuid)
                  :instruction "x"
                  :reads [:x]
                  :writes [:y]
                  :rlm {:auto-classify? true}
                  :context {:tree-id manual-tree-id
                            :self-learning? true}}]
        (with-redefs [ontology/classify-task
                      (fn [_ctx _opts]
                        (reset! classify-called? true)
                        (throw (ex-info "should not be called when manual :context wins" {})))]
          (let [result-node ((wedge-fn) node (assoc ctx
                                                    :sheet-id (random-uuid)
                                                    :tick-id (random-uuid)))]
            (is (= manual-tree-id (-> result-node :context :tree-id))
                "Manual :tree-id preserved")
            (is (false? @classify-called?)
                "classify-task was NOT invoked"))
          (let [events (into [] (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :types #{:ontology/task-classified}}))]
            (is (empty? events)
                "No :ontology/task-classified events emitted (manual wins)")))))))

(deftest wedge-classifies-when-auto-on-and-no-context
  (testing "When :auto-classify? true AND no :context, the wedge calls classify-task, dispatches the command, and sets :context on the node"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned-tree-id (random-uuid)
            classify-opts (atom nil)
            fake-result {:assigned-tree-id assigned-tree-id
                         :confidence 0.85
                         :top-candidates [{:document-id "a" :score 0.85}]
                         :reasoning "Strong fit."
                         :was-fresh-mint? false}
            node {:type :repl-researcher
                  :id node-id
                  :instruction "Process the document."
                  :reads [:document]
                  :writes [:summary]
                  :rlm {:auto-classify? true}}]
        (with-redefs [ontology/classify-task (fn [_ctx opts]
                                                (reset! classify-opts opts)
                                                fake-result)
                      ontology/classify-behaviors (fn [_ _]
                                                    {:behaviors [] :rerank-fallback? false})]
          (let [result-node ((wedge-fn) node (assoc ctx
                                                    :sheet-id sheet-id
                                                    :tick-id tick-id))]
            (is (some? @classify-opts)
                "classify-task was invoked")
            (is (= assigned-tree-id (-> result-node :context :tree-id))
                ":context.:tree-id populated from classifier")
            ;; R-Inject: wedge now stashes the full classifier payload under
            ;; :context.:r05-classifier so apply-r05-classifier-context can
            ;; prepend it to the model's Phase 1 prompt. The legacy
            ;; :self-learning? flag was dropped (it only existed for the
            ;; replaced apply-ontology-context path).
            (is (some? (get-in result-node [:context :r05-classifier]))
                "wedge stamps :r05-classifier payload on :context")
            (is (= assigned-tree-id
                   (get-in result-node [:context :r05-classifier :structural :assigned-tree-id]))
                "structural envelope carries the assigned tree-id"))
          (Thread/sleep 100)
          (let [events (into [] (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :types #{:ontology/task-classified}}))]
            (is (= 1 (count events))
                "Exactly one :ontology/task-classified event emitted")
            (let [event (first events)]
              (is (= assigned-tree-id (:assigned-tree-id event)))
              (is (= sheet-id (:source-sheet-id event)))
              (is (= tick-id (:source-tick-id event)))
              (is (= node-id (:source-node-id event))))))))))

;; =============================================================================
;; C-2c-2 RED #8 — :auto-classify-threshold forwarded to classify-task
;; =============================================================================

(deftest wedge-forwards-auto-classify-threshold
  (testing "When :rlm carries :auto-classify-threshold, the wedge passes it as the threshold opt to classify-task"
    (with-test-ctx [ctx]
      (let [captured (atom nil)
            node {:type :repl-researcher
                  :id (random-uuid)
                  :instruction "x" :reads [:x] :writes [:y]
                  :rlm {:auto-classify? true
                        :auto-classify-threshold 0.85}}]
        (with-redefs [ontology/classify-task (fn [_ctx opts]
                                                (reset! captured opts)
                                                {:assigned-tree-id (random-uuid)
                                                 :confidence 0.9
                                                 :top-candidates []
                                                 :reasoning ""
                                                 :was-fresh-mint? false})]
          ((wedge-fn) node (assoc ctx :sheet-id (random-uuid) :tick-id (random-uuid)))
          (is (= 0.85 (:threshold @captured))
              ":auto-classify-threshold from :rlm passes through to classify-task")))))

  (testing "Defaults to 0.7 when :auto-classify-threshold is absent"
    (with-test-ctx [ctx]
      (let [captured (atom nil)
            node {:type :repl-researcher
                  :id (random-uuid)
                  :instruction "x" :reads [:x] :writes [:y]
                  :rlm {:auto-classify? true}}]
        (with-redefs [ontology/classify-task (fn [_ctx opts]
                                                (reset! captured opts)
                                                {:assigned-tree-id (random-uuid)
                                                 :confidence 0.9
                                                 :top-candidates []
                                                 :reasoning ""
                                                 :was-fresh-mint? false})]
          ((wedge-fn) node (assoc ctx :sheet-id (random-uuid) :tick-id (random-uuid)))
          (is (= 0.7 (:threshold @captured))
              "Default threshold is 0.7 when not specified"))))))

;; =============================================================================
;; C-2c-2 RED #9 — parent-summary lookup happens and is forwarded
;; =============================================================================

(deftest wedge-passes-parent-summary-to-classify-task
  (testing "When the corpus has a :tree-fingerprint description for the parent sheet, the wedge looks it up and passes :parent-summary to classify-task"
    (with-test-ctx [ctx]
      (let [captured (atom nil)
            sheet-id (random-uuid)
            parent-summary-text "Parent tree is a chunked-extraction pipeline."
            node {:type :repl-researcher
                  :id (random-uuid)
                  :instruction "x" :reads [:x] :writes [:y]
                  :rlm {:auto-classify? true}}]
        (with-redefs [ontology/classify-task (fn [_ctx opts]
                                                (reset! captured opts)
                                                {:assigned-tree-id (random-uuid)
                                                 :confidence 0.9 :top-candidates []
                                                 :reasoning "" :was-fresh-mint? false})
                      ontology/get-description (fn [_ctx granularity target]
                                                  (when (and (= :tree-fingerprint granularity)
                                                             (= sheet-id target))
                                                    {:summary parent-summary-text}))]
          ((wedge-fn) node (assoc ctx :sheet-id sheet-id :tick-id (random-uuid)))
          (is (= parent-summary-text (:parent-summary @captured))
              ":parent-summary forwarded to classify-task")))))

  (testing "When the corpus has NO parent description, :parent-summary is absent (graceful degrade)"
    (with-test-ctx [ctx]
      (let [captured (atom nil)
            node {:type :repl-researcher
                  :id (random-uuid)
                  :instruction "x" :reads [:x] :writes [:y]
                  :rlm {:auto-classify? true}}]
        (with-redefs [ontology/classify-task (fn [_ctx opts]
                                                (reset! captured opts)
                                                {:assigned-tree-id (random-uuid)
                                                 :confidence 0.9 :top-candidates []
                                                 :reasoning "" :was-fresh-mint? false})
                      ontology/get-description (fn [_ctx _g _t] nil)]
          ((wedge-fn) node (assoc ctx :sheet-id (random-uuid) :tick-id (random-uuid)))
          (is (not (contains? @captured :parent-summary))
              "No :parent-summary key when corpus is cold for the parent"))))))

;; =============================================================================
;; C-2c-2 RED #10 — collect-tick-classification helper folds events into envelope
;;
;; The runtime's orc/execute calls a helper that queries the event store
;; by [:tick tick-id] tag for :ontology/task-classified events and folds
;; the latest one into the result envelope as :auto-classification.
;;
;; We test the helper directly (pure event-store query → envelope shape).
;; Wiring into runtime.execute is covered by C-2c-3's live verify.
;; =============================================================================

(deftest collect-classification-finds-event-by-tick-tag
  (testing "When a :ontology/task-classified event is in the store with the matching [:tick tick-id] tag, the helper returns the envelope rich map"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned-tree-id (random-uuid)]
        ;; Dispatch the command so the event lands in the store with the tag
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/assign-task-class
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :source-sheet-id sheet-id
                  :source-tick-id tick-id
                  :source-node-id node-id
                  :assigned-tree-id assigned-tree-id
                  :confidence 0.87
                  :top-candidates [{:document-id "a" :score 0.87}
                                   {:document-id "b" :score 0.42}]
                  :reasoning "Concrete fit."
                  :was-fresh-mint? false}))
        (Thread/sleep 100)
        (let [collect (requiring-resolve
                        'ai.obney.orc.orc-service.core.runtime/collect-tick-classification)
              envelope (collect ctx tick-id)]
          (is (some? envelope)
              "Envelope returned when event exists for this tick-id")
          (is (= assigned-tree-id (:tree-id envelope))
              ":tree-id from the event")
          (is (= 0.87 (:confidence envelope))
              ":confidence from the event")
          (is (vector? (:top-candidates envelope))
              ":top-candidates is a vector")
          (is (false? (:was-fresh-mint? envelope))
              ":was-fresh-mint? preserved")))))

  (testing "No event in store → returns nil (envelope is omitted by callers)"
    (with-test-ctx [ctx]
      (let [collect (requiring-resolve
                      'ai.obney.orc.orc-service.core.runtime/collect-tick-classification)]
        (is (nil? (collect ctx (random-uuid)))
            "No event for this tick → nil (no envelope to attach)")))))
