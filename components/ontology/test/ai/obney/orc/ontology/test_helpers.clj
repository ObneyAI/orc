(ns ai.obney.orc.ontology.test-helpers
  "Test helpers for ontology component.

   Provides:
   - In-memory event store test context
   - Command execution helpers
   - Test data factories
   - Event assertion helpers"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.queries :as qry]
            [ai.obney.orc.ontology.core.read-models :as rm])
  (:import [java.io File]))

;; =============================================================================
;; Test Context Management
;; =============================================================================

(defn- delete-dir-recursively
  "Delete a directory and all its contents."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-dir-recursively (.getPath child))))
      (.delete f))))

(defn create-test-context
  "Create a fresh test context with in-memory event store and LMDB cache."
  []
  (rmp/l1-clear!)
  (let [dir (str "/tmp/ontology-test-" (random-uuid))
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub nil
                               :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
     ::cache-dir dir}))

(defn stop-context
  "Stop the test context and clean up resources."
  [ctx]
  (rmp/l1-clear!)
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (delete-dir-recursively dir)))

(defmacro with-test-context
  "Execute body with a fresh test context, cleaning up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context)]
     (try
       ~@body
       (finally
         (stop-context ~ctx-sym)))))

;; =============================================================================
;; Event Helpers
;; =============================================================================

(defn apply-events!
  "Apply events from a command result to the event store."
  [ctx result]
  (when-let [events (seq (:command-result/events result))]
    (es/append (:event-store ctx) {:events (vec events) :tenant-id (:tenant-id ctx)}))
  result)

(defn run-and-apply!
  "Run a command and apply its events to the event store.
   Returns the command result."
  [ctx command-fn & args]
  (let [result (apply command-fn (cons ctx args))]
    (apply-events! ctx result)
    result))

(defn get-result-events
  "Get events from a command result."
  [result]
  (:command-result/events result))

(defn event-of-type?
  "Check if result contains an event of the given type."
  [result event-type]
  (some #(= event-type (:event/type %))
        (get-result-events result)))

(defn get-event-by-type
  "Get the first event of a given type from result."
  [result event-type]
  (first (filter #(= event-type (:event/type %))
                 (get-result-events result))))

;; =============================================================================
;; Test Data Factories
;; =============================================================================

(defn make-concept-data
  "Create concept data for testing."
  [& {:keys [ontology-id uri label description scope broader indicators]
      :or {ontology-id (random-uuid)
           scope :custom
           broader []
           indicators []}}]
  {:ontology-id ontology-id
   :uri uri
   :label label
   :description description
   :scope scope
   :broader broader
   :indicators indicators})

(defn make-tree-strength-data
  "Create tree strength data for testing."
  [tree-id & {:keys [pattern-uri confidence evidence-trace-ids avg-score]
              :or {pattern-uri "success:ValidationLoop"
                   confidence 0.85
                   evidence-trace-ids []
                   avg-score 0.9}}]
  {:tree-id tree-id
   :pattern-uri pattern-uri
   :confidence confidence
   :evidence-trace-ids (if (empty? evidence-trace-ids)
                         [(random-uuid)]
                         evidence-trace-ids)
   :avg-score avg-score})

(defn make-tree-weakness-data
  "Create tree weakness data for testing."
  [tree-id & {:keys [failure-uri subtype-uri frequency severity triggers evidence-trace-ids]
              :or {failure-uri "failure:Hallucination"
                   frequency 0.3
                   severity :high
                   triggers ["missing context"]
                   evidence-trace-ids []}}]
  {:tree-id tree-id
   :failure-uri failure-uri
   :subtype-uri subtype-uri
   :frequency frequency
   :severity severity
   :triggers triggers
   :evidence-trace-ids (if (empty? evidence-trace-ids)
                         [(random-uuid)]
                         evidence-trace-ids)})

(defn make-problem-mapping-data
  "Create problem mapping data for testing."
  [tree-id & {:keys [problem-uri success-rate execution-count]
              :or {problem-uri "problem:Classification"
                   success-rate 0.85
                   execution-count 100}}]
  {:tree-id tree-id
   :problem-uri problem-uri
   :success-rate success-rate
   :execution-count execution-count})

(defn make-node-pattern-data
  "Create node pattern data for testing."
  [node-id sheet-id & {:keys [node-type pattern-type effective? pattern-description
                              metrics evidence-trace-ids]
                       :or {node-type :llm
                            pattern-type :instruction
                            effective? true
                            pattern-description "Use explicit output schemas"
                            metrics {:success-rate 0.9 :avg-score 0.85}
                            evidence-trace-ids []}}]
  {:node-id node-id
   :sheet-id sheet-id
   :node-type node-type
   :pattern-type pattern-type
   :effective? effective?
   :pattern-description pattern-description
   :metrics metrics
   :evidence-trace-ids (if (empty? evidence-trace-ids)
                         [(random-uuid)]
                         evidence-trace-ids)})

(defn make-evaluation-data
  "Create evaluation data for testing classification."
  [& {:keys [score dimensions]
      :or {score 0.5
           dimensions [{:name "Grounding" :score 0.3
                        :feedback "Output contained hallucinated claims"}
                       {:name "Instruction Following" :score 0.9
                        :feedback "Good"}]}}]
  {:score score
   :dimensions dimensions})

;; =============================================================================
;; Read Model Helpers
;; =============================================================================

(defn get-concept-count
  "Get the number of concepts in the event store."
  [ctx]
  (count (rm/get-concept-map ctx)))

(defn get-tree-profile
  "Get a tree profile from the event store."
  [ctx tree-id]
  (rm/get-tree-profile ctx tree-id))

(defn get-node-learnings
  "Get node learnings for a specific type."
  [ctx node-type]
  (rm/get-node-type-learnings ctx node-type))

;; =============================================================================
;; Test Setup Helpers
;; =============================================================================

(defn setup-test-tree!
  "Set up a tree with strengths, weaknesses, and problem mapping.
   Returns the tree-id."
  [ctx & {:keys [strengths weaknesses mappings]
          :or {strengths 1 weaknesses 1 mappings 1}}]
  (let [tree-id (random-uuid)]
    ;; Add strengths
    (dotimes [_ strengths]
      (run-and-apply! ctx
                      (fn [c]
                        (cmd/ontology-record-tree-strength
                         (assoc c :command (make-tree-strength-data tree-id))))))
    ;; Add weaknesses
    (dotimes [_ weaknesses]
      (run-and-apply! ctx
                      (fn [c]
                        (cmd/ontology-record-tree-weakness
                         (assoc c :command (make-tree-weakness-data tree-id))))))
    ;; Add problem mappings
    (dotimes [_ mappings]
      (run-and-apply! ctx
                      (fn [c]
                        (cmd/ontology-record-problem-mapping
                         (assoc c :command (make-problem-mapping-data tree-id))))))
    tree-id))

(defn setup-multiple-trees!
  "Set up multiple trees for retrieval testing.
   Returns vector of tree-ids."
  [ctx n & {:keys [with-profiles?] :or {with-profiles? true}}]
  (vec (repeatedly n
                   #(if with-profiles?
                      (setup-test-tree! ctx)
                      (random-uuid)))))
