(ns ai.obney.orc.orc-service.rlm-tree-outcome-test
  "Tests for the tree-outcome push channel and surviving-vars merge.

   Background: a tree could finish :status :success with nil declared
   writes, and the only outcome signal in the model's iteration history
   was :vars-created — key-presence-based and nil-blind. The summary's
   :nil-writes existed but lived on a pull-only channel
   ((get-var :tree-results)). These tests pin the push channel:
   render-tree-outcome, the history rendering, and the surviving-vars
   collection that lets recovery trees resume instead of recompute."
  (:require [ai.obney.orc.orc-service.core.executor :as executor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; =============================================================================
;; render-tree-outcome
;; =============================================================================

(deftest render-outcome-surfaces-nil-writes-loudly
  (testing "a :success summary with nil-writes renders the nil keys with a warning"
    (let [summary {:status :success
                   :elapsed-ms 325097
                   :nodes-succeeded 15 :nodes-failed 0 :nodes-total 15
                   :outputs-keys [:report :key-dates :key-entities]
                   :outputs-previews {:report "nil"
                                      :key-dates "nil"
                                      :key-entities {:count 21 :sample-3 ["{...}"]}}
                   :nil-writes [:report :key-dates]}
          rendered (executor/render-tree-outcome summary)]
      (is (str/includes? rendered "status: :success"))
      (is (str/includes? rendered "NIL/EMPTY WRITES: :report, :key-dates"))
      (is (str/includes? rendered "did NOT land"))
      ;; nil-written keys are NOT listed among merged outputs
      (is (str/includes? rendered ":key-entities"))
      (let [merged-section (first (str/split rendered #"NIL/EMPTY"))]
        (is (not (str/includes? merged-section ":report")))))))

(deftest render-outcome-includes-failed-leaf-errors
  (testing "failed leaves render with node-id and error (which carries the raw preview)"
    (let [summary {:status :failure
                   :elapsed-ms 5000
                   :nodes-succeeded 12 :nodes-failed 1 :nodes-total 13
                   :outputs-keys []
                   :outputs-previews {}
                   :nil-writes [:report]
                   :failed-leaves [{:node-id #uuid "00000000-0000-0000-0000-00000000aaaa"
                                    :status :failure
                                    :error "LLM output unparseable for keys [:report] — ... preview: # Briefing Report ..."}]}
          rendered (executor/render-tree-outcome summary)]
      (is (str/includes? rendered "status: :failure"))
      (is (str/includes? rendered "Failed leaves:"))
      (is (str/includes? rendered "00000000-0000-0000-0000-00000000aaaa"))
      (is (str/includes? rendered "unparseable for keys [:report]")))))

(deftest render-outcome-clean-success-has-no-warnings
  (testing "all writes landed → no NIL/EMPTY or Failed leaves sections"
    (let [summary {:status :success
                   :elapsed-ms 1000
                   :nodes-succeeded 4 :nodes-failed 0 :nodes-total 4
                   :outputs-keys [:answer]
                   :outputs-previews {:answer "A: 110\nB: 14"}
                   :nil-writes []}
          rendered (executor/render-tree-outcome summary)]
      (is (str/includes? rendered "status: :success"))
      (is (str/includes? rendered ":answer"))
      (is (not (str/includes? rendered "NIL/EMPTY")))
      (is (not (str/includes? rendered "Failed leaves"))))))

(deftest render-outcome-lists-surviving-vars
  (testing "surviving intermediate vars render with a do-not-recompute hint"
    (let [summary {:status :failure
                   :nodes-succeeded 13 :nodes-failed 1 :nodes-total 14
                   :outputs-keys []
                   :outputs-previews {}
                   :nil-writes [:report :key-dates :key-entities]
                   :surviving-vars [:all-facts :batches]}
          rendered (executor/render-tree-outcome summary)]
      (is (str/includes? rendered "Surviving intermediate vars"))
      (is (str/includes? rendered ":all-facts"))
      (is (str/includes? rendered "do NOT recompute")))))

;; =============================================================================
;; surviving-vars-from-events
;; =============================================================================

(defn- write-event [k v]
  {:event/type :sheet/execution-value-written :key k :value v})

(deftest surviving-vars-collects-intermediate-writes
  (testing "intermediate writes are collected; declared writes, inputs, and reserved keys excluded"
    (let [events [(write-event :batches ["b1" "b2"])
                  (write-event :all-facts "facts...")
                  (write-event :report nil)          ; declared write — excluded
                  (write-event :document-page-texts ["p1"]) ; researcher input — excluded
                  {:event/type :sheet/node-execution-completed :status :success}]
          surviving (executor/surviving-vars-from-events
                      events
                      [:report :key-dates :key-entities :document-page-texts
                       :tree-results :generated-tree :generated-tree-raw
                       :iteration-reasonings])]
      (is (= {:batches ["b1" "b2"] :all-facts "facts..."} surviving)))))

(deftest surviving-vars-last-write-wins
  (testing "the same key written twice keeps the later value"
    (let [events [(write-event :draft "v1")
                  (write-event :draft "v2")]]
      (is (= {:draft "v2"} (executor/surviving-vars-from-events events []))))))

;; =============================================================================
;; build-iteration-history renders :tree-outcome in place of Result
;; =============================================================================

(deftest history-renders-tree-outcome-over-result
  (testing "a history entry with :tree-outcome shows the outcome, not the compiled tree"
    (let [history [{:code "(emit-tree! [:sequence ...])"
                    :result "(sheet/sequence (sheet/code :fn #object[sci...]))"
                    :stdout ""
                    :error nil
                    :vars-created [:key-entities]
                    :tree-outcome "Tree executed — status: :success | nodes: 15/15 succeeded\nNIL/EMPTY WRITES: :report, :key-dates — these declared writes did NOT land"}]
          rendered (#'executor/build-iteration-history history)]
      (is (str/includes? rendered "NIL/EMPTY WRITES: :report, :key-dates"))
      (is (not (str/includes? rendered "sheet/sequence"))
          "the useless compiled-tree string is not shown")
      (is (str/includes? rendered "Variables created: :key-entities"))))

  (testing "non-tree iterations render Result unchanged"
    (let [history [{:code "(get-var :answer)"
                    :result "\"42\""
                    :stdout ""
                    :error nil
                    :vars-created []}]
          rendered (#'executor/build-iteration-history history)]
      (is (str/includes? rendered "Result: \"42\"")))))
