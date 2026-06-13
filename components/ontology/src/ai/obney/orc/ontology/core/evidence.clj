(ns ai.obney.orc.ontology.core.evidence
  "S13 — Evidence Tier-1: deterministic, always-on aggregation that rides
   the compare-to-existing path (S12's cascade) for free.

   This namespace ships TWO things:

   1. The pure, deterministic `evidence-score` formula — the single
      place every weight + its rationale lives. Same input → same output.
      No LLM, no time-based variance, no hidden constants. Used by the
      cascade's defcommand to stamp the score onto each
      `:ontology/concept-evidence-aggregated` event AND by the
      `get-concept-evidence` read-model accessor when it surfaces the
      latest aggregate for a URI.

   2. Helpers that take a `:ontology/concept-evidence` projection's
      per-URI entry and SHAPE it as the structured ledger record the
      query surface returns. Pure shaping — no side effects.

   Why pure? Replay-determinism is a binding S13 acceptance criterion
   (d) — replaying the event stream must reconstruct identical state.
   Any non-pure helper in this chain would defeat the property.

   The compare-to-existing emit hook lives in
   `core/commands.clj :: run-dedup-cascade`. The projection lives in
   `core/read_models.clj :: concept-evidence` (event-type
   `:ontology/concept-evidence-aggregated`). Both consult this
   namespace for the score and the ledger shape — single source of
   truth."
  (:require [clojure.string :as str]))

;; =============================================================================
;; The evidence-score function — THE load-bearing transparency
;; =============================================================================

(defn evidence-score
  "Deterministic evidence score ∈ [0.0, 1.0] for one concept.

   Inputs (all optional; missing keys default to 0 / empty map):
   - :source-count                    int — total source-refs touching
                                            the concept (re-encounters
                                            from any source).
   - :distinct-sources                int — count of DISTINCT source-refs
                                            (deduped on source-id).
   - :dedup-decisions                 int — count of cascade verdicts
                                            (any tier) recorded against
                                            this concept.
   - :equivalence-history             int — count of equivalence
                                            assertions that mention this
                                            concept (S08 events touching
                                            this URI as source or
                                            target).
   - :axiom-presence                  int — count of disjointness /
                                            type axioms referencing this
                                            concept (S07).
   - :edge-confidence-distribution    map — {:extracted N :inferred N
                                            :ambiguous N} edge-meta tally
                                            (S06).
   - :age-of-concept-days             num — days since the concept's
                                            first-recorded creation.
   - :contradictions-count            int — number of contradiction
                                            markers recorded for this
                                            concept (criterion b).

   Result is the SUM of weighted contributions, clamped to [0.0, 1.0].

   ## Weight rationale

   The weights below are the load-bearing transparency of this slice —
   each one has a written, defensible justification. No 'this captures
   importance' boilerplate. If a weight is changed, the rationale here
   must change with it.

   - :distinct-sources    × 0.40  — DIVERSITY beats VOLUME. 5
     independent sources is much stronger evidence than 100 occurrences
     from a single source — the over-counting failure mode the slice
     explicitly tests. The single largest weight by design.

   - :source-count        × 0.10  — Volume DOES matter (a thing
     re-mentioned even by the same source is mild evidence) but is
     capped so it cannot dominate diversity. A 4x ratio against
     distinct-sources is the floor that survives the B(5 src × 20) >
     A(2 src × 100) adversarial test.

   - :dedup-decisions     × 0.15  — Every KEEP/MERGE verdict against
     this concept is a cascade-validated decision. A concept the
     cascade has examined many times is a concept whose boundary is
     well-tested, regardless of whether the verdicts kept or merged.

   - :equivalence-history × 0.15  — Equivalence assertions counter-
     balance KEEP-only concepts. A merged-into concept is high-
     confidence-typed AND has a queryable trail to its synonym(s).

   - :axiom-presence      × 0.10  — A concept with disjointness
     assertions (the maintainer EXPLICITLY drew its boundary) is more
     trustworthy than one without. The weight is moderate because
     axioms are mostly hand-authored — the signal is high-quality but
     low-volume.

   - :edge-confidence     × 0.05  — Edge-metadata mix:
     :extracted weighted 1.0, :inferred 0.5, :ambiguous 0.2. The
     contribution is small because edges are PER-RELATIONSHIP — many
     low-confidence edges shouldn't bury one strong concept.

   - :age-of-concept-days × 0.05  — DOCUMENTED choice: older = MORE
     confident GIVEN no contradictions. The reasoning: a concept that
     has SURVIVED 30 days of new sources WITHOUT a contradiction
     marker has been implicitly re-validated by every source that
     touched it without conflict. Saturates at 30 days. Contradictions
     ZERO the age contribution (any positive
     :contradictions-count → 0 age weight) so a stale-but-now-
     contested concept gets no premium.

   ## Saturation curve

   All count-style inputs (source-count, distinct-sources,
   dedup-decisions, equivalence-history, axiom-presence) pass through
   a log-base-11 saturation: `min(1, log(1+n) / log(11))`. This
   reaches ~1.0 at n=10 and grows extremely slowly past that. The
   curve defeats volume-spam attacks — doubling source-count from 100
   to 200 barely moves the contribution. The base-11 choice gives
   exactly 1.0 at n=10 (a 'mature' single-axis count) without
   discontinuity at n=0."
  [{:keys [source-count distinct-sources dedup-decisions
           equivalence-history axiom-presence
           edge-confidence-distribution
           age-of-concept-days contradictions-count]
    :or {source-count 0
         distinct-sources 0
         dedup-decisions 0
         equivalence-history 0
         axiom-presence 0
         edge-confidence-distribution {}
         age-of-concept-days 0
         contradictions-count 0}}]
  (let [;; log-base-11 saturation reaches 1.0 at n=10 and grows
        ;; vanishingly slowly past that — defeats volume-spam.
        sat (fn [^long n]
              (if (<= n 0)
                0.0
                (min 1.0 (/ (Math/log (inc (double n)))
                            (Math/log 11.0)))))
        diversity  (sat (long distinct-sources))
        volume     (sat (long source-count))
        decisions  (sat (long dedup-decisions))
        equiv-hist (sat (long equivalence-history))
        axiom      (sat (long axiom-presence))
        edge       (let [d edge-confidence-distribution
                         tot (apply + (vals d))
                         ex  (get d :extracted 0)
                         inf (get d :inferred 0)
                         amb (get d :ambiguous 0)]
                     (if (zero? tot)
                       0.0
                       (/ (+ (* 1.0 ex) (* 0.5 inf) (* 0.2 amb))
                          (double tot))))
        ;; Age: older = more confident IFF zero contradictions.
        ;; Any positive contradictions-count wipes the age premium.
        age        (if (pos? (long contradictions-count))
                     0.0
                     (min 1.0 (/ (double age-of-concept-days) 30.0)))
        raw (+ (* 0.40 diversity)
               (* 0.10 volume)
               (* 0.15 decisions)
               (* 0.15 equiv-hist)
               (* 0.10 axiom)
               (* 0.05 edge)
               (* 0.05 age))]
    (max 0.0 (min 1.0 raw))))

;; =============================================================================
;; Aggregation — pure: build the inputs to evidence-score from cascade context
;; =============================================================================

(defn aggregate-from-cascade
  "Given the side of a candidate pair (the side's URI + its current
   evidence entry from the projection, if any) and the cascade verdict
   that just ran, return the inputs the projection should store for
   THIS URI.

   Pure — no I/O, no time access (`:computed-at` is supplied by the
   caller). Used by the defcommand to compute the body of the
   `:ontology/concept-evidence-aggregated` event for each side.

   Inputs:
   - :existing       map | nil — the URI's prior entry in the
                                 concept-evidence projection (nil for
                                 first encounter).
   - :verdict        map     — the cascade verdict map (`:tier`,
                                 `:verdict`, `:reason`, ...).
   - :source-ref     str | nil — the source-id / source-uri the
                                 candidate originated from, if known.
                                 Counts toward :source-count and
                                 :distinct-sources when supplied.
   - :computed-at    str     — ISO timestamp the event will carry.
   - :alignment-id   uuid | nil — when verdict is :merge, the
                                 alignment-section the equivalence
                                 was recorded under.

   Returns: the body shape for the evidence-aggregated event."
  [{:keys [existing verdict source-ref computed-at alignment-id]}]
  (let [tier (:tier verdict)
        v   (:verdict verdict)
        prior-tier-contributions (:tier-contributions existing {})
        prior-sources            (:source-refs existing #{})
        prior-decisions-count    (:dedup-decisions-count existing 0)
        prior-equivalence-hist   (:equivalence-history existing [])
        new-sources (cond-> prior-sources
                      source-ref (conj source-ref))
        new-tier-contribs (update prior-tier-contributions
                                  (or tier :unknown-tier)
                                  (fnil inc 0))
        new-decisions-count (inc prior-decisions-count)
        ;; Only :merge verdicts append equivalence history. Distinct
        ;; verdicts contribute to :tier-contributions but NOT to
        ;; equivalence-history (no equivalence event was recorded).
        new-equiv-hist (if (= :merge v)
                         (conj prior-equivalence-hist
                               (cond-> {:kind (:kind verdict)
                                        :recorded-at computed-at}
                                 alignment-id
                                 (assoc :alignment-ontology-id alignment-id)))
                         prior-equivalence-hist)
        ;; The score is computed from the NEW totals (the score IS
        ;; the after-state).
        score (evidence-score
               {:source-count (+ (count prior-sources)
                                 (if (and source-ref
                                          (not (contains? prior-sources source-ref)))
                                   1 0))
                :distinct-sources (count new-sources)
                :dedup-decisions new-decisions-count
                :equivalence-history (count new-equiv-hist)
                :axiom-presence (:axiom-presence existing 0)
                :edge-confidence-distribution
                (:edge-confidence-distribution existing {})
                :age-of-concept-days (:age-of-concept-days existing 0)
                :contradictions-count (count (:contradictions existing []))})]
    {:tier-contributions new-tier-contribs
     :source-refs new-sources
     :sources-count (count new-sources)
     :dedup-decisions-count new-decisions-count
     :equivalence-history new-equiv-hist
     :evidence-score score
     :last-reinforced-at computed-at
     :computed-at computed-at}))

(defn ledger-record
  "Shape a projection entry as the public ledger record `get-concept-evidence`
   returns. Pure shaping with defaulted-zeros for the unknown-URI case.

   The defaults are NOT nils — callers should be able to use `(:foo
   (get-concept-evidence ...))` without nil-guarding. The slice's
   adversarial test explicitly checks that an unknown URI returns a
   usable zero record, not nil."
  [entry]
  {:evidence-score        (get entry :evidence-score 0.0)
   :tier-contributions    (get entry :tier-contributions {})
   :sources-count         (get entry :sources-count 0)
   :source-refs           (vec (get entry :source-refs []))
   :dedup-decisions-count (get entry :dedup-decisions-count 0)
   :equivalence-history   (vec (get entry :equivalence-history []))
   :contradictions        (vec (get entry :contradictions []))
   :last-reinforced-at    (get entry :last-reinforced-at)
   :computed-at           (get entry :computed-at)})
