(ns resume-state-postgres-roundtrip-test
  "Live proof, against a real Grain Postgres v3 store, that a checkpointed
   researcher's resume state hydrates after the store's serialization round
   trip (lists come back vectors, Integers come back Longs). Needs the local
   dev container orc-rs7-postgres (127.0.0.1:5435, user orc); load with
   load-file from the repository root. It FAILS, never skips, when the
   container is unavailable. The brick-level test of the same property
   (no database) is ai.obney.orc.orc-service.researcher-resume-state-test."
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-postgres-v3.interface]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Real Postgres round trip — the actual store-specific mechanism
;; =============================================================================
;;
;; Uses the same throwaway dev container as the rs7-traffic-sweep bench
;; harness (docker container "orc-rs7-postgres", 127.0.0.1:5435, user orc, no
;; password — see development/src/rs7_traffic_sweep.clj's
;; rs7-postgres-conn-defaults). A fresh, uniquely-named database is created
;; before this ns's tests and dropped afterward; nothing else in the
;; container is touched.

(def ^:private pg-conn-defaults
  {:server-name "127.0.0.1"
   :port-number "5435"
   :username "orc"
   :password nil})

(def ^:private pg-database (atom nil))

(defn- docker-psql! [& args]
  (apply shell/sh "docker" "exec" "orc-rs7-postgres" args))

(defn- postgres-fixture [f]
  (let [db-name (str "rs7_resume_state_test_" (System/currentTimeMillis))]
    (reset! pg-database db-name)
    (let [result (docker-psql! "createdb" "-U" "orc" db-name)]
      (if (zero? (:exit result))
        (try
          (f)
          (finally
            (docker-psql! "dropdb" "-U" "orc" db-name)
            (reset! pg-database nil)))
        (throw (ex-info "could not create a throwaway database on the orc-rs7-postgres dev container"
                        {:err (:err result)}))))))

(use-fixtures :once postgres-fixture)

(defn- pg-event-store-conn []
  (assoc pg-conn-defaults :type :postgres :database-name @pg-database))

(defn- checkpoint-command [sheet-id tick-id node-id state]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :resume-state state
   :iteration-record {:iteration-index (dec (:revision state))
                      :attempt-ordinal 0
                      :status :success}
   :resume? false
   :inputs {}})

(defn- resume-state [revision sandbox-vars]
  {:version 2
   :revision revision
   :ownership-epoch 1
   :next-iteration revision
   :sandbox-vars sandbox-vars
   :var-creation-times (zipmap (keys sandbox-vars) (repeat 0))
   :usage {:prompt-tokens revision :completion-tokens 0 :total-tokens revision}
   :cumulative-tree-ms revision
   :iteration-attempts {}
   :campaign-started-at-ms 100
   :campaign-deadline-ms 10000})

(deftest rs7-a-sandbox-list-value-survives-a-real-postgres-round-trip
  (testing "a representative researcher sandbox containing a genuine Clojure
            list — the value kind the live rs7-traffic-sweep campaign
            actually stored (a `reverse`/`(list ...)`-shaped value bound by
            model-authored sandbox code) — hydrates cleanly through a real
            Postgres event store instead of throwing 'researcher sandbox
            full snapshot hash mismatch'"
    (do
      (h/with-async-test-context [ctx {:event-store-conn (pg-event-store-conn)}]
        (let [sheet-id (random-uuid)
              tick-id (random-uuid)
              node-id (random-uuid)
              submitted (resume-state
                         1 {:memo "kept"
                            :iteration-reasonings (list "first reasoning")
                            :emitted-tree (list :sequence [:llm {:writes [:x]}])
                            :tree-stats {:nodes-total (int 3) :nodes-failed (int 0) :nodes-succeeded (int 3)}})]
          (h/run-and-apply! ctx (checkpoint-command sheet-id tick-id node-id submitted))
          (let [projected (:resume-state
                            (rm/get-researcher-resume-state ctx sheet-id tick-id node-id))]
            (is (some? projected)
                "hydration must not throw and must return the resumed state")
            (is (= (:sandbox-vars submitted) (:sandbox-vars projected))
                "the sandbox value is unchanged under = even though its
                 concrete list/vector class was not preserved by the store")))))))
