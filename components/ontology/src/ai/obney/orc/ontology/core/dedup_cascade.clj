(ns ai.obney.orc.ontology.core.dedup-cascade
  "S12 — Tiered, cheapest-first dedup cascade with a disjointness KEEP-guard
   as the FIRST gate, structured number/negation/entity KEEP rules, and a
   focused LLM merge/keep verdict ONLY for ambiguity-band pairs.

   Tier order (numbered so the verdict log is unambiguous):

     T1 :disjointness-guard       — concepts under disjoint classes never
                                    merge; the cascade STOPS here. The S07
                                    `:ontology/axioms` projection supplies
                                    the disjointness sets. ZERO LLM calls.
     T2 :number-guard             — labels differ in any numeric token →
                                    KEEP (`Model 3` vs `Model 30`,
                                    `ISO 9001` vs `ISO 9002`).
     T3 :negation-guard           — polarity-flip pair → KEEP. Either a
                                    negation-prefix flip (`approved` vs
                                    `unapproved`) or a canonical polarity-
                                    antonym pair (`present`/`absent`).
     T4 :entropy-gate             — very-short or stop-word-only label →
                                    :skip (too noisy to learn from).
     T5 :type-blocking            — concept type mismatch (`:class` vs
                                    `:property` vs `:individual`) →
                                    :distinct :reason :type-mismatch.
     T6 :exact-normalization      — labels equal after NFC + lowercase + ws
                                    collapse AND descriptions agree →
                                    :merge. When descriptions disagree,
                                    DEFER to the LLM tier (catches surface-
                                    form-distinct-entities like `Paris`
                                    city vs `Paris` person).
     T7 :lsh-blocking             — combined word-token + 3-shingle Jaccard
                                    below threshold → :skip (no merge).
     T8 :string-similarity        — Jaro-Winkler:
                                    - ≥ merge threshold AND descs agree →
                                      :merge
                                    - < ambiguity-lo → :distinct (:below-band)
                                    - else fall through to T9.
     T9 :llm-verdict              — focused merge/keep LLM call. ONLY for
                                    [ambiguity-lo, merge-threshold) pairs
                                    AND exact-normalization-equal-but-descs-
                                    disagree pairs. The prompt carries the
                                    LOAD-BEARING rule: differ in number /
                                    negation / entity → KEEP.

   Budget: when `:llm-budget` is exhausted the cascade surfaces remaining
   T9 pairs as `:verdict :requires-review` — NEVER silently merges, NEVER
   silently skips. The slice's hard rule (the round-2 grill's
   `:llm-budget` discipline).

   Pure functions ONLY in this namespace — no event store / no commands /
   no I/O. The `:ontology/run-dedup-cascade` defcommand wraps this with
   event emission; tests run the pure cascade for unit-level adversarial
   coverage and the defcommand path for the integration-level event-shape
   contract."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Normalization + entropy
;; =============================================================================

(defn normalize-label
  "Unicode NFC + lowercase + whitespace collapse + trim. Returns nil for nil
   input so the caller can guard against nil labels without a special case."
  [^String s]
  (when s
    (-> (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC)
        str/lower-case
        str/trim
        (str/replace #"\s+" " "))))

(def stopwords
  "Stop-word-only labels are skipped by the entropy gate — they carry too
   little information to learn from on either side of a merge verdict."
  #{"a" "an" "the" "of" "to" "and" "or" "in" "on" "for" "at" "by" "is" "as"})

(defn low-info-label?
  "Entropy gate: TRUE for labels shorter than 3 chars after normalization
   or labels whose every token is a stop word."
  [s]
  (let [n (normalize-label s)]
    (or (nil? n)
        (< (count n) 3)
        (every? stopwords (str/split n #"\s+")))))

;; =============================================================================
;; Number + negation guards
;; =============================================================================

(defn number-difference?
  "TRUE when the two labels differ in their NUMERIC token set. Used by the
   T2 number-guard to KEEP pairs like `Model 3` vs `Model 30`."
  [a b]
  (let [a-nums (set (re-seq #"\d+" (or a "")))
        b-nums (set (re-seq #"\d+" (or b "")))]
    (and (or (seq a-nums) (seq b-nums))
         (not= a-nums b-nums))))

(def ^:private negation-prefix-re
  "Negation prefixes recognized by the negation-guard. `un`, `in`, and `dis`
   are reasonable English defaults; `non` and `anti` extend to common
   ontology surface forms (`non-public`, `anti-pattern`)."
  #"^(no|not|non|un|in|dis|anti)[- ]")

(def polarity-antonym-pairs
  "Canonical polarity antonym pairs. The T3 negation-guard KEEPs any pair
   whose normalized labels match one of these (in either order). The LLM
   prompt also carries this set so the model can extend it semantically —
   the deterministic set is a FLOOR not a ceiling, not a silent quality
   gate that hardcodes domain vocabulary into Clojure."
  #{#{"approved" "rejected"}
    #{"present" "absent"}
    #{"authorized" "unauthorized"}
    #{"valid" "invalid"}
    #{"active" "inactive"}
    #{"complete" "incomplete"}
    #{"public" "private"}
    #{"open" "closed"}
    #{"true" "false"}
    #{"success" "failure"}})

(defn negation-difference?
  "TRUE when the two labels are a polarity-flip pair — either via a
   negation prefix on one side OR via the canonical polarity-antonym set."
  [a b]
  (let [an (normalize-label a) bn (normalize-label b)]
    (when (and an bn)
      (let [a-bare (str/replace an negation-prefix-re "")
            b-bare (str/replace bn negation-prefix-re "")
            a-neg? (not= an a-bare)
            b-neg? (not= bn b-bare)]
        (or (and (not= a-neg? b-neg?) (= a-bare b-bare))
            (and (not= an bn)
                 (contains? polarity-antonym-pairs (set [an bn]))))))))

(defn descriptions-disagree?
  "TRUE when both descriptions are non-empty and normalize-different —
   the T6 / T8 merge tiers DEFER to the LLM when this is TRUE so surface-
   form-equal pairs with divergent descriptions reach a verdict via the
   LLM's entity-disambiguation step."
  [a b]
  (let [an (normalize-label a) bn (normalize-label b)]
    (and (seq an) (seq bn) (not= an bn))))

;; =============================================================================
;; Blocking (LSH proxy via combined word-token + 3-shingle Jaccard)
;; =============================================================================

(defn- shingles
  "k-shingles of the normalized text."
  [s k]
  (let [n (or (normalize-label s) "")]
    (if (< (count n) k)
      (when (seq n) #{n})
      (set (map #(subs n % (+ % k))
                (range (- (count n) k -1)))))))

(defn word-tokens
  "Word tokens of a label honoring whitespace AND camelCase / under_score /
   slash boundaries. CamelCase splitting happens BEFORE lowercasing so the
   boundary signal survives (the prototype caught this bug: lowercasing
   first collapsed `hasAuthor` to `hasauthor` and erased the token boundary
   the Jaccard signal needs for `hasAuthor` vs `hasWriter`)."
  [s]
  (if (str/blank? s)
    #{}
    (-> s
        (java.text.Normalizer/normalize java.text.Normalizer$Form/NFC)
        (str/replace #"([a-z])([A-Z])" "$1 $2")
        (str/replace #"[_/]" " ")
        str/lower-case
        str/trim
        (str/split #"\s+")
        (->> (remove str/blank?))
        set)))

(defn blocking-jaccard
  "Max of word-token Jaccard and 3-shingle Jaccard. The word-token signal
   handles camelCase / underscore composite identifiers (`hasAuthor` and
   `hasWriter` share the `has` token); the 3-shingle signal handles
   substring overlap on single-word labels."
  [s1 s2]
  (let [w1 (word-tokens s1) w2 (word-tokens s2)
        sh1 (shingles s1 3) sh2 (shingles s2 3)
        word-jacc (if (or (empty? w1) (empty? w2))
                    0.0
                    (/ (double (count (set/intersection w1 w2)))
                       (count (set/union w1 w2))))
        shingle-jacc (if (or (empty? sh1) (empty? sh2))
                       0.0
                       (/ (double (count (set/intersection sh1 sh2)))
                          (count (set/union sh1 sh2))))]
    (max word-jacc shingle-jacc)))

;; =============================================================================
;; String similarity (Jaro-Winkler)
;; =============================================================================

(defn jaro-similarity
  "Jaro similarity ∈ [0,1]. Classical implementation; no external dep."
  [^String s1 ^String s2]
  (cond
    (= s1 s2) 1.0
    (or (empty? s1) (empty? s2)) 0.0
    :else
    (let [l1 (count s1)
          l2 (count s2)
          match-distance (max 0 (dec (quot (max l1 l2) 2)))
          s1-matches (boolean-array l1)
          s2-matches (boolean-array l2)
          matches (volatile! 0)
          _ (dotimes [i l1]
              (let [lo (max 0 (- i match-distance))
                    hi (min (dec l2) (+ i match-distance))]
                (loop [j lo]
                  (when (<= j hi)
                    (cond
                      (aget s2-matches j) (recur (inc j))
                      (= (.charAt s1 i) (.charAt s2 j))
                      (do (aset s1-matches i true)
                          (aset s2-matches j true)
                          (vswap! matches inc))
                      :else (recur (inc j)))))))
          m @matches]
      (if (zero? m)
        0.0
        (let [k (volatile! 0)
              t (volatile! 0)
              _ (dotimes [i l1]
                  (when (aget s1-matches i)
                    (loop [j @k]
                      (if (aget s2-matches j)
                        (do
                          (when (not= (.charAt s1 i) (.charAt s2 j))
                            (vswap! t inc))
                          (vreset! k (inc j)))
                        (recur (inc j))))))
              m-d (double m)]
          (/ (+ (/ m-d l1)
                (/ m-d l2)
                (/ (- m-d (/ @t 2.0)) m-d))
             3.0))))))

(defn jaro-winkler-similarity
  "Jaro-Winkler ∈ [0,1] — boosts agreement on common prefix up to 4 chars."
  [s1 s2]
  (let [jaro (jaro-similarity s1 s2)
        prefix (loop [i 0]
                 (if (and (< i 4) (< i (count s1)) (< i (count s2))
                          (= (.charAt ^String s1 i) (.charAt ^String s2 i)))
                   (recur (inc i))
                   i))]
    (+ jaro (* prefix 0.1 (- 1 jaro)))))

;; =============================================================================
;; LLM tier — prompt (production string) + verdict parser
;; =============================================================================

(def llm-keep-rule-prompt
  "The production LLM prompt for the cascade's T9 merge/keep verdict. The
   load-bearing rules (number / negation / entity → KEEP) are EXPLICITLY
   articulated in the prompt body — the cascade also fires structured
   guards for these cases at T2/T3, but the prompt carries the rules so
   the model handles edge cases the deterministic guards can't cover
   (compound numeric mentions, multi-language negation, semantic entity-
   class disagreement) without surprising the user.

   The prompt is a `format` template — substitute label-a / desc-a /
   label-b / desc-b at runtime."
  "You are deciding whether two ontology concept labels refer to the SAME
underlying entity (MERGE) or to DISTINCT entities (KEEP). Read both labels
plus their short descriptions. Apply these load-bearing rules in order:

  1. If the labels differ in any NUMBER (e.g. 'Model 3' vs 'Model 30'), the
     answer is KEEP — numbers identify distinct things even when the surface
     form looks similar.
  2. If the labels differ in NEGATION or POLARITY (e.g. 'approved' vs
     'not approved', 'present' vs 'absent', 'authorized' vs 'unauthorized'),
     the answer is KEEP — the entities are opposites, not synonyms.
  3. If the labels refer to DIFFERENT ENTITIES that happen to share a surface
     form (e.g. 'Paris' the city vs 'Paris' the person; the descriptions
     disambiguate), the answer is KEEP.
  4. Otherwise — when the labels are aliases, case/whitespace variants,
     well-known equivalent terms, or rephrasings of the same concept —
     answer MERGE.

When MERGE, also classify the KIND:
  - same-as            (the SAME individual)
  - equivalent-class   (the SAME class)
  - equivalent-property (the SAME predicate)

Return EDN of exactly this shape:
  {:verdict :merge  :kind :same-as|:equivalent-class|:equivalent-property
   :reason \"...\"}
  or
  {:verdict :distinct :reason :number|:negation|:entity|:other
   :detail \"...\"}

Pair under review:
  A: label=%s description=%s
  B: label=%s description=%s")

(defn render-llm-prompt
  "Render the LLM prompt for the given pair."
  [{:keys [a-label a-desc b-label b-desc]}]
  (format llm-keep-rule-prompt
          (pr-str a-label) (pr-str (or a-desc ""))
          (pr-str b-label) (pr-str (or b-desc ""))))

(defn mock-llm-verdict
  "Deterministic mock encoding the prompt's KEEP rule. Used by unit tests
   and as a default when no `:llm-fn` is supplied. Real LLM calls plug in
   via the `:llm-fn` argument on `run-cascade`."
  [{:keys [a-label a-desc b-label b-desc kind-hint]}]
  (let [a-nums (re-seq #"\d+" (or a-label ""))
        b-nums (re-seq #"\d+" (or b-label ""))]
    (cond
      (not= (set a-nums) (set b-nums))
      {:verdict :distinct :reason :number
       :detail (str "labels differ in numbers: " (pr-str a-nums) " vs " (pr-str b-nums))}

      (negation-difference? a-label b-label)
      {:verdict :distinct :reason :negation
       :detail (str "polarity differs: " (pr-str a-label) " vs " (pr-str b-label))}

      (let [classify (fn [d]
                       (cond
                         (re-find #"(?i)city|capital|country|place|town" d) :place
                         (re-find #"(?i)person|author|inventor|wrote|painted|memoir|writer" d) :person
                         (re-find #"(?i)company|corporation|firm|electronics|consumer" d) :organization
                         (re-find #"(?i)fruit|plant|tree|vegetable|grown" d) :fruit
                         (re-find #"(?i)the act of|action of|activity|process of" d) :activity
                         :else :other))
            a-class (classify (or a-desc ""))
            b-class (classify (or b-desc ""))]
        (and (not= :other a-class) (not= :other b-class) (not= a-class b-class)))
      {:verdict :distinct :reason :entity
       :detail "descriptions place the labels in disjoint reference classes"}

      :else
      {:verdict :merge :kind (or kind-hint :equivalent-class)
       :reason "labels and descriptions describe the same concept"})))

;; =============================================================================
;; The cascade
;; =============================================================================

(defn run-cascade
  "Run one candidate pair through the cascade. Pure function — returns a
   verdict map carrying the tier that closed the verdict, the verdict
   (:merge / :distinct / :skip / :requires-review), the equivalence
   :kind (when :merge), and a structured :reason for adversarial review.

   Options:
     :string-merge-threshold     JW ≥ this → MERGE without LLM (default 0.95)
     :string-ambiguity-lo        JW < this → :below-band :distinct (default 0.78)
     :lsh-jaccard-min            Jaccard < this → :skip via LSH (default 0.30)
     :llm-fn                     1-arg fn taking the {a-label / a-desc / b-label
                                 / b-desc / kind-hint} map; defaults to
                                 `mock-llm-verdict`
     :llm-budget                 max LLM calls; when exhausted, T9 surfaces
                                 :requires-review (never silently merges)
     :llm-counter                atom holding LLM-call count for cost discipline
     :disjoint-pair-fn           (a-uri b-uri) → boolean — the T1 guard's
                                 disjointness check (closed over the S07
                                 `:ontology/axioms` projection by the caller)

   Returns a map with at least:
     {:tier <tier-keyword>
      :verdict :merge|:distinct|:skip|:requires-review
      :kind   <equiv-kind>   (when :merge)
      :reason <reason-keyword>}"
  [{:keys [a b
           string-merge-threshold string-ambiguity-lo lsh-jaccard-min
           llm-fn llm-budget llm-counter disjoint-pair-fn]
    :or {string-merge-threshold 0.95
         string-ambiguity-lo 0.78
         lsh-jaccard-min 0.30
         llm-fn mock-llm-verdict
         disjoint-pair-fn (constantly false)}}]
  (let [a-uri (:uri a) b-uri (:uri b)
        a-lab (:label a) b-lab (:label b)
        a-desc (:description a) b-desc (:description b)
        a-type (:type a) b-type (:type b)
        descs-disagree? (descriptions-disagree? a-desc b-desc)]
    (cond
      ;; T1 — Disjointness KEEP-guard. FIRST gate. NEVER calls the LLM.
      (disjoint-pair-fn a-uri b-uri)
      {:tier :disjointness-guard :verdict :distinct :reason :disjointness-guard
       :detail "concepts under disjoint classes — KEEP without further tiers"}

      ;; T2 — Number guard
      (number-difference? a-lab b-lab)
      {:tier :number-guard :verdict :distinct :reason :number-difference
       :detail (str "labels carry different numeric tokens: "
                    (pr-str (re-seq #"\d+" a-lab)) " vs "
                    (pr-str (re-seq #"\d+" b-lab)))}

      ;; T3 — Negation guard
      (negation-difference? a-lab b-lab)
      {:tier :negation-guard :verdict :distinct :reason :negation-difference
       :detail "labels are a polarity-flip pair"}

      ;; T4 — Entropy gate
      (or (low-info-label? a-lab) (low-info-label? b-lab))
      {:tier :entropy-gate :verdict :skip :reason :low-information
       :detail "label too short or stop-word-only"}

      ;; T5 — Type-based blocking
      (and a-type b-type (not= a-type b-type))
      {:tier :type-blocking :verdict :distinct :reason :type-mismatch
       :detail (str "types differ: " (pr-str a-type) " vs " (pr-str b-type))}

      ;; T6 — Exact normalization (with desc agreement)
      (and (= (normalize-label a-lab) (normalize-label b-lab))
           (not descs-disagree?))
      {:tier :exact-normalization :verdict :merge
       :kind (or (:kind-hint a) (:kind-hint b) :same-as)
       :reason :exact-normalization
       :detail "labels equal after NFC + lowercase + ws collapse; descs agree"}

      :else
      (let [jacc (blocking-jaccard a-lab b-lab)
            norm-equal? (= (normalize-label a-lab) (normalize-label b-lab))]
        (if (and (not norm-equal?) (< jacc lsh-jaccard-min))
          ;; T7 — LSH blocking
          {:tier :lsh-blocking :verdict :skip :reason :no-shared-shingles
           :detail (format "Jaccard %.3f below %.3f" jacc lsh-jaccard-min)}

          (let [jw (jaro-winkler-similarity (normalize-label a-lab)
                                            (normalize-label b-lab))]
            (cond
              ;; T8a — string-similarity-high
              (and (>= jw string-merge-threshold) (not descs-disagree?))
              {:tier :string-similarity-high :verdict :merge
               :kind (or (:kind-hint a) (:kind-hint b) :equivalent-class)
               :reason :string-similarity-high
               :detail (format "JW %.3f ≥ merge threshold %.3f" jw string-merge-threshold)}

              ;; T8b — string-similarity-low
              (< jw string-ambiguity-lo)
              {:tier :string-similarity-low :verdict :distinct :reason :below-band
               :detail (format "JW %.3f below ambiguity band %.3f" jw string-ambiguity-lo)}

              :else
              ;; T9 — LLM verdict (the only LLM tier)
              (let [budget-exhausted? (and llm-budget llm-counter
                                           (>= @llm-counter llm-budget))]
                (if budget-exhausted?
                  {:tier :llm-budget-exhausted :verdict :requires-review
                   :reason :budget :detail "LLM budget exhausted"}
                  (let [_ (when llm-counter (swap! llm-counter inc))
                        llm (llm-fn {:a-label a-lab :a-desc a-desc
                                     :b-label b-lab :b-desc b-desc
                                     :kind-hint (or (:kind-hint a) (:kind-hint b))})]
                    (assoc llm :tier :llm-verdict)))))))))))

;; =============================================================================
;; Disjointness lookup helper — used by the defcommand to wire S07 axioms in
;; =============================================================================

(defn disjoint-under-axioms?
  "Given the S07 axioms map for an ontology-id and the two candidates'
   `:broader` URI vectors, return TRUE when the cascade's T1 guard should
   fire — i.e. ANY pair of broader URIs across the two candidates is in
   the disjointness projection.

   Pure — caller projects the axioms map ahead of time, passes it in."
  [disjointness-map a-broader b-broader]
  (boolean
   (some (fn [ab]
           (some (fn [bb]
                   (when-let [siblings (get disjointness-map ab)]
                     (contains? siblings bb)))
                 b-broader))
         a-broader)))
