(ns ai.obney.orc.predict-rlm-redaction-tools.interface
  "Deterministic redaction transform for the document_redaction benchmark port.

   apply-redactions is the model-callable :code node function that takes
   per-page text + per-page redaction targets and produces redacted text
   + a RedactionResult-equivalent. The behavior tree the model emits
   identifies targets via vision LLM (per page) and then invokes this
   pure function to apply them.

   No I/O, no LLM. Pure substring replacement."
  (:require [clojure.string :as str]))

(def ^:private redaction-block
  "Character block used to replace redacted text. Matches the visual
   convention of black-bar redactions in document workflows."
  "█")

(defn- replace-target-in-text
  "Replace all occurrences of target-text in page-text with a redaction
   block of equal length. Returns [new-page-text replaced?] — replaced?
   is true iff at least one occurrence was found and replaced."
  [page-text target-text]
  (if (and (string? target-text)
           (seq target-text)
           (str/includes? page-text target-text))
    (let [replacement (apply str (repeat (count target-text) redaction-block))]
      [(str/replace page-text target-text replacement) true])
    [page-text false]))

(defn apply-redactions
  "Apply a list of redaction targets to per-page text and return a
   RedactionResult-equivalent map.

   :inputs map MUST contain:
     :page-texts — vector of per-page text strings (page i = index i)
     :targets    — vector of {:page :text :category :reason} maps

   Returns:
     {:redacted-text-per-page [string ...]
      :total-redactions <int>
      :page-summaries [{:page :redaction_count :categories [...]}]
      :targets-applied [target ...]      ;; subset of input :targets whose text was found
      :targets-missing [target ...]}     ;; subset whose text could not be located"
  [{:keys [inputs]}]
  (let [;; Shape-detect the two expected inputs so the model can name its
        ;; :reads keys freely. The page-texts value is a vector of strings;
        ;; the targets value is a vector of maps. If both inputs match a
        ;; shape, the first one bound to that shape wins.
        vals-by-shape (group-by (fn [v]
                                  (cond
                                    (and (vector? v) (every? string? v)) :strings
                                    (and (vector? v) (every? map? v)) :maps
                                    :else :other))
                                (vals inputs))
        page-texts (vec (or (first (get vals-by-shape :strings)) []))
        targets (vec (or (first (get vals-by-shape :maps)) []))
        ;; Reduce over targets, accumulating redactions per page and
        ;; classifying each target as applied or missing.
        init-state {:texts page-texts
                    :applied []
                    :missing []
                    :per-page-counts {}
                    :per-page-categories {}}
        final-state (reduce
                      (fn [state target]
                        (let [page-idx (:page target)
                              target-text (:text target)
                              current-text (get-in state [:texts page-idx])]
                          (if (nil? current-text)
                            ;; page out of range
                            (update state :missing conj target)
                            (let [[new-text replaced?] (replace-target-in-text
                                                        current-text target-text)]
                              (if replaced?
                                (-> state
                                    (assoc-in [:texts page-idx] new-text)
                                    (update :applied conj target)
                                    (update-in [:per-page-counts page-idx] (fnil inc 0))
                                    (update-in [:per-page-categories page-idx]
                                               (fnil conj #{})
                                               (:category target)))
                                (update state :missing conj target))))))
                      init-state
                      targets)
        applied (:applied final-state)
        page-summaries (vec
                         (for [p (range (count page-texts))]
                           {:page p
                            :redaction_count (get-in final-state [:per-page-counts p] 0)
                            :categories (vec (sort (get-in final-state [:per-page-categories p] #{})))}))]
    {:redacted-text-per-page (:texts final-state)
     :total-redactions (count applied)
     :page-summaries page-summaries
     :targets-applied applied
     :targets-missing (:missing final-state)}))

(def available-code-nodes
  "Plain-text catalog of code-node fns. Surfaced via the task's
   :available-code-nodes field. Deliberately minimal — gpt-5.4 returned
   :code nil when this text contained embedded Clojure-DSL examples with
   escaped quotes; the simplified plain-text form below is what the model
   actually parses cleanly."
  (str
   "Pre-built deterministic functions available for :code nodes:\n\n"
   "Function: ai.obney.orc.predict-rlm-redaction-tools.interface/apply-redactions\n"
   "Reads: a per-page text vector AND a targets vector\n"
   "Writes: :redacted-text-per-page :total-redactions :page-summaries :targets-applied :targets-missing\n"
   "Behavior: pure substring redaction. For each target map "
   "{:page <0-indexed> :text <verbatim substring> :category <string> :reason <string>}, "
   "replace the target's :text in the appropriate page's text with a block of █ characters. "
   "Returns the per-page redacted text vector, the total count of redactions actually applied, "
   "per-page summaries with redaction count and category list, the subset of input targets "
   "applied, and the subset whose text could not be located in the indicated page.\n\n"
   "Use this fn for the apply step of redaction workflows rather than asking an LLM to "
   "splice strings; LLM string-splicing is hallucination-prone, this is exact."))
