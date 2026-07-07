(ns connect3b-authoring-prototype
  "CONNECT-3b STEP 1 — PROTOTYPE (test-the-builder). Feed the model a REAL O*NET
   junction sample (Skills) + a realistic model-spec + the NEW case-C guidance and
   measure whether it DISCOVERS an ASSOCIATION-spec (:predicate + :element-entity-type)
   instead of rolling the element into a per-key attribute list. Also sanity-check
   that an Alternate-Titles sample (entity-specific labels) still → an ATTRIBUTE.

   Faithful to the pipeline: SAME sampling (mechanical-sample-rows, first-n), SAME
   author-prompt shape (transform-author-prompt + guidance + vocab guidance), SAME
   dscloj/predict call the :llm node makes. NO domain names fed (no 'skill'/'requires'
   in the guidance); the model-spec carries NO occupation->element edge — the
   association decision + predicate must be the model's OWN discovery (#9).

   USAGE: OPENROUTER_API_KEY=... clj -M:dev -m connect3b-authoring-prototype [N]"
  (:require [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [ai.obney.orc.ontology.core.extract-subbehavior :as es]
            [cheshire.core :as json]
            [clojure.pprint :as pp]))

(def onet-dir "/Users/darylroberts/Downloads/db_30_1_excel")
(def default-model "google/gemini-3-flash-preview")

(defn register-openrouter! [model]
  (litellm-router/register! :openrouter
    {:provider :openrouter :model model
     :config {:api-base "https://openrouter.ai/api/v1"
              :api-key (or (System/getenv "OPENROUTER_API_KEY")
                           (throw (ex-info "OPENROUTER_API_KEY not set (env only)" {})))}}))

;; ---------------------------------------------------------------------------
;; The PROPOSED new aggregation-author-guidance (case C added). This is the exact
;; text I will port into cagg/aggregation-author-guidance in STEP 2 if it wins.
;; Domain-agnostic: names no column/type/predicate. The association signal is
;; STRUCTURAL (the element value recurs across many DIFFERENT keys — a reusable
;; shared thing) + SEMANTIC (it is itself a general, referenceable entity you'd
;; traverse to), NOT a keyword list.
;; ---------------------------------------------------------------------------

(defn proposed-guidance []
  (str
   "\n\n*** REPEATING-KEY TABLES — AUTHOR AN AGGREGATION-SPEC INSTEAD (read carefully) ***\n"
   "INSPECT the provided sample-rows. Ask: does ONE entity key value appear in MANY "
   "rows? If so, emitting one concept per raw row would SHATTER each entity into "
   "dozens of fragments — roll it up with an `aggregation-spec` (a DATA MAP, not "
   "code) instead of a per-row `transform-source`. There are THREE cases — decide "
   "which the element column is:\n\n"

   "(A) MANY ROWS PER ENTITY, and each row's ELEMENT value is an ENTITY-SPECIFIC "
   "LABEL/MEASURE — a value that BELONGS TO this one entity and would NOT be shared "
   "verbatim by many other entities (an alternate name/title, a one-off descriptive "
   "string, a code specific to this row). These have no independent identity worth a "
   "node of their own. ROLL THEM UP onto the entity as an ATTRIBUTE with:\n"
   "  {:key-col      <the ENTITY key column to group by (the one that REPEATS)>\n"
   "   :element-col  <the column whose values are the labels to collect>\n"
   "   :value-col    <OPTIONAL — the NUMERIC column to RANK by. Include ONLY when the "
   "rows carry a REAL measure/score you VERIFIED in the sample (NOT an id/code) → a "
   "ranked TOP-N. If the elements are descriptive labels with no ranking measure, "
   "OMIT it → a bounded flat LIST of the label values.>\n"
   "   :n            <how many top elements to keep per entity for the top-N (ignored "
   "for a list-collect)>\n"
   "   :attr-name    <the flat array attribute name to hold them, e.g. topElements>\n"
   "   :entity-type  <the model-spec entity-type this key names>\n"
   "   :filter-col   <OPTIONAL — a scale/subset column; see the scale-filter note below>\n"
   "   :filter-val   <OPTIONAL — the single scale/subset VALUE to KEEP>}\n\n"

   "(C) MANY ROWS PER ENTITY, but each row's ELEMENT value is itself a GENERAL, "
   "REFERENCEABLE ENTITY — a reusable 'thing' with its OWN identity that MANY "
   "DIFFERENT entities would share (a capability, category, topic, standard, "
   "activity, component, requirement — a value you would expect to see attached to "
   "lots of other keys too, and that you'd want to REACH AS A NODE and compare "
   "ACROSS entities). This is a MANY-TO-MANY RELATION, not an attribute: burying it "
   "in a per-entity list yields ZERO shared nodes and ZERO edges (a graph you cannot "
   "traverse from one entity to another through the shared thing). Instead MINT the "
   "element as a SHARED node + a key->element EDGE by authoring an ASSOCIATION-spec — "
   "the SAME map as (A) but with TWO extra fields IN PLACE OF `:attr-name`:\n"
   "   :predicate            <the relationship VERB from the key entity to the element "
   "entity — how the key RELATES to the element (author it yourself from the meaning; "
   "e.g. a verb like uses / performs / belongs-to / depends-on)>\n"
   "   :element-entity-type  <the element's OWN entity-type — the type of thing each "
   "element value IS (propose a new type here if the model-spec has none for it)>\n"
   " Keep `:key-col` `:element-col` and (when the rows carry a real measure you "
   "verified) `:value-col` + the scale `:filter-col`/`:filter-val` — the value-col "
   "rating RIDES THE EDGE. Do NOT set `:attr-name` for an association.\n"
   " HOW TO TELL (A) FROM (C): ask 'would this exact element value plausibly appear "
   "for MANY OTHER entity keys, and is it a general thing I'd want as its own node to "
   "traverse to?' — YES → (C) association; or is it a label/measure specific to THIS "
   "entity → (A) attribute. NOTE: a small contiguous sample often shows only ONE key's "
   "rows, so you may NOT literally SEE the element repeat across keys — judge by the "
   "element's NATURE (a shareable, referenceable kind of thing vs a one-entity label).\n\n"

   "(B) ONE ROW PER ENTITY (each row is a DISTINCT entity — the key is near-unique). "
   "NOT an aggregation: leave `aggregation-spec` empty/absent and author the per-row "
   "`transform-source` exactly as described above.\n\n"

   "The SCALE FILTER is critical whenever you rank a top-N: if the table repeats each "
   "entity×element under multiple scales/subsets, a top-N WITHOUT the filter MIXES "
   "scales and is garbage — inspect the sample for a scale/subset column and set "
   "`filter-col`/`filter-val` to the one scale you rank by. Pick a value that occurs "
   "in the real sampled rows.\n\n"

   "Write your `reasoning` FIRST (#13): state whether the key repeats (many rows per "
   "entity vs one); if it repeats, state whether the element is an ENTITY-SPECIFIC "
   "label/measure (→ A, attribute) or a GENERAL REFERENCEABLE entity many keys would "
   "share (→ C, association) and name the predicate + element-entity-type; then name "
   "the key/element columns and any value column + scale filter, and why."))

;; ---------------------------------------------------------------------------
;; A realistic SHARED model-spec (what the EB3 Model stage plausibly emits across
;; the whole O*NET source). Entity-types occupation + a couple discovered types,
;; but NO occupation->element edge and NO predicate — the association is the
;; author's to discover (#9, do-not-feed-the-answer).
;; ---------------------------------------------------------------------------

(def model-spec
  {:entity-types [{:type "occupation"
                   :uri-keying-fields ["O*NET-SOC Code"]
                   :grain-strategy "canonical-row-filter"
                   :canonical-row-marker "one row per occupation code"}]
   :scope-filter nil
   :edges []})

(defn- instruction-for [key-shape]
  ;; SAME composition the :llm author node uses, but with the PROPOSED guidance in
  ;; place of the current cagg/aggregation-author-guidance.
  (str (es/transform-author-prompt key-shape)
       (proposed-guidance)
       (vb/vocabulary-binding-guidance)
       (vb/vocabulary-proposal-guidance)))

(def ^:private module-outputs
  [{:name :reasoning :spec :string :description "Chain-of-thought FIRST (#13)."}
   {:name :transform-source :spec :string :description "A per-row transform STRING, or empty when you author an aggregation-spec."}
   {:name :selector :spec :string :description "The exact sheet/table name the rows came from."}
   {:name :aggregation-spec :spec [:maybe [:map {:closed false}]]
    :description "The OPTIONAL rollup/association DATA MAP (a repeating-key table); empty/absent for a per-row transform."}
   {:name :entity-type-proposal :spec [:maybe [:map {:closed false}]]
    :description "The OPTIONAL new entity-type proposal ({:type :uri-keying-fields :description})."}])

(defn author-once
  "One faithful author call. Returns the parsed authored aggregation-spec (or nil)
   + the reasoning + whether a transform-source was authored instead."
  [sheet]
  (let [rows (dt/mechanical-sample-rows {:type :excel :path onet-dir} sheet 5)
        key-shape (dt/sample-row-key-shape {:type :excel :path onet-dir} rows sheet)
        container {:name sheet :type :excel}
        module {:inputs [{:name :model-spec :spec [:map {:closed false}] :description "The EB3 model-spec."}
                         {:name :sample-rows :spec [:vector [:map {:closed false}]] :description "REAL sampled rows."}
                         {:name :container :spec [:map {:closed false}] :description "The container descriptor."}]
                :outputs module-outputs
                :instructions (instruction-for key-shape)}
        inputs {:model-spec (json/generate-string model-spec)
                :sample-rows (json/generate-string rows)
                :container (json/generate-string container)}
        result (dscloj/predict :openrouter module inputs {:validate? false :with-metadata? false})
        outputs (or (:outputs result) result)
        raw-agg (:aggregation-spec outputs)
        spec (ca/parse-aggregation-spec raw-agg)]
    {:spec spec
     :raw-agg raw-agg
     :raw-agg-type (str (type raw-agg))
     :association? (ca/association-mode? spec)
     :valid-agg? (ca/valid-aggregation-spec? spec)
     :transform-source (let [ts (:transform-source outputs)] (when (and ts (seq (str ts))) ts))
     :reasoning (:reasoning outputs)}))

(defn run-sheet [sheet n]
  (println (str "\n\n########## " sheet " — " n " runs ##########"))
  (let [results (vec (for [i (range n)]
                       (let [r (try (author-once sheet)
                                    (catch Throwable t {:error (.getMessage t)}))]
                         (println (format "  run %d: association?=%s valid-agg?=%s predicate=%s element-type=%s attr-name=%s transform?=%s"
                                          (inc i) (:association? r) (:valid-agg? r)
                                          (pr-str (get-in r [:spec :predicate]))
                                          (pr-str (get-in r [:spec :element-entity-type]))
                                          (pr-str (get-in r [:spec :attr-name]))
                                          (boolean (:transform-source r))))
                         (when (:error r) (println "    ERROR:" (:error r)))
                         (println "    RAW-AGG" (:raw-agg-type r) ":" (pr-str (:raw-agg r)))
                         r)))
        assoc-hits (count (filter :association? results))]
    (println (format "\n  >>> %s: %d/%d authored an ASSOCIATION-spec (:predicate + :element-entity-type)"
                     sheet assoc-hits n))
    {:sheet sheet :n n :assoc-hits assoc-hits :results results}))

(defn -main [& args]
  (register-openrouter! default-model)
  (let [n (if (seq args) (Integer/parseInt (first args)) 8)
        skills (run-sheet "Skills" n)
        alt (run-sheet "Alternate Titles" (max 5 (quot n 2)))]
    (println "\n\n========== SAMPLE AUTHORED ASSOCIATION-SPEC (Skills) ==========")
    (when-let [hit (first (filter :association? (:results skills)))]
      (pp/pprint (:spec hit))
      (println "\n--- its reasoning ---")
      (println (:reasoning hit)))
    (println "\n\n========== A Skills run that chose ATTRIBUTE (if any) ==========")
    (if-let [miss (first (remove :association? (:results skills)))]
      (do (pp/pprint (:spec miss)) (println "reasoning:" (:reasoning miss)))
      (println "  (none — every Skills run authored an association)"))
    (println "\n\n========== ALT-TITLES SANITY (should be attribute, association?=false) ==========")
    (doseq [r (:results alt)]
      (println (format "  association?=%s valid-agg?=%s transform?=%s"
                       (:association? r) (:valid-agg? r) (boolean (:transform-source r))))
      (println "    spec:" (pr-str (:spec r)))
      (println "    reasoning:" (some-> (:reasoning r) (subs 0 (min 320 (count (str (:reasoning r))))))))
    (println (format "\n=== VERDICT === Skills association hit-rate: %d/%d ; Alt-Titles associations: %d/%d (want 0)"
                     (:assoc-hits skills) (:n skills)
                     (:assoc-hits alt) (:n alt)))
    (shutdown-agents)))
