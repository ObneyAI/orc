(ns ai.obney.orc.orc-service.cc13-injection-record-test
  "CC-13 — the injection record and a default-off randomized holdout.

   Every R-Inject render must leave a durable, joinable record of WHAT was
   injected, AT WHAT VERSION, and UNDER WHICH ARM (treatment vs holdout).
   Without it the deferred budget-governed render change can only be measured
   observationally — which is exactly what made the earlier pattern-injection
   cost table uninterpretable.

   The record is a strict SUBSET of the deferred `:intervention/*` ledger
   (research-lessons-integration §6) so it can WIDEN into it rather than be
   replaced.

   Every assertion here reads the PROJECTION back — never a command's return
   value."
  (:require [ai.obney.orc.evaluation.interface :as evaluation]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as th]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))


(defn- mk-structural-candidate
  [target-id fitness content]
  {:content content
   :document-id (str ":tree-fingerprint:" target-id)
   :document-metadata {:granularity :tree-fingerprint :target-id target-id}
   :fitness-score fitness
   :reasoning (str "candidate " target-id " scored " fitness)
   :rerank-source :reranker})

(defn- mk-behavioral-entry
  [behavior-id confidence]
  {:behavior-id behavior-id
   :confidence confidence
   :was-fresh-mint? false
   :reasoning (str "behavior " behavior-id " scored " confidence)
   :rerank-source :reranker})

(defn- mk-payload
  [{:keys [structural-candidates behaviors assigned-tree-id was-fresh-mint?]}]
  {:structural {:assigned-tree-id assigned-tree-id
                :confidence 0.92
                :was-fresh-mint? (boolean was-fresh-mint?)
                :reasoning "structural reasoning"
                :top-candidates (vec structural-candidates)
                :rerank-fallback? false}
   :behavioral {:behaviors (vec behaviors)
                :rerank-fallback? false}})

(defn- mk-node
  [instruction payload]
  {:id (random-uuid)
   :type :repl-researcher
   :name "cc13-node"
   :model "anthropic/claude-opus-4"
   :instruction instruction
   :context {:tree-id (get-in payload [:structural :assigned-tree-id])
             :r05-classifier payload}})

(defn- body-with-version
  [version]
  {:summary "seed summary"
   :capabilities ["c"]
   :strengths []
   :weaknesses []
   :representative-uses ["u"]
   :avoid-when ["a"]
   :version version
   :consolidated-from-event-count 0})


(deftest render-emits-an-injection-record-readable-from-the-projection
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          node (mk-node "Extract the key dates from the RFP."
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.92 "ChunkedExtraction pattern")]
                                     :behaviors []}))
          render-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)]

      (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 3))]
        (tp/apply-r05-classifier-context node render-ctx))

      (let [record (rm/get-injection-record ctx sheet-id tick-id (:id node))]
        (testing "the record LANDED — read back from the projection, not a return value"
          (is (some? record)
              "an injection record exists for [sheet-id tick-id node-id]"))

        (testing "it is an :intervention/* ledger row, typed as a pattern injection"
          (is (= :pattern-injection (:intervention/type record))))

        (testing "it carries the occurrence identity that joins it to the turn"
          (is (= sheet-id (:sheet-id record)))
          (is (= tick-id (:tick-id record)))
          (is (= (:id node) (:node-id record))))))))


(deftest record-names-the-rendered-candidates-their-versions-and-the-dose
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          alt-id (random-uuid)
          behavior-id (random-uuid)
          versions {tree-id 7 alt-id 2 behavior-id 4}
          node (mk-node "Extract the key dates from the RFP."
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.92 "ChunkedExtraction pattern")
                                      (mk-structural-candidate alt-id 0.71 "Sequential reduce pattern")]
                                     :behaviors [(mk-behavioral-entry behavior-id 0.88)]}))
          render-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
          result (with-redefs [ontology/get-description
                               (fn [_ _ target-id]
                                 (body-with-version (get versions target-id 1)))]
                   (tp/apply-r05-classifier-context node render-ctx))
          record (rm/get-injection-record ctx sheet-id tick-id (:id node))
          by-id (into {} (map (juxt :candidate-id identity) (:candidates record)))]

      (testing "every candidate the model actually saw is named, on both axes"
        (is (= #{(str tree-id) (str alt-id) (str behavior-id)}
               (set (map :candidate-id (:candidates record))))
            "structural AND behavioral candidates are recorded")
        (is (= :structural (:axis (by-id (str tree-id)))))
        (is (= :behavioral (:axis (by-id (str behavior-id))))))

      (testing "AT WHAT VERSION — the version of the description body whose content was rendered"
        (is (= 7 (:version (by-id (str tree-id)))))
        (is (= 2 (:version (by-id (str alt-id)))))
        (is (= 4 (:version (by-id (str behavior-id))))
            "a per-candidate version, not one version for the turn"))

      (testing ":candidate-id names the PRIMARY candidate under test (ledger field)"
        (is (= (str tree-id) (:candidate-id record))))

      (testing ":task-class is the structural class the wedge assigned"
        (is (= tree-id (:task-class record))))

      (testing ":model is the model the rendered prompt was destined for"
        (is (= "anthropic/claude-opus-4" (:model record))))

      (testing "the DOSE — how much content was rendered — is what a budget change moves"
        (is (pos? (:rendered-chars record)))
        (is (= (- (count (:instruction result))
                  (count "Extract the key dates from the RFP."))
               (:rendered-chars record))
            "rendered-chars equals the chars the render actually prepended"))

      (testing ":prompt-content-hash identifies the rendered block without storing it"
        (is (string? (:prompt-content-hash record)))
        (is (= 64 (count (:prompt-content-hash record)))
            "SHA-256 hex")))))


(deftest record-excludes-candidates-the-render-did-not-show
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          shown-id (random-uuid)
          below-floor-id (random-uuid)
          node (mk-node "Compare the two contracts."
                        (mk-payload {:assigned-tree-id shown-id
                                     :structural-candidates
                                     [(mk-structural-candidate shown-id 0.90 "shown pattern")
                                      (mk-structural-candidate below-floor-id 0.12 "noise pattern")]
                                     :behaviors []}))
          render-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
          result (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                   (tp/apply-r05-classifier-context node render-ctx))
          record (rm/get-injection-record ctx sheet-id tick-id (:id node))
          recorded-ids (set (map :candidate-id (:candidates record)))]

      (testing "the below-floor candidate really was not rendered"
        (is (not (clojure.string/includes? (:instruction result) "noise pattern"))))

      (testing "and it is therefore not recorded as injected"
        (is (contains? recorded-ids (str shown-id)))
        (is (not (contains? recorded-ids (str below-floor-id)))
            "recording a candidate the model never saw would misattribute the outcome")))))

(deftest fresh-mint-structural-branch-records-no-structural-candidates
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          behavior-id (random-uuid)
          node (mk-node "Do something genuinely novel."
                        (mk-payload {:assigned-tree-id tree-id
                                     :was-fresh-mint? true
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.95 "would-be pattern")]
                                     :behaviors [(mk-behavioral-entry behavior-id 0.8)]}))
          render-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
          result (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                   (tp/apply-r05-classifier-context node render-ctx))
          record (rm/get-injection-record ctx sheet-id tick-id (:id node))]

      (testing "the fresh-mint branch renders guidance, not candidates"
        (is (not (clojure.string/includes? (:instruction result) "would-be pattern"))))

      (testing "so no structural candidate is recorded, and :candidate-id is nil"
        (is (empty? (filter #(= :structural (:axis %)) (:candidates record))))
        (is (nil? (:candidate-id record))))

      (testing "the behavioral axis still rendered, and is still recorded"
        (is (= [(str behavior-id)]
               (map :candidate-id (filter #(= :behavioral (:axis %)) (:candidates record)))))))))


(defn- sheet-with-a-root-leaf!
  "A real sheet with a root leaf, built through real commands."
  [ctx]
  (let [sheet-id (random-uuid)]
    (th/run-command ctx (th/make-create-sheet-command :sheet-id sheet-id
                                                      :name (str "cc13-" sheet-id)))
    (th/run-command ctx (th/make-create-node-command sheet-id :leaf))
    sheet-id))

(deftest record-carries-root-trace-id-and-correlation-id-for-a-child-tick
  (th/with-test-context [ctx]
    (let [sheet-id (sheet-with-a-root-leaf! ctx)
          correlation-id (random-uuid)
          root-tick (random-uuid)
          child-tick (random-uuid)
          tree-id (random-uuid)]

      (th/run-command ctx (assoc (assoc (th/make-tick-tree-command sheet-id :tick-id root-tick) :inputs {})
                                 :correlation-id correlation-id))
      (th/run-command ctx (assoc (assoc (th/make-tick-tree-command sheet-id :tick-id child-tick) :inputs {})
                                 :parent-tick-id root-tick
                                 :correlation-id correlation-id))

      (let [node (mk-node "Phase 2 work."
                          (mk-payload {:assigned-tree-id tree-id
                                       :structural-candidates
                                       [(mk-structural-candidate tree-id 0.9 "pattern")]
                                       :behaviors []}))
            render-ctx (assoc ctx :sheet-id sheet-id :tick-id child-tick
                              :correlation-id correlation-id)]
        (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
          (tp/apply-r05-classifier-context node render-ctx))

        (let [record (rm/get-injection-record ctx sheet-id child-tick (:id node))]
          (testing ":root-trace-id resolves through the tick lineage to the ROOT tick"
            (is (= root-tick (:root-trace-id record))
                "a child-tick injection must join to the run it belongs to, not to itself"))
          (testing ":correlation-id is carried verbatim"
            (is (= correlation-id (:correlation-id record)))))))))

(deftest root-trace-id-of-a-top-level-tick-is-its-own-tick
  (th/with-test-context [ctx]
    (let [sheet-id (sheet-with-a-root-leaf! ctx)
          tick-id (random-uuid)
          tree-id (random-uuid)]
      (th/run-command ctx (assoc (th/make-tick-tree-command sheet-id :tick-id tick-id) :inputs {}))
      (let [node (mk-node "Top-level work."
                          (mk-payload {:assigned-tree-id tree-id
                                       :structural-candidates
                                       [(mk-structural-candidate tree-id 0.9 "pattern")]
                                       :behaviors []}))]
        (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
          (tp/apply-r05-classifier-context
            node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
        (let [record (rm/get-injection-record ctx sheet-id tick-id (:id node))]
          (is (= tick-id (:root-trace-id record))))))))


(defn- record-judge-score!
  [ctx sheet-id tick-id node-id judge-name score]
  (th/run-command ctx {:command/name :evaluation/record-judge-score
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :judge-name judge-name
                       :judge-config {:type :completeness :weight 1.0}
                       :score score
                       :feedback "fixture"
                       :dimensions []}))

(defn- render-in-tick!
  "Render R-Inject for one turn and return the node that rendered."
  [ctx sheet-id tick-id tree-id]
  (let [node (mk-node "Turn instruction."
                      (mk-payload {:assigned-tree-id tree-id
                                   :structural-candidates
                                   [(mk-structural-candidate tree-id 0.9 "pattern")]
                                   :behaviors []}))]
    (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
      (tp/apply-r05-classifier-context node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
    node))

(deftest injection-record-joins-to-that-turns-judge-scores
  (let [get-scores evaluation/get-judge-scores]
   (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-a (random-uuid)
          tick-b (random-uuid)
          tree-a (random-uuid)
          tree-b (random-uuid)
          node-a (render-in-tick! ctx sheet-id tick-a tree-a)
          node-b (render-in-tick! ctx sheet-id tick-b tree-b)]

      (doseq [r [(record-judge-score! ctx sheet-id tick-a (:id node-a) "completeness" 0.9)
                 (record-judge-score! ctx sheet-id tick-b (:id node-b) "completeness" 0.2)]]
        (is (nil? (:cognitect.anomalies/category r))
            (str "judge score fixture must actually land: " (:cognitect.anomalies/message r))))

      (let [records (vals (rm/get-injection-records ctx sheet-id))
            joined (into {} (for [r records]
                              [(:candidate-id r)
                               (mapv :score (get-scores ctx (:sheet-id r) (:node-id r) (:tick-id r)))]))]

        (testing "both turns produced a record"
          (is (= 2 (count records))))

        (testing "each injection joins to ITS OWN turn's judge score"
          (is (= {(str tree-a) [0.9]
                  (str tree-b) [0.2]}
                 joined)
              "candidate A attributed 0.9, candidate B attributed 0.2 — not swapped, not merged"))

        (testing "a bare sheet-id join would have been ambiguous — which is why the pair is the key"
          (is (= 2 (count (filter #(= sheet-id (:sheet-id %)) records)))
              "sheet-id alone selects BOTH turns; only the tick disambiguates")))))))


(deftest render-writes-no-tmp-sidecar
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          sidecar (java.io.File. (str "/tmp/r-inject-trace-" sheet-id ".edn"))]
      (render-in-tick! ctx sheet-id tick-id tree-id)
      (testing "no unbounded per-sheet temp file is left behind"
        (is (not (.exists sidecar))
            (str "render must not write " (.getPath sidecar))))
      (testing "the information it used to hold is in the store instead"
        (is (some? (rm/get-injection-records ctx sheet-id)))))))


(deftest holdout-is-off-by-default
  (testing "the shipped default config holds out nothing"
    (is (false? (:enabled? service-logic/*injection-holdout*)))
    (is (zero? (:fraction service-logic/*injection-holdout*))))

  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          node (mk-node "Turn instruction."
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.9 "pattern")]
                                     :behaviors []}))
          result (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                   (tp/apply-r05-classifier-context
                     node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
          record (rm/get-injection-record ctx sheet-id tick-id (:id node))]

      (testing "with no configuration at all, the turn is TREATED"
        (is (= :treatment (:arm record)))
        (is (clojure.string/starts-with? (:instruction result)
                                         "## Suggested patterns from corpus")))

      (testing "and the propensity honestly records that everyone was treated"
        (is (= 1.0 (:selection-propensity record))))

      (testing "the baseline policy is named on the treated arm too — otherwise the comparison has no named control"
        (is (string? (:baseline-policy-id record)))))))


(deftest holdout-arm-renders-without-the-treatment-and-is-stamped
  (th/with-test-context [ctx]
    (binding [service-logic/*injection-holdout* {:enabled? true
                                      :fraction 1.0
                                      :baseline-policy-id "cc13-experiment/no-injection"}]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            tree-id (random-uuid)
            original "Turn instruction."
            node (mk-node original
                          (mk-payload {:assigned-tree-id tree-id
                                       :structural-candidates
                                       [(mk-structural-candidate tree-id 0.9 "pattern")]
                                       :behaviors []}))
            result (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                     (tp/apply-r05-classifier-context
                       node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
            record (rm/get-injection-record ctx sheet-id tick-id (:id node))]

        (testing "the control condition's prompt is the UNTREATED instruction"
          (is (= original (:instruction result))
              "nothing was prepended — that is what makes the comparison causal"))

        (testing "the assignment is stamped on the event"
          (is (= :holdout (:arm record)))
          (is (= "cc13-experiment/no-injection" (:baseline-policy-id record)))
          (is (= 0.0 (:selection-propensity record))))

        (testing "the dose is zero and no block was hashed"
          (is (zero? (:rendered-chars record)))
          (is (nil? (:prompt-content-hash record))))

        (testing "but the candidate set it was DENIED is still recorded — otherwise the arms are not comparable"
          (is (= [(str tree-id)] (map :candidate-id (:candidates record)))))))))


(deftest holdout-is-configurable-per-context
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          original "Turn instruction."
          node (mk-node original
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.9 "pattern")]
                                     :behaviors []}))
          result (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                   (tp/apply-r05-classifier-context
                     node (assoc ctx :sheet-id sheet-id :tick-id tick-id
                                 :injection-holdout {:enabled? true :fraction 1.0})))
          record (rm/get-injection-record ctx sheet-id tick-id (:id node))]
      (testing "an experiment can turn the holdout on for one run without rebinding a global"
        (is (= :holdout (:arm record)))
        (is (= original (:instruction result)))))))

(deftest holdout-assignment-is-an-injectable-seam
  (th/with-test-context [ctx]
    (let [seen (atom [])
          sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          node (mk-node "Turn instruction."
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.9 "pattern")]
                                     :behaviors []}))]
      (binding [service-logic/*injection-holdout* {:enabled? true :fraction 0.25}
                service-logic/*holdout-assignment* (fn [occurrence fraction]
                                          (swap! seen conj [occurrence fraction])
                                          true)]
        (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
          (tp/apply-r05-classifier-context
            node (assoc ctx :sheet-id sheet-id :tick-id tick-id))))

      (testing "the fake assignment is honored (so tests never depend on real randomness)"
        (is (= :holdout (:arm (rm/get-injection-record ctx sheet-id tick-id (:id node))))))

      (testing "and it is handed the OCCURRENCE identity plus the configured fraction"
        (is (= [[[sheet-id tick-id (:id node)] 0.25]] @seen))))))

(deftest default-assignment-is-stable-per-occurrence-and-splits-across-occurrences
  (th/with-test-context [ctx]
    (binding [service-logic/*injection-holdout* {:enabled? true :fraction 0.5}]
      (let [sheet-id (random-uuid)
            arm-for (fn [tick-id]
                      (let [tree-id (random-uuid)
                            node (mk-node "Turn instruction."
                                          (mk-payload {:assigned-tree-id tree-id
                                                       :structural-candidates
                                                       [(mk-structural-candidate tree-id 0.9 "pattern")]
                                                       :behaviors []}))]
                        (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                          (tp/apply-r05-classifier-context
                            node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
                        (:arm (rm/get-injection-record ctx sheet-id tick-id (:id node)))))
            arms (mapv (fn [_] (arm-for (random-uuid))) (range 40))]

        (testing "assignment actually SPLITS — it is randomized, not a constant"
          (is (contains? (set arms) :treatment))
          (is (contains? (set arms) :holdout))
          (is (< 8 (count (filter #(= :holdout %) arms)) 32)
              (str "roughly half held out; got " (frequencies arms))))))))

(deftest default-assignment-is-deterministic-for-the-same-occurrence
  (th/with-test-context [ctx]
    (binding [service-logic/*injection-holdout* {:enabled? true :fraction 0.5}]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            tree-id (random-uuid)
            node (mk-node "Turn instruction."
                          (mk-payload {:assigned-tree-id tree-id
                                       :structural-candidates
                                       [(mk-structural-candidate tree-id 0.9 "pattern")]
                                       :behaviors []}))
            render (fn [] (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                            (tp/apply-r05-classifier-context
                              node (assoc ctx :sheet-id sheet-id :tick-id tick-id))))
            first-instruction (:instruction (render))
            second-instruction (:instruction (render))]
        (testing "a retry of the SAME turn must not switch arms mid-turn"
          (is (= first-instruction second-instruction)
              "same occurrence identity ⇒ same assignment ⇒ same rendered prompt"))))))


(deftest rendered-block-capture-is-off-by-default-and-opt-in
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-a (random-uuid)
          tick-b (random-uuid)
          tree-id (random-uuid)
          render! (fn [tick-id extra-ctx]
                    (let [node (mk-node "Turn instruction."
                                        (mk-payload {:assigned-tree-id tree-id
                                                     :structural-candidates
                                                     [(mk-structural-candidate tree-id 0.9 "pattern")]
                                                     :behaviors []}))]
                      (with-redefs [ontology/get-description (fn [_ _ _] (body-with-version 1))]
                        (tp/apply-r05-classifier-context
                          node (merge (assoc ctx :sheet-id sheet-id :tick-id tick-id) extra-ctx)))
                      (rm/get-injection-record ctx sheet-id tick-id (:id node))))]

      (testing "off by default — the standing engine stores no prompt text"
        (is (false? service-logic/*capture-rendered-block?*))
        (is (nil? (:rendered-block (render! tick-a {})))))

      (testing "on when a run asks for it, and it is the block the model actually saw"
        (let [record (render! tick-b {:injection-capture-rendered-block? true})]
          (is (string? (:rendered-block record)))
          (is (clojure.string/starts-with? (:rendered-block record)
                                           "## Suggested patterns from corpus"))
          (is (= (:rendered-chars record) (count (:rendered-block record)))))))))


(deftest fresh-mint-behavioral-marker-is-not-recorded-and-triggers-no-body-read
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          tree-id (random-uuid)
          real-behavior (random-uuid)
          marker-behavior (random-uuid)
          fetched (atom [])
          node (mk-node "Turn instruction."
                        (mk-payload {:assigned-tree-id tree-id
                                     :structural-candidates
                                     [(mk-structural-candidate tree-id 0.9 "pattern")]
                                     :behaviors
                                     [(mk-behavioral-entry real-behavior 0.9)
                                      (assoc (mk-behavioral-entry marker-behavior 0.1)
                                             :was-fresh-mint? true)]}))]
      (with-redefs [ontology/get-description (fn [_ _ target-id]
                                               (swap! fetched conj target-id)
                                               (body-with-version 1))]
        (tp/apply-r05-classifier-context node (assoc ctx :sheet-id sheet-id :tick-id tick-id)))

      (let [record (rm/get-injection-record ctx sheet-id tick-id (:id node))]
        (testing "the marker is not recorded as injected content"
          (is (= #{(str tree-id) (str real-behavior)}
                 (set (map :candidate-id (:candidates record))))))
        (testing "and no description body was fetched for it"
          (is (not (contains? (set @fetched) marker-behavior))))))))
