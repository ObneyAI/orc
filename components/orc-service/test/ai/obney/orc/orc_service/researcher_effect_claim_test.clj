(ns ai.obney.orc.orc-service.researcher-effect-claim-test
  "RR-7 propagated contracts for campaign ownership epochs and effect claims.

   These tests exercise schema-validated commands, Grain's real atomic append,
   raw durable events, and the read projection. An atom represents only the
   injected external effect and is incremented strictly after a successful
   claim append; it is not a substitute for any production state."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn- command
  [name body]
  (merge {:command/id (random-uuid)
          :command/timestamp (time/now)
          :command/name name}
         body))

(def rr10-generated-child-effects (atom 0))

(defn count-rr10-generated-child-effect
  [_]
  {:summary (str "child-effect-" (swap! rr10-generated-child-effects inc))})

(defn- claim-frontier!
  [ctx sheet-id tick-id node-id ownership-epoch]
  (h/run-and-apply!
   ctx
   (command :sheet/claim-researcher-frontier
            {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :ownership-epoch ownership-epoch
             :claimed-at "2030-01-01T00:00:00Z"})))

(defn- claim-effect!
  [ctx sheet-id tick-id node-id ownership-epoch logical-id attempt-id]
  (h/run-and-apply!
   ctx
   (command :sheet/claim-researcher-effect
            {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :iteration-index 0
             :logical-action-identity logical-id
             :attempt-identity attempt-id
             :attempt-ordinal 0
             :ownership-epoch ownership-epoch
             :kind :tool
             :claimed-at "2030-01-01T00:00:01Z"})))

(defn- complete-effect!
  [ctx sheet-id tick-id node-id ownership-epoch logical-id attempt-id result]
  (h/run-and-apply!
   ctx
   (command :sheet/complete-researcher-effect
            {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :logical-action-identity logical-id
             :attempt-identity attempt-id
             :ownership-epoch ownership-epoch
             :result result
             :resolved-at "2030-01-01T00:00:02Z"})))

(defn- mark-effect-indeterminate!
  [ctx sheet-id tick-id node-id ownership-epoch logical-id attempt-id]
  (h/run-and-apply!
   ctx
   (command :sheet/mark-researcher-effect-indeterminate
            {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :logical-action-identity logical-id
             :attempt-identity attempt-id
             :ownership-epoch ownership-epoch
             :resolved-at "2030-01-01T00:00:03Z"})))

(defn- resume-state
  [ownership-epoch revision next-iteration]
  {:version 2
   :revision revision
   :ownership-epoch ownership-epoch
   :next-iteration next-iteration
   :sandbox-vars {}
   :var-creation-times {}
   :usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
   :cumulative-tree-ms 0
   :iteration-attempts {}
   :campaign-started-at-ms 100
   :campaign-deadline-ms 10000})

(defn- iteration-record
  [iteration-index]
  {:iteration-index iteration-index
   :attempt-ordinal 0
   :status :success})

(defn- commit-iteration!
  [ctx sheet-id tick-id node-id state record]
  (h/run-and-apply!
   ctx
   (command :sheet/checkpoint-researcher-iteration
            {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :resume-state state
             :iteration-record record
             :resume? false
             :inputs {}})))

(deftest det-e2e-261-same-frontier-race-claims-before-one-effect
  (testing "two workers racing one logical action in one epoch produce one claim before one effect"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            logical-id "sha256:det-e2e-261-logical"
            attempt-id (effects/attempt-identity logical-id 1 0)
            frontier-result (claim-frontier! ctx sheet-id tick-id node-id 1)
            effect-count (atom 0)
            ready (java.util.concurrent.CountDownLatch. 2)
            release (promise)
            contenders
            (doall
             (repeatedly
              2
              #(future
                 (.countDown ready)
                 @release
                 (let [result (claim-effect! ctx sheet-id tick-id node-id 1
                                             logical-id attempt-id)]
                   (when-not (::anom/category result)
                     ;; Injected capability: dispatch is permitted only after
                     ;; the claim append has returned successfully.
                     (swap! effect-count inc))
                   result))))]
        (is (nil? (::anom/category frontier-result)) (pr-str frontier-result))
        (is (.await ready 2 java.util.concurrent.TimeUnit/SECONDS)
            "both workers reach the deterministic release barrier")
        (deliver release true)
        (let [results (mapv #(deref % 5000 ::timeout) contenders)
              events (h/read-all-events ctx)
              claims (filterv #(and (= :rlm/researcher-effect-claimed
                                         (:event/type %))
                                    (= logical-id
                                       (:logical-action-identity %)))
                              events)
              get-claims (ns-resolve
                          'ai.obney.orc.orc-service.core.read-models
                          'get-researcher-effect-claims)
              projected (when get-claims
                          (get-claims ctx sheet-id tick-id node-id))]
          (is (not-any? #{::timeout} results) (pr-str results))
          (is (= 1 (count (remove ::anom/category results))) (pr-str results))
          (is (= 1 (count (filter #(= ::anom/conflict (::anom/category %))
                                  results)))
              (pr-str results))
          (is (= 1 @effect-count)
              "only the worker whose claim appended may fire the effect")
          (is (= 1 (count claims)) (pr-str claims))
          (is (ifn? get-claims) "RR-7 exposes the durable claim projection")
          (is (= [{:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :iteration-index 0
                   :logical-action-identity logical-id
                   :attempt-identity attempt-id
                   :attempt-ordinal 0
                   :ownership-epoch 1
                   :kind :tool
                   :status :claimed
                   :claimed-at "2030-01-01T00:00:01Z"
                   :resolved-at nil}]
                 projected)))))))

(deftest current-frontier-fences-new-claims-and-completions
  (testing "a superseded owner can neither claim unseen work nor complete old work"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            old-logical "sha256:old-logical"
            old-attempt (effects/attempt-identity old-logical 1 0)
            new-logical "sha256:new-logical"
            stale-new-attempt (effects/attempt-identity new-logical 1 0)
            new-attempt (effects/attempt-identity new-logical 2 0)]
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 1))))
        (is (nil? (::anom/category
                   (claim-effect! ctx sheet-id tick-id node-id 1
                                  old-logical old-attempt))))
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 2))))
        (let [stale-new (claim-effect! ctx sheet-id tick-id node-id 1
                                       new-logical stale-new-attempt)
              stale-completion (complete-effect! ctx sheet-id tick-id node-id 1
                                                  old-logical old-attempt
                                                  {:value "stale"})]
          (is (= ::anom/conflict (::anom/category stale-new)) (pr-str stale-new))
          (is (= ::anom/conflict (::anom/category stale-completion))
              (pr-str stale-completion)))
        (is (nil? (::anom/category
                   (claim-effect! ctx sheet-id tick-id node-id 2
                                  new-logical new-attempt))))
        (is (nil? (::anom/category
                   (complete-effect! ctx sheet-id tick-id node-id 2
                                     new-logical new-attempt {:value "current"}))))
        (let [duplicate-completion
              (complete-effect! ctx sheet-id tick-id node-id 2
                                new-logical new-attempt {:value "duplicate"})
              campaign-events
              (filterv #(contains? (:event/tags %)
                                   [:researcher-campaign
                                    (effects/campaign-tag-id sheet-id tick-id node-id)])
                       (h/read-all-events ctx))
              projected (rm/get-researcher-effect-claims ctx sheet-id tick-id node-id)]
          (is (= ::anom/conflict (::anom/category duplicate-completion))
              (pr-str duplicate-completion))
          (is (= [:rlm/researcher-frontier-claimed
                  :rlm/researcher-effect-claimed
                  :rlm/researcher-frontier-claimed
                  :rlm/researcher-effect-claimed
                  :rlm/researcher-effect-completed]
                 (mapv :event/type campaign-events))
              (pr-str campaign-events))
          (is (= {:status :claimed :resolved-at nil}
                 (select-keys (first projected) [:status :resolved-at])))
          (is (= {:status :completed
                  :resolved-at "2030-01-01T00:00:02Z"
                  :result {:value "current"}}
                 (select-keys (second projected) [:status :resolved-at :result]))))))))

(deftest det-e2e-272-a-new-frontier-resolves-an-orphaned-claim-once
  (testing "claimed becomes terminal indeterminate under the newer owner"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            logical-id "sha256:det-e2e-272-orphaned-action"
            attempt-id (effects/attempt-identity logical-id 1 0)]
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 1))))
        (is (nil? (::anom/category
                   (claim-effect! ctx sheet-id tick-id node-id 1
                                  logical-id attempt-id))))
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 2))))
        (let [resolution
              (mark-effect-indeterminate! ctx sheet-id tick-id node-id 2
                                          logical-id attempt-id)
              duplicate
              (mark-effect-indeterminate! ctx sheet-id tick-id node-id 2
                                          logical-id attempt-id)
              terminal-completion
              (complete-effect! ctx sheet-id tick-id node-id 2
                                logical-id attempt-id {:value "too late"})
              indeterminate-events
              (filterv #(and (= :rlm/researcher-effect-indeterminate
                                 (:event/type %))
                              (= attempt-id (:attempt-identity %)))
                       (h/read-all-events ctx))
              projected
              (rm/get-researcher-effect-claims ctx sheet-id tick-id node-id)
              public-query
              (ns-resolve 'ai.obney.orc.orc-service.interface
                          'get-researcher-effect-claims)]
          (is (nil? (::anom/category resolution)) (pr-str resolution))
          (is (= ::anom/conflict (::anom/category duplicate))
              (pr-str duplicate))
          (is (= ::anom/conflict (::anom/category terminal-completion))
              (pr-str terminal-completion))
          (is (= 1 (count indeterminate-events))
              (pr-str indeterminate-events))
          (is (= 2 (:resolved-by-ownership-epoch
                    (first indeterminate-events)))
              (pr-str indeterminate-events))
          (is (ifn? public-query)
              "operators can query indeterminate claims through the public interface")
          (is (= projected
                 (when public-query
                   (public-query ctx sheet-id tick-id node-id))))
          (is (= {:logical-action-identity logical-id
                  :attempt-identity attempt-id
                  :ownership-epoch 1
                  :resolved-by-ownership-epoch 2
                  :status :indeterminate
                  :resolved-at "2030-01-01T00:00:03Z"}
                 (select-keys (first projected)
                              [:logical-action-identity :attempt-identity
                               :ownership-epoch :resolved-by-ownership-epoch
                               :status :resolved-at]))
              (pr-str projected)))))))

(deftest indeterminate-resolution-requires-a-new-current-frontier-and-unresolved-claim
  (testing "missing, current, completed, and stale-resolver attempts cannot settle"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            current-logical "sha256:det-e2e-272-current"
            completed-logical "sha256:det-e2e-272-completed"
            unresolved-logical "sha256:det-e2e-272-unresolved"
            missing-logical "sha256:det-e2e-272-missing"
            current-attempt (effects/attempt-identity current-logical 1 0)
            completed-attempt (effects/attempt-identity completed-logical 1 0)
            unresolved-attempt (effects/attempt-identity unresolved-logical 1 0)
            missing-attempt (effects/attempt-identity missing-logical 1 0)]
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 1))))
        (doseq [[logical-id attempt-id]
                [[current-logical current-attempt]
                 [completed-logical completed-attempt]
                 [unresolved-logical unresolved-attempt]]]
          (is (nil? (::anom/category
                     (claim-effect! ctx sheet-id tick-id node-id 1
                                    logical-id attempt-id)))))
        (is (= ::anom/conflict
               (::anom/category
                (mark-effect-indeterminate! ctx sheet-id tick-id node-id 1
                                            current-logical current-attempt))))
        (is (nil? (::anom/category
                   (complete-effect! ctx sheet-id tick-id node-id 1
                                     completed-logical completed-attempt
                                     {:value "done"}))))
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 2))))
        (is (= ::anom/conflict
               (::anom/category
                (mark-effect-indeterminate! ctx sheet-id tick-id node-id 2
                                            missing-logical missing-attempt))))
        (is (= ::anom/conflict
               (::anom/category
                (mark-effect-indeterminate! ctx sheet-id tick-id node-id 2
                                            completed-logical completed-attempt))))
        (is (nil? (::anom/category
                   (claim-frontier! ctx sheet-id tick-id node-id 3))))
        (is (= ::anom/conflict
               (::anom/category
                (mark-effect-indeterminate! ctx sheet-id tick-id node-id 2
                                            unresolved-logical
                                            unresolved-attempt))))
        (is (nil? (::anom/category
                   (mark-effect-indeterminate! ctx sheet-id tick-id node-id 3
                                               unresolved-logical
                                               unresolved-attempt))))
        (let [claims (rm/get-researcher-effect-claims
                      ctx sheet-id tick-id node-id)
              by-attempt (into {} (map (juxt :attempt-identity identity)) claims)
              indeterminate-events
              (filterv #(= :rlm/researcher-effect-indeterminate (:event/type %))
                       (h/read-all-events ctx))]
          (is (= 1 (count indeterminate-events))
              (pr-str indeterminate-events))
          (is (= :claimed (get-in by-attempt [current-attempt :status])))
          (is (= :completed (get-in by-attempt [completed-attempt :status])))
          (is (= {:ownership-epoch 1
                  :resolved-by-ownership-epoch 3
                  :status :indeterminate}
                 (select-keys (get by-attempt unresolved-attempt)
                              [:ownership-epoch :resolved-by-ownership-epoch
                               :status]))))))))

(deftest logical-action-identity-is-content-derived-and-canonical
  (testing "replay and reordered evaluation preserve identity while content changes do not"
    (let [base {:tick-id #uuid "00000000-0000-0000-0000-000000000001"
                :node-id #uuid "00000000-0000-0000-0000-000000000002"
                :iteration-index 3
                :generated-code-hash "sha256:code"
                :kind :tool
                :target "search"
                :arguments {:query "fence"
                            :options {:limit 5 :modes #{:graph :vector}}}}
          reordered (assoc base :arguments
                           (array-map :options
                                      (array-map :modes (hash-set :vector :graph)
                                                 :limit 5)
                                      :query "fence"))
          base-id (effects/logical-action-identity base)
          reordered-id (effects/logical-action-identity reordered)
          other (assoc base :target "lookup")
          first-order [(effects/logical-action-identity base)
                       (effects/logical-action-identity other)]
          second-order [(effects/logical-action-identity other)
                        (effects/logical-action-identity reordered)]]
      (is (= base-id reordered-id))
      (is (= first-order (vec (reverse second-order)))
          "evaluation order does not become identity input")
      (is (re-matches #"sha256:[0-9a-f]{64}" base-id))
      (doseq [[field changed]
              [[:tick-id #uuid "00000000-0000-0000-0000-000000000003"]
               [:node-id #uuid "00000000-0000-0000-0000-000000000004"]
               [:iteration-index 4]
               [:generated-code-hash "sha256:different-code"]
               [:kind :provider]
               [:target "lookup"]
               [:arguments {:query "different"}]]]
        (is (not= base-id
                  (effects/logical-action-identity (assoc base field changed)))
            (str field " is part of the logical identity preimage")))))
  (testing "list, vector, set, and map arguments retain distinct types"
    (let [identity-for
          (fn [arguments]
            (effects/logical-action-identity
             {:tick-id #uuid "00000000-0000-0000-0000-000000000001"
              :node-id #uuid "00000000-0000-0000-0000-000000000002"
              :iteration-index 0
              :generated-code-hash "sha256:code"
              :kind :tool
              :target "typed"
              :arguments arguments}))
          identities (mapv identity-for ['(:a :b) [:a :b] #{:a :b} {:a :b}])]
      (is (= 4 (count (set identities))) (pr-str identities)))))

(deftest physical-attempt-identity-is-stable-unique-and-non-negative
  (let [logical-id "sha256:logical-attempt-contract"
        attempt-1 (effects/attempt-identity logical-id 1 0)]
    (is (= attempt-1 (effects/attempt-identity logical-id 1 0)))
    (is (not= attempt-1 (effects/attempt-identity logical-id 2 0))
        "an ownership retry has a distinct physical attempt")
    (is (not= attempt-1 (effects/attempt-identity logical-id 1 1))
        "an ordinal retry has a distinct physical attempt")
    (is (re-matches #"sha256:[0-9a-f]{64}" attempt-1))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-negative"
                          (effects/attempt-identity logical-id 1 -1))))
  (testing "a negative ordinal is rejected before any claim append"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            _ (claim-frontier! ctx sheet-id tick-id node-id 1)
            result
            (h/run-and-apply!
             ctx
             (command :sheet/claim-researcher-effect
                      {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :iteration-index 0
                       :logical-action-identity "sha256:negative-logical"
                       :attempt-identity "sha256:negative-attempt"
                       :attempt-ordinal -1
                       :ownership-epoch 1
                       :kind :tool
                       :claimed-at "2030-01-01T00:00:01Z"}))]
        (is (= ::anom/incorrect (::anom/category result)) (pr-str result))
        (is (empty? (filter #(= :rlm/researcher-effect-claimed (:event/type %))
                            (h/read-all-events ctx))))))))

(deftest claim-command-rejects-an-attempt-identity-from-another-preimage
  (h/with-async-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          logical-id "sha256:attempt-must-match"
          mismatched-attempt "sha256:not-derived-from-the-command-fields"]
      (claim-frontier! ctx sheet-id tick-id node-id 1)
      (let [result
            (h/run-and-apply!
             ctx
             (command :sheet/claim-researcher-effect
                      {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :iteration-index 0
                       :logical-action-identity logical-id
                       :attempt-identity mismatched-attempt
                       :attempt-ordinal 0
                       :ownership-epoch 1
                       :kind :tool
                       :claimed-at "2030-01-01T00:00:01Z"}))
            raw-claims
            (filter #(and (= :rlm/researcher-effect-claimed (:event/type %))
                          (= logical-id (:logical-action-identity %)))
                    (h/read-all-events ctx))]
        (is (= ::anom/incorrect (::anom/category result)) (pr-str result))
        (is (re-find #"attempt identity"
                     (or (::anom/message result) ""))
            (pr-str result))
        (is (empty? raw-claims)
            "a mismatched physical attempt identity cannot append")))))

(deftest v2-checkpoint-commit-is-fenced-by-the-current-frontier-epoch
  (h/with-async-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          state-1 (resume-state 1 1 1)
          stale-higher-revision (resume-state 1 2 2)
          state-2 (resume-state 2 2 2)]
      (claim-frontier! ctx sheet-id tick-id node-id 1)
      (is (nil? (::anom/category
                 (commit-iteration! ctx sheet-id tick-id node-id
                                    state-1 (iteration-record 0)))))
      (claim-frontier! ctx sheet-id tick-id node-id 2)
      (let [stale-result
            (commit-iteration! ctx sheet-id tick-id node-id
                               stale-higher-revision (iteration-record 1))
            after-stale
            (filterv #(contains? #{:rlm/researcher-iteration-recorded
                                   :rlm/researcher-resume-state-saved}
                                 (:event/type %))
                     (h/read-all-events ctx))]
        (is (= ::anom/conflict (::anom/category stale-result))
            (pr-str stale-result))
        (is (= [0]
               (mapv :iteration-index
                     (filter #(= :rlm/researcher-iteration-recorded
                                 (:event/type %))
                             after-stale)))
            "the stale iteration record did not append")
        (is (= [1]
               (mapv #(get-in % [:resume-state :ownership-epoch])
                     (filter #(= :rlm/researcher-resume-state-saved
                                 (:event/type %))
                             after-stale)))
            "the stale resume state did not append"))
      (let [current-result
            (commit-iteration! ctx sheet-id tick-id node-id
                               state-2 (iteration-record 1))
            projected (rm/get-researcher-resume-state
                       ctx sheet-id tick-id node-id)]
        (is (nil? (::anom/category current-result)) (pr-str current-result))
        (is (= state-2 (:resume-state projected)))))))

(deftest checkpoint-codecs-canonicalize-effect-inputs-before-identity
  (let [codec {:tag :java-duration
               :match? #(instance? java.time.Duration %)
               :encode str
               :decode #(java.time.Duration/parse %)}
        source (str "(do (search (array-map :duration "
                    "(get-input :duration))) "
                    "(final! {:summary \"ok\"}))")
        duration-a (java.time.Duration/parse "PT42S")
        duration-b (java.time.Duration/parse "PT42S")
        node-id (random-uuid)
        tick-id (random-uuid)
        node {:id node-id
              :type :repl-researcher
              :instruction "hash the durable tool arguments"
              :reads [:duration]
              :writes [:summary]
              :mcp-tools ["search"]
              :tool-contracts {"search" {:arguments [:map]
                                           :result :any
                                           :checkpoint-safe? true}}
              :max-iterations 1
              :rlm {:checkpointed? true
                    :recursive? false
                    :timeouts {:provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 5000}}}
        claims (atom [])
        context {:sheet-id (random-uuid)
                 :tick-id tick-id
                 :node-id node-id
                 :researcher-ownership-epoch 1
                 :researcher-checkpoint-codecs [codec]
                 :call-tool-fn (fn [_ _ _] {:ok true})
                 :claim-researcher-effect!
                 (fn [claim]
                   (swap! claims conj claim)
                   {:command-result/events []})
                 :complete-researcher-effect!
                 (fn [_] {:command-result/events []})}
        blackboard
        (fn [duration]
          {:duration {:key :duration :schema :any :value duration :version 0}
           :summary {:key :summary :schema :string :value nil :version 0}})]
    (with-redefs [llm/predict
                  (fn [& _]
                    {:outputs {:code source}
                     :usage {:prompt_tokens 1
                             :completion_tokens 1
                             :total_tokens 2}})]
      (let [first-result
            (executor/execute-repl-researcher-rlm
             node (blackboard duration-a) :test context)
            second-result
            (executor/execute-repl-researcher-rlm
             node (blackboard duration-b) :test context)
            tool-claims (filterv #(= :tool (:kind %)) @claims)
            expected-logical
            (effects/logical-action-identity
             {:tick-id tick-id
              :node-id node-id
              :iteration-index 0
              :generated-code-hash (effects/generated-code-hash source)
              :kind :tool
              :target "search"
              :arguments
              (executor/encode-checkpoint-value
               [codec] {:duration duration-a})})]
        (is (= [:success :success]
               (mapv :status [first-result second-result])))
        (is (= 2 (count tool-claims)))
        (is (= expected-logical
               (:logical-action-identity (first tool-claims)))
            "the public tool claim hashes canonical durable arguments")
        (is (= 1 (count (set (map :logical-action-identity tool-claims))))
            "equal codec-supported values have one content identity")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unsupported"
         (effects/logical-action-identity
          {:tick-id tick-id
           :node-id node-id
           :iteration-index 0
           :generated-code-hash "sha256:code"
           :kind :tool
           :target "search"
           :arguments (Object.)})))
    (is (= "sha256:ccb3a19c2efac938f6898e946c265ca3f5643eb718ff05c1d939f7b3d1ed9c11"
           (effects/logical-action-identity
            {:tick-id #uuid "00000000-0000-0000-0000-000000000001"
             :node-id #uuid "00000000-0000-0000-0000-000000000002"
             :iteration-index 0
             :generated-code-hash "sha256:code"
             :kind :tool
             :target "stable"
             :arguments (array-map :a 1 :b 2)}))
        "ordinary reordered EDN retains its established identity")))

(deftest checkpointed-provider-is-claimed-before-dispatch-and-completed-afterward
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [provider-calls (atom 0)
          observation (atom nil)
          definition
          (sheet/workflow "rr7-provider-claim"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "finish deterministically"
              :writes [:summary]
              :max-iterations 1
              :model "deterministic-model"
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 10000}}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [provider module inputs options]
                      (swap! provider-calls inc)
                      (let [claims (filterv #(= :rlm/researcher-effect-claimed
                                                 (:event/type %))
                                            (h/read-all-events ctx))]
                        (reset! observation
                                {:provider provider
                                 :module module
                                 :inputs inputs
                                 :options options
                                 :claims-at-dispatch claims}))
                      {:outputs {:code "(final! {:summary \"claimed\"})"}
                       :usage {:prompt_tokens 2
                               :completion_tokens 1
                               :total_tokens 3}})]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
              tick-id (:trace-id result)
              node-id (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))
              campaign-events
              (filterv #(contains? #{:rlm/researcher-frontier-claimed
                                     :rlm/researcher-effect-claimed
                                     :rlm/researcher-effect-completed}
                                   (:event/type %))
                       (h/read-tick-events ctx tick-id))
              provider-claim (first (filter #(= :provider (:kind %))
                                            campaign-events))
              identity-fn (ns-resolve
                           'ai.obney.orc.orc-service.core.researcher-effects
                           'provider-logical-action-identity)
              expected-logical
              (when identity-fn
                (identity-fn {:tick-id tick-id
                              :node-id node-id
                              :iteration-index 0
                              :provider (:provider @observation)
                              :model "deterministic-model"
                              :module (:module @observation)
                              :inputs (:inputs @observation)
                              :options (:options @observation)}))
              projected (rm/get-researcher-effect-claims
                         ctx sheet-id tick-id node-id)]
          (is (= :success (:status result)) (pr-str result))
          (is (= "claimed" (get-in result [:outputs :summary])))
          (is (= 1 @provider-calls))
          (is (= 1 (count (:claims-at-dispatch @observation)))
              "the raw claim exists before the provider capability is entered")
          (is (= [:rlm/researcher-frontier-claimed
                  :rlm/researcher-effect-claimed
                  :rlm/researcher-effect-completed]
                 (mapv :event/type campaign-events))
              (pr-str campaign-events))
          (is (ifn? identity-fn))
          (is (= expected-logical (:logical-action-identity provider-claim)))
          (is (= :completed (:status (first projected))))
          (is (string? (:claimed-at (first projected))))
          (is (string? (:resolved-at (first projected)))))))))

(deftest det-e2e-272-new-frontier-settles-orphan-before-provider-redispatch
  (testing "a recovered worker settles the prior claim before entering a new effect"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            first-provider-entered (promise)
            release-first-provider (promise)
            second-provider-entered (promise)
            prior-settled-at-second-dispatch? (atom false)
            definition
            (sheet/workflow "rr10-provider-takeover-resolution"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish after ownership recovery"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (let [call-number (swap! provider-calls inc)]
                          (if (= 1 call-number)
                            (do
                              (deliver first-provider-entered true)
                              @release-first-provider
                              {:outputs {:code "(final! {:summary \"old\"})"}
                               :usage {:prompt_tokens 1
                                       :completion_tokens 1
                                       :total_tokens 2}})
                            (do
                              (reset! prior-settled-at-second-dispatch?
                                      (boolean
                                       (some #(= :rlm/researcher-effect-indeterminate
                                                 (:event/type %))
                                             (h/read-all-events ctx))))
                              (deliver second-provider-entered true)
                              {:outputs {:code "(final! {:summary \"new\"})"}
                               :usage {:prompt_tokens 1
                                       :completion_tokens 1
                                       :total_tokens 2}}))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                first-entered? (deref first-provider-entered 5000 false)
                start-event
                (when first-entered?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                second-entered? (deref second-provider-entered 5000 false)]
            (deliver release-first-provider true)
            (when recovered-worker
              (deref recovered-worker 10000 ::timeout))
            (let [result (deref execution 15000 ::timeout)
                  claims (rm/get-researcher-effect-claims
                          ctx sheet-id (:trace-id result) node-id)]
              (is first-entered? "the first owner reached the provider after its claim")
              (is (some? start-event))
              (is second-entered? "the recovered owner reached provider redispatch")
              (is @prior-settled-at-second-dispatch?
                  "the old unresolved claim must be durably indeterminate before redispatch")
              (is (= 2 @provider-calls))
              (is (= [:indeterminate :completed]
                     (mapv :status claims))
                  (pr-str claims)))))))))

(deftest indeterminate-resolution-cas-conflict-stops-the-stale-recovered-worker
  (testing "a newer frontier that wins during settlement prevents all later work"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            first-provider-entered (promise)
            release-first-provider (promise)
            resolution-attempted (promise)
            definition
            (sheet/workflow "rr10-resolution-ownership-loss"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "never dispatch after losing settlement ownership"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        (deliver first-provider-entered true)
                        @release-first-provider
                        {:outputs {:code "(final! {:summary \"old\"})"}
                         :usage {:prompt_tokens 1
                                 :completion_tokens 1
                                 :total_tokens 2}})
                      cp/process-command
                      (fn [command-context]
                        (let [candidate (:command command-context)]
                          (if (and (= :sheet/mark-researcher-effect-indeterminate
                                      (:command/name candidate))
                                   (= 2 (:ownership-epoch candidate)))
                            (do
                              ;; This is a real CAS race: frontier 3 commits
                              ;; after this worker won frontier 2 but before its
                              ;; resolution command reaches the append boundary.
                              (real-process-command
                               (assoc command-context
                                      :command
                                      (command
                                       :sheet/claim-researcher-frontier
                                       {:sheet-id sheet-id
                                        :tick-id (:tick-id candidate)
                                        :node-id node-id
                                        :ownership-epoch 3
                                        :claimed-at
                                        "2030-01-01T00:00:04Z"})))
                              (let [result
                                    (real-process-command command-context)]
                                (deliver resolution-attempted result)
                                result))
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                first-entered? (deref first-provider-entered 5000 false)
                start-event
                (when first-entered?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 7000 ::timeout))
                conflict-result (deref resolution-attempted 5000 ::timeout)
                events (h/read-all-events ctx)
                tick-id (:tick-id start-event)
                claims (when tick-id
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))]
            (try
              (is first-entered? "the original owner reached its provider")
              (is (some? start-event))
              (is (not= ::timeout recovered-result)
                  "the stale recovered worker terminates instead of hanging")
              (is (= ::anom/conflict (::anom/category conflict-result))
                  (pr-str conflict-result))
              (is (= 1 @provider-calls)
                  "ownership loss during settlement prevents redispatch")
              (is (= [1 2 3]
                     (->> events
                          (filter #(and (= :rlm/researcher-frontier-claimed
                                           (:event/type %))
                                        (= tick-id (:tick-id %))))
                          (mapv :ownership-epoch)))
                  (pr-str events))
              (is (= [:claimed] (mapv :status claims))
                  "the stale resolver cannot invent an indeterminate outcome")
              (is (empty?
                   (filter #(and (= :rlm/researcher-effect-claimed
                                    (:event/type %))
                                 (= 2 (:ownership-epoch %)))
                           events))
                  "no effect is claimed after settlement ownership is lost")
              (is (empty?
                   (filter #(contains? #{:rlm/researcher-resume-state-saved
                                         :sheet/node-execution-completed}
                                       (:event/type %))
                           events))
                  "the stale worker writes neither a checkpoint nor terminal")
              (finally
                (deliver release-first-provider true)
                (deref execution 3000 ::timeout)))))))))

(deftest indeterminate-resolution-store-error-fails-closed-before-redispatch
  (testing "a non-conflict settlement anomaly is an observable engine failure"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            first-provider-entered (promise)
            release-first-provider (promise)
            forced-resolution-error (promise)
            definition
            (sheet/workflow "rr10-resolution-store-error"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "fail closed when settlement storage fails"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        (deliver first-provider-entered true)
                        @release-first-provider
                        {:outputs {:code "(final! {:summary \"old\"})"}
                         :usage {:prompt_tokens 1
                                 :completion_tokens 1
                                 :total_tokens 2}})
                      cp/process-command
                      (fn [command-context]
                        (let [candidate (:command command-context)]
                          (if (and (= :sheet/mark-researcher-effect-indeterminate
                                      (:command/name candidate))
                                   (= 2 (:ownership-epoch candidate)))
                            (let [result {::anom/category ::anom/fault
                                          ::anom/message
                                          "forced indeterminate store failure"}]
                              (deliver forced-resolution-error result)
                              result)
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                first-entered? (deref first-provider-entered 5000 false)
                start-event
                (when first-entered?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 7000 ::timeout))
                anomaly-result (deref forced-resolution-error 5000 ::timeout)
                result (deref execution 7000 ::timeout)
                events (h/read-all-events ctx)
                tick-id (:tick-id start-event)
                claims (when tick-id
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))]
            (try
              (is first-entered? "the original owner reached its provider")
              (is (some? start-event))
              (is (not= ::timeout recovered-result))
              (is (= ::anom/fault (::anom/category anomaly-result)))
              (is (= :failure (:status result)) (pr-str result))
              (is (re-find #"forced indeterminate store failure"
                           (or (:error result) ""))
                  (pr-str result))
              (is (= 1 @provider-calls)
                  "storage failure prevents a recovered provider dispatch")
              (is (= [:claimed] (mapv :status claims))
                  "a failed append cannot manufacture terminal claim state")
              (is (empty?
                   (filter #(= :rlm/researcher-resume-state-saved
                                (:event/type %))
                           events))
                  "fail-closed settlement does not checkpoint progress")
              (is (= 1
                     (count
                      (filter #(and (= :sheet/node-execution-completed
                                        (:event/type %))
                                    (= :failure (:status %))
                                    (= 2 (:researcher-ownership-epoch %)))
                              events)))
                  "the winning frontier records exactly one fenced failure")
              (finally
                (deliver release-first-provider true)))))))))

(deftest det-e2e-272-provider-retry-is-attributable-but-not-model-visible
  (testing "two physical attempts remain one authored iteration in the next prompt"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            prompt-histories (atom [])
            old-provider-attempt-id (atom nil)
            old-provider-result-observed (promise)
            release-old-completion (promise)
            old-completion-result (promise)
            first-iteration-code "(store! :memo \"authored-once\")"
            final-code "(final! {:summary (get-var :memo)})"
            definition
            (sheet/workflow "rr10-provider-attempt-history"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "record one authored iteration then finish"
                :writes [:summary]
                :max-iterations 2
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :quantum {:max-iterations 2}
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [_provider _module inputs _options]
                        (swap! prompt-histories conj (:history inputs))
                        (let [call-number (swap! provider-calls inc)]
                          {:outputs {:code (if (= 3 call-number)
                                             final-code
                                             first-iteration-code)}
                           :reasoning (if (= 3 call-number)
                                        "finish from the authored iteration"
                                        "record authored work")
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}}))
                      cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)
                              command-name (:command/name command)]
                          (cond
                            (and (= :sheet/claim-researcher-effect command-name)
                                 (= :provider (:kind command))
                                 (= 0 (:iteration-index command))
                                 (= 1 (:ownership-epoch command)))
                            (let [result (real-process-command command-context)]
                              (when-not (::anom/category result)
                                (reset! old-provider-attempt-id
                                        (:attempt-identity command)))
                              result)

                            (and (= :sheet/complete-researcher-effect command-name)
                                 (= @old-provider-attempt-id
                                    (:attempt-identity command))
                                 (= 1 (:ownership-epoch command)))
                            (do
                              (deliver old-provider-result-observed true)
                              @release-old-completion
                              (let [result (real-process-command command-context)]
                                (deliver old-completion-result result)
                                result))

                            :else
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                provider-result-observed?
                (deref old-provider-result-observed 7000 false)
                start-event
                (when provider-result-observed?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 15000 ::timeout)
                tick-id (:trace-id result)
                provider-claims
                (filterv #(= :provider (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))
                first-logical-id
                (:logical-action-identity (first provider-claims))
                first-logical-attempts
                (filterv #(= first-logical-id
                             (:logical-action-identity %))
                         provider-claims)
                iteration-records
                (rm/get-researcher-iteration-records
                 ctx sheet-id tick-id node-id)
                next-iteration-history (nth @prompt-histories 2 nil)]
            (try
              (is provider-result-observed?
                  "the old provider returned before its outcome append")
              (is (some? start-event))
              (is (not= ::timeout recovered-result))
              (is (= :success (:status result)) (pr-str result))
              (is (= "authored-once" (get-in result [:outputs :summary])))
              (is (= 3 @provider-calls)
                  "one uncertain call, its retry, and the next logical iteration")
              (is (= ["None" "None"] (vec (take 2 @prompt-histories)))
                  (pr-str @prompt-histories))
              (is (re-find #"### Iteration 1" (or next-iteration-history ""))
                  (pr-str next-iteration-history))
              (is (re-find #"authored-once" (or next-iteration-history ""))
                  (pr-str next-iteration-history))
              (is (not (re-find #"(?i)indeterminate|ownership|CAS failed|process crash"
                                (or next-iteration-history "")))
                  (pr-str next-iteration-history))
              (is (= 3 (count provider-claims)) (pr-str provider-claims))
              (is (= 2 (count first-logical-attempts))
                  (pr-str first-logical-attempts))
              (is (= [:indeterminate :completed]
                     (mapv :status first-logical-attempts))
                  (pr-str first-logical-attempts))
              (is (= [1 2] (mapv :ownership-epoch first-logical-attempts)))
              (is (= 2 (count (set (map :attempt-identity
                                         first-logical-attempts)))))
              (is (= [0 1] (mapv :iteration-index iteration-records))
                  (pr-str iteration-records))
              (finally
                (deliver release-old-completion true)))
            (is (= ::anom/conflict
                   (::anom/category
                    (deref old-completion-result 5000 {})))
                "the old provider outcome remains rejected after recovery")))))))

(deftest checkpointed-inline-provider-is-claimed-before-dispatch
  (let [provider-calls (atom 0)
        inline-observation (atom nil)
        ctx-ref (atom nil)
        duration (java.time.Duration/parse "PT42S")
        codec {:tag :java-duration
               :match? #(instance? java.time.Duration %)
               :encode str
               :decode #(java.time.Duration/parse %)}
        inline-result {:outputs {:detail duration}
                       :usage {:prompt_tokens 1
                               :completion_tokens 1
                               :total_tokens 2}}
        source (str "(let [a (llm \"sub\" :instruction \"summarize\" "
                    ":writes [:detail] :model \"inline-model\") "
                    "b (llm \"sub\" :instruction \"summarize\" "
                    ":writes [:detail] :model \"inline-model\")] "
                    "(final! {:summary (str (:detail a) \"|\" (:detail b))}))")]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :researcher-checkpoint-codecs [codec]}}]
      (reset! ctx-ref ctx)
      (let [definition
            (sheet/workflow "rr7-inline-provider-claim"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "make one inline provider call"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (let [call-number (swap! provider-calls inc)]
                          (if (= 1 call-number)
                            {:outputs {:code source}
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                            (do
                              (reset! inline-observation
                                      {:provider-claims-at-dispatch
                                       (filterv #(and (= :rlm/researcher-effect-claimed
                                                          (:event/type %))
                                                      (= :provider (:kind %)))
                                                (h/read-all-events @ctx-ref))})
                              inline-result))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id result)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet ctx sheet-id))))
                provider-claims
                (filterv #(= :provider (:kind %))
                         (rm/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "PT42S|PT42S" (get-in result [:outputs :summary])))
            (is (= 2 @provider-calls)
                "the repeated identical inline call reuses one completed action")
            (is (= 2 (count (:provider-claims-at-dispatch
                             @inline-observation)))
                "the inline claim is durable before the second provider call")
            (is (= 2 (count provider-claims)) (pr-str provider-claims))
            (is (= 2 (count (set (map :logical-action-identity
                                      provider-claims))))
                "outer and generated-code-origin provider calls have distinct content identities")
            (is (every? #(= :completed (:status %)) provider-claims))
            (is (some #(= (executor/encode-checkpoint-value [codec]
                                                            inline-result)
                           (:result %))
                      provider-claims)
                "the inline provider completion stores codec-tagged durable data")))))))

(deftest det-e2e-272-inline-provider-retries-as-a-new-physical-attempt
  (testing "the inline provider uses the same logical identity under the newer epoch"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            epoch-one-provider-claims (atom 0)
            old-inline-attempt-id (atom nil)
            old-inline-result-observed (promise)
            release-old-completion (promise)
            old-completion-result (promise)
            source (str "(let [a (llm \"sub\" :instruction \"summarize\" "
                        ":writes [:detail] :model \"inline-model\")] "
                        "(final! {:summary (:detail a)}))")
            inline-result {:outputs {:detail "inline-value"}
                           :usage {:prompt_tokens 1
                                   :completion_tokens 1
                                   :total_tokens 2}}
            definition
            (sheet/workflow "rr10-inline-provider-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "recover one inline provider"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (if (= 1 (swap! provider-calls inc))
                          {:outputs {:code source}
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}}
                          inline-result))
                      cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)
                              command-name (:command/name command)]
                          (cond
                            (and (= :sheet/claim-researcher-effect command-name)
                                 (= :provider (:kind command))
                                 (= 1 (:ownership-epoch command)))
                            (let [result (real-process-command command-context)
                                  claim-number
                                  (when-not (::anom/category result)
                                    (swap! epoch-one-provider-claims inc))]
                              (when (= 2 claim-number)
                                (reset! old-inline-attempt-id
                                        (:attempt-identity command)))
                              result)

                            (and (= :sheet/complete-researcher-effect command-name)
                                 (= @old-inline-attempt-id
                                    (:attempt-identity command))
                                 (= 1 (:ownership-epoch command)))
                            (do
                              (deliver old-inline-result-observed true)
                              @release-old-completion
                              (let [result (real-process-command command-context)]
                                (deliver old-completion-result result)
                                result))

                            :else
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                inline-result-observed?
                (deref old-inline-result-observed 7000 false)
                start-event
                (when inline-result-observed?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 15000 ::timeout)
                tick-id (:trace-id result)
                provider-claims
                (filterv #(= :provider (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))
                inline-logical-id
                (:logical-action-identity
                 (first (filter #(= @old-inline-attempt-id
                                   (:attempt-identity %))
                                provider-claims)))
                inline-attempts
                (filterv #(= inline-logical-id
                             (:logical-action-identity %))
                         provider-claims)]
            (try
              (is inline-result-observed?
                  "the old inline provider returned before its outcome append")
              (is (some? start-event))
              (is (not= ::timeout recovered-result))
              (is (= :success (:status result)) (pr-str result))
              (is (= "inline-value" (get-in result [:outputs :summary])))
              (is (= 3 @provider-calls)
                  "outer provider once and inline provider twice")
              (is (= 3 (count provider-claims)) (pr-str provider-claims))
              (is (= 2 (count inline-attempts)) (pr-str inline-attempts))
              (is (= [:indeterminate :completed]
                     (mapv :status inline-attempts))
                  (pr-str inline-attempts))
              (is (= [1 2] (mapv :ownership-epoch inline-attempts)))
              (is (= 2 (count (set (map :attempt-identity inline-attempts)))))
              (finally
                (deliver release-old-completion true)))
            (is (= ::anom/conflict
                   (::anom/category
                    (deref old-completion-result 5000 {})))
                "the old inline outcome remains fenced")))))))

(deftest rejected-provider-claim-cannot-record-a-legacy-completion
  (let [provider-calls (atom 0)
        completion-calls (atom 0)
        legacy-writes (atom 0)
        node {:id (random-uuid)
              :type :repl-researcher
              :instruction "do not cross a rejected claim"
              :writes [:summary]
              :max-iterations 1
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 5000}}}
        context {:sheet-id (random-uuid)
                 :tick-id (random-uuid)
                 :node-id (:id node)
                 :researcher-ownership-epoch 1
                 :claim-researcher-effect!
                 (fn [_]
                   {::anom/category ::anom/conflict
                    ::anom/message "lost frontier"})
                 :complete-researcher-effect!
                 (fn [_]
                   (swap! completion-calls inc)
                   {:command-result/events []})
                 :persist-researcher-action!
                 (fn [_]
                   (swap! legacy-writes inc)
                   {:command-result/events []})}]
    (with-redefs [llm/predict
                  (fn [& _]
                    (swap! provider-calls inc)
                    {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
      (let [result (executor/execute-repl-researcher-rlm
                    node {} :test context)]
        (is (= :failure (:status result)) (pr-str result))
        (is (zero? @provider-calls)
            "the provider stays behind the rejected claim")
        (is (zero? @completion-calls)
            "a rejected claim has no guarded completion")
        (is (zero? @legacy-writes)
            "a rejected claim cannot manufacture legacy completion evidence")))))

(deftest checkpointed-executor-fails-closed-without-effect-claim-capabilities
  (let [provider-calls (atom 0)
        node {:id (random-uuid)
              :type :repl-researcher
              :instruction "must not dispatch"
              :writes [:summary]
              :max-iterations 1
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 5000}}}
        context {:sheet-id (random-uuid)
                 :tick-id (random-uuid)
                 :node-id (:id node)
                 :researcher-ownership-epoch 1}]
    (with-redefs [llm/predict
                  (fn [& _]
                    (swap! provider-calls inc)
                    {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
      (let [result (executor/execute-repl-researcher-rlm
                    node {} :test context)]
        (is (= :failure (:status result)) (pr-str result))
        (is (re-find #"claim.*complete|effect-claim"
                     (or (:error result) ""))
            (pr-str result))
        (is (zero? @provider-calls)
            "a checkpointed executor without claim capabilities cannot fire a provider"))
      (let [result (executor/execute-repl-researcher-rlm
                    node {} :test
                    (assoc (dissoc context :researcher-ownership-epoch)
                           :claim-researcher-effect!
                           (fn [_] {:command-result/events []})
                           :complete-researcher-effect!
                           (fn [_] {:command-result/events []})))]
        (is (= :failure (:status result)) (pr-str result))
        (is (re-find #"ownership epoch"
                     (or (:error result) ""))
            (pr-str result))
        (is (zero? @provider-calls)
            "claim functions cannot substitute for a winning ownership epoch")))))

(deftest checkpointed-effectful-tool-requires-context-aware-caller-before-effect-claim
  (let [provider-calls (atom 0)
        tool-calls (atom 0)]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :call-tool-fn
             (fn [_tool-name _arguments]
               (swap! tool-calls inc)
               {:hits ["must-not-run"]})}}]
      (let [definition
            (sheet/workflow "rr7-context-aware-checkpoint-tool"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "call one checkpoint-safe effectful tool"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts
                {"search" {:arguments [:map]
                           :result :any
                           :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs
                         {:code
                          "(do (search {:query \"must-not-run\"}) (final! {:summary \"wrong\"}))"}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id result)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet ctx sheet-id))))
                effect-claims
                (filterv #(= :rlm/researcher-effect-claimed (:event/type %))
                         (h/read-all-events ctx))
                projected-claims
                (rm/get-researcher-effect-claims
                 ctx sheet-id tick-id node-id)]
            (is (= :failure (:status result)) (pr-str result))
            (is (re-find #"context-aware 3-argument.*idempotency"
                         (str (:error result)))
                (pr-str result))
            (is (zero? @provider-calls)
                "incompatible checkpointed tool configuration fails before model work")
            (is (zero? @tool-calls)
                "an incompatible caller never dispatches the external tool")
            (is (empty? effect-claims)
                "an incompatible caller is rejected before any raw effect claim")
            (is (empty? projected-claims)
                "the effect-claim projection also remains empty")))))))

(deftest checkpointed-effectful-tool-requires-a-configured-caller-before-effect-claim
  (let [provider-calls (atom 0)]
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr7-configured-checkpoint-tool-caller"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "call one checkpoint-safe effectful tool"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts
                {"search" {:arguments [:map]
                           :result :any
                           :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs
                         {:code
                          "(do (search {:query \"must-not-run\"}) (final! {:summary \"wrong\"}))"}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id result)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet ctx sheet-id))))
                effect-claims
                (filterv #(= :rlm/researcher-effect-claimed (:event/type %))
                         (h/read-all-events ctx))
                projected-claims
                (rm/get-researcher-effect-claims
                 ctx sheet-id tick-id node-id)]
            (is (= :failure (:status result)) (pr-str result))
            (is (re-find #"context-aware 3-argument.*idempotency"
                         (str (:error result)))
                (pr-str result))
            (is (zero? @provider-calls)
                "a missing checkpointed tool caller fails before model work")
            (is (empty? effect-claims)
                "a missing caller is rejected before any raw effect claim")
            (is (empty? projected-claims)
                "the effect-claim projection also remains empty")))))))

(deftest frontier-command-errors-fail-the-node-instead-of-looking-like-race-loss
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [provider-calls (atom 0)
          definition
          (sheet/workflow "rr7-frontier-command-error"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "do not hide a frontier store error"
              :writes [:summary]
              :max-iterations 1
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 5000}}))
          sheet-id (sheet/build-workflow! ctx definition)
          process-command cp/process-command]
      (with-redefs [cp/process-command
                    (fn [command-context]
                      (if (= :sheet/claim-researcher-frontier
                             (get-in command-context [:command :command/name]))
                        {::anom/category ::anom/incorrect
                         ::anom/message "forced frontier store failure"}
                        (process-command command-context)))
                    llm/predict
                    (fn [& _]
                      (swap! provider-calls inc)
                      {:outputs {:code "(final! {:summary \"unsafe\"})"}})]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 2000)]
          (is (= :failure (:status result)) (pr-str result))
          (is (re-find #"forced frontier store failure"
                       (or (:error result) ""))
              (pr-str result))
          (is (zero? @provider-calls)
              "a failed frontier append cannot dispatch the provider"))))))

(deftest completed-effect-projection-rejoins-when-legacy-action-write-is-lost
  (let [tool-calls (atom 0)
        duration (java.time.Duration/ofSeconds 42)
        codec {:tag :java-duration
               :match? #(instance? java.time.Duration %)
               :encode str
               :decode #(java.time.Duration/parse %)}
        source (str "(final! {:summary (str (get (search "
                    "(array-map :query \"rejoin\")) :value))})")]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :researcher-checkpoint-codecs [codec]
             :call-tool-fn
             (fn [_ _ _]
               (swap! tool-calls inc)
               {:value (if (= 1 @tool-calls)
                         duration
                         (java.time.Duration/ofSeconds 99))})}}]
      (let [definition
            (sheet/workflow "rr7-completed-claim-rejoin"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "reuse the durable tool outcome"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts {"search" {:arguments [:map]
                                             :result :any
                                             :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code source}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})
                      cp/process-command
                      (fn [command-context]
                        (if (= :sheet/record-researcher-action
                               (get-in command-context [:command :command/name]))
                          {::anom/category ::anom/unavailable
                           ::anom/message "simulated lost legacy mirror"}
                          (real-process-command command-context)))]
          (let [first-result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id first-result)
                node (:node
                      (first (filter #(= "researcher" (:name (:node %)))
                                     (map (fn [n] {:node n})
                                          (sheet/get-nodes-for-sheet ctx sheet-id)))))
                node-id (:id node)
                claims (rm/get-researcher-effect-claims
                        ctx sheet-id tick-id node-id)
                tool-claim (first (filter #(= :tool (:kind %)) claims))
                legacy-actions (rm/get-researcher-actions
                                ctx sheet-id tick-id node-id)
                replay-blackboard
                {:summary {:key :summary
                           :schema :string
                           :value nil
                           :version 0}}
                replay-context
                {:sheet-id sheet-id
                 :tick-id tick-id
                 :node-id node-id
                 :researcher-ownership-epoch 2
                 :researcher-effect-claims claims
                 :researcher-actions legacy-actions
                 :call-tool-fn
                 (fn [_ _ _]
                   (swap! tool-calls inc)
                   {:value (java.time.Duration/ofSeconds 99)})
                 :researcher-checkpoint-codecs [codec]
                 :claim-researcher-effect! (fn [_] {:command-result/events []})
                 :complete-researcher-effect! (fn [_] {:command-result/events []})}
                replay-result
                (executor/execute-repl-researcher-rlm
                 node replay-blackboard :test replay-context)
                stale-legacy-replay-result
                (executor/execute-repl-researcher-rlm
                 node replay-blackboard :test
                 (assoc replay-context
                        :researcher-actions
                        {(:logical-action-identity tool-claim)
                         {:status :completed
                          :result {:value "stale-legacy-result"}}}))]
            (is (= :success (:status first-result)) (pr-str first-result))
            (is (= "PT42S" (get-in first-result [:outputs :summary])))
            (is (empty? legacy-actions)
                "the post-completion legacy researcher-action append was lost")
            (is (= :completed (:status tool-claim)))
            (is (= {:value duration}
                   (executor/decode-checkpoint-value [codec]
                                                     (:result tool-claim))))
            (is (= :success (:status replay-result)) (pr-str replay-result))
            (is (= "PT42S" (get-in replay-result [:outputs :summary]))
                "replay returns the guarded completion result")
            (is (= "PT42S"
                   (get-in stale-legacy-replay-result [:outputs :summary]))
                "the guarded completion deterministically overrides a stale legacy mirror")
            (is (= 1 @tool-calls)
                "replay does not re-fire the completed external tool")))))))

(deftest checkpointed-inline-tool-is-claimed-before-dispatch
  (let [tool-calls (atom 0)
        tool-observation (atom nil)
        ctx-ref (atom nil)]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :call-tool-fn
             (fn [tool-name arguments tool-context]
               (swap! tool-calls inc)
               (reset! tool-observation
                       {:tool-name tool-name
                        :arguments arguments
                        :tool-context tool-context
                        :claims-at-dispatch
                        (filterv #(and (= :rlm/researcher-effect-claimed
                                           (:event/type %))
                                       (= :tool (:kind %)))
                                 (h/read-all-events @ctx-ref))})
               {:hits ["one"]})}}]
      (reset! ctx-ref ctx)
      (let [source "(do (search (array-map :query \"fence\" :limit 2)) (final! {:summary \"tool-claimed\"}))"
            definition
            (sheet/workflow "rr7-tool-claim"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "call the tool once"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts {"search" {:arguments [:map]
                                             :result :any
                                             :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code source}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id result)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet ctx sheet-id))))
                tool-claim (first (filter #(= :tool (:kind %))
                                          (rm/get-researcher-effect-claims
                                           ctx sheet-id tick-id node-id)))]
            (is (= :success (:status result)) (pr-str result))
            (is (= 1 @tool-calls))
            (is (= 1 (count (:claims-at-dispatch @tool-observation)))
                "the tool claim is durable before the host caller runs")
            (is (= "search" (:tool-name @tool-observation)))
            (is (= {:query "fence" :limit 2}
                   (:arguments @tool-observation)))
            (is (= (:logical-action-identity tool-claim)
                   (get-in @tool-observation
                           [:tool-context :orc/idempotency-key])))
            (is (= :completed (:status tool-claim)))
            (is (= (effects/attempt-identity
                    (:logical-action-identity tool-claim)
                    (:ownership-epoch tool-claim)
                    (:attempt-ordinal tool-claim))
                   (:attempt-identity tool-claim)))))))))

(deftest repeated-identical-tool-content-reuses-one-completed-logical-action
  (let [tool-calls (atom 0)
        source (str "(let [a (search {:query \"same\"}) "
                    "b (search {:query \"same\"})] "
                    "(final! {:summary (str (:value a) \"|\" (:value b))}))")]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :call-tool-fn
             (fn [_ _ _]
               (swap! tool-calls inc)
               {:value "one"})}}]
      (let [definition
            (sheet/workflow "rr7-repeated-identical-tool"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "reuse one content-addressed tool action"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts {"search" {:arguments [:map]
                                             :result :any
                                             :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code source}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tick-id (:trace-id result)
                node-id (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet ctx sheet-id))))
                tool-claims (filterv #(= :tool (:kind %))
                                     (rm/get-researcher-effect-claims
                                      ctx sheet-id tick-id node-id))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "one|one" (get-in result [:outputs :summary]))
                (pr-str result))
            (is (= 1 @tool-calls)
                "identical content is one logical external action")
            (is (= 1 (count tool-claims)))
            (is (= :completed (:status (first tool-claims))))))))))

(deftest same-frontier-public-tool-workers-fire-once-with-canonical-arguments
  (let [provider-calls (atom 0)
        tool-calls (atom 0)
        provider-entered (promise)
        release-provider (promise)
        source "(do (search (array-map :query \"fence\" :limit 2)) (final! {:summary \"one-tool-call\"}))"]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :call-tool-fn
             (fn [_ _ _]
               (swap! tool-calls inc)
               {:hits ["one"]})}}]
      (let [definition
            (sheet/workflow "rr7-tool-frontier-race"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "call the tool once"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts {"search" {:arguments [:map]
                                             :result :any
                                             :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        (deliver provider-entered true)
                        @release-provider
                        {:outputs {:code source}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                entered? (deref provider-entered 5000 false)
                start-event
                (when entered?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                competing-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx :event start-event :llm-provider :test)))]
            (deliver release-provider true)
            (let [result (deref execution 15000 ::timeout)
                  _ (when competing-worker
                      (deref competing-worker 10000 ::timeout))
                  tick-id (:trace-id result)
                  events (h/read-all-events ctx)
                  frontiers (filter #(and (= :rlm/researcher-frontier-claimed
                                               (:event/type %))
                                            (= tick-id (:tick-id %)))
                                    events)
                  tool-claims (filter #(and (= :rlm/researcher-effect-claimed
                                                 (:event/type %))
                                              (= :tool (:kind %))
                                              (= tick-id (:tick-id %)))
                                      events)
                  tool-claim (first tool-claims)
                  tool-completions
                  (filter #(and (= :rlm/researcher-effect-completed
                                   (:event/type %))
                                (= (:logical-action-identity tool-claim)
                                   (:logical-action-identity %)))
                          events)
                  projected-tool
                  (first (filter #(= :tool (:kind %))
                                 (rm/get-researcher-effect-claims
                                  ctx sheet-id tick-id node-id)))
                  event-position
                  (fn [event-id]
                    (first (keep-indexed
                            (fn [index event]
                              (when (= event-id (:event/id event)) index))
                            events)))
                  expected-logical
                  (effects/logical-action-identity
                   {:tick-id tick-id
                    :node-id node-id
                    :iteration-index 0
                    :generated-code-hash (effects/generated-code-hash source)
                    :kind :tool
                    :target "search"
                    ;; Reverse insertion order from the generated array-map.
                    :arguments (array-map :limit 2 :query "fence")})]
              (is entered? "the winning worker reached provider dispatch")
              (is (some? start-event))
              (is (= :success (:status result)) (pr-str result))
              (is (= 1 @provider-calls))
              (is (= 1 @tool-calls))
              (is (= 1 (count frontiers)) (pr-str frontiers))
              (is (= 1 (count tool-claims)) (pr-str tool-claims))
              (is (= 1 (count tool-completions)) (pr-str tool-completions))
              (is (< (event-position (:event/id tool-claim))
                     (event-position (:event/id (first tool-completions))))
                  "the winning raw claim precedes its guarded completion")
              (is (= :completed (:status projected-tool)))
              (is (= {:hits ["one"]} (:result projected-tool)))
              (is (= expected-logical (:logical-action-identity tool-claim))
                  "reordered canonical args resolve to the public claim identity"))))))))

(deftest det-e2e-272-checkpoint-safe-tool-owner-deduplicates-an-indeterminate-retry
  (testing "the recovered worker retries with one stable callee idempotency key"
    (let [provider-calls (atom 0)
          tool-invocations (atom 0)
          external-effects (atom 0)
          idempotency-keys (atom [])
          callee-results (atom {})
          old-tool-attempt-id (atom nil)
          old-tool-result-observed (promise)
          release-old-completion (promise)
          old-completion-result (promise)]
      (h/with-async-test-context
        [ctx {:context
              {:llm-provider :test
               :call-tool-fn
               (fn [_tool-name _arguments tool-context]
                 (swap! tool-invocations inc)
                 (let [idempotency-key (:orc/idempotency-key tool-context)]
                   (swap! idempotency-keys conj idempotency-key)
                   (or (get @callee-results idempotency-key)
                       (let [result {:hits [(str "external-effect-"
                                                (swap! external-effects inc))]}]
                         (swap! callee-results assoc idempotency-key result)
                         result))))}}]
        (let [source (str "(do (search {:query \"indeterminate\"}) "
                          "(final! {:summary \"tool-recovered\"}))")
              definition
              (sheet/workflow "rr10-tool-owner-dedup"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "retry one checkpoint-safe tool"
                  :writes [:summary]
                  :mcp-tools ["search"]
                  :tool-contracts
                  {"search" {:arguments [:map]
                             :result :any
                             :checkpoint-safe? true}}
                  :max-iterations 1
                  :model "deterministic-model"
                  :rlm {:checkpointed? true
                        :recursive? false
                        :timeouts {:provider-ms 5000
                                   :iteration-ms 7000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              node-id (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))
              real-process-command cp/process-command]
          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! provider-calls inc)
                          {:outputs {:code source}
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}})
                        cp/process-command
                        (fn [command-context]
                          (let [command (:command command-context)
                                command-name (:command/name command)]
                            (cond
                              (and (= :sheet/claim-researcher-effect command-name)
                                   (= :tool (:kind command))
                                   (= 1 (:ownership-epoch command)))
                              (let [result (real-process-command command-context)]
                                (when-not (::anom/category result)
                                  (reset! old-tool-attempt-id
                                          (:attempt-identity command)))
                                result)

                              (and (= :sheet/complete-researcher-effect command-name)
                                   (= @old-tool-attempt-id
                                      (:attempt-identity command))
                                   (= 1 (:ownership-epoch command)))
                              (do
                                (deliver old-tool-result-observed true)
                                @release-old-completion
                                (let [result (real-process-command command-context)]
                                  (deliver old-completion-result result)
                                  result))

                              :else
                              (real-process-command command-context))))]
            (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                  tool-result-observed?
                  (deref old-tool-result-observed 7000 false)
                  start-event
                  (when tool-result-observed?
                    (first (filter #(and (= :sheet/node-execution-started
                                            (:event/type %))
                                         (= node-id (:node-id %)))
                                   (h/read-all-events ctx))))
                  recovered-worker
                  (when start-event
                    (todo/execute-repl-researcher-node
                     (assoc ctx
                            :llm-provider :test
                            :event (assoc start-event
                                          :researcher-ownership-epoch 2))))
                  recovered-result
                  (when recovered-worker
                    (deref recovered-worker 10000 ::timeout))
                  result (deref execution 15000 ::timeout)
                  tick-id (:trace-id result)
                  tool-claims
                  (filterv #(= :tool (:kind %))
                           (sheet/get-researcher-effect-claims
                            ctx sheet-id tick-id node-id))]
              (try
                (is tool-result-observed?
                    "the old tool returned before its parent outcome append")
                (is (some? start-event))
                (is (not= ::timeout recovered-result))
                (is (= :success (:status result)) (pr-str result))
                (is (= 1 @provider-calls)
                    "the recovered worker reuses the completed provider claim")
                (is (= 2 @tool-invocations)
                    "ORC retries the participating callee")
                (is (= 1 @external-effects)
                    "the callee owner deduplicates the external side effect")
                (is (= 1 (count (set @idempotency-keys)))
                    (pr-str @idempotency-keys))
                (is (= 2 (count tool-claims)) (pr-str tool-claims))
                (is (= [:indeterminate :completed]
                       (mapv :status tool-claims))
                    (pr-str tool-claims))
                (is (apply = (map :logical-action-identity tool-claims)))
                (is (= 2 (count (set (map :attempt-identity tool-claims)))))
                (is (= [1 2] (mapv :ownership-epoch tool-claims)))
                (finally
                  (deliver release-old-completion true)))
              (is (= ::anom/conflict
                     (::anom/category
                      (deref old-completion-result 5000 {})))
                  "the old tool outcome cannot replace either terminal row")
              (is (= [:indeterminate :completed]
                     (mapv :status
                           (filter #(= :tool (:kind %))
                                   (sheet/get-researcher-effect-claims
                                    ctx sheet-id tick-id node-id))))))))))))

(deftest checkpointed-generated-child-is-claimed-before-stable-dispatch
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [source-tree
          '[:sequence
            [:code {:writes [:summary]
                    :output-schemas {:summary :string}
                    :fn (fn [_] {:summary "child-claimed"})}]
            [:final {:keys [:summary]}]]
          code (str "(emit-tree! (quote " (pr-str source-tree) "))")
          definition
          (sheet/workflow "rr7-generated-child-claim"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "emit one deterministic child"
              :writes [:summary]
              :max-iterations 1
              :rlm {:checkpointed? true
                    :recursive? false
                    :timeouts {:provider-ms 1000
                               :iteration-ms 5000
                               :campaign-ms 15000}}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code code}
                       :usage {:prompt_tokens 2
                               :completion_tokens 1
                               :total_tokens 3}})]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
              tick-id (:trace-id result)
              node-id (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))
              all-events (h/read-all-events ctx)
              child-claim (first (filter #(and (= :rlm/researcher-effect-claimed
                                                   (:event/type %))
                                                (= :generated-child (:kind %))
                                                (= tick-id (:tick-id %)))
                                         all-events))
              child-start (first (filter #(and (= :sheet/tree-tick-started
                                                   (:event/type %))
                                                (= tick-id (:parent-tick-id %)))
                                         all-events))
              event-position (fn [event-id]
                               (first (keep-indexed
                                       (fn [index event]
                                         (when (= event-id (:event/id event)) index))
                                       all-events)))
              projected (first (filter #(= :generated-child (:kind %))
                                       (rm/get-researcher-effect-claims
                                        ctx sheet-id tick-id node-id)))
              stable-child-id
              (when child-claim
                (java.util.UUID/nameUUIDFromBytes
                 (.getBytes (:logical-action-identity child-claim) "UTF-8")))]
          (is (= :success (:status result)) (pr-str result))
          (is (= "child-claimed" (get-in result [:outputs :summary])))
          (is (some? child-claim) (pr-str all-events))
          (is (some? child-start) (pr-str all-events))
          (is (< (event-position (:event/id child-claim))
                 (event-position (:event/id child-start)))
              "the durable child claim precedes the real child tick")
          (is (= stable-child-id (:tick-id child-start)))
          (is (= :completed (:status projected)))
          (is (= (:tick-id child-start)
                 (get-in projected [:result :trace-id]))))))))

(deftest det-e2e-272-indeterminate-generated-child-rejoins-one-stable-tick
  (testing "a newer parent attempt rejoins the child completed before outcome recording"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            provider-calls (atom 0)
            child-attempts (atom [])
            drop-first-child-completion? (atom true)
            source-tree
            '[:sequence
              [:code {:writes [:summary]
                      :output-schemas {:summary :string}
                      :fn (fn [_] {:summary "durable-child"})}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            node {:id node-id
                  :type :repl-researcher
                  :instruction "rejoin one durable generated child"
                  :writes [:summary]
                  :max-iterations 1
                  :model "deterministic-model"
                  :rlm {:checkpointed? true
                        :recursive? false
                        :timeouts {:provider-ms 2000
                                   :iteration-ms 5000
                                   :campaign-ms 15000}}}
            blackboard {:summary {:key :summary
                                  :schema :string
                                  :value nil
                                  :version 0}}
            claim!
            (fn [ownership-epoch]
              (fn [{:keys [iteration-index logical-action-identity
                           attempt-identity attempt-ordinal kind]}]
                (when (= :generated-child kind)
                  (swap! child-attempts conj attempt-identity))
                (h/run-and-apply!
                 ctx
                 (command :sheet/claim-researcher-effect
                          {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :iteration-index iteration-index
                           :logical-action-identity logical-action-identity
                           :attempt-identity attempt-identity
                           :attempt-ordinal attempt-ordinal
                           :ownership-epoch ownership-epoch
                           :kind kind
                           :claimed-at "2030-01-01T00:00:01Z"}))))
            complete!
            (fn [ownership-epoch]
              (fn [{:keys [logical-action-identity attempt-identity result]}]
                (if (and (= attempt-identity (first @child-attempts))
                         (compare-and-set! drop-first-child-completion? true false))
                  {::anom/category ::anom/unavailable
                   ::anom/message "simulated crash before parent child-outcome append"}
                  (h/run-and-apply!
                   ctx
                   (command :sheet/complete-researcher-effect
                            {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :logical-action-identity logical-action-identity
                             :attempt-identity attempt-identity
                             :ownership-epoch ownership-epoch
                             :result result
                             :resolved-at "2030-01-01T00:00:04Z"})))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code code}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (is (nil? (::anom/category
                     (claim-frontier! ctx sheet-id tick-id node-id 1))))
          (let [first-result
                (executor/execute-repl-researcher-rlm
                 node blackboard :test
                 (assoc ctx
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :researcher-ownership-epoch 1
                        :researcher-effect-claims []
                        :claim-researcher-effect! (claim! 1)
                        :complete-researcher-effect! (complete! 1)))
                claims-after-crash
                (rm/get-researcher-effect-claims ctx sheet-id tick-id node-id)
                old-child
                (first (filter #(= :generated-child (:kind %))
                               claims-after-crash))
                child-starts-after-crash
                (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                               (= tick-id (:parent-tick-id %)))
                         (h/read-all-events ctx))]
            (is (= :failure (:status first-result)) (pr-str first-result))
            (is (= :claimed (:status old-child)) (pr-str claims-after-crash))
            (is (= 1 (count child-starts-after-crash))
                (pr-str child-starts-after-crash))
            (is (nil? (::anom/category
                       (claim-frontier! ctx sheet-id tick-id node-id 2))))
            (is (nil? (::anom/category
                       (mark-effect-indeterminate!
                        ctx sheet-id tick-id node-id 2
                        (:logical-action-identity old-child)
                        (:attempt-identity old-child)))))
            (let [second-result
                  (executor/execute-repl-researcher-rlm
                   node blackboard :test
                   (assoc ctx
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :researcher-ownership-epoch 2
                          :researcher-effect-claims
                          (rm/get-researcher-effect-claims
                           ctx sheet-id tick-id node-id)
                          :claim-researcher-effect! (claim! 2)
                          :complete-researcher-effect! (complete! 2)))
                  events (h/read-all-events ctx)
                  child-starts
                  (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                                 (= tick-id (:parent-tick-id %)))
                           events)
                  child-claims
                  (filterv #(= :generated-child (:kind %))
                           (rm/get-researcher-effect-claims
                            ctx sheet-id tick-id node-id))]
              (is (contains? #{:running :success} (:status second-result))
                  (pr-str second-result))
              (is (= 1 @provider-calls)
                  "the completed provider result is replayed on the second parent attempt")
              (is (= 1 (count child-starts))
                  "the recovered parent must not start a second child tick")
              (is (= 1 (count (set (map :tick-id child-starts)))))
              (is (= 2 (count child-claims)) (pr-str child-claims))
              (is (= [:indeterminate :completed]
                     (mapv :status child-claims))
                  (pr-str child-claims))
              (is (apply = (map :logical-action-identity child-claims)))
              (is (= 2 (count (set (map :attempt-identity child-claims)))))
              (is (= [1 2] (mapv :ownership-epoch child-claims)))
              (is (= (:tick-id (first child-starts))
                     (get-in (second child-claims) [:result :trace-id]))))))))))

(deftest det-e2e-272-recovered-generated-child-rejoins-before-old-owner-releases
  (testing "the real recovered worker rejoins a completed child while the old outcome is paused"
    (reset! rr10-generated-child-effects 0)
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            old-child-attempt-id (atom nil)
            old-child-result-observed (promise)
            release-old-completion (promise)
            old-completion-result (promise)
            source-tree
            '[:sequence
              [:code {:writes [:summary]
                      :output-schemas {:summary :string}
                      :fn "ai.obney.orc.orc-service.researcher-effect-claim-test/count-rr10-generated-child-effect"}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            definition
            (sheet/workflow "rr10-public-generated-child-rejoin"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one recoverable generated child"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code code}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})
                      cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)
                              command-name (:command/name command)]
                          (cond
                            (and (= :sheet/claim-researcher-effect command-name)
                                 (= :generated-child (:kind command))
                                 (= 1 (:ownership-epoch command)))
                            (let [result (real-process-command command-context)]
                              (when-not (::anom/category result)
                                (reset! old-child-attempt-id
                                        (:attempt-identity command)))
                              result)

                            (and (= :sheet/complete-researcher-effect command-name)
                                 (= @old-child-attempt-id
                                    (:attempt-identity command))
                                 (= 1 (:ownership-epoch command)))
                            (do
                              (deliver old-child-result-observed true)
                              @release-old-completion
                              (let [result (real-process-command command-context)]
                                (deliver old-completion-result result)
                                result))

                            :else
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                child-result-observed?
                (deref old-child-result-observed 7000 false)
                start-event
                (when child-result-observed?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 15000 ::timeout)
                tick-id (:trace-id result)
                events-before-release (h/read-all-events ctx)
                child-starts
                (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                               (= tick-id (:parent-tick-id %)))
                         events-before-release)
                child-claims
                (filterv #(= :generated-child (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))
                indeterminate-event
                (first (filter #(and (= :rlm/researcher-effect-indeterminate
                                        (:event/type %))
                                     (= (:attempt-identity (first child-claims))
                                        (:attempt-identity %)))
                               events-before-release))
                newer-claim-event
                (first (filter #(and (= :rlm/researcher-effect-claimed
                                        (:event/type %))
                                     (= (:attempt-identity (second child-claims))
                                        (:attempt-identity %)))
                               events-before-release))
                event-position
                (fn [event-id]
                  (first (keep-indexed
                          (fn [index event]
                            (when (= event-id (:event/id event)) index))
                          events-before-release)))]
            (try
              (is child-result-observed?
                  "the old child completed before its parent outcome append")
              (is (some? start-event))
              (is (not= ::timeout recovered-result))
              (is (= :success (:status result)) (pr-str result))
              (is (= 1 @provider-calls)
                  "the recovered owner reuses the completed provider claim")
              (is (= 1 @rr10-generated-child-effects)
                  "the child effect is not repeated")
              (is (= 1 (count child-starts)) (pr-str child-starts))
              (is (= 2 (count child-claims)) (pr-str child-claims))
              (is (= [{:ownership-epoch 1
                       :resolved-by-ownership-epoch 2
                       :status :indeterminate}
                      {:ownership-epoch 2
                       :status :completed}]
                     (mapv #(select-keys % [:ownership-epoch
                                            :resolved-by-ownership-epoch
                                            :status])
                           child-claims))
                  (pr-str child-claims))
              (is (apply = (map :logical-action-identity child-claims)))
              (is (= 2 (count (set (map :attempt-identity child-claims)))))
              (is (= (:tick-id (first child-starts))
                     (get-in (second child-claims) [:result :trace-id])))
              (is (some? indeterminate-event) (pr-str events-before-release))
              (is (some? newer-claim-event) (pr-str events-before-release))
              (is (< (event-position (:event/id indeterminate-event))
                     (event-position (:event/id newer-claim-event)))
                  "the old claim settles before the recovered parent claims its retry")
              (finally
                (deliver release-old-completion true)))
            (is (= ::anom/conflict
                   (::anom/category
                    (deref old-completion-result 5000 {})))
                "the old parent's delayed outcome loses the epoch fence")
            (is (= [:indeterminate :completed]
                   (mapv :status
                         (filter #(= :generated-child (:kind %))
                                 (sheet/get-researcher-effect-claims
                                  ctx sheet-id tick-id node-id)))))))))))

(deftest det-e2e-272-recovered-generated-child-starts-the-stable-prestart-identity-once
  (testing "recovery after claim but before child start dispatches one stable child"
    (reset! rr10-generated-child-effects 0)
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            old-child-attempt-id (atom nil)
            old-child-claim-blocked? (atom false)
            old-child-claim-persisted (promise)
            release-old-claim-return (promise)
            old-completion-result (promise)
            source-tree
            '[:sequence
              [:code {:writes [:summary]
                      :output-schemas {:summary :string}
                      :fn "ai.obney.orc.orc-service.researcher-effect-claim-test/count-rr10-generated-child-effect"}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            definition
            (sheet/workflow "rr10-public-generated-child-prestart-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "start one stable child after parent recovery"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code code}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})
                      cp/process-command
                      (fn [command-context]
                        (let [candidate (:command command-context)
                              command-name (:command/name candidate)]
                          (cond
                            (and (= :sheet/claim-researcher-effect command-name)
                                 (= :generated-child (:kind candidate))
                                 (= 1 (:ownership-epoch candidate))
                                 (compare-and-set! old-child-claim-blocked?
                                                   false true))
                            (let [result (real-process-command command-context)]
                              (when-not (::anom/category result)
                                (reset! old-child-attempt-id
                                        (:attempt-identity candidate))
                                (deliver old-child-claim-persisted result)
                                ;; The old parent has durably claimed, but the
                                ;; executor cannot observe the successful claim
                                ;; or dispatch the child before recovery begins.
                                @release-old-claim-return)
                              result)

                            (and (= :sheet/complete-researcher-effect command-name)
                                 (= @old-child-attempt-id
                                    (:attempt-identity candidate)))
                            (let [result (real-process-command command-context)]
                              (deliver old-completion-result result)
                              result)

                            :else
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                claim-persisted?
                (not= ::timeout (deref old-child-claim-persisted 7000 ::timeout))
                events-at-crash (h/read-all-events ctx)
                start-event
                (when claim-persisted?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 events-at-crash)))
                child-starts-at-crash
                (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                               (= (:tick-id start-event) (:parent-tick-id %)))
                         events-at-crash)
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 10000 ::timeout)
                tick-id (:tick-id start-event)
                events-before-release (h/read-all-events ctx)
                child-starts
                (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                               (= tick-id (:parent-tick-id %)))
                         events-before-release)
                child-claims
                (filterv #(= :generated-child (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))
                stable-child-id
                (when-let [logical-id (:logical-action-identity
                                       (first child-claims))]
                  (java.util.UUID/nameUUIDFromBytes
                   (.getBytes logical-id "UTF-8")))]
            (try
              (is claim-persisted?
                  "the old parent durably claimed its child action")
              (is (some? start-event))
              (is (empty? child-starts-at-crash)
                  "the crash window is before any child tick starts")
              (is (not= ::timeout recovered-result))
              (is (= :success (:status result)) (pr-str result))
              (is (= 1 @provider-calls)
                  "recovery replays the completed provider outcome")
              (is (= 1 @rr10-generated-child-effects)
                  "only the recovered stable child performs work")
              (is (= 1 (count child-starts)) (pr-str child-starts))
              (is (= stable-child-id (:tick-id (first child-starts))))
              (is (= 2 (count child-claims)) (pr-str child-claims))
              (is (= [:indeterminate :completed]
                     (mapv :status child-claims))
                  (pr-str child-claims))
              (is (apply = (map :logical-action-identity child-claims)))
              (is (= [1 2] (mapv :ownership-epoch child-claims)))
              (is (= 2 (count (set (map :attempt-identity child-claims)))))
              (is (= stable-child-id
                     (get-in (second child-claims) [:result :trace-id])))
              (finally
                (deliver release-old-claim-return true)))
            (is (= ::anom/conflict
                   (::anom/category
                    (deref old-completion-result 5000 {})))
                "the released old parent rejoins the same child but loses completion CAS")
            (is (= 1
                   (count
                    (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                  (= tick-id (:parent-tick-id %)))
                            (h/read-all-events ctx))))
                "releasing the old parent cannot create a second child")))))))

(deftest checkpointed-behavior-mint-is-claimed-before-ontology-command
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [mint-body {:capabilities ["retain evidence"]
                     :strengths []
                     :weaknesses []
                     :representative-uses ["durable research"]
                     :avoid-when ["no evidence exists"]
                     :summary "Retain durable evidence before reuse."
                     :version 1
                     :consolidated-from-event-count 0}
          mint-call (str "(mint-behavior! \"rr7-durable-mint\" "
                         (pr-str mint-body) ")")
          code (str "(let [a " mint-call " b " mint-call "] "
                    "(final! {:summary (str (= a b))}))")
          definition
          (sheet/workflow "rr7-mint-claim"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "mint one behavior"
              :writes [:summary]
              :max-iterations 1
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 1000
                               :iteration-ms 3000
                               :campaign-ms 10000}}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code code}
                       :usage {:prompt_tokens 2
                               :completion_tokens 1
                               :total_tokens 3}})]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
              tick-id (:trace-id result)
              node-id (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))
              events (h/read-all-events ctx)
              claim (first (filter #(and (= :rlm/researcher-effect-claimed
                                            (:event/type %))
                                         (= :behavior-mint (:kind %))
                                         (= tick-id (:tick-id %)))
                                   events))
              mint-events (filterv #(and (= :ontology/behavioral-subtree-minted
                                              (:event/type %))
                                           (= "rr7-durable-mint" (:name %)))
                                    events)
              mint-event (first mint-events)
              position (fn [event-id]
                         (first (keep-indexed
                                 (fn [index event]
                                   (when (= event-id (:event/id event)) index))
                                 events)))
              projected (first (filter #(= :behavior-mint (:kind %))
                                       (rm/get-researcher-effect-claims
                                        ctx sheet-id tick-id node-id)))]
          (is (= :success (:status result)) (pr-str result))
          (is (= "true" (get-in result [:outputs :summary])) (pr-str result))
          (is (some? claim) (pr-str events))
          (is (some? mint-event) (pr-str events))
          (is (= 1 (count mint-events))
              "repeated identical mint content reuses the completed action")
          (is (< (position (:event/id claim))
                 (position (:event/id mint-event)))
              "the mint claim precedes the ontology command's append")
          (is (= (:logical-action-identity claim)
                 (:logical-action-identity mint-event)))
          (is (= (:attempt-identity claim) (:attempt-identity mint-event)))
          (is (= 0 (:researcher-iteration mint-event)))
          (is (= :completed (:status projected)))
          (is (= (str (:target-id mint-event)) (:result projected))))))))

(deftest behavior-mint-callee-rejects-a-retry-of-the-same-logical-action
  (testing "a later physical attempt remains attributable but cannot duplicate the mint effect"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            logical-id "sha256:rr7-one-logical-mint"
            attempt-1 (effects/attempt-identity logical-id 1 0)
            attempt-2 (effects/attempt-identity logical-id 2 0)
            mint-body {:capabilities ["retain evidence"]
                       :strengths []
                       :weaknesses []
                       :representative-uses ["durable research"]
                       :avoid-when ["no evidence exists"]
                       :summary "Retain durable evidence before reuse."
                       :version 1
                       :consolidated-from-event-count 0}
            mint-command
            (fn [attempt-id]
              (command :ontology/mint-behavioral-subtree
                       {:name "rr7-idempotent-mint"
                        :body mint-body
                        :provenance :agent-minted
                        :minted-by-sheet-id sheet-id
                        :minted-by-tick-id tick-id
                        :logical-action-identity logical-id
                        :attempt-identity attempt-id
                        :researcher-iteration 0}))]
        (claim-frontier! ctx sheet-id tick-id node-id 1)
        (claim-effect! ctx sheet-id tick-id node-id 1 logical-id attempt-1)
        (let [first-mint (h/run-and-apply! ctx (mint-command attempt-1))]
          (is (nil? (::anom/category first-mint)) (pr-str first-mint)))
        (complete-effect! ctx sheet-id tick-id node-id 1 logical-id attempt-1
                          "rr7-idempotent-mint")
        (claim-frontier! ctx sheet-id tick-id node-id 2)
        (claim-effect! ctx sheet-id tick-id node-id 2 logical-id attempt-2)
        (let [retry (h/run-and-apply! ctx (mint-command attempt-2))
              events (h/read-all-events ctx)
              claims (filter #(and (= :rlm/researcher-effect-claimed
                                       (:event/type %))
                                   (= logical-id
                                      (:logical-action-identity %)))
                             events)
              mints (filter #(and (= :ontology/behavioral-subtree-minted
                                      (:event/type %))
                                  (= logical-id
                                     (:logical-action-identity %)))
                            events)
              target-id (:target-id (first mints))
              descriptions
              (filter #(and (= :ontology/tree-description-updated
                                (:event/type %))
                            (= target-id (:target-id %)))
                      events)]
          (is (= ::anom/conflict (::anom/category retry)) (pr-str retry))
          (is (= [attempt-1 attempt-2]
                 (mapv :attempt-identity claims))
              "both physical attempts remain in the durable claim history")
          (is (= [attempt-1] (mapv :attempt-identity mints))
              "only the winning physical attempt reaches the mint audit trail")
          (is (= 1 (count mints)))
          (is (= 1 (count descriptions))))))))

(deftest det-e2e-272-behavior-mint-owner-recovers-an-indeterminate-retry
  (testing "the recovered mint attempt returns the one target its owner already created"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            old-mint-attempt-id (atom nil)
            old-mint-result-observed (promise)
            release-old-completion (promise)
            old-completion-result (promise)
            mint-body {:capabilities ["retain evidence"]
                       :strengths []
                       :weaknesses []
                       :representative-uses ["durable research"]
                       :avoid-when ["no evidence exists"]
                       :summary "Recover one durable behavior mint."
                       :version 1
                       :consolidated-from-event-count 0}
            code (str "(let [target (mint-behavior! \"rr10-recovered-mint\" "
                      (pr-str mint-body)
                      ")] (final! {:summary target}))")
            definition
            (sheet/workflow "rr10-behavior-mint-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "recover one behavior mint"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 5000
                                 :iteration-ms 7000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            node-id (:id (first (filter #(= "researcher" (:name %))
                                        (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code code}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})
                      cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)
                              command-name (:command/name command)]
                          (cond
                            (and (= :sheet/claim-researcher-effect command-name)
                                 (= :behavior-mint (:kind command))
                                 (= 1 (:ownership-epoch command)))
                            (let [result (real-process-command command-context)]
                              (when-not (::anom/category result)
                                (reset! old-mint-attempt-id
                                        (:attempt-identity command)))
                              result)

                            (and (= :sheet/complete-researcher-effect command-name)
                                 (= @old-mint-attempt-id
                                    (:attempt-identity command))
                                 (= 1 (:ownership-epoch command)))
                            (do
                              (deliver old-mint-result-observed true)
                              @release-old-completion
                              (let [result (real-process-command command-context)]
                                (deliver old-completion-result result)
                                result))

                            :else
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                mint-result-observed?
                (deref old-mint-result-observed 7000 false)
                start-event
                (when mint-result-observed?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= node-id (:node-id %)))
                                 (h/read-all-events ctx))))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 15000 ::timeout)
                tick-id (:trace-id result)
                events-before-release (h/read-all-events ctx)
                mints
                (filterv #(and (= :ontology/behavioral-subtree-minted
                                   (:event/type %))
                               (= "rr10-recovered-mint" (:name %)))
                         events-before-release)
                target-id (:target-id (first mints))
                descriptions
                (filterv #(and (= :ontology/tree-description-updated
                                   (:event/type %))
                               (= target-id (:target-id %)))
                         events-before-release)
                mint-claims
                (filterv #(= :behavior-mint (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id node-id))]
            (try
              (is mint-result-observed?
                  "the old mint returned before its parent outcome append")
              (is (some? start-event))
              (is (not= ::timeout recovered-result))
              (is (= :success (:status result)) (pr-str result))
              (is (= (str target-id) (get-in result [:outputs :summary]))
                  (pr-str result))
              (is (= 1 @provider-calls)
                  "the recovered worker reuses the completed provider claim")
              (is (= 1 (count mints)) (pr-str mints))
              (is (= 1 (count descriptions)) (pr-str descriptions))
              (is (= 2 (count mint-claims)) (pr-str mint-claims))
              (is (= [:indeterminate :completed]
                     (mapv :status mint-claims))
                  (pr-str mint-claims))
              (is (apply = (map :logical-action-identity mint-claims)))
              (is (= 2 (count (set (map :attempt-identity mint-claims)))))
              (is (= [1 2] (mapv :ownership-epoch mint-claims)))
              (is (= (str target-id) (:result (second mint-claims))))
              (finally
                (deliver release-old-completion true)))
            (is (= ::anom/conflict
                   (::anom/category
                    (deref old-completion-result 5000 {})))
                "the old mint outcome cannot replace either terminal row")))))))
