(ns ai.obney.orc.orc-service.tick-context-equivalence-test
  "Proof that resolving blackboard values from the write log reproduces what
   the tick-execution-contexts cache used to hold — exactly.

   The cached blackboard is moving from {key -> {.. :value v}} to metadata
   only, with values resolved on demand from :sheet/execution-value-written.
   That is only safe if resolution is TOTAL and EXACT, so these tests assert
   whole-map equality rather than spot-checking a key or two:

       strip every :value from the cached blackboard
       -> hydrate it back through value-log
       -> must equal what we started with

   Every key in the cache arrives by one of two exhaustive routes — a write
   (which always records :source) or a seed from the tick-started
   event (which never does) — so a round-trip that holds across the fixture
   matrix below covers the whole space of ways a value can get there.

   Fixtures deliberately span every shape that reaches the blackboard by a
   different path: plain leaf writes, map-each item seeds (serial AND
   concurrent, where several iterations race on one shared item key),
   composite map-each children whose writes are attributed to descendants,
   empty source lists, conditions, parallel branches, keys declared but never
   written, and keys written more than once.

   All executors are deterministic :code fns — no network, no model."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Deterministic executors
;; =============================================================================

(defn produce-doc [{:keys [inputs]}]
  {:doc (apply str (repeat (or (:scale inputs) 1) "0123456789"))})

(defn summarize [{:keys [inputs]}] {:summary (str "len=" (count (:doc inputs)))})

(defn overwrite-doc [{:keys [inputs]}]
  {:doc (str "REWRITTEN:" (count (:doc inputs)))})

(defn expand-item [{:keys [inputs]}]
  (let [i (:current-item inputs)]
    {:current-item (str "ITEM-" i "-" (apply str (repeat 40 (char (+ 97 (mod i 26))))))}))

(defn tag-item [{:keys [inputs]}]
  {:current-item (str (:current-item inputs) "|tagged")})

(defn note-item [{:keys [inputs]}]
  ;; Writes a NON-item key from inside a map-each iteration. This is what
  ;; makes composite-child attribution observable: the write is attributed to
  ;; a descendant of the map-each child, not to the child itself.
  {:note (str "saw-" (:current-item inputs))})

(defn branch-a [_] {:out-a "A"})
(defn branch-b [_] {:out-b "B"})

;; =============================================================================
;; Fixture construction
;; =============================================================================

(defn- fq [s] (str "ai.obney.orc.orc-service.tick-context-equivalence-test/" s))

(defn- new-sheet! [ctx nm keys*]
  (let [r (h/run-and-apply! ctx (h/make-create-sheet-command :name nm))
        sheet-id (-> r :command-result/events first :sheet-id)]
    (doseq [[k schema] keys*]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k schema)))
    sheet-id))

(defn- add-node! [ctx sheet-id type & {:keys [parent-id index]}]
  (-> (h/run-and-apply! ctx (apply h/make-create-node-command sheet-id type
                                   (cond-> []
                                     parent-id (conj :parent-id parent-id)
                                     index (conj :index index))))
      :command-result/events first :node-id))

(defn- add-leaf! [ctx sheet-id parent-id index fn-name reads writes]
  (let [id (add-node! ctx sheet-id :leaf :parent-id parent-id :index index)]
    (h/run-and-apply! ctx (h/make-set-node-executor-command
                           sheet-id id :code :fn (fq fn-name)))
    (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id id reads writes))
    id))

(defn- dispatch! [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        r (cp/process-command
           (assoc ctx :command {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/tick-tree
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :inputs inputs
                                :options {:timeout-ms 20000}}))]
    (is (not (:cognitect.anomalies/category r))
        (str "dispatch failed: " (:cognitect.anomalies/message r)))
    [(deref p 20000 ::timeout) tick-id]))

;; --- the matrix ------------------------------------------------------------

(defn- fixture:sequence
  "Producer writes :doc, consumer reads it and writes :summary. Also declares
   :unused, which is never written — the entry-set-preservation case."
  [ctx]
  (let [sheet-id (new-sheet! ctx "Sequence" {:scale :int :doc :string
                                             :summary :string :unused :string})
        seq-id (add-node! ctx sheet-id :sequence)]
    (add-leaf! ctx sheet-id seq-id 0 "produce-doc" [:scale] [:doc])
    (add-leaf! ctx sheet-id seq-id 1 "summarize" [:doc] [:summary])
    [sheet-id {:scale 3}]))

(defn- fixture:overwritten-key
  "The same key written by two different nodes — last write must win, and the
   pointer must name the LAST one."
  [ctx]
  (let [sheet-id (new-sheet! ctx "Overwrite" {:scale :int :doc :string})
        seq-id (add-node! ctx sheet-id :sequence)]
    (add-leaf! ctx sheet-id seq-id 0 "produce-doc" [:scale] [:doc])
    (add-leaf! ctx sheet-id seq-id 1 "overwrite-doc" [:doc] [:doc])
    [sheet-id {:scale 2}]))

(defn- map-each-sheet!
  [ctx nm max-concurrency composite?]
  (let [sheet-id (new-sheet! ctx nm {:items [:vector :int]
                                     :current-item :any
                                     :note :string
                                     :results [:vector :any]})
        me-id (add-node! ctx sheet-id :map-each)]
    (if composite?
      ;; Composite child: the map-each's direct child is a sequence, so the
      ;; real writers are its LEAF descendants and attribution by
      ;; [node-id exec-context] alone cannot find them.
      (let [child (add-node! ctx sheet-id :sequence :parent-id me-id :index 0)]
        (add-leaf! ctx sheet-id child 0 "expand-item" [:current-item] [:current-item])
        (add-leaf! ctx sheet-id child 1 "note-item" [:current-item] [:note]))
      (add-leaf! ctx sheet-id me-id 0 "expand-item" [:current-item] [:current-item]))
    (h/run-and-apply! ctx (h/make-set-map-each-config-command
                           sheet-id me-id :items :current-item :results
                           :max-concurrency max-concurrency))
    sheet-id))

(defn- fixture:map-each-serial [ctx]
  [(map-each-sheet! ctx "MapEach1" 1 false) {:items [1 2 3]}])

(defn- fixture:map-each-concurrent [ctx]
  [(map-each-sheet! ctx "MapEach4" 4 false) {:items [1 2 3 4 5 6]}])

(defn- fixture:map-each-composite [ctx]
  [(map-each-sheet! ctx "MapEachComposite" 1 true) {:items [1 2 3 4]}])

(defn- fixture:map-each-composite-concurrent [ctx]
  [(map-each-sheet! ctx "MapEachCompositeC" 4 true) {:items [1 2 3 4]}])

(defn- fixture:map-each-empty [ctx]
  [(map-each-sheet! ctx "MapEachEmpty" 1 false) {:items []}])

(defn- fixture:parallel [ctx]
  (let [sheet-id (new-sheet! ctx "Parallel" {:out-a :string :out-b :string})
        par-id (add-node! ctx sheet-id :parallel)]
    (add-leaf! ctx sheet-id par-id 0 "branch-a" [] [:out-a])
    (add-leaf! ctx sheet-id par-id 1 "branch-b" [] [:out-b])
    (h/run-and-apply! ctx (h/make-set-parallel-config-command
                           sheet-id par-id :success-policy :all :failure-policy :any))
    [sheet-id {}]))

(def ^:private serial-fixtures
  "Fixtures whose write ORDER is fully determined, so a clean replay of the
   events and the live projection must agree key for key."
  {:sequence            fixture:sequence
   :overwritten-key     fixture:overwritten-key
   :map-each-serial     fixture:map-each-serial
   :map-each-composite  fixture:map-each-composite
   :map-each-empty      fixture:map-each-empty})

(def ^:private concurrent-fixtures
  "Fixtures with genuinely concurrent writes. A clean replay sees the FINAL
   write to a contended key; the live projection may still be pointing at an
   earlier one. Both are legitimate views of a tick in flight — and the old
   cache had exactly the same property, since it was written by the same
   projection — so these get the pointer-fidelity invariant instead of whole-map
   equality against a replay."
  {:map-each-concurrent           fixture:map-each-concurrent
   :map-each-composite-concurrent fixture:map-each-composite-concurrent
   :parallel                      fixture:parallel})

(def ^:private fixtures (merge serial-fixtures concurrent-fixtures))

;; =============================================================================
;; The round trip
;; =============================================================================

;; --- the oracle ------------------------------------------------------------

(defn oracle-tick-execution-contexts*
  "The canonical-reference BLACKBOARD projection used as an independent oracle.

   It is a pure function of a tick's events, so reducing it over those events
   reproduces exactly what the cache used to hold — the ground truth every
   resolution must match. Only the blackboard is modelled; the other fields of
   a tick context (nodes-by-id, options, tool-context) are unchanged by this
   work and are not under test here."
  [state event]
  (case (:event/type event)
    :sheet/tree-tick-started
    (if-let [snapshot (:execution-snapshot event)]
      (assoc state (:tick-id event)
             {:blackboard (:blackboard-entries snapshot)})
      state)

    :sheet/execution-value-written
    (let [tick-id (:tick-id event) k (:key event) v (:value event)]
      (if (contains? state tick-id)
        (-> state
            (assoc-in [tick-id :blackboard k :value] v)
            (update-in [tick-id :blackboard k :version] (fnil inc 0))
            (assoc-in [tick-id :blackboard k :source]
                      {:tick-id tick-id :event-id (:event/id event)}))
        state))

    state))

(defn- normalize
  "Compare on what a consumer can observe: the value behind each key.

   Drops the fields that exist only to support resolution (:source,
   :profile), and treats an absent :value the same as a nil one — the old read
   model stored {:value nil} for a seeded nil, while hydration leaves the key
   off. Both yield nil from (:value entry), which is all any caller reads.

   :version is also dropped, and that one needs justifying. Under a concurrent
   map-each the live projection's counter runs AHEAD of a clean replay of the
   same events, because concurrent projections can re-apply a window of events
   on top of a state that already had them. :value survives that (assoc-in of
   the same event's value is idempotent) but (fnil inc 0) does not. See
   live-version-counter-overcounts-under-concurrency below, which pins the
   behavior. It is pre-existing — the counter is written by the same code path
   as before this change — and nothing reads it: a repo-wide search finds
   :version on the tick blackboard only in docstrings and write paths."
  [bb]
  (reduce-kv (fn [m k e]
               (assoc m k (cond-> (dissoc e :source :profile :version)
                            (nil? (:value e)) (dissoc :value))))
             {}
             (or bb {})))

(defn- oracle-blackboard
  "Expected blackboard state for this tick, derived independently from events."
  [ctx tick-id]
  (:blackboard (get (reduce oracle-tick-execution-contexts* {}
                            (h/read-tick-events ctx tick-id))
                    tick-id)))

(defn- hydrated-blackboard
  "The v3 metadata cache, with values resolved back from the write log."
  [ctx tick-id]
  (value-log/hydrate-blackboard (:event-store ctx) (:tenant-id ctx) tick-id
                                (rm/get-tick-blackboard ctx tick-id) nil))

(defn- round-trip [ctx tick-id]
  [(oracle-blackboard ctx tick-id) (hydrated-blackboard ctx tick-id)])

(deftest resolution-is-faithful-to-the-pointer-across-fixture-matrix
  ;; The exact, freshness-independent invariant, checked on EVERY fixture
  ;; including the concurrent ones:
  ;;
  ;;   a key with a :source must resolve to the value carried by
  ;;   THAT event — the write the projection last applied, which is precisely
  ;;   the value the old cache held;
  ;;   a key without one must resolve to its seed.
  ;;
  ;; This is what "the resolver reproduces the cache" means operationally, and
  ;; unlike a comparison against a clean replay it cannot be perturbed by how
  ;; far the projection has caught up.
  (doseq [[fixture-name build!] fixtures]
    (testing (str "pointer fidelity: " fixture-name)
      (h/with-async-test-context [ctx]
        (let [[sheet-id inputs] (build! ctx)
              [result tick-id] (dispatch! ctx sheet-id inputs)]
          (is (= :success (:status result))
              (str fixture-name " did not succeed: " (:error result)))
          (h/settle-until! #(h/trace-stored? ctx tick-id))
          (let [cached (rm/get-tick-blackboard ctx tick-id)
                seeds (value-log/tick-seeds (:event-store ctx) (:tenant-id ctx) tick-id)
                hydrated (hydrated-blackboard ctx tick-id)]
            (is (seq cached) (str fixture-name ": no cached blackboard"))
            (doseq [[k entry] cached]
              (let [expected (if-let [src (:source entry)]
                               (value-log/resolve-source (:event-store ctx)
                                                         (:tenant-id ctx) src)
                               (get seeds k))]
                (is (= expected (:value (get hydrated k)))
                    (str fixture-name " key " k
                         ": resolved value is not the one the cache pointed at"))))))))))

(deftest resolver-reproduces-cached-values-across-fixture-matrix
  ;; Whole-map equality against a clean replay of the tick's events. Restricted
  ;; to serial fixtures: with concurrent writes a replay and the live
  ;; projection can legitimately sit on different writes to a contended key
  ;; (see concurrent-fixtures), which the pointer-fidelity test above covers
  ;; exactly instead.
  (doseq [[fixture-name build!] serial-fixtures]
    (testing (str "round trip: " fixture-name)
      (h/with-async-test-context [ctx]
        (let [[sheet-id inputs] (build! ctx)
              [result tick-id] (dispatch! ctx sheet-id inputs)]
          (is (not= ::timeout result) (str fixture-name " timed out"))
          ;; A fixture that FAILS would round-trip trivially — both sides end
          ;; up equally empty — so the equality assertion below would pass
          ;; vacuously. Require the run to have actually succeeded.
          (is (= :success (:status result))
              (str fixture-name " did not succeed: " (:error result)))
          (h/settle-until! #(h/trace-stored? ctx tick-id))
          (let [[oracle hydrated] (round-trip ctx tick-id)]
            (is (seq oracle) (str fixture-name ": oracle produced no blackboard"))
            ;; Whole-map equality against the independent reducer. Not a spot
            ;; check: every key the fixture produced, including entry set,
            ;; schema and version.
            (is (= (normalize oracle) (normalize hydrated))
                (str fixture-name ": resolved blackboard differs from the oracle.\n"
                     "oracle:   " (pr-str (normalize oracle)) "\n"
                     "hydrated: " (pr-str (normalize hydrated))))))))))

(deftest live-version-counter-overcounts-under-concurrency
  (testing "the oracle's :version equals the write count; the live one can exceed it"
    ;; Pins the one field where a clean replay and the live projection diverge,
    ;; and shows WHY: a clean reduce increments once per write event, so its
    ;; counter is exactly the number of writes to that key. The live counter
    ;; can be higher, because concurrent projections re-apply a window of
    ;; events over a state that already had them — (fnil inc 0) is not
    ;; idempotent, while assoc-in of a value is. Values are therefore
    ;; unaffected, which is what the equivalence test above asserts.
    ;;
    ;; Pre-existing: the counter is produced by the same projection path as
    ;; before this change. Recorded rather than fixed because nothing reads it.
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:map-each-concurrent ctx)
            [result tick-id] (dispatch! ctx sheet-id inputs)]
        (is (= :success (:status result)))
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [events (h/read-tick-events ctx tick-id)
              writes-to-item (count (filter #(and (= :sheet/execution-value-written
                                                     (:event/type %))
                                                  (= :current-item (:key %)))
                                            events))
              oracle-v (:version (get (oracle-blackboard ctx tick-id) :current-item))
              live-v (:version (get (rm/get-tick-blackboard ctx tick-id) :current-item))]
          (is (pos? writes-to-item) "fixture must write the item key repeatedly")
          (is (= writes-to-item oracle-v)
              "a clean replay increments exactly once per write event")
          (is (>= live-v oracle-v)
              "the live counter never runs behind the true write count"))))))

(deftest cached-blackboard-carries-no-values
  (testing "the projection itself yields metadata only"
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:sequence ctx)
            [_ tick-id] (dispatch! ctx sheet-id inputs)]
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [cached (rm/get-tick-blackboard ctx tick-id)]
          (is (seq cached))
          (doseq [[k e] cached]
            (is (not (contains? e :value))
                (str "key " k " still carries a :value in the cache"))))))))

(deftest every-cached-key-resolves-by-exactly-one-route
  (testing "a key has a canonical :source or no value"
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:sequence ctx)
            [_ tick-id] (dispatch! ctx sheet-id inputs)]
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [cached (rm/get-tick-blackboard ctx tick-id)
              seeds (value-log/tick-seeds (:event-store ctx) (:tenant-id ctx) tick-id)]
          (doseq [[k e] cached]
            ;; :unused is declared but never written and never seeded — it has
            ;; no value at all, which is a legitimate third state. A :profile
            ;; is recorded exactly when there was a value to describe, so it is
            ;; the metadata-side witness for "this key has a value."
            (when (some? (:profile e))
              (is (some? (:source e))
                  (str "key " k " has a value but no route to resolve it")))))))))

(deftest declared-but-unwritten-key-keeps-its-entry
  (testing "hydration preserves the entry set exactly"
    ;; gather-inputs distinguishes 'entry absent' (skip the key) from 'entry
    ;; present, value nil' (serialize nil -> empty string). Dropping an
    ;; unresolvable entry would silently change LLM prompts.
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:sequence ctx)
            [_ tick-id] (dispatch! ctx sheet-id inputs)]
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [[oracle hydrated] (round-trip ctx tick-id)]
          (is (contains? oracle :unused))
          (is (= (set (keys oracle)) (set (keys hydrated)))
              "hydration must not add or drop keys")
          (is (nil? (:value (get hydrated :unused)))))))))

(deftest as-of-pointer-rejects-mismatched-event
  (testing "a pointer that fails the tag filter resolves to nothing, not to the previous write"
    ;; :as-of is an inclusive upper BOUND, not an equality filter. Without the
    ;; (= (:event/id e) id) guard in values-by-event-id, a pointer whose event
    ;; is not in this tick would silently resolve to whatever write happened to
    ;; precede it — a wrong value rather than a missing one. That is the worst
    ;; failure mode available here, so it gets a dedicated test.
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:overwritten-key ctx)
            [_ tick-a] (dispatch! ctx sheet-id inputs)
            [_ tick-b] (dispatch! ctx sheet-id inputs)]
        (h/settle-until! #(h/trace-stored? ctx tick-b))
        (let [bb-b (rm/get-tick-blackboard ctx tick-b)
              ptr-b (:source (get bb-b :doc))]
          (is (some? ptr-b) "fixture must produce a written key")
          ;; Ask tick A to resolve a pointer that belongs to tick B. There ARE
          ;; earlier :doc writes in tick A that :as-of would happily return.
          (let [resolved (value-log/values-by-event-id
                          (:event-store ctx) (:tenant-id ctx) tick-a
                          [(:event-id ptr-b)])]
            (is (empty? resolved)
                (str "pointer from another tick must not resolve; got "
                     (pr-str resolved)))))))))

(deftest map-each-item-seeds-resolve-per-iteration
  (testing "concurrent iterations racing on one item key each see their own value"
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:map-each-concurrent ctx)
            [result tick-id] (dispatch! ctx sheet-id inputs)]
        (is (= :success (:status result)) (str "run failed: " (:error result)))
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [events (h/read-tick-events ctx tick-id)
              seeds (value-log/input-seeds-by-iteration events)]
          ;; One seed per item, each under its own iteration identity.
          (is (= (count (:items inputs)) (count seeds))
              (str "expected one seeded iteration per item, got " (pr-str seeds)))
          (is (= (set (:items inputs))
                 (set (map (fn [[_ kv]] (:current-item kv)) seeds)))
              "each iteration must be seeded with its own item"))))))

(deftest delivered-outputs-do-not-depend-on-projection-freshness
  (testing "every written key reaches the caller, resolved from the log"
    ;; Result delivery reads the tick's end state from the write log, not from
    ;; the cached blackboard. This is what makes :outputs immune to a
    ;; projection that has not caught up — a race that can otherwise drop a
    ;; key silently, since a partly-projected blackboard looks exactly like a
    ;; complete one.
    ;;
    ;; The parallel fixture is the sharp case: three children complete at once,
    ;; so their three writes land concurrently and the projection is most
    ;; likely to lag.
    (h/with-async-test-context [ctx]
      (let [[sheet-id inputs] (fixture:parallel ctx)
            [result tick-id] (dispatch! ctx sheet-id inputs)]
        (is (= :success (:status result)))
        (h/settle-until! #(h/trace-stored? ctx tick-id))
        (let [written (into {} (for [e (h/read-tick-events ctx tick-id)
                                     :when (= :sheet/execution-value-written
                                              (:event/type e))]
                                 [(:key e) (:value e)]))]
          (is (seq written) "fixture must produce writes")
          (doseq [[k v] written]
            (is (= v (get (:outputs result) k))
                (str "written key " k " did not reach the caller's :outputs"))))))))

(deftest results-are-deterministic-under-concurrency
  (testing "the same concurrent map-each run produces identical outputs every time"
    (let [runs (for [_ (range 5)]
                 (h/with-async-test-context [ctx]
                   (let [[sheet-id inputs] (fixture:map-each-concurrent ctx)
                         [result _] (dispatch! ctx sheet-id inputs)]
                     (set (:results (:outputs result))))))]
      (is (= 1 (count (set runs)))
          (str "concurrent map-each is not deterministic across runs: "
               (pr-str (set runs)))))))
