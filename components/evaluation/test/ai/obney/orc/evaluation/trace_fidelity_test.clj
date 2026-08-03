(ns ai.obney.orc.evaluation.trace-fidelity-test
  "Trace MEANING, not trace bytes.

   storage_budget_test guards the byte discipline: values are stored once and
   everything else references them by key. This file guards the other half —
   that the values rehydration returns are the ones that node actually saw.

   The general oracle is the trace's own stored profiles. :input-profile and
   :output-profile are recorded per execution at completion time; if a
   rehydrated value does not profile identically to what that execution
   recorded, the wrong value came back.

   That oracle is a LOWER bound: it cannot see a wrong value that happens to
   share a shape, which is the common case for map-each items. The map-each
   tests therefore assert on item IDENTITY instead."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.profile :as profile]
            [ai.obney.orc.evaluation.core.trace-extraction :as tx]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.event-store-v3.interface :as es]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Deterministic executors — every value a distinct, recognizable length
;; =============================================================================

(def ^:private v1 (apply str (repeat 100 "a")))   ; first :doc value
(def ^:private v2 (apply str (repeat 500 "b")))   ; second :doc value, written LATER

(defn seed-doc [_] {:doc v1})

(defn consume-doc
  "Reads :doc. Must see v1 — :doc is rewritten after this node finishes."
  [{:keys [inputs]}]
  {:consumed (str "saw-" (count (:doc inputs)))})

(defn rewrite-doc [_] {:doc v2})

(defn expand-item
  "map-each child: each iteration writes a value of a DIFFERENT length, so
   cross-iteration contamination changes the profile and is detectable."
  [{:keys [inputs]}]
  (let [i (:current-item inputs)]
    {:current-item (apply str (repeat (* 10 (inc i)) (char (+ 97 (mod i 26)))))}))

(defn echo-item
  "map-each child whose items are all the SAME SHAPE and differ only by value.
   Deliberately invisible to the profile oracle — {:type :map :length 2} either
   way — so only an identity check can tell iteration i's item from j's."
  [{:keys [inputs]}]
  (let [item (:current-item inputs)]
    {:current-item (assoc item :seen (:id item))}))

(defn read-enriched-item
  "Third sequence step: reads the item after echo-item rewrote it."
  [{:keys [inputs]}]
  (let [item (:current-item inputs)]
    {:noted {:from (:id item) :saw-enriched (contains? item :seen)}}))

(defn note-item
  "Grandchild that reads the item without writing it."
  [{:keys [inputs]}]
  {:noted {:from (:id (:current-item inputs))}})

(defn- fq [s] (str "ai.obney.orc.evaluation.trace-fidelity-test/" s))

;; =============================================================================
;; Fixture: one tick exercising all three failure modes at once
;; =============================================================================

(defn- setup!
  "sequence[ seed-doc, consume-doc, rewrite-doc, map-each(expand-item) ]

   Covers three shapes at once: a leaf with a declared read; a key written
   twice, the second write landing after the node that read it finished; and
   one child node-id executing several times under map-each."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Trace Fidelity"))
        sheet-id (-> sr :command-result/events first :sheet-id)
        add! (fn [parent idx fn-name reads writes]
               (let [r (h/run-and-apply! ctx (h/make-create-node-command
                                              sheet-id :leaf :parent-id parent :index idx))
                     nid (-> r :command-result/events first :node-id)]
                 (h/run-and-apply! ctx (h/make-set-node-executor-command
                                        sheet-id nid :code :fn (fq fn-name)))
                 (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id nid reads writes))
                 nid))]
    (doseq [[k s] [[:doc :string] [:consumed :string]
                   [:items [:vector :int]] [:current-item :any] [:results [:vector :any]]]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k s)))
    (let [seq-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-r :command-result/events first :node-id)
          seeder (add! seq-id 0 "seed-doc" [] [:doc])
          consumer (add! seq-id 1 "consume-doc" [:doc] [:consumed])
          rewriter (add! seq-id 2 "rewrite-doc" [] [:doc])
          me-r (h/run-and-apply! ctx (h/make-create-node-command
                                      sheet-id :map-each :parent-id seq-id :index 3))
          me-id (-> me-r :command-result/events first :node-id)
          child (add! me-id 0 "expand-item" [:current-item] [:current-item])]
      (h/run-and-apply! ctx (h/make-set-map-each-config-command
                             sheet-id me-id :items :current-item :results
                             :max-concurrency 2))
      {:sheet-id sheet-id :seeder seeder :consumer consumer
       :rewriter rewriter :map-each me-id :child child})))

(defn- setup-identity-map-each!
  "map-each over same-shape, distinct-value items at a racing concurrency.
   The profile oracle is blind here, so assertions are on item IDENTITY."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Item Identity"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (doseq [[k s] [[:items [:vector :any]] [:current-item :any] [:results [:vector :any]]]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k s)))
    (let [me-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
          me-id (-> me-r :command-result/events first :node-id)
          cr (h/run-and-apply! ctx (h/make-create-node-command
                                    sheet-id :leaf :parent-id me-id :index 0))
          child (-> cr :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id child :code :fn (fq "echo-item")))
      (h/run-and-apply! ctx (h/make-set-node-io-command
                             sheet-id child [:current-item] [:current-item]))
      (h/run-and-apply! ctx (h/make-set-map-each-config-command
                             sheet-id me-id :items :current-item :results
                             :max-concurrency 8))
      {:sheet-id sheet-id :map-each me-id :child child})))

(defn- setup-composite-map-each!
  "map-each whose child is a SEQUENCE, not a leaf.

   The node reading the item is then a DESCENDANT of the child the item write
   is stamped with, so a lookup keyed on node-id misses it. The iteration
   identity — (map-each parent, index) — is shared by the whole subtree and is
   what must be keyed on."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Composite MapEach"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (doseq [[k s] [[:items [:vector :any]] [:current-item :any]
                   [:noted :any] [:results [:vector :any]]]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k s)))
    (let [me-r (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
          me-id (-> me-r :command-result/events first :node-id)
          sq (h/run-and-apply! ctx (h/make-create-node-command
                                    sheet-id :sequence :parent-id me-id :index 0))
          sq-id (-> sq :command-result/events first :node-id)
          add! (fn [idx fn-name reads writes]
                 (let [r (h/run-and-apply! ctx (h/make-create-node-command
                                                sheet-id :leaf :parent-id sq-id :index idx))
                       nid (-> r :command-result/events first :node-id)]
                   (h/run-and-apply! ctx (h/make-set-node-executor-command
                                          sheet-id nid :code :fn (fq fn-name)))
                   (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id nid reads writes))
                   nid))
          ;; grandchild #1 reads the item; grandchild #2 reads it AGAIN after a
          ;; sibling has run, so it cannot rely on an :inputs overlay either.
          note (add! 0 "note-item" [:current-item] [:noted])
          echo (add! 1 "echo-item" [:current-item] [:current-item])
          ;; Reads the item AFTER step 1 rewrote it: one (key, iteration)
          ;; pair legitimately has several writes.
          after (add! 2 "read-enriched-item" [:current-item] [:noted])]
      (h/run-and-apply! ctx (h/make-set-map-each-config-command
                             sheet-id me-id :items :current-item :results
                             :max-concurrency 8))
      {:sheet-id sheet-id :map-each me-id :sequence sq-id
       :note note :echo echo :after after})))

(defn- setup-nested!
  "Parent sheet whose leaf delegates to a child sheet, so the child READS a key
   it was SEEDED with rather than one written inside its own tick."
  [ctx]
  (let [csr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Child Sheet"))
        child-sheet (-> csr :command-result/events first :sheet-id)]
    (doseq [k [:doc :consumed]]
      (h/run-and-apply! ctx (h/make-declare-key-command child-sheet k :string)))
    (let [cn (h/run-and-apply! ctx (h/make-create-node-command child-sheet :leaf))
          child-leaf (-> cn :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             child-sheet child-leaf :code :fn (fq "consume-doc")))
      (h/run-and-apply! ctx (h/make-set-node-io-command
                             child-sheet child-leaf [:doc] [:consumed]))
      (let [psr (h/run-and-apply! ctx (h/make-create-sheet-command :name "Parent Sheet"))
            parent-sheet (-> psr :command-result/events first :sheet-id)]
        (doseq [k [:doc :consumed]]
          (h/run-and-apply! ctx (h/make-declare-key-command parent-sheet k :string)))
        (let [sq (h/run-and-apply! ctx (h/make-create-node-command parent-sheet :sequence))
              sq-id (-> sq :command-result/events first :node-id)
              seeder (h/run-and-apply! ctx (h/make-create-node-command
                                            parent-sheet :leaf :parent-id sq-id :index 0))
              seeder-id (-> seeder :command-result/events first :node-id)
              dn (h/run-and-apply! ctx (h/make-create-node-command
                                        parent-sheet :delegate :parent-id sq-id :index 1))
              del-id (-> dn :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-node-executor-command
                                 parent-sheet seeder-id :code :fn (fq "seed-doc")))
          (h/run-and-apply! ctx (h/make-set-node-io-command parent-sheet seeder-id [] [:doc]))
          (h/run-and-apply! ctx (h/make-set-delegate-config-command
                                 parent-sheet del-id child-sheet
                                 :reads [:doc] :writes [:consumed]))
          (h/run-and-apply! ctx (h/make-set-node-io-command
                                 parent-sheet del-id [:doc] [:consumed]))
          {:parent-sheet parent-sheet :child-sheet child-sheet
           :child-leaf child-leaf :delegate del-id})))))

(defn- run!
  [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        res (cp/process-command
             (assoc ctx :command {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/tick-tree
                                  :sheet-id sheet-id :tick-id tick-id
                                  :inputs inputs :options {:timeout-ms 30000}}))]
    (is (not (:cognitect.anomalies/category res))
        (str "dispatch failed: " (:cognitect.anomalies/message res)))
    (let [result (deref p 30000 ::timeout)]
      (Thread/sleep 2000)  ;; trace is stored from a future
      [result tick-id])))

(defn- io-for
  "Rehydrated I/O for one node trace, keyed the way tick-node-io keys it."
  [io nt]
  (get io [(:node-id nt) (or (:exec-context nt) {})]))

;; =============================================================================
;; The general invariant — catches F1, F2, F3 and anything of that shape
;; =============================================================================

(deftest rehydrated-io-matches-each-executions-own-profiles
  (testing "every node trace's rehydrated values profile identically to what that execution recorded"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup! ctx)
            [result trace-id] (run! ctx sheet-id {:items [0 1 2 3]})
            trace (rm/get-trace ctx trace-id)
            io (tx/tick-node-io ctx trace-id (:node-traces trace))]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (is (some? trace) "trace was stored")
        (is (seq (:node-traces trace)) "trace has node entries")

        (doseq [nt (:node-traces trace)
                :let [rehydrated (io-for io nt)
                      label (str (:node-name nt) " " (:node-id nt)
                                 " ctx=" (pr-str (:exec-context nt)))]]
          (when (seq (:read-keys nt))
            (is (some? rehydrated) (str "no rehydrated I/O at all for " label))
            (is (= (:input-profile nt) (profile/profile-values (:inputs rehydrated)))
                (str "rehydrated INPUTS disagree with this execution's own "
                     ":input-profile for " label
                     " — got " (pr-str (profile/profile-values (:inputs rehydrated)))
                     ", recorded " (pr-str (:input-profile nt)))))
          (when (seq (:write-keys nt))
            (is (some? rehydrated) (str "no rehydrated I/O at all for " label))
            (is (= (:output-profile nt) (profile/profile-values (:outputs rehydrated)))
                (str "rehydrated OUTPUTS disagree with this execution's own "
                     ":output-profile for " label
                     " — got " (pr-str (profile/profile-values (:outputs rehydrated)))
                     ", recorded " (pr-str (:output-profile nt))))))))))

;; =============================================================================
;; The three defects, named — so a regression says WHICH one came back
;; =============================================================================

(deftest f1-leaf-reads-are-captured
  (testing "a leaf with declared reads records :read-keys and rehydrates real values"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id consumer]} (setup! ctx)
            [_ trace-id] (run! ctx sheet-id {:items [0 1]})
            trace (rm/get-trace ctx trace-id)
            nt (first (filter #(= consumer (:node-id %)) (:node-traces trace)))
            io (tx/tick-node-io ctx trace-id (:node-traces trace))]
        (is (some? nt) "the consuming leaf has a node trace")
        (is (= [:doc] (:read-keys nt))
            "leaf records the key it read — without this, judges ground on {}")
        (is (seq (:input-profile nt)) "and the shape of what it read")
        (is (= {:doc v1} (:inputs (io-for io nt)))
            "and the value rehydrates")))))

(deftest f2-reads-resolve-to-the-write-the-node-actually-saw
  (testing "a key rewritten later in the tick does not leak backwards into an earlier reader"
    ;; consume-doc reads :doc (v1). rewrite-doc writes :doc again (v2) AFTER
    ;; consume-doc finished, so resolving by key name alone yields a value that
    ;; did not exist when the node ran.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id consumer]} (setup! ctx)
            [result trace-id] (run! ctx sheet-id {:items [0 1]})
            trace (rm/get-trace ctx trace-id)
            nt (first (filter #(= consumer (:node-id %)) (:node-traces trace)))
            io (tx/tick-node-io ctx trace-id (:node-traces trace))
            got (:doc (:inputs (io-for io nt)))]
        (is (= "saw-100" (:consumed (:outputs result)))
            "sanity: the node really did execute against v1")
        (is (= v1 got)
            (str "read resolved to the wrong write: expected the 100-char value "
                 "this node saw, got " (count (str got)) " chars"))
        (is (not= v2 got)
            "read leaked the LATER write, which happened after this node finished")))))

(deftest f2a-every-read-key-resolves-to-something
  (testing "no declared read is silently dropped during rehydration"
    ;; Needs no ground truth: only that a key a node recorded reading comes
    ;; back with SOME value. A resolver whose lookup steps are terminal rather
    ;; than falling through drops keys silently.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup! ctx)
            [_ trace-id] (run! ctx sheet-id {:items [0 1 2 3]})
            trace (rm/get-trace ctx trace-id)
            io (tx/tick-node-io ctx trace-id (:node-traces trace))
            unresolved (for [nt (:node-traces trace)
                             :let [got (:inputs (io-for io nt))]
                             k (:read-keys nt)
                             :when (not (contains? got k))]
                         [(:node-name nt) k])]
        (is (empty? unresolved)
            (str "read keys that failed to rehydrate: " (vec unresolved)))))))

(deftest f2a-nested-tick-seeded-reads-resolve
  (testing "a child tick reading a key it was SEEDED with rehydrates that key"
    ;; The value was written in the PARENT tick, so it arrives via the child's
    ;; tree-tick-started :inputs rather than its own write log. If those keys
    ;; are stored in a different form than the node declares in :reads —
    ;; strings vs keywords — the read is silently dropped.
    (h/with-async-test-context [ctx]
      (let [{:keys [parent-sheet child-sheet child-leaf]} (setup-nested! ctx)
            [result _] (run! ctx parent-sheet {})]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (is (= "saw-100" (:consumed (:outputs result)))
            "sanity: the delegated child really did read the seeded doc")
        ;; Find the CHILD tick's trace and assert its leaf's read resolved.
        (let [child-traces (filter #(= child-sheet (:sheet-id %))
                                   (rm/get-traces-for-sheet ctx child-sheet))
              tr (first child-traces)]
          (is (some? tr) "the delegated child tick produced its own trace")
          (let [io (tx/tick-node-io ctx (:trace-id tr) (:node-traces tr))
                nt (first (filter #(= child-leaf (:node-id %)) (:node-traces tr)))]
            (is (some? nt) "the child's leaf has a node trace")
            (is (= [:doc] (:read-keys nt)) "it recorded the seeded key it read")
            (is (= v1 (:doc (:inputs (io-for io nt))))
                "the seeded value rehydrates — a string/keyword mismatch drops it")))))))

(deftest f2b-map-each-items-resolve-to-their-own-iteration
  (testing "each iteration rehydrates the item it actually ran on, by identity"
    ;; Built so the profile oracle cannot see the error: every item profiles
    ;; as {:type :map :length 2}, so a cross-iteration mix-up is invisible to
    ;; shape equality and only an identity check catches it.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id child]} (setup-identity-map-each! ctx)
            items (mapv (fn [i] {:id i :tag (str "item-" i)}) (range 12))
            [result trace-id] (run! ctx sheet-id {:items items})
            trace (rm/get-trace ctx trace-id)
            iters (filter #(= child (:node-id %)) (:node-traces trace))
            io (tx/tick-node-io ctx trace-id (:node-traces trace))]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (is (= 12 (count iters)) "one node trace per iteration")
        ;; Each iteration's rehydrated INPUT must be the item whose index
        ;; matches that iteration's map-each index.
        (let [wrong (for [nt iters
                          :let [idx (get (:exec-context nt)
                                         :ai.obney.orc.orc-service.core.todo-processors/map-each-index)
                                got (:current-item (:inputs (io-for io nt)))]
                          :when (not= idx (:id got))]
                      {:iteration idx :resolved-to (:id got)})]
          (is (empty? wrong)
              (str "iterations served another iteration's item: " (vec wrong)
                   " — the profile oracle cannot see this, every item is the same shape")))
        ;; And the outputs stay distinct, by identity rather than by length.
        (let [seen (map #(:seen (:current-item (:outputs (io-for io %)))) iters)]
          (is (= (set (range 12)) (set seen))
              (str "each iteration's output should carry its own :seen id; got " (vec seen))))))))

(deftest f3-map-each-iterations-keep-their-own-io
  (testing "N iterations of one child node-id each get their own inputs and outputs"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id child]} (setup! ctx)
            [result trace-id] (run! ctx sheet-id {:items [0 1 2 3]})
            trace (rm/get-trace ctx trace-id)
            iters (filter #(= child (:node-id %)) (:node-traces trace))
            io (tx/tick-node-io ctx trace-id (:node-traces trace))
            outs (map #(:current-item (:outputs (io-for io %))) iters)]
        (is (= :success (:status result)))
        (is (= 4 (count iters)) "one node trace per iteration")
        (is (= 4 (count (distinct (map :exec-context iters))))
            "each iteration is addressable — distinct exec-context")
        (is (= 4 (count (distinct outs)))
            (str "iterations were served each other's outputs; got "
                 (mapv #(count (str %)) outs) " distinct=" (count (distinct outs))))
        (is (= [10 20 30 40] (sort (map #(count (str %)) outs)))
            "each iteration's output is its own, by length")))))

;; =============================================================================
;; The identity oracle — what shape equality structurally cannot see
;; =============================================================================

(deftest f2b-composite-child-descendants-resolve-their-own-item
  (testing "a map-each child that is a SEQUENCE: its grandchildren each resolve
            the item for THEIR iteration, not another's"
    ;; When the map-each child is a composite, the node reading the item is a
    ;; descendant of the node the item write is stamped with, so a lookup keyed
    ;; on node-id misses and falls back to the shared blackboard slot that
    ;; concurrent iterations clobber.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id note echo]} (setup-composite-map-each! ctx)
            items (mapv (fn [i] {:id i :tag (str "item-" i)}) (range 12))
            [result trace-id] (run! ctx sheet-id {:items items})
            trace (rm/get-trace ctx trace-id)
            io (tx/tick-node-io ctx trace-id (:node-traces trace))
            idx-of #(get (:exec-context %)
                         :ai.obney.orc.orc-service.core.todo-processors/map-each-index)]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (doseq [[label node-id] [["grandchild reading only" note]
                                 ["grandchild reading after a sibling ran" echo]]]
          (let [nts (filter #(= node-id (:node-id %)) (:node-traces trace))
                wrong (for [nt nts
                            :let [got (:current-item (:inputs (io-for io nt)))]
                            :when (not= (idx-of nt) (:id got))]
                        {:iteration (idx-of nt) :resolved-to (:id got)})]
            (is (= 12 (count nts)) (str label ": one trace per iteration"))
            (is (empty? wrong)
                (str label " served another iteration's item: " (vec wrong)))))))))

(deftest f2b-read-sources-never-name-another-iterations-write
  (testing "every :read-sources entry resolves to a write stamped with the
            reader's own map-each index"
    ;; No fixture ground truth needed — runs against any real trace. A
    ;; :read-sources entry captured from the shared blackboard slot names an
    ;; arbitrary iteration's write; shape equality reports nothing on that.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-composite-map-each! ctx)
            items (mapv (fn [i] {:id i :tag (str "item-" i)}) (range 12))
            [_ trace-id] (run! ctx sheet-id {:items items})
            events (into [] (es/read (:event-store ctx)
                                     {:tags #{[:tick trace-id]}
                                      :tenant-id (:tenant-id ctx)}))
            by-id (into {} (for [e events
                                 :when (= :sheet/execution-value-written (:event/type e))]
                             [(:event/id e) e]))
            mei :ai.obney.orc.orc-service.core.todo-processors/map-each-index
            offenders (for [c events
                            :when (= :sheet/node-execution-completed (:event/type c))
                            :let [reader-idx (get (:inputs c) mei)]
                            :when (some? reader-idx)
                            [k src] (:read-sources c)
                            :let [w (get by-id src)
                                  w-idx (get (:exec-context w) mei)]
                            :when (and (some? w-idx) (not= reader-idx w-idx))]
                        {:key k :reader-index reader-idx :write-index w-idx})]
        (is (empty? offenders)
            (str ":read-sources naming another iteration's write: " (vec offenders)))))))

;; =============================================================================
;; The PUBLIC path — every check above passes with get-llm-traces returning []
;; =============================================================================

(defn mock-ai-predict
  "dscloj/predict stub so the :ai leaf resolves offline and deterministically."
  [_provider _module _inputs _opts]
  {:outputs {:answer "answered-from-the-document"}
   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})

(defn- setup-ai-sheet!
  "A sheet with a leaf whose executor is :ai, carrying an instruction and a
   model — the shape an evaluation consumer expects to find."
  [ctx]
  (let [sr (h/run-and-apply! ctx (h/make-create-sheet-command :name "AI Sheet"))
        sheet-id (-> sr :command-result/events first :sheet-id)]
    (doseq [k [:doc :answer]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [sq (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          sq-id (-> sq :command-result/events first :node-id)
          seeder (h/run-and-apply! ctx (h/make-create-node-command
                                        sheet-id :leaf :parent-id sq-id :index 0))
          seeder-id (-> seeder :command-result/events first :node-id)
          ai (h/run-and-apply! ctx (h/make-create-node-command
                                    sheet-id :leaf :parent-id sq-id :index 1))
          ai-id (-> ai :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id seeder-id :code :fn (fq "seed-doc")))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id seeder-id [] [:doc]))
      ;; The node under test: executor :ai, with instruction + model.
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id ai-id :ai :model "test/model"))
      (h/run-and-apply! ctx (h/make-set-node-instruction-command
                             sheet-id ai-id "Answer using the document."))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id ai-id [:doc] [:answer]))
      {:sheet-id sheet-id :ai-node ai-id})))

(deftest f4-get-llm-traces-returns-populated-rows
  (testing "the documented evaluation entry point returns LLM rows, populated"
    ;; Every other assertion in this file calls tick-node-io directly, so all
    ;; of them pass even when the public API returns [] for every sheet. This
    ;; asserts the path the docs point consumers at.
    (h/with-async-test-context [ctx]
      ;; The leaf's executor really is :ai — that is what get-llm-traces
      ;; filters on — so the model call is stubbed at the root var. Leaf
      ;; execution happens on a processor thread, so a dynamic `binding`
      ;; would not reach it; with-redefs alters the root and does.
      (let [{:keys [sheet-id ai-node]} (setup-ai-sheet! ctx)
            [result _] (with-redefs [dscloj/predict mock-ai-predict]
                         (run! ctx sheet-id {}))]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (let [rows (tx/get-llm-traces ctx {:sheet-id sheet-id})]
          (is (seq rows)
              "get-llm-traces returned no rows — the LLM filter is not matching")
          (let [row (first (filter #(= ai-node (:node-id %)) rows))]
            (is (some? row) "the :ai leaf appears in the results")
            (testing "and its row is populated, not a shell"
              (is (seq (:inputs row)) ":inputs rehydrated")
              (is (seq (:outputs row)) ":outputs rehydrated")
              (is (= v1 (:doc (:inputs row))) "the real read value came back")
              (is (= "answered-from-the-document" (:answer (:outputs row)))
                  "the model's output came back")
              (is (seq (:instruction row)) ":instruction resolved from the sheet")
              (is (some? (:model row)) ":model resolved from the sheet"))))))))

(deftest f4-llm-filter-uses-both-axes
  (testing "node-type and executor are separate axes and both select LLM nodes"
    (let [pred #'tx/is-llm-node?]
      (testing "executor axis"
        (is (pred {:node-type :leaf :executor :ai}) ":ai leaf is an LLM node")
        (is (not (pred {:node-type :leaf :executor :code})) ":code leaf is not")
        (is (not (pred {:node-type :leaf :executor :tool})) ":tool leaf is not"))
      (testing "node-type axis"
        (is (pred {:node-type :repl-researcher}))
        (is (pred {:node-type :llm-condition}))
        (is (not (pred {:node-type :sequence})))
        (is (not (pred {:node-type :map-each}))))
      (testing "falls back to node metadata when the trace predates :executor"
        (is (pred {:node-type :leaf} {:executor :ai}))
        (is (not (pred {:node-type :leaf} {:executor :code})))))))


;; No assertion here that rehydration equals what a step ACTUALLY read.
;; map-each with a composite body at concurrency > 1 races on the shared item
;; key: steps after the first resolve the item from the blackboard and can
;; observe another iteration's value (F5 in docs/trace-fidelity-assessment.md,
;; pre-existing). While that holds no per-iteration rule can be faithful — the
;; value read belonged to a different iteration — so such an assertion would
;; encode the idealization as the contract.
;; f2b-composite-child-descendants-resolve-their-own-item covers what is
;; well-defined: per-iteration resolution.

(deftest f4-get-llm-traces-returns-populated-rows
  (testing "the documented evaluation entry point returns LLM rows, populated"
    ;; Every other assertion in this file calls tick-node-io directly, so all
    ;; of them pass even when the public API returns [] for every sheet. This
    ;; asserts the path the docs point consumers at.
    (h/with-async-test-context [ctx]
      ;; The leaf's executor really is :ai — that is what get-llm-traces
      ;; filters on — so the model call is stubbed at the root var. Leaf
      ;; execution happens on a processor thread, so a dynamic `binding`
      ;; would not reach it; with-redefs alters the root and does.
      (let [{:keys [sheet-id ai-node]} (setup-ai-sheet! ctx)
            [result _] (with-redefs [dscloj/predict mock-ai-predict]
                         (run! ctx sheet-id {}))]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (let [rows (tx/get-llm-traces ctx {:sheet-id sheet-id})]
          (is (seq rows)
              "get-llm-traces returned no rows — the LLM filter is not matching")
          (let [row (first (filter #(= ai-node (:node-id %)) rows))]
            (is (some? row) "the :ai leaf appears in the results")
            (testing "and its row is populated, not a shell"
              (is (seq (:inputs row)) ":inputs rehydrated")
              (is (seq (:outputs row)) ":outputs rehydrated")
              (is (= v1 (:doc (:inputs row))) "the real read value came back")
              (is (= "answered-from-the-document" (:answer (:outputs row)))
                  "the model's output came back")
              (is (seq (:instruction row)) ":instruction resolved from the sheet")
              (is (some? (:model row)) ":model resolved from the sheet"))))))))

(deftest f4-llm-filter-uses-both-axes
  (testing "node-type and executor are separate axes and both select LLM nodes"
    (let [pred #'tx/is-llm-node?]
      (testing "executor axis"
        (is (pred {:node-type :leaf :executor :ai}) ":ai leaf is an LLM node")
        (is (not (pred {:node-type :leaf :executor :code})) ":code leaf is not")
        (is (not (pred {:node-type :leaf :executor :tool})) ":tool leaf is not"))
      (testing "node-type axis"
        (is (pred {:node-type :repl-researcher}))
        (is (pred {:node-type :llm-condition}))
        (is (not (pred {:node-type :sequence})))
        (is (not (pred {:node-type :map-each}))))
      (testing "falls back to node metadata when the trace predates :executor"
        (is (pred {:node-type :leaf} {:executor :ai}))
        (is (not (pred {:node-type :leaf} {:executor :code})))))))


;; NOTE — the assertion "rehydration equals what the step ACTUALLY read" is
;; deliberately absent, and its absence is load-bearing.
;;
;; F5 (docs/trace-fidelity-assessment.md): map-each with a COMPOSITE body at
;; concurrency > 1 races on the shared item key — sequence steps after the
;; first resolve the item from the blackboard and can observe another
;; iteration's value. Verified at concurrency 4 and reproduced identically on
;; a8fb2c0, so it predates the storage work.
;;
;; While that race exists no per-iteration rehydration rule can be faithful:
;; the value the step read belonged to a different iteration, so resolving by
;; (key, iteration) necessarily reports the idealized value instead. Writing
;; an assertion that passes today would encode the idealization as the
;; contract. f2b-composite-child-descendants-resolve-their-own-item covers
;; what IS well-defined — per-iteration resolution — and this note records
;; what is blocked on F5.
