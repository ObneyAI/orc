(ns ai.obney.orc.mcp-sheet-builder.core.tool-relevance
  "Tool relevance scoring for large MCP servers (50+ tools).

   Phase 5 of Intent-Based Sheet Building.

   This module helps users navigate large tool sets by:
   - Scoring each tool's relevance to user intent
   - Suggesting top N tools for the workflow
   - Allowing user overrides (include/exclude specific tools)

   Key principle: This is ASSISTANCE, not restriction.
   - User sees ALL tools with relevance scores
   - System SUGGESTS which to use
   - User can override with :include-tools / :exclude-tools"
  (:require [ai.obney.orc.mcp-sheet-builder.core.intent-analyzer :as intent]
            [clojure.string :as str]
            [clojure.set :as set]))

;; ============================================================================
;; Tool Relevance Scoring
;; ============================================================================

(defn score-all-tools
  "Score each tool's relevance to user intent.

   Args:
     tools - Vector of analyzed MCP tools
     intent - Intent map from analyze-intent or infer-intent-from-keywords

   Returns vector of scored tools:
     [{:tool {...}
       :relevance 0.95
       :reason \"matches search capability; matches keywords\"}
      ...]

   Does NOT filter - returns ALL tools with their relevance scores."
  [tools user-intent]
  (->> tools
       (map (fn [tool]
              (let [score-result (intent/score-tool-relevance tool user-intent)]
                {:tool tool
                 :name (:name tool)
                 :relevance (:relevance score-result)
                 :reason (:reason score-result)})))
       (sort-by :relevance >)
       vec))

(defn categorize-tools
  "Categorize tools by relevance level.

   Returns:
     {:high-relevance [...]    ;; relevance >= 0.7
      :medium-relevance [...]  ;; relevance >= 0.4
      :low-relevance [...]}    ;; relevance < 0.4"
  [scored-tools]
  (let [grouped (group-by
                 (fn [{:keys [relevance]}]
                   (cond
                     (>= relevance 0.7) :high-relevance
                     (>= relevance 0.4) :medium-relevance
                     :else :low-relevance))
                 scored-tools)]
    {:high-relevance (vec (get grouped :high-relevance []))
     :medium-relevance (vec (get grouped :medium-relevance []))
     :low-relevance (vec (get grouped :low-relevance []))}))

;; ============================================================================
;; Tool Suggestion
;; ============================================================================

(defn suggest-tools
  "Suggest tools for a workflow based on intent, with user override support.

   This is ASSISTANCE, not restriction - shows all tools with scores.

   Args:
     tools - Vector of all available MCP tools
     user-intent - Intent map from analyze-intent
     opts - Options map:
       :max-tools - Maximum tools to select (default 10)
       :min-relevance - Minimum relevance threshold (default 0.3)
       :include-tools - Set/vector of tool names to force include
       :exclude-tools - Set/vector of tool names to force exclude
       :discover-related - If true, suggests tools user might not have thought of

   Returns:
     {:selected [...selected tools with scores...]
      :also-available [...other tools with scores (not selected)...]
      :forced-include [...tools forced in by user...]
      :forced-exclude [...tools forced out by user...]
      :selection-reasoning string}"
  ([tools user-intent]
   (suggest-tools tools user-intent {}))
  ([tools user-intent {:keys [max-tools min-relevance include-tools exclude-tools discover-related]
                       :or {max-tools 10
                            min-relevance 0.3
                            include-tools #{}
                            exclude-tools #{}}}]
   (let [
         include-set (set include-tools)
         exclude-set (set exclude-tools)

         scored-tools (score-all-tools tools user-intent)

         after-exclude (remove #(exclude-set (:name %)) scored-tools)

         forced-includes (filter #(include-set (:name %)) scored-tools)
         remaining-after-include (remove #(include-set (:name %)) after-exclude)

         slots-remaining (- max-tools (count forced-includes))

         auto-selected (->> remaining-after-include
                            (filter #(>= (:relevance %) min-relevance))
                            (take slots-remaining)
                            vec)

         selected (vec (concat forced-includes auto-selected))
         selected-names (set (map :name selected))

         also-available (vec (remove #(or (selected-names (:name %))
                                          (exclude-set (:name %)))
                                     scored-tools))

         excluded-tools (filter #(exclude-set (:name %)) scored-tools)

         reasoning (str "Selected " (count selected) " tools: "
                        (count forced-includes) " forced, "
                        (count auto-selected) " auto-selected with relevance >= "
                        min-relevance
                        (when (seq excluded-tools)
                          (str ". Excluded " (count excluded-tools) " by user request")))]

     {:selected selected
      :also-available also-available
      :forced-include (vec forced-includes)
      :forced-exclude (vec excluded-tools)
      :selection-reasoning reasoning
      :total-tools (count tools)
      :categorized (categorize-tools scored-tools)})))

;; ============================================================================
;; Discovery Features
;; ============================================================================

(defn find-complementary-tools
  "Find tools that complement the selected tools.

   Looks for tools that:
   - Have different capabilities than selected (fill gaps)
   - Have high capability overlap with selected (alternatives)
   - Are in the same domain"
  [selected-tools all-tools user-intent]
  (let [selected-names (set (map :name selected-tools))
        selected-capabilities (apply set/union (map #(or (:capabilities %) #{}) selected-tools))
        unselected (remove #(selected-names (:name %)) all-tools)]
    (->> unselected
         (map (fn [tool]
                (let [tool-caps (or (:capabilities tool) #{})
                      complement-score (/ (count (set/difference tool-caps selected-capabilities))
                                          (max 1 (count tool-caps)))
                      alternative-score (/ (count (set/intersection tool-caps selected-capabilities))
                                           (max 1 (count tool-caps)))
                      {:keys [relevance]} (intent/score-tool-relevance tool user-intent)]
                  {:tool tool
                   :name (:name tool)
                   :complement-score complement-score
                   :alternative-score alternative-score
                   :intent-relevance relevance
                   :combined-score (+ (* 0.4 complement-score)
                                      (* 0.3 alternative-score)
                                      (* 0.3 relevance))})))
         (sort-by :combined-score >)
         vec)))

(defn suggest-related-tools
  "Suggest tools the user might not have thought of.

   Returns tools that could be useful but weren't in the initial selection."
  [selected-tools all-tools user-intent {:keys [max-suggestions]
                                          :or {max-suggestions 3}}]
  (let [complementary (find-complementary-tools selected-tools all-tools user-intent)]
    {:complementary (->> complementary
                         (filter #(> (:complement-score %) 0.3))
                         (take max-suggestions)
                         vec)
     :alternatives (->> complementary
                        (filter #(> (:alternative-score %) 0.5))
                        (take max-suggestions)
                        vec)}))

;; ============================================================================
;; High-Level API
;; ============================================================================

(defn select-tools-for-workflow
  "High-level function to select tools for a workflow.

   Combines intent analysis with tool suggestion.

   Args:
     tools - Vector of all MCP tools
     user-description - String describing what user wants to accomplish
     opts - Options:
       :max-tools - Max tools to include
       :include-tools - Tools to force include
       :exclude-tools - Tools to force exclude
       :use-llm-intent? - If true, use LLM for intent analysis (slower but more accurate)

   Returns:
     {:intent {...}
      :suggestion {:selected [...] :also-available [...] ...}
      :related {:complementary [...] :alternatives [...]}}"
  ([tools user-description]
   (select-tools-for-workflow tools user-description {}))
  ([tools user-description {:keys [use-llm-intent? provider model] :as opts}]
   (let [
         user-intent (if use-llm-intent?
                       (intent/analyze-intent user-description tools
                                              {:provider (or provider :openrouter)
                                               :model (or model "google/gemini-2.5-flash")})
                       (intent/infer-intent-from-keywords user-description))

         suggestion (suggest-tools tools user-intent opts)

         selected-tools (mapv :tool (:selected suggestion))
         related (suggest-related-tools selected-tools tools user-intent opts)]

     {:intent user-intent
      :suggestion suggestion
      :related related})))

;; ============================================================================
;; Display Helpers
;; ============================================================================

(defn format-tool-suggestion
  "Format a tool suggestion for display."
  [{:keys [selected also-available forced-include forced-exclude selection-reasoning]}]
  (str "=== Tool Suggestion ===\n\n"
       selection-reasoning "\n\n"
       "SELECTED TOOLS:\n"
       (str/join "\n" (map (fn [{:keys [name relevance reason]}]
                             (format "  [%.2f] %s - %s" relevance name reason))
                           selected))
       (when (seq also-available)
         (str "\n\nALSO AVAILABLE:\n"
              (str/join "\n" (map (fn [{:keys [name relevance]}]
                                    (format "  [%.2f] %s" relevance name))
                                  (take 5 also-available)))
              (when (> (count also-available) 5)
                (format "\n  ... and %d more" (- (count also-available) 5)))))
       (when (seq forced-exclude)
         (str "\n\nEXCLUDED BY REQUEST:\n"
              (str/join "\n" (map #(str "  - " (:name %)) forced-exclude))))))

;; ============================================================================
;; REPL / Testing
;; ============================================================================
