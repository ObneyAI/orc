(ns ai.obney.orc.orc-service.r05c-mint-behavior-sandbox-test
  "R05c — sandbox primitive (mint-behavior! ...) integration test.

   Asserts:
   - build-rlm-context accepts new :sheet-id / :tick-id / :command-registry
     opts and threads them through to the mint-behavior! primitive.
   - (mint-behavior! ...) dispatches :ontology/mint-behavioral-subtree
     with :provenance :agent-minted automatically stamped + :minted-by-*
     populated from the build-rlm-context opts.
   - Returns the new behavior's UUID as a STRING so the agent can
     interpolate it in subsequent DSL forms.
   - Throws a clear error when the command-context opts are absent
     (test/dry-run scenarios should fail loudly rather than silently
     no-op)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn- create-ctx []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r05c-sandbox-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-ctx [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (create-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

(defn- mint-body-template []
  "A non-trivial body the agent might author when minting a new
   behavioral subtree from inside the sandbox."
  {:capabilities ["x"]
   :strengths []
   :weaknesses []
   :representative-uses ["x"]
   :avoid-when ["x"]
   :summary "Minted from sandbox in a recursive RLM session."
   :version 1
   :consolidated-from-event-count 0})

;; =============================================================================
;; RED #9 — (mint-behavior! ...) dispatches the defcommand with agent provenance
;;            + sandbox sheet/tick stamped from build-rlm-context opts
;; =============================================================================

(deftest sandbox-primitive-mints-with-agent-provenance
  (with-ctx [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id sheet-id
                     :tick-id tick-id})
          code (str "(mint-behavior! \"agent-discovered-behavior\" "
                    (pr-str (mint-body-template))
                    ")")
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)]

      (testing "primitive returned a string (no error)"
        (is (nil? (:error exec))
            (str "Expected no error from sandbox; got: " (:error exec)))
        (is (string? (:raw-result exec))
            "Primitive returns the new UUID as a STRING for DSL interpolation"))

      (testing "audit-trail :ontology/behavioral-subtree-minted event landed"
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/behavioral-subtree-minted}}))
              event (last events)]
          (is (some? event) "Mint event landed in the store")
          (is (= :agent-minted (:provenance event))
              "Sandbox path stamps :provenance :agent-minted automatically")
          (is (= sheet-id (:minted-by-sheet-id event))
              "Sandbox stamps :minted-by-sheet-id from build-rlm-context opt")
          (is (= tick-id (:minted-by-tick-id event))
              "Sandbox stamps :minted-by-tick-id from build-rlm-context opt"))))))

;; =============================================================================
;; RED #10 — return value is a string UUID that parses back to the audit event's
;;             :target-id (agent can interpolate it in subsequent DSL forms)
;; =============================================================================

(deftest sandbox-primitive-return-value-roundtrips
  (with-ctx [ctx]
    (let [rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id (random-uuid)
                     :tick-id (random-uuid)})
          code (str "(mint-behavior! \"roundtrip-behavior\" "
                    (pr-str (mint-body-template))
                    ")")
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)
          returned-str (:raw-result exec)
          audit-event (last (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :types #{:ontology/behavioral-subtree-minted}})))]
      (testing "string parses back to the same UUID stored on the audit event"
        (is (= (java.util.UUID/fromString returned-str)
               (:target-id audit-event))
            "Sandbox return value identifies the minted concept end-to-end")))))

;; =============================================================================
;; RED #11 — :parent kwarg is forwarded to the defcommand as :parent-behavior
;; =============================================================================

(deftest sandbox-primitive-forwards-parent-kwarg
  (with-ctx [ctx]
    (let [parent-id (random-uuid)
          rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id (random-uuid)
                     :tick-id (random-uuid)})
          code (str "(mint-behavior! \"child-behavior\" "
                    (pr-str (mint-body-template))
                    " :parent \"" parent-id "\")")
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)]
      (is (nil? (:error exec))
          (str "Expected no error; got: " (:error exec)))
      (let [audit-event (last (into [] (es/read (:event-store ctx)
                                                {:tenant-id (:tenant-id ctx)
                                                 :types #{:ontology/behavioral-subtree-minted}})))]
        (testing ":parent kwarg becomes :parent-behavior on the audit event"
          (is (= (str parent-id) (str (:parent-behavior audit-event)))
              "Parent ID forwarded — agent can mint under an existing behavior"))))))

;; =============================================================================
;; RED #12 — calling (mint-behavior! ...) without command-context opts throws
;;             a clear error (no silent no-op)
;; =============================================================================

(deftest sandbox-primitive-without-command-context-throws
  (let [rlm-ctx (rlm-sandbox/build-rlm-context
                  {:provider :openrouter
                   :blackboard {}
                   :declared-writes [:result]})
        code (str "(mint-behavior! \"will-fail\" "
                  (pr-str (mint-body-template))
                  ")")
        exec (rlm-sandbox/execute-rlm-code rlm-ctx code)]
    (testing "sandbox surfaces an error (clear failure, not silent no-op)"
      (is (some? (:error exec))
          "mint-behavior! without :sheet-id/:tick-id/:command-registry must fail loudly")
      (is (re-find #"(?i)mint-behavior!|command.context|command-registry"
                   (or (:error exec) ""))
          "Error message identifies the missing affordance"))))

;; =============================================================================
;; QP-1 — Malformed body must surface as a sandbox error, NOT silent success
;; =============================================================================
;;
;; Discovered during C-Loop-3 LIVE: when the model emitted (mint-behavior!
;; ...) with :strengths as a VECTOR OF STRINGS (not vector of principle-
;; entry maps), Grain's command processor returned an anomaly
;; {::anom/category ::anom/incorrect ::anom/message "Invalid Command:
;; Failed Schema Validation"} but the sandbox's mint-behavior!-fn ignored
;; the anomaly and returned an empty string "" (because (:command-result/
;; events <anomaly>) is nil). The model believed the mint succeeded and
;; referenced the "minted behavior" in subsequent iterations — but no
;; concept ever existed.
;;
;; Fix contract: when the dispatched command returns an anomaly, the
;; sandbox primitive throws a clear error. The agent's next iteration sees
;; the error in the iteration history and can reconsider the mint shape.

(deftest sandbox-primitive-with-malformed-body-surfaces-error
  (with-ctx [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id sheet-id
                     :tick-id tick-id})
          ;; :strengths is a vec of STRINGS — schema requires vec of
          ;; principle-entry maps. Grain's command processor rejects.
          malformed-body {:capabilities ["x"]
                          :strengths ["this should be a map not a string"]
                          :weaknesses []
                          :representative-uses ["x"]
                          :avoid-when ["x"]
                          :summary "Malformed body to verify error surfaces."
                          :version 1
                          :consolidated-from-event-count 0}
          code (str "(mint-behavior! \"malformed-body-test\" "
                    (pr-str malformed-body)
                    ")")
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)]

      (testing "sandbox surfaces an error (does NOT silently return empty string)"
        (is (some? (:error exec))
            (str "Expected mint-behavior! with malformed body to surface an error. "
                 "Got :error nil — this is the silent-drop bug. exec: "
                 (pr-str (select-keys exec [:error :raw-result])))))

      (testing "error message identifies the validation failure clearly"
        (is (re-find #"(?i)mint|schema|invalid|valid"
                     (or (:error exec) ""))
            "Error message should reference mint / schema / invalid so the agent knows what to fix"))

      (testing "no mint event landed in the store"
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/behavioral-subtree-minted}}))]
          (is (zero? (count events))
              "No behavioral-subtree-minted event should land when the body fails schema validation"))))))

;; =============================================================================
;; C-Loop-2 S5 — (get-description ...) SCI binding finds same-iteration mints
;; =============================================================================
;;
;; After (mint-behavior! ...) returns, the SAME iteration's code can call
;; (get-description "<minted-uuid-string>") and receive the description
;; body. Lets the agent verify a mint, inspect existing seeds, or read
;; descriptions for behaviors surfaced by classify-behaviors before
;; deciding to reuse / adapt / mint.
;;
;; Granularity defaults to :tree-fingerprint (where behavioral-subtree
;; mints land); explicit 2-arity supports other granularities. Returns
;; the description body map or nil.

(deftest sandbox-get-description-finds-just-minted-behavior
  (with-ctx [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id sheet-id
                     :tick-id tick-id})
          code (str "(let [minted-id (mint-behavior! \"same-iter-lookup\" "
                    (pr-str (mint-body-template)) ")]\n"
                    "  {:minted-id minted-id\n"
                    "   :description (get-description minted-id)})")
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)
          result (:raw-result exec)]
      (testing "no sandbox error"
        (is (nil? (:error exec))
            (str "Expected no error; got: " (:error exec))))
      (testing "(get-description ...) returns a non-nil body for the just-minted behavior"
        (is (map? (:description result))
            (str "Expected description body to be a map; got: " (pr-str result))))
      (testing "the body has :scope :behavioral-subtree (stamped by the mint defcommand)"
        (is (= :behavioral-subtree (:scope (:description result)))
            "Mint defcommand stamps :scope :behavioral-subtree on the body — same-iteration lookup finds it"))
      (testing "the body's :summary survives roundtrip"
        (is (= "Minted from sandbox in a recursive RLM session."
               (:summary (:description result)))
            ":summary from the original mint body is readable via get-description")))))

(deftest sandbox-get-description-returns-nil-for-unknown-target
  (with-ctx [ctx]
    (let [rlm-ctx (rlm-sandbox/build-rlm-context
                    {:provider :openrouter
                     :blackboard {}
                     :declared-writes [:result]
                     :event-store (:event-store ctx)
                     :tenant-id (:tenant-id ctx)
                     :cache (:cache ctx)
                     :command-registry (:command-registry ctx)
                     :sheet-id (random-uuid)
                     :tick-id (random-uuid)})
          code "(get-description \"00000000-0000-0000-0000-000000000000\")"
          exec (rlm-sandbox/execute-rlm-code rlm-ctx code)]
      (testing "unknown target → nil (not an error)"
        (is (nil? (:error exec))
            "Lookup of an unknown target-id should not throw")
        (is (nil? (:raw-result exec))
            "Lookup of an unknown target-id returns nil — agent can branch on this")))))
