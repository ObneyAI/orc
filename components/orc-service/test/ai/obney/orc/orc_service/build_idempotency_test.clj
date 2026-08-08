(ns ai.obney.orc.orc-service.build-idempotency-test
  "Tests for no-op workflow builds when definition is unchanged.
   Verifies that build-workflow! skips all events when content hash matches."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn- count-events
  "Count all events in the event store for the given context."
  [ctx]
  (count (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))))

(defn- simple-workflow []
  (dsl/workflow "idempotency-test"
    (dsl/blackboard {:input :string :output :string})
    (dsl/sequence "main"
      (dsl/llm "process"
        :model "test/model"
        :instruction "Process the input"
        :reads [:input]
        :writes [:output]))))

(deftest build-workflow-stores-content-hash
  (testing "first build stores a content hash on the sheet"
    (h/with-test-context [ctx]
      (let [wf (simple-workflow)
            sheet-id (dsl/build-workflow! ctx wf)
            sheet (rm/get-sheet ctx sheet-id)]
        (is (some? sheet))
        (is (string? (:content-hash sheet)))
        (is (pos? (count (:content-hash sheet))))))))

(deftest rebuild-unchanged-workflow-is-noop
  (testing "rebuilding an identical workflow emits no new events"
    (h/with-test-context [ctx]
      (let [wf (simple-workflow)
            _ (dsl/build-workflow! ctx wf)
            events-after-first (count-events ctx)
            _ (dsl/build-workflow! ctx wf)
            events-after-second (count-events ctx)]
        (is (pos? events-after-first) "first build should emit events")
        (is (= events-after-first events-after-second)
            "second build of identical workflow should emit zero new events")))))

(deftest rebuild-changed-workflow-emits-events
  (testing "rebuilding with a different instruction triggers a full rebuild"
    (h/with-test-context [ctx]
      (let [wf1 (simple-workflow)
            sheet-id (dsl/build-workflow! ctx wf1)
            events-after-first (count-events ctx)
            hash-after-first (:content-hash (rm/get-sheet ctx sheet-id))
            wf2 (dsl/workflow "idempotency-test"
                  (dsl/blackboard {:input :string :output :string})
                  (dsl/sequence "main"
                    (dsl/llm "process"
                      :model "test/model"
                      :instruction "CHANGED instruction"
                      :reads [:input]
                      :writes [:output])))
            _ (dsl/build-workflow! ctx wf2)
            events-after-second (count-events ctx)
            hash-after-second (:content-hash (rm/get-sheet ctx sheet-id))]
        (is (< events-after-first events-after-second)
            "changed workflow should emit new events")
        (is (not= hash-after-first hash-after-second)
            "content hash should change after rebuild")))))

(deftest rebuild-changed-blackboard-emits-events
  (testing "rebuilding with a different blackboard schema triggers a full rebuild"
    (h/with-test-context [ctx]
      (let [wf1 (simple-workflow)
            sheet-id (dsl/build-workflow! ctx wf1)
            events-after-first (count-events ctx)
            wf2 (dsl/workflow "idempotency-test"
                  (dsl/blackboard {:input :string :output :string :extra :int})
                  (dsl/sequence "main"
                    (dsl/llm "process"
                      :model "test/model"
                      :instruction "Process the input"
                      :reads [:input]
                      :writes [:output])))
            _ (dsl/build-workflow! ctx wf2)
            events-after-second (count-events ctx)]
        (is (< events-after-first events-after-second)
            "changed blackboard should trigger rebuild")))))

(deftest first-build-emits-events-and-hash
  (testing "a fresh build emits events and stores a content hash"
    (h/with-test-context [ctx]
      (let [wf (simple-workflow)
            sheet-id (dsl/build-workflow! ctx wf)
            event-count (count-events ctx)
            sheet (rm/get-sheet ctx sheet-id)]
        (is (pos? event-count) "first build should emit events")
        (is (some? (:content-hash sheet)) "content hash should be stored")))))

(deftest referenced-blackboard-schema-changes-rebuild-effective-definition
  (testing "direct and transitive registry dependencies are captured; unrelated changes are ignored"
    (let [registry-before @schema-util/registry*
          item-schema :orc.build-idempotency-test/item
          items-schema :orc.build-idempotency-test/items
          unrelated-schema :orc.build-idempotency-test/unrelated]
      (try
        (schema-util/register!
         {item-schema [:map [:overview :string]]
          items-schema [:vector item-schema]
          unrelated-schema :string})
        (h/with-test-context [ctx]
          (let [wf (dsl/workflow "referenced-schema-idempotency-test"
                     (dsl/blackboard {:items items-schema}))
                sheet-id (dsl/build-workflow! ctx wf)
                initial-events (count-events ctx)]
            (is (= [:vector [:map [:overview :string]]]
                   (:schema (get (rm/get-blackboard-by-key ctx sheet-id) :items)))
                "the first build persists the effective schema snapshot")

            (schema-util/register! {unrelated-schema :int})
            (dsl/build-workflow! ctx wf)
            (is (= initial-events (count-events ctx))
                "an unrelated registry change is a zero-event no-op")

            (schema-util/register!
             {item-schema
              [:map
               [:overview
                [:or :string [:map [:credential-line :string]]]]]})
            (dsl/build-workflow! ctx wf)
            (is (< initial-events (count-events ctx))
                "a transitive registry dependency change rebuilds the sheet")
            (is (= [:vector
                    [:map
                     [:overview
                      [:or :string [:map [:credential-line :string]]]]]]
                   (:schema (get (rm/get-blackboard-by-key ctx sheet-id) :items)))
                "the rebuilt projection contains the new effective schema")))
        (finally
          (reset! schema-util/registry* registry-before))))))
