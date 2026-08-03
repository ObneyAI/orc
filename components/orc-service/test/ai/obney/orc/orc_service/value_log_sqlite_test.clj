(ns ai.obney.orc.orc-service.value-log-sqlite-test
  "The resolver, exercised against the SQLite event store.

   Why a second backend: values are resolved by POINTER — a
   {:as-of <write-event-id> :reverse? true :limit 1} query per key — and that
   query means genuinely different code on each store. In-memory scans a vector
   in process; SQLite emits `e.id <= ? ORDER BY e.id DESC LIMIT 1` against an
   index, and a batch of pointers becomes a UNION. A resolver that is correct
   on one is not thereby correct on the other, and SQLite is the durable
   backend a consumer would actually deploy.

   These are resolver-level rather than workflow-level tests by necessity: the
   SQLite store has no pubsub wiring, so todo processors never fire and no tick
   can execute against it. The events are therefore synthesized to the exact
   shape commands.clj and todo_processors.clj emit — which is the part the
   resolver contracts with anyway.

   The sharpest case here is the :as-of guard. :as-of is an inclusive upper
   BOUND, not an equality filter, so a pointer that fails the tag/type
   predicate resolves to whatever write happens to precede it — a wrong value
   rather than a missing one, and silently. Both the in-memory and the SQL path
   have to reject that."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            ;; Registers the :sheet/* event schemas the store validates against.
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.java.io :as io]))

;; =============================================================================
;; Store lifecycle
;; =============================================================================

(defn- sqlite-store! []
  (let [db-file (str "/tmp/value-log-sqlite-" (random-uuid) ".db")]
    [(es/start {:conn {:type :sqlite
                       :database-file db-file
                       :maximum-pool-size 4}})
     db-file]))

(defn- stop-store! [store db-file]
  (es/stop store)
  (doseq [s ["" "-wal" "-shm"]]
    (io/delete-file (str db-file s) true)))

(defmacro with-sqlite-store [[store-sym] & body]
  `(let [[~store-sym db-file#] (sqlite-store!)]
     (try ~@body (finally (stop-store! ~store-sym db-file#)))))

;; =============================================================================
;; Event synthesis — the exact shapes the engine emits
;; =============================================================================

(defn- tick-started
  "Mirrors commands.clj tick-tree: carries the execution snapshot's blackboard
   entries and the caller's :inputs, both with values inline."
  [sheet-id tick-id bb-entries inputs]
  (es/->event {:type :sheet/tree-tick-started
               :tags #{[:sheet sheet-id] [:tick tick-id]}
               :body {:sheet-id sheet-id
                      :tick-id tick-id
                      :inputs inputs
                      :execution-snapshot {:blackboard-entries bb-entries
                                           :nodes-by-id {}
                                           :root-node-id nil}}}))

(defn- value-written
  "Mirrors commands.clj complete-node-execution / make-bb-write-event."
  [sheet-id tick-id node-id k v & {:keys [exec-context input-seed?]}]
  (es/->event {:type :sheet/execution-value-written
               :tags #{[:sheet sheet-id] [:tick tick-id]}
               :body (cond-> {:sheet-id sheet-id :tick-id tick-id
                              :node-id node-id :key k :value v}
                       (seq exec-context) (assoc :exec-context exec-context)
                       input-seed? (assoc :input-seed? true))}))

(defn- append! [store tenant-id events]
  (let [r (es/append store {:tenant-id tenant-id :events (vec events)})]
    (is (not (:cognitect.anomalies/category r))
        (str "append failed: " (:cognitect.anomalies/message r)))
    r))

;; =============================================================================
;; The oracle — the v2 reducer's blackboard projection
;; =============================================================================

(defn- oracle
  "What the v2 cache would have held, reduced from the events directly."
  [events tick-id]
  (:blackboard
   (get (reduce
         (fn [state event]
           (case (:event/type event)
             :sheet/tree-tick-started
             (let [snapshot (:execution-snapshot event)
                   blackboard (reduce (fn [bb [kn value]]
                                        (let [k (if (string? kn) (keyword kn) kn)]
                                          (if (get bb k)
                                            (-> bb (assoc-in [k :value] value)
                                                (assoc-in [k :version] 1))
                                            (assoc bb k {:key k :schema :any
                                                         :value value :version 1}))))
                                      (:blackboard-entries snapshot)
                                      (or (:inputs event) {}))]
               (assoc state (:tick-id event) {:blackboard blackboard}))

             :sheet/execution-value-written
             (let [t (:tick-id event) k (:key event)]
               (if (contains? state t)
                 (-> state
                     (assoc-in [t :blackboard k :value] (:value event))
                     (update-in [t :blackboard k :version] (fnil inc 0))
                     (assoc-in [t :blackboard k :source-event-id] (:event/id event)))
                 state))

             state))
         {}
         events)
        tick-id)))

(defn- metadata-of
  "Strip the oracle down to what the v3 cache stores."
  [bb]
  (reduce-kv (fn [m k e] (assoc m k (dissoc e :value))) {} bb))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest resolver-matches-oracle-on-sqlite
  (testing "pointer resolution over the SQL path reproduces every value"
    (with-sqlite-store [store]
      (let [tenant-id (random-uuid)
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node-a (random-uuid)
            node-b (random-uuid)
            events [(tick-started sheet-id tick-id
                                  {:doc {:key :doc :schema :string
                                         :value "seeded-doc" :version 1}
                                   :untouched {:key :untouched :schema :string
                                               :value "never-written" :version 1}}
                                  {:scale 3})
                    (value-written sheet-id tick-id node-a :out "first")
                    (value-written sheet-id tick-id node-b :out "second")
                    (value-written sheet-id tick-id node-a :doc "overwritten")]]
        (append! store tenant-id events)
        (let [stored (into [] (es/read store {:tenant-id tenant-id
                                              :tags #{[:tick tick-id]}}))
              expected (oracle stored tick-id)
              hydrated (value-log/hydrate-blackboard
                        store tenant-id tick-id (metadata-of expected) nil)]
          (is (= 4 (count stored)) "all events must be readable back")
          (doseq [[k e] expected]
            (is (= (:value e) (:value (get hydrated k)))
                (str "key " k " resolved to the wrong value on SQLite")))
          ;; Named explicitly so a regression in any single route is legible.
          (is (= "overwritten" (:value (get hydrated :doc)))
              "a seeded key that was later written resolves to the WRITE")
          (is (= "second" (:value (get hydrated :out)))
              "a key written twice resolves to the LAST write")
          (is (= "never-written" (:value (get hydrated :untouched)))
              "a seeded key never written resolves to the SEED"))))))

(deftest as-of-pointer-rejects-mismatched-event-on-sqlite
  (testing "a pointer from another tick resolves to nothing, not to a neighbour"
    ;; The SQL path is where this bites hardest: `e.id <= ? ORDER BY e.id DESC
    ;; LIMIT 1` combined with a tag filter will happily return an EARLIER row
    ;; when the pointed-at row is filtered out. Without the identity guard the
    ;; caller gets a plausible, wrong value.
    (with-sqlite-store [store]
      (let [tenant-id (random-uuid)
            sheet-id (random-uuid)
            tick-a (random-uuid)
            tick-b (random-uuid)
            node (random-uuid)]
        ;; tick A writes :k twice, so there are earlier rows for :as-of to
        ;; fall back onto; tick B's write is the pointer we will misuse.
        (append! store tenant-id [(value-written sheet-id tick-a node :k "a1")
                                  (value-written sheet-id tick-a node :k "a2")])
        (let [b-event (value-written sheet-id tick-b node :k "b1")]
          (append! store tenant-id [b-event])
          (let [resolved (value-log/values-by-event-id
                          store tenant-id tick-a [(:event/id b-event)])]
            (is (empty? resolved)
                (str "pointer from tick B must not resolve within tick A; got "
                     (pr-str resolved)))
            (is (not (contains? (set (vals resolved)) "a2"))
                "must not silently fall back to the preceding write")))))))

(deftest resolution-survives-append-order-differing-from-id-order
  (testing "a pointer resolves even when the store's append order is not id order"
    ;; THE regression this pins. The obvious way to fetch one event by id is
    ;; {:as-of id :reverse? true :limit 1} — "the last matching event at or
    ;; before this id". But "last" means last in APPEND order, and concurrent
    ;; appends commit out of id order routinely. When they do, that query
    ;; returns a NEIGHBOURING write.
    ;;
    ;; Constructed deterministically here: two events are created so that
    ;; a-id < b-id, then appended in the opposite order. A seek for b would
    ;; reverse the append order [b a] to [a b] and take a — the wrong event.
    ;; values-by-event-id must return b's value regardless.
    (with-sqlite-store [store]
      (let [tenant-id (random-uuid)
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node (random-uuid)
            ev-a (value-written sheet-id tick-id node :k "value-a")
            ev-b (value-written sheet-id tick-id node :k "value-b")]
        (is (neg? (compare (:event/id ev-a) (:event/id ev-b)))
            "fixture assumes a was created before b")
        ;; Append in reverse: storage order [b a], id order [a b].
        (append! store tenant-id [ev-b])
        (append! store tenant-id [ev-a])
        (is (= {(:event/id ev-b) "value-b"}
               (value-log/values-by-event-id store tenant-id tick-id
                                             [(:event/id ev-b)]))
            "must resolve the requested event, not its append-order neighbour")
        (is (= {(:event/id ev-a) "value-a"}
               (value-log/values-by-event-id store tenant-id tick-id
                                             [(:event/id ev-a)])))))))

(deftest resolution-scales-to-many-pointers-on-sqlite
  (testing "every pointer in a large tick resolves"
    (with-sqlite-store [store]
      (let [tenant-id (random-uuid)
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node (random-uuid)
            n 40
            events (mapv #(value-written sheet-id tick-id node
                                         (keyword (str "k" %)) (str "v" %))
                         (range n))]
        (append! store tenant-id events)
        (let [ids (mapv :event/id events)
              all-at-once (value-log/values-by-event-id store tenant-id tick-id ids)
              one-at-a-time (reduce (fn [acc id]
                                      (merge acc (value-log/values-by-event-id
                                                  store tenant-id tick-id [id])))
                                    {} ids)]
          (is (= n (count all-at-once)) "must resolve every pointer")
          (is (= all-at-once one-at-a-time)
              "resolving in bulk and one-by-one must agree"))))))

(deftest seeded-values-survive-a-round-trip-on-sqlite
  (testing "seeds are recovered from the tick-started event, not from a write"
    (with-sqlite-store [store]
      (let [tenant-id (random-uuid)
            sheet-id (random-uuid)
            tick-id (random-uuid)]
        (append! store tenant-id
                 [(tick-started sheet-id tick-id
                                {:from-snapshot {:key :from-snapshot :schema :string
                                                 :value "snap" :version 1}}
                                ;; String keys, as runtime/execute produces.
                                {"from-inputs" "inp"})])
        (let [seeds (value-log/tick-seeds store tenant-id tick-id)]
          (is (= "snap" (:from-snapshot seeds)))
          (is (= "inp" (:from-inputs seeds))
              "string input keys must be keywordized, matching the reducer"))))))
