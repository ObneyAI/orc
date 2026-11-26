(ns rs-p3-family-merge-probe
  "PROTOTYPE — throwaway (convergence grill C3, gate for bundle CV-C). Does a
   judged merge step converge paraphrases of one domain onto one family without
   merging different domains? Replays the convergence sweep's 24 paraphrases
   (8 groups × 3) IN ORDER against a growing set of families, exactly as the
   runtime would once C1–C3 land:

     arm :rich  — the nearest existing families BY RANK (ColBERT rerank of each
                  family's RICH description against the task, no index, no
                  cutoff; bounded by merge-candidate-count), then ONE discrete
                  question with those descriptions in view: same family (named),
                  new, or unknown — reason before verdict.
     arm :labels — the same question shown ONLY the families' labels (C2's
                  cheap path on its own).

   Ground truth is the corpus group in the slug (never the model's label). A
   family's rich description is synthesised here from what the runtime already
   records at birth (the verdict's reasoning, the birth signature, the birth
   shape) — the shape C2 makes durable. Nothing in components/ is modified."
  (:require [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def sweep-dir "development/bench/ood-stress-results/2026-09-16_093519-rs6-convergence-sweep/pass-1")
(def corpus-dir "development/bench/ood-corpus-convergence")
(def out-dir "development/bench/ood-stress-results/rs-p3-family-merge-probe")
(def merge-candidate-count 5)
(def model "google/gemini-3-flash-preview")

(def shape-names
  {"fc82d884-b2c7-38a7-9783-ac2bba41237f" "ETL pipeline"
   "aef71f08-76bf-373c-862a-1250ef23f450" "Iterative refinement"
   "acbcf0ca-6478-3a63-8b93-31b93bf0902c" "Producer/validator"
   "2ead65e6-6373-38de-81c4-ef7bf067afc9" "Sequential pipeline"
   "0f80961b-9318-329c-91c3-8f77cda96c43" "Parallel independent"
   "153f1c69-e1d8-3592-8e62-391a7fab2dac" "Briefing generation"})

(defn- read-edn [f]
  (edn/read-string {:readers (merge @(requiring-resolve 'time-literals.read-write/tags)
                                    {'uuid (fn [s] (java.util.UUID/fromString s))})
                    :default (fn [_ v] v)}
                   (slurp f)))

(defn- group-of [slug] (second (re-matches #"\d+-([a-z]+)-[a-z]" slug)))

(defn load-records []
  (let [texts (into {} (map (juxt :slug :instruction)) (ood/load-corpus corpus-dir))]
    (->> (file-seq (io/file sweep-dir))
         (filter #(str/ends-with? (.getName %) ".edn"))
         (map read-edn)
         (map (fn [r]
                (let [top (:top-1 r)
                      ev (:classified-event r)]
                  {:slug (:slug r)
                   :group (group-of (:slug r))
                   :signature (get texts (:slug r))
                   :label (or (:domain-label top) (:domain-label r))
                   :coverage (:domain-coverage top)
                   :reasoning (or (get-in ev [:domain-verdict :domain-reasoning]) "")
                   :shape (get shape-names (str (:target-id top)) (str (:target-id top)))
                   :would-mint? (contains? #{:mint-domain-child :mint-sibling-domain-child} (:assigned-via r))})))
         (sort-by :slug)
         vec)))

(defn- rich-description [{:keys [label signature reasoning shape]}]
  (str "Domain family '" label "'. Born under the " shape " shape. Purpose (why the shape was judged "
       "not to cover this task's domain): " reasoning " First representative use — the task that minted it: "
       (subs signature 0 (min 600 (count signature)))))

(def ^:private judge-module
  {:inputs [{:name :task :spec :string :description "the new task's instruction text"}
            {:name :reasoning :spec :string :description "the reranker's domain reasoning for the new task"}
            {:name :candidates :spec :string :description "JSON array of candidate families, each with id and description"}]
   :outputs [{:name :answer :spec :string
              :description "a JSON object {\"merge_reasoning\": string, \"verdict\": \"same\"|\"new\"|\"unknown\", \"family\": string-or-null}"}]
   :instructions (str "You decide whether a NEW task belongs to an EXISTING domain family or starts a new one.\n"
                      "A domain family is the set of tasks that share SUBJECT MATTER and OUTPUT KIND — e.g. every "
                      "request to scale a tested recipe to a different batch size is ONE family whatever the dish, "
                      "venue or equipment; a request to compute nutrition labels is a DIFFERENT family even if it "
                      "also involves recipes. The concrete instance (the dish, the database engine, the runner's age) "
                      "never makes a new family; a different subject matter or a different kind of output does.\n"
                      "Write merge_reasoning FIRST: name the candidate you compared and say what is shared and what "
                      "differs in subject matter and output kind. Then verdict: \"same\" with the family id when one "
                      "candidate is the same family; \"new\" when none is; \"unknown\" when you cannot tell from what "
                      "is shown. Respond with ONLY the JSON object.")})

(defn- parse-answer
  "The provider returns the structured output either as a map (function
   calling) or as a JSON string; accept both — the first probe run dropped 16
   valid verdicts by expecting a string."
  [raw]
  (cond
    (map? raw) (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw)
    (string? raw)
    (let [s (.indexOf raw "{") e (.lastIndexOf raw "}")]
      (when (and (>= s 0) (> e s))
        (try (json/read-str (subs raw s (inc e)) :key-fn keyword) (catch Throwable _ nil))))
    :else nil))

(defn- ask-judge! [ctx task reasoning candidates]
  (let [result (llm/predict (:llm-provider ctx :openrouter) judge-module
                            {:task task :reasoning reasoning :candidates (json/write-str candidates)}
                            {:model model :use-function-calling? true :validate? false :with-metadata? true})
        parsed (parse-answer (get-in result [:outputs :answer]))]
    {:verdict (keyword (or (:verdict parsed) "unknown"))
     :family (:family parsed)
     :reasoning (:merge_reasoning parsed)
     :usage (:usage result)}))

(defn- neighbourhood [ctx task families arm]
  (when (seq families)
    (let [docs (mapv (if (= arm :rich) :description :label) families)
          results (colbert/rerank ctx {:query task :documents docs :k (min merge-candidate-count (count docs))})
          by-doc (into {} (map (juxt (if (= arm :rich) :description :label) identity)) families)]
      (vec (keep #(get by-doc (:content %)) results)))))

(defn run-arm! [ctx records arm]
  (let [families (atom [])]
    (vec
      (for [r records :when (and (:signature r) (:label r))]
        (let [cands (neighbourhood ctx (:signature r) @families arm)
              shown (mapv (fn [f] {:id (:id f)
                                   (if (= arm :rich) :description :label) (if (= arm :rich) (:description f) (:label f))})
                          cands)
              answer (if (seq cands)
                       (ask-judge! ctx (:signature r) (:reasoning r) shown)
                       {:verdict :new :family nil :reasoning "no existing families" :usage nil})
              named (some #(when (= (:id %) (:family answer)) %) cands)
              outcome (cond
                        (and (= :same (:verdict answer)) named (= (:group named) (:group r))) :same-correct
                        (and (= :same (:verdict answer)) named) :same-cross-group
                        (= :same (:verdict answer)) :same-unnamed
                        (and (= :new (:verdict answer)) (some #(= (:group %) (:group r)) @families)) :new-but-family-existed
                        (= :new (:verdict answer)) :new-correct
                        :else :unknown)]
          (when (contains? #{:new-correct :new-but-family-existed :same-unnamed :unknown} outcome)
            (when (not= :unknown outcome)
              (swap! families conj {:id (str "family-" (count @families) "-" (:label r))
                                    :label (:label r) :group (:group r)
                                    :description (rich-description r)})))
          (println (format "  [%s] %-22s %-14s -> %s %s" (name arm) (:slug r) (:label r) outcome (or (:family answer) "")))
          {:slug (:slug r) :group (:group r) :label (:label r) :arm arm
           :candidates-shown (mapv :id shown) :verdict (:verdict answer) :family (:family answer)
           :family-group (:group named) :outcome outcome :judge-reasoning (:reasoning answer)
           :usage (:usage answer)})))))

(defn summarise [rows]
  (let [by (group-by :arm rows)]
    (into {} (for [[arm rs] by]
               [arm {:n (count rs)
                     :outcomes (frequencies (map :outcome rs))
                     :families-per-group (into (sorted-map)
                                               (for [[g grs] (group-by :group rs)]
                                                 [g (count (filter #(contains? #{:new-correct :new-but-family-existed} (:outcome %)) grs))]))
                     :tokens (reduce + 0 (keep (comp :total-tokens :usage) rs))}]))))

(defn run! [ctx]
  (.mkdirs (io/file out-dir))
  (let [records (load-records)
        _ (println "records" (count records) "usable" (count (filter #(and (:signature %) (:label %)) records)))
        rich (run-arm! ctx records :rich)
        labels (run-arm! ctx records :labels)
        rows (into rich labels)
        summary (summarise rows)]
    (spit (str out-dir "/probe-results.edn") (with-out-str (pp/pprint {:rows rows :summary summary})))
    (pp/pprint summary)
    (println "saved to" out-dir)
    summary))
