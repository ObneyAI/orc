(ns ai.obney.orc.orc-service.delegate-composition-test
  "EB1 — subbehavior-sheet harness + `:delegate` composition seam.

   The foundational tracer for the evolutionary-builder re-architecture: a
   SUBBEHAVIOR is a first-class composed ORC sheet, built via the DSL +
   `build-workflow!`, REGISTERED under a stable name → deterministic sheet-id,
   and invoked from a CENTRAL tree via `:delegate` with mapped `:reads`/`:writes`
   (real child tick, isolated blackboard).

   These tests verify behavior through the subbehavior's PUBLIC `:reads`/`:writes`
   contract (never internal nodes), and prove the writes LANDED by reading the
   PARENT tick's blackboard back from the projection — not by trusting the
   `execute` return value (Grain discipline).

   The subbehavior body here is a deterministic `:code` leaf so the durable test
   is hermetic (no LLM). The reasoning-first `:llm` path and the measured
   delegation overhead are covered by the live-verify
   (`development/src/eb1_delegate_harness_live_verify.clj`,
   `docs/build-timeline/live-verify/EB1-delegate-harness.md`)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.streaming :as streaming]))

(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try (f) (finally (streaming/reset-all!)))))

;; ---------------------------------------------------------------------------
;; The thin subbehavior body — domain-agnostic, deterministic `:code`.
;; Receives {:inputs {:input-contract m}} and returns {:echoed-contract m'}.
;; Preserves every key/value verbatim and adds two provenance keys, so the
;; round-trip is exactly assertable.
;; ---------------------------------------------------------------------------
(defn echo-transform [{:keys [inputs]}]
  (let [m (let [c (:input-contract inputs)] (if (map? c) c {}))]
    {:echoed-contract (assoc m
                             :echoed-by "eb1-echo-subbehavior"
                             :field-count (count m))}))

(def echo-subbehavior-name "eb1-test/echo-contract@v1")
(def central-name "eb1-test/central-delegator@v1")

(defn- echo-subbehavior-def []
  (dsl/workflow echo-subbehavior-name
    (dsl/blackboard {:input-contract :map
                     :echoed-contract :map})
    (dsl/sequence "echo-root"
      (dsl/code "echo"
        :fn "ai.obney.orc.orc-service.delegate-composition-test/echo-transform"
        :reads [:input-contract]
        :writes [:echoed-contract]))))

(defn- central-def [target-sheet-id]
  (dsl/workflow central-name
    (dsl/blackboard {:input-contract :map
                     :echoed-contract :map})
    (dsl/sequence "central-root"
      (dsl/delegate "to-echo-subbehavior"
        :target-sheet-id target-sheet-id
        :reads [:input-contract]
        :writes [:echoed-contract]
        :timeout-ms 15000))))

(def sample-contract
  {:goal "round-trip a contract through a delegated subbehavior"
   :source-kind "arbitrary"
   :payload {:a 1 :b [2 3] :c "three"}})

;; ---------------------------------------------------------------------------
;; Registry pattern: name → deterministic sheet-id, idempotent registration.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-sheet-id-test
  (testing "a subbehavior registered by name resolves to a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (dsl/build-workflow! ctx (echo-subbehavior-def))
            ;; The registry LOOK-UP: name → sheet-id, computed purely, must equal
            ;; the id build-workflow! minted at registration time.
            looked-up (dsl/sheet-id-for-name echo-subbehavior-name)
            ;; Re-registration of an unchanged def is idempotent → same id.
            id-2 (dsl/build-workflow! ctx (echo-subbehavior-def))]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (rm/get-sheet-by-name ctx echo-subbehavior-name))
            "the registered subbehavior is discoverable by name in the projection")))))

;; ---------------------------------------------------------------------------
;; The subbehavior is independently runnable via its public contract.
;; ---------------------------------------------------------------------------

(deftest subbehavior-runnable-in-isolation-test
  (testing "the subbehavior runs standalone via its :reads/:writes contract"
    (h/with-async-test-context [ctx]
      (let [sub-id (dsl/build-workflow! ctx (echo-subbehavior-def))
            result (runtime/execute ctx sub-id {"input-contract" sample-contract}
                                    :timeout-ms 15000)
            echoed (get-in result [:outputs :echoed-contract])]
        (is (= :success (:status result)))
        (is (= (:payload sample-contract) (:payload echoed))
            "input payload preserved verbatim")
        (is (= "eb1-echo-subbehavior" (:echoed-by echoed)))
        (is (= (count sample-contract) (:field-count echoed)))))))

;; ---------------------------------------------------------------------------
;; The composition seam: central tree :delegate → child tick → writes back,
;; proven by reading the PARENT tick blackboard from the projection.
;; ---------------------------------------------------------------------------

(deftest central-delegate-roundtrips-contract-via-projection-test
  (testing "a central tree :delegate runs the subbehavior as a child tick and the
            contract round-trips onto the PARENT blackboard (events landed, read back)"
    (h/with-async-test-context [ctx]
      (let [sub-id (dsl/build-workflow! ctx (echo-subbehavior-def))
            ;; central tree resolves the subbehavior by name → deterministic id
            central-id (dsl/build-workflow! ctx (central-def
                                                 (dsl/sheet-id-for-name echo-subbehavior-name)))
            tick-id (random-uuid)
            result (runtime/execute ctx central-id {"input-contract" sample-contract}
                                    :timeout-ms 15000 :tick-id tick-id)
            ;; DISCIPLINE 7: do not trust :outputs — read the parent tick
            ;; blackboard back from the projection.
            parent-bb (rm/get-tick-blackboard ctx tick-id)
            echoed (get-in parent-bb [:echoed-contract :value])]
        (is (= :success (:status result)))
        (is (map? echoed)
            "the delegated :writes landed on the PARENT tick blackboard (projection read-back)")
        (is (= (:payload sample-contract) (:payload echoed))
            "input payload round-trips verbatim through the child tick onto the parent bb")
        (is (= (:goal sample-contract) (:goal echoed))
            "input goal round-trips verbatim")
        (is (= "eb1-echo-subbehavior" (:echoed-by echoed))
            "subbehavior provenance flowed back to the parent")
        (is (= (count sample-contract) (:field-count echoed))
            "subbehavior-computed field-count flowed back to the parent")))))

(deftest delegate-maps-only-declared-reads-and-writes-test
  (testing ":delegate maps ONLY the declared :reads in and :writes back — an
            undeclared parent key does not leak into the child, and an undeclared
            subbehavior output does not appear on the parent"
    (h/with-async-test-context [ctx]
      (let [sub-id (dsl/build-workflow! ctx (echo-subbehavior-def))
            ;; central declares an EXTRA parent key the delegate does NOT read.
            central-def* (dsl/workflow central-name
                           (dsl/blackboard {:input-contract :map
                                            :secret :string
                                            :echoed-contract :map})
                           (dsl/sequence "central-root"
                             (dsl/delegate "to-echo-subbehavior"
                               :target-sheet-id (dsl/sheet-id-for-name echo-subbehavior-name)
                               :reads [:input-contract]   ;; NOT :secret
                               :writes [:echoed-contract]
                               :timeout-ms 15000)))
            central-id (dsl/build-workflow! ctx central-def*)
            tick-id (random-uuid)
            _ (runtime/execute ctx central-id
                               {"input-contract" sample-contract
                                "secret" "do-not-leak"}
                               :timeout-ms 15000 :tick-id tick-id)
            parent-bb (rm/get-tick-blackboard ctx tick-id)
            echoed (get-in parent-bb [:echoed-contract :value])]
        ;; :secret was NOT in :reads, so the subbehavior never saw it: the echoed
        ;; contract carries the input-contract keys + provenance, but not :secret.
        (is (map? echoed))
        (is (not (contains? echoed :secret))
            "an undeclared parent key must not flow into the delegated child")
        (is (= (set (concat (keys sample-contract) [:echoed-by :field-count]))
               (set (keys echoed)))
            "echoed contract carries exactly the input keys + provenance")))))

;; ---------------------------------------------------------------------------
;; Cross-sheet blackboard-key isolation (the EB3 C1 root-cause regression).
;;
;; The :sheet/blackboard read model is PARTITIONED by :sheet-id. The partition
;; cache requires globally-unique entity-ids; keying by the bare blackboard :key
;; let a second sheet's shared key (e.g. :goal) be routed as a cross-partition
;; "move" out of the first sheet's partition, leaving the second sheet's
;; blackboard EMPTY (schemas lost). Because :delegate maps :reads/:writes by the
;; SAME key name on parent + sub, the parent and sub ALWAYS share key names — so
;; the central tree's blackboard projection poisoned the sub-sheet's, and a
;; delegated :llm node's structured [:map …] write lost its schema and crossed
;; :delegate as a JSON STRING (the EB3 C1 failure). The fix composes the
;; entity-id with the sheet-id. These hermetic tests lock that isolation.
;; ---------------------------------------------------------------------------

(deftest blackboards-with-shared-key-names-stay-isolated-per-sheet-test
  (testing "two sheets that declare the SAME blackboard key names keep their own
            schemas — projecting one must not empty/poison the other's partition"
    (h/with-async-test-context [ctx]
      (let [;; both sheets declare a key named :shared but with DIFFERENT schemas
            sheet-a (dsl/build-workflow! ctx
                      (dsl/workflow "iso-test/sheet-a@v1"
                        (dsl/blackboard {:shared :string :only-a :int})
                        (dsl/sequence "a-root"
                          (dsl/code "a" :fn "clojure.core/identity"
                            :reads [:shared] :writes [:only-a]))))
            sheet-b (dsl/build-workflow! ctx
                      (dsl/workflow "iso-test/sheet-b@v1"
                        (dsl/blackboard {:shared [:map {:closed false}] :only-b :boolean})
                        (dsl/sequence "b-root"
                          (dsl/code "b" :fn "clojure.core/identity"
                            :reads [:shared] :writes [:only-b]))))
            ;; read A first (populates the partition cache / entity-index), then B
            bb-a (rm/get-blackboard-by-key ctx sheet-a)
            bb-b (rm/get-blackboard-by-key ctx sheet-b)]
        ;; Each sheet keeps EXACTLY its own keys — no bleed, no emptying.
        (is (= #{:shared :only-a} (set (keys bb-a)))
            "sheet A keeps its own keys after sheet B is also projected")
        (is (= #{:shared :only-b} (set (keys bb-b)))
            "sheet B's blackboard is NOT emptied by sharing :shared with sheet A")
        ;; The shared key carries each sheet's OWN schema (not the other's).
        (is (= :string (get-in bb-a [:shared :schema]))
            "sheet A's :shared keeps A's schema")
        (is (= [:map {:closed false}] (get-in bb-b [:shared :schema]))
            "sheet B's :shared keeps B's schema (not poisoned by A)")
        ;; Every entry is correctly attributed to its own sheet.
        (is (= sheet-a (get-in bb-a [:shared :sheet-id])))
        (is (= sheet-b (get-in bb-b [:shared :sheet-id])))))))

(deftest delegated-sub-sheet-keeps-its-schema-when-parent-shares-key-names-test
  (testing "the EB3 C1 root cause: a delegated sub-sheet's blackboard SCHEMA
            survives even though the central tree declares the SAME key names —
            so a structured map write can flatten/parse across :delegate"
    (h/with-async-test-context [ctx]
      (let [structured-schema [:map {:closed false} [:x {:optional true} :any]]
            sub-id (dsl/build-workflow! ctx
                     (dsl/workflow "iso-test/sub@v1"
                       (dsl/blackboard {:input-contract :map
                                        :echoed-contract structured-schema})
                       (dsl/sequence "sub-root"
                         (dsl/code "echo"
                           :fn "ai.obney.orc.orc-service.delegate-composition-test/echo-transform"
                           :reads [:input-contract] :writes [:echoed-contract]))))
            ;; central declares the SAME keys (delegate maps by name) — this is
            ;; what poisoned the sub-sheet partition before the fix.
            central-id (dsl/build-workflow! ctx
                         (dsl/workflow "iso-test/central@v1"
                           (dsl/blackboard {:input-contract :map
                                            :echoed-contract structured-schema})
                           (dsl/sequence "c-root"
                             (dsl/delegate "to-sub"
                               :target-sheet-id sub-id
                               :reads [:input-contract] :writes [:echoed-contract]
                               :timeout-ms 15000))))
            tick-id (random-uuid)
            _ (runtime/execute ctx central-id {"input-contract" sample-contract}
                               :timeout-ms 15000 :tick-id tick-id)
            ;; the sub-sheet's build-time blackboard still carries its schema
            sub-bb (rm/get-blackboard-by-key ctx sub-id)]
        (is (= structured-schema (get-in sub-bb [:echoed-contract :schema]))
            "the sub-sheet's structured :echoed-contract schema is intact even though
             the central tree declares the same key name (no partition poisoning)")
        (is (= :map (get-in sub-bb [:input-contract :schema]))
            "the sub-sheet's :input-contract schema is intact too")))))
