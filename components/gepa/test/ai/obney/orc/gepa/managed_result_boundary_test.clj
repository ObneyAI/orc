(ns ai.obney.orc.gepa.managed-result-boundary-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn- start! [ctx]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/start-optimization :sheet-id (random-uuid)
             :trainset [{"question" "q"}] :valset [{"question" "q"}]
             :config {:max-metric-calls 20}})
          [:command/result :optimization-id]))

(defn- candidate! [ctx opt]
  (get-in (tu/process-command! ctx
            {:command/name :gepa/create-candidate :optimization-id opt
             :instructions {"answer" "Respond"} :parent-ids [] :mutation-reason "fixture"})
          [:command/result :candidate-id]))

(defn- task [role index]
  {:call-id (random-uuid) :trace-id (random-uuid) :role role :example-index index
   :input {"question" "q"} :instructions {"answer" "Respond"}
   :metric-retry-safe? true :max-metric-attempts 3})

(defn- admit! [ctx opt boundary tasks]
  (let [boundary-id (random-uuid)]
    (assert (= :opened (:status (ledger/open-boundary! ctx
                                 {:optimization-id opt :boundary-id boundary-id
                                  :boundary boundary :tasks tasks}))))
    (mapv (fn [t]
            (let [owner {:optimization-id opt :boundary-id boundary-id :call-id (:call-id t)
                         :owner-id (random-uuid) :epoch 1 :attempt-id (random-uuid)}]
              (assert (= :owned (:status (ledger/claim! ctx owner))))
              (assert (= :dispatched (:status (ledger/dispatch! ctx (assoc owner :attempt-kind :evaluation)))))
              owner)) tasks)))

(defn- finish! [ctx owner t score]
  (assert (= :workflow-recorded (:status (ledger/record-workflow! ctx
            (assoc owner :result {:status :success :outputs {:answer "done"} :trace-id (:trace-id t)})))))
  (assert (= :metric-started (:status (ledger/begin-metric! ctx owner))))
  (assert (= :completed (:status (ledger/finish! ctx
            (assoc owner :result {:output {:answer "done"} :score score}))))))

(defn- facts [ctx type]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{type}})))

(deftest managed-candidate-publication-requires-completed-task-evidence
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) candidate (candidate! ctx opt) t (task :candidate 0)
          owner (first (admit! ctx opt {:kind :candidate :candidate-id candidate} [t]))
          command {:command/name :gepa/record-evaluation-result
                   :optimization-id opt :candidate-id candidate :scores [0.8]
                   :trace-ids [(:trace-id t)] :metric-calls 1
                   :accounting-mode :tasks :task-call-ids [(:call-id t)]}
          rejected (tu/process-command! ctx command)]
      (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category rejected)))
      (is (empty? (facts ctx :gepa/candidate-evaluated)))
      (finish! ctx owner t 0.8)
      (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx command))))
      (is (= [0.8] (:scores (first (facts ctx :gepa/candidate-evaluated)))))
      (is (= 1 (:spent (ledger/get-accounting ctx opt)))))))

(deftest managed-candidate-rejects-altered-membership-and-evidence
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) candidate (candidate! ctx opt)
          tasks [(task :candidate 0) (task :candidate 1)]
          owners (admit! ctx opt {:kind :candidate :candidate-id candidate} tasks)
          other-candidate (candidate! ctx opt) other (task :candidate 0)
          other-owner (first (admit! ctx opt {:kind :candidate :candidate-id other-candidate} [other]))
          command {:command/name :gepa/record-evaluation-result
                   :optimization-id opt :candidate-id candidate :scores [0.2 0.8]
                   :trace-ids (mapv :trace-id tasks) :metric-calls 2
                   :accounting-mode :tasks :task-call-ids (mapv :call-id tasks)}]
      (doseq [[owner t score] (map vector owners tasks [0.2 0.8])]
        (finish! ctx owner t score))
      (finish! ctx other-owner other 0.2)
      (doseq [changes [{:task-call-ids [(:call-id (first tasks))] :scores [0.2]
                        :trace-ids [(:trace-id (first tasks))] :metric-calls 1}
                       {:task-call-ids (vec (repeat 2 (:call-id (first tasks))))}
                       {:task-call-ids [(:call-id other) (:call-id (second tasks))]}
                       {:scores [0.9 0.8]}
                       {:scores [0.8 0.2] :trace-ids (vec (reverse (:trace-ids command)))}
                       {:trace-ids [(random-uuid) (:trace-id (second tasks))]}
                       {:metric-calls 1}]]
        (is (= :cognitect.anomalies/conflict
               (:cognitect.anomalies/category (tu/process-command! ctx (merge command changes))))
            (pr-str changes))
        (is (empty? (facts ctx :gepa/candidate-evaluated))))
      (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx command))))
      (is (= [0.2 0.8] (:scores (first (facts ctx :gepa/candidate-evaluated))))))))

(deftest managed-subsample-compares-original-role-and-example-evidence
  (doseq [skip? [false true]]
    (tu/with-test-context [ctx]
      (let [opt (start! ctx) parent (candidate! ctx opt)
            boundary {:kind :subsample :parent-id parent :iteration 1}
            indices [2 0]
            proposed-instructions {"answer" "Improve"}
            parent-tasks (mapv #(task :subsample-parent %) indices)
            proposal-tasks (if skip? [] (mapv #(assoc (task :subsample-proposed %)
                                                    :instructions proposed-instructions) indices))
            parent-owners (admit! ctx opt boundary parent-tasks)
            proposal-owners (when-not skip? (admit! ctx opt boundary proposal-tasks))
            parent-scores (if skip? [1.0 1.0] [0.4 0.1])
            proposed-scores (if skip? parent-scores [0.9 0.2])
            tasks (into parent-tasks proposal-tasks)
            command {:command/name :gepa/record-subsample-result
                     :optimization-id opt :parent-id parent :iteration 1
                     :proposed-instructions proposed-instructions :component-updated "answer"
                     :subsample-indices indices :parent-scores parent-scores
                     :proposed-scores proposed-scores :accepted? (not skip?)
                     :parent-perfect-skip? skip? :metric-calls (count tasks)
                     :accounting-mode :tasks :task-call-ids (mapv :call-id tasks)}]
        (doseq [[owner t score] (map vector (concat parent-owners proposal-owners)
                                    tasks (concat parent-scores (when-not skip? proposed-scores)))]
          (finish! ctx owner t score))
        (doseq [changes (concat [{:parent-scores [0.0 0.0]}
                                 {:proposed-scores [0.0 0.0]}
                                 {:accepted? skip?}
                                 {:metric-calls 0}
                                 {:task-call-ids (vec (rest (:task-call-ids command)))}]
                                (if skip? [{:parent-perfect-skip? false}]
                                  [{:subsample-indices [0 2]}
                                   {:proposed-instructions {"answer" "Other proposal"}}]))]
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category (tu/process-command! ctx (merge command changes))))
              (pr-str [skip? changes]))
          (is (empty? (facts ctx :gepa/subsample-evaluated))))
        (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx command))))
        (let [published (first (facts ctx :gepa/subsample-evaluated))]
          (is (= parent-scores (:parent-scores published)))
          (is (= proposed-scores (:proposed-scores published)))
          (is (= skip? (:parent-perfect-skip? published))))))))

(deftest managed-work-cannot-be-bypassed-or-discarded-by-legacy-commands
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) baseline (candidate! ctx opt)
          _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                     :optimization-id opt :candidate-id baseline
                                     :scores [0.2] :trace-ids [(random-uuid)] :metric-calls 1})
          candidate (candidate! ctx opt) t (task :candidate 0)
          owner (first (admit! ctx opt {:kind :candidate :candidate-id candidate} [t]))
          legacy {:command/name :gepa/record-task-call :optimization-id opt
                  :call-id (:call-id t) :role :candidate :example-index 0
                  :trace-id (:trace-id t) :output {:answer "done"} :score 0.8}
          complete {:command/name :gepa/complete-optimization :optimization-id opt}]
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (tu/process-command! ctx legacy))))
      (is (empty? (facts ctx :gepa/task-call-completed)))
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (tu/process-command! ctx complete))))
      (is (empty? (facts ctx :gepa/optimization-completed)))
      (finish! ctx owner t 0.8)
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (tu/process-command! ctx legacy)))
          "Even identical managed replay belongs to ledger finish")
      (is (= 1 (count (facts ctx :gepa/task-call-completed))))
      (is (nil? (:cognitect.anomalies/category
                  (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                           :optimization-id opt :candidate-id candidate :scores [0.8]
                                           :trace-ids [(:trace-id t)] :metric-calls 1
                                           :accounting-mode :tasks :task-call-ids [(:call-id t)]}))))
      (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx complete))))
      (is (= candidate (:best-candidate-id (first (facts ctx :gepa/optimization-completed))))))))

(deftest managed-candidate-cannot-opt-out-of-evidence-validation
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) candidate (candidate! ctx opt) t (task :candidate 0)
          _ (admit! ctx opt {:kind :candidate :candidate-id candidate} [t])
          result (tu/process-command! ctx
                   {:command/name :gepa/record-evaluation-result
                    :optimization-id opt :candidate-id candidate
                    :scores [0.8] :trace-ids [(:trace-id t)] :metric-calls 1})]
      (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
      (is (empty? (facts ctx :gepa/candidate-evaluated)))
      (is (= 1 (:spent (ledger/get-accounting ctx opt)))))))

(deftest managed-aggregate-preserves-immutable-output-and-feedback
  (doseq [forged [{:outputs {0 {:answer "forged"}}}
                  {:feedbacks {0 "Invented diagnosis"}}]]
    (tu/with-test-context [ctx]
      (let [opt (start! ctx) candidate (candidate! ctx opt) t (task :candidate 0)
            owner (first (admit! ctx opt {:kind :candidate :candidate-id candidate} [t]))
            command {:command/name :gepa/record-evaluation-result
                     :optimization-id opt :candidate-id candidate :scores [0.8]
                     :trace-ids [(:trace-id t)] :metric-calls 1
                     :accounting-mode :tasks :task-call-ids [(:call-id t)]}]
        (ledger/record-workflow! ctx (assoc owner :result {:status :success
                                                        :outputs {:answer "done"}
                                                        :trace-id (:trace-id t)}))
        (ledger/begin-metric! ctx owner)
        (ledger/finish! ctx (assoc owner :result {:output {:answer "done"} :score 0.8
                                                :feedback "Original diagnosis.\nKeep exact evidence."}))
        (is (= :cognitect.anomalies/conflict
               (:cognitect.anomalies/category (tu/process-command! ctx (merge command forged)))))
        (is (empty? (facts ctx :gepa/candidate-evaluated)))
        (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx command))))
        (let [result (first (facts ctx :gepa/candidate-evaluated))]
          (is (= {0 {:answer "done"}} (:outputs result)))
          (is (= {0 "Original diagnosis.\nKeep exact evidence."} (:feedbacks result))))))))

(deftest managed-subsample-cannot-opt-out-of-evidence-validation
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) parent (candidate! ctx opt)
          _ (admit! ctx opt {:kind :subsample :parent-id parent :iteration 1}
                    [(task :subsample-parent 0)])
          result (tu/process-command! ctx
                   {:command/name :gepa/record-subsample-result
                    :optimization-id opt :parent-id parent :iteration 1
                    :proposed-instructions {"answer" "Improved"} :component-updated "answer"
                    :subsample-indices [0] :parent-scores [0.2] :proposed-scores [0.8]
                    :accepted? true :metric-calls 2})]
      (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
      (is (empty? (facts ctx :gepa/subsample-evaluated)))
      (is (= 1 (:spent (ledger/get-accounting ctx opt)))))))

(deftest aggregate-publication-arbitrates-with-boundary-admission
  (doseq [kind [:candidate :subsample] same-boundary? [true false]]
    (tu/with-test-context [ctx]
      (let [opt (start! ctx) candidate (candidate! ctx opt)
            boundary (if (= :candidate kind)
                       {:kind :candidate :candidate-id candidate}
                       {:kind :subsample :parent-id candidate :iteration 1})
            admitted-boundary (if same-boundary? boundary
                                {:kind :candidate :candidate-id (random-uuid)})
            event-type (if (= :candidate kind) :gepa/candidate-evaluated :gepa/subsample-evaluated)
            command (merge {:optimization-id opt :metric-calls 1}
                      (if (= :candidate kind)
                        {:command/name :gepa/record-evaluation-result :candidate-id candidate
                         :scores [0.8] :trace-ids [(random-uuid)]}
                        {:command/name :gepa/record-subsample-result :parent-id candidate :iteration 1
                         :proposed-instructions {"answer" "Improved"} :component-updated "answer"
                         :subsample-indices [0] :parent-scores [0.2] :proposed-scores [0.8]
                         :accepted? true}))
            original-append es/append
            intercepted (atom false)
            result (with-redefs [es/append
                                (fn [store request]
                                  (when (and (some #(= event-type (:event/type %)) (:events request))
                                             (compare-and-set! intercepted false true))
                                    (is (= :opened (:status
                                          (ledger/open-boundary! ctx
                                            {:optimization-id opt :boundary-id (random-uuid)
                                             :boundary admitted-boundary :tasks [(task :candidate 0)]})))))
                                  (original-append store request))]
                     (tu/process-command! ctx command))]
        (is @intercepted "Admission must occur after the command's preliminary reads")
        (if same-boundary?
          (do (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
              (is (empty? (facts ctx event-type))))
          (do (is (nil? (:cognitect.anomalies/category result)))
              (is (= 1 (count (facts ctx event-type))))))))))

(deftest completion-waits-for-a-ready-higher-candidate-aggregate
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) baseline (candidate! ctx opt)
          _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                     :optimization-id opt :candidate-id baseline
                                     :scores [0.2] :trace-ids [(random-uuid)] :metric-calls 1})
          candidate (candidate! ctx opt) t (task :candidate 0)
          owner (first (admit! ctx opt {:kind :candidate :candidate-id candidate} [t]))
          complete {:command/name :gepa/complete-optimization :optimization-id opt}]
      (finish! ctx owner t 0.8)
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category (tu/process-command! ctx complete))))
      (is (empty? (facts ctx :gepa/optimization-completed)))
      (is (nil? (:cognitect.anomalies/category
                  (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                           :optimization-id opt :candidate-id candidate :scores [0.8]
                                           :trace-ids [(:trace-id t)] :metric-calls 1
                                           :accounting-mode :tasks :task-call-ids [(:call-id t)]}))))
      (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx complete))))
      (is (= [candidate] (mapv :best-candidate-id (facts ctx :gepa/optimization-completed)))))))

(deftest aggregate-publication-cannot-mutate-a-terminal-optimization
  (doseq [kind [:candidate :subsample] timing [:before :append]]
    (tu/with-test-context [ctx]
      (let [opt (start! ctx) baseline (candidate! ctx opt) candidate (candidate! ctx opt)
            _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                       :optimization-id opt :candidate-id baseline
                                       :scores [0.2] :trace-ids [(random-uuid)] :metric-calls 1})
            event-type (if (= :candidate kind) :gepa/candidate-evaluated :gepa/subsample-evaluated)
            command (merge {:optimization-id opt :metric-calls 1}
                      (if (= :candidate kind)
                        {:command/name :gepa/record-evaluation-result :candidate-id candidate
                         :scores [0.8] :trace-ids [(random-uuid)]}
                        {:command/name :gepa/record-subsample-result :parent-id candidate :iteration 1
                         :proposed-instructions {"answer" "Improved"} :component-updated "answer"
                         :subsample-indices [0] :parent-scores [0.2] :proposed-scores [0.8]
                         :accepted? true}))
            terminal-command (if (= :before timing)
                               {:command/name :gepa/complete-optimization :optimization-id opt}
                               {:command/name :gepa/fail-optimization :optimization-id opt
                                :error-message "Application stopped optimization"})
            terminate! #(is (nil? (:cognitect.anomalies/category
                                    (tu/process-command! ctx terminal-command))))
            _ (when (= :before timing) (terminate!))
            original-append es/append
            intercepted (atom false)
            result (with-redefs [es/append
                                (fn [store request]
                                  (when (and (= :append timing)
                                             (some #(= event-type (:event/type %)) (:events request))
                                             (compare-and-set! intercepted false true))
                                    (terminate!))
                                  (original-append store request))]
                     (tu/process-command! ctx command))]
        (when (= :append timing) (is @intercepted))
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (empty? (filter #(or (= candidate (:candidate-id %))
                                (= candidate (:parent-id %))) (facts ctx event-type))))
        (when (= :before timing)
          (is (= baseline (:best-candidate-id
                            (ai.obney.orc.gepa.core.read-models/get-optimization-summary ctx opt)))))))))

(deftest completion-publishes-ready-subsamples-but-can-stop-an-incomplete-pair
  (doseq [mode [:full-pair :perfect-parent :incomplete-pair]]
    (tu/with-test-context [ctx]
      (let [tasks (cond-> [(task :subsample-parent 0)]
                    (= :full-pair mode) (conj (task :subsample-proposed 0)))
            opt (get-in (tu/process-command! ctx
                          {:command/name :gepa/start-optimization :sheet-id (random-uuid)
                           :trainset [{"question" "q"}] :valset [{"question" "q"}]
                           :config {:max-metric-calls (inc (count tasks))}})
                        [:command/result :optimization-id])
            baseline (candidate! ctx opt)
            _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                       :optimization-id opt :candidate-id baseline
                                       :scores [0.2] :trace-ids [(random-uuid)] :metric-calls 1})
            owners (admit! ctx opt {:kind :subsample :parent-id baseline :iteration 1} tasks)
            parent-score (if (= :perfect-parent mode) 1.0 0.2)
            complete {:command/name :gepa/complete-optimization :optimization-id opt}]
        (doseq [[owner t score] (map vector owners tasks [parent-score 0.8])]
          (finish! ctx owner t score))
        (is (= 0 (:available (ledger/get-accounting ctx opt))))
        (if (= :incomplete-pair mode)
          (do (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx complete))))
              (is (= [baseline] (mapv :best-candidate-id (facts ctx :gepa/optimization-completed)))))
          (do
            (is (= :cognitect.anomalies/conflict
                   (:cognitect.anomalies/category (tu/process-command! ctx complete))))
            (is (empty? (facts ctx :gepa/optimization-completed)))
            (is (nil? (:cognitect.anomalies/category
                        (tu/process-command! ctx
                          {:command/name :gepa/record-subsample-result
                           :optimization-id opt :parent-id baseline :iteration 1
                           :proposed-instructions {"answer" "Respond"} :component-updated "answer"
                           :subsample-indices [0] :parent-scores [parent-score]
                           :proposed-scores [(if (= :perfect-parent mode) 1.0 0.8)]
                           :accepted? (= :full-pair mode) :parent-perfect-skip? (= :perfect-parent mode)
                           :metric-calls (count tasks) :accounting-mode :tasks
                           :task-call-ids (mapv :call-id tasks)}))))
            (is (nil? (:cognitect.anomalies/category (tu/process-command! ctx complete))))
            (is (= 1 (count (facts ctx :gepa/subsample-evaluated))))))))))

(deftest failed-optimization-cannot-later-complete
  (tu/with-test-context [ctx]
    (let [opt (start! ctx) candidate (candidate! ctx opt)]
      (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                               :optimization-id opt :candidate-id candidate
                               :scores [0.8] :trace-ids [(random-uuid)] :metric-calls 1})
      (is (nil? (:cognitect.anomalies/category
                  (tu/process-command! ctx {:command/name :gepa/fail-optimization
                                           :optimization-id opt :error-message "Stopped"}))))
      (is (= :cognitect.anomalies/conflict
             (:cognitect.anomalies/category
               (tu/process-command! ctx {:command/name :gepa/complete-optimization
                                        :optimization-id opt}))))
      (is (empty? (facts ctx :gepa/optimization-completed)))
      (is (= 1 (count (facts ctx :gepa/optimization-failed)))))))

(deftest competing-terminal-commands-publish-one-terminal-event
  (doseq [outer-kind [:fail :complete]]
    (tu/with-test-context [ctx]
      (let [opt (start! ctx) candidate (candidate! ctx opt)
            _ (tu/process-command! ctx {:command/name :gepa/record-evaluation-result
                                       :optimization-id opt :candidate-id candidate
                                       :scores [0.8] :trace-ids [(random-uuid)] :metric-calls 1})
            complete {:command/name :gepa/complete-optimization :optimization-id opt}
            fail {:command/name :gepa/fail-optimization :optimization-id opt :error-message "Stopped"}
            [outer inner event-type] (if (= :fail outer-kind)
                                       [fail complete :gepa/optimization-failed]
                                       [complete fail :gepa/optimization-completed])
            original-append es/append intercepted (atom false)
            result (with-redefs [es/append
                                (fn [store request]
                                  (when (and (some #(= event-type (:event/type %)) (:events request))
                                             (compare-and-set! intercepted false true))
                                    (is (nil? (:cognitect.anomalies/category
                                                (tu/process-command! ctx inner)))))
                                  (original-append store request))]
                     (tu/process-command! ctx outer))]
        (is @intercepted)
        (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
        (is (= 1 (+ (count (facts ctx :gepa/optimization-completed))
                    (count (facts ctx :gepa/optimization-failed)))))))))
