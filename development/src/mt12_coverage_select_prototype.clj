(ns mt12-coverage-select-prototype
  "SLICE 0 (throwaway prototype, GATES the coverage-aware-selection plan). Proves the
   MECHANISM on real O*NET before any production change or TDD:

     H1 (parse)   — the coverage-aware ranker, given goal + NUMBERED CQs + candidate
                    summaries, emits a PARSEABLE coverage map (vector of maps, NOT an
                    unparsed string) — the MT-11 schema lesson (concrete [:vector [:map]]
                    + concrete leaf types + descriptions + a string :enum, NO bare kw).
     H2 (discover)— the LLM correctly assigns the Job Zones table to a job-zone CQ and
                    the Interests table to an interests CQ (the coverage judgment is real).
     H3 (promote) — at cap=6, today's flat-rank STARVES those facet tables, but the
                    deterministic bounded-promotion over the coverage map LIFTS them into
                    the selection. (Pure logic — computed here from the live coverage map.)

   Facet NAMES (job zone / interests) appear ONLY in this reviewer prototype's measurement,
   NEVER in the coverage-rank prompt or the (future) production path — the general property.

   USAGE: clj -J-Xmx4g -M:dev:test -m mt12-coverage-select-prototype"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def onet {:type :excel :path h/onet-dir})

(def goal
  (str "Build an ontology of OCCUPATIONS and the skills, knowledge, job-zone "
       "requirements, and interests associated with each occupation."))

;; Hand-authored CQ set for a DETERMINISTIC prototype (production will thread the
;; STEP-3-derived CQs). Includes a job-zone CQ (idx 2) + an interests CQ (idx 3) —
;; the two facets today's flat rank starves at cap=6.
(def cqs
  ["What skills does a given occupation require?"
   "What knowledge areas does a given occupation require?"
   "What job zone (level of education/experience preparation) is a given occupation in?"
   "What vocational interests (RIASEC categories) are associated with a given occupation?"
   "What are the core work activities of a given occupation?"])

;; ---- the coverage-aware rank sheet (inline, the SLICE-2 shape, prototype-local) ----

(def coverage-schema
  [:vector
   [:map {:closed false}
    [:name [:string {:description "the container's EXACT :name, copied verbatim from the candidates"}]]
    [:serves-cqs
     [:vector {:description "the 0-based indices of the numbered competency-questions this container helps ANSWER (may be empty)"}
      :int]]
    [:relevance {:optional true}
     [:enum {:description "overall relevance of this container to the goal"} "high" "medium" "low"]]]])

(defn coverage-prompt [cq-list]
  (str
   "*** HOW THIS NODE WORKS ***\n"
   "You are a single REASONING step. You are GIVEN: the GOAL, a NUMBERED list of "
   "COMPETENCY QUESTIONS the built ontology must be able to answer, and a list of "
   "container SUMMARIES (each with its EXACT :name, :shape, :columns, :approx-row-count). "
   "You do NOT call tools or emit a tree.\n\n"
   "*** THE NUMBERED COMPETENCY QUESTIONS ***\n"
   (str/join "\n" (map-indexed (fn [i q] (str "  " i ". " q)) cq-list))
   "\n\n*** YOUR JOB ***\n"
   "For EACH container, decide (a) its overall relevance to the goal, and (b) WHICH of "
   "the numbered competency questions it helps ANSWER — by their 0-based indices. Judge "
   "from the container's columns + shape + name. A container may serve zero, one, or "
   "several questions. Order your output MOST-RELEVANT-FIRST. Use each :name VERBATIM "
   "(never rename/invent/merge).\n\n"
   "*** OUTPUT (reasoning FIRST) ***\n"
   "  1. `reasoning` — think through, per question, which containers can answer it.\n"
   "  2. `container-coverage` — a VECTOR of {:name <exact> :serves-cqs [<int idx> ...] "
   ":relevance \"high\"|\"medium\"|\"low\"}, most-relevant-first. Emit REAL structured "
   "data (a vector of maps), NOT a JSON string, NOT prose."))

(defn build-coverage-sheet! [ctx]
  (dsl/build-workflow!
   ctx (dsl/workflow "mt12/coverage-rank"
         (dsl/blackboard {:goal :string
                          :competency-questions [:vector :string]
                          :candidates [:vector [:map {:closed false}
                                                [:name {:optional true} :any]
                                                [:shape {:optional true} :any]
                                                [:columns {:optional true} [:vector :any]]
                                                [:approx-row-count {:optional true} :any]]]
                          :reasoning :string
                          :container-coverage coverage-schema})
         (dsl/sequence "root"
           (dsl/llm "cover"
             :model h/default-model
             :instruction (coverage-prompt cqs)
             :reads [:goal :competency-questions :candidates]
             :writes [:reasoning :container-coverage])))))

;; ---- deterministic bounded promotion (the SLICE-1 heart, prototype-local) ----

(def coverage-slack 8)

(defn coverage-select
  "Pure: base take-cap by rank order, then promote (bounded) the highest-ranked
   unselected container serving each still-uncovered CQ. Returns {:selected :promoted
   :cq-coverage}."
  [ordered-names cov-by-name n-cqs cap]
  (let [ordered (vec ordered-names)
        base (vec (take cap ordered))
        covered-by (fn [names] (reduce into #{} (map #(set (cov-by-name %)) names)))
        ceiling (min (count ordered) (+ cap coverage-slack))]
    (loop [selected base
           promoted []]
      (let [covered (covered-by selected)
            uncovered (remove covered (range n-cqs))]
        (if (or (empty? uncovered) (>= (count selected) ceiling))
          {:selected selected :promoted promoted
           :cq-coverage {:total-cqs n-cqs :covered (vec (sort covered))
                         :uncovered (vec uncovered) :complete? (empty? uncovered)}}
          (let [cq (first uncovered)
                cand (first (filter (fn [nm] (and (not (some #{nm} selected))
                                                  (contains? (set (cov-by-name nm)) cq)))
                                    ordered))]
            (if cand
              (recur (conj selected cand) (conj promoted {:name cand :for-cq cq}))
              ;; no survivor serves this CQ at all — honestly uncoverable; stop trying it
              (recur (conj selected ::skip) promoted))))))))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-12 COVERAGE-SELECT PROTOTYPE (SLICE 0 gate) — real O*NET, real LLM ===")
      (let [candidates (csel/classify-source-containers onet {})
            survivors (filterv :keep? candidates)
            summaries (mapv (fn [c] {:name (:name c) :shape (:shape c)
                                     :columns (vec (:header c)) :approx-row-count (:row-count c)})
                            survivors)
            _ (println "survivors:" (count survivors) " (cap=6)")
            sid (build-coverage-sheet! ctx)
            tick (random-uuid)
            _ (dsl/execute ctx sid {"goal" goal "competency-questions" cqs "candidates" summaries}
                           :tick-id tick :timeout-ms 180000)
            bb (dsl/get-tick-blackboard ctx tick)
            cov (get-in bb [:container-coverage :value])]
        (println "\n--- H1 (parse) ---")
        (println "  coverage arrival:" (cond (vector? cov) "VECTOR(parsed-ok)"
                                             (string? cov) "STRING(parse-FAILED)"
                                             :else (str (type cov))))
        (println "  entries:" (count cov))
        (let [cov-by-name (into {} (map (juxt :name (comp vec :serves-cqs)) cov))
              ordered-names (mapv :name cov)
              ;; reviewer-only facet locate (NOT in the prompt/production)
              jobzone-nm (first (filter #(re-find #"(?i)job zone" (str %)) ordered-names))
              interests-nm (first (filter #(re-find #"(?i)interest" (str %)) ordered-names))]
          (println "\n--- H2 (discover) — does the LLM map facet tables → facet CQs? ---")
          (println "  Job Zones table:" (pr-str jobzone-nm) " serves-cqs=" (pr-str (get cov-by-name jobzone-nm)))
          (println "     assigns job-zone CQ (idx 2)? =>" (boolean (some #{2} (get cov-by-name jobzone-nm))))
          (println "  Interests table:" (pr-str interests-nm) " serves-cqs=" (pr-str (get cov-by-name interests-nm)))
          (println "     assigns interests CQ (idx 3)? =>" (boolean (some #{3} (get cov-by-name interests-nm))))
          (println "\n--- H3 (promote) — flat take-6 vs coverage-aware select ---")
          (let [flat6 (vec (take 6 ordered-names))
                cs (coverage-select ordered-names cov-by-name (count cqs) 6)]
            (println "  flat take-6:" flat6)
            (println "     jobzone in flat-6? " (boolean (some #{jobzone-nm} flat6))
                     " | interests in flat-6? " (boolean (some #{interests-nm} flat6)))
            (println "  coverage-aware selected:" (:selected cs))
            (println "     promoted:" (pr-str (:promoted cs)))
            (println "     cq-coverage:" (pr-str (:cq-coverage cs)))
            (println "     jobzone in coverage-sel? " (boolean (some #{jobzone-nm} (:selected cs)))
                     " | interests in coverage-sel? " (boolean (some #{interests-nm} (:selected cs))))
            (println "\n=== GATE VERDICT ===")
            (let [h1 (vector? cov)
                  h2 (and (some #{2} (get cov-by-name jobzone-nm))
                          (some #{3} (get cov-by-name interests-nm)))
                  h3 (and (some #{jobzone-nm} (:selected cs)) (some #{interests-nm} (:selected cs)))]
              (println "  H1 parse:" (if h1 "PASS" "FAIL"))
              (println "  H2 discover:" (if h2 "PASS" "FAIL — LLM didn't map facet tables to facet CQs"))
              (println "  H3 promote-lifts-facets:" (if h3 "PASS" "FAIL — promotion didn't include the facet tables"))
              (println "  => MECHANISM" (if (and h1 h2 h3) "PROVEN — proceed to TDD" "NOT PROVEN — revisit before TDD")))))
        (println "\nraw coverage map:")
        (pp/pprint cov)
        (println "=== DONE ==="))
      (finally ((deref #'h/stop-ctx) ctx)))))
