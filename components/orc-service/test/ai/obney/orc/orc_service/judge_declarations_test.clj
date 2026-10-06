(ns ai.obney.orc.orc-service.judge-declarations-test
  "S6a: a judge is declared once under a name with a rubric, purposes and a
   model, and is REVISED (never re-declared) when its definition changes.
   Driven through the public commands and the judges read model."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def bands {1 "Poor: unsupported" 2 "Fair: partly supported" 3 "Good: fully supported"})

(defn- rubric
  ([] (rubric :required))
  ([feedback] {:criterion "Is every claim supported?" :stance "strict" :bands bands :feedback feedback}))

(defn- new-sheet! [ctx]
  (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name (str "s6a-" (random-uuid))))
      :command-result/events first :sheet-id))

(defn- declare! [ctx sheet-id jname config]
  (h/run-command ctx (h/make-declare-judge-command sheet-id jname config)))

(deftest declared-rubric-defaults-purposes-and-starts-at-revision-1
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          result (declare! ctx sheet-id "j" {:type :custom :sheet-id (random-uuid)
                                             :model "google/gemini-3-flash-preview"
                                             :timeout-ms 5000
                                             :rubric (rubric)})
          judge (rm/get-judge ctx sheet-id "j")]
      (is (= :sheet/judge-declared (h/get-event-type result)) (pr-str result))
      (is (= (rubric) (:rubric judge)))
      (is (= #{:monitoring :learning} (:purposes judge)))
      (is (= 1 (:revision-number judge)))
      (is (= "google/gemini-3-flash-preview" (:model judge)))
      (is (= 5000 (:timeout-ms judge))))))

(deftest feedback-none-rubric-defaults-to-monitoring-only
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)]
      (declare! ctx sheet-id "j" {:type :grounding :rubric (rubric :none)})
      (is (= #{:monitoring} (:purposes (rm/get-judge ctx sheet-id "j")))))))

(deftest explicit-purposes-are-kept-and-judges-without-new-fields-still-work
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)]
      (declare! ctx sheet-id "mon" {:type :grounding :rubric (rubric) :purposes #{:monitoring}})
      (declare! ctx sheet-id "old" {:type :grounding :criteria "c" :weight 0.5})
      (is (= #{:monitoring} (:purposes (rm/get-judge ctx sheet-id "mon"))))
      (let [old (rm/get-judge ctx sheet-id "old")]
        (is (= #{:monitoring :learning} (:purposes old)))
        (is (= 1 (:revision-number old)))
        (is (nil? (:rubric old)))
        (is (= "c" (:criteria old)))))))

(deftest invalid-declarations-are-rejected
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          rejected (fn [config]
                     (let [r (declare! ctx sheet-id (str (random-uuid)) config)]
                       (and (h/is-anomaly? r) (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category r)))))]
      (testing "learning judge must require feedback"
        (let [r (declare! ctx sheet-id "l" {:type :grounding :purposes #{:learning} :rubric (rubric :none)})]
          (is (h/is-anomaly? r))
          (is (re-find #"learning judge must require feedback" (:cognitect.anomalies/message r))))
        (is (rejected {:type :grounding :purposes #{:monitoring :learning} :rubric (rubric :none)})))
      (testing "bad rubrics"
        (is (rejected {:type :grounding :rubric (assoc (rubric) :bands {1 "a" 2 "  "})}) "blank band")
        (is (rejected {:type :grounding :rubric (assoc (rubric) :bands {1 "a" 3 "c"})}) "non-contiguous")
        (is (rejected {:type :grounding :rubric (assoc (rubric) :bands {1 "a"})}) "single band")
        (is (rejected {:type :grounding :rubric (assoc (rubric) :criterion "  ")}) "blank criterion"))
      (testing "bad purposes and timeout"
        (is (rejected {:type :grounding :purposes #{}}))
        (is (rejected {:type :grounding :timeout-ms 0}))))))

(deftest revise-judge-bumps-revision-and-keeps-history
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          v1 {:type :grounding :rubric (rubric)}
          v2 {:type :grounding :rubric (assoc (rubric) :criterion "Stricter criterion")}]
      (declare! ctx sheet-id "j" v1)
      (let [r (h/run-command ctx (h/make-revise-judge-command sheet-id "j" v2))
            judge (rm/get-judge ctx sheet-id "j")]
        (is (= :sheet/judge-revised (h/get-event-type r)) (pr-str r))
        (is (= 2 (:revision-number judge)))
        (is (= "Stricter criterion" (get-in judge [:rubric :criterion])))
        (is (= [{:revision-number 1 :judge-config v1} {:revision-number 2 :judge-config v2}]
               (:revisions judge)))
        (is (= 1 (count (rm/get-judges ctx sheet-id)))))
      (testing "revising to the identical definition is a conflict"
        (let [r (h/run-command ctx (h/make-revise-judge-command sheet-id "j" v2))]
          (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category r)))
          (is (= 2 (:revision-number (rm/get-judge ctx sheet-id "j"))))))
      (testing "type changes are allowed"
        (h/run-command ctx (h/make-revise-judge-command sheet-id "j" {:type :reasoning :rubric (rubric)}))
        (is (= :reasoning (:type (rm/get-judge ctx sheet-id "j"))))
        (is (= 3 (:revision-number (rm/get-judge ctx sheet-id "j")))))
      (testing "an invalid revision is rejected and changes nothing"
        (let [r (h/run-command ctx (h/make-revise-judge-command
                                    sheet-id "j" {:type :grounding :purposes #{:learning} :rubric (rubric :none)}))]
          (is (h/is-anomaly? r))
          (is (= 3 (:revision-number (rm/get-judge ctx sheet-id "j")))))))))

(deftest revising-an-undeclared-judge-is-not-found
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          r (h/run-command ctx (h/make-revise-judge-command sheet-id "ghost" {:type :grounding}))]
      (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category r))))))

(deftest revised-custom-judge-keeps-eval-sheet-id
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          eval-1 (random-uuid) eval-2 (random-uuid)]
      (declare! ctx sheet-id "c" {:type :custom :sheet-id eval-1})
      (is (= eval-1 (:eval-sheet-id (rm/get-judge ctx sheet-id "c"))))
      (h/run-command ctx (h/make-revise-judge-command sheet-id "c" {:type :custom :sheet-id eval-2}))
      (let [j (rm/get-judge ctx sheet-id "c")]
        (is (= eval-2 (:eval-sheet-id j)))
        (is (= sheet-id (:sheet-id j)) "host sheet id still partitions the judge")
        (is (= 2 (:revision-number j))))
      (testing "a custom type needs its eval sheet on revision too"
        (is (h/is-anomaly? (h/run-command ctx (h/make-revise-judge-command sheet-id "c" {:type :custom :criteria "x"}))))))))

(deftest judge-history-is-reproduced-from-the-event-store-in-a-fresh-context
  (h/with-test-context [ctx]
    (let [sheet-id (new-sheet! ctx)
          v1 {:type :grounding :rubric (rubric)}
          v2 {:type :grounding :model "m/x" :rubric (assoc (rubric) :criterion "Stricter")}]
      (declare! ctx sheet-id "j" v1)
      (h/run-command ctx (h/make-revise-judge-command sheet-id "j" v2))
      (let [live (rm/get-judge ctx sheet-id "j")
            ;; Fresh L1 + fresh LMDB cache over the SAME event store: the
            ;; projection must be rebuilt purely from the stored events.
            fresh (h/create-test-context)]
        (try
          (let [rebuilt (rm/get-judge (assoc fresh :event-store (:event-store ctx)) sheet-id "j")]
            (is (= 2 (:revision-number rebuilt)))
            (is (= [{:revision-number 1 :judge-config v1} {:revision-number 2 :judge-config v2}]
                   (:revisions rebuilt)))
            (is (= live rebuilt)))
          (finally (h/stop-context fresh)))))))

;; =============================================================================
;; DSL: sheet/judges carries the new fields; rebuilds revise instead of failing
;; =============================================================================

(defn- workflow-with-judge [judge-config & {:keys [instruction] :or {instruction "Process"}}]
  (dsl/workflow "s6a-dsl-judges"
    (dsl/blackboard {:input :string :output :string})
    (dsl/judges {:grounding-judge judge-config})
    (dsl/sequence "main"
      (dsl/llm "process" :model "test/model" :instruction instruction
        :reads [:input] :writes [:output] :judges ["grounding-judge"]))))

(defn- count-events [ctx]
  (count (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))))

(deftest dsl-judges-carry-rubric-purposes-model-and-timeout
  (h/with-test-context [ctx]
    (let [config {:type :grounding :rubric (rubric) :purposes #{:monitoring}
                  :model "google/gemini-3-flash-preview" :timeout-ms 9000}
          sheet-id (dsl/build-workflow! ctx (workflow-with-judge config))
          judge (rm/get-judge ctx sheet-id "grounding-judge")]
      (is (= (rubric) (:rubric judge)))
      (is (= #{:monitoring} (:purposes judge)))
      (is (= "google/gemini-3-flash-preview" (:model judge)))
      (is (= 9000 (:timeout-ms judge)))
      (is (= 1 (:revision-number judge))))))

(deftest rebuild-with-edited-rubric-revises-the-judge
  (h/with-test-context [ctx]
    (let [v1 {:type :grounding :rubric (rubric)}
          v2 {:type :grounding :rubric (assoc (rubric) :criterion "Edited criterion")}
          sheet-id (dsl/build-workflow! ctx (workflow-with-judge v1))
          _ (dsl/build-workflow! ctx (workflow-with-judge v2))
          judge (rm/get-judge ctx sheet-id "grounding-judge")]
      (is (= 2 (:revision-number judge)))
      (is (= "Edited criterion" (get-in judge [:rubric :criterion])))
      (is (= [1 2] (mapv :revision-number (:revisions judge)))))))

(deftest rebuild-changing-only-the-tree-keeps-the-judge-revision
  (h/with-test-context [ctx]
    (let [v1 {:type :grounding :rubric (rubric)}
          sheet-id (dsl/build-workflow! ctx (workflow-with-judge v1))
          _ (dsl/build-workflow! ctx (workflow-with-judge v1 :instruction "A different instruction"))]
      (is (= 1 (:revision-number (rm/get-judge ctx sheet-id "grounding-judge")))
          "an unchanged judge is not a new revision"))))

(deftest rebuild-unchanged-with-judges-is-a-noop
  (h/with-test-context [ctx]
    (let [wf (workflow-with-judge {:type :grounding :rubric (rubric)})
          _ (dsl/build-workflow! ctx wf)
          before (count-events ctx)]
      (dsl/build-workflow! ctx wf)
      (is (= before (count-events ctx))))))

(deftest judges-survive-export-dsl-and-import-round-trips
  (h/with-test-context [ctx]
    (let [config {:type :grounding :rubric (rubric) :purposes #{:monitoring}
                  :model "google/gemini-3-flash-preview" :timeout-ms 9000}
          source-id (dsl/build-workflow! ctx (workflow-with-judge config))
          exported (dsl/export-sheet ctx source-id)
          regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                        (eval (read-string (dsl/export-to-dsl exported))))
          imported-id (dsl/import-sheet ctx (assoc-in exported [:sheet :name] "s6a-imported"))]
      (is (= config (get-in exported [:judges-schema :grounding-judge])))
      (is (= config (get-in regenerated [:judges-schema :grounding-judge])))
      (is (= config (:judge-config (peek (:revisions (rm/get-judge ctx imported-id "grounding-judge")))))))))
