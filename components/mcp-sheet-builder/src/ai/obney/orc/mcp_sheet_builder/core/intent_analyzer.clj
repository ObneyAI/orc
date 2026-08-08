(ns ai.obney.orc.mcp-sheet-builder.core.intent-analyzer
  "Analyze user intent to inform intelligent sheet building.

   Phase 2 of Intent-Based Sheet Building.

   This module uses an LLM to understand what the user wants to accomplish,
   producing structured intent data that informs:
   - Tool relevance scoring (which tools matter for this goal)
   - Pattern selection (which workflow pattern fits the intent)
   - Node instructions (goal-oriented, not generic)"
  (:require [ai.obney.orc.llm.interface :as llm]
            [clojure.string :as str]))

;; ============================================================================
;; Intent Analysis Module (for ORC LLM predict)
;; ============================================================================

(def intent-analysis-module
  "ORC LLM module for intent analysis.
   Uses proper Malli schemas - vectors, enums, etc."
  {:inputs [{:name :user-description
             :spec :string
             :description "What the user wants to do, in their own words"}
            {:name :available-tools
             :spec :string
             :description "List of available MCP tools and their descriptions"}]
   :outputs [{:name :primary-goal
              :spec :string
              :description "The main thing the user wants to accomplish - be specific"}
             {:name :secondary-goals
              :spec [:vector :string]
              :description "Additional related goals or sub-tasks"}
             {:name :needed-actions
              :spec [:vector [:enum "search" "retrieve" "transform" "generate" "validate" "create" "update" "delete"]]
              :description "Action types needed to accomplish the goal"}
             {:name :domain
              :spec :string
              :description "The domain or topic area (e.g., documentation, pricing, api-integration)"}
             {:name :keywords
              :spec [:vector :string]
              :description "Key terms to search for or focus on"}]
   :instructions "You are an intent analyzer. Given a user's description of what they want to accomplish and a list of available tools, analyze their intent. Be specific and actionable. Focus on what the user wants to achieve, not how to achieve it."})

;; ============================================================================
;; Intent Analysis Functions
;; ============================================================================

(defn- format-tools-for-prompt
  "Format tools into a string for the LLM prompt."
  [tools]
  (->> tools
       (map (fn [{:keys [name description capabilities]}]
              (str "- " name
                   (when description (str ": " description))
                   (when (seq capabilities)
                     ;; Use clojure.core/name to avoid shadowing by destructured 'name'
                     (str " [" (str/join ", " (map clojure.core/name capabilities)) "]")))))
       (str/join "\n")))

(defn- parse-csv-field
  "Parse a comma-separated field into a vector."
  [s]
  (if (or (nil? s) (str/blank? s))
    []
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- parse-actions
  "Parse actions string into valid action keywords."
  [actions-str]
  (let [valid-actions #{"search" "retrieve" "transform" "generate"
                        "validate" "create" "update" "delete"}]
    (->> (parse-csv-field actions-str)
         (map str/lower-case)
         (filter valid-actions)
         vec)))

(defn analyze-intent
  "Use LLM to understand what user wants to accomplish.

   Args:
     user-description - What the user wants to do, in their own words
     tools - Vector of analyzed MCP tools
     opts - Options map:
       :provider - ORC LLM provider (default :openrouter)
       :model - Model to use (default \"google/gemini-2.5-flash\")

   Returns:
     {:primary-goal \"...\"
      :secondary-goals [...]
      :needed-actions [\"search\" \"retrieve\" ...]
      :domain \"...\"
      :keywords [...]}"
  ([user-description tools]
   (analyze-intent user-description tools {}))
  ([user-description tools {:keys [provider model]
                            :or {provider :openrouter
                                 model "google/gemini-2.5-flash"}}]
   (let [tools-str (format-tools-for-prompt tools)
         ;; Input values
         inputs {:user-description user-description
                 :available-tools tools-str}
         ;; Options
         options {:with-metadata? true :validate? false :model model}
         ;; Call ORC LLM predict: (predict provider module inputs options)
         result (llm/predict provider intent-analysis-module inputs options)
         outputs (:outputs result)]
     ;; With proper schemas, outputs are already correctly typed (vectors, strings, etc.)
     {:primary-goal (:primary-goal outputs)
      :secondary-goals (or (:secondary-goals outputs) [])
      :needed-actions (or (:needed-actions outputs) [])
      :domain (:domain outputs)
      :keywords (or (:keywords outputs) [])
      :raw-result result})))

(defn- capability-to-action
  "Map a capability keyword to an action string."
  [capability]
  (case capability
    :search "search"
    :retrieval "retrieve"
    :transform "transform"
    :generate "generate"
    :validate "validate"
    :create "create"
    :update "update"
    :delete "delete"
    nil))

(defn score-tool-relevance
  "Score how relevant a tool is to the analyzed intent.

   Returns a score from 0.0 to 1.0 with reasoning."
  [tool intent]
  (let [{:keys [needed-actions domain keywords]} intent
        {:keys [name description capabilities]} tool

        ;; Score based on capability match
        tool-actions (set (keep capability-to-action capabilities))
        needed-actions-set (set needed-actions)
        action-overlap (count (clojure.set/intersection tool-actions needed-actions-set))
        action-score (if (empty? needed-actions-set)
                       0.5
                       (/ (double action-overlap) (count needed-actions-set)))

        ;; Score based on keyword match in name/description
        tool-text (str/lower-case (str name " " (or description "")))
        keyword-matches (count (filter #(str/includes? tool-text (str/lower-case %)) keywords))
        keyword-score (if (empty? keywords)
                        0.5
                        (min 1.0 (/ (double keyword-matches) (max 1 (/ (count keywords) 2)))))

        ;; Score based on domain match
        domain-score (if (and domain (str/includes? tool-text (str/lower-case domain)))
                       1.0
                       0.3)

        ;; Combined score (weighted)
        combined-score (+ (* 0.4 action-score)
                          (* 0.4 keyword-score)
                          (* 0.2 domain-score))

        ;; Build reasoning
        reasons (cond-> []
                  (pos? action-overlap)
                  (conj (str "matches actions: " (str/join ", " (clojure.set/intersection tool-actions needed-actions-set))))

                  (pos? keyword-matches)
                  (conj (str "matches keywords"))

                  (> domain-score 0.3)
                  (conj "matches domain"))]

    {:tool-name name
     :relevance combined-score
     :reason (if (seq reasons)
               (str/join "; " reasons)
               "no direct match")}))

(defn rank-tools-by-relevance
  "Rank all tools by their relevance to the intent.

   Returns tools sorted by relevance score (highest first)."
  [tools intent]
  (->> tools
       (map #(merge % (score-tool-relevance % intent)))
       (sort-by :relevance >)))

;; ============================================================================
;; Quick Intent Inference (No LLM)
;; ============================================================================

(defn infer-intent-from-keywords
  "Quick intent inference from user description without LLM call.
   Useful for fast/cheap intent detection.

   Returns a basic intent map based on keyword matching."
  [user-description]
  (let [desc-lower (str/lower-case user-description)

        ;; Detect actions from keywords
        actions (cond-> []
                  (or (str/includes? desc-lower "search")
                      (str/includes? desc-lower "find")
                      (str/includes? desc-lower "look for"))
                  (conj "search")

                  (or (str/includes? desc-lower "get")
                      (str/includes? desc-lower "fetch")
                      (str/includes? desc-lower "retrieve")
                      (str/includes? desc-lower "read"))
                  (conj "retrieve")

                  (or (str/includes? desc-lower "create")
                      (str/includes? desc-lower "make")
                      (str/includes? desc-lower "generate")
                      (str/includes? desc-lower "write"))
                  (conj "generate")

                  (or (str/includes? desc-lower "transform")
                      (str/includes? desc-lower "convert")
                      (str/includes? desc-lower "process"))
                  (conj "transform")

                  (or (str/includes? desc-lower "validate")
                      (str/includes? desc-lower "check")
                      (str/includes? desc-lower "verify"))
                  (conj "validate")

                  (or (str/includes? desc-lower "update")
                      (str/includes? desc-lower "modify")
                      (str/includes? desc-lower "change"))
                  (conj "update")

                  (str/includes? desc-lower "delete")
                  (conj "delete"))

        ;; Extract potential keywords (simple word extraction)
        words (->> (str/split user-description #"\s+")
                   (map #(str/replace % #"[^\w]" ""))
                   (filter #(> (count %) 3))
                   (remove #(#{"want" "need" "help" "please" "would" "like" "about" "with" "that" "this" "from" "the"}
                             (str/lower-case %)))
                   vec)]

    {:primary-goal user-description
     :secondary-goals []
     :needed-actions (if (empty? actions) ["search" "retrieve"] actions)
     :domain nil
     :keywords words
     :inference-method :keyword-based}))

;; ============================================================================
;; REPL / Testing
;; ============================================================================

(comment
  ;; Example usage
  (def sample-tools
    [{:name "searchLangfuseDocs"
      :description "Search Langfuse documentation"
      :capabilities #{:search}}
     {:name "getLangfuseDocsPage"
      :description "Get a specific documentation page"
      :capabilities #{:retrieval}}
     {:name "getLangfuseOverview"
      :description "Get high-level overview"
      :capabilities #{:retrieval}}])

  ;; Quick inference (no LLM)
  (infer-intent-from-keywords "I want to find information about Langfuse pricing")

  ;; Full LLM-based analysis
  (analyze-intent "I want to understand Langfuse pricing and compare plans" sample-tools)

  ;; Score tools against intent
  (let [intent (infer-intent-from-keywords "search for pricing information")]
    (rank-tools-by-relevance sample-tools intent)))
