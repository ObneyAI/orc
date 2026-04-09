(ns comprehensive-site-test
  "Test all 8 apartment sites with diverse pagination.

   This test suite validates:
   1. Each site can be navigated successfully
   2. Listings are extracted with proper pagination
   3. Patterns are learned and stored in ontology
   4. Patterns are reused on subsequent visits

   Run order:
   Phase A (Headless): craigslist, redfin, rent.com, realtor.com
   Phase B (Headed): zillow, apartments.com, trulia, hotpads

   Usage:
   ```clojure
   (require '[comprehensive-site-test :as cst])

   ;; Setup
   (def ctx (cst/setup!))

   ;; Test single site
   (cst/test-single-site! ctx {:domain \"craigslist.org\"
                               :url \"https://sfbay.craigslist.org/search/apa\"
                               :headed false})

   ;; Test all easy sites (headless)
   (cst/test-easy-sites! ctx)

   ;; Test all protected sites (headed)
   (cst/test-protected-sites! ctx)

   ;; Run full test suite
   (cst/run-all-tests! ctx)
   ```"
  (:require [ai.obney.orc.orc-dev.core :as dev]
            [ai.obney.workshop.sheet-service.interface :as sheet]
            [ai.obney.orc.agent-browser.interface :as browser]
            [clojure.pprint :refer [pprint]]))

;; =============================================================================
;; Site Definitions
;; =============================================================================

(def easy-sites
  "Sites that typically work in headless mode."
  [{:domain "craigslist.org"
    :url "https://sfbay.craigslist.org/search/apa"
    :headed false
    :expected-pagination "click-numbered"
    :notes "Simple HTML, numbered pagination"}

   {:domain "redfin.com"
    :url "https://www.redfin.com/city/17151/CA/San-Francisco/apartments-for-rent"
    :headed false
    :expected-pagination "infinite-scroll"
    :notes "Map-based interface, may need scrolling"}

   {:domain "rent.com"
    :url "https://www.rent.com/california/san-francisco-apartments"
    :headed false
    :expected-pagination "load-more"
    :notes "May have popups, 'Load More' button"}

   {:domain "realtor.com"
    :url "https://www.realtor.com/apartments/San-Francisco_CA"
    :headed false
    :expected-pagination "infinite-scroll"
    :notes "News Corp owned"}])

(def protected-sites
  "Sites that typically require headed mode due to bot detection."
  [{:domain "zillow.com"
    :url "https://www.zillow.com/san-francisco-ca/rentals/"
    :headed true
    :expected-pagination "infinite-scroll"
    :notes "PerimeterX protection, press-hold challenge"}

   {:domain "apartments.com"
    :url "https://www.apartments.com/san-francisco-ca/"
    :headed true
    :expected-pagination "infinite-scroll"
    :notes "PerimeterX protection"}

   {:domain "trulia.com"
    :url "https://www.trulia.com/for_rent/San_Francisco,CA/"
    :headed true
    :expected-pagination "infinite-scroll"
    :notes "Zillow subsidiary"}

   {:domain "hotpads.com"
    :url "https://hotpads.com/san-francisco-ca/apartments-for-rent"
    :headed true
    :expected-pagination "infinite-scroll"
    :notes "Zillow subsidiary, map-focused"}])

(def all-sites
  (vec (concat easy-sites protected-sites)))

;; =============================================================================
;; Setup
;; =============================================================================

(defn setup!
  "Initialize the test environment.

   Returns context with:
   - System context
   - Built workflow sheet-id
   - Site registry seeded"
  []
  (require '[dscloj.core :as dscloj])
  (require '[apartment-site-registry :as asr])
  (require '[multi-site-apartment-search :as msa])

  (println "Starting ORC system...")
  (let [service (dev/start)
        ctx (::dev/context service)]

    ;; Setup DSCloj
    ((resolve 'dscloj.core/quick-setup!))

    ;; Seed known sites
    (println "Seeding site registry...")
    (let [seed-result ((resolve 'apartment-site-registry/seed-known-sites!) ctx)]
      (println "  Sites seeded:" (:seeded seed-result)
               "Skipped:" (:skipped seed-result)))

    ;; Build workflows
    (println "Building workflows...")
    (let [single-sheet-id (sheet/build-workflow!
                           ctx
                           @(resolve 'multi-site-apartment-search/single-site-apartment-search))
          multi-sheet-id (sheet/build-workflow!
                          ctx
                          @(resolve 'multi-site-apartment-search/multi-site-apartment-search))]

      (println "  Single-site workflow:" single-sheet-id)
      (println "  Multi-site workflow:" multi-sheet-id)
      (println "Setup complete!")

      {:ctx ctx
       :service service
       :single-sheet-id single-sheet-id
       :multi-sheet-id multi-sheet-id})))

;; =============================================================================
;; Single Site Test
;; =============================================================================

(defn test-single-site!
  "Test a single apartment site.

   Args:
   - env: Environment from setup! (contains :ctx and :single-sheet-id)
   - site: Site map with :domain, :url, :headed

   Returns:
   {:domain \"...\", :status :success/:failed, :listings-count N, :duration-ms N, :patterns-learned N}"
  [{:keys [ctx single-sheet-id]} {:keys [domain url headed] :as site}]
  (require '[ai.obney.orc.ontology.core.read-models :as rm])

  (println)
  (println (apply str (repeat 60 "=")))
  (println "Testing:" domain (if headed "[HEADED]" "[headless]"))
  (println "URL:" url)
  (println (apply str (repeat 60 "-")))

  ;; Configure browser mode
  (browser/set-headed-mode! headed)
  (browser/close {:all true})

  (let [start-time (System/currentTimeMillis)

        ;; Get patterns learned BEFORE this test
        pre-patterns (try
                       ((resolve 'ai.obney.orc.ontology.core.read-models/get-site-patterns)
                        ctx domain)
                       (catch Exception _ []))

        ;; Execute the workflow
        result (try
                 (sheet/execute ctx single-sheet-id
                   {:site-url url
                    :location "San Francisco"
                    :max-rent 5000}
                   :timeout-ms 180000)
                 (catch Exception e
                   {:status :error
                    :error (.getMessage e)}))

        duration-ms (- (System/currentTimeMillis) start-time)

        ;; Extract results
        listings (get-in result [:outputs "listings"] [])
        listings (if (sequential? listings) listings [])

        ;; Get patterns learned AFTER this test
        post-patterns (try
                        ((resolve 'ai.obney.orc.ontology.core.read-models/get-site-patterns)
                         ctx domain)
                        (catch Exception _ []))
        new-patterns (- (count post-patterns) (count pre-patterns))

        ;; Determine status
        status (cond
                 (= :error (:status result)) :error
                 (and (= :success (:status result)) (seq listings)) :success
                 (= :success (:status result)) :partial
                 :else :failed)]

    ;; Print results
    (println)
    (println "Results:")
    (println "  Status:" (name status))
    (println "  Listings found:" (count listings))
    (println "  Duration:" (format "%.1f sec" (/ duration-ms 1000.0)))
    (println "  Pre-existing patterns:" (count pre-patterns))
    (println "  New patterns learned:" new-patterns)

    (when (and (seq listings) (<= (count listings) 3))
      (println "  Sample listings:")
      (doseq [l (take 3 listings)]
        (println "    -" (:title l "?") "|" (:price l "?") "|" (:address l "?"))))

    (when-let [error (:error result)]
      (println "  Error:" error))

    ;; Return result map
    {:domain domain
     :url url
     :headed headed
     :status status
     :listings-count (count listings)
     :duration-ms duration-ms
     :patterns-before (count pre-patterns)
     :patterns-after (count post-patterns)
     :new-patterns new-patterns
     :sample (first listings)
     :error (:error result)}))

;; =============================================================================
;; Batch Tests
;; =============================================================================

(defn test-easy-sites!
  "Test all easy (headless) sites."
  [env]
  (println)
  (println (apply str (repeat 70 "=")))
  (println "PHASE A: Testing Easy Sites (Headless)")
  (println (apply str (repeat 70 "=")))

  (let [results (mapv #(test-single-site! env %) easy-sites)]
    (println)
    (println "Phase A Summary:")
    (doseq [r results]
      (println (format "  %-15s %10s %3d listings %2d new patterns"
                       (:domain r)
                       (name (:status r))
                       (:listings-count r)
                       (:new-patterns r))))
    results))

(defn test-protected-sites!
  "Test all protected (headed mode) sites."
  [env]
  (println)
  (println (apply str (repeat 70 "=")))
  (println "PHASE B: Testing Protected Sites (Headed Mode)")
  (println (apply str (repeat 70 "=")))

  (let [results (mapv #(test-single-site! env %) protected-sites)]
    (println)
    (println "Phase B Summary:")
    (doseq [r results]
      (println (format "  %-15s %10s %3d listings %2d new patterns"
                       (:domain r)
                       (name (:status r))
                       (:listings-count r)
                       (:new-patterns r))))
    results))

(defn run-all-tests!
  "Run full test suite on all 8 sites."
  [env]
  (let [start-time (System/currentTimeMillis)
        easy-results (test-easy-sites! env)
        protected-results (test-protected-sites! env)
        all-results (concat easy-results protected-results)
        total-time (- (System/currentTimeMillis) start-time)]

    ;; Print final summary
    (println)
    (println (apply str (repeat 70 "=")))
    (println "FINAL SUMMARY")
    (println (apply str (repeat 70 "=")))

    (doseq [r all-results]
      (println (format "%-15s %8s %4d listings %2d patterns %6.1fs"
                       (:domain r)
                       (name (:status r))
                       (:listings-count r)
                       (:new-patterns r)
                       (/ (:duration-ms r) 1000.0))))

    (println)
    (println "Totals:")
    (println "  Sites tested:" (count all-results))
    (println "  Successful:" (count (filter #(= :success (:status %)) all-results)))
    (println "  Partial:" (count (filter #(= :partial (:status %)) all-results)))
    (println "  Failed:" (count (filter #(#{:failed :error} (:status %)) all-results)))
    (println "  Total listings:" (reduce + (map :listings-count all-results)))
    (println "  Total new patterns:" (reduce + (map :new-patterns all-results)))
    (println "  Total time:" (format "%.1f min" (/ total-time 60000.0)))

    ;; Reset browser to headless
    (browser/set-headed-mode! false)
    (browser/close {:all true})

    all-results))

;; =============================================================================
;; Pattern Analysis
;; =============================================================================

(defn analyze-learned-patterns
  "Analyze all patterns learned during testing."
  [ctx]
  (require '[ai.obney.orc.ontology.core.read-models :as rm])

  (println "Learned Patterns by Site:")
  (println (apply str (repeat 60 "-")))

  (doseq [site all-sites]
    (let [patterns ((resolve 'ai.obney.orc.ontology.core.read-models/get-site-patterns)
                    ctx (:domain site))]
      (println)
      (println (:domain site) "-" (count patterns) "patterns")
      (doseq [p patterns]
        (println "  Type:" (:pattern-type p))
        (println "  Data:" (:pattern-data p))
        (println "  Confidence:" (:confidence p))
        (println)))))

(defn verify-pattern-reuse
  "Run a site twice and verify the second run uses learned patterns."
  [env site]
  (println "Testing pattern reuse for" (:domain site))
  (println)

  ;; First run - should learn patterns
  (println "Run 1 (learning):")
  (let [r1 (test-single-site! env site)]
    (println "  Patterns after run 1:" (:patterns-after r1))

    (Thread/sleep 2000)

    ;; Second run - should reuse patterns
    (println)
    (println "Run 2 (reusing patterns):")
    (let [r2 (test-single-site! env site)]
      (println "  Patterns at start:" (:patterns-before r2))
      (println "  Did faster? Run1:" (:duration-ms r1) "ms, Run2:" (:duration-ms r2) "ms")

      {:run1 r1
       :run2 r2
       :patterns-reused (pos? (:patterns-before r2))
       :faster-second-run (< (:duration-ms r2) (:duration-ms r1))})))

;; =============================================================================
;; Cleanup
;; =============================================================================

(defn cleanup!
  "Clean up after testing."
  [env]
  (browser/set-headed-mode! false)
  (browser/close {:all true})
  (println "Cleanup complete."))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; ====== SETUP ======
  (def env (setup!))
  (def ctx (:ctx env))

  ;; ====== TEST SINGLE SITE ======
  (test-single-site! env {:domain "craigslist.org"
                          :url "https://sfbay.craigslist.org/search/apa"
                          :headed false})

  ;; ====== TEST EASY SITES (headless) ======
  (test-easy-sites! env)

  ;; ====== TEST PROTECTED SITES (headed) ======
  (test-protected-sites! env)

  ;; ====== RUN ALL TESTS ======
  (def results (run-all-tests! env))

  ;; ====== ANALYZE PATTERNS ======
  (analyze-learned-patterns ctx)

  ;; ====== VERIFY PATTERN REUSE ======
  (verify-pattern-reuse env (first easy-sites))

  ;; ====== CLEANUP ======
  (cleanup! env)
  )
