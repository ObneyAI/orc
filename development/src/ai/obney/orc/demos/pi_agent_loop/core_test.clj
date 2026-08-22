(ns ai.obney.orc.demos.pi-agent-loop.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [ai.obney.orc.demos.pi-agent-loop.core :as loop]
            [ai.obney.orc.demos.pi-agent-loop.tools :as demo-tools]
            [ai.obney.orc.demos.pi-agent-loop.runtime :as demo-runtime]
            [ai.obney.orc.demos.pi-agent-loop.model :as demo-model]
            [ai.obney.orc.demos.pi-agent-loop.events :as events]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.read-models :as read-models]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [litellm.router :as router]))

(defn- user [text] {:role :user :content text})
(defn- assistant
  ([text] (assistant text [] :stop))
  ([text tool-calls stop-reason]
   {:role :assistant :content text :tool-calls tool-calls :stop-reason stop-reason}))

(defn echo-tool-workflow [{:keys [inputs]}]
  {:answer (str "orc:" (:query inputs))})

(defonce ^:private controlled-calls (atom {}))
(defonce ^:private node-effects (atom []))

(defn controlled-tool-workflow [{:keys [inputs]}]
  (let [{:keys [started release]} (get @controlled-calls (:query inputs))]
    (swap! node-effects conj [:controlled (:query inputs)])
    (when started (deliver started true))
    (when release @release)
    {:answer (str "orc:" (:query inputs))}))

(defn blocking-first-node [{:keys [inputs]}]
  (let [{:keys [started release]} (get @controlled-calls (:query inputs))]
    (swap! node-effects conj [:first (:query inputs)])
    (deliver started true)
    @release
    {:middle (:query inputs)}))

(defn forbidden-later-node [{:keys [inputs]}]
  (swap! node-effects conj [:later (:middle inputs)])
  {:answer (str "late:" (:middle inputs))})

(defn failing-tool-workflow [_]
  (throw (ex-info "durable tool failure" {:source :pi-demo-test})))

(defn- test-fq [name]
  (str "ai.obney.orc.demos.pi-agent-loop.core-test/" name))

(defn- all-events [ctx]
  (into [] (event-store/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(deftest simple-prompt-matches-pi-lifecycle
  (let [model-calls (atom [])
        result (loop/run {:messages []
                          :prompts [(user "hello")]
                          :model-turn (fn [messages _]
                                        (swap! model-calls conj messages)
                                        (assistant "hi"))})]
    (is (= [[(user "hello")]] @model-calls))
    (is (= [(user "hello") (assistant "hi")] (:new-messages result)))
    (is (= [:agent-start :turn-start
            :message-start :message-end
            :message-start :message-end
           :turn-end :agent-end]
           (mapv :type (:events result))))
    (is (= "hi" (->> (:events result)
                      (filter #(and (= :message-start (:type %))
                                    (= :assistant (get-in % [:message :role]))))
                      first :message :content))
        "a non-fragmented Pi response starts with its completed message")))

(deftest context-is-transformed-before-model-conversion
  (let [steps (atom [])]
    (loop/run {:prompts [(user "raw")]
               :transform-context (fn [messages]
                                    (swap! steps conj :transform)
                                    (mapv #(update % :content str "-transformed") messages))
               :convert-to-model-messages (fn [messages]
                                            (swap! steps conj :convert)
                                            (mapv #(update % :content str "-converted") messages))
               :model-turn (fn [messages _]
                             (swap! steps conj [:model (:content (first messages))])
                             (assistant "done"))})
    (is (= [:transform :convert [:model "raw-transformed-converted"]] @steps))))

(deftest abort-and-provider-error-have-one-terminal-turn
  (doseq [result [(loop/run {:prompts [(user "abort")]
                             :aborted? (constantly true)
                             :model-turn (fn [_ _] (throw (Exception. "unreachable")))})
                  (loop/run {:messages [(user "existing")]
                             :prompts [(user "new")]
                             :model-turn (fn [_ _] (assistant "failed" [] :error))})]]
    (is (= 1 (count (filter #(= :turn-end (:type %)) (:events result)))))
    (is (= 1 (count (filter #(= :agent-end (:type %)) (:events result))))))
  (let [result (loop/run {:messages [(user "existing")]
                          :prompts [(user "new")]
                          :model-turn (fn [_ _] (assistant "done"))})]
    (is (= ["new" "done"] (mapv :content (:new-messages result))))))

(deftest terminal-provider-turn-skips-next-turn-hooks
  (let [called (atom [])
        result (loop/run {:prompts [(user "fail")]
                          :prepare-next-turn #(swap! called conj [:prepare %])
                          :should-stop-after-turn? #(do (swap! called conj [:stop %]) true)
                          :model-turn (fn [_ _] (assistant "failed" [] :error))})]
    (is (= [] @called))
    (is (= :error (:status result)))))

(deftest tool-result-is-fed-to-a-later-model-turn
  (let [calls (atom 0)
        tool-call {:id "call-1" :name "lookup" :arguments {:key "answer"}}
        result (loop/run
                {:messages []
                 :prompts [(user "look it up")]
                 :tools {"lookup" {:execute (fn [_] {:content "nonce-417"})}}
                 :model-turn
                 (fn [messages _]
                   (case (swap! calls inc)
                     1 (assistant "" [tool-call] :tool-use)
                     2 (do (is (= :tool-result (:role (last messages))))
                           (is (= "nonce-417" (:content (last messages))))
                           (assistant "nonce-417"))))})]
    (is (= 2 @calls))
    (is (= [:user :assistant :tool-result :assistant]
           (mapv :role (:new-messages result))))
    (is (= [:tool-execution-start :tool-execution-end]
           (->> (:events result)
                (filter #(#{:tool-execution-start :tool-execution-end} (:type %)))
                (mapv :type))))))

(deftest tool-body-arity-errors-do-not-retry-side-effects
  (let [executions (atom 0)
        turns (atom 0)
        result (loop/run
                {:prompts [(user "run once")]
                 :tools {"once" {:execute (fn [_]
                                            (swap! executions inc)
                                            (throw (clojure.lang.ArityException. 1 "tool-body")))}}
                 :model-turn (fn [_ _]
                               (case (swap! turns inc)
                                 1 (assistant "" [{:id "once-1" :name "once"
                                                   :arguments {}}] :tool-use)
                                 2 (assistant "recovered")))})]
    (is (= 1 @executions))
    (is (= true (:error (nth (:new-messages result) 2))))))

(deftest length-truncated-tool-call-never-executes
  (let [executions (atom 0)
        turns (atom 0)
        result (loop/run
                {:messages []
                 :prompts [(user "do work")]
                 :tools {"mutate" {:execute (fn [_] (swap! executions inc))}}
                 :model-turn
                 (fn [_ _]
                   (case (swap! turns inc)
                     1 (assistant "" [{:id "bad" :name "mutate" :arguments {}}] :length)
                     2 (assistant "recovered")))})]
    (is (zero? @executions))
    (is (= true (:error (nth (:new-messages result) 2))))))

(deftest steering-waits-until-the-tool-batch-finishes
  (let [steering (atom [])
        turns (atom 0)
        result (loop/run
                {:messages []
                 :prompts [(user "start")]
                 :tools {"one" {:execute (fn [_]
                                            (reset! steering [(user "change course")])
                                            {:content "one"})}
                         "two" {:execute (fn [_] {:content "two"})}}
                 :take-steering! (fn [] (let [x @steering] (reset! steering []) x))
                 :model-turn
                 (fn [messages _]
                   (case (swap! turns inc)
                     1 (assistant "" [{:id "1" :name "one" :arguments {}}
                                       {:id "2" :name "two" :arguments {}}] :tool-use)
                     2 (do (is (= [:tool-result :tool-result :user]
                                  (mapv :role (take-last 3 messages))))
                           (assistant "changed"))))})]
    (is (= 2 @turns))
    (is (= "change course" (:content (nth (:new-messages result) 4))))))

(deftest follow-up-prevents-agent-end-until-consumed
  (let [follow-ups (atom [[(user "and another thing")]])
        turns (atom 0)
        result (loop/run
                {:messages []
                 :prompts [(user "first")]
                 :take-follow-ups! (fn []
                                     (let [x (first @follow-ups)]
                                       (swap! follow-ups #(vec (rest %)))
                                       (or x [])))
                 :model-turn (fn [messages _]
                               (swap! turns inc)
                               (assistant (str "reply-" (count messages))))})]
    (is (= 2 @turns))
    (is (= 1 (count (filter #(= :agent-end (:type %)) (:events result)))))))

(deftest parallel-completions-are-live-order-but-results-are-source-order
  (let [release-slow (promise)
        slow-started (promise)
        turns (atom 0)
        result (loop/run
                {:prompts [(user "parallel")]
                 :tools {"slow" {:execute (fn [_] (deliver slow-started true)
                                             @release-slow {:content "slow"})}
                         "fast" {:execute (fn [_] @slow-started
                                             (deliver release-slow true)
                                             {:content "fast"})}}
                 :model-turn (fn [_ _]
                               (case (swap! turns inc)
                                 1 (assistant "" [{:id "slow" :name "slow" :arguments {}}
                                                   {:id "fast" :name "fast" :arguments {}}] :tool-use)
                                 2 (assistant "done")))})
        completions (filter #(= :tool-execution-end (:type %)) (:events result))
        results (filter #(= :tool-result (:role %)) (:new-messages result))]
    (is (= ["fast" "slow"] (mapv :tool-call-id completions)))
    (is (= ["slow" "fast"] (mapv :tool-call-id results)))))

(deftest one-sequential-tool-forces-whole-batch-sequential
  (let [order (atom []) turns (atom 0)]
    (loop/run {:prompts [(user "ordered")]
               :tools {"a" {:execute (fn [_] (swap! order conj :a) {:content "a"})}
                       "b" {:execution-mode :sequential
                            :execute (fn [_] (swap! order conj :b) {:content "b"})}}
               :model-turn (fn [_ _]
                             (case (swap! turns inc)
                               1 (assistant "" [{:id "a" :name "a" :arguments {}}
                                                 {:id "b" :name "b" :arguments {}}] :tool-use)
                               2 (assistant "done")))})
    (is (= [:a :b] @order))))

(deftest hooks-prepare-validate-block-transform-and-terminate
  (let [executed (atom []) turns (atom 0)
        result (loop/run
                {:prompts [(user "hooks")]
                 :config {:before-tool-call (fn [{:keys [tool-call arguments]}]
                                              (case (:name tool-call)
                                                "blocked" {:block true :reason "policy"
                                                           :terminate true}
                                                "ok" {:arguments (assoc arguments :n 99)}
                                                nil))
                          :after-tool-call (fn [{:keys [result]}]
                                             (assoc result :content (str (:content result) "!")))}
                 :tools {"ok" {:prepare-arguments #(update % :n inc)
                                :validate-arguments (fn [args]
                                                      (when-not (= 2 (:n args))
                                                        (throw (ex-info "bad args" {})))
                                                      args)
                                :execute (fn [args] (swap! executed conj args)
                                           {:content "ok" :terminate true})}
                         "blocked" {:execute (fn [_] (throw (Exception. "must not run")))}}
                 :model-turn (fn [_ _]
                               (swap! turns inc)
                               (assistant "" [{:id "1" :name "ok" :arguments {:n 1}}
                                               {:id "2" :name "blocked" :arguments {}}]
                                          :tool-use))})]
    (is (= [{:n 99}] @executed)
        "the pre-call hook changes validated args without revalidation")
    (is (= 1 @turns) "unanimously terminating results stop the loop")
    (is (= ["ok!" "policy"]
           (mapv :content (filter #(= :tool-result (:role %)) (:new-messages result)))))))

(deftest mixed-termination-continues
  (let [turns (atom 0)]
    (loop/run {:prompts [(user "mixed")]
               :tools {"stop" {:execute (fn [_] {:content "s" :terminate true})}
                       "go" {:execute (fn [_] {:content "g"})}}
               :model-turn (fn [_ _]
                             (case (swap! turns inc)
                               1 (assistant "" [{:id "s" :name "stop" :arguments {}}
                                                 {:id "g" :name "go" :arguments {}}] :tool-use)
                               2 (assistant "continued")))})
    (is (= 2 @turns))))

(deftest streaming-updates-and-event-sequences-are-well-formed
  (let [result (loop/run
                {:prompts [(user "stream")]
                 :model-turn (fn [_ _]
                               {:initial-message (assistant "" [] nil)
                                :updates [{:kind :text-delta :delta "hel"}
                                          {:kind :text-delta :delta "lo"}]
                                :message (assistant "hello")})})
        types (mapv :type (:events result))
        start (.indexOf types :message-start)
        updates (keep-indexed #(when (= :message-update %2) %1) types)
        end (last (keep-indexed #(when (= :message-end %2) %1) types))]
    (is (every? #(< start % end) updates))
    (is (= (range 1 (inc (count (:events result))))
           (map :sequence-number (:events result))))))

(deftest tool-updates-retain-pi-shape-and-live-position
  (let [turns (atom 0)
        result (loop/run
                {:prompts [(user "tool update")]
                 :config {:tool-execution :sequential}
                 :tools {"progress" {:prepare-arguments #(update % :step inc)
                                      :execute-with-updates? true
                                      :execute (fn [_ update!]
                                                 (update! {:percent 50})
                                                 {:content "done"})}}
                 :model-turn (fn [_ _]
                               (case (swap! turns inc)
                                 1 (assistant "" [{:id "progress-1" :name "progress"
                                                   :arguments {:step 0}}] :tool-use)
                                 2 (assistant "complete")))})
        tool-events (filter #(#{:tool-execution-start :tool-execution-update
                                :tool-execution-end} (:type %)) (:events result))
        update-event (second tool-events)]
    (is (= [:tool-execution-start :tool-execution-update :tool-execution-end]
           (mapv :type tool-events)))
    (is (= {:step 1} (:arguments update-event)))
    (is (= {:percent 50} (:partial-result update-event)))))

(deftest continuation-validation-and-no-duplicate-input-events
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no messages"
                        (loop/continue-run {:messages [] :model-turn (constantly nil)})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assistant"
                        (loop/continue-run {:messages [(assistant "tail")]
                                            :model-turn (constantly nil)})))
  (let [existing [(user "existing")]
        result (loop/continue-run {:messages existing
                                   :model-turn (fn [_ _] (assistant "continued"))})]
    (is (= [(assistant "continued")] (:new-messages result)))
    (is (= 2 (count (filter #(#{:message-start :message-end} (:type %))
                            (:events result)))))))

(deftest prepare-next-turn-and-stop-after-turn
  (let [configs (atom []) turns (atom 0)
        result (loop/run
                {:prompts [(user "config")]
                 :prepare-next-turn (fn [_] {:config {:model :second}})
                 :should-stop-after-turn? (fn [_] (= 2 @turns))
                 :tools {"go" {:execute (fn [_] {:content "go"})}}
                 :model-turn (fn [_ config]
                               (swap! configs conj config)
                               (case (swap! turns inc)
                                 1 (assistant "" [{:id "g" :name "go" :arguments {}}] :tool-use)
                                 2 (assistant "stop")))})]
    (is (= [{} {:model :second}] @configs))
    (is (= :agent-end (:type (last (:events result)))))))

(deftest tool-effect-crosses-real-orc-workflow-and-retains-provenance
  (h/with-async-test-context [ctx]
    (let [definition (orc/workflow "pi-loop-demo-deterministic-tool"
                       (orc/blackboard {:query :string :answer :string})
                       (orc/code "echo"
                         :fn "ai.obney.orc.demos.pi-agent-loop.core-test/echo-tool-workflow"
                         :reads [:query] :writes [:answer]))
          sheet-id (orc/build-workflow! ctx definition)
          correlation-id (random-uuid)
          tool (demo-tools/orc-workflow-tool
                {:name "lookup" :context ctx :sheet-id sheet-id
                 :correlation-id correlation-id
                 :input-fn #(select-keys % [:query])
                 :content-fn #(get-in % [:outputs :answer])})
          turns (atom 0)
          result (loop/run
                  {:prompts [(user "lookup")]
                   :tools {"lookup" tool}
                   :model-turn (fn [messages _]
                                 (case (swap! turns inc)
                                   1 (assistant "" [{:id "orc-1" :name "lookup"
                                                      :arguments {:query "nonce"}}] :tool-use)
                                   2 (do (is (= "orc:nonce" (:content (last messages))))
                                         (assistant "grounded: orc:nonce"))))})
          result-message (first (filter #(= :tool-result (:role %)) (:new-messages result)))
          tick-id (get-in result-message [:details :orc/trace-id])
          events (into [] (event-store/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)
                                             :tags #{[:tick tick-id]}}))]
      (is (uuid? tick-id))
      (is (= correlation-id (get-in result-message [:details :orc/correlation-id])))
      (is (= :success (get-in result-message [:details :orc/status])))
      (is (some #(= :sheet/execution-value-written (:event/type %)) events))
      (is (some #(and (= :sheet/tree-tick-completed (:event/type %))
                      (= :success (:root-status %))) events)))))

(deftest orc-backed-batches-preserve-pi-order-and-replay
  (h/with-async-test-context [ctx]
    (reset! node-effects [])
    (let [slow-started (promise) fast-started (promise)
          slow-release (promise) fast-release (promise)
          _ (reset! controlled-calls
                    {"slow" {:started slow-started :release slow-release}
                     "fast" {:started fast-started :release fast-release}})
          sheet-id (orc/build-workflow!
                    ctx
                    (orc/workflow "pi-loop-orc-parallel-batch"
                      (orc/blackboard {:query :string :answer :string})
                      (orc/code "controlled" :fn (test-fq "controlled-tool-workflow")
                        :reads [:query] :writes [:answer])))
          correlation-id (random-uuid)
          make-tool #(demo-tools/orc-workflow-tool
                      {:name % :context ctx :sheet-id sheet-id
                       :correlation-id correlation-id
                       :input-fn (fn [arguments] (select-keys arguments [:query]))
                       :content-fn (fn [result] (get-in result [:outputs :answer]))})
          turns (atom 0)
          running (future
                    (loop/run
                     {:prompts [(user "run both")]
                      :tools {"slow" (make-tool "slow") "fast" (make-tool "fast")}
                      :model-turn
                      (fn [_ _]
                        (case (swap! turns inc)
                          1 (assistant "" [{:id "slow-call" :name "slow"
                                             :arguments {:query "slow"}}
                                            {:id "fast-call" :name "fast"
                                             :arguments {:query "fast"}}] :tool-use)
                          2 (assistant "done")))}))]
      (is (true? (deref slow-started 10000 false)))
      (is (true? (deref fast-started 10000 false)))
      (deliver fast-release true)
      (Thread/sleep 50)
      (deliver slow-release true)
      (let [result @running
            completions (filter #(= :tool-execution-end (:type %)) (:events result))
            tool-results (filter #(= :tool-result (:role %)) (:new-messages result))
            trace-ids (mapv #(get-in % [:details :orc/trace-id]) tool-results)]
        (is (= ["fast-call" "slow-call"] (mapv :tool-call-id completions)))
        (is (= ["slow-call" "fast-call"] (mapv :tool-call-id tool-results)))
        (is (= ["orc:slow" "orc:fast"] (mapv :content tool-results)))
        (is (= 2 (count trace-ids)))
        (doseq [trace-id trace-ids]
          (is (h/settle-until! #(h/trace-stored? ctx trace-id)))
          (let [events (all-events ctx)
                live (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                             [:query/result :trace])
                replayed (get (reduce read-models/traces* {} events) trace-id)]
            (is (= live replayed))
            (is (= :success (:status live)))))
        (reset! controlled-calls {"first" {} "second" {}})
        (reset! node-effects [])
        (let [sequential-turns (atom 0)
              sequential-result
              (loop/run
               {:prompts [(user "run sequentially")]
                :tools {"first" (assoc (make-tool "first") :execution-mode :sequential)
                        "second" (make-tool "second")}
                :model-turn
                (fn [_ _]
                  (case (swap! sequential-turns inc)
                    1 (assistant "" [{:id "first-call" :name "first"
                                       :arguments {:query "first"}}
                                      {:id "second-call" :name "second"
                                       :arguments {:query "second"}}] :tool-use)
                    2 (assistant "done")))})]
          (is (= [[:controlled "first"] [:controlled "second"]] @node-effects))
          (is (= ["first-call" "second-call"]
                 (mapv :tool-call-id
                       (filter #(= :tool-execution-end (:type %))
                               (:events sequential-result)))))
          (is (= [[:start "first-call"] [:end "first-call"]
                  [:message-start "first-call"] [:message-end "first-call"]
                  [:start "second-call"] [:end "second-call"]
                  [:message-start "second-call"] [:message-end "second-call"]]
                 (->> (:events sequential-result)
                      (keep (fn [event]
                              (case (:type event)
                                :tool-execution-start [:start (:tool-call-id event)]
                                :tool-execution-end [:end (:tool-call-id event)]
                                :message-start (when (= :tool-result
                                                        (get-in event [:message :role]))
                                                 [:message-start
                                                  (get-in event [:message :tool-call-id])])
                                :message-end (when (= :tool-result
                                                      (get-in event [:message :role]))
                                               [:message-end
                                                (get-in event [:message :tool-call-id])])
                                nil)))
                      vec))))
        (is (every? #(= correlation-id (:correlation-id %))
                    (events/correlation-events ctx correlation-id)))))))

(deftest orc-backed-errors-truncation-and-unanimous-termination
  (h/with-async-test-context [ctx]
    (let [success-id (orc/build-workflow!
                      ctx
                      (orc/workflow "pi-loop-orc-success"
                        (orc/blackboard {:query :string :answer :string})
                        (orc/code "echo" :fn (test-fq "echo-tool-workflow")
                          :reads [:query] :writes [:answer])))
          failure-id (orc/build-workflow!
                      ctx
                      (orc/workflow "pi-loop-orc-failure"
                        (orc/blackboard {:query :string :answer :string})
                        (orc/code "fail" :fn (test-fq "failing-tool-workflow")
                          :reads [:query] :writes [:answer])))
          correlation-id (random-uuid)
          make-tool (fn [name sheet-id]
                      (demo-tools/orc-workflow-tool
                       {:name name :context ctx :sheet-id sheet-id
                        :correlation-id correlation-id
                        :input-fn #(select-keys % [:query])
                        :content-fn #(get-in % [:outputs :answer])}))
          good (make-tool "good" success-id)
          bad (make-tool "bad" failure-id)
          termination-correlation-id (random-uuid)
          termination-tool (demo-tools/orc-workflow-tool
                            {:name "termination" :context ctx :sheet-id success-id
                             :correlation-id termination-correlation-id
                             :input-fn #(select-keys % [:query])
                             :content-fn #(get-in % [:outputs :answer])})
          invoked (atom 0)
          truncated-turns (atom 0)
          truncated (loop/run
                     {:prompts [(user "truncate")]
                      :tools {"good" (assoc good :execute
                                            (fn [& args]
                                              (swap! invoked inc)
                                              (apply (:execute good) args)))}
                      :model-turn (fn [_ _]
                                    (case (swap! truncated-turns inc)
                                      1 (assistant "" [{:id "t" :name "good"
                                                         :arguments {:query "never"}}]
                                                   :length)
                                      2 (assistant "stopped")))})
          error-turns (atom 0)
          errored (loop/run
                   {:prompts [(user "errors")]
                    :tools {"blocked" good "bad" bad}
                    :config {:before-tool-call
                             (fn [{:keys [tool-call]}]
                               (when (= "blocked" (:name tool-call))
                                 {:block true :reason "policy blocked"}))}
                    :model-turn (fn [_ _]
                                  (case (swap! error-turns inc)
                                    1 (assistant "" [{:id "b" :name "blocked"
                                                       :arguments {:query "blocked"}}
                                                      {:id "m" :name "missing" :arguments {}}
                                                      {:id "f" :name "bad"
                                                       :arguments {:query "fail"}}]
                                                 :tool-use)
                                    2 (assistant "stopped")))})
          turns (atom 0)
          terminated (loop/run
                      {:prompts [(user "terminate")]
                       :tools {"left" termination-tool "right" termination-tool}
                       :config {:after-tool-call (fn [_] {:terminate true})}
                       :model-turn (fn [_ _]
                                     (swap! turns inc)
                                     (assistant "" [{:id "l" :name "left"
                                                      :arguments {:query "left"}}
                                                     {:id "r" :name "right"
                                                      :arguments {:query "right"}}]
                                                :tool-use))})]
      (is (zero? @invoked))
      (is (= [true] (mapv :error (filter #(= :tool-result (:role %))
                                         (:new-messages truncated)))))
      (let [results (filter #(= :tool-result (:role %)) (:new-messages errored))]
        (is (= ["b" "m" "f"] (mapv :tool-call-id results)))
        (is (every? :error results))
        (is (= 1 (count (filter #(= :sheet/tree-tick-started (:event/type %))
                                (events/correlation-events ctx correlation-id))))))
      (is (= 1 @turns))
      (is (= 2 (count (filter #(= :tool-result (:role %))
                              (:new-messages terminated))))))))

(deftest abort-cancels-active-orc-work-and-prevents-later-nodes
  (h/with-async-test-context [ctx]
    (reset! node-effects [])
    (let [started (promise) release (promise)
          _ (reset! controlled-calls {"cancel" {:started started :release release}})
          sheet-id (orc/build-workflow!
                    ctx
                    (orc/workflow "pi-loop-orc-cancellation"
                      (orc/blackboard {:query :string :middle :string :answer :string})
                      (orc/sequence "main"
                        (orc/code "blocking-first" :fn (test-fq "blocking-first-node")
                          :reads [:query] :writes [:middle])
                        (orc/code "must-not-run" :fn (test-fq "forbidden-later-node")
                          :reads [:middle] :writes [:answer]))))
          active-ticks (atom #{})
          correlation-id (random-uuid)
          tool (demo-tools/orc-workflow-tool
                {:name "cancel_work" :context ctx :sheet-id sheet-id
                 :correlation-id correlation-id :active-ticks active-ticks
                 :input-fn #(select-keys % [:query])})
          harness (demo-runtime/create
                   {:context ctx :active-ticks active-ticks :tools {"cancel_work" tool}
                    :model-turn (fn [_ _]
                                  (assistant "" [{:id "cancel" :name "cancel_work"
                                                   :arguments {:query "cancel"}}]
                                             :tool-use))})
          running (future (demo-runtime/prompt! harness [(user "cancel now")]))]
      (is (true? (deref started 10000 false)))
      (is (= 1 (count @active-ticks)))
      (demo-runtime/abort! harness)
      (is (= 1 (count (demo-runtime/cancellations harness))))
      (is (= 1 (count (:cancelled (first (demo-runtime/cancellations harness))))))
      (deliver release true)
      (let [result (deref running 10000 ::timeout)]
        (is (not= ::timeout result))
        (is (= :aborted (:status result)))
        (is (= [[:first "cancel"]] @node-effects))
        (is (empty? @active-ticks))
        (is (= 2 (count (filter #(= :turn-end (:type %)) (:events result)))))
        (is (= 1 (count (filter #(= :agent-end (:type %)) (:events result)))))
        (let [cancelled-tick (first (:cancelled
                                     (first (demo-runtime/cancellations harness))))]
        (is (h/settle-until!
               #(some (fn [event] (= :sheet/tick-cancelled (:event/type event)))
                      (events/tick-events ctx cancelled-tick)))))))))

(deftest sequential-abort-does-not-start-later-tool-calls
  (let [aborted (atom false)
        executed (atom [])
        result (loop/run
                {:prompts [(user "abort sequential batch")]
                 :config {:tool-execution :sequential}
                 :aborted? #(boolean @aborted)
                 :tools {"first" {:execute (fn [_]
                                              (swap! executed conj :first)
                                              (reset! aborted true)
                                              {:content "first"})}
                         "later" {:execute (fn [_]
                                              (swap! executed conj :later)
                                              {:content "later"})}}
                 :model-turn (fn [_ _]
                               (assistant "" [{:id "first" :name "first" :arguments {}}
                                               {:id "later" :name "later" :arguments {}}]
                                          :tool-use))})]
    (is (= [:first] @executed))
    (is (= ["first"]
           (mapv :tool-call-id
                 (filter #(= :tool-execution-start (:type %)) (:events result)))))
    (is (= :aborted (:status result)))))

(deftest parallel-preparation-abort-does-not-start-later-tool-calls
  (let [aborted (atom false)
        result (loop/run
                {:prompts [(user "abort preparation")]
                 :aborted? #(boolean @aborted)
                 :config {:before-tool-call
                          (fn [{:keys [tool-call]}]
                            (when (= "first" (:name tool-call))
                              (reset! aborted true)))}
                 :tools {"first" {:execute (fn [_] (throw (Exception. "must not run")))}
                         "later" {:execute (fn [_] (throw (Exception. "must not run")))} }
                 :model-turn (fn [_ _]
                               (assistant "" [{:id "first" :name "first" :arguments {}}
                                               {:id "later" :name "later" :arguments {}}]
                                          :tool-use))})]
    (is (= ["first"]
           (mapv :tool-call-id
                 (filter #(= :tool-execution-start (:type %)) (:events result)))))
    (is (= :aborted (:status result)))))

(deftest stateful-runtime-accepts-steering-during-an-active-tool
  (let [started (promise) release (promise) turns (atom 0)
        harness (demo-runtime/create
                 {:tools {"held" {:execute (fn [_] (deliver started true) @release
                                              {:content "released"})}}
                  :model-turn (fn [messages _]
                                (case (swap! turns inc)
                                  1 (assistant "" [{:id "h" :name "held" :arguments {}}] :tool-use)
                                  2 (do (is (= "steer-now" (:content (last messages))))
                                        (assistant "steered"))))})
        running (future (demo-runtime/prompt! harness [(user "begin")]))]
    @started
    (demo-runtime/steer! harness [(user "steer-now")])
    (deliver release true)
    (let [result @running]
      (is (= :completed (:status result)))
      (is (= "steered" (:content (last (demo-runtime/messages harness))))))))

(deftest stateful-runtime-rejects-overlapping-prompt
  (let [started (promise) release (promise)
        harness (demo-runtime/create
                 {:model-turn (fn [_ _] (deliver started true) @release (assistant "done"))})
        running (future (demo-runtime/prompt! harness [(user "one")]))]
    @started
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already running"
                          (demo-runtime/prompt! harness [(user "two")])))
    (deliver release true)
    @running))

(deftest stateful-runtime-rejects-continuation-after-assistant
  (let [harness (demo-runtime/create
                 {:messages [(user "question") (assistant "answer")]
                  :model-turn (fn [_ _] (assistant "unreachable"))})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"assistant"
                          (demo-runtime/continue! harness)))))

(deftest abort-cancels-every-active-orc-tick
  (let [active (atom (set [(random-uuid) (random-uuid)]))
        cancelled (atom [])
        harness (demo-runtime/create {:context {:test true} :active-ticks active
                                      :model-turn (fn [_ _] (assistant "unused"))})]
    (with-redefs [orc/cancel! (fn [context tick-id]
                                (swap! cancelled conj [context tick-id])
                                {:cancelled [tick-id]})]
      (demo-runtime/abort! harness))
    (is (= @active (set (map second @cancelled))))
    (is (every? #(= {:test true} (first %)) @cancelled))))

(deftest provider-adapter-preserves-tool-call-and-provenance
  (let [request (atom nil)
        evidence (atom [])
        tool {:description "lookup" :parameters {:type "object"
                                                   :properties {:query {:type "string"}}
                                                   :required ["query"]}}
        turn (demo-model/live-model-turn
              {:provider :test :model "pinned-model" :system-prompt "system"
               :tools {"lookup" tool} :evidence evidence})]
    (with-redefs [router/completion
                  (fn [_ req]
                    (reset! request req)
                    {:id "response-1" :model "pinned-model" :usage {:total-tokens 7}
                     :choices [{:finish_reason "tool_calls"
                                :message {:role :assistant :content nil
                                          :tool_calls [{:id "tc-1"
                                                        :function {:name "lookup"
                                                                   :arguments "{\"query\":\"needle\"}"}}]}}]})]
      (let [assistant (turn [(user "find it")] {})]
        (is (= {:query "needle"} (get-in assistant [:tool-calls 0 :arguments])))
        (is (= :tool-use (:stop-reason assistant)))
        (is (= "response-1" (get-in assistant [:provider-evidence :response-id])))
        (is (= :system (get-in @request [:messages 0 :role])))
        (is (= "lookup" (get-in @request [:tools 0 :function :name])))
        (is (= 1 (count @evidence)))))))

(deftest streaming-provider-adapter-reconstructs-fragmented-tool-calls
  (let [request (atom nil)
        retained (atom [])
        chunks [{:id "stream-1" :model "pinned-model"
                 :choices [{:delta {:tool-calls
                                    [{:index 0 :id "tc-stream" :type "function"
                                      :function {:name "lookup" :arguments "{\"query\":"}}]}
                            :finish-reason nil}]}
                {:id "stream-1" :model "pinned-model"
                 :choices [{:delta {:tool-calls
                                    [{:index 0 :function {:arguments "\"needle\"}"}}]}
                            :finish-reason :tool_calls}]
                 :usage {:total-tokens 9}}]
        turn (demo-model/live-streaming-model-turn
              {:provider :openrouter :model "pinned-model"
               :tools {"lookup" {:description "lookup"
                                  :parameters {:type "object"
                                               :properties {:query {:type "string"}}}}}
               :on-evidence #(swap! retained conj %)})]
    (with-redefs [router/completion (fn [_ req]
                                      (reset! request req)
                                      (async/to-chan! chunks))]
      (let [response (turn [(user "find it")] {})
            message (:message response)]
        (is (= [{:id "tc-stream" :name "lookup" :arguments {:query "needle"}
                 :source-index 0}]
               (:tool-calls message)))
        (is (= :tool-use (:stop-reason message)))
        (is (= 2 (count (filter #(= :tool-call-delta (:kind %))
                                (:updates response)))))
        (is (= {:total-tokens 9} (:usage (first @retained))))
        (is (= "lookup" (get-in @request [:tools 0 :function :name])))))))
