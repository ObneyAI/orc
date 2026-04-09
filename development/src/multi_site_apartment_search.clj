(ns multi-site-apartment-search
  "Multi-site apartment search workflow with TRUE self-learning.

   KEY DESIGN PRINCIPLES:
   1. NO HARDCODED SITE LOGIC - Everything is learned through experience
   2. ORC-OWNED TOOLS - No dependency on Claude Code's MCP
   3. PATTERN LEARNING - Each navigation/extraction records what worked

   A general web-solving tree that:
   1. Gets sites from registry (initially just names, no assumptions)
   2. Optionally discovers new sites via Tavily (native client, not MCP)
   3. Searches each site, LEARNING patterns as it goes
   4. Records what worked/failed to ontology for future visits
   5. Trust scores adjust based on extraction success

   LEARNING LOOP:
   - First visit to site: Try headless, no assumptions
   - If blocked: Record 'requires-headed' pattern
   - If succeeded: Record navigation/extraction patterns
   - Next visit: Query learned patterns before attempting

   SETUP:
   ```clojure
   (require '[ai.obney.orc.orc-dev.core :as dev])
   (require '[ai.obney.workshop.sheet-service.interface :as sheet])
   (require '[multi-site-apartment-search :as msa])
   (require '[apartment-site-registry :as asr])
   (require '[tavily-client :as tavily])

   ;; Start system
   (def service (dev/start))
   (def ctx (::dev/context service))

   ;; Register Tavily for site discovery (ORC-owned, not Claude MCP)
   (tavily/register-with-orc!)

   ;; Seed known sites (just names, no hardcoded patterns)
   (asr/seed-known-sites! ctx)

   ;; Build and run
   (def sheet-id (sheet/build-workflow! ctx msa/multi-site-apartment-search))
   (sheet/execute ctx sheet-id {:location \"San Francisco, CA\" :max-rent 4000})
   ```"
  (:require [ai.obney.workshop.sheet-service.interface :as sheet]))

;; =============================================================================
;; Schema Definitions
;; =============================================================================

(def site-schema
  "Schema for a site in the search pipeline."
  [:map
   [:domain :string]
   [:display-name :string]
   [:url :string]
   [:trust-score :double]
   [:requires-headed :boolean]
   [:known-challenges [:vector :string]]])

(def site-result-schema
  "Schema for extraction result from a single site."
  [:map
   [:domain :string]
   [:status [:enum :success :partial :failed :blocked]]
   [:listings-count :int]
   [:error {:optional true} :string]])

(def apartment-listing-schema
  "Schema for an extracted apartment listing."
  [:map
   [:title :string]
   [:price :string]
   [:address :string]
   [:source-site :string]
   [:bedrooms {:optional true} :string]
   [:bathrooms {:optional true} :string]
   [:sqft {:optional true} :string]
   [:url {:optional true} :string]])

;; =============================================================================
;; Multi-Site Apartment Search Workflow
;; =============================================================================

(def multi-site-apartment-search
  "General apartment search that discovers and learns sites over time."
  (sheet/workflow "multi-site-apartment-search"
    (sheet/blackboard
      {:location :string
       :max-rent :int
       ;; Site discovery
       :known-sites [:vector site-schema]
       :discovered-sites [:vector site-schema]
       :all-sites [:vector site-schema]
       ;; Per-site extraction (for map-each)
       :current-site site-schema
       :site-domain :string
       :site-patterns [:vector [:map-of :keyword :any]]
       :requires-headed :boolean
       :headed-mode-set :boolean
       :learned-patterns-found :int
       :page-loaded :boolean
       :listings [:vector apartment-listing-schema]
       :site-result site-result-schema
       ;; Learning fields
       :should-record-pattern :boolean
       :learned-pattern [:map
                         [:pattern-type [:enum :navigation :extraction :bot-bypass :pagination]]
                         [:pattern-data [:map-of :keyword :any]]
                         [:confidence :double]]
       :pattern-recorded :boolean
       ;; Aggregated results
       :site-results [:vector site-result-schema]
       :all-listings [:vector apartment-listing-schema]
       :total-count :int})

    (sheet/sequence "main"
      ;; =========================================================================
      ;; Step 1: Get Known Sites
      ;; =========================================================================
      (sheet/code "get-known-sites"
        :fn "apartment-site-registry/get-trusted-sites"
        :reads [:location]
        :writes [:known-sites])

      ;; =========================================================================
      ;; Step 2: Optionally Discover New Sites via Web Search
      ;; =========================================================================
      ;; Only discovers if we have fewer than 3 known sites
      (sheet/fallback "discover-or-skip"
        ;; Condition: Skip if we already have enough sites
        (sheet/condition "has-enough-sites"
          :test #(>= (count (get % "known-sites" [])) 3))

        ;; Otherwise: Discover new sites via Tavily (ORC's native client)
        (sheet/repl-researcher "discover-apartment-sites"
          :model "google/gemini-2.5-flash"
          :instruction "Search for apartment listing websites for the given location.

Use web search to find legitimate apartment listing sites.

CRITERIA FOR GOOD SITES:
- Corporate or well-known aggregator sites
- Sites with actual rental listings (not just articles about renting)
- Sites that cover the target location
- Avoid scam sites, pure advertising sites

LOCATION: {location}
ALREADY KNOWN: {known-sites}

Use the tavily_search tool to search. Example:
(tavily_search {:query \"San Francisco apartments for rent listing websites\" :max_results 10})

Search queries to try:
1. '{location} apartments for rent'
2. 'best apartment listing websites 2024'

From the results, extract domains that are legitimate apartment listing sites.

Return FINAL_ANSWER with a vector of NEW sites (not already in known-sites):
[{:domain \"example.com\" :display-name \"Example Site\" :url \"https://example.com/apartments\"}]"
          :reads [:location :known-sites]
          :writes [:discovered-sites]
          ;; Uses ORC's registered call-tool-fn (set via tavily/register-with-orc!)
          :mcp-tools ["tavily_search"]
          :max-iterations 3))

      ;; =========================================================================
      ;; Step 3: Merge Known + Discovered Sites
      ;; =========================================================================
      (sheet/code "merge-sites"
        :fn "apartment-site-registry/merge-site-lists"
        :reads [:known-sites :discovered-sites]
        :writes [:all-sites])

      ;; =========================================================================
      ;; Step 4: Search Each Site (Parallel with map-each)
      ;; =========================================================================
      (sheet/map-each "search-sites"
        :over :all-sites
        :as :current-site
        :collect :site-results

        (sheet/sequence "site-extraction"
          ;; 4a: Load learned patterns for this site
          (sheet/code "load-site-patterns"
            :fn "apartment-site-registry/get-site-patterns"
            :reads [:current-site]
            :writes [:site-patterns :site-domain :requires-headed])

          ;; 4b: Enable headed mode if needed
          (sheet/code "configure-browser"
            :fn "adaptive-search-executors/check-headed-mode-required"
            :reads [:site-domain :requires-headed]
            :writes [:headed-mode-set])

          ;; 4c: Navigate to site and handle challenges
          (sheet/fallback "navigate-with-retry"
            ;; Try navigation with learned patterns
            (sheet/repl-researcher "navigate-with-patterns"
              :model "google/gemini-2.5-flash"
              :instruction "Navigate to the apartment listing site and LEARN from the experience.

SITE: {current-site}
LEARNED PATTERNS: {site-patterns}
LOCATION: {location}

YOUR GOAL: Get to a page showing apartment listings, and RECORD what you learn.

STEPS:
1. Use (open url) to navigate to the site URL
2. Use (wait 2000) to let the page load
3. Use (snapshot) to see the page content
4. Analyze what you see:
   - If you see listings -> SUCCESS
   - If you see 'press and hold' or 'verify you are human' -> BLOCKED (needs headed mode)
   - If you see a cookie popup -> Click accept and continue
   - If you see 'Access Denied' or '403' -> BLOCKED

BROWSER TOOLS:
(open url) - Navigate to URL
(snapshot) - Get page accessibility tree with @ref markers
(click ref) - Click element by @ref
(fill ref text) - Fill text field
(wait ms) - Wait milliseconds
(scroll direction) - Scroll :up, :down

CRITICAL - Return FINAL_ANSWER as a map with BOTH results AND learning:
{:page-loaded true/false
 :should-record-pattern true
 :learned-pattern {:pattern-type :bot-bypass OR :navigation
                   :pattern-data {:requires-headed true/false
                                  :challenge-seen \"description\"
                                  :steps-taken [\"what you did\"]
                                  :worked true/false}
                   :confidence 0.9}}

Examples:
- Successfully loaded: {:page-loaded true :should-record-pattern true :learned-pattern {:pattern-type :navigation :pattern-data {:requires-headed false :worked true} :confidence 0.9}}
- Blocked by bot detection: {:page-loaded false :should-record-pattern true :learned-pattern {:pattern-type :bot-bypass :pattern-data {:requires-headed true :challenge-seen \"press and hold\"} :confidence 0.95}}"
              :reads [:current-site :site-patterns :location]
              :writes [:page-loaded :should-record-pattern :learned-pattern]
              :browser-tools ["open" "snapshot" "click" "fill" "wait" "scroll" "press"]
              :context {:self-learning? true}
              :max-iterations 5)

            ;; Fallback: Mark as failed if navigation fails
            (sheet/code "mark-navigation-failed"
              :fn "(fn [{:keys [_inputs]}] {\"page-loaded\" false})"
              :reads []
              :writes [:page-loaded]))

          ;; 4d: Extract listings (only if page loaded)
          (sheet/fallback "extract-or-skip"
            ;; Condition: Skip if page didn't load
            (sheet/condition "page-loaded?"
              :test #(not (get % "page-loaded" false)))

            ;; Extract listings
            (sheet/repl-researcher "extract-listings"
              :model "google/gemini-2.5-flash"
              :instruction "Extract apartment listings with SMART PAGINATION.

SITE: {site-domain}
MAX RENT: {max-rent}
LEARNED PATTERNS: {site-patterns}

YOUR GOAL: Extract AS MANY listings as possible using the right pagination strategy.

STEP 1 - DETECT PAGINATION STYLE:
Use (snapshot) and look for:
- Numbered page buttons (1, 2, 3..., 'Page X of Y') → Click pagination
- 'Next' or '>' or '›' buttons → Click next
- 'Load More', 'Show More', 'See More' buttons → Click load-more
- No pagination controls visible → Infinite scroll (use scroll)

Common patterns by site type:
- Craigslist: Click numbered pagination or 'next >'
- Zillow/Apartments.com: Infinite scroll
- Redfin: Scroll on map view, click on list view
- Rent.com: 'Load More' or 'See More' buttons

STEP 2 - EXTRACT CURRENT PAGE:
Parse listings visible in snapshot:
- Title: apartment name or description
- Price: look for $X,XXX/mo format
- Address: street address, city
- Bedrooms/Bathrooms: X bd / X ba or similar
- Source: use '{site-domain}'

STEP 3 - PAGINATE (repeat up to 5 times):
Based on what you detected:
- For infinite scroll: (scroll :down) then (wait 2000) then (snapshot)
- For click: Find the button ref, (click \"@ref\") then (wait 2500) then (snapshot)
- Look for '@ref' markers on pagination elements

STEP 4 - STOP WHEN:
- You have 30+ listings, OR
- No new listings appear after pagination (compare counts), OR
- 5 pagination attempts made

BROWSER TOOLS:
(snapshot) - See page with @ref markers (use frequently!)
(scroll :down) - Scroll for infinite scroll sites
(click \"@ref\") - Click pagination buttons
(wait ms) - Wait for content to load (use 2000-3000ms)

CRITICAL - Return FINAL_ANSWER as a map with BOTH listings AND learning:
{:listings [{:title \"Apartment Name\"
             :price \"$2,500/mo\"
             :address \"123 Main St\"
             :source-site \"{site-domain}\"
             :bedrooms \"2 bd\"
             :bathrooms \"1 ba\"}]
 :should-record-pattern true
 :learned-pattern {:pattern-type :pagination
                   :pattern-data {:pagination-style \"scroll OR click-numbered OR click-next OR load-more\"
                                  :worked true
                                  :listings-per-page 20}
                   :confidence 0.85}}

If extraction fails or no listings found, still return:
{:listings []
 :should-record-pattern true
 :learned-pattern {:pattern-type :extraction
                   :pattern-data {:error \"what went wrong\"
                                  :page-structure \"what you observed\"}
                   :confidence 0.5}}"
              :reads [:site-domain :max-rent :site-patterns]
              :writes [:listings :should-record-pattern :learned-pattern]
              :browser-tools ["snapshot" "scroll" "wait" "click"]
              :context {:self-learning? true}
              :max-iterations 8))

          ;; 4e: Record learned patterns (navigation, extraction, bot-bypass)
          (sheet/code "record-learned-patterns"
            :fn "adaptive-search-executors/record-learned-pattern"
            :reads [:site-domain :learned-pattern :should-record-pattern]
            :writes [:pattern-recorded])

          ;; 4f: Record success/failure for trust score
          (sheet/code "record-site-result"
            :fn "apartment-site-registry/record-extraction-result"
            :reads [:current-site :listings]
            :writes [:site-result])))

      ;; =========================================================================
      ;; Step 5: Aggregate All Results
      ;; =========================================================================
      (sheet/code "aggregate-results"
        :fn "apartment-site-registry/aggregate-listings"
        :reads [:site-results]
        :writes [:all-listings :total-count]))))

;; =============================================================================
;; Single-Site Workflow (For Testing)
;; =============================================================================

(def single-site-apartment-search
  "Simple single-site search for testing individual sites."
  (sheet/workflow "single-site-apartment-search"
    (sheet/blackboard
      {:site-url :string
       :site-domain :string
       :location :string
       :max-rent :int
       :requires-headed :boolean
       :headed-mode-set :boolean
       :page-snapshot :string
       :listings [:vector apartment-listing-schema]})

    (sheet/sequence "main"
      ;; Extract domain from URL
      (sheet/code "extract-domain"
        :fn "adaptive-search-executors/extract-domain"
        :reads [:site-url]
        :writes [:site-domain])

      ;; Check headed mode
      (sheet/code "check-headed-mode"
        :fn "adaptive-search-executors/check-headed-mode-required"
        :reads [:site-domain]
        :writes [:requires-headed :headed-mode-set])

      ;; Navigate and extract
      (sheet/repl-researcher "navigate-and-extract"
        :model "google/gemini-2.5-flash"
        :instruction "Navigate to the apartment listing site and extract listings.

SITE URL: {site-url}
MAX RENT: {max-rent}

STEPS:
1. Use (open site-url) to navigate
2. Use (wait 3000) to let page load
3. Use (snapshot) to see the page
4. Handle any popups or challenges
5. Extract listings with title, price, address
6. Scroll and extract more if available

Return FINAL_ANSWER with vector of listings:
[{:title \"...\" :price \"...\" :address \"...\" :source-site \"...\"}]"
        :reads [:site-url :max-rent]
        :writes [:listings]
        :browser-tools ["open" "snapshot" "click" "fill" "wait" "scroll" "press"]
        :max-iterations 8))))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; ====== SETUP ======
  (require '[ai.obney.orc.orc-dev.core :as dev])
  (require '[ai.obney.workshop.sheet-service.interface :as sheet])
  (require '[apartment-site-registry :as asr])
  (require '[ai.obney.orc.agent-browser.interface :as browser])
  (require '[ai.obney.orc.ontology.core.read-models :as rm])
  (require '[tavily-client :as tavily])
  (require '[dscloj.core :as dscloj])

  (def service (dev/start))
  (def ctx (::dev/context service))
  (dscloj/quick-setup!)

  ;; ====== REGISTER TAVILY (ORC-owned, not Claude MCP) ======
  ;; Make sure TAVILY_API_KEY is set in your environment
  (tavily/register-with-orc!)

  ;; ====== SEED SITES (just names, no hardcoded patterns) ======
  (asr/seed-known-sites! ctx)

  ;; ====== VERIFY NO HARDCODED PATTERNS ======
  ;; Sites start with NO learned patterns - they're learned through experience
  (rm/site-registry-statistics ctx)
  ;; => {:total-sites 8 :total-patterns 0 ...}

  ;; ====== BUILD WORKFLOW ======
  (def sheet-id (sheet/build-workflow! ctx multi-site-apartment-search))

  ;; ====== EXECUTE ======
  (def result
    (sheet/execute ctx sheet-id
      {:location "San Francisco, CA"
       :max-rent 4000}
      :timeout-ms 600000))  ;; 10 min timeout for multi-site

  ;; ====== VIEW RESULTS ======
  (:total-count (:outputs result))
  (count (:all-listings (:outputs result)))
  (pprint (:site-results (:outputs result)))

  ;; ====== SINGLE SITE TEST ======
  (def single-sheet-id (sheet/build-workflow! ctx single-site-apartment-search))

  ;; Test Craigslist (no headed mode needed)
  (def cl-result
    (sheet/execute ctx single-sheet-id
      {:site-url "https://sfbay.craigslist.org/search/apa"
       :location "San Francisco"
       :max-rent 4000}
      :timeout-ms 120000))

  ;; Test Redfin
  (def redfin-result
    (sheet/execute ctx single-sheet-id
      {:site-url "https://www.redfin.com/city/17151/CA/San-Francisco/apartments-for-rent"
       :location "San Francisco"
       :max-rent 4000}
      :timeout-ms 120000))

  ;; Test Zillow (requires headed mode)
  (browser/set-headed-mode! true)
  (def zillow-result
    (sheet/execute ctx single-sheet-id
      {:site-url "https://www.zillow.com/san-francisco-ca/rentals/"
       :location "San Francisco"
       :max-rent 4000}
      :timeout-ms 120000))
  (browser/set-headed-mode! false)

  ;; ====== CHECK LEARNED PATTERNS ======
  ;; After running, see what the system learned
  (rm/get-site-patterns ctx "zillow.com")
  ;; If blocked: => [{:pattern-type :bot-bypass :pattern-data {:requires-headed true} ...}]

  (rm/get-site-patterns ctx "craigslist.org")
  ;; If succeeded: => [{:pattern-type :navigation :pattern-data {:requires-headed false ...}}]

  ;; See all sites with their learned patterns
  (for [site (rm/get-all-sites ctx)]
    {:domain (:domain site)
     :trust-score (:trust-score site)
     :patterns (rm/get-site-patterns ctx (:domain site))})

  ;; Statistics
  (rm/site-registry-statistics ctx)
  ;; => {:total-sites 8
  ;;     :total-patterns 12
  ;;     :sites-with-extractions 5
  ;;     :avg-trust-score 0.72}

  ;; ====== CLEANUP ======
  (browser/close {:all true})
  )
