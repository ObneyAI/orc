(ns mt5-acceptance
  "MT-5 — acceptance for the whole MT line: a comprehensive O*NET-only build through
   the REAL composed pipeline (survey → model → MT select/aggregate → merge → land →
   embed), measured against the A2 reference cache.

   `onet-acceptance-verdict` is the PURE, testable verdict (the /tdd deliverable); the
   live build + measurement + B-vs-A2 comparison is the /inspect-orc.

   USAGE:
     verdict tests:  clj -M:dev:test -e \"(require '[mt5-acceptance :as m]) (clojure.test/run-tests 'mt5-acceptance)\"
     live build:     clj -M:dev:test -m mt5-acceptance"
  (:require [eb12-graph-b-central-evolver :as h]
            [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; ===========================================================================
;; The PURE acceptance verdict (the /tdd deliverable) — testable on fixtures.
;; ===========================================================================

(def occupation-count-floor 800)
(def occupation-count-ceil 1200)
(def skills-populated-floor
  "A real populated result — not merely 'beats A2's 4%'. Graph B's whole point is
   that most occupations carry their skills; require a substantial majority." 0.5)

(defn onet-acceptance-verdict
  "PURE pass/fail over a measured O*NET build snapshot vs the A2 baseline.

   `b` = {:occupation-count int
          :junction-node-count int      ; bridge/observation nodes minted (MUST be 0)
          :facet-coverage {:topSkills f :topKnowledge f :jobZone f :riasec f}}  ; fraction 0..1
   `a2` = the SAME :facet-coverage shape for the A2 reference cache.

   Criteria (gating unless :gating? false):
     :occupation-count-sane   — count in [800,1200] (≈1000 SOC, not fragments)
     :no-junction-nodes       — junction-node-count == 0 (bridges DROPPED, not modeled)
     :skills-populated        — topSkills coverage ≥ skills-populated-floor (a real result)
     :skills-beats-a2         — topSkills coverage strictly > A2's (the headline win)
     :knowledge-beats-a2      — topKnowledge coverage strictly > A2's
     :jobzone-riasec          — NON-gating: report B vs A2 (bonus facets)
   :pass? = AND of the gating criteria."
  [b a2]
  (let [occ (:occupation-count b)
        junk (:junction-node-count b)
        bf (:facet-coverage b)
        af (:facet-coverage a2)
        g (fn [m k] (double (or (get m k) 0)))
        criteria
        [{:criterion :occupation-count-sane
          :pass? (boolean (and (number? occ)
                               (<= occupation-count-floor occ occupation-count-ceil)))
          :detail (str "occupation-count=" occ " (want [" occupation-count-floor ","
                       occupation-count-ceil "])")}
         {:criterion :no-junction-nodes
          :pass? (= 0 junk)
          :detail (str "junction/observation nodes=" junk " (want 0 — bridges dropped)")}
         {:criterion :skills-populated
          :pass? (>= (g bf :topSkills) skills-populated-floor)
          :detail (str "topSkills coverage=" (format "%.2f" (g bf :topSkills))
                       " (want ≥ " skills-populated-floor ")")}
         {:criterion :skills-beats-a2
          :pass? (> (g bf :topSkills) (g af :topSkills))
          :detail (str "B topSkills=" (format "%.2f" (g bf :topSkills))
                       " vs A2=" (format "%.2f" (g af :topSkills)))}
         {:criterion :knowledge-beats-a2
          :pass? (> (g bf :topKnowledge) (g af :topKnowledge))
          :detail (str "B topKnowledge=" (format "%.2f" (g bf :topKnowledge))
                       " vs A2=" (format "%.2f" (g af :topKnowledge)))}
         {:criterion :jobzone-riasec
          :gating? false
          :pass? (and (>= (g bf :jobZone) (g af :jobZone)) (>= (g bf :riasec) (g af :riasec)))
          :detail (str "B jobZone=" (format "%.2f" (g bf :jobZone)) " riasec=" (format "%.2f" (g bf :riasec))
                       " vs A2 jobZone=" (format "%.2f" (g af :jobZone)) " riasec=" (format "%.2f" (g af :riasec))
                       " [NON-GATING bonus]")}]]
    {:pass? (every? :pass? (remove #(false? (:gating? %)) criteria))
     :reasons criteria}))

;; ===========================================================================
;; TDD — the verdict passes a good graph and FAILS honestly on each bad shape.
;; ===========================================================================

(def ^:private a2-fixture {:facet-coverage {:topSkills 0.04 :topKnowledge 0.04 :jobZone 0.46 :riasec 0.46}})

(deftest verdict-passes-a-good-onet-build
  (testing "≈1000 occupations, populated topSkills ≫ A2, 0 junction nodes → pass"
    (let [v (onet-acceptance-verdict
             {:occupation-count 1016 :junction-node-count 0
              :facet-coverage {:topSkills 0.88 :topKnowledge 0.85 :jobZone 0.50 :riasec 0.50}}
             a2-fixture)]
      (is (:pass? v))
      (is (every? :pass? (:reasons v))))))

(deftest verdict-fails-on-empty-skills
  (testing "a completed build with ~0 populated topSkills is a FAIL (no false green)"
    (let [v (onet-acceptance-verdict
             {:occupation-count 1016 :junction-node-count 0
              :facet-coverage {:topSkills 0.0 :topKnowledge 0.0 :jobZone 0.5 :riasec 0.5}}
             a2-fixture)]
      (is (not (:pass? v)))
      (is (not (:pass? (first (filter #(= :skills-populated (:criterion %)) (:reasons v))))))
      (is (not (:pass? (first (filter #(= :skills-beats-a2 (:criterion %)) (:reasons v)))))))))

(deftest verdict-fails-on-junction-nodes
  (testing "any junction/observation node minted → FAIL"
    (let [v (onet-acceptance-verdict
             {:occupation-count 1016 :junction-node-count 7
              :facet-coverage {:topSkills 0.88 :topKnowledge 0.85 :jobZone 0.5 :riasec 0.5}}
             a2-fixture)]
      (is (not (:pass? v)))
      (is (not (:pass? (first (filter #(= :no-junction-nodes (:criterion %)) (:reasons v)))))))))

(deftest verdict-fails-on-fragmented-count
  (testing "occupation count wildly off (per-row fragments) → FAIL"
    (let [v (onet-acceptance-verdict
             {:occupation-count 62000 :junction-node-count 0
              :facet-coverage {:topSkills 0.88 :topKnowledge 0.85 :jobZone 0.5 :riasec 0.5}}
             a2-fixture)]
      (is (not (:pass? v)))
      (is (not (:pass? (first (filter #(= :occupation-count-sane (:criterion %)) (:reasons v)))))))))

;; ===========================================================================
;; A2 baseline loader + B measurement (analysis — MAY name O*NET fields; #12 binds
;; the IMPLEMENTATION, not this driver).
;; ===========================================================================

(def a2-cache "/Users/darylroberts/Desktop/Code/daryls-area51/.bryc-graph-cache-with-embeddings.json")

(defn- frac [n d] (if (pos? d) (double (/ n d)) 0.0))

(defn a2-baseline
  "Read the A2 reference cache and measure occupation-facet coverage."
  []
  (let [d (json/read-str (slurp a2-cache) :key-fn identity)
        props (get d "properties")
        soc (filter #(str/starts-with? (key %) "soc:") props)
        n (count soc)
        pop? (fn [node pat] (some (fn [[k v]]
                                    (and (not= k "embedding") (re-find pat (str k))
                                         (not (contains? #{nil "" [] {}} v))))
                                  node))]
    {:occupation-count n
     :facet-coverage
     {:topSkills   (frac (count (filter #(pop? (val %) #"(?i)skill") soc)) n)
      :topKnowledge (frac (count (filter #(pop? (val %) #"(?i)knowledg") soc)) n)
      :jobZone     (frac (count (filter #(pop? (val %) #"(?i)zone") soc)) n)
      :riasec      (frac (count (filter #(pop? (val %) #"(?i)riasec|interest") soc)) n)}}))

(defn- occ-node? [c] (re-find #"(?i)occupation|soc|onet" (str (:uri c))))
(defn- junction-node? [c]
  (re-find #"(?i) to |bridge|observation|junction|/measure|activity.?to|ability.?to" (str (:uri c))))

(defn measure-b
  "Measure the built graph B's occupation-facet coverage from the ::concepts snapshot."
  [concepts]
  (let [occ (filter occ-node? concepts)
        n (count occ)
        arr-attr? (fn [c pat]
                    (some (fn [[k v]] (and (re-find pat (str k))
                                           (or (and (coll? v) (seq v)) (and (some? v) (not= v "")))))
                          (:attributes c)))]
    {:occupation-count n
     :junction-node-count (count (filter junction-node? concepts))
     :facet-coverage
     {:topSkills    (frac (count (filter #(arr-attr? % #"(?i)skill") occ)) n)
      :topKnowledge (frac (count (filter #(arr-attr? % #"(?i)knowledg") occ)) n)
      :jobZone      (frac (count (filter #(arr-attr? % #"(?i)zone") occ)) n)
      :riasec       (frac (count (filter #(arr-attr? % #"(?i)riasec|interest") occ)) n)}
     :sample-occ (mapv (fn [c] {:uri (:uri c) :label (:label c)
                                :attr-keys (vec (keys (:attributes c)))})
                       (take 5 occ))}))

(defn run-once [max-containers]
  (let [r (h/run! {:only [:onet] :max-containers max-containers :store :sqlite
                   :budget {:max-iterations 8 :total-budget-ms 600000 :max-retries 3}
                   :evolver-config {:max-iterations 1}})
        b (measure-b (::h/concepts r))]
    {:status (:status r) :b b}))

(defn -diag
  "Diagnostic: ONE build at a given cap, print status + measurement (for bisecting OOM)."
  [cap]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (println "=== MT-5 DIAG — ONE O*NET build at cap" cap "===")
  (let [{:keys [status b]} (run-once cap)]
    (println "status:" status)
    (pp/pprint (dissoc b :sample-occ))
    (doseq [s (:sample-occ b)] (println "  " s))
    (shutdown-agents)
    (System/exit 0)))

(defn -main [& args]
  (if-let [cap (some-> (first args) parse-long)]
    (-diag cap)
    (do
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (println "=== MT-5 ACCEPTANCE — comprehensive O*NET-only build (x2), B vs A2 ===")
  (let [a2 (a2-baseline)
        _ (println "A2 baseline:" (:occupation-count a2) "occ," (:facet-coverage a2))
        run1 (run-once 12)
        _ (println "\n--- RUN 1 --- status" (:status run1)) _ (pp/pprint (dissoc (:b run1) :sample-occ))
        _ (println "  sample occ:") _ (doseq [s (:sample-occ (:b run1))] (println "   " s))
        run2 (run-once 12)
        _ (println "\n--- RUN 2 --- status" (:status run2)) _ (pp/pprint (dissoc (:b run2) :sample-occ))
        v1 (onet-acceptance-verdict (:b run1) a2)
        v2 (onet-acceptance-verdict (:b run2) a2)]
    (println "\n=== VERDICT RUN 1 ===  pass?" (:pass? v1))
    (doseq [r (:reasons v1)] (println (format "  %-24s %-6s %s" (name (:criterion r)) (str (:pass? r)) (:detail r))))
    (println "\n=== VERDICT RUN 2 ===  pass?" (:pass? v2))
    (doseq [r (:reasons v2)] (println (format "  %-24s %-6s %s" (name (:criterion r)) (str (:pass? r)) (:detail r))))
    (println "\n=== REPRODUCIBILITY ===")
    (println "  run1 occ:" (:occupation-count (:b run1)) " run2 occ:" (:occupation-count (:b run2)))
    (println "  stable?" (< (abs (- (:occupation-count (:b run1)) (:occupation-count (:b run2)))) 50))
    (shutdown-agents)
    (System/exit (if (and (:pass? v1) (:pass? v2)) 0 1))))))
