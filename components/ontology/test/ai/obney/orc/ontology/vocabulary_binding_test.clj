(ns ai.obney.orc.ontology.vocabulary-binding-test
  "MT-7a — vocabulary binding + enforcement seam (PURE core).

   The model-spec's discovered `:entity-types` is the CANONICAL ENTITY-TYPE
   VOCABULARY for all of a source's containers (ADR-0001). Every per-container
   extraction author must emit a declared type; enforcement is deterministic
   normalized-EXACT matching (the SAME case/separator normalization GC-1's
   identity uses) with snap-to-canonical-spelling. NO fuzzy/substring matching —
   `job-zones/occupation` must NOT resolve to `Occupation` (vocabulary
   freelancing stays excluded + surfaced, never silently over-merged).

   Tracer 1 — `resolve-entity-type` (+ its vocab constructor `canonical-types`):
   exact match; case/separator variants snap to the canonical spelling; alias
   match when aliases are present; the no-fuzzy assertion; unknown → nil."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.orc-service.interface :as dsl]))

;; ---------------------------------------------------------------------------
;; The vocab fixture — generic names only (#12: domain-agnostic; the vocabulary
;; is the runtime model-spec's, never baked).
;; ---------------------------------------------------------------------------

(def ^:private model-spec
  {:entity-types [{:type "Occupation" :uri-keying-fields ["code"]}
                  {:type "Job Element" :uri-keying-fields ["element id"]}
                  {:type "Scale"
                   :uri-keying-fields ["scale id"]
                   :aliases ["Measurement Scale" "rating-scale"]}]})

(def ^:private vocab (vb/canonical-types model-spec))

(deftest canonical-types-returns-the-declared-vocabulary
  (testing "canonical-types returns one entry per declared entity-type, carrying
            the canonical :type spelling + its keying fields + aliases when present"
    (is (= ["Occupation" "Job Element" "Scale"] (mapv :type vocab))
        "the canonical :type spellings, in declaration order")
    (is (= ["code"] (:uri-keying-fields (first vocab)))
        "each entry carries its uri-keying-fields")
    (is (= ["Measurement Scale" "rating-scale"] (:aliases (nth vocab 2)))
        "aliases are tolerated and carried when present (schema is {:closed false})")
    (is (nil? (:aliases (first vocab)))
        "an entry without aliases simply has none (match on :type only)"))
  (testing "a string-form :entity-types (the C1 parse fragility) is coerced —
            the SAME coercion normalize-model-spec applies (no fork)"
    (is (= ["Occupation"]
           (mapv :type (vb/canonical-types
                        {:entity-types "[{:type \"Occupation\" :uri-keying-fields [\"code\"]}]"})))))
  (testing "entries with no usable :type are dropped honestly (they cannot bind)"
    (is (= ["Occupation"]
           (mapv :type (vb/canonical-types
                        {:entity-types [{:type "Occupation"} {:uri-keying-fields ["x"]} {:type "   "}]}))))))

(deftest resolve-entity-type-exact-match-returns-canonical-spelling
  (testing "an exact canonical :type resolves to itself (the common case)"
    (is (= "Occupation" (vb/resolve-entity-type vocab "Occupation")))
    (is (= "Job Element" (vb/resolve-entity-type vocab "Job Element")))))

(deftest resolve-entity-type-case-and-separator-variants-snap
  (testing "case/separator variants (the SAME normalization GC-1's identity uses)
            SNAP to the canonical spelling — job-element ≡ Job Element"
    (is (= "Job Element" (vb/resolve-entity-type vocab "job-element")))
    (is (= "Job Element" (vb/resolve-entity-type vocab "job_element")))
    (is (= "Job Element" (vb/resolve-entity-type vocab "JOB ELEMENT")))
    (is (= "Occupation" (vb/resolve-entity-type vocab "occupation")))
    (is (= "Occupation" (vb/resolve-entity-type vocab :occupation))
        "a keyword-form emitted type still resolves (same key-name normalization)")))

(deftest resolve-entity-type-alias-match-when-aliases-present
  (testing "an alias resolves to the entry's CANONICAL :type spelling (not the alias)"
    (is (= "Scale" (vb/resolve-entity-type vocab "Measurement Scale")))
    (is (= "Scale" (vb/resolve-entity-type vocab "rating_scale"))
        "alias matching is the same normalized-EXACT matching"))
  (testing "a C1-mangled NON-sequential :aliases is ignored (never iterated as
            characters) — matching falls back to :type alone for that entry"
    (let [v (vb/canonical-types {:entity-types [{:type "Scale" :aliases "x"}]})]
      (is (= "Scale" (vb/resolve-entity-type v "Scale")))
      (is (nil? (vb/resolve-entity-type v "x"))
          "the mangled alias string does not match (and does not char-iterate)"))))

(deftest resolve-entity-type-no-fuzzy-no-substring
  (testing "NO fuzzy/substring matching — a container-prefixed variant must NOT
            resolve (it would silently over-merge; freelancing must surface)"
    (is (nil? (vb/resolve-entity-type vocab "job-zones/occupation"))
        "\"job-zones/occupation\" does NOT resolve to \"Occupation\"")
    (is (nil? (vb/resolve-entity-type vocab "Occupation Data"))
        "a suffixed variant does not resolve either")
    (is (nil? (vb/resolve-entity-type vocab "Occ"))
        "a prefix/abbreviation does not resolve")))

(deftest resolve-entity-type-unknown-and-degenerate-inputs-are-nil
  (testing "unknown / nil / blank emitted types resolve to nil (pure + total)"
    (is (nil? (vb/resolve-entity-type vocab "Workplace")))
    (is (nil? (vb/resolve-entity-type vocab nil)))
    (is (nil? (vb/resolve-entity-type vocab "")))
    (is (nil? (vb/resolve-entity-type [] "Occupation"))
        "an empty vocabulary resolves nothing (the orchestrator hard-stops upstream)")))

;; ---------------------------------------------------------------------------
;; Tracer 2 — `bind-draft-types`: matching-variant drafts SNAPPED to the
;; canonical spelling; non-matching drafts EXCLUDED + counted per as-emitted
;; type (vocabulary freelancing surfaced, never silently landed); all-match →
;; passthrough; empty drafts → honest zero.
;; ---------------------------------------------------------------------------

(deftest bind-draft-types-snaps-variants-and-excludes-freelancing
  (testing "matching-variant drafts land with :entity-type SNAPPED to the canonical
            spelling; non-matching (freelanced) drafts are EXCLUDED + counted"
    (let [drafts [{:uri "a" :label "a" :entity-type "Occupation"}       ; exact
                  {:uri "b" :label "b" :entity-type "job-element"}      ; variant
                  {:uri "c" :label "c" :entity-type "rating_scale"}     ; alias variant
                  {:uri "d" :label "d" :entity-type "job-zones/occupation"} ; freelanced
                  {:uri "e" :label "e" :entity-type "job-zones/occupation"} ; freelanced (again)
                  {:uri "f" :label "f" :entity-type "Workplace"}        ; freelanced
                  {:uri "g" :label "g"}]                                ; NO declared type
          {:keys [drafts excluded]} (vb/bind-draft-types vocab drafts)]
      (is (= [["a" "Occupation"] ["b" "Job Element"] ["c" "Scale"]]
             (mapv (juxt :uri :entity-type) drafts))
          "matching drafts proceed with the CANONICAL spelling (variant/alias snapped)")
      (is (= {"job-zones/occupation" 2 "Workplace" 1 nil 1}
             (into {} (map (juxt :entity-type :count)) excluded))
          "excluded drafts are COUNTED per as-emitted type — including the
           no-declared-type drafts (every author must emit a declared type)")))
  (testing "non-entity-type fields of a snapped draft are untouched"
    (let [{:keys [drafts]} (vb/bind-draft-types
                            vocab [{:uri "x" :label "X" :entity-type "occupation"
                                    :attributes {"code" "11"} :scope :custom}])]
      (is (= [{:uri "x" :label "X" :entity-type "Occupation"
               :attributes {"code" "11"} :scope :custom}]
             drafts)))))

(deftest bind-draft-types-all-match-is-passthrough
  (testing "drafts already carrying canonical spellings pass through BYTE-IDENTICAL
            (behavior-preserving — the common case)"
    (let [drafts [{:uri "a" :label "a" :entity-type "Occupation" :attributes {"code" "1"}}
                  {:uri "b" :label "b" :entity-type "Job Element"}]
          out (vb/bind-draft-types vocab drafts)]
      (is (= drafts (:drafts out)) "the drafts are unchanged")
      (is (= [] (:excluded out)) "nothing excluded — honest empty"))))

(deftest bind-draft-types-empty-drafts-is-honest-zero
  (testing "no drafts in → no drafts out, nothing excluded (honest zero)"
    (is (= {:drafts [] :excluded []} (vb/bind-draft-types vocab [])))
    (is (= {:drafts [] :excluded []} (vb/bind-draft-types vocab nil)))))

(deftest bind-draft-types-empty-vocabulary-is-passthrough-not-mass-exclusion
  (testing "an EMPTY vocabulary passes drafts through unchanged — enforcement
            against no vocabulary is the ORCHESTRATOR's loud hard stop (piece 4);
            silently excluding 100% here would manufacture a fake 0-draft signal
            that mis-fires the EB9 gate (corrupted measurement, not enforcement)"
    (let [drafts [{:uri "a" :label "a" :entity-type "Anything"}]]
      (is (= {:drafts drafts :excluded []} (vb/bind-draft-types [] drafts)))
      (is (= {:drafts drafts :excluded []} (vb/bind-draft-types nil drafts))))))

;; ---------------------------------------------------------------------------
;; The AUTHOR contract (static prompt, runtime enumeration): the binding block
;; is STATIC text appended to BOTH author prompts (the resilient primary +
;; robust AND the flat path) exactly like `aggregation-author-guidance` — the
;; model-spec (the vocabulary) arrives at RUNTIME as the node's `:model-spec`
;; read. Domain-agnostic (#12).
;; ---------------------------------------------------------------------------

(deftest vocabulary-binding-guidance-is-binding-and-domain-agnostic
  (testing "the guidance requires the entity-type — in the aggregation-spec AND in
            every minted draft — to be a model-spec :type value copied VERBATIM
            (never invented/renamed/prefixed/re-cased), reasoning FIRST, and bakes
            in NO domain term (#12)"
    (let [g (vb/vocabulary-binding-guidance)
          lg (clojure.string/lower-case g)]
      (is (clojure.string/includes? g "entity-type") "names the bound field")
      (is (clojure.string/includes? g "model-spec") "points at the RUNTIME vocabulary input")
      (is (clojure.string/includes? lg "verbatim") "requires the canonical spelling verbatim")
      (is (clojure.string/includes? g "aggregation-spec")
          "binds the aggregating path's spec type too")
      (is (clojure.string/includes? lg "reasoning") "reasoning FIRST (#13)")
      (doseq [banned ["invent" "prefix"]]
        (is (clojure.string/includes? lg banned)
            (str "spells out the banned freelancing move: " banned)))
      (doseq [leak ["o*net" "onet" "occupation" "cip" "soc" "job zone" "scale id"]]
        (is (not (clojure.string/includes? lg leak))
            (str "the binding guidance must not bake in the vertical term: " leak))))))

(deftest binding-guidance-is-appended-to-both-author-paths
  (testing "BOTH the resilient (primary + robust) and the flat author :llm nodes
            carry the binding block — appended like aggregation-author-guidance"
    (let [author-instructions
          (fn [unit-def]
            (let [nodes (atom [])
                  walk (fn walk [n]
                         (when (map? n)
                           (when (:name n) (swap! nodes conj n))
                           (doseq [v (vals n)]
                             (cond (map? v) (walk v)
                                   (sequential? v) (doseq [x v] (walk x))))))]
              (walk (:root-node unit-def))
              (->> @nodes
                   (filter #(re-find #"author$|^author$" (str (:name %))))
                   (mapv (juxt :name :instruction)))))
          marker "CANONICAL ENTITY-TYPE VOCABULARY"
          resilient (author-instructions (extract/extract-per-container-def {}))
          flat (author-instructions (extract/extract-per-container-def {:resilient? false}))]
      (is (= 2 (count resilient)) "the resilient path has primary + robust authors")
      (doseq [[nm instruction] resilient]
        (is (clojure.string/includes? (str instruction) marker)
            (str nm " carries the vocabulary-binding block")))
      (is (= 1 (count flat)) "the flat path has one author")
      (doseq [[nm instruction] flat]
        (is (clojure.string/includes? (str instruction) marker)
            (str nm " carries the vocabulary-binding block"))))))

;; ===========================================================================
;; Tracer 3 — the APPLY seam (public `apply-transform-for-container-code`) over
;; a REAL csv stream (the MT-6 fixture pattern — no mocked source). The binding
;; is a DETERMINISTIC step inside the existing seam (#8, re-orchestrate):
;;   - an aggregation-spec with a VARIANT type → drafts land under the
;;     CANONICAL spelling (snapped BEFORE the aggregating fold);
;;   - a freelanced-type per-row transform → its drafts EXCLUDED +
;;     `:freelanced-drafts` surfaced in the extraction report + the flat
;;     `:concept-count` reflects the exclusion (so the EB9 gate can fire);
;;   - a canonical-type author → behavior-preserving (byte-identical drafts).
;; ===========================================================================

(defn- write-tall-csv!
  "A temp repeating-key csv: entity,element,value. Two entities, several element
   rows each (the tall shape the aggregating path rolls up). Returns the path."
  []
  (let [f (java.io.File/createTempFile "mt7a-tall" ".csv")]
    (.deleteOnExit f)
    (with-open [w (io/writer f)]
      (.write w "entity,element,value\n")
      (.write w "e1,A,5\n")
      (.write w "e1,B,4\n")
      (.write w "e1,C,3\n")
      (.write w "e2,X,7\n")
      (.write w "e2,Y,6\n"))
    (.getAbsolutePath f)))

(def ^:private tall-sample
  ;; a repeating-key sample so the MT-6 gate fires the aggregating path.
  [{"entity" "e1" "element" "A" "value" "5"}
   {"entity" "e1" "element" "B" "value" "4"}
   {"entity" "e2" "element" "X" "value" "7"}])

(def ^:private thing-vocab-model-spec
  ;; generic runtime-discovered vocabulary (#12 — no domain names in code).
  {:entity-types [{:type "Thing" :uri-keying-fields ["entity"]}]})

(defn- per-row-transform
  "A per-row transform-source minting one concept-draft per row under the given
   entity-type (string). csv rows are string-keyed."
  [entity-type]
  (str "(fn [row]
          {:concept-drafts
           [{:uri (str \"row:\" (get row \"entity\") \"-\" (get row \"element\"))
             :label (str (get row \"element\"))
             :entity-type \"" entity-type "\"
             :attributes {\"entity\" (get row \"entity\")}}]
           :relationship-drafts []})"))

(deftest apply-seam-aggregation-spec-variant-type-lands-canonical
  (testing "an aggregation-spec whose :entity-type is a case variant of a declared
            type → the spec is SNAPPED before the fold, so every landed draft
            carries the CANONICAL spelling"
    (let [path (write-tall-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :long-form}
                         :model-spec thing-vocab-model-spec
                         :sample-rows tall-sample
                         :aggregation-spec {:key-col "entity" :element-col "element"
                                            :value-col "value" :n 10
                                            :attr-name :topElements
                                            ;; the VARIANT spelling — must snap
                                            :entity-type "thing"}}})
          drafts (:concept-drafts out)]
      (is (= 2 (count drafts)) "aggregated per-KEY drafts (the MT-6 path still fires)")
      (is (every? #(= "Thing" (:entity-type %)) drafts)
          "every landed draft carries the CANONICAL spelling, not the variant")
      (is (= {:count 0 :types []} (:freelanced-drafts (:extraction-report out)))
          "binding ran and excluded nothing — surfaced honestly"))))

(deftest apply-seam-freelanced-aggregation-spec-falls-to-per-row
  (testing "an aggregation-spec whose :entity-type does NOT resolve is treated as
            no-valid-spec — the per-row branch runs (a transform-source exists) and
            the rejected type is SURFACED, never a freelanced aggregation"
    (let [path (write-tall-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :long-form}
                         :model-spec thing-vocab-model-spec
                         :sample-rows tall-sample
                         :transform-source (per-row-transform "Thing")
                         :aggregation-spec {:key-col "entity" :element-col "element"
                                            :value-col "value" :n 10
                                            :attr-name :topElements
                                            ;; freelanced — a container-prefixed scheme
                                            :entity-type "sheet-c/thing"}}})
          drafts (:concept-drafts out)]
      (is (= 5 (count drafts))
          "the PER-ROW branch ran (5 rows) — the freelanced-type aggregation did NOT")
      (is (every? #(= "Thing" (:entity-type %)) drafts)
          "the per-row drafts (canonical type) proceed, bound as usual")
      (is (= "sheet-c/thing"
             (get-in out [:extraction-report :freelanced-drafts :rejected-aggregation-type]))
          "the rejected aggregation-spec type is surfaced in the report"))))

(deftest apply-seam-freelanced-per-row-drafts-excluded-and-surfaced
  (testing "a per-row transform minting a freelanced entity-type → its drafts are
            EXCLUDED, :freelanced-drafts surfaces them, and the flat :concept-count
            reflects the exclusion (0 → the EB9 0-draft gate can fire the re-ask)"
    (let [path (write-tall-csv!)
          out (extract/apply-transform-for-container-code
               {:inputs {:source {:type :csv :path path}
                         :container {:name "c" :shape :entity}
                         :model-spec thing-vocab-model-spec
                         :transform-source (per-row-transform "sheet-c/thing")}})]
      (is (= [] (:concept-drafts out))
          "no freelanced draft lands (they would only be unfixable fragments)")
      (is (= 0 (:concept-count out))
          "the FLAT concept-count (the EB9 resilience gate key) reflects the exclusion")
      (is (= {:count 5 :types ["sheet-c/thing"]}
             (select-keys (get-in out [:extraction-report :freelanced-drafts])
                          [:count :types]))
          "the exclusion is SURFACED per as-emitted type — never silently landed")
      (is (= 0 (get-in out [:extraction-report :concept-count]))
          "the report's concept-count is the post-binding truth"))))

(deftest apply-seam-canonical-per-row-author-is-behavior-preserving
  (testing "a per-row transform already emitting the canonical type → byte-identical
            drafts (the common case — nothing changes but the honest 0-exclusion)"
    (let [path (write-tall-csv!)
          run (fn [model-spec]
                (extract/apply-transform-for-container-code
                 {:inputs {:source {:type :csv :path path}
                           :container {:name "c" :shape :entity}
                           :model-spec model-spec
                           :transform-source (per-row-transform "Thing")}}))
          bound (run thing-vocab-model-spec)
          unbound (run {:entity-types []})]
      (is (= 5 (count (:concept-drafts bound))) "all 5 per-row drafts land")
      (is (= (:concept-drafts unbound) (:concept-drafts bound))
          "byte-identical drafts vs the pre-binding (empty-vocabulary) behavior")
      (is (= {:count 0 :types []}
             (select-keys (get-in bound [:extraction-report :freelanced-drafts])
                          [:count :types]))
          "binding ran with zero exclusions — surfaced honestly")
      (is (nil? (get-in unbound [:extraction-report :freelanced-drafts]))
          "with NO vocabulary the seam does not claim enforcement ran (the empty-
           vocabulary state is the orchestrator's loud hard stop, not this seam's)"))))

;; ===========================================================================
;; Tracer 4 — the EMPTY-VOCABULARY HARD STOP (public orchestrator seam). Today
;; an empty/unparseable :entity-types silently proceeds against [] (guaranteed
;; 100% vocabulary freelancing). MT-7a converts that into a LOUD stop at
;; `orchestrate-extract-containers`, right after normalize-model-spec — the
;; tree fails, the pipeline surfaces :failed-at-model-extract, and ZERO
;; per-container child ticks are driven. (The Model subbehavior's own
;; empty-entity-types re-ask gate is the recovery path UPSTREAM — untouched.)
;; ===========================================================================

(deftest orchestrator-hard-stops-on-empty-vocabulary
  (testing "an EMPTY :entity-types → loud ex-info, ZERO child ticks driven —
            extraction never proceeds container-by-container against no vocabulary"
    (let [driven (atom 0)]
      (with-redefs [extract/list-source-containers (fn [_] [{:name "c1"} {:name "c2"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [& _] (swap! driven inc) {:status :success})
                    dsl/get-tick-blackboard (fn [_ _] {})]
        (doseq [empty-spec [{} {:entity-types []} nil]]
          (let [thrown (try (extract/orchestrate-extract-containers
                             {:inputs {:source {:type :sql :path "/tmp/x.db"}
                                       :model-spec empty-spec}
                              :tick-id (random-uuid) :event-store :stub})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
            (is (some? thrown)
                (str "an empty vocabulary (" (pr-str empty-spec) ") FAILS LOUDLY"))
            (is (= :empty-entity-type-vocabulary (:reason (ex-data thrown)))
                "the ex-data names the honest reason")))
        (is (= 0 @driven)
            "ZERO per-container child ticks were driven before the stop"))))
  (testing "an UNPARSEABLE string :entity-types (the C1 fragility that today
            coerces to [] and silently proceeds) also hard-stops"
    (let [driven (atom 0)]
      (with-redefs [extract/list-source-containers (fn [_] [{:name "c1"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [& _] (swap! driven inc) {:status :success})
                    dsl/get-tick-blackboard (fn [_ _] {})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"vocabulary"
           (extract/orchestrate-extract-containers
            {:inputs {:source {:type :excel :path "/tmp/x"}
                      :model-spec {:entity-types "{not-parseable-edn"}}
             :tick-id (random-uuid) :event-store :stub}))
          "an unparseable vocabulary is a LOUD stop, not a silent [] degrade")
      (is (= 0 @driven) "no child tick ran")))))

(deftest orchestrator-proceeds-unchanged-with-a-non-empty-vocabulary
  (testing "a non-empty discovered vocabulary → the orchestrator behaves exactly
            as before (drives the containers, accumulates, reports honestly)"
    (let [tick->c (atom {})]
      (with-redefs [extract/list-source-containers (fn [_] [{:name "c1"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [_ _ _ & {:keys [tick-id]}]
                                  (swap! tick->c assoc tick-id "c1")
                                  {:status :success})
                    dsl/get-tick-blackboard
                    (fn [_ _] {:concept-drafts {:value [{:uri "u1" :label "a"}]}
                               :relationship-drafts {:value []}
                               :extraction-report {:value {:rows-streamed 3 :rows-errored 0}}})]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {:entity-types [{:type "Thing"
                                                          :uri-keying-fields ["id"]}]}}
                    :tick-id (random-uuid) :event-store :stub})]
          (is (= 1 (count (:concept-drafts out))) "the container's drafts accumulate")
          (is (= 1 (get-in out [:extraction-report :containers-processed]))
              "the container was processed — no behavior change for the healthy path"))))))
