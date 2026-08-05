(ns ai.obney.orc.orc-service.partition-identity-test
  "Regression tests for sheet-local entity identities in partitioned read models."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- create-sheet! [ctx name]
  (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name name))
      :command-result/events first :sheet-id))

(deftest same-named-blackboard-keys-remain-isolated
  (h/with-test-context [ctx]
    (testing "a declaration in a later sheet does not evict the earlier schema"
      (let [sheet-a (create-sheet! ctx "Vector sheet")
            sheet-b (create-sheet! ctx "String sheet")]
        (h/run-and-apply! ctx
                          (h/make-declare-key-command sheet-a :shared [:vector :string]))
        ;; Warm the first partition before adding the colliding name. This
        ;; exercises Grain's incremental single-partition path as well as the
        ;; initial projection path used by normal workflow builds.
        (is (= [:vector :string]
               (get-in (rm/get-blackboard-by-key ctx sheet-a) [:shared :schema])))

        (h/run-and-apply! ctx
                          (h/make-declare-key-command sheet-b :shared :string))
        (h/run-and-apply! ctx
                          (h/make-set-key-value-command sheet-a :shared ["one" "two"]))
        (h/run-and-apply! ctx
                          (h/make-set-key-value-command sheet-b :shared "prose"))

        (is (= [:vector :string]
               (get-in (rm/get-blackboard-by-key ctx sheet-a) [:shared :schema])))
        (is (= [:vector :string]
               (get-in (executor/build-module
                        {:name "structured-writer" :reads [] :writes [:shared]}
                        (rm/get-blackboard-by-key ctx sheet-a))
                       [:outputs 0 :spec])))
        (is (= ["one" "two"]
               (get-in (rm/get-blackboard-by-key ctx sheet-a) [:shared :value])))
        (is (= :string
               (get-in (rm/get-blackboard-by-key ctx sheet-b) [:shared :schema])))
        (is (= "prose"
               (get-in (rm/get-blackboard-by-key ctx sheet-b) [:shared :value])))

        (h/run-and-apply! ctx (h/make-delete-key-command sheet-b :shared))
        (is (empty? (rm/get-blackboard-by-key ctx sheet-b)))
        (is (= [:vector :string]
               (get-in (rm/get-blackboard-by-key ctx sheet-a) [:shared :schema])))))))

(deftest same-named-judges-remain-isolated
  (h/with-test-context [ctx]
    (testing "judge names are local to their host sheet"
      (let [sheet-a (create-sheet! ctx "Grounding sheet")
            sheet-b (create-sheet! ctx "Reasoning sheet")]
        (h/run-and-apply! ctx
                          (h/make-declare-judge-command
                           sheet-a "quality" {:type :grounding
                                              :criteria "Cite sources"}))
        (is (= :grounding (get-in (rm/get-judges ctx sheet-a) ["quality" :type])))

        (h/run-and-apply! ctx
                          (h/make-declare-judge-command
                           sheet-b "quality" {:type :reasoning
                                              :criteria "Show the logic"}))

        (is (= {:type :grounding :criteria "Cite sources"}
               (select-keys (get (rm/get-judges ctx sheet-a) "quality")
                            [:type :criteria])))
        (is (= {:type :reasoning :criteria "Show the logic"}
               (select-keys (get (rm/get-judges ctx sheet-b) "quality")
                            [:type :criteria])))))))
