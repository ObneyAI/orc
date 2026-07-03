(ns ai.obney.orc.ontology.vocabulary-proposal-test
  "MT-7b — the vocabulary PROPOSAL path (explicit new-type admission, ADR-0001).

   An extraction author meeting an entity type the vocabulary missed does NOT
   freelance — it declares an explicit vocabulary proposal
   `{:type … :uri-keying-fields … :description …}` (keying fields drawn from the
   REAL sampled columns). Admission is DETERMINISTIC: validate against the
   sample (normalize-name matching — the same case/separator tolerance
   `recover-via-value` uses); a name collision with the existing vocabulary
   snaps to the existing type (no new type); valid + novel → the container's
   drafts bind against vocab + proposal LOCALLY (no shared mutable state across
   concurrent ticks). Post-extract the orchestrator reconciles proposals:
   normalized-name collisions merge (first in container order wins, aliases
   recorded); same-keying-different-names → BOTH kept + :requires-review —
   NEVER auto-merged (two distinct types can share a key field).

   Tracer 1 — `admit-proposal` (pure): valid+novel → admitted; keying field
   absent from the sample → rejected with a reason; name collision → snap to
   the existing type; the C1 string-form proposal is parsed."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.orc-service.interface :as dsl]))

;; ---------------------------------------------------------------------------
;; Fixtures — generic names only (#12: domain-agnostic; the vocabulary and the
;; sample are the runtime run's own, never baked).
;; ---------------------------------------------------------------------------

(def ^:private vocab
  (vb/canonical-types
   {:entity-types [{:type "Thing" :uri-keying-fields ["id"]}
                   {:type "Job Element"
                    :uri-keying-fields ["element id"]
                    :aliases ["Element"]}]}))

(def ^:private sample-rows
  ;; the REAL sampled rows the proposal's keying fields must resolve against —
  ;; string keys (the csv shape); matching is case/separator-tolerant.
  [{"Part Num" "p-1" "label" "alpha" "id" "1"}
   {"Part Num" "p-2" "label" "beta" "id" "2"}])

;; ---------------------------------------------------------------------------
;; Tracer 1 — admit-proposal
;; ---------------------------------------------------------------------------

(deftest admit-proposal-valid-novel-proposal-is-admitted
  (testing "a proposal whose keying fields resolve against the REAL sample keys
            (case/separator-tolerant — `part num` ≡ `Part Num`) and whose name is
            novel to the vocabulary is ADMITTED, carrying the proposal verbatim"
    (let [out (vb/admit-proposal vocab sample-rows
                                 {:type "Widget"
                                  :uri-keying-fields ["part num"]
                                  :description "One physical widget in this source."})]
      (is (= :admitted (:outcome out)))
      (is (true? (:admitted? out)))
      (is (= "Widget" (:proposed out)))
      (is (= {:type "Widget"
              :uri-keying-fields ["part num"]
              :description "One physical widget in this source."}
             (:proposal out))
          "the admitted proposal carries the type + keying fields + description
           as proposed (the fields matched the sample case-tolerantly)"))))

(deftest admit-proposal-keying-field-absent-from-sample-is-rejected
  (testing "a keying field that resolves against NO sampled column REJECTS the
            proposal with an honest reason + the missing fields (never a silent
            admit of an unkeyable type — #5)"
    (let [out (vb/admit-proposal vocab sample-rows
                                 {:type "Widget"
                                  :uri-keying-fields ["serial number"]
                                  :description "d"})]
      (is (= :rejected (:outcome out)))
      (is (false? (:admitted? out)))
      (is (= :keying-field-not-in-sample (:reason out)))
      (is (= ["serial number"] (:missing-fields out))
          "the unresolvable fields are named"))
    (testing "one bad field among good ones still rejects (a partial key would
              mis-key every canonical URI)"
      (let [out (vb/admit-proposal vocab sample-rows
                                   {:type "Widget"
                                    :uri-keying-fields ["part num" "serial number"]})]
        (is (= :rejected (:outcome out)))
        (is (= ["serial number"] (:missing-fields out)))))
    (testing "an EMPTY sample resolves no field — rejected honestly, never
              admitted unvalidated"
      (is (= :rejected (:outcome (vb/admit-proposal vocab []
                                                    {:type "Widget"
                                                     :uri-keying-fields ["part num"]})))))))

(deftest admit-proposal-name-collision-snaps-to-existing-type
  (testing "a proposal whose name collides with the vocabulary (normalized-EXACT,
            case/separator variant) IS the existing type — SNAP, no new type"
    (let [out (vb/admit-proposal vocab sample-rows
                                 {:type "job-element"
                                  :uri-keying-fields ["part num"]})]
      (is (= :snapped-to-existing (:outcome out)))
      (is (false? (:admitted? out)) "a snap admits NO new type")
      (is (= "job-element" (:proposed out)))
      (is (= "Job Element" (:canonical-type out))
          "the snap names the CANONICAL spelling"))
    (testing "an ALIAS collision snaps too (aliases are part of the entry's identity)"
      (is (= "Job Element"
             (:canonical-type (vb/admit-proposal vocab sample-rows
                                                 {:type "element"
                                                  :uri-keying-fields ["part num"]})))))
    (testing "the snap wins even when the proposal's keying fields are bogus —
              the EXISTING entry's keying fields govern that type's identity"
      (is (= :snapped-to-existing
             (:outcome (vb/admit-proposal vocab sample-rows
                                          {:type "THING"
                                           :uri-keying-fields ["not a column"]})))))))

(deftest admit-proposal-c1-string-form-is-parsed
  (testing "the C1 fragility — the SAME write may arrive as an un-parsed EDN
            STRING; it is parsed back (mirror parse-aggregation-spec) and admitted"
    (let [out (vb/admit-proposal
               vocab sample-rows
               "{:type \"Widget\" :uri-keying-fields [\"part num\"] :description \"d\"}")]
      (is (= :admitted (:outcome out)))
      (is (= "Widget" (get-in out [:proposal :type])))
      (is (= ["part num"] (get-in out [:proposal :uri-keying-fields])))))
  (testing "an UNPARSEABLE non-blank string is a REJECTION with a reason —
            never a silent no-proposal (#5)"
    (let [out (vb/admit-proposal vocab sample-rows "{not-parseable-edn")]
      (is (= :rejected (:outcome out)))
      (is (= :unparseable-proposal (:reason out))))))

(deftest admit-proposal-degenerate-inputs
  (testing "nil / blank — NO proposal was made (the optional write is absent) →
            nil, not a rejection (the honest no-proposal case)"
    (is (nil? (vb/admit-proposal vocab sample-rows nil)))
    (is (nil? (vb/admit-proposal vocab sample-rows "   "))))
  (testing "a blank/missing :type cannot name a type — rejected"
    (is (= :blank-type (:reason (vb/admit-proposal vocab sample-rows
                                                   {:uri-keying-fields ["part num"]}))))
    (is (= :blank-type (:reason (vb/admit-proposal vocab sample-rows
                                                   {:type "  " :uri-keying-fields ["part num"]})))))
  (testing "no usable keying fields — rejected (an unkeyable type cannot mint
            canonical URIs)"
    (is (= :no-keying-fields (:reason (vb/admit-proposal vocab sample-rows
                                                         {:type "Widget"}))))
    (is (= :no-keying-fields (:reason (vb/admit-proposal vocab sample-rows
                                                         {:type "Widget"
                                                          :uri-keying-fields []}))))))

;; ---------------------------------------------------------------------------
;; Tracer 2 — reconcile-proposals (pure, post-extract, container order):
;; normalized-name collisions MERGE (first wins the spelling AND the entry;
;; variants recorded as :aliases + the deterministic :alias-map draft snap);
;; same-keying-different-names → BOTH kept + :requires-review — NEVER
;; auto-merged; disjoint proposals pass through; empty → honest zero.
;; ---------------------------------------------------------------------------

(deftest reconcile-proposals-name-collision-variants-merge-first-wins
  (testing "two containers proposing normalized-name variants of the SAME type
            merge to ONE admitted entry — FIRST in container order wins the
            spelling; the variant spelling becomes an :aliases entry and an
            :alias-map rewrite (the deterministic draft snap)"
    (let [out (vb/reconcile-proposals
               [{:type "work-activity" :uri-keying-fields ["activity id"]}
                {:type "Work Activity" :uri-keying-fields ["Activity ID"]}])]
      (is (= [{:type "work-activity" :uri-keying-fields ["activity id"]
               :aliases ["Work Activity"]}]
             (:admitted out))
          "ONE admitted entry; the first proposal wins spelling AND keying fields")
      (is (= {"Work Activity" "work-activity"} (:alias-map out))
          "the variant spelling rewrites to the winning spelling")
      (is (= 1 (:merged out)))
      (is (= [] (:requires-review out))
          "a NAME merge is not a review case — the two were the same type")))
  (testing "the merge is ORDER-DETERMINISTIC — reversing the container order
            reverses the winner"
    (is (= "Work Activity"
           (-> (vb/reconcile-proposals
                [{:type "Work Activity" :uri-keying-fields ["Activity ID"]}
                 {:type "work-activity" :uri-keying-fields ["activity id"]}])
               :admitted first :type))))
  (testing "an IDENTICAL-spelling duplicate merges without a fake alias"
    (let [out (vb/reconcile-proposals
               [{:type "Widget" :uri-keying-fields ["part num"]}
                {:type "Widget" :uri-keying-fields ["part num"]}])]
      (is (= [{:type "Widget" :uri-keying-fields ["part num"]}] (:admitted out)))
      (is (= {} (:alias-map out)))
      (is (= 1 (:merged out))))))

(deftest reconcile-proposals-same-keying-different-names-never-auto-merged
  (testing "two proposals sharing the SAME normalized keying-field SET under
            DISTINCT names are BOTH kept and surfaced :requires-review — NEVER
            auto-merged (two distinct types can legitimately share a key field;
            the post-landing dedup cascade is the right court)"
    (let [out (vb/reconcile-proposals
               [{:type "Alpha" :uri-keying-fields ["ref id"]}
                {:type "Beta" :uri-keying-fields ["Ref ID"]}])]
      (is (= ["Alpha" "Beta"] (mapv :type (:admitted out)))
          "BOTH types stay admitted — no over-merge")
      (is (= 0 (:merged out)) "nothing was merged")
      (is (= 1 (count (:requires-review out))))
      (is (= {:keying-fields ["refid"] :types ["Alpha" "Beta"]}
             (select-keys (first (:requires-review out)) [:keying-fields :types]))
          "the review entry names the shared normalized keying set + both types")
      (is (= {} (:alias-map out)) "no draft is rewritten across the pair"))))

(deftest reconcile-proposals-disjoint-proposals-pass-through
  (testing "proposals with distinct names AND distinct keying sets pass through
            unchanged, in container order"
    (let [ps [{:type "Alpha" :uri-keying-fields ["a id"]}
              {:type "Beta" :uri-keying-fields ["b id"]}]
          out (vb/reconcile-proposals ps)]
      (is (= ps (:admitted out)))
      (is (= {:alias-map {} :merged 0 :requires-review []}
             (select-keys out [:alias-map :merged :requires-review]))))))

(deftest reconcile-proposals-empty-is-honest-zero
  (testing "no proposals in → honest zero out"
    (is (= {:admitted [] :alias-map {} :merged 0 :requires-review []}
           (vb/reconcile-proposals [])))
    (is (= {:admitted [] :alias-map {} :merged 0 :requires-review []}
           (vb/reconcile-proposals nil)))))

;; ===========================================================================
;; Tracer 3 — the APPLY seam (public `apply-transform-for-container-code`) over
;; a REAL csv stream (the 7a fixture pattern — no mocked source). Admission is
;; LOCAL: a valid proposal extends THIS container's binding vocabulary only —
;; drafts typed with the proposed name LAND; an INVALID proposal is rejected and
;; its drafts stay excluded as vocabulary freelancing (the honest 7a path); the
;; per-container report surfaces the admission outcome either way.
;; ===========================================================================

(defn- write-flat-csv!
  "A temp one-row-per-entity csv: entity,element,value. Returns the path."
  []
  (let [f (java.io.File/createTempFile "mt7b-flat" ".csv")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (.write w "entity,element,value\n")
      (.write w "e1,A,5\n")
      (.write w "e2,B,4\n")
      (.write w "e3,C,3\n"))
    (.getAbsolutePath f)))

(def ^:private csv-sample
  ;; the REAL string-keyed csv sample the SAMPLE node would have written — the
  ;; rows the proposal's keying fields validate against.
  [{"entity" "e1" "element" "A" "value" "5"}
   {"entity" "e2" "element" "B" "value" "4"}])

(def ^:private thing-vocab-model-spec
  ;; the discovered vocabulary MISSES the type this container holds (#12 —
  ;; generic names only).
  {:entity-types [{:type "Thing" :uri-keying-fields ["id"]}]})

(defn- widget-transform
  "A per-row transform-source minting one draft per row under the given
   entity-type (string). csv rows are string-keyed."
  [entity-type]
  (str "(fn [row]
          {:concept-drafts
           [{:uri (str \"row:\" (get row \"entity\"))
             :label (str (get row \"element\"))
             :entity-type \"" entity-type "\"
             :attributes {\"entity\" (get row \"entity\")}}]
           :relationship-drafts []})"))

(deftest apply-seam-valid-proposal-admits-locally-and-drafts-land
  (testing "an author that meets a type the vocabulary missed and PROPOSES it
            (keying fields from the REAL sampled columns) → the container's
            drafts bind against vocab + proposal LOCALLY and LAND; the report
            surfaces the admission"
    (let [path (write-flat-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :sample-rows csv-sample
                         :transform-source (widget-transform "Widget")
                         :entity-type-proposal {:type "Widget"
                                                :uri-keying-fields ["entity"]
                                                :description "One widget row."}}})
          drafts (:concept-drafts out)]
      (is (= 3 (count drafts)) "all 3 per-row drafts LAND (not excluded)")
      (is (every? #(= "Widget" (:entity-type %)) drafts)
          "the drafts carry the PROPOSED type spelling")
      (is (= {:count 0 :types []}
             (select-keys (get-in out [:extraction-report :freelanced-drafts])
                          [:count :types]))
          "nothing is freelanced — the proposal covered the new type")
      (is (= {:outcome :admitted :admitted? true :proposed "Widget"}
             (select-keys (get-in out [:extraction-report :entity-type-proposal])
                          [:outcome :admitted? :proposed]))
          "the per-container report surfaces the admission")
      (is (= {:type "Widget" :uri-keying-fields ["entity"]
              :description "One widget row."}
             (get-in out [:extraction-report :entity-type-proposal :proposal]))
          "the admitted proposal rides the report for the orchestrator to reconcile"))))

(deftest apply-seam-case-variant-drafts-bind-to-the-proposal
  (testing "drafts typed with a case/separator VARIANT of the proposed name SNAP
            to the proposal's spelling — the same normalized-EXACT binding the
            vocabulary itself gets (the proposal is a LOCAL vocabulary entry)"
    (let [path (write-flat-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :sample-rows csv-sample
                         :transform-source (widget-transform "widget")
                         :entity-type-proposal {:type "Widget"
                                                :uri-keying-fields ["entity"]}}})]
      (is (= 3 (count (:concept-drafts out))))
      (is (every? #(= "Widget" (:entity-type %)) (:concept-drafts out))
          "the variant-typed drafts snap to the proposed canonical spelling"))))

(deftest apply-seam-invalid-proposal-rejected-drafts-stay-freelanced
  (testing "an INVALID proposal (keying field not a real sampled column) is
            REJECTED with a reason; drafts typed with it stay EXCLUDED as
            vocabulary freelancing (the honest 7a path — no silent fallback)"
    (let [path (write-flat-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :sample-rows csv-sample
                         :transform-source (widget-transform "Widget")
                         :entity-type-proposal {:type "Widget"
                                                :uri-keying-fields ["serial number"]}}})]
      (is (= [] (:concept-drafts out)) "no draft of the rejected type lands")
      (is (= 0 (:concept-count out))
          "the flat concept-count reflects the exclusion (the EB9 gate can fire)")
      (is (= {:count 3 :types ["Widget"]}
             (select-keys (get-in out [:extraction-report :freelanced-drafts])
                          [:count :types]))
          "the excluded drafts surface as freelancing, per as-emitted type")
      (is (= {:outcome :rejected :admitted? false :proposed "Widget"
              :reason :keying-field-not-in-sample :missing-fields ["serial number"]}
             (select-keys (get-in out [:extraction-report :entity-type-proposal])
                          [:outcome :admitted? :proposed :reason :missing-fields]))
          "the rejection + reason are surfaced — never a silent drop"))))

(deftest apply-seam-name-collision-proposal-snaps-no-new-type
  (testing "a proposal whose name collides with the vocabulary snaps to the
            existing type — drafts typed with it land under the EXISTING
            canonical spelling; no new type is admitted"
    (let [path (write-flat-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :sample-rows csv-sample
                         :transform-source (widget-transform "thing")
                         :entity-type-proposal {:type "thing"
                                                :uri-keying-fields ["entity"]}}})]
      (is (= 3 (count (:concept-drafts out))))
      (is (every? #(= "Thing" (:entity-type %)) (:concept-drafts out))
          "drafts land under the EXISTING canonical spelling")
      (is (= {:outcome :snapped-to-existing :admitted? false :canonical-type "Thing"}
             (select-keys (get-in out [:extraction-report :entity-type-proposal])
                          [:outcome :admitted? :canonical-type]))))))

(deftest apply-seam-no-proposal-is-behavior-preserving
  (testing "with NO proposal the seam behaves exactly as 7a left it — the report
            carries no :entity-type-proposal key (never a claimed-but-not-made
            proposal)"
    (let [path (write-flat-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :sample-rows csv-sample
                         :transform-source (widget-transform "Thing")}})]
      (is (= 3 (count (:concept-drafts out))))
      (is (not (contains? (:extraction-report out) :entity-type-proposal))))))

;; ---------------------------------------------------------------------------
;; Piece 1 wiring — the AUTHOR contract: the proposal guidance block is appended
;; to BOTH author paths (like the 7a binding block) and both authors declare the
;; optional :entity-type-proposal write; both APPLY nodes read it.
;; ---------------------------------------------------------------------------

(deftest proposal-guidance-is-binding-and-domain-agnostic
  (testing "the proposal guidance names the write key, requires keying fields
            copied from the REAL sampled columns, forbids freelancing, and bakes
            in NO domain term (#12)"
    (let [g (vb/vocabulary-proposal-guidance)
          lg (str/lower-case g)]
      (is (str/includes? g "entity-type-proposal") "names the write key")
      (is (str/includes? g "uri-keying-fields") "names the keying-fields field")
      (is (str/includes? lg "sample") "grounds the keying fields in the sample")
      (is (str/includes? lg "freelance") "names the banned move")
      (doseq [leak ["o*net" "onet" "occupation" "cip" "soc" "job zone" "scale id"]]
        (is (not (str/includes? lg leak))
            (str "the proposal guidance must not bake in the vertical term: " leak))))))

(deftest proposal-contract-is-wired-into-both-author-paths
  (testing "BOTH the resilient (primary + robust) and the flat author :llm nodes
            carry the proposal guidance + declare the :entity-type-proposal
            write; the APPLY nodes read it"
    (let [nodes-of (fn [unit-def]
                     (let [nodes (atom [])
                           walk (fn walk [n]
                                  (when (map? n)
                                    (when (:name n) (swap! nodes conj n))
                                    (doseq [v (vals n)]
                                      (cond (map? v) (walk v)
                                            (sequential? v) (doseq [x v] (walk x))))))]
                       (walk (:root-node unit-def))
                       @nodes))
          marker "VOCABULARY PROPOSAL"
          check (fn [unit-def expected-authors]
                  (let [nodes (nodes-of unit-def)
                        authors (filter #(re-find #"author$|^author$" (str (:name %))) nodes)
                        applies (filter #(re-find #"apply" (str (:name %))) nodes)]
                    (is (= expected-authors (count authors)))
                    (doseq [a authors]
                      (is (str/includes? (str (:instruction a)) marker)
                          (str (:name a) " carries the proposal guidance"))
                      (is (some #{:entity-type-proposal} (:writes a))
                          (str (:name a) " declares the :entity-type-proposal write")))
                    (doseq [ap applies]
                      (is (some #{:entity-type-proposal} (:reads ap))
                          (str (:name ap) " reads the proposal")))))]
      (check (extract/extract-per-container-def {}) 2)
      (check (extract/extract-per-container-def {:resilient? false}) 1))))

;; ===========================================================================
;; Tracer 4 — the ORCHESTRATOR reconciliation (public
;; `orchestrate-extract-containers`, stubbed child ticks mirroring the existing
;; orchestrator tests). Concurrent per-container ticks admitted their proposals
;; LOCALLY; here their proposals MEET: name-variants merge to ONE type (drafts
;; from both containers canonicalize to ONE URI scheme); a keying-collision
;; pair stays BOTH-kept + :requires-review; the aggregate report carries the
;; full LEDGER.
;; ===========================================================================

(defn- run-orchestrator-with-stubbed-children
  "Drive `orchestrate-extract-containers` with stubbed child ticks: one entry of
   `container->bb` per container (name → the child blackboard the per-container
   unit would have left: drafts + extraction-report incl. any admission)."
  [model-spec container->bb]
  (let [tick->container (atom {})]
    (with-redefs [extract/list-source-containers
                  (fn [_] (mapv (fn [n] {:name n}) (keys container->bb)))
                  extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                  dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                (swap! tick->container assoc tick-id
                                       (get-in inputs ["container" :name]))
                                {:status :success})
                  dsl/get-tick-blackboard
                  (fn [_ tick-id]
                    (get container->bb (get @tick->container tick-id)))]
      (extract/orchestrate-extract-containers
       {:inputs {:source {:type :sql :path "/tmp/x.db"}
                 :model-spec model-spec}
        :tick-id (random-uuid) :event-store :stub}))))

(defn- child-bb
  "A stubbed per-container child blackboard: the drafts + a minimal extraction
   report carrying the container's LOCAL admission outcome (when any)."
  [drafts admission]
  {:concept-drafts {:value drafts}
   :relationship-drafts {:value []}
   :extraction-report {:value (cond-> {:rows-streamed (count drafts) :rows-errored 0}
                                admission (assoc :entity-type-proposal admission))}})

(def ^:private base-model-spec
  {:entity-types [{:type "Thing" :uri-keying-fields ["id"]}]})

(deftest orchestrator-merges-name-variant-proposals-to-one-uri-scheme
  (testing "two containers proposing NAME VARIANTS of the same new type → ONE
            admitted type (first in container order wins the spelling); drafts
            from BOTH containers canonicalize to ONE URI scheme (GC-1 mints
            canonical URIs for the proposed type because the admitted proposals
            extend the model-spec LOCALLY before canonicalize-drafts)"
    (let [out (run-orchestrator-with-stubbed-children
               base-model-spec
               ;; array-map → deterministic container order: c1 first.
               (array-map
                "c1" (child-bb [{:uri "a1" :label "A1" :entity-type "Widget"
                                 :attributes {"part num" "p-1"}}]
                               {:outcome :admitted :admitted? true :proposed "Widget"
                                :proposal {:type "Widget" :uri-keying-fields ["part num"]}})
                "c2" (child-bb [{:uri "b1" :label "B1" :entity-type "widget"
                                 :attributes {"Part Num" "p-1"}}
                                {:uri "b2" :label "B2" :entity-type "widget"
                                 :attributes {"Part Num" "p-2"}}]
                               {:outcome :admitted :admitted? true :proposed "widget"
                                :proposal {:type "widget" :uri-keying-fields ["Part Num"]}})))
          drafts (:concept-drafts out)
          by-label (into {} (map (juxt :label identity)) drafts)]
      (is (= 3 (count drafts)))
      (is (every? #(= "Widget" (:entity-type %)) drafts)
          "the variant containers' draft :entity-type is REWRITTEN to the
           winning spelling before canonicalize (the deterministic alias snap)")
      (is (= "widget/p-1" (:uri (by-label "A1"))))
      (is (= (:uri (by-label "A1")) (:uri (by-label "B1")))
          "the SAME entity from two containers under variant spellings mints a
           BYTE-IDENTICAL canonical URI — ONE URI scheme, no fragmentation")
      (is (= "widget/p-2" (:uri (by-label "B2"))))
      (is (= [] (get-in out [:extraction-report :canonicalization :degraded]))
          "NO proposed-type draft degrades — the local model-spec extension ran
           BEFORE canonicalize-drafts")
      (let [ledger (get-in out [:extraction-report :vocabulary-proposals])]
        (is (= 2 (:proposed ledger)))
        (is (= [{:type "Widget" :uri-keying-fields ["part num"] :aliases ["widget"]}]
               (:admitted ledger))
            "ONE admitted type; the variant spelling is recorded as an alias")
        (is (= 1 (:merged ledger)))
        (is (= [] (:requires-review ledger)))
        (is (= [] (:rejected ledger))))
      (is (not (contains? out :model-spec))
          "the orchestrator never writes a model-spec — the extension is LOCAL
           to canonicalize; downstream consumers see the pipeline's original"))))

(deftest orchestrator-keying-collision-pair-both-kept-never-auto-merged
  (testing "two containers proposing DISTINCT names over the SAME keying-field
            set → BOTH kept (distinct URI schemes) + surfaced :requires-review —
            NEVER auto-merged"
    (let [out (run-orchestrator-with-stubbed-children
               base-model-spec
               (array-map
                "c1" (child-bb [{:uri "x1" :label "X1" :entity-type "Alpha"
                                 :attributes {"ref id" "r-1"}}]
                               {:outcome :admitted :admitted? true :proposed "Alpha"
                                :proposal {:type "Alpha" :uri-keying-fields ["ref id"]}})
                "c2" (child-bb [{:uri "y1" :label "Y1" :entity-type "Beta"
                                 :attributes {"Ref ID" "r-1"}}]
                               {:outcome :admitted :admitted? true :proposed "Beta"
                                :proposal {:type "Beta" :uri-keying-fields ["Ref ID"]}})))
          drafts (:concept-drafts out)
          ledger (get-in out [:extraction-report :vocabulary-proposals])]
      (is (= ["alpha/r-1" "beta/r-1"] (sort (mapv :uri drafts)))
          "BOTH types keep their OWN URI scheme — sharing a key field value did
           NOT collapse them into one entity")
      (is (= ["Alpha" "Beta"] (mapv :type (:admitted ledger)))
          "both proposals stay admitted")
      (is (= 0 (:merged ledger)))
      (is (= [{:keying-fields ["refid"] :types ["Alpha" "Beta"]}]
             (mapv #(select-keys % [:keying-fields :types]) (:requires-review ledger)))
          "the pair is surfaced :requires-review — the post-landing dedup
           cascade is the court, never an auto-merge here"))))

(deftest orchestrator-ledger-carries-rejections-and-honest-counts
  (testing "a container whose proposal was REJECTED locally surfaces in the
            ledger's :rejected with its container + reason; :proposed counts
            every proposal outcome"
    (let [out (run-orchestrator-with-stubbed-children
               base-model-spec
               (array-map
                "c1" (child-bb [{:uri "a1" :label "A1" :entity-type "Widget"
                                 :attributes {"part num" "p-1"}}]
                               {:outcome :admitted :admitted? true :proposed "Widget"
                                :proposal {:type "Widget" :uri-keying-fields ["part num"]}})
                "c2" (child-bb []
                               {:outcome :rejected :admitted? false :proposed "Gadget"
                                :reason :keying-field-not-in-sample
                                :missing-fields ["nope"]})
                "c3" (child-bb [{:uri "t1" :label "T1" :entity-type "Thing"
                                 :attributes {"id" "1"}}]
                               nil)))
          ledger (get-in out [:extraction-report :vocabulary-proposals])]
      (is (= 2 (:proposed ledger)) "c3 made no proposal — not counted")
      (is (= [{:type "Widget" :uri-keying-fields ["part num"]}] (:admitted ledger)))
      (is (= [{:container "c2" :proposed "Gadget"
               :reason :keying-field-not-in-sample :missing-fields ["nope"]}]
             (:rejected ledger))
          "the rejection is surfaced with its container + reason — never dropped")
      (is (= "thing/1" (:uri (first (filter #(= "T1" (:label %)) (:concept-drafts out)))))
          "the ORIGINAL vocabulary's drafts canonicalize exactly as before")))
  (testing "with NO proposals anywhere the aggregate report carries NO ledger —
            byte-preserving for every existing run"
    (let [out (run-orchestrator-with-stubbed-children
               base-model-spec
               {"c1" (child-bb [{:uri "t1" :label "T1" :entity-type "Thing"
                                 :attributes {"id" "1"}}]
                               nil)})]
      (is (not (contains? (:extraction-report out) :vocabulary-proposals))))))

(deftest orchestrator-guard-proposal-never-overrides-an-original-types-identity
  (testing "a (seam-unreachable, corruption-shaped) 'admitted' proposal whose
            name collides with an ORIGINAL vocabulary type must NOT override
            that type's declared :uri-keying-fields at canonicalize — GC-1
            identity is never rewritten by a proposal"
    (let [out (run-orchestrator-with-stubbed-children
               base-model-spec
               {"c1" (child-bb [{:uri "t1" :label "T1" :entity-type "Thing"
                                 :attributes {"id" "1" "other" "x"}}]
                               {:outcome :admitted :admitted? true :proposed "THING"
                                ;; claims a DIFFERENT keying field for the same type
                                :proposal {:type "THING" :uri-keying-fields ["other"]}})})]
      (is (= "thing/1" (:uri (first (:concept-drafts out))))
          "the draft canonicalizes under the ORIGINAL declared keying field
           (id), not the colliding proposal's (other)"))))
