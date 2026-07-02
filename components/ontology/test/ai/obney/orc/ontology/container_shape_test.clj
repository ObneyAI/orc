(ns ai.obney.orc.ontology.container-shape-test
  "MT-1 — deterministic container shape-classification. Fixtures are the REAL
   structural signals captured from O*NET db_30_1_excel (bridge / long-form /
   entity / reference), so the unit tests guard the exact separation the
   /prototype measured. The LIVE proof on the real source is the /inspect-orc."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-shape :as cs]))

;; Real O*NET headers + representative sample rows (shape-faithful — the values
;; mirror the real distinct/numeric profile the probe measured; domain-agnostic
;; assertions below name no O*NET column).

(defn- rep
  "Build n sample rows from per-column generators (idx -> value)."
  [header n gens]
  (mapv (fn [i] (into {} (map (fn [h] [h ((gens h) i)]) header))) (range n)))

(deftest bridge-two-code-pairs-no-measure-test
  (testing "a junction/bridge container (two entity-pair code columns, NO numeric
            measure, NO unique key) → :bridge, dropped"
    ;; Abilities to Work Activities: 4 cols, no numeric, no unique key.
    (let [header ["Abilities Element ID" "Abilities Element Name"
                  "Work Activities Element ID" "Work Activities Element Name"]
          sample (rep header 30
                      {"Abilities Element ID"        #(str "1.A." (mod % 3))       ; ~0.1 distinct
                       "Abilities Element Name"      #(str "ability-" (mod % 3))
                       ;; real "Work Activities" side capped ~0.8 distinct — NO unique key
                       "Work Activities Element ID"  #(str "4.A." (mod % 24))
                       "Work Activities Element Name" #(str "activity-" (mod % 24))})
          out (cs/classify-container-shape {:header header :sample sample :row-count 140})]
      (is (= :bridge (:shape out)))
      (is (false? (:keep? out)) "a bridge is noise — dropped"))))

(deftest long-form-repeating-key-plus-measure-test
  (testing "a long-form container (repeating non-unique key + element/label column
            + numeric value column) → :long-form, kept, with key/element/value roles"
    ;; Knowledge: key repeats (many rows per occupation), Element Name mid-card,
    ;; Data Value numeric+near-unique. A numeric near-unique col must NOT be a key.
    (let [header ["O*NET-SOC Code" "Element Name" "Scale ID" "Data Value" "Standard Error"]
          sample (rep header 30
                      {"O*NET-SOC Code" (fn [_] "11-1011.00")           ; one occ per window (near-constant key)
                       "Element Name"   #(str "knowledge-" (mod % 15))
                       "Scale ID"       #(if (even? %) "IM" "LV")
                       "Data Value"     #(str (+ 1.0 (* 0.13 %)))       ; numeric, ~unique
                       "Standard Error" #(str (* 0.01 %))})             ; numeric, ~unique
          out (cs/classify-container-shape {:header header :sample sample :row-count 59005})]
      (is (= :long-form (:shape out)))
      (is (true? (:keep? out)))
      (is (= "O*NET-SOC Code" (get-in out [:roles :key]))
          "the repeating NON-numeric column is the entity key")
      (is (some? (get-in out [:roles :value]))
          "a numeric value column is identified for aggregation")
      (is (not= "Data Value" (get-in out [:roles :key]))
          "a numeric near-unique column is NEVER mistaken for the key"))))

(deftest entity-unique-key-plus-attributes-test
  (testing "an entity container (a unique NON-numeric key + attribute columns) →
            :entity, kept, with the key role"
    ;; Occupation Data: SOC unique per row, + title + description, no measure.
    (let [header ["O*NET-SOC Code" "Title" "Description"]
          sample (rep header 30
                      {"O*NET-SOC Code" #(str "11-" (format "%04d" %) ".00")  ; unique
                       "Title"          #(str "Occupation " %)
                       "Description"    #(str "Description of occupation " %)})
          out (cs/classify-container-shape {:header header :sample sample :row-count 1017})]
      (is (= :entity (:shape out)))
      (is (true? (:keep? out)))
      (is (= "O*NET-SOC Code" (get-in out [:roles :key]))))))

(deftest tiny-reference-dropped-structurally-test
  (testing "a tiny reference/lookup container (unique key but very few rows) →
            :reference, dropped structurally"
    ;; Scales Reference: 32 rows, Scale ID unique, numeric bounds.
    (let [header ["Scale ID" "Scale Name" "Minimum" "Maximum"]
          sample (rep header 30
                      {"Scale ID"   #(str "S" %)      ; unique
                       "Scale Name" #(str "scale-" %)
                       "Minimum"    #(str %)
                       "Maximum"    #(str (+ 5 %))})
          out (cs/classify-container-shape {:header header :sample sample :row-count 32})]
      (is (= :reference (:shape out)))
      (is (false? (:keep? out))))))

(deftest entity-shaped-reference-falls-through-to-relevance-test
  (testing "an entity-SHAPED reference (unique key + name + description, NOT tiny)
            is structurally indistinguishable from a real entity → kept as :entity
            so MT-2's relevance rank (not the structural classifier) decides it"
    ;; Content Model Reference: 631 rows, Element ID unique, name + description.
    (let [header ["Element ID" "Element Name" "Description"]
          sample (rep header 30
                      {"Element ID"   #(str "2.A." %)   ; unique
                       "Element Name" #(str "element-" %)
                       "Description"  #(str "definition " %)})
          out (cs/classify-container-shape {:header header :sample sample :row-count 631})]
      (is (= :entity (:shape out)) "structurally an entity — NOT dropped structurally")
      (is (true? (:keep? out)) "kept; relevance rank (MT-2) is what drops it, not shape"))))
