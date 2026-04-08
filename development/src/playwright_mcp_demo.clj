(ns playwright-mcp-demo
  "Demo: Playwright MCP integration with ORC behavior trees.

   This demonstrates how to use Playwright browser automation via MCP
   with the self-learning ORC system.

   Prerequisites:
   1. Start Playwright MCP server: npx @playwright/mcp@latest --port 3001
   2. Set OPENROUTER_API_KEY (or ANTHROPIC_API_KEY) environment variable

   Usage:
   1. Load this file in REPL
   2. Evaluate the forms in order"
  (:require [ai.obney.orc.orc-dev.core :as dev]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]))

;; =============================================================================
;; Step 1: Start the ORC System
;; =============================================================================

(comment
  ;; Start the dev system (in-memory event store, LMDB cache, control plane)
  (def service (dev/start))
  (def ctx (::dev/context service))

  ;; Verify the system is running
  ctx
  ;; Should show {:tenant-id #uuid "...", :event-store ..., :call-tool-fn ...}
  )

;; =============================================================================
;; Step 2: Connect to Playwright MCP
;; =============================================================================

(comment
  ;; First, make sure Playwright MCP is running:
  ;; npx @playwright/mcp@latest --port 3001

  ;; Connect to the Playwright MCP server
  (def pw-conn (mcp/connect {:type :http
                              :url "http://localhost:3001"}))

  ;; List available tools
  (mcp/list-tools pw-conn)
  ;; Should return: [{:name "browser_navigate" ...} {:name "browser_click" ...} ...]

  ;; Register the connection with ORC
  (dev/set-mcp-call-tool-fn! (partial mcp/call-tool pw-conn))

  ;; Verify it's set
  @dev/mcp-call-tool-fn*
  ;; Should return: #function[...]
  )

;; =============================================================================
;; Step 3: Build a Simple Playwright Workflow
;; =============================================================================

(comment
  ;; Build a repl-researcher workflow with Playwright tools
  ;; This uses the static preset (tool definitions without live connection)
  ;; but the actual execution will use the live connection we registered
  (def simple-researcher
    (mcp/build-repl-researcher-sheet! ctx
      {:type :static :preset :playwright}
      "Navigate to https://example.com and tell me the page title."
      {:max-iterations 5}))

  ;; View what was created
  simple-researcher
  ;; => {:sheet-id #uuid "..."
  ;;     :workflow-name "mcp-researcher"
  ;;     :tools ["browser_navigate" "browser_click" ...]}

  ;; Execute the workflow
  (def result
    (sheet/execute ctx (:sheet-id simple-researcher)
                   {:question "What is the title of example.com?"}
                   :timeout-ms 60000))

  ;; Check the result
  result
  ;; => {:status :success
  ;;     :outputs {:answer "Example Domain"}
  ;;     :duration-ms 1234}
  )

;; =============================================================================
;; Step 4: More Complex Example - Extract Data from a Page
;; =============================================================================

(comment
  ;; Build a data extraction workflow
  (def extractor
    (mcp/build-repl-researcher-sheet! ctx
      {:type :static :preset :playwright}
      "Navigate to https://news.ycombinator.com and extract the titles of the top 5 stories.
       Return them as a JSON array of strings."
      {:max-iterations 10}))

  ;; Execute
  (def hn-result
    (sheet/execute ctx (:sheet-id extractor)
                   {:question "Get top 5 HN story titles"}
                   :timeout-ms 120000))

  hn-result
  )

;; =============================================================================
;; Step 5: Custom Workflow with DSL
;; =============================================================================

(comment
  ;; You can also build custom workflows using the ORC DSL directly
  (def custom-workflow
    (sheet/workflow "playwright-scraper"
      (sheet/blackboard
        {:url :string
         :selector :string
         :extracted-text :string})

      (sheet/repl-researcher "scrape"
        :model "anthropic/claude-sonnet-4-20250514"
        :instruction "Navigate to the given URL, wait for the page to load,
                     then extract text from the element matching the selector.
                     Return the extracted text as FINAL_ANSWER."
        :reads [:url :selector]
        :writes [:extracted-text]
        :mcp-tools ["browser_navigate" "browser_snapshot" "browser_evaluate"
                    "browser_wait_for" "browser_take_screenshot"]
        :max-iterations 5)))

  ;; Build the workflow
  (def scraper-id (sheet/build-workflow! ctx custom-workflow))

  ;; Execute with specific inputs
  (def scrape-result
    (sheet/execute ctx scraper-id
                   {:url "https://example.com"
                    :selector "h1"}
                   :timeout-ms 60000))

  scrape-result
  )

;; =============================================================================
;; Step 6: Apartment Search Demo (Full Example)
;; =============================================================================

(comment
  ;; This is the full apartment search workflow from our brainstorm
  (def apartment-search
    (sheet/workflow "apartment-search"
      (sheet/blackboard
        {:location [:string {:description "City or neighborhood to search"}]
         :max-price [:int {:description "Maximum monthly rent"}]
         :bedrooms [:int {:description "Number of bedrooms"}]
         :requirements [:string {:description "Additional requirements"}]
         :listings [:vector [:map
                             [:address :string]
                             [:price :int]
                             [:bedrooms :int]
                             [:link :string]]]})

      (sheet/repl-researcher "search-zillow"
        :model "anthropic/claude-sonnet-4-20250514"
        :instruction "Search for apartments on Zillow matching the criteria.
                     1. Navigate to zillow.com
                     2. Search for rentals in the specified location
                     3. Apply filters for price and bedrooms
                     4. Extract listing details (address, price, bedrooms, link)
                     5. Return as FINAL_ANSWER: [{...}, {...}, ...]"
        :reads [:location :max-price :bedrooms :requirements]
        :writes [:listings]
        :mcp-tools ["browser_navigate" "browser_click" "browser_type"
                    "browser_fill_form" "browser_snapshot" "browser_wait_for"
                    "browser_evaluate" "browser_take_screenshot"]
        :max-iterations 15
        ;; Enable self-learning for this node
        :context {:problem-type "problem:WebScraping"
                  :self-learning? true
                  :include-patterns true})))

  ;; Build the workflow
  (def apt-search-id (sheet/build-workflow! ctx apartment-search))

  ;; Execute a search
  (def apt-result
    (sheet/execute ctx apt-search-id
                   {:location "San Francisco, CA"
                    :max-price 3000
                    :bedrooms 2
                    :requirements "pet-friendly, near transit"}
                   :timeout-ms 300000)) ;; 5 minutes for complex scraping

  apt-result
  )

;; =============================================================================
;; Cleanup
;; =============================================================================

(comment
  ;; Close the MCP connection when done
  (mcp/close pw-conn)

  ;; Stop the ORC system
  (dev/stop service)
  )

;; =============================================================================
;; Troubleshooting
;; =============================================================================

(comment
  ;; Check if Playwright MCP is running
  ;; In terminal: lsof -i :3001

  ;; Test direct tool call (bypassing ORC)
  (mcp/call-tool pw-conn "browser_navigate" {:url "https://example.com"})

  ;; Check the dynamic call-tool-fn
  @dev/mcp-call-tool-fn*

  ;; Test the dynamic function directly
  (dev/dynamic-call-tool-fn "browser_navigate" {:url "https://example.com"})

  ;; Check if DSCloj providers are configured
  (require '[dscloj.core :as dscloj])
  @dscloj/providers*

  ;; View workflow structure
  (sheet/print-tree ctx apt-search-id)
  )
