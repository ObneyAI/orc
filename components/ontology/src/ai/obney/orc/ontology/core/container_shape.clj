(ns ai.obney.orc.ontology.core.container-shape
  "MT-1 — deterministic, domain/format-agnostic structural shape classification of
   a source CONTAINER (a table/sheet). Reads ONLY structure — the header, a small
   REAL sample, and the row count — and names no column. Separates the MEANINGFUL
   shapes (entity / long-form / wide-stats) from the NOISE shapes (bridge /
   reference) so container selection (MT-2) keeps the meaningful containers and
   drops the noise, replacing the blind first-N-alphabetical take.

   Thresholds + the key/element/value role heuristics are grounded in the real
   O*NET signal separation measured in the MT-1 prototype:
     - entity keys read distinct-ratio ~1.0 (Occupation Data SOC = 1.0);
     - long-form/bridge keys read <=0.8 (Knowledge SOC = 0.03) — a wide gap;
     - a NUMERIC near-unique column (Standard Error, Data Value) is a MEASURE, not
       a key — so key detection excludes numeric columns;
     - a bridge (Abilities to Work Activities) has no unique key AND no measure;
     - a tiny table (Scales Reference = 32 rows) is a lookup/reference dictionary.
   An entity-SHAPED reference (Content Model Reference: unique id + name + desc,
   not tiny) is structurally indistinguishable from a real entity, so it is KEPT
   as :entity and left for MT-2's relevance rank to drop — NOT dropped here."
  (:require [clojure.string :as str]))

(def ^:private unique-key-threshold
  "A NON-numeric column whose sampled distinct-ratio is at/above this is a unique
   identifier (one row per entity). The entity/non-entity gap is wide, so 0.9 is a
   safe separator." 0.9)

(def ^:private numeric-threshold
  "A column at/above this numeric fraction is a numeric measure/value column." 0.9)

(def ^:private tiny-row-count
  "At/below this row count a container is a lookup/reference dictionary, not a real
   entity set." 50)

(def ^:private wide-stats-min-measures
  "An entity-shaped container carrying at least this many numeric measure columns is
   wide-stats (many measures for one subject) rather than a plain entity." 5)

(defn- numeric-like? [v]
  (or (number? v)
      (and (string? v) (re-matches #"\s*-?\d+(?:\.\d+)?\s*" v) true)))

(defn- column-stats
  "Per-column {:col :distinct-ratio :numeric-frac} over the sample (distinct-ratio
   is distinct-nonnil / sample-size; numeric-frac is over non-nil values)."
  [header sample]
  (let [n (max 1 (count sample))]
    (mapv (fn [h]
            (let [vals (remove nil? (map #(get % h) sample))
                  c (count vals)]
              {:col h
               :distinct-ratio (/ (count (distinct vals)) (double n))
               :numeric-frac (if (pos? c)
                               (/ (count (filter numeric-like? vals)) (double c))
                               0.0)}))
          header)))

(defn classify-container-shape
  "Classify a container from its structural facts
   `{:header [col …] :sample [{col val} …] :row-count n}`. Returns
   `{:shape :entity|:long-form|:wide-stats|:bridge|:reference
     :keep? boolean
     :roles {…}          ;; :key for entity/wide-stats; :key/:element/:value for long-form
     :stats [col-stat …]}`. Pure + total — never throws; an empty/degenerate
   container classifies as :bridge (nothing to extract), dropped."
  [{:keys [header sample row-count]}]
  (let [stats (column-stats header sample)
        ;; a KEY is a NON-numeric near-unique column (a numeric near-unique column
        ;; — a measure — is NOT a key; the MT-1 prototype nuance).
        key-cols (filter #(and (>= (:distinct-ratio %) unique-key-threshold)
                               (< (:numeric-frac %) numeric-threshold))
                         stats)
        measure-cols (filter #(>= (:numeric-frac %) numeric-threshold) stats)
        non-numeric (remove #(>= (:numeric-frac %) numeric-threshold) stats)
        has-key? (seq key-cols)
        has-measure? (seq measure-cols)
        tiny? (and row-count (<= row-count tiny-row-count))
        result
        (cond
          ;; entity-shaped: a unique non-numeric key present
          has-key?
          (cond
            tiny? {:shape :reference :keep? false}
            (>= (count measure-cols) wide-stats-min-measures)
            {:shape :wide-stats :keep? true :roles {:key (:col (first key-cols))}}
            :else {:shape :entity :keep? true :roles {:key (:col (first key-cols))}})
          ;; no unique key but a numeric value → long-form (repeating key × element → value)
          has-measure?
          (let [ranked (sort-by :distinct-ratio non-numeric)]
            {:shape :long-form :keep? true
             :roles {:key (:col (first ranked))       ; repeats most → the entity key
                     :element (:col (last ranked))    ; varies most → the element/label
                     :value (:col (first measure-cols))}})
          ;; no key, no measure → a pairing/bridge (an EDGE, not an entity table)
          :else {:shape :bridge :keep? false})]
    (assoc result :stats stats)))
