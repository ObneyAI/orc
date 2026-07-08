(ns el1a-convergence-proto
  "THROWAWAY — EL-1a convergence prototype (ADR 0015, emergence loop).
   NOT production code. Proves the scatter bug + the two-axis fix on real
   grain + real ColBERT.

   The bug (verified): classify-task queries ONLY :granularity
   :tree-fingerprint; search-descriptions hard-filters to that granularity.
   So a recorded :tree-class description is indexed-but-UNREACHABLE -> a
   similar task can't match it -> fresh-mints a new random-uuid -> scatter.

   The fix under test: classify-task ALSO retrieves :tree-class candidates
   (granularity SET #{:tree-fingerprint :tree-class}).

   This probe records a :tree-class description (stand-in for a previously
   fresh-minted-then-described class), rebuilds the index, then:
     BEFORE  — search :tree-fingerprint only       -> recorded class ABSENT
               classify-task on a similar task      -> fresh-mint
     AFTER   — search #{:tree-fingerprint :tree-class} -> recorded class PRESENT
               classify-task on a similar task      -> MATCHES (not a mint)

   Run (venv read-only from orc-main; -J-D BEFORE -M:dev):
     OPENROUTER_API_KEY=... \\
     clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
             -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
             -M:dev -m el1a-convergence-proto"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; A stable seed UUID standing in for a previously fresh-minted tree-class.
(def recorded-class-id #uuid "deadbeef-0000-4000-8000-000000000001")

;; The description body the consolidator would have recorded for this class
;; (the substrate R-Inject reads). :summary is the field embedded for retrieval.
;; Body MUST satisfy the description-body schema (interface.schemas) — all
;; the structured fields are REQUIRED, else process-command rejects the
;; command and emits no event. (Rule-out-the-harness: a partial body here
;; silently no-ops the record, which would look like a retrieval miss.)
(def recorded-class-body
  {:summary (str "Task class for adding a third-party JSON serialization "
                 "dependency to a Clojure project's deps.edn and wiring it "
                 "into an export writer so a report map is serialized to a "
                 ".json file on disk. Dependency-wiring + serialization shape.")
   :scope :tree-class
   :capabilities ["Add a dependency to deps.edn"
                  "Wire a JSON serializer into an export writer"
                  "Serialize a report map to a .json file"]
   :strengths [{:trait "Handles deps.edn dependency additions cleanly"
                :confidence 0.9 :evidence-count 3}]
   :weaknesses []
   :representative-uses ["Add cheshire to deps.edn and serialize the report to JSON"]
   :avoid-when []
   :version 1
   :consolidated-from-event-count 3})

;; The instruction whose signature is SIMILAR to the recorded class — this is
;; the E4 "wire-dependency" task verbatim, so convergence is measured on the
;; same task the regression gate uses.
(def similar-instruction
  "Add the cheshire JSON dependency to deps.edn and use it in the export writer to serialize the report map to a .json file.")

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(def classifier-intent
  "Classify this task into its tree-class: the recurring structural pattern of work it represents.")

(defn target-id->uuid [tid]
  (when tid (try (if (uuid? tid) tid (java.util.UUID/fromString (str tid))) (catch Throwable _ nil))))

(defn results->ids [results]
  (->> results
       (map #(-> % :document-metadata :target-id target-id->uuid))
       (remove nil?)
       set))

(defn class-present? [results]
  (contains? (results->ids results) recorded-class-id))

(defn -main [& _]
  (println "=== EL-1a CONVERGENCE PROTOTYPE (ADR 0015) ===")
  (println "venv  :" (System/getProperty "colbert.venv.path"))
  (println "bridge:" (System/getProperty "colbert.bridge.script"))
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
          sig (build-sig similar-instruction)]

      ;; ====================================================================
      ;; 0. RULE OUT THE HARNESS FIRST
      ;; ====================================================================
      (println "\n############ 0. RULE-OUT-HARNESS ############")
      (let [idxs (filter #(= "ontology-descriptions" (:index-name %)) (colbert/list-indexes ctx))]
        (println "ontology-descriptions indexes built:" (count idxs)))
      ;; Prove :tree-fingerprint pool returns non-zero hits, right granularity.
      (let [tf (ont/search-descriptions ctx
                 {:query sig :granularity :tree-fingerprint
                  :rerank-with-intent classifier-intent :k 5})]
        (println ":tree-fingerprint hits:" (count tf)
                 " all-granularity-tf? ="
                 (and (seq tf) (every? #(= :tree-fingerprint (-> % :document-metadata :granularity)) tf))))

      ;; ====================================================================
      ;; 1. BEFORE — no recorded class yet; classify-task on similar task
      ;; ====================================================================
      (println "\n############ 1. BEFORE recording the class ############")
      (let [pt (ont/classify-task ctx {:task-signature sig :threshold 0.7 :walk-down? false})]
        (println "classify-task (pre-record): mint?=" (:was-fresh-mint? pt)
                 " assigned=" (:assigned-tree-id pt)
                 " conf=" (double (or (:confidence pt) 0.0))))

      ;; ====================================================================
      ;; 2. RECORD a :tree-class description + rebuild the index
      ;; ====================================================================
      (println "\n############ 2. RECORD :tree-class + rebuild index ############")
      (let [res (cp/process-command
                  (assoc ctx :command
                         {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :ontology/record-tree-class-description
                          :target-id recorded-class-id
                          :body recorded-class-body}))]
        (println "record command result events:" (count (:command-result/events res))
                 " error?:" (pr-str (:command-result/error res))))
      (Thread/sleep 500)
      ;; Confirm it projected into the descriptions read-model under :tree-class.
      (let [desc (ont/get-description ctx :tree-class recorded-class-id)]
        (println "get-description :tree-class ->" (boolean desc)
                 " summary-prefix:" (some-> desc :summary (subs 0 (min 60 (count (:summary desc)))))))
      ;; force-rebuild! (NOT maybe-rebuild!): a single record event is below
      ;; the threshold-10 gate, so maybe-rebuild! is a no-op and the class
      ;; would never reach the index (a harness trap that masquerades as a
      ;; retrieval miss). force-rebuild! bypasses the gate.
      (println "Force-rebuilding index to include the new :tree-class description...")
      (ont-tp/force-rebuild! ctx)
      (Thread/sleep 1000)

      ;; ====================================================================
      ;; 3. THE BUG — :tree-fingerprint-only search still cannot see it
      ;; ====================================================================
      (println "\n############ 3. BUG: :tree-fingerprint-only retrieval ############")
      (let [tf (ont/search-descriptions ctx
                 {:query sig :granularity :tree-fingerprint
                  :rerank-with-intent classifier-intent :k 5})]
        (println ":tree-fingerprint search hits:" (count tf)
                 " recorded-class-present? =" (class-present? tf)
                 "  <-- expect FALSE (indexed-but-unreachable)"))
      (let [pt (ont/classify-task ctx {:task-signature sig :threshold 0.7 :walk-down? false})]
        (println "classify-task (current code, post-record): mint?=" (:was-fresh-mint? pt)
                 " assigned=" (:assigned-tree-id pt)
                 " matched-recorded? =" (= recorded-class-id (:assigned-tree-id pt))
                 "  <-- expect mint?=true / matched-recorded?=false (SCATTER)"))

      ;; ====================================================================
      ;; 4. THE FIX — two-axis retrieval makes the class a candidate
      ;; ====================================================================
      (println "\n############ 4. FIX: #{:tree-fingerprint :tree-class} retrieval ############")
      ;; Probe the fix DIRECTLY through search-descriptions with a granularity
      ;; SET. (After the source change lands, classify-task will pass this set
      ;; internally; here we prove the retrieval layer surfaces the class.)
      (let [merged (ont/search-descriptions ctx
                     {:query sig :granularity #{:tree-fingerprint :tree-class}
                      :rerank-with-intent classifier-intent :k 5})]
        (println "two-axis search hits:" (count merged)
                 " recorded-class-present? =" (class-present? merged)
                 "  <-- expect TRUE")
        (println "granularities returned:"
                 (pr-str (frequencies (map #(-> % :document-metadata :granularity) merged))))
        (doseq [r merged]
          (let [uid (target-id->uuid (-> r :document-metadata :target-id))]
            (println (format "   - %-14s target=%s fit=%s%s"
                             (str (-> r :document-metadata :granularity))
                             (if uid (subs (str uid) 0 8) "?")
                             (some-> (:fitness-score r) double)
                             (if (= recorded-class-id uid) "   <== RECORDED CLASS" "")))))
        (let [top (first merged)
              top-uid (target-id->uuid (-> top :document-metadata :target-id))]
          (println "TOP-1 is the recorded class? =" (= recorded-class-id top-uid)
                   " fit=" (some-> (:fitness-score top) double)
                   " above-0.7? =" (>= (or (:fitness-score top) 0.0) 0.7))))

      ;; ====================================================================
      ;; 5. REACHABILITY PROBE — is the recorded class in the CANDIDATE POOL?
      ;;    (EL-1a acceptance is reachability/candidacy, NOT winning the rank
      ;;    — winning is EL-2's job. Prove the class is no longer
      ;;    indexed-but-unreachable.)
      ;; ====================================================================
      (println "\n############ 5. REACHABILITY: recorded class in candidate pool ############")
      ;; 5a. RAW ColBERT (no rerank), high k, two-axis filter — does the
      ;;     recorded class survive the granularity filter at all?
      (let [raw-tf (ont/search-descriptions ctx
                     {:query sig :granularity :tree-fingerprint :k 30})
            raw-2ax (ont/search-descriptions ctx
                      {:query sig :granularity #{:tree-fingerprint :tree-class} :k 30})]
        (println "raw :tree-fingerprint-only (k=30): recorded-class-present? ="
                 (class-present? raw-tf) "  <-- expect FALSE (the bug)")
        (println "raw two-axis (k=30):              recorded-class-present? ="
                 (class-present? raw-2ax) "  <-- expect TRUE (the fix)"
                 " hits=" (count raw-2ax)
                 " tree-class-hits=" (count (filter #(= :tree-class (-> % :document-metadata :granularity)) raw-2ax))))
      ;; 5b. Query with the recorded class's OWN summary text — it must be the
      ;;     clear top :tree-class match (proves identity + content reachable).
      (let [self-q (:summary recorded-class-body)
            self (ont/search-descriptions ctx
                   {:query self-q :granularity #{:tree-fingerprint :tree-class}
                    :rerank-with-intent classifier-intent :k 5})]
        (println "\nself-query (recorded class's own summary) two-axis top-5:")
        (doseq [r self]
          (let [uid (target-id->uuid (-> r :document-metadata :target-id))]
            (println (format "   - %-14s target=%s fit=%s%s"
                             (str (-> r :document-metadata :granularity))
                             (if uid (subs (str uid) 0 8) "?")
                             (some-> (:fitness-score r) double)
                             (if (= recorded-class-id uid) "   <== RECORDED CLASS" "")))))
        (println "recorded-class-present-in-self-query? =" (class-present? self)
                 "  <-- expect TRUE")
        (let [rank (first (keep-indexed
                            (fn [i r] (when (= recorded-class-id
                                               (target-id->uuid (-> r :document-metadata :target-id)))
                                        (inc i)))
                            self))]
          (println "recorded-class rank in self-query =" (or rank "absent"))))

      (println "\n=== DONE ==="))
    (catch Throwable t
      (println "ERROR:" (.getMessage t))
      (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
