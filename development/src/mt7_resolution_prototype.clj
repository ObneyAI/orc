(ns mt7-resolution-prototype
  "MT-7 /prototype (THROWAWAY) — de-risk the keying-field entity-type RESOLUTION before
   TDD. Runs the REAL survey→model→extract (cap 12) to get drafts whose :entity-type
   STRINGS vary (occupation vs job-zones/occupation, Workplace vs Workplace Element),
   then applies a CANDIDATE resolution — resolve each draft's canonical type to the
   model-spec type whose keying-fields the draft FULLY satisfies, MOST-SPECIFIC wins —
   and reports the canonical-scheme distribution BEFORE vs AFTER. Confirms:
     (a) variant-typed same-keying drafts COLLAPSE to one canonical type,
     (b) a finer-grained multi-key entity (a metric/observation) does NOT collapse into
         its coarser single-key parent (the subset-over-collapse hazard).
   Nothing ships. USAGE: clj -M:dev:test -m mt7-resolution-prototype"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [clojure.string :as str]))

(def onet {:type :excel :path h/onet-dir})
(def goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have.")

(defn- norm [s] (some-> s str str/lower-case (str/replace #"[^a-z0-9]" "")))

(defn- draft-has-field?
  "Case/separator-tolerant: does the draft's :attributes carry a recoverable value for
   keying `field`? (a simplified stand-in for recover-via-value, enough to de-risk the
   resolution LOGIC on real drafts)."
  [draft field]
  (let [t (norm field)]
    (some (fn [[k v]] (and (= t (norm k)) (some? v) (seq (str v)))) (:attributes draft))))

(defn- resolve-type
  "CANDIDATE: the model-spec type whose keying-fields the draft FULLY satisfies,
   MOST-SPECIFIC (largest keying set) first. Returns [type keying] or nil."
  [draft entity-types]
  (->> entity-types
       (keep (fn [{:keys [type uri-keying-fields]}]
               (let [fields (vec (remove nil? (or uri-keying-fields [])))]
                 (when (and (seq fields) (every? #(draft-has-field? draft %) fields))
                   [type fields]))))
       (sort-by (fn [[_ fields]] (- (count fields))))
       first))

(defn- scheme [uri] (let [u (str uri) i (str/index-of u "/")] (if i (subs u 0 i) u)))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-7 RESOLUTION /prototype — real extract (cap 12), candidate keying-resolution ===")
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? false})
            sv (ce/delegate-survey! ctx {:source onet :goal goal :model h/default-model})
            mx (ce/delegate-model-extract! ctx {:source onet :goal goal :profile (:profile sv)
                                                :pipeline-sheet-id pipeline-sheet-id
                                                :model h/default-model :max-containers 12})
            ms (:model-spec mx)
            ets (:entity-types ms)
            drafts (:concept-drafts mx)]
        (println "\nmodel entity-types:")
        (doseq [et ets] (println "   " (pr-str (:type et)) " keying=" (pr-str (:uri-keying-fields et))))
        (println "\n=== BEFORE — schemes by draft :entity-type (the current behavior) ===")
        (doseq [[s n] (reverse (sort-by val (frequencies (map (comp scheme :uri) drafts))))]
          (println (format "   %-8d %s" n s)))
        (println "\n=== AFTER — resolved canonical type (most-specific keying the draft satisfies) ===")
        (let [resolved (map (fn [d] (let [[t _] (resolve-type d ets)]
                                      {:draft d :resolved (or t :UNRESOLVED)})) drafts)
              by-resolved (frequencies (map (comp norm str :resolved) resolved))]
          (doseq [[t n] (reverse (sort-by val by-resolved))]
            (println (format "   %-8d %s" n t)))
          ;; adversarial checks
          (println "\n=== VERDICT ===")
          (let [occ-variants (filter #(re-find #"(?i)occupation" (scheme (:uri (:draft %)))) resolved)
                occ-resolved-types (distinct (map (comp norm str :resolved) occ-variants))
                metric-drafts (filter #(re-find #"(?i)metric|requirement" (scheme (:uri (:draft %)))) resolved)
                metric-resolved (distinct (map (comp norm str :resolved) metric-drafts))]
            (println "  occupation-URI drafts resolve to types:" occ-resolved-types
                     "=>" (if (= 1 (count occ-resolved-types)) "PASS — collapse to ONE type" "check"))
            (println "  metric-URI drafts resolve to types:" metric-resolved
                     "=>" (if (and (seq metric-drafts)
                                   (not (some #{(norm "Occupation")} metric-resolved)))
                            "PASS — NOT collapsed into occupation" "check (or no metric drafts this run)"))
            (println "  UNRESOLVED drafts:" (count (filter #(= :UNRESOLVED (:resolved %)) resolved))
                     "(these keep their :entity-type — honest degrade)"))))
      (println "\n=== DONE ===")
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
