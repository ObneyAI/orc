(ns ai.obney.orc.orc-service.storage-budget-test
  "Storage-cost invariants for the event log.

   :sheet/execution-value-written is the canonical record of every blackboard
   write; every other event references values by key. These tests encode that
   discipline as executable invariants so it cannot silently regress.

     amplification = total-stored-bytes / canonical-payload-bytes

   Amplification is the metric of record because it is scale-free: growing
   the payload grows the canonical term too, so a fixed byte threshold would
   be vacuous on small fixtures and fail spuriously on large ones. A log that
   stores each value once scores ~1.0.

   Everything here runs on deterministic :code executors and the mock LLM
   provider — no network, byte-reproducible across runs.

   BUDGETS below are ceilings, not targets."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Budgets
;; =============================================================================

(def ^:private budgets
  "Ceilings measured against the fixtures below — ceilings, not targets.

     :amplification        total bytes / canonical (execution-value-written) bytes
     :started-scaling      node-execution-started byte growth when payload grows 10x
     :doc-occurrences      how many distinct events embed the producer's payload
     :seed-amplification   total bytes / size of a doc seeded into a child tick
                           and never read or written by it
     :duplication-ratio    fraction of large-payload bytes that are a second
                           copy of something already stored; 0.0 means every
                           value is stored exactly once
     :map-each-duplication map-each has a structural floor near 0.5 — an item's
                           result is stored under the shared item key AND inside
                           the vector map-each collects into, and both are real
                           blackboard values. This budget catches duplication
                           ABOVE that floor."
  {:amplification        2.85
   :started-scaling      1.05
   :doc-occurrences         1
   :seed-amplification    1.1
   :duplication-ratio    0.01
   :map-each-duplication 0.55})

;; =============================================================================
;; Deterministic executors
;; =============================================================================

(def ^:private payload-unit
  "One unit of synthetic payload, distinctive so occurrence counting is
   unambiguous."
  (apply str (repeat 1000 "abcdefghij")))

(defn produce-doc
  "Leaf executor: emits a payload sized by the :scale input."
  [{:keys [inputs]}]
  {:doc (apply str (repeat (or (:scale inputs) 1) payload-unit))})

(defn summarize-a [{:keys [inputs]}] {:out-a (str "A:" (count (:doc inputs)))})
(defn summarize-b [{:keys [inputs]}] {:out-b (str "B:" (count (:doc inputs)))})
(defn summarize-c [{:keys [inputs]}] {:out-c (str "C:" (count (:doc inputs)))})

(defn double-item [{:keys [inputs]}]
  (let [item (:current-item inputs)]
    {:current-item (update item :value * 2)}))

(defn expand-item
  "map-each child: turns each item into a large distinct value, big enough to
   dominate the byte accounting."
  [{:keys [inputs]}]
  (let [i (:current-item inputs)]
    {:current-item (str "ITEM-" i "-" (apply str (repeat 200 (char (+ 97 (mod i 26))))))}))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- fq [sym] (str "ai.obney.orc.orc-service.storage-budget-test/" sym))

(defn- setup-fanout-sheet!
  "producer writes :doc; three consumers each read :doc. One large value with
   M readers, so any redundant inlining of resolved reads shows up as an O(M)
   term."
  [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Fanout"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:scale :doc :out-a :out-b :out-c]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k
                                                        (if (= k :scale) :int :string))))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          ;; Explicit :index — without it children execute in reverse
          ;; creation order, which would run the consumers before the
          ;; producer and leave :doc nil.
          add-leaf! (fn [idx fn-sym reads writes]
                      (let [r (h/run-and-apply! ctx (h/make-create-node-command
                                                     sheet-id :leaf :parent-id seq-id
                                                     :index idx))
                            leaf-id (-> r :command-result/events first :node-id)]
                        (h/run-and-apply! ctx (h/make-set-node-executor-command
                                               sheet-id leaf-id :code :fn (fq fn-sym)))
                        (h/run-and-apply! ctx (h/make-set-node-io-command
                                               sheet-id leaf-id reads writes))
                        leaf-id))]
      {:sheet-id sheet-id
       :producer (add-leaf! 0 "produce-doc" [:scale] [:doc])
       :consumers [(add-leaf! 1 "summarize-a" [:doc] [:out-a])
                   (add-leaf! 2 "summarize-b" [:doc] [:out-b])
                   (add-leaf! 3 "summarize-c" [:doc] [:out-c])]})))

(defn- setup-map-each-sheet!
  "map-each over :items with concurrency, each child producing a large
   distinct value."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "MapEach"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :int]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :any))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :any]))
    (let [mr (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
          me-id (-> mr :command-result/events first :node-id)
          cr (h/run-and-apply! ctx (h/make-create-node-command
                                    sheet-id :leaf :parent-id me-id :index 0))
          child-id (-> cr :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id child-id :code :fn (fq "expand-item")))
      (h/run-and-apply! ctx (h/make-set-node-io-command
                             sheet-id child-id [:current-item] [:current-item]))
      (h/run-and-apply! ctx (h/make-set-map-each-config-command
                             sheet-id me-id :items :current-item :results
                             :max-concurrency 4))
      {:sheet-id sheet-id :map-each me-id :child child-id})))

(defn- dispatch!
  "Run a tick to completion and return [result tick-id]."
  [ctx sheet-id inputs & {:keys [timeout-ms] :or {timeout-ms 20000}}]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        cmd-result (cp/process-command
                    (assoc ctx :command
                           {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :sheet/tick-tree
                            :sheet-id sheet-id
                            :tick-id tick-id
                            :inputs inputs
                            :options {:timeout-ms timeout-ms}}))]
    (is (not (:cognitect.anomalies/category cmd-result))
        (str "tick-tree dispatch failed: " (:cognitect.anomalies/message cmd-result)))
    [(deref p timeout-ms ::timeout) tick-id]))

(defn- settle!
  "Trace assembly and other completion processors run in futures off the
   pubsub thread, so give them a moment to append before accounting."
  []
  (Thread/sleep 1500))

(defn- amplification
  "total stored bytes / canonical (execution-value-written) bytes."
  [report]
  (let [canonical (h/bytes-for-type report :sheet/execution-value-written)]
    (is (pos? canonical) "canonical execution-value-written bytes must be non-zero")
    (double (/ (:total-bytes report) (max canonical 1)))))

(defn- occurrences-of
  "How many events contain the given payload string anywhere in them."
  [events needle]
  (count (filter #(clojure.string/includes? (pr-str %) needle) events)))

;; =============================================================================
;; Baseline report — prints the whole-run accounting
;; =============================================================================

(deftest storage-baseline-report
  (testing "whole-run byte accounting is measurable and non-trivial"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:scale 1})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (settle!)
        (let [report (h/run-byte-report ctx)]
          (println)
          (println "=== storage baseline: fan-out fixture, 1 unit payload ===")
          (println (h/format-byte-report report))
          (println (format "amplification: %.2fx"
                           (amplification report)))
          (println (format "duplication:   %s"
                           (h/payload-duplication-report (h/read-all-events ctx))))
          (is (pos? (:total-bytes report)))
          (is (pos? (:event-count report))))))))

;; =============================================================================
;; Invariant: amplification
;; =============================================================================

(deftest amplification-within-budget
  (testing "the log does not store each value an unbounded number of times"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:scale 4})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (settle!)
        (let [report (h/run-byte-report ctx)
              amp (amplification report)]
          (println (format "[amplification] %.2fx (budget %.2fx)"
                           amp (:amplification budgets)))
          (is (<= amp (:amplification budgets))
              (str "amplification " (format "%.2f" amp)
                   " exceeds budget " (:amplification budgets)
                   ". Per-type breakdown:\n"
                   (h/format-byte-report report))))))))

;; =============================================================================
;; Invariant: lifecycle events must not scale with payload
;; =============================================================================

(deftest node-execution-started-does-not-scale-with-payload
  ;; The sharpest invariant in this file. node-execution-started must carry
  ;; execution context and genuine overrides ONLY — never resolved reads.
  ;; A leaf resolves its own reads from the tick blackboard at execution
  ;; time (grain read models are read-your-own-write), so inlining them here
  ;; stored one extra copy of every value per reader.
  (testing "node-execution-started size is independent of payload size"
    (h/with-async-test-context [ctx-small]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx-small)
            [r1 _] (dispatch! ctx-small sheet-id {:scale 1})]
        (is (= :success (:status r1)))
        (settle!)
        (let [small (h/bytes-for-type (h/run-byte-report ctx-small)
                                      :sheet/node-execution-started)]
          (h/with-async-test-context [ctx-large]
            (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx-large)
                  [r2 _] (dispatch! ctx-large sheet-id {:scale 10})]
              (is (= :success (:status r2)))
              (settle!)
              (let [large (h/bytes-for-type (h/run-byte-report ctx-large)
                                            :sheet/node-execution-started)
                    ratio (double (/ large (max small 1)))]
                (println (format "[started-scaling] %d -> %d bytes = %.2fx (budget %.2fx)"
                                 small large ratio (:started-scaling budgets)))
                (is (<= ratio (:started-scaling budgets))
                    (str "node-execution-started grew " (format "%.2f" ratio)
                         "x for a 10x payload; it should be independent of "
                         "payload size once resolved reads are dropped"))))))))))

;; =============================================================================
;; Invariant: capturing a node's reads must not re-inline their values
;; =============================================================================

(deftest completion-events-carry-read-shape-not-read-values
  (testing "a leaf's reads reach the trace as keys + profile, never as values"
    ;; A leaf's reads ARE captured on the completion command (without it,
    ;; grounding judges score against {}), but complete-node-execution reduces
    ;; them to :read-keys + :input-profile before they reach the event. This
    ;; asserts the reduction actually happens — the failure mode it guards is
    ;; re-inlining large read values and quietly undoing the storage win.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:scale 4})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (settle!)
        (let [completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                  (h/read-all-events ctx))
              readers (filter #(seq (:read-keys %)) completions)]
          (is (seq readers) "sanity: some leaf recorded the keys it read")
          (doseq [c readers]
            (is (seq (:input-profile c))
                (str "read keys without a profile on node " (:node-id c)))
            ;; :inputs may carry namespaced execution-context keys only.
            (let [simple (filter #(and (keyword? %) (nil? (namespace %)))
                                 (keys (:inputs c)))]
              (is (empty? simple)
                  (str "completion event re-inlined read VALUES under " (vec simple)
                       " — reads must reach the event as :read-keys + :input-profile"))))
          ;; And the whole-run budget still holds with reads captured.
          (let [dup (h/payload-duplication-report (h/read-all-events ctx))]
            (is (<= (:ratio dup) (:duplication-ratio budgets))
                (str "capturing reads reintroduced duplication: " dup))))))))

;; =============================================================================
;; Invariant: no value is stored twice
;; =============================================================================

(deftest no-duplicate-payload-bytes
  (testing "every large value is stored exactly once across the whole log"
    ;; The strongest form of the copy-count discipline. :sheet/execution-
    ;; value-written is the canonical record of a write; every other event
    ;; references values by key and resolves them through core/value-log.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:scale 3})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (settle!)
        (let [dup (h/payload-duplication-report (h/read-all-events ctx))]
          (println (format "[duplication] %.2f (budget %.2f) — %d occurrences of %d distinct"
                           (:ratio dup) (:duplication-ratio budgets)
                           (:occurrences dup) (:distinct dup)))
          (is (<= (:ratio dup) (:duplication-ratio budgets))
              (str "duplicate payload bytes: " dup)))))))

(deftest map-each-duplication-is-aggregation-only
  (testing "map-each duplication is the collected vector, and nothing more"
    ;; map-each is the one shape where duplication is INHERENT rather than
    ;; incidental: each iteration's result is stored under the shared item
    ;; key, and again inside the vector the map-each collects into :results.
    ;; Both are genuine blackboard values, so neither can be dropped — the
    ;; aggregate IS the node's output.
    ;;
    ;; That puts a floor of ~0.5 on the duplication ratio here. This test
    ;; pins the floor so that any duplication ABOVE it — which would be
    ;; incidental, and removable — shows up as a failure.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-map-each-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:items (vec (range 8))})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (settle!)
        (let [results (:results (:outputs result))
              dup (h/payload-duplication-report (h/read-all-events ctx) :min-chars 100)]
          (testing "per-iteration attribution is exact under concurrency"
            (is (= 8 (count results)))
            (is (= 8 (count (distinct results))) "no cross-iteration contamination")
            (is (= (mapv #(str "ITEM-" %) (range 8))
                   (mapv #(subs (str %) 0 6) results))
                "results align with their source items, in order"))
          (println (format "[map-each duplication] %.2f — %d occurrences of %d distinct"
                           (:ratio dup) (:occurrences dup) (:distinct dup)))
          (is (<= (:ratio dup) (:map-each-duplication budgets))
              (str "duplication beyond the aggregation floor: " dup)))))))

;; =============================================================================
;; Invariant: copy count of a single payload
;; =============================================================================

(deftest payload-copy-count
  (testing "a produced value is embedded in a bounded number of events"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-fanout-sheet! ctx)
            [result _] (dispatch! ctx sheet-id {:scale 1})]
        (is (= :success (:status result)))
        (settle!)
        (let [events (h/read-all-events ctx)
              ;; A distinctive slice of the payload: long enough to be
              ;; unique, short enough to survive any truncation.
              needle (subs payload-unit 0 200)
              n (occurrences-of events needle)]
          (println (format "[copy-count] payload appears in %d events (budget %d)"
                           n (:doc-occurrences budgets)))
          (is (<= n (:doc-occurrences budgets))
              (str "payload embedded in " n " events; the canonical record is "
                   ":sheet/execution-value-written and everything else should "
                   "reference it")))))))

;; =============================================================================
;; Nested-tick round trip (RLM Phase 2 shape, mock provider)
;; =============================================================================

(deftest nested-tick-seed-round-trip
  (testing "a document seeded into a child tick and never touched is not
            re-stored on the way out"
    ;; This is the RLM Phase 2 shape. execute-tree seeds the child tick with
    ;; (merge blackboard sandbox-vars) — the whole parent working set — and
    ;; tree-tick-completed currently dumps the whole child blackboard back
    ;; out. A document that is never read and never written therefore gets
    ;; stored on the way in, on the way out, and twice more inside the
    ;; trace's input/output snapshots.
    (binding [tp-core/*default-dscloj-provider* nil]
      (h/with-async-test-context [ctx]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl [:sequence [:final {:keys [:summary]}]])
              big (apply str (repeat 20 payload-unit))
              doc-bytes (count (.getBytes ^String big "UTF-8"))
              result (tree-executor/execute-tree
                      tree ctx
                      {:blackboard {:source-document big}
                       :sandbox-vars {:summary "short summary"}
                       :timeout-ms 20000})]
          (is (= :success (:status result))
              (str "tree execution failed: " (:error result)))
          (settle!)
          (let [events (h/read-all-events ctx)
                needle (subs payload-unit 0 200)
                n (occurrences-of events needle)
                report (h/run-byte-report ctx)
                seed-amp (double (/ (:total-bytes report) doc-bytes))]
            (println (format "[seed-round-trip] doc in %d events, %.2fx amplification (budget %.2fx)"
                             n seed-amp (:seed-amplification budgets)))
            (is (<= seed-amp (:seed-amplification budgets))
                (str "a seeded-but-untouched document cost "
                     (format "%.2f" seed-amp) "x its own size to store:\n"
                     (h/format-byte-report report)))))))))
