(ns apartment-site-registry
  "Apartment site registry for multi-site discovery system.

   Provides:
   1. Known apartment sites with pre-seeded data
   2. Code executors for ORC workflow integration
   3. Trust-based site selection
   4. Pattern retrieval for learned navigation

   Usage in ORC workflow:
   ```clojure
   (sheet/code \"get-known-sites\"
     :fn \"apartment-site-registry/get-trusted-sites\"
     :reads [:location]
     :writes [:known-sites])
   ```"
  (:require [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [clojure.string :as str]))

;; =============================================================================
;; Known Apartment Sites
;; =============================================================================

(def known-apartment-sites
  "Pre-seeded apartment listing sites.

   NOTE: No hardcoded `requires-headed` or `known-challenges`!
   The system learns these through experience:
   1. First visit tries headless mode
   2. If blocked, records pattern that site needs headed mode
   3. Future visits use learned patterns

   Sites are categorized by:
   - :corporate - Major real estate companies
   - :peer-to-peer - Classified/direct from owner
   - :aggregator - Sites that aggregate from multiple sources
   - :local - Regional/local listing sites"
  [{:domain "zillow.com"
    :display-name "Zillow"
    :category :corporate
    :url-pattern "https://www.zillow.com/{location}/rentals/"
    ;; NO hardcoded requires-headed - will be learned
    :notes "Major rental site - system will learn navigation patterns"}

   {:domain "redfin.com"
    :display-name "Redfin"
    :category :corporate
    :url-pattern "https://www.redfin.com/{location}/apartments-for-rent"
    :notes "Map-based search interface"}

   {:domain "craigslist.org"
    :display-name "Craigslist"
    :category :peer-to-peer
    :url-pattern "https://{region}.craigslist.org/search/apa"
    :notes "Regional subdomains with click pagination"}

   {:domain "apartments.com"
    :display-name "Apartments.com"
    :category :corporate
    :url-pattern "https://www.apartments.com/{location}/"
    :notes "CoStar owned - system will learn navigation patterns"}

   {:domain "trulia.com"
    :display-name "Trulia"
    :category :corporate
    :url-pattern "https://www.trulia.com/for_rent/{location}/"
    :notes "Zillow subsidiary"}

   {:domain "hotpads.com"
    :display-name "HotPads"
    :category :corporate
    :url-pattern "https://hotpads.com/{location}/apartments-for-rent"
    :notes "Zillow subsidiary"}

   {:domain "rent.com"
    :display-name "Rent.com"
    :category :aggregator
    :url-pattern "https://www.rent.com/{state}/{city}/apartments"
    :notes "Aggregator site"}

   {:domain "realtor.com"
    :display-name "Realtor.com"
    :category :corporate
    :url-pattern "https://www.realtor.com/apartments/{location}"
    :notes "News Corp owned"}])

;; =============================================================================
;; Site Registry Management
;; =============================================================================

(defn seed-known-sites!
  "Seed the site registry with known apartment sites.

   Idempotent - will not duplicate sites that already exist.

   Args:
   - ctx: System context with event-store

   Returns:
   {:seeded N :skipped M :errors []}"
  [ctx]
  (reduce
   (fn [acc site]
     (try
       (let [result (cp/process-command
                     (assoc ctx :command
                            (merge {:command/name :apartment/register-site
                                    :discovered-via :manual}
                                   (dissoc site :notes)
                                   (when (:notes site)
                                     {:notes (:notes site)}))))]
         (if (:command-result/events result)
           (update acc :seeded inc)
           (update acc :skipped inc)))
       (catch Exception e
         (update acc :errors conj {:domain (:domain site)
                                   :error (.getMessage e)}))))
   {:seeded 0 :skipped 0 :errors []}
   known-apartment-sites))

;; =============================================================================
;; Location URL Building
;; =============================================================================

(defn- normalize-location
  "Normalize location string for URL building.

   'San Francisco, CA' -> 'san-francisco-ca'
   'Los Angeles' -> 'los-angeles'"
  [location]
  (-> location
      str/lower-case
      (str/replace #"[,.]" "")
      (str/replace #"\s+" "-")))

(defn- location->region
  "Extract Craigslist region from location.

   'San Francisco, CA' -> 'sfbay'
   'Los Angeles, CA' -> 'losangeles'"
  [location]
  (let [loc (str/lower-case location)]
    (cond
      (str/includes? loc "san francisco") "sfbay"
      (str/includes? loc "oakland") "sfbay"
      (str/includes? loc "san jose") "sfbay"
      (str/includes? loc "los angeles") "losangeles"
      (str/includes? loc "san diego") "sandiego"
      (str/includes? loc "seattle") "seattle"
      (str/includes? loc "portland") "portland"
      (str/includes? loc "denver") "denver"
      (str/includes? loc "chicago") "chicago"
      (str/includes? loc "new york") "newyork"
      (str/includes? loc "boston") "boston"
      (str/includes? loc "austin") "austin"
      (str/includes? loc "miami") "miami"
      (str/includes? loc "atlanta") "atlanta"
      :else (normalize-location (first (str/split location #","))))))

(defn- location->state-city
  "Extract state and city from location for rent.com URLs.

   'San Francisco, CA' -> {:state 'california' :city 'san-francisco'}"
  [location]
  (let [parts (str/split location #",")
        city (-> (first parts) str/trim str/lower-case (str/replace #"\s+" "-"))
        state-abbr (when (> (count parts) 1) (str/trim (second parts)))
        state-map {"CA" "california" "WA" "washington" "OR" "oregon"
                   "CO" "colorado" "TX" "texas" "FL" "florida"
                   "NY" "new-york" "IL" "illinois" "GA" "georgia"
                   "MA" "massachusetts"}]
    {:state (get state-map state-abbr "california")
     :city city}))

(defn build-site-url
  "Build the search URL for a site and location.

   Args:
   - site: Site map with :domain and :url-pattern
   - location: Location string like 'San Francisco, CA'

   Returns: Full URL string"
  [site location]
  (let [pattern (:url-pattern site)
        domain (:domain site)
        normalized (normalize-location location)]
    (cond
      (str/includes? domain "craigslist")
      (str "https://" (location->region location) ".craigslist.org/search/apa")

      (str/includes? domain "rent.com")
      (let [{:keys [state city]} (location->state-city location)]
        (str "https://www.rent.com/" state "/" city "/apartments"))

      (str/includes? pattern "{location}")
      (str/replace pattern "{location}" normalized)

      :else
      (:url-pattern site))))

;; =============================================================================
;; Code Executors for ORC Workflow
;; =============================================================================

(defn get-trusted-sites
  "Code executor: Get trusted sites for apartment search.

   Returns sites above minimum trust threshold, sorted by trust score.
   If no sites exist in registry, seeds known sites first.

   LEARNING: requires-headed is NOT hardcoded - it's queried from
   learned patterns stored in the ontology.

   Reads: location
   Writes: known-sites"
  [{:keys [inputs context]}]
  (let [location (get inputs "location" "")
        ctx (:execution-context context)
        ;; Get trusted sites from registry
        sites (when ctx (rm/get-trusted-sites ctx {:min-trust 0.3}))
        ;; If no sites, we would seed them (but caller should do this)
        final-sites (if (seq sites)
                      sites
                      ;; Fallback to known sites if registry empty
                      (mapv #(assoc % :trust-score 0.5 :extraction-count 0)
                            known-apartment-sites))
        ;; For each site, check learned patterns for requires-headed
        sites-with-learned-patterns
        (mapv (fn [site]
                (let [domain (:domain site)
                      ;; Query learned bot-bypass patterns
                      patterns (when ctx
                                 (try
                                   (rm/get-site-patterns ctx domain)
                                   (catch Exception _ [])))
                      ;; Check if any pattern indicates headed mode needed
                      learned-headed? (some (fn [p]
                                              (and (= :bot-bypass (:pattern-type p))
                                                   (get-in p [:pattern-data :requires-headed])))
                                            patterns)]
                  {:domain domain
                   :display-name (:display-name site)
                   :url (build-site-url site location)
                   :trust-score (:trust-score site 0.5)
                   ;; Use learned pattern, NOT hardcoded value
                   :requires-headed (boolean learned-headed?)
                   ;; Include learned patterns for the AI to use
                   :learned-patterns (vec patterns)}))
              final-sites)]
    {"known-sites" sites-with-learned-patterns}))

(defn get-site-patterns
  "Code executor: Get learned patterns for a specific site.

   Reads: current-site
   Writes: site-patterns, requires-headed"
  [{:keys [inputs context]}]
  (let [site (get inputs "current-site")
        domain (or (:domain site) (get site "domain"))
        ctx (:execution-context context)
        patterns (when ctx (rm/get-site-patterns ctx domain))
        site-info (when ctx (rm/get-site-by-domain ctx domain))]
    {"site-patterns" (or patterns [])
     "site-domain" domain
     "requires-headed" (boolean (or (:requires-headed site-info)
                                    (:requires-headed site)
                                    (get site "requires-headed")))}))

(defn merge-site-lists
  "Code executor: Merge known sites with discovered sites.

   Deduplicates by domain, preferring known sites data.

   Reads: known-sites, discovered-sites
   Writes: all-sites"
  [{:keys [inputs]}]
  (let [known (get inputs "known-sites" [])
        discovered (get inputs "discovered-sites" [])
        known-domains (set (map :domain known))
        ;; Add discovered sites not in known list
        new-sites (remove #(contains? known-domains (:domain %)) discovered)
        ;; Merge with known sites first (higher priority)
        all-sites (into (vec known) new-sites)]
    {"all-sites" all-sites}))

(defn record-extraction-result
  "Code executor: Record extraction success/failure for learning.

   Updates site trust score based on extraction outcome.
   Also records any learned patterns.

   Reads: current-site, listings
   Writes: site-result"
  [{:keys [inputs context]}]
  (let [site (get inputs "current-site")
        listings (get inputs "listings")
        domain (or (:domain site) (get site "domain"))
        ctx (:execution-context context)
        success? (and listings (seq listings))
        listing-count (if (sequential? listings) (count listings) 0)]

    ;; Update trust score in event store
    (when ctx
      (try
        (cp/process-command
         (assoc ctx :command
                {:command/name :apartment/update-site-trust
                 :domain domain
                 :success? success?
                 :listings-extracted listing-count}))
        (catch Exception e
          (println "Warning: Failed to update site trust:" (.getMessage e)))))

    {"site-result" {:domain domain
                    :status (if success? :success :failed)
                    :listings-count listing-count}}))

(defn aggregate-listings
  "Code executor: Aggregate listings from all sites.

   Flattens site-results into single listings collection.

   Reads: site-results
   Writes: all-listings, total-count"
  [{:keys [inputs]}]
  (let [site-results (get inputs "site-results" [])
        ;; Each site-result has :listings or nested structure
        all-listings (mapcat (fn [result]
                               (or (:listings result)
                                   (get result "listings")
                                   []))
                             site-results)
        total (count all-listings)]
    {"all-listings" (vec all-listings)
     "total-count" total}))

;; =============================================================================
;; Trust Score Helpers
;; =============================================================================

(defn calculate-new-trust
  "Calculate updated trust score after an extraction attempt.

   Uses exponential moving average with alpha = 0.3 for recent weighting."
  [site outcome]
  (let [current (or (:trust-score site) 0.5)
        alpha 0.3
        new-value (if (= outcome :success) 1.0 0.0)]
    (+ (* alpha new-value) (* (- 1 alpha) current))))

;; =============================================================================
;; REPL Helpers
;; =============================================================================

(comment
  ;; ====== SETUP ======
  (require '[ai.obney.orc.orc-dev.core :as dev])
  (def service (dev/start))
  (def ctx (::dev/context service))

  ;; ====== SEED KNOWN SITES ======
  (seed-known-sites! ctx)
  ;; => {:seeded 8 :skipped 0 :errors []}

  ;; ====== QUERY SITES ======
  (rm/get-all-sites ctx)
  (rm/get-trusted-sites ctx {:min-trust 0.5})
  (rm/get-site-by-domain ctx "zillow.com")
  (rm/get-sites-requiring-headed ctx)

  ;; ====== BUILD URLS ======
  (build-site-url {:domain "zillow.com"
                   :url-pattern "https://www.zillow.com/{location}/rentals/"}
                  "San Francisco, CA")
  ;; => "https://www.zillow.com/san-francisco-ca/rentals/"

  (build-site-url {:domain "craigslist.org"
                   :url-pattern "https://{region}.craigslist.org/search/apa"}
                  "San Francisco, CA")
  ;; => "https://sfbay.craigslist.org/search/apa"

  ;; ====== TEST CODE EXECUTORS ======
  (get-trusted-sites {:inputs {"location" "San Francisco, CA"}
                      :context {:execution-context ctx}})

  ;; ====== STATISTICS ======
  (rm/site-registry-statistics ctx)
  )
