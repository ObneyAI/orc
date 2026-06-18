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
