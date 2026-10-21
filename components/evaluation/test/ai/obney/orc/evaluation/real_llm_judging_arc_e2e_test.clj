(ns ai.obney.orc.evaluation.real-llm-judging-arc-e2e-test
  "REAL-model, real-processor proof of the whole judge arc in one realistic
   workflow: a producer tree (map-each of llm leaves, then a delegate), judged at
   three scopes (leaf, delegate, root) by built-in, score-only (Jev) and custom
   judges, with alerts, composites, assessment origin, model provenance, node
   performance and a published version. Opt-in through ORC_OPENROUTER_E2E_TESTS;
   uses OPENROUTER_API_KEY (never printed). No accuracy or calibration claim:
   assertions are about the ENGINE's rules, never about what a model says."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.orc-service.core.family-usage :as family-usage]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as router]))

(def ^:private chat-model "z-ai/glm-5.3-flash")

(defn- live? []
  (and (= "true" (some-> (System/getenv "ORC_OPENROUTER_E2E_TESTS") str/trim str/lower-case))
       (not (str/blank? (System/getenv "OPENROUTER_API_KEY")))))

(defmacro ^:private with-live [& body]
  `(if (live?)
     (do ~@body)
     (testing "REAL-LLM skipped: gate or key absent" (is true))))

(defn- register-providers! []
  (router/register! :openrouter
                    {:provider :openrouter :model chat-model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key (System/getenv "OPENROUTER_API_KEY")}})
  (llm/register-provider! :live-jev
                          {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                           :config {:api-key (System/getenv "OPENROUTER_API_KEY")}}))

;; -----------------------------------------------------------------------------
;; The workflow
;; -----------------------------------------------------------------------------

(def ^:private facts
  ["The Eiffel Tower is 330 metres tall."
   "Honey never spoils when stored in a sealed container."
   "Octopuses have three hearts."])

(def ^:private io [:map-of :keyword [:any {:description "any value"}]])

(def ^:private child-assessments-schema
  [:vector [:map-of :keyword [:any {:description "one child assessment"}]]])

(def ^:private note-bands
  {1 "The note contradicts or ignores the fact"
   2 "The note is only partly supported by the fact"
   3 "The note is fully supported by the fact"})

(def ^:private brief-bands
  {1 "The brief is unusable: the notes and summary are wrong or missing"
   2 "The brief has serious problems: several notes or the summary are weak"
   3 "The brief is acceptable but uneven"
   4 "The brief is good: notes and summary are sound with minor flaws"
   5 "The brief is excellent: every note is grounded and the summary is faithful"})

(def ^:private alert {:below 0.99 :window 3 :min-coverage 0.5})

(defn- root-judge-workflow []
  (sheet/workflow (str "arc-root-judge-" (random-uuid))
    (sheet/blackboard
     {:host-inputs io
      :host-outputs io
      :host-instruction [:string {:description "The instruction the assessed node was given"}]
      :original-task io
      :child-assessments child-assessments-schema
      :rubric [:map {:description "The grading contract: criterion, stance and the described bands. Choose exactly one band number."}
               [:bands [:map-of :int [:maybe :string]]]
               [:criterion {:optional true} :string]
               [:stance {:optional true} :string]]
      :reasoning [:string {:description "Brief reasoning, written first, that weighs the child assessments against the brief's outputs"}]
      :band [:int {:description "The chosen band number from the rubric (1 to 5)"}]
      :feedback [:string {:description "One or two sentences of actionable feedback on the brief"}]})
    (sheet/llm "grade-brief"
      :instruction (str "You judge a whole brief workflow. Read the original task, the brief's outputs and the "
                        "assessments other judges already made of its parts (child-assessments). Weigh them, "
                        "then choose exactly one band from the rubric and write short feedback. "
                        "Write reasoning first, then the band, then the feedback.")
      :reads [:host-inputs :host-outputs :host-instruction :original-task :child-assessments :rubric]
      :writes [:reasoning :band :feedback])))

(defn- child-workflow []
  (sheet/workflow "arc-summarize-child"
    (sheet/blackboard {:notes [:vector [:map {:description "One note per fact"} [:note :string]]]
                       :summary [:string {:description "A two-sentence summary of the notes"}]})
    (sheet/llm "write-summary"
      :instruction "Summarise the notes in at most two sentences, using only what the notes say."
      :reads [:notes]
      :writes [:summary])))

(defn- producer-workflow [child-id root-judge-id]
  (sheet/workflow "arc-brief"
    (sheet/blackboard {:facts [:vector [:string {:description "A short fact"}]]
                       :fact [:string {:description "The current fact"}]
                       :note [:string {:description "A one-sentence note grounded in the fact"}]
                       :notes [:vector [:map {:description "One note per fact"} [:note :string]]]
                       :summary [:string {:description "The summary of the notes"}]})
    (sheet/judges
     {:grounding {:type :grounding :alert alert :timeout-ms 120000}
      :jev-monitor {:type :grounding :model "live-jev" :timeout-ms 120000
                    :rubric {:criterion "Is the note supported by the fact it was written from?"
                             :stance "Be exacting."
                             :bands note-bands
                             :feedback :none}}
      :completeness {:type :completeness :timeout-ms 120000}
      :root {:type :custom :sheet-id root-judge-id :timeout-ms 180000
             :rubric {:criterion "Is the whole brief faithful, grounded and complete?"
                      :stance "Be exacting; weigh the assessments of the parts."
                      :bands brief-bands
                      :feedback :required}}})
    (sheet/sequence "brief" :judges ["root"]
      (sheet/map-each "notes" :from :facts :as :fact :into :notes
        (sheet/llm "note"
          :instruction "Write exactly one sentence that restates the fact. Add nothing that the fact does not say."
          :reads [:fact]
          :writes [:note]
          :judges ["grounding" "jev-monitor"]))
      (sheet/delegate "summarize" :target-sheet-id child-id
        :reads [:notes] :writes [:summary] :judges ["completeness"]))))

;; -----------------------------------------------------------------------------
;; Reading the store
;; -----------------------------------------------------------------------------

(defn- events [ctx types]
  (into [] (es/read (:event-store ctx) {:types types :tenant-id (:tenant-id ctx)})))

(defn- node-id [ctx sheet-id n]
  (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))))

(defn- until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (>= (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 100) (recur))))))

(defn- assessments-of
  "The assessments of the run family `version-number` (nil = draft run)."
  [ctx version-number]
  (filterv #(= version-number (:version-number %)) (evaluation/get-assessments ctx {})))

(defn- await-settled
  "Bounded wait until `n` assessments of the run kind exist and none is pending."
  [ctx version-number n ms]
  (until #(let [as (assessments-of ctx version-number)]
            (and (= n (count as)) (not-any? (comp #{:pending} :status) as)))
         ms))

(defn- report-outcomes [label ctx as]
  (let [names (into {} (map (fn [n] [(:id n) (:name n)]))
                    (mapcat #(sheet/get-nodes-for-sheet ctx (:sheet-id %)) (take 1 as)))]
    (doseq [a (sort-by :requested-at as)]
      (println label
               (pr-str {:node (get names (:node-id a))
                        :judge (:judge-name a)
                        :status (:status a)
                        :band (:band a)
                        :score (:score a)
                        :feedback? (some? (:feedback a))
                        :reason (:reason a)
                        :message (:message a)
                        :model-provenance (:model-provenance a)})))))

(defn- expected-alert-signal
  "The rule, from the grounding judge's outcomes in the order they were recorded:
   a full window judged by coverage, then by trailing mean."
  [outcomes {:keys [below window min-coverage]}]
  (when (>= (count outcomes) window)
    (let [in-window (take-last window outcomes)
          scores (keep #(when (= :evaluation/assessment-scored (:event/type %)) (:score %)) in-window)
          coverage (/ (double (count scores)) window)
          mean (when (seq scores) (/ (reduce + 0.0 scores) (count scores)))]
      (cond (< coverage min-coverage) :evaluation/performance-coverage-degraded
            (< mean below) :evaluation/performance-threshold-crossed
            :else nil))))

(def ^:private terminal-types
  #{:evaluation/assessment-scored :evaluation/assessment-failed :evaluation/assessment-ungradable})

(def ^:private signal-types
  #{:evaluation/performance-threshold-crossed :evaluation/performance-threshold-recovered
    :evaluation/performance-coverage-degraded :evaluation/performance-coverage-restored})

(deftest live-judging-arc
  (with-live
    (register-providers!)
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow! ctx (child-workflow))
            root-judge-id (sheet/build-workflow! ctx (root-judge-workflow))
            sheet-id (sheet/build-workflow! ctx (producer-workflow child-id root-judge-id))
            note-id (node-id ctx sheet-id "note")
            summarize-id (node-id ctx sheet-id "summarize")
            brief-id (node-id ctx sheet-id "brief")
            result (sheet/execute ctx sheet-id {:facts facts} :timeout-ms 240000)
            _ (println :ARC-PRODUCER (pr-str {:status (:status result) :outputs (:outputs result)
                                              :error (:error result)}))
            settled? (await-settled ctx nil 8 420000)
            as (assessments-of ctx nil)
            by-node (fn [id] (filterv #(= id (:node-id %)) as))
            by-judge (fn [n] (filterv #(= n (:judge-name %)) as))]
        (is (#{:success :partial} (:status result)) (pr-str (select-keys result [:status :error])))
        (when (not= :success (:status result))
          (println :ARC-NOT-SUCCESS (pr-str (:status result))))
        (report-outcomes :ARC-OUTCOME ctx as)
        ;; Diagnostics: what each assessed completion (the subject) actually was.
        (let [by-event-id (into {} (map (juxt :event/id identity))
                                (events ctx #{:sheet/node-execution-completed}))]
          (doseq [a (sort-by :requested-at as)
                  :let [c (get by-event-id (:subject-completion-id a))]]
            (println :ARC-SUBJECT
                     (pr-str {:judge (:judge-name a) :status (:status a) :band (:band a)
                              :subject-status (:status c)
                              :failure-kind (:failure-kind c)
                              :writes (:write-keys c)
                              :exec-context (:exec-context c)
                              :rejected (:rejected-write-keys c)}))))
        (doseq [c (events ctx #{:sheet/node-execution-completed})
                :when (and (not= :success (:status c)) (not (:assessment-origin-tick c)))]
          (println :ARC-NONSUCCESS-COMPLETION
                   (pr-str (select-keys c [:node-id :tick-id :status :failure-kind :error :message
                                           :provider-evidence :raw-response :exec-context :usage :model])))) 

        (testing "1. the right assessments exist and every one is terminal"
          (is settled? (str "8 terminal assessments expected, got "
                            (pr-str (frequencies (map (juxt :judge-name :status) as)))))
          (is (= 6 (count (by-node note-id))) "3 grounding + 3 Jev on note")
          (is (= {"grounding" 3 "jev-monitor" 3} (frequencies (map :judge-name (by-node note-id)))))
          (is (= 1 (count (by-node summarize-id))))
          (is (= 1 (count (by-node brief-id))))
          (is (= 3 (count (set (map :subject-completion-id (by-judge "grounding")))))
              "distinct subjects per iteration (grounding)")
          (is (= 3 (count (set (map :subject-completion-id (by-judge "jev-monitor")))))
              "distinct subjects per iteration (Jev)")
          (is (= 3 (count (set (map :exec-context (by-node note-id)))))
              "each iteration carries its own execution context")
          (is (every? #(#{:scored :failed :ungradable} (:status %)) as)))

        (testing "2. legacy events: only learning + feedback; Jev never"
          (let [legacy (events ctx #{:judge/score-emitted})
                jev (by-judge "jev-monitor")]
            (println :ARC-LEGACY (pr-str (mapv #(select-keys % [:judge-name :assessment-id :band :score]) legacy)))
            (is (= 3 (count jev)))
            (doseq [a jev]
              (is (= :scored (:status a)) (pr-str (select-keys a [:status :reason :message])))
              (is (integer? (:band a)))
              (is (number? (:score a)))
              (is (not (contains? a :feedback)) "a score-only judge writes no feedback"))
            (is (not-any? #(= "jev-monitor" (:judge-name %)) legacy) "Jev never emits a legacy score")
            (let [scored-with-feedback (filterv #(and (= :scored (:status %)) (not= "jev-monitor" (:judge-name %))
                                                      (some? (:feedback %)))
                                                as)]
              (is (= (set (map :assessment-id scored-with-feedback))
                     (set (keep :assessment-id legacy)))
                  "exactly the scored learning outcomes with feedback emit a legacy event")
              (is (every? #(integer? (:band %)) legacy))
              (is (= (count scored-with-feedback) (count legacy)) "one legacy event each"))))

        (testing "3. the root judge ran after every other assessment settled and saw all seven"
          (let [root (first (by-judge "root"))
                others (remove #(= "root" (:judge-name %)) as)
                terminals (events ctx terminal-types)
                other-ids (set (map :assessment-id others))
                ;; event ids are time-ordered UUIDv7s: text order is append order
                last-other (last (sort (map #(str (:event/id %))
                                            (filter #(other-ids (:assessment-id %)) terminals))))
                tick-started (first (events ctx #{:sheet/tree-tick-started}))
                root-tick (:judge-tick-id root)
                root-start (some #(when (= root-tick (:tick-id %)) %)
                                 (events ctx #{:sheet/tree-tick-started}))
                family (when root-tick (sheet/get-execution-family ctx root-tick))
                grade (some #(when (= "grade-brief" (:node-name %)) %) family)
                handed (:child-assessments (:inputs grade))]
            (is (some? tick-started))
            (is (= 7 (count (:depends-on root))) "the root request waits on seven assessments")
            (is (= (set (map :assessment-id others)) (set (:depends-on root))))
            (is (some? root-start) "the root judge ran")
            (when root-start
              (is (pos? (compare (str (:event/id root-start)) last-other))
                  "the root judge's tick started after the last other outcome was recorded"))
            (is (= 7 (count handed)) (pr-str (map #(select-keys % [:judge-name :status]) handed)))
            (is (= (set (map :assessment-id others)) (set (keep :assessment-id handed)))
                "it was handed exactly the seven child assessments, by id")))

        (testing "4. nothing inside a judge's own run was assessed (assessment origin)"
          (let [requests (events ctx #{:evaluation/assessment-requested})
                judge-ticks (set (keep :judge-tick-id as))]
            (println :ARC-ORIGIN (pr-str {:requests (count requests)
                                          :judge-ticks (count judge-ticks)}))
            (is (= 8 (count requests)))
            (is (= 0 (count (filter #(some? (sheet/assessment-origin ctx (:tick-id %))) requests)))
                "requests whose tick has an assessment origin")
            (is (= 8 (count judge-ticks)) "each assessment ran its own judge tick")
            (is (every? #(some? (sheet/assessment-origin ctx %)) judge-ticks)
                "control: the judge runs carry the origin")
            (is (empty? (filter #(judge-ticks (:tick-id %)) requests))
                "no request names a tick of a judge's run")))

        (testing "5. model provenance and usage"
          (let [scored (filterv #(= :scored (:status %)) as)
                provenance-of (fn [a] (first (:model-provenance a)))
                models (fn [a] (str (:resolved-model (provenance-of a)) "|" (:requested-model (provenance-of a))
                                    "|" (:model (provenance-of a))))
                family (sheet/get-family-usage ctx (:trace-id result))]
            (println :ARC-PROVENANCE (pr-str (mapv (fn [a] {:judge (:judge-name a) :status (:status a)
                                                            :provenance (:model-provenance a)})
                                                   as)))
            (println :ARC-PRODUCER-USAGE (pr-str family))
            (is (seq scored))
            (doseq [a scored]
              (is (seq (:model-provenance a)) (pr-str (select-keys a [:judge-name])))
              (if (= "jev-monitor" (:judge-name a))
                (is (str/includes? (models a) "jev") (models a))
                (is (str/includes? (models a) "glm-5.3-flash") (models a))))
            (is (every? #(pos? (or (get-in (provenance-of %) [:usage :total-tokens]) 0))
                        (remove #(= "jev-monitor" (:judge-name %)) scored))
                "chat judges carry usage")
            (println :ARC-JEV-USAGE (pr-str (mapv #(get-in (provenance-of %) [:usage])
                                                  (filter #(= "jev-monitor" (:judge-name %)) scored))))
            (is (pos? (:total-tokens family)) "the producer family used tokens")))

        (testing "6. performance of the note node"
          (let [perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id note-id})
                v (first (:versions perf))
                stat (fn [n] (first (filter #(= n (:judge-name %)) (:judges v))))]
            (println :ARC-PERF (pr-str perf))
            (is (= 1 (count (:versions perf))))
            (is (nil? (:version-number v)) "a draft node version")
            (is (= 3 (:scored (stat "grounding"))))
            (is (= 3 (:scored (stat "jev-monitor"))))))

        (testing "7. composites: only when two or more learning judges were expected"
          (let [composites (events ctx #{:judge/composite-score-computed})]
            (println :ARC-COMPOSITES (pr-str (count composites)))
            (is (empty? composites)
                "every subject had exactly one learning judge (Jev monitors only): no composite")))

        ;; ---------------------------------------------------------------------
        ;; 8. publish and re-run
        ;; ---------------------------------------------------------------------
        (let [published (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "arc v1"))
              _ (is (not (h/is-anomaly? published)) (pr-str published))
              result2 (sheet/execute ctx sheet-id {:facts facts} :use-version 1 :timeout-ms 240000)
              _ (println :ARC-PUBLISHED-PRODUCER (pr-str {:status (:status result2) :executed-version (:executed-version result2)
                                                         :error (:error result2)}))
              settled2? (await-settled ctx 1 8 420000)
              as2 (assessments-of ctx 1)]
          (report-outcomes :ARC-OUTCOME-V1 ctx as2)
          (testing "8. the published version is judged via the source nodes and kept apart"
            (is (= :success (:status result2)))
            (is (= 1 (:executed-version result2)))
            (is settled2? (pr-str (frequencies (map (juxt :judge-name :status) as2))))
            (is (= #{note-id summarize-id brief-id} (set (map :node-id as2)))
                "assessed through the draft (source) nodes")
            (is (= 6 (count (filter #(= note-id (:node-id %)) as2))))
            (let [perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id note-id})
                  by-number (into {} (map (juxt :version-number identity)) (:versions perf))
                  stat (fn [v n] (first (filter #(= n (:judge-name %)) (:judges v))))]
              (println :ARC-PERF-V1 (pr-str perf))
              (is (= #{nil 1} (set (keys by-number))))
              (is (= 2 (count (set (map :node-version (:versions perf))))))
              (is (= 3 (:scored (stat (get by-number 1) "grounding"))))
              (is (= 3 (:scored (stat (get by-number 1) "jev-monitor"))))
              (is (= 3 (:scored (stat (get by-number nil) "grounding"))) "the draft's cell is untouched")
              (is (= 6 (:scored (first (filter #(= "grounding" (:judge-name %)) (:rollup perf))))
                     ) "the rollup spans both versions"))))

        ;; ---------------------------------------------------------------------
        ;; 6 (alerts), judged last so the alert stage has had every chance to run
        ;; ---------------------------------------------------------------------
        (testing "6. alert signals follow the declared window rule"
          (let [draft-version (:node-version (first (by-judge "grounding")))
                outcomes (filterv #(and (terminal-types (:event/type %))
                                        (= "grounding" (:judge-name %))
                                        (= draft-version (:node-version %)))
                                  (events ctx terminal-types))
                expected (expected-alert-signal outcomes alert)
                _ (when expected
                    (until #(seq (filter (fn [e] (and (= "grounding" (:judge-name e))
                                                      (= draft-version (:node-version e))))
                                         (events ctx signal-types)))
                           30000))
                _ (Thread/sleep 3000)
                signals (filterv #(and (= "grounding" (:judge-name %))
                                       (= draft-version (:node-version %)))
                                 (events ctx signal-types))]
            (println :ARC-ALERTS (pr-str {:outcomes (mapv (juxt :event/type :score) outcomes)
                                          :expected expected
                                          :signals (mapv :event/type signals)}))
            (is (= 3 (count outcomes)))
            (is (every? #(= alert (:alert %)) outcomes) "every outcome carries the declared alert")
            (is (= (if expected [expected] []) (mapv :event/type signals))
                "the window rule: one crossing if the trailing mean is below, else none")
            (is (not-any? #(= "jev-monitor" (:judge-name %)) (events ctx signal-types))
                "the Jev monitor declared no alert")))

        (println :ARC-PHYSICAL-CALLS
                 (pr-str {:completions-with-model (count (filter :model (events ctx #{:sheet/node-execution-completed})))
                          :ticks (count (events ctx #{:sheet/tree-tick-started}))}))))))
