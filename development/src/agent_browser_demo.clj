(ns agent-browser-demo
  "Demo of agent-browser integration with ORC repl-researcher nodes.

   agent-browser provides token-efficient browser automation for AI agents.
   Unlike MCP-based solutions:
   - No session management needed (shell commands)
   - ~4x lower token cost (compact accessibility snapshots)
   - @ref markers designed for LLMs (e.g., @e1, @e2)

   ## Quick Start

   1. Ensure agent-browser is installed: npm install -g agent-browser
   2. Start nREPL: ./scripts/nrepl.sh
   3. Evaluate this file"
  (:require [ai.obney.orc.agent-browser.interface :as browser]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.sci-sandbox :as sci-sandbox]
            [ai.obney.orc.orc-dev.core :as dev]))

;; ============================================================================
;; Direct Browser Testing
;; ============================================================================

(comment
  ;; Test basic browser operations
  (browser/open "https://example.com")
  ;; => {:success true :output "✓ Example Domain\n  https://example.com/"}

  (browser/snapshot)
  ;; => {:success true :output "- heading \"Example Domain\" [level=1, ref=e1]\n- link \"More information...\" [ref=e2]"}

  (browser/get-title)
  ;; => {:success true :output "Example Domain"}

  (browser/click "@e2")
  ;; Clicks the "More information" link

  ;; Navigate and snapshot in one call
  (browser/navigate-and-snapshot "https://news.ycombinator.com")

  ;; Close browser
  (browser/close))

;; ============================================================================
;; SCI Sandbox Testing
;; ============================================================================

(comment
  ;; Test browser tools in SCI sandbox directly
  (def result
    (sci-sandbox/execute-with-mcp
     {:browser-tools ["open" "snapshot" "get-title"]
      :code "(do
               (println \"Opening page...\")
               (open \"https://example.com\")
               (println \"Getting snapshot...\")
               (let [snap (snapshot)]
                 (println \"Snapshot:\" (:output snap))
                 (str \"FINAL_ANSWER: \" (:output (get-title)))))\"}))

  (:final-answer result)
  ;; => "Example Domain"
  )

;; ============================================================================
;; ORC Workflow with Browser Tools
;; ============================================================================

(def page-extractor-workflow
  "Simple workflow that extracts info from a webpage."
  (sheet/workflow "page-info-extractor"
    (sheet/blackboard
     {:url :string
      :page-title :string
      :page-content :string})

    (sheet/repl-researcher "extract"
      :model "google/gemini-2.5-flash"
      :instruction "Navigate to the given URL, extract the page title and
                    list of interactive elements. Return both as FINAL_ANSWER."
      :reads [:url]
      :writes [:page-title :page-content]
      :browser-tools ["open" "snapshot" "get-title" "get-text"]
      :max-iterations 3)))

(comment
  ;; Start dev system
  (def service (dev/start))
  (def ctx (::dev/context service))

  ;; Build and execute the workflow
  (def sheet-id (sheet/build-workflow! ctx page-extractor-workflow))

  (def result
    (sheet/execute ctx sheet-id
                   {:url "https://example.com"}
                   :timeout-ms 60000))

  (:status result)
  (:outputs result))

;; ============================================================================
;; Apartment Search Demo
;; ============================================================================

(def apartment-search-workflow
  "Search for apartments on a rental site."
  (sheet/workflow "apartment-search"
    (sheet/blackboard
     {:search-url :string
      :search-query :string
      :listings [:vector [:map
                          [:title :string]
                          [:price :string]
                          [:link {:optional true} :string]]]})

    (sheet/repl-researcher "search"
      :model "google/gemini-2.5-flash"
      :instruction "Navigate to the search URL, enter the search query into the
                    search box, submit the search, and extract apartment listings.
                    For each listing, get the title, price, and link if available.
                    Return the listings as FINAL_ANSWER in EDN format:
                    [{:title \"...\" :price \"...\" :link \"...\"}]"
      :reads [:search-url :search-query]
      :writes [:listings]
      :browser-tools ["open" "snapshot" "click" "fill" "press" "wait" "get-text" "scroll"]
      :max-iterations 8)))

(comment
  ;; Start dev system if not already running
  (def service (dev/start))
  (def ctx (::dev/context service))

  ;; Build the apartment search workflow
  (def apt-sheet-id (sheet/build-workflow! ctx apartment-search-workflow))

  ;; Execute with a sample search
  (def apt-result
    (sheet/execute ctx apt-sheet-id
                   {:search-url "https://www.apartments.com"
                    :search-query "San Francisco, CA"}
                   :timeout-ms 120000))

  (:status apt-result)
  (:outputs apt-result))

;; ============================================================================
;; Hacker News Demo
;; ============================================================================

(def hn-extractor-workflow
  "Extract top stories from Hacker News."
  (sheet/workflow "hn-top-stories"
    (sheet/blackboard
     {:stories [:vector [:map
                         [:title :string]
                         [:points :string]
                         [:comments :string]]]})

    (sheet/repl-researcher "extract"
      :model "google/gemini-2.5-flash"
      :instruction "Navigate to https://news.ycombinator.com, extract the top 5 stories.
                    For each story get: title, points, and comment count.
                    Return as FINAL_ANSWER in EDN format."
      :reads []
      :writes [:stories]
      :browser-tools ["open" "snapshot" "get-text" "scroll"]
      :max-iterations 3)))

(comment
  (def service (dev/start))
  (def ctx (::dev/context service))

  (def hn-sheet-id (sheet/build-workflow! ctx hn-extractor-workflow))

  (def hn-result
    (sheet/execute ctx hn-sheet-id {} :timeout-ms 60000))

  (:status hn-result)
  (:outputs hn-result))
