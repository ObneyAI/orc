(ns ai.obney.orc.ontology.cc21b-evidence-projection-test
  "CC-21b — the consolidator's evidence window must fit inside the provider's
   context WITHOUT dropping occurrences.

   The defect, measured (CC-21a), not inferred. For target
   `:node-type :repl-researcher` the window is 178 real observations at
   ~36 KB each — a 6,401,543-byte payload — and the provider rejects the
   whole call, verbatim:

     This endpoint's maximum context length is 1048576 tokens.
     However, you requested about 1571414 tokens

   0/3 successful, retried 4x each, non-recoverable. Those two targets are
   2 of 638, but they are 34 of 145 consolidation requests — 23.4% of every
   consolidation ever attempted, at 0/3 success, on the node that writes code.

   Where the bytes are: `:inputs` 65.3% + `:writes` 32.6% = 97.9%, and inside
   those the orc-sessions session transcript (`:turns`) alone is 74.7% of the
   whole window. It rides in `:inputs` and is written straight back out in
   `:writes`, and it grows within a session — so the newest observations are
   the biggest and capping the window is the weakest lever.

   The fix is the one the EMITTER already made for this exact data:
   `orc_service/core/commands.clj` keeps only the namespaced `:inputs` keys
   ('correlation metadata that must survive') and pushes `:writes` to the
   value log. The consolidator was the last place still asking for the values.

   RETENTION IS THE POINT. Every rejected alternative (byte budget, string
   truncation) drops evidence: a 1 MB budget keeps 17 of 178 and is STILL at
   45% of the limit; 64 KB yields an empty window, straight into CC-4's
   evidence guard. So every size assertion below is paired with a retention
   assertion — a projection that loses occurrences is the byte budget wearing
   a different hat.

   THE CORPUS. `cc21b_evidence_window_corpus.edn` is mechanically extracted,
   verbatim, from the real production window (tenant a89f9f58, the same dump
   CC-21a measured), by a path that rebuilds the window WITHOUT calling the
   consolidator — a fixture extracted through the fix would be the fix's own
   output, and every RED assertion here would be vacuous. It is a spanning
   set: all three statuses that occur (`:blocked` 57, `:success` 118,
   `:failure` 3), both size extremes (94,462 B and 6,031 B), and the one
   shape in the corpus carrying namespaced exec-context keys. The full
   178-observation window is 6.4 MB and does not belong in git; the size
   assertions below rebuild a 178-observation window from these four, which
   comes to 6,665,128 B against the real 6,401,543 B — 4.1% high, because the
   spanning set over-weights the big `:blocked` observations."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.core.evidence-projection :as evidence-projection]))

;; =============================================================================
;; The provider ceiling, and CC-21a's token model calibrated against 10 REAL
;; provider measurements (`prompt_tokens` from the ledgers of the runs that
;; failed). The constant is NOT constant: A/B/C windows tokenise at ~3.2-4.0
;; chars/token, this one at 2.0. Two slopes were fitted; the conservative one
;; never underestimated any of the 10 anchors.
;;
;;   Below the limit is asserted with the CONSERVATIVE slope (never under-reads)
;;   Above the limit is asserted with the MEAN slope (never over-reads)
;;
;; so neither direction is proved by the estimator being generous.
;; =============================================================================

(def ^:private provider-limit-tokens 1048576)
(def ^:private c0 2500)
(def ^:private slope-mean 1.0555)
(def ^:private slope-conservative 1.1833)

(defn- p-cls [^String s]
  (count (re-seq #"[A-Za-z]+|[0-9]+|[^\sA-Za-z0-9]" s)))

(defn- est-tokens-mean [x] (long (+ c0 (* slope-mean (p-cls (pr-str x))))))
(defn- est-tokens-conservative [x] (long (+ c0 (* slope-conservative (p-cls (pr-str x))))))

;; =============================================================================
;; The real corpus
;; =============================================================================

(def ^:private corpus
  (delay (edn/read-string (slurp (io/resource "cc21b_evidence_window_corpus.edn")))))

(defn- observations
  "The four real observations, verbatim as the production event store holds them."
  []
  (mapv :observation (:observations @corpus)))

(def ^:private production-window-observation-count
  "The real window's size for `:node-type :repl-researcher` — measured, and
   re-asserted below so this number cannot rot into a comment."
  178)

(defn- production-scale-window
  "A 178-observation window built from the four real observations. Not a
   simulation of the payload — every observation in it IS a production
   observation; only the multiplicity is reconstructed."
  [obs]
  (vec (take production-window-observation-count (cycle obs))))

(defn- ->ctx
  "An in-memory event store holding these observations as the completion
   events they are, so the window is built by the REAL gather path rather
   than by the test.

   The store returns events sorted by `:event/id`, so the ids are assigned
   MONOTONICALLY from the input order — a random id per event silently
   shuffles the window, and window ORDER is load-bearing
   (`evidence-window-episodes` keeps the LAST 50). That the order really did
   survive is asserted, not assumed, below."
  [obs]
  (let [tenant-id (random-uuid)
        store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})]
    (dosync (alter (:state store)
                   (fn [s] (assoc s :events
                                  (vec (map-indexed
                                         (fn [i ev]
                                           (assoc ev
                                                  :grain/tenant-id tenant-id
                                                  :event/id (java.util.UUID. (inc (long i)) 0)))
                                         obs))))))
    {:event-store store :tenant-id tenant-id}))

(defn- exec-context-observation
  "The only shape in the corpus that carries NAMESPACED `:inputs` keys — a
   map-each child's correlation metadata. 6 of 1,788 completion events have
   it, and it is the metadata the emitter's comment calls out as the thing
   that MUST survive: `trace-execution-key` and `matches-execution-context?`
   correlate on it and map-each correctness depends on it."
  []
  (get-in @corpus [:exec-context-observation :observation]))

(def ^:private gather-recent-events @#'consolidator/gather-recent-events)
(def ^:private occurrence-pair @#'consolidator/occurrence-pair)
(def ^:private evidence-window-episodes @#'consolidator/evidence-window-episodes)

(defn- gathered
  "The window the consolidator actually hands the reflection, for the real
   failing target."
  [obs]
  (gather-recent-events (->ctx obs) :node-type :repl-researcher))

;; =============================================================================
;; Cycle 1/2 — the window must fit, and it must keep every occurrence
;; =============================================================================

(deftest the-corpus-is-the-defect-and-still-reproduces-it
  (testing "the fixture is the real thing: raw, a production-scale window of
            these observations blows past the provider limit"
    (let [obs (observations)
          raw (production-scale-window obs)]
      (is (= 4 (count obs)) "four real observations, printed so this is not vacuous")
      (is (= production-window-observation-count (:window-observation-count @corpus))
          "the fixture records the real window's observation count")
      (is (= 6401543 (:window-edn-bytes @corpus))
          "the fixture records the real window's measured size, to the byte")
      (println "\n[CC-21b] fixture observations N =" (count obs)
               "| raw production-scale window bytes =" (count (pr-str raw))
               "| est tokens (mean) =" (est-tokens-mean raw))
      (is (> (est-tokens-mean raw) provider-limit-tokens)
          (str "RAW, this window does not fit: est " (est-tokens-mean raw)
               " tokens against a " provider-limit-tokens "-token limit. "
               "If this ever stops being true the fixture has stopped being "
               "the defect and every assertion below is vacuous.")))))

(deftest the-projected-window-fits-inside-the-provider-context
  (testing "through the real gather path, a production-scale window fits —
            and every occurrence is still in it"
    (let [obs (observations)
          window (gathered (production-scale-window obs))
          tokens (est-tokens-conservative window)]
      (println "[CC-21b] projected window N =" (count window)
               "| bytes =" (count (pr-str window))
               "| est tokens (conservative) =" tokens
               (format "| %.1f%% of limit" (* 100.0 (/ tokens (double provider-limit-tokens)))))
      (is (= production-window-observation-count (count window))
          "ALL 178 observations retained — a projection that drops occurrences
           is the byte budget wearing a different hat")
      (is (< tokens provider-limit-tokens)
          (str "the projected window fits: est " tokens " conservative tokens "
               "against a " provider-limit-tokens "-token limit")))))

;; =============================================================================
;; Cycle 3 — the evidence IDENTITY survives, so nothing downstream moves
;;
;; CC-4's grounding guard and CC-7's support counts resolve claims against
;; `[sheet-id tick-id]` occurrence pairs. If the projection touched those, a
;; "smaller window" would silently become a DIFFERENT window — which is the
;; failure mode every rejected alternative has.
;; =============================================================================

(deftest the-occurrence-pairs-cc4-and-cc7-resolve-against-survive-untouched
  (testing "every observation still yields the same [sheet-id tick-id] pair"
    (let [obs (observations)
          window (gathered obs)
          raw-pairs (mapv occurrence-pair obs)
          projected-pairs (mapv occurrence-pair window)]
      (println "[CC-21b] occurrence pairs before =" (count (remove nil? raw-pairs))
               "| after =" (count (remove nil? projected-pairs))
               "| episodes before =" (count (evidence-window-episodes obs))
               "| after =" (count (evidence-window-episodes window)))
      (is (= (count obs) (count window)) "no observation was dropped")
      (is (every? some? raw-pairs)
          "non-vacuous: every fixture observation HAS an occurrence pair to lose")
      (is (= raw-pairs projected-pairs)
          "the occurrence pairs are identical, in order")
      (is (= (evidence-window-episodes obs) (evidence-window-episodes window))
          "evidence-window-episodes — what CC-4's guard is handed — is unchanged")))

  (testing "the identity and outcome fields the reflection reasons over are untouched"
    (let [obs (observations)
          window (gathered obs)
          identity-of (juxt :event/type :sheet-id :tick-id :node-id :node-type
                            :status :error :duration-ms :usage :timestamp
                            :completion-kind :block-payload)]
      (is (= (mapv identity-of obs) (mapv identity-of window))
          "status, error, duration, usage, block reason and identity all survive
           — the projection only reduces VALUE payloads"))))

(deftest the-shape-of-what-was-read-and-written-replaces-the-values
  (testing "the read/write KEY SETS survive; only the values go"
    (let [obs (observations)
          window (gathered obs)]
      (doseq [[raw projected] (map vector obs window)]
        (is (contains? raw :writes) "non-vacuous: the raw observation HAS values to lose")
        (is (not (contains? projected :writes))
            "the write VALUES are gone")
        (is (= (set (keys (:writes raw))) (set (:write-keys projected)))
            "…and every write KEY is still there")
        ;; The emitter's own rule, matched exactly: a key whose value is nil
        ;; has no shape to record, so it is named in :write-keys and absent
        ;; from :write-profile.
        (is (= (set (keys (remove (comp nil? val) (:writes raw))))
               (set (keys (:write-profile projected))))
            "…with a shape profile for every non-nil value, the same shape
             (and the same nil rule) the emitter records")
        (is (= (set (keys (:inputs raw))) (set (:read-keys projected)))
            "every blackboard key this node READ is still named")
        (is (not (contains? projected :inputs))
            "this corpus predates the emitter fix — none of its :inputs keys are
             namespaced, so nothing correlation-bearing is left to keep"))
      (is (pos? (count obs)) (str "N = " (count obs) " observations checked")))))

(deftest the-execution-context-that-must-survive-does
  (testing "namespaced :inputs keys — map-each correlation metadata — are kept verbatim"
    (let [raw (exec-context-observation)
          projected (evidence-projection/project-observation raw)
          namespaced (into {} (filter (fn [[k _]] (some? (namespace k)))) (:inputs raw))]
      (is (seq namespaced)
          "non-vacuous: this real observation HAS namespaced input keys")
      (println "[CC-21b] exec-context keys kept =" (pr-str (sort (map str (keys namespaced)))))
      (is (= namespaced (:inputs projected))
          "the exec-context is passed through untouched — map-each correctness
           and trace-execution-key depend on it")
      (is (= (set (keys (:writes raw))) (set (:write-keys projected)))
          "and its writes are still named after being reduced to shape"))))

(deftest the-projection-is-idempotent-and-never-overwrites-the-source
  (testing "projecting twice changes nothing the second time"
    (let [obs (observations)
          once (mapv evidence-projection/project-observation obs)
          twice (mapv evidence-projection/project-observation once)]
      (is (= once twice)
          "an observation emitted AFTER the producer-side fix already carries
           :read-keys/:write-keys/:input-profile/:write-profile; re-projecting
           must not clobber the source's own account of its shape")
      (is (= (count obs) (count once)) (str "N = " (count once))))))

(deftest observations-with-no-value-payload-pass-straight-through
  (testing "the :tree-class join path — where these bytes are NOT — is untouched"
    ;; Measured: eliding :inputs/:writes saves 47.8x on the failing path and
    ;; EXACTLY 0 on the tree-class path, whose payload is :top-candidates
    ;; (59.4%) and :judge-scores (17.2%). This asserts the "exactly 0" half:
    ;; a classification observation must come back byte-identical.
    (let [classification (-> (observations) first
                             (select-keys [:event/type :sheet-id :tick-id :status :timestamp])
                             (assoc :assigned-tree-id (random-uuid)
                                    :confidence 0.82
                                    :reasoning "kept verbatim — this is not a value payload"))]
      (is (= classification (evidence-projection/project-observation classification))
          "no :inputs, no :writes ⇒ nothing to project")
      (is (= nil (evidence-projection/project-observation nil)))
      (is (= "not a map" (evidence-projection/project-observation "not a map"))))))
