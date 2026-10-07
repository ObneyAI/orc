(ns ai.obney.orc.evaluation.behaviour-judging-test
  "S9b: judges on behaviours (sequence, delegate, map-each, root) see the whole:
   evidence by scope, opt-in family evidence, waits for settled children, and
   an honest composite. Driven through the live processor path: build-workflow!
   -> execute -> :sheet/node-execution-completed -> evaluation processors ->
   durable assessments. Deterministic custom judge workflows record what the
   evaluation runtime handed them."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private judge-calls
  "What the evaluation runtime handed each judge, by the judge's name."
  (atom {}))

(defn step [{:keys [inputs]}] {:mid (str "mid:" (:request inputs))})
(defn finish [{:keys [inputs]}] {:answer (str "answer:" (:mid inputs))})

(defn- recorder [judge-name]
  (fn [{:keys [inputs]}]
    (swap! judge-calls update judge-name (fnil conj []) inputs)
    {:score 0.75 :feedback "recorded"}))

(def record-seq (recorder :seq))
(def record-del (recorder :del))
(def record-leaf (recorder :leaf))

(defn- family-recorder [judge-name]
  (fn [{:keys [inputs]}]
    (swap! judge-calls update judge-name (fnil conj []) inputs)
    {:score 0.5 :feedback "recorded"}))

(def record-family (family-recorder :family))
(def record-plain (family-recorder :plain))

(defn score-high [_] {:score 0.8 :feedback "high"})
(defn score-low [_] {:score 0.4 :feedback "low"})
(defn cannot-grade [_] (throw (ex-info "this judge cannot grade" {})))

;; --- waiting on child assessments -------------------------------------------

(def ^:private gate
  "Released by the test to let the slow child judges finish."
  (atom (promise)))

(def ^:private timeline
  "Ordered facts: [:child-started item] [:child-done item] [:parent-ran]."
  (atom []))

(defn slow-child-judge
  "A child judge that blocks until the gate opens; fails for item \"b\"."
  [{:keys [inputs]}]
  (let [item (get-in inputs [:host-inputs :item])]
    (swap! timeline conj [:child-started item])
    (deref @gate 60000 nil)
    (swap! timeline conj [:child-done item])
    (when (= "b" item) (throw (ex-info "child judge cannot grade b" {:item item})))
    {:score 0.6 :feedback (str "ok " item)}))

(defn parent-judge
  "Records what the parent's judge was handed and when it ran."
  [{:keys [inputs]}]
  (swap! timeline conj [:parent-ran])
  (swap! judge-calls update :parent (fnil conj []) inputs)
  {:score 0.9 :feedback "parent"})

(defn per-item [{:keys [inputs]}] {:result (str "r:" (:item inputs))})

(def ^:private io [:map-of :keyword [:any {:description "any value"}]])

(defn- judge-workflow
  "A deterministic custom judge workflow calling `record-fn`. `extra` is a map of
   further declared blackboard keys ({key schema}) the judge asks the runtime for."
  ([record-fn] (judge-workflow record-fn {}))
  ([record-fn extra]
   (sheet/workflow (str "s9b-judge-" (random-uuid))
     (sheet/blackboard (merge {:host-inputs io
                               :host-outputs io
                               :host-instruction [:string {:description "Host instruction"}]
                               :original-task io
                               :score :double
                               :feedback [:string {:description "Feedback"}]}
                              extra))
     (sheet/code "record" :fn (str "ai.obney.orc.evaluation.behaviour-judging-test/" record-fn)
       :reads (into [:host-inputs :host-outputs :host-instruction :original-task] (keys extra))
       :writes [:score :feedback]))))

(defn- settled-assessments
  "Bounded poll: the assessments matching `pred` once at least `n` are no longer pending."
  [ctx pred n]
  (let [deadline (+ (System/currentTimeMillis) 60000)]
    (loop []
      (let [as (filterv #(and (pred %) (not= :pending (:status %)))
                        (evaluation/get-assessments ctx {}))]
        (if (or (>= (count as) n) (>= (System/currentTimeMillis) deadline))
          as
          (do (Thread/sleep 50) (recur)))))))

(defn- node-id [ctx sheet-id n]
  (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))))

(def ^:private bb {:request [:string {:description "Input"}]
                   :mid [:string {:description "Intermediate"}]
                   :answer [:string {:description "Final"}]})

(deftest composite-and-delegate-judges-see-the-whole
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [seq-judge (sheet/build-workflow! ctx (judge-workflow "record-seq"))
          del-judge (sheet/build-workflow! ctx (judge-workflow "record-del"))
          child (sheet/build-workflow! ctx
                  (sheet/workflow "s9b-child"
                    (sheet/blackboard bb)
                    (sheet/code "finish" :fn "ai.obney.orc.evaluation.behaviour-judging-test/finish"
                      :reads [:mid] :writes [:answer])))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9b-parent"
                     (sheet/blackboard bb)
                     (sheet/judges {:seq-judge {:type :custom :sheet-id seq-judge}
                                    :del-judge {:type :custom :sheet-id del-judge}})
                     (sheet/sequence "pipeline" :judges ["seq-judge"]
                       (sheet/code "step" :fn "ai.obney.orc.evaluation.behaviour-judging-test/step"
                         :reads [:request] :writes [:mid])
                       (sheet/delegate "specialist" :target-sheet-id child
                         :reads [:mid] :writes [:answer] :judges ["del-judge"]))))
          result (sheet/execute ctx parent {:request "hello"} :timeout-ms 60000)
          settled (settled-assessments ctx (constantly true) 2)
          seen (fn [j] (first (get @judge-calls j)))]
      (is (= :success (:status result)) (pr-str result))
      (is (= 2 (count settled)) "both composite judges settled")
      (testing "a judge on a sequence sees the sequence's reads, writes and the original task"
        (is (= {:answer "answer:mid:hello"} (:host-outputs (seen :seq))))
        (is (= {:request "hello"} (:host-inputs (seen :seq))))
        (is (= {:request "hello"} (:original-task (seen :seq)))))
      (testing "a judge on a delegate sees what the delegate wrote"
        (is (= {:answer "answer:mid:hello"} (:host-outputs (seen :del))))
        (is (= {:mid "mid:hello"} (:host-inputs (seen :del))))
        (is (= {:request "hello"} (:original-task (seen :del))))))))

(deftest map-each-leaf-judge-sees-no-engine-bookkeeping
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-leaf"))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9b-map-each"
                     (sheet/blackboard {:items [:vector [:string {:description "Items"}]]
                                        :item [:string {:description "Current item"}]
                                        :result [:string {:description "Per-item result"}]
                                        :results [:vector [:string {:description "Results"}]]})
                     (sheet/judges {:leaf-judge {:type :custom :sheet-id judge}})
                     (sheet/map-each "each" :from :items :as :item :into :results
                       (sheet/code "per-item" :fn "ai.obney.orc.evaluation.behaviour-judging-test/per-item"
                         :reads [:item] :writes [:result] :judges ["leaf-judge"]))))
          result (sheet/execute ctx parent {:items ["a" "b"]} :timeout-ms 60000)
          settled (settled-assessments ctx (constantly true) 2)
          calls (get @judge-calls :leaf)]
      (is (= :success (:status result)) (pr-str result))
      (is (= 2 (count settled)) "one assessment per iteration")
      (is (= #{{:item "a"} {:item "b"}} (set (map :host-inputs calls)))
          "each iteration's judge sees exactly the item the leaf read")
      (is (every? #(every? (fn [k] (nil? (namespace k))) (keys (:host-inputs %))) calls)
          (pr-str (map :host-inputs calls))))))

(deftest family-evidence-is-opt-in
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [family-judge (sheet/build-workflow!
                        ctx (judge-workflow "record-family"
                                            {:host-family [:vector [:map-of :keyword [:any {:description "one execution"}]]]}))
          plain-judge (sheet/build-workflow! ctx (judge-workflow "record-plain"))
          child (sheet/build-workflow! ctx
                  (sheet/workflow "s9b-fam-child"
                    (sheet/blackboard bb)
                    (sheet/code "finish" :fn "ai.obney.orc.evaluation.behaviour-judging-test/finish"
                      :reads [:mid] :writes [:answer])))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9b-fam-parent"
                     (sheet/blackboard bb)
                     (sheet/judges {:family-judge {:type :custom :sheet-id family-judge}
                                    :plain-judge {:type :custom :sheet-id plain-judge}})
                     (sheet/sequence "pipeline" :judges ["family-judge" "plain-judge"]
                       (sheet/code "step" :fn "ai.obney.orc.evaluation.behaviour-judging-test/step"
                         :reads [:request] :writes [:mid])
                       (sheet/delegate "specialist" :target-sheet-id child
                         :reads [:mid] :writes [:answer]))))
          family-reads (atom 0)
          real-family sheet/get-execution-family
          result (with-redefs [sheet/get-execution-family
                               (fn [& args] (swap! family-reads inc) (apply real-family args))]
                   (let [r (sheet/execute ctx parent {:request "hello"} :timeout-ms 60000)]
                     (settled-assessments ctx (constantly true) 2)
                     r))
          expected (real-family ctx (:trace-id result) {:node-id (node-id ctx parent "pipeline")})
          got (:host-family (first (get @judge-calls :family)))]
      (is (= :success (:status result)) (pr-str result))
      (is (= #{"step" "specialist" "finish"} (set (map :node-name got)))
          "every descendant execution, including the delegate's child run")
      (is (= (map #(select-keys % [:event-id :tick-id :node-name :inputs :outputs :status]) expected)
             (map #(select-keys % [:event-id :tick-id :node-name :inputs :outputs :status]) got))
          "the family is handed over verbatim")
      (is (nil? (:host-family (first (get @judge-calls :plain))))
          "a judge that does not declare :host-family is not handed it")
      (is (= 1 @family-reads)
          "only the judge that declared :host-family caused a family read"))))

(def ^:private child-assessments-schema
  [:vector [:map-of :keyword [:any {:description "one child assessment"}]]])

(defn- map-each-with-slow-children
  "Build a map-each of three items: each iteration's leaf has a slow child judge;
   the map-each itself has a judge whose workflow runs `parent-fn` and declares
   `parent-extra`. Returns the parent sheet id."
  [ctx parent-fn parent-extra]
  (let [child-judge (sheet/build-workflow! ctx (judge-workflow "slow-child-judge"))
        parent-judge-sheet (sheet/build-workflow! ctx (judge-workflow parent-fn parent-extra))]
    (sheet/build-workflow! ctx
      (sheet/workflow "s9b-waits"
        (sheet/blackboard {:items [:vector [:string {:description "Items"}]]
                           :item [:string {:description "Current item"}]
                           :result [:string {:description "Per-item result"}]
                           :results [:vector [:string {:description "Results"}]]})
        (sheet/judges {:child-judge {:type :custom :sheet-id child-judge}
                       :parent-judge {:type :custom :sheet-id parent-judge-sheet}})
        (sheet/map-each "each" :from :items :as :item :into :results :judges ["parent-judge"]
          (sheet/code "per-item" :fn "ai.obney.orc.evaluation.behaviour-judging-test/per-item"
            :reads [:item] :writes [:result] :judges ["child-judge"]))))))

(defn- until
  "Bounded polling: true as soon as (pred) is truthy, false after `ms`."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (>= (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(deftest parent-judge-waits-for-every-child-assessment
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (reset! timeline [])
    (reset! gate (promise))
    (let [parent (map-each-with-slow-children ctx "parent-judge"
                                              {:child-assessments child-assessments-schema})
          each-id (node-id ctx parent "each")
          result (sheet/execute ctx parent {:items ["a" "b" "c"]} :timeout-ms 60000)
          started? (until #(= 3 (count (filter (comp #{:child-started} first) @timeline))) 30000)
          parent-request? (until #(seq (evaluation/get-assessments ctx {:node-id each-id})) 30000)]
      (is (= :success (:status result)) (pr-str result))
      (is started? "all three child judges are running")
      (is parent-request? "the parent's assessment is requested")
      (is (not (until #(some #{[:parent-ran]} @timeline) 750))
          "the parent's judge does not run while a child assessment is still pending")
      (is (= :pending (:status (first (evaluation/get-assessments ctx {:node-id each-id}))))
          "the parent's assessment stays pending")
      (deliver @gate :go)
      (let [parent-settled (settled-assessments ctx #(= each-id (:node-id %)) 1)
            handed (first (get @judge-calls :parent))
            kids (:child-assessments handed)]
        (is (= 1 (count parent-settled)))
        (is (= 3 (count kids)) (pr-str kids))
        (is (= {:scored 2 :failed 1} (frequencies (map :status kids)))
            "the failed child is shown as failed, not dropped")
        (is (= [:parent-ran] (last @timeline)) "the parent ran last")
        (is (= 3 (count (filter (comp #{:child-done} first) @timeline)))
            "every child judge finished first")))))

(deftest parent-judge-without-child-assessments-never-waits
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (reset! timeline [])
    (reset! gate (promise))
    (let [parent (map-each-with-slow-children ctx "parent-judge" {})
          each-id (node-id ctx parent "each")
          result (sheet/execute ctx parent {:items ["a" "b" "c"]} :timeout-ms 60000)
          ran? (until #(some #{[:parent-ran]} @timeline) 30000)
          done-when-parent-ran (count (filter (comp #{:child-done} first) @timeline))]
      (is (= :success (:status result)) (pr-str result))
      (is ran? "the parent's judge ran although every child judge is still blocked")
      (is (zero? done-when-parent-ran))
      (is (nil? (:depends-on (first (evaluation/get-assessments ctx {:node-id each-id}))))
          "its request waits on nothing")
      (deliver @gate :go)
      (settled-assessments ctx #(not= each-id (:node-id %)) 3))))

;; --- honest composite --------------------------------------------------------

(defn- composites-of [ctx node-id]
  (filterv #(= node-id (:node-id %))
           (into [] (es/read (:event-store ctx) {:types #{:judge/composite-score-computed}
                                                 :tenant-id (:tenant-id ctx)}))))

(defn- judged-leaf
  "A one-leaf workflow judged by `judges`: {judge-name {:fn \"score-high\" :purposes ..}}.
   Runs it, waits for every assessment to end and for the composite, and returns
   {:composites [...] :node-id id}."
  [ctx judges]
  (let [declared (into {}
                       (map (fn [[judge-name {:keys [fn purposes]}]]
                              [judge-name (cond-> {:type :custom
                                                   :sheet-id (sheet/build-workflow!
                                                              ctx (judge-workflow fn))}
                                            purposes (assoc :purposes purposes))]))
                       judges)
        parent (sheet/build-workflow! ctx
                 (sheet/workflow (str "s9b-composite-" (random-uuid))
                   (sheet/blackboard bb)
                   (apply sheet/judges [declared])
                   (sheet/code "step" :fn "ai.obney.orc.evaluation.behaviour-judging-test/step"
                     :reads [:request] :writes [:mid]
                     :judges (mapv name (keys judges)))))
        id (node-id ctx parent "step")
        result (sheet/execute ctx parent {:request "hello"} :timeout-ms 60000)]
    (settled-assessments ctx #(= id (:node-id %)) (count judges))
    (until #(seq (composites-of ctx id)) 30000)
    {:result result :node-id id :composites (composites-of ctx id)}))

(deftest composite-carries-coverage-and-is-partial-when-a-judge-did-not-score
  (h/with-async-test-context [ctx]
    (let [{:keys [result composites]} (judged-leaf ctx {:a {:fn "score-high"}
                                                        :b {:fn "score-low"}
                                                        :c {:fn "cannot-grade"}})
          composite (first composites)]
      (is (= :success (:status result)) (pr-str result))
      (is (= 1 (count composites)) "one composite, formed after every judge settled")
      (is (= true (:partial composite)))
      (is (= {:expected 3 :scored 2 :failed 1 :ungradable 0} (:coverage composite)))
      (is (= 0.6 (:composite-score composite)) "the mean over the judges that scored")
      (is (= #{"a" "b"} (set (map :judge-name (:contributing-judges composite))))))))

(deftest composite-of-fully-scored-judges-is-not-partial
  (h/with-async-test-context [ctx]
    (let [{:keys [composites]} (judged-leaf ctx {:a {:fn "score-high"} :b {:fn "score-low"}})
          composite (first composites)]
      (is (= 1 (count composites)))
      (is (= false (:partial composite)))
      (is (= {:expected 2 :scored 2 :failed 0 :ungradable 0} (:coverage composite))))))

(deftest composite-never-mixes-monitoring-only-and-learning-judges
  (h/with-async-test-context [ctx]
    (let [{:keys [composites]} (judged-leaf ctx {:a {:fn "score-high"}
                                                 :b {:fn "score-low"}
                                                 :m {:fn "score-low" :purposes #{:monitoring}}})
          composite (first composites)]
      (is (= 1 (count composites)))
      (is (= #{"a" "b"} (set (map :judge-name (:contributing-judges composite))))
          "the monitoring-only judge is not part of the learning composite")
      (is (= {:expected 2 :scored 2 :failed 0 :ungradable 0} (:coverage composite)))
      (is (= false (:partial composite))))))

(deftest composite-with-one-scored-judge-is-partial
  (h/with-async-test-context [ctx]
    (let [{:keys [composites]} (judged-leaf ctx {:a {:fn "score-high"}
                                                 :c1 {:fn "cannot-grade"}
                                                 :c2 {:fn "cannot-grade"}})
          composite (first composites)]
      (is (= 1 (count composites)))
      (is (= true (:partial composite)))
      (is (= {:expected 3 :scored 1 :failed 2 :ungradable 0} (:coverage composite)))
      (is (= 0.8 (:composite-score composite)) "the mean over the one judge that scored"))))

(deftest composite-with-no-scored-judge-is-coverage-only
  (h/with-async-test-context [ctx]
    (let [{:keys [composites]} (judged-leaf ctx {:c1 {:fn "cannot-grade"}
                                                 :c2 {:fn "cannot-grade"}})
          composite (first composites)]
      (is (= 1 (count composites)))
      (is (= true (:partial composite)))
      (is (= {:expected 2 :scored 0 :failed 2 :ungradable 0} (:coverage composite)))
      (is (not (contains? composite :composite-score)) "no score is invented"))))

(deftest composite-identity-is-per-subject-not-per-node-and-tick
  (h/with-async-test-context [ctx]
    (let [a (sheet/build-workflow! ctx (judge-workflow "score-high"))
          b (sheet/build-workflow! ctx (judge-workflow "score-low"))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9b-composite-per-subject"
                     (sheet/blackboard {:items [:vector [:string {:description "Items"}]]
                                        :item [:string {:description "Current item"}]
                                        :result [:string {:description "Per-item result"}]
                                        :results [:vector [:string {:description "Results"}]]})
                     (sheet/judges {:a {:type :custom :sheet-id a}
                                    :b {:type :custom :sheet-id b}})
                     (sheet/map-each "each" :from :items :as :item :into :results
                       (sheet/code "per-item" :fn "ai.obney.orc.evaluation.behaviour-judging-test/per-item"
                         :reads [:item] :writes [:result] :judges ["a" "b"]))))
          id (node-id ctx parent "per-item")
          result (sheet/execute ctx parent {:items ["x" "y" "z"]} :timeout-ms 60000)]
      (settled-assessments ctx #(= id (:node-id %)) 6)
      (until #(= 3 (count (composites-of ctx id))) 30000)
      (let [composites (composites-of ctx id)]
        (is (= :success (:status result)) (pr-str result))
        (is (= 3 (count composites)) "one composite per iteration")
        (is (= 3 (count (set (keep :subject-completion-id composites)))) "each names its own subject")
        (is (every? #(= {:expected 2 :scored 2 :failed 0 :ungradable 0} (:coverage %)) composites))))))

;; --- restart --------------------------------------------------------------

(defn- restart-processors
  "Stop every processor of `ctx` and start fresh ones over the same event store,
   cache and pubsub. (Nothing can be judged while they are down - a judge is a
   workflow the processors execute - so the interesting moment is the restart
   itself, with a parent's request already durable and waiting.)"
  [ctx]
  (let [old (:processors ctx)]
    (h/stop-test-processors! ctx)
    (let [fresh (h/start-test-processors ctx)]
      (assert (not-any? #(identical? % (first (vals old))) (vals fresh)))
      (assoc ctx :processors fresh))))

(deftest waiting-parent-starts-after-processors-restart
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (reset! timeline [])
    (reset! gate (promise))
    (let [parent (map-each-with-slow-children ctx "parent-judge"
                                              {:child-assessments child-assessments-schema})
          each-id (node-id ctx parent "each")
          result (sheet/execute ctx parent {:items ["a" "b" "c"]} :timeout-ms 60000)
          started? (until #(= 3 (count (filter (comp #{:child-started} first) @timeline))) 30000)
          parent-request? (until #(seq (evaluation/get-assessments ctx {:node-id each-id})) 30000)
          restarted (restart-processors ctx)]
      (is (= :success (:status result)) (pr-str result))
      (is (and started? parent-request?) "children are judging and the parent is waiting")
      (is (not (until #(some #{[:parent-ran]} @timeline) 500))
          "still waiting after the restart")
      (deliver @gate :go)
      (let [parent-settled (settled-assessments restarted #(= each-id (:node-id %)) 1)
            kids (:child-assessments (first (get @judge-calls :parent)))]
        (is (= 1 (count parent-settled)) "the parent started once the last child settled")
        (is (= 3 (count kids)) (pr-str kids))
        (is (= [:parent-ran] (last @timeline))))
      (h/stop-test-processors! restarted))))

;; --- built-in judges see the original task too ------------------------------

(deftest built-in-judge-sees-the-original-task-separately-from-the-instruction
  (h/with-async-test-context [ctx]
    (let [captured (atom nil)
          stub-predict (fn [_provider _module inputs _options]
                         (reset! captured inputs)
                         {:outputs {:band 4
                                    :reasoning "Adversarial review: claims trace to the source."
                                    :grounded-claims ["cited"]
                                    :ungrounded-claims []
                                    :feedback "Well grounded."}
                          :usage {:total-tokens 1}})
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9b-builtin"
                     (sheet/blackboard bb)
                     (sheet/judges {:g {:type :grounding}})
                     (sheet/code "step" :fn "ai.obney.orc.evaluation.behaviour-judging-test/step"
                       :reads [:request] :writes [:mid] :judges ["g"])))
          id (node-id ctx parent "step")]
      (with-redefs [llm/predict stub-predict]
        (sheet/execute ctx parent {:request "hello"} :timeout-ms 60000)
        (settled-assessments ctx #(= id (:node-id %)) 1))
      (is (some? @captured) "the built-in judge called the model")
      (is (= "{\"request\":\"hello\"}" (:original-task @captured))
          "the run's original task is handed over as its own item")
      (is (= (:host-inputs @captured) (:original-task @captured))
          "here the node read exactly the run's input, serialised the same way")
      (is (not (str/includes? (str (:host-instruction @captured)) "hello"))
          "the original task is not folded into the node's instruction"))))
