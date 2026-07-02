(ns mt3-aggregate-prototype
  "MT-3 /prototype (THROWAWAY) — de-risk the aggregating-transform mechanism on the
   REAL O*NET `Skills` long-form container BEFORE any TDD. Establishes:

     (a) the SHAPE of a model-authored ROLLUP SPEC (which column is key / element /
         rank, N, the flat attr name) and whether it needs a SCALE/FILTER dimension
         (O*NET Skills carries BOTH Importance (IM) and Level (LV) scale rows per
         occupation×element — a naive top-N by Data Value would MIX scales);
     (b) that a PURE, BOUNDED streaming top-N accumulation (one draft per key, top-N
         elements each) is CORRECT on the full ~92k-row container and does NOT
         materialize the whole source (peak accumulator = distinct-keys × N);
     (c) whether the LLM reliably AUTHORS that spec given the long-form sample + the
         MT-1 role tags (and whether it correctly handles the scale-filter).

   NOTHING here ships — it informs the MT-3 handoff + TDD. No mocks: real stream-all
   over the real workbook, real LLM author.

   USAGE: clj -M:dev:test -m mt3-aggregate-prototype"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.orc-service.core.source-tools :as st]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [dscloj.core :as dscloj]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(def onet {:type :excel :path h/onet-dir})

;; ---------------------------------------------------------------------------
;; The candidate MECHANISM — a PURE, bounded streaming top-N aggregate.
;; A ROLLUP SPEC (what the Model authors) + a lazy row-seq → per-key drafts.
;; ---------------------------------------------------------------------------

(defn- num [v]
  (cond (number? v) v
        (and (string? v) (re-matches #"\s*-?\d+(?:\.\d+)?\s*" v)) (Double/parseDouble (str/trim v))
        :else nil))

(defn stream-aggregate
  "Bounded streaming group-by-key → top-N-by-value rollup. `spec` is the
   model-authored rollup:
     {:key-col :element-col :value-col :n :attr-name
      :filter-col <opt> :filter-val <opt> :entity-type <opt>}
   Folds `rows` (a lazy seq of keyed row-maps) maintaining, per key, only the
   TOP-N {:element :value} pairs (bounded memory = distinct-keys × N). A row whose
   filter-col != filter-val is skipped (the scale dimension). Emits ONE draft per
   key: {:uri :label :attributes {attr-name [element …top-N by value…]}}.
   Returns {:drafts [...] :distinct-keys n :peak-acc-entries n :rows-seen n :rows-kept n}."
  [spec rows]
  (let [{:keys [key-col element-col value-col n attr-name filter-col filter-val entity-type]} spec
        n (or n 10)
        et (or entity-type "occupation")
        keep-topn (fn [pairs]                       ; bounded: sort desc by value, take n
                    (->> pairs (sort-by :value >) (take n) vec))
        result
        (reduce
         (fn [{:keys [acc rows-seen rows-kept] :as st} row]
           (let [k (get row key-col)
                 e (get row element-col)
                 v (num (get row value-col))
                 keep? (and k e (some? v)
                            (or (nil? filter-col)
                                (= (str (get row filter-col)) (str filter-val))))]
             (if-not keep?
               (assoc st :rows-seen (inc rows-seen))
               (let [cur (get acc k [])
                     nxt (keep-topn (conj cur {:element e :value v}))]
                 (assoc st :acc (assoc acc k nxt)
                           :rows-seen (inc rows-seen)
                           :rows-kept (inc rows-kept))))))
         {:acc {} :rows-seen 0 :rows-kept 0}
         rows)
        acc (:acc result)
        drafts (mapv (fn [[k pairs]]
                       {:uri (str et "/" k)
                        :label (str k)
                        :attributes {attr-name (mapv :element pairs)}})
                     acc)]
    {:drafts drafts
     :distinct-keys (count acc)
     :peak-acc-entries (reduce + 0 (map (comp count val) acc))
     :rows-seen (:rows-seen result)
     :rows-kept (:rows-kept result)}))

;; ---------------------------------------------------------------------------
;; Real-source streaming — the uniform container contract's :stream-all (lazy
;; windows), flattened to a lazy row-seq (the same seq apply-extraction-transform!
;; folds), so the bound is proven on the REAL 92k-row container.
;; ---------------------------------------------------------------------------

(defn- container-for [nm]
  (let [cc (st/container-contract onet)]
    [cc (first (filter #(= nm (:name %)) ((:list-containers cc))))]))

(defn- lazy-rows [cc container max-windows]
  (let [windows ((:stream-all cc) container {:max-windows max-windows})]
    (mapcat (fn [w] (vec (or (:rows w) []))) windows)))

(defn probe [nm]
  (let [[cc c] (container-for nm)
        rows (csel/normalize-sample-result ((:sample-rows cc) c {:limit 20}))
        header (vec (distinct (mapcat keys rows)))]
    (println "\n#####" nm "  header:" header)
    (println "  first 3 sample rows:")
    (doseq [r (take 3 rows)] (println "   " (pr-str r)))))

;; a HAND-authored spec grounded in the probe (the reviewer naming what the Model
;; should author) — top skills by IMPORTANCE (Scale ID = IM).
(def skills-spec
  {:key-col "O*NET-SOC Code" :element-col "Element Name"
   :value-col "Data Value" :filter-col "Scale ID" :filter-val "IM"
   :n 10 :attr-name :topSkills :entity-type "occupation"})

(defn- manual-topn
  "Independent hand-aggregation for ONE key over the FULL real stream — the
   adversarial cross-check of the streaming top-N."
  [cc c spec k]
  (->> (lazy-rows cc c 100000)
       (filter #(and (= k (get % (:key-col spec)))
                     (= (:filter-val spec) (str (get % (:filter-col spec))))))
       (map (fn [r] {:element (get r (:element-col spec)) :value (num (get r (:value-col spec)))}))
       (filter :value)
       (sort-by :value >)
       (take (:n spec))
       (mapv :element)))

;; ---------------------------------------------------------------------------
;; Can the LLM AUTHOR the spec from the sample + role tags? (the (a)/(c) risk)
;; ---------------------------------------------------------------------------

(defn author-spec-llm [nm sample roles]
  (let [module {:inputs [{:name :request :spec :string :description "long-form sample + role tags"}]
                :outputs [{:name :reasoning :spec :string}
                          {:name :key-col :spec :string}
                          {:name :element-col :spec :string}
                          {:name :value-col :spec :string}
                          {:name :filter-col :spec :string :description "the scale/subset column to filter on, or empty"}
                          {:name :filter-val :spec :string :description "the value of filter-col to keep, or empty"}
                          {:name :n :spec :string}
                          {:name :attr-name :spec :string :description "the flat array attribute name, e.g. topSkills"}]
                :instructions
                (str "You are authoring a ROLLUP SPEC for a LONG-FORM table (many rows per entity: "
                     "one row per entity×element, with a value column). Group by the ENTITY KEY, rank the "
                     "group's ELEMENTS by the VALUE column, keep the top-N element labels as ONE flat array "
                     "attribute on ONE record per key. If the table carries MULTIPLE measurement scales/subsets "
                     "in a column (so the same entity×element appears more than once under different scales), "
                     "pick the SINGLE scale to rank by and set filter-col/filter-val; otherwise leave them empty. "
                     "Reason FIRST. Table: " nm "\nStructural role hints (may be imperfect — verify against the sample): "
                     (pr-str roles) "\nSAMPLE ROWS:\n" (str/join "\n" (map pr-str (take 15 sample))))}
        r (dscloj/predict :openrouter module {:request "Author the rollup spec."} {:validate? false :with-metadata? false})
        o (or (:outputs r) r)]
    o))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (println "=== MT-3 AGGREGATE PROTOTYPE — real O*NET Skills / Knowledge ===")
  (probe "Skills")
  (probe "Knowledge")
  (let [[cc c] (container-for "Skills")
        t0 (System/currentTimeMillis)
        rows (lazy-rows cc c 100000)          ; the FULL container, lazily windowed
        agg (stream-aggregate skills-spec rows)
        elapsed (- (System/currentTimeMillis) t0)]
    (println "\n>>> (b) BOUNDED STREAMING TOP-N on the FULL real Skills container")
    (println "    rows-seen:" (:rows-seen agg) " rows-kept(IM):" (:rows-kept agg)
             " distinct-keys(SOC):" (:distinct-keys agg)
             " peak-acc-entries:" (:peak-acc-entries agg) "(bound = keys×N)"
             " drafts:" (count (:drafts agg)) "(" elapsed "ms)")
    (println "    sample drafts (first 3):")
    (doseq [d (take 3 (:drafts agg))] (println "     " (:uri d) "->" (get-in d [:attributes :topSkills])))
    ;; adversarial cross-check: streaming top-N == an independent full-scan hand-agg.
    (let [ks (mapv :label (take 3 (:drafts agg)))]
      (println "\n>>> ADVERSARIAL cross-check (streaming vs independent full-scan hand-agg):")
      (doseq [k ks]
        (let [stream-topn (get-in (first (filter #(= k (:label %)) (:drafts agg))) [:attributes :topSkills])
              hand-topn (manual-topn cc c skills-spec k)]
          (println "   SOC" k "  match?" (= stream-topn hand-topn))
          (when (not= stream-topn hand-topn)
            (println "      stream:" stream-topn)
            (println "      hand  :" hand-topn)))))
    ;; (a)/(c) — can the LLM author the spec?
    (println "\n>>> (a)/(c) LLM authors the rollup spec (real :llm):")
    (let [sample (csel/normalize-sample-result ((:sample-rows cc) c {:limit 20}))
          header (vec (distinct (mapcat keys sample)))
          spec (author-spec-llm "Skills" sample {:key "O*NET-SOC Code" :element "Element Name" :value "Data Value" :header header})]
      (pp/pprint (select-keys spec [:reasoning :key-col :element-col :value-col :filter-col :filter-val :n :attr-name]))
      ;; verify the LLM-authored spec, when executed, gives a populated non-mixed rollup.
      (let [authored {:key-col (:key-col spec) :element-col (:element-col spec) :value-col (:value-col spec)
                      :filter-col (when (seq (str (:filter-col spec))) (:filter-col spec))
                      :filter-val (:filter-val spec)
                      :n (or (num (:n spec)) 10) :attr-name :top :entity-type "occupation"}
            a2 (stream-aggregate authored (lazy-rows cc c 100000))]
        (println "    LLM-authored spec executed → distinct-keys:" (:distinct-keys a2)
                 " rows-kept:" (:rows-kept a2) " drafts:" (count (:drafts a2))
                 " sample:" (get-in (first (:drafts a2)) [:attributes :top])))))
  (println "\n=== PROTOTYPE DONE ==="))

(defn -main [& _]
  (run! )
  (shutdown-agents)
  (System/exit 0))
