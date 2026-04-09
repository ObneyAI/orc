(ns adaptive-search-executors
  "Code node executors for adaptive apartment search.

   These functions implement deterministic operations for bot detection
   and site navigation. They are called by `code` nodes in the behavior
   tree, NOT by LLM nodes.

   Key design principle (from CLAUDE.md plan):
   - Use behavior tree structure for decision-making, not LLM with many tools
   - Code nodes for deterministic operations (detection, click, wait)
   - LLM nodes only when reasoning is truly needed (slider endpoint detection)

   Usage in ORC workflow:
   ```clojure
   (sheet/code \"detect-challenge\"
     :fn \"adaptive-search-executors/detect-bot-challenge\"
     :reads [:page-snapshot]
     :writes [:challenge-type :challenge-ref :challenge-detected])
   ```"
  (:require [ai.obney.orc.agent-browser.interface :as browser]
            [clojure.string :as str]))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn- find-ref-in-line
  "Extract element ref (e.g., 'e123') from a snapshot line."
  [line]
  (when-let [[_ ref] (re-find #"ref=([^\]]+)" line)]
    (str "@" ref)))

(defn- find-button-ref
  "Find a button ref matching any of the given patterns."
  [lines & patterns]
  (let [pattern-re (re-pattern (str "(?i)" (str/join "|" patterns)))]
    (->> lines
         (filter #(and (re-find #"(?i)button" %)
                       (re-find pattern-re %)))
         first
         find-ref-in-line)))

(defn- find-checkbox-ref
  "Find a checkbox element ref."
  [lines]
  (->> lines
       (filter #(re-find #"(?i)checkbox" %))
       first
       find-ref-in-line))

(defn- find-slider-ref
  "Find a slider element ref."
  [lines]
  (->> lines
       (filter #(re-find #"(?i)slider|range|track" %))
       first
       find-ref-in-line))

;; ============================================================================
;; Page Snapshot
;; ============================================================================

(defn take-page-snapshot
  "Code executor: Take a page snapshot.

   Reads: (none)
   Writes: page-snapshot"
  [{:keys [_inputs]}]
  (let [result (browser/snapshot)]
    {"page-snapshot" (or (:output result) "")}))

;; ============================================================================
;; Domain Extraction
;; ============================================================================

(defn extract-domain
  "Code executor: Extract domain from URL.

   Reads: site-url
   Writes: site-domain"
  [{:keys [inputs]}]
  (let [url (get inputs "site-url" "")
        domain (try
                 (when-let [uri (java.net.URI. url)]
                   (.getHost uri))
                 (catch Exception _
                   (second (re-find #"https?://([^/]+)" url))))]
    {"site-domain" (or domain "")}))

;; ============================================================================
;; Bot Detection (Deterministic - No LLM)
;; ============================================================================

(defn detect-bot-challenge
  "Code executor: Scan snapshot for bot detection patterns.

   Returns challenge type and element ref. This is DETERMINISTIC -
   uses regex patterns, NOT LLM reasoning.

   Challenge types:
   - \"press-hold\" - Press and hold buttons (Zillow)
   - \"checkbox\" - 'I am not a robot' checkboxes
   - \"wait\" - Cloudflare 'checking your browser' pages
   - \"slider\" - Drag slider CAPTCHAs
   - \"none\" - No challenge detected

   Reads: page-snapshot
   Writes: challenge-type, challenge-ref, challenge-detected"
  [{:keys [inputs]}]
  (let [snapshot (get inputs "page-snapshot" "")
        lines (str/split-lines snapshot)]

    (cond
      ;; Press-and-hold detection (Zillow, apartments.com)
      (some #(re-find #"(?i)(press|click).*hold|hold.*(verify|button)|press.*button.*hold" %) lines)
      (let [ref (find-button-ref lines "press" "hold" "verify")]
        {"challenge-type" "press-hold"
         "challenge-ref" ref
         "challenge-detected" true})

      ;; Checkbox verification (reCAPTCHA, hCaptcha)
      (some #(re-find #"(?i)verify.*human|not.*robot|i.?am.?human|recaptcha|hcaptcha" %) lines)
      (let [ref (or (find-checkbox-ref lines)
                    (find-button-ref lines "verify" "human" "robot"))]
        {"challenge-type" "checkbox"
         "challenge-ref" ref
         "challenge-detected" true})

      ;; Cloudflare waiting challenge
      (some #(re-find #"(?i)checking.*browser|please.*wait|verifying.*connection|cloudflare|just.*moment" %) lines)
      {"challenge-type" "wait"
       "challenge-ref" nil
       "challenge-detected" true}

      ;; Slider CAPTCHA
      (some #(re-find #"(?i)slide.*verify|drag.*slider|slide.*puzzle|move.*slider" %) lines)
      (let [ref (find-slider-ref lines)]
        {"challenge-type" "slider"
         "challenge-ref" ref
         "challenge-detected" true})

      ;; No challenge detected
      :else
      {"challenge-type" "none"
       "challenge-ref" nil
       "challenge-detected" false})))

;; ============================================================================
;; Challenge Handlers (Deterministic Actions)
;; ============================================================================

;; NOTE: No hardcoded domain lists! The system learns which sites need headed mode
;; by trying headless first, detecting failures, and recording patterns.
;;
;; Learning happens via:
;; 1. Try headless -> if blocked, record that site needs headed mode
;; 2. Store patterns in ontology via :apartment/site-pattern-learned events
;; 3. Next time, check learned patterns BEFORE attempting navigation

(defn execute-press-hold
  "Code executor: Perform press-and-hold action.

   Press-and-hold challenges typically indicate PerimeterX or similar
   bot detection that may require headed browser mode.

   This executor:
   1. Attempts the press-hold action
   2. Verifies if it succeeded
   3. If failed, records a learning pattern (requires headed mode)

   Reads: challenge-ref, site-domain
   Writes: press-hold-executed, hold-duration-used, headed-mode-required, should-record-pattern"
  [{:keys [inputs context]}]
  (let [ref (get inputs "challenge-ref")
        site-domain (get inputs "site-domain" "")
        ;; Could query ontology for learned optimal duration
        duration 2500]
    (if ref
      (do
        ;; Attempt press-and-hold
        (browser/press-and-hold ref duration)
        (browser/wait 2000)

        ;; Check if challenge is still present
        (let [new-snapshot (:output (browser/snapshot))
              still-challenged? (some #(re-find #"(?i)press.*hold|human.*verification" %)
                                      (str/split-lines (or new-snapshot "")))]
          (if still-challenged?
            ;; Press-hold didn't work - we learned something!
            ;; Record that this site needs headed mode
            {"press-hold-executed" false
             "hold-duration-used" duration
             "headed-mode-required" true
             "should-record-pattern" true  ;; Signal to record learning
             "learned-pattern" {:pattern-type :bot-bypass
                                :pattern-data {:requires-headed true
                                               :challenge-type "press-hold"
                                               :failed-in-headless true}
                                :confidence 0.9}
             "error" (str "Press-hold failed in headless mode for: " site-domain
                         ". Recording pattern: requires headed mode.")}
            ;; Success!
            {"press-hold-executed" true
             "hold-duration-used" duration
             "headed-mode-required" false
             "should-record-pattern" true
             "learned-pattern" {:pattern-type :bot-bypass
                                :pattern-data {:requires-headed false
                                               :challenge-type "press-hold"
                                               :hold-duration-ms duration
                                               :succeeded-in-headless true}
                                :confidence 0.95}})))
      {"press-hold-executed" false
       "hold-duration-used" 0
       "headed-mode-required" false
       "should-record-pattern" false
       "error" "No challenge-ref provided"})))

(defn click-checkbox
  "Code executor: Click a verification checkbox.

   Reads: challenge-ref
   Writes: checkbox-clicked"
  [{:keys [inputs]}]
  (let [ref (get inputs "challenge-ref")]
    (if ref
      (do
        (browser/click ref)
        (browser/wait 1500)  ;; Wait for verification
        {"checkbox-clicked" true})
      {"checkbox-clicked" false
       "error" "No challenge-ref provided"})))

(defn wait-for-challenge
  "Code executor: Wait for Cloudflare-style challenges to complete.

   These challenges auto-complete after a few seconds.

   Reads: (none)
   Writes: waited"
  [{:keys [_inputs]}]
  (browser/wait 5000)
  {"waited" true})

(defn no-challenge-detected
  "Code executor: No-op when no challenge is present.

   This is the default branch in the fallback node.

   Reads: (none)
   Writes: challenge-handled"
  [{:keys [_inputs]}]
  {"challenge-handled" true
   "challenge-type" "none"})

;; ============================================================================
;; Bot Detection Pattern Recording (for Self-Learning)
;; ============================================================================

(defn record-learned-pattern
  "Code executor: Record a learned navigation/bot-bypass pattern to ontology.

   This is the core of the self-learning system. Patterns learned here
   will be queried by check-headed-mode-required on future visits.

   Reads: site-domain, learned-pattern, should-record-pattern
   Writes: pattern-recorded"
  [{:keys [inputs context]}]
  (let [site-domain (get inputs "site-domain")
        should-record (get inputs "should-record-pattern" false)
        learned-pattern (get inputs "learned-pattern")
        ctx (:execution-context context)]

    (if (and should-record learned-pattern site-domain ctx)
      (do
        ;; Record the pattern to ontology
        (try
          (require '[ai.obney.grain.command-processor-v2.interface :as cp])
          ((resolve 'ai.obney.grain.command-processor-v2.interface/process-command)
           (assoc ctx :command
                  {:command/name :apartment/record-site-pattern
                   :domain site-domain
                   :pattern-type (or (:pattern-type learned-pattern) :bot-bypass)
                   :pattern-data (or (:pattern-data learned-pattern) {})
                   :confidence (or (:confidence learned-pattern) 0.8)}))
          (println "Recorded learned pattern for" site-domain ":"
                   (:pattern-type learned-pattern))
          {"pattern-recorded" true
           "pattern-type" (name (or (:pattern-type learned-pattern) :unknown))}
          (catch Exception e
            (println "Warning: Failed to record pattern:" (.getMessage e))
            {"pattern-recorded" false
             "error" (.getMessage e)})))
      {"pattern-recorded" false
       "reason" (cond
                  (not should-record) "no-pattern-to-record"
                  (not learned-pattern) "missing-pattern-data"
                  (not site-domain) "missing-domain"
                  (not ctx) "missing-context")})))

(defn record-bot-detection-success
  "Code executor: Record successful bot detection bypass.

   DEPRECATED: Use record-learned-pattern instead.
   This function is kept for backward compatibility.

   Reads: site-domain, challenge-type, hold-duration-used
   Writes: pattern-recorded"
  [{:keys [inputs context]}]
  (let [site-domain (get inputs "site-domain")
        challenge-type (get inputs "challenge-type")
        hold-duration (get inputs "hold-duration-used")]
    ;; Delegate to record-learned-pattern
    (record-learned-pattern
     {:inputs {"site-domain" site-domain
               "should-record-pattern" true
               "learned-pattern" {:pattern-type :bot-bypass
                                  :pattern-data {:challenge-type challenge-type
                                                 :hold-duration-ms hold-duration
                                                 :succeeded true}
                                  :confidence 0.9}}
      :context context})))

;; ============================================================================
;; Navigation Pattern Learning
;; ============================================================================

(defn record-navigation-success
  "Code executor: Record successful navigation pattern.

   Called after successfully reaching a listings page. Records:
   - What selectors worked for popups/cookies
   - Pagination style detected
   - Any special navigation steps needed

   Reads: site-domain, navigation-steps, selectors-used
   Writes: pattern-recorded"
  [{:keys [inputs context]}]
  (let [site-domain (get inputs "site-domain")
        navigation-steps (get inputs "navigation-steps" [])
        selectors-used (get inputs "selectors-used" {})
        ctx (:execution-context context)]
    (when (and site-domain ctx (or (seq navigation-steps) (seq selectors-used)))
      (try
        (require '[ai.obney.grain.command-processor-v2.interface :as cp])
        ((resolve 'ai.obney.grain.command-processor-v2.interface/process-command)
         (assoc ctx :command
                {:command/name :apartment/record-site-pattern
                 :domain site-domain
                 :pattern-type :navigation
                 :pattern-data {:steps navigation-steps
                                :selectors selectors-used
                                :worked true}
                 :confidence 0.85}))
        {"pattern-recorded" true}
        (catch Exception e
          {"pattern-recorded" false
           "error" (.getMessage e)})))
    {"pattern-recorded" (boolean (and site-domain ctx))}))

(defn record-extraction-pattern
  "Code executor: Record successful extraction pattern.

   Called after successfully extracting listings. Records:
   - Listing card selectors
   - Price/address/title patterns
   - Pagination mechanism

   Reads: site-domain, listing-selector, pagination-type, field-patterns
   Writes: pattern-recorded"
  [{:keys [inputs context]}]
  (let [site-domain (get inputs "site-domain")
        listing-selector (get inputs "listing-selector")
        pagination-type (get inputs "pagination-type")
        field-patterns (get inputs "field-patterns" {})
        listings-count (get inputs "listings-count" 0)
        ctx (:execution-context context)]
    (when (and site-domain ctx (> listings-count 0))
      (try
        (require '[ai.obney.grain.command-processor-v2.interface :as cp])
        ((resolve 'ai.obney.grain.command-processor-v2.interface/process-command)
         (assoc ctx :command
                {:command/name :apartment/record-site-pattern
                 :domain site-domain
                 :pattern-type :extraction
                 :pattern-data {:listing-selector listing-selector
                                :pagination-type pagination-type
                                :field-patterns field-patterns
                                :successful-extraction-count listings-count}
                 :confidence (min 0.95 (+ 0.5 (* 0.01 listings-count)))}))
        {"pattern-recorded" true}
        (catch Exception e
          {"pattern-recorded" false
           "error" (.getMessage e)})))
    {"pattern-recorded" (boolean (and site-domain ctx (> listings-count 0)))}))

;; ============================================================================
;; Cookie/Popup Handling
;; ============================================================================

(defn detect-overlay
  "Code executor: Detect cookie banners or popup overlays.

   Reads: page-snapshot
   Writes: overlay-type, overlay-ref, overlay-detected"
  [{:keys [inputs]}]
  (let [snapshot (get inputs "page-snapshot" "")
        lines (str/split-lines snapshot)]

    (cond
      ;; Cookie consent
      (some #(re-find #"(?i)cookie|accept.*all|consent|privacy" %) lines)
      (let [ref (find-button-ref lines "accept" "agree" "ok" "got it")]
        {"overlay-type" "cookie"
         "overlay-ref" ref
         "overlay-detected" true})

      ;; Marketing popup
      (some #(re-find #"(?i)sign.*up|subscribe|newsletter|email.*updates|close|dismiss|no.*thanks" %) lines)
      (let [ref (or (find-button-ref lines "close" "dismiss" "no" "maybe.*later" "×" "x")
                    ;; Also look for close icons
                    (->> lines
                         (filter #(re-find #"(?i)button.*close|close.*button|×|✕" %))
                         first
                         find-ref-in-line))]
        {"overlay-type" "popup"
         "overlay-ref" ref
         "overlay-detected" true})

      :else
      {"overlay-type" "none"
       "overlay-ref" nil
       "overlay-detected" false})))

(defn dismiss-overlay
  "Code executor: Dismiss detected overlay.

   Tries clicking the overlay ref, then pressing Escape as fallback.

   Reads: overlay-ref
   Writes: overlay-dismissed"
  [{:keys [inputs]}]
  (let [ref (get inputs "overlay-ref")]
    (if ref
      (do
        (browser/click ref)
        (browser/wait 500)
        {"overlay-dismissed" true})
      ;; Try pressing Escape as fallback
      (do
        (browser/press "Escape")
        (browser/wait 500)
        {"overlay-dismissed" true}))))

;; ============================================================================
;; Listing Persistence (for Data Storage)
;; ============================================================================

(defn parse-price
  "Parse a price string to numeric value."
  [price-str]
  (when (and price-str (string? price-str))
    (try
      (-> price-str
          (str/replace #"[^0-9.]" "")
          (Double/parseDouble))
      (catch Exception _ nil))))

(defn extract-city
  "Extract city from location string."
  [location]
  (when location
    (-> location
        (str/split #",")
        first
        str/trim)))

(defn extract-state
  "Extract state from location string."
  [location]
  (when location
    (-> location
        (str/split #",")
        second
        (or "")
        str/trim
        (str/replace #"\s+\d+.*" ""))))  ;; Remove zip codes

(defn persist-listings
  "Code executor: Persist extracted listings to event store.

   Reads: listings, site-domain, location
   Writes: persisted-count"
  [{:keys [inputs context]}]
  (let [listings (get inputs "listings")
        site-domain (get inputs "site-domain")
        location (get inputs "location")
        ctx (:execution-context context)]

    (if (and ctx (seq listings))
      (do
        ;; TODO: Integrate with Grain event store when available
        ;; For now, just count and log
        (println "Would persist" (count listings) "listings from" site-domain)
        {"persisted-count" (count listings)})
      {"persisted-count" 0})))

;; ============================================================================
;; Headed Mode Management
;; ============================================================================

(defn check-headed-mode-required
  "Code executor: Check if headed mode is required based on LEARNED patterns.

   Queries the ontology for previously learned patterns about this site.
   If no patterns exist, defaults to headless (we'll learn if it fails).

   Learning loop:
   1. First visit: Try headless (requires-headed = false)
   2. If blocked: record-bot-detection-failure records the pattern
   3. Next visit: This function finds the pattern and sets headed mode

   Reads: site-domain, requires-headed (from site registry)
   Writes: requires-headed, headed-mode-set"
  [{:keys [inputs context]}]
  (let [site-domain (get inputs "site-domain" "")
        ;; Check if site registry already knows this site needs headed mode
        site-requires-headed (get inputs "requires-headed" false)
        ctx (:execution-context context)
        ;; Query learned patterns from ontology
        learned-patterns (when ctx
                           (try
                             (require '[ai.obney.orc.ontology.core.read-models :as rm])
                             ((resolve 'ai.obney.orc.ontology.core.read-models/get-site-patterns)
                              ctx site-domain {:pattern-type :bot-bypass})
                             (catch Exception _ [])))
        ;; Check if any learned pattern indicates headed mode is needed
        learned-headed? (some (fn [p]
                                (get-in p [:pattern-data :requires-headed]))
                              learned-patterns)
        requires-headed? (or site-requires-headed learned-headed?)]
    (when requires-headed?
      (browser/set-headed-mode! true))
    {"requires-headed" requires-headed?
     "headed-mode-set" requires-headed?
     "learned-patterns-found" (count (or learned-patterns []))}))

(defn reset-headed-mode
  "Code executor: Reset headed mode to default (headless).

   Call this after workflow completion if headed mode was enabled.

   Reads: (none)
   Writes: headed-mode-reset"
  [{:keys [_inputs]}]
  (browser/set-headed-mode! false)
  {"headed-mode-reset" true})

;; ============================================================================
;; Pagination Detection and Execution
;; ============================================================================

(defn detect-pagination-style
  "Code executor: Analyze snapshot to detect pagination mechanism.

   Detects:
   - :infinite-scroll - Page loads more on scroll
   - :click-numbered - Numbered page buttons (1, 2, 3...)
   - :click-next - 'Next' button
   - :load-more - 'Load More' or 'Show More' button
   - :none - Single page, no pagination

   Reads: page-snapshot
   Writes: pagination-type, pagination-ref, has-more-pages"
  [{:keys [inputs]}]
  (let [snapshot (get inputs "page-snapshot" "")
        lines (str/split-lines snapshot)]

    (cond
      ;; Check for numbered pagination (1, 2, 3... or page links)
      (some #(re-find #"(?i)(page\s*[0-9]|aria-label.*page.*[0-9]|pagination.*[0-9]|\bpage\s+\d+\s+of)" %) lines)
      (let [next-ref (find-button-ref lines "next" ">" "→" "»")]
        {"pagination-type" "click-numbered"
         "pagination-ref" next-ref
         "has-more-pages" (boolean next-ref)})

      ;; Check for "Load More" or "Show More" buttons
      (some #(re-find #"(?i)(load\s*more|show\s*more|see\s*more\s*listings|view\s*more)" %) lines)
      (let [ref (find-button-ref lines "load" "show" "see" "view" "more")]
        {"pagination-type" "load-more"
         "pagination-ref" ref
         "has-more-pages" (boolean ref)})

      ;; Check for "Next" button without page numbers
      (some #(re-find #"(?i)(button|link).*(next|→|>>|›)" %) lines)
      (let [ref (find-button-ref lines "next" ">" "»" "›")]
        {"pagination-type" "click-next"
         "pagination-ref" ref
         "has-more-pages" (boolean ref)})

      ;; Default: Assume infinite scroll if page has listings (price indicators)
      (some #(re-find #"(?i)\$[0-9,]+" %) lines)
      {"pagination-type" "infinite-scroll"
       "pagination-ref" nil
       "has-more-pages" true}

      ;; No pagination detected
      :else
      {"pagination-type" "none"
       "pagination-ref" nil
       "has-more-pages" false})))

(defn execute-pagination
  "Code executor: Perform the appropriate pagination action.

   Based on detected pagination type, scrolls or clicks to load more content.

   Reads: pagination-type, pagination-ref
   Writes: pagination-executed, action-taken, new-snapshot"
  [{:keys [inputs]}]
  (let [pagination-type (get inputs "pagination-type" "none")
        pagination-ref (get inputs "pagination-ref")]
    (case pagination-type
      "infinite-scroll"
      (do
        (browser/scroll :down 800)
        (browser/wait 2000)
        (let [new-snapshot (:output (browser/snapshot))]
          {"pagination-executed" true
           "action-taken" "scroll"
           "new-snapshot" (or new-snapshot "")}))

      ("click-numbered" "click-next" "load-more")
      (if pagination-ref
        (do
          (browser/click pagination-ref)
          (browser/wait 2500)
          (let [new-snapshot (:output (browser/snapshot))]
            {"pagination-executed" true
             "action-taken" "click"
             "new-snapshot" (or new-snapshot "")}))
        {"pagination-executed" false
         "action-taken" "none"
         "error" "No pagination ref found"})

      ;; No pagination
      {"pagination-executed" false
       "action-taken" "none"})))

(defn count-price-indicators
  "Count unique price indicators in snapshot (proxy for listing count)."
  [snapshot]
  (if (string? snapshot)
    (count (re-seq #"\$[0-9,]+(?:/mo|/month)?" snapshot))
    0))

(defn has-new-content?
  "Code executor: Check if new content was loaded after pagination.

   Compares listing count before and after pagination action.

   Reads: old-snapshot, new-snapshot
   Writes: new-content-loaded, listings-delta"
  [{:keys [inputs]}]
  (let [old-snapshot (get inputs "old-snapshot" "")
        new-snapshot (get inputs "new-snapshot" "")
        old-count (count-price-indicators old-snapshot)
        new-count (count-price-indicators new-snapshot)
        delta (- new-count old-count)]
    {"new-content-loaded" (> delta 0)
     "listings-delta" delta
     "old-count" old-count
     "new-count" new-count}))

;; ============================================================================
;; High-Level Workflow Helpers
;; ============================================================================

(defn verify-page-loaded
  "Code executor: Verify that a page has loaded successfully.

   Checks for common error patterns in the snapshot.

   Reads: page-snapshot
   Writes: page-valid, page-error"
  [{:keys [inputs]}]
  (let [snapshot (get inputs "page-snapshot" "")
        lines (str/split-lines snapshot)]

    (cond
      ;; Access denied
      (some #(re-find #"(?i)access.*denied|forbidden|blocked" %) lines)
      {"page-valid" false
       "page-error" "access-denied"}

      ;; 404 or not found
      (some #(re-find #"(?i)not.*found|404|page.*doesn.t.*exist" %) lines)
      {"page-valid" false
       "page-error" "not-found"}

      ;; Empty page
      (< (count lines) 3)
      {"page-valid" false
       "page-error" "empty-page"}

      ;; Looks valid
      :else
      {"page-valid" true
       "page-error" nil})))
