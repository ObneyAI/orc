(ns ai.obney.orc.ontology.r07-investigation-behavioral-seed-test
  "R07 — New behavioral seed (Investigation) addressing the R05e
   live-verify cluster of 4 fresh-mints in the investigative/forensic/
   diagnostic semantic (code-002 / code-003 / code-004 / conv-004).

   Covers:
   - investigation-behavior-id resolves to a stable UUID derived from
     'seed:behavior:investigation'
   - investigation-behavior-seed body validates against description-body
   - every strength + weakness entry is principle-shaped
   - every :recommended-pattern snippet passes rlm-dsl/rlm-dsl->orc-dsl
     (the R06 floor — uses :fn not :code, :items not :from, body OUTSIDE
     :map-each opts)
   - :composes-into includes the 3 abstract structural patterns
   - :scope :behavioral-subtree + :parent-behavior nil (top-level)
   - seed appears in all-behavioral-subtree-seeds registry
   - end-to-end: after seed-all! + R05a processor drive, the concept
     graph contains behavioral-subtree:<investigation-id> with the
     expected behavior:composes-into edges to the 3 abstract patterns"
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [seed-descriptions :as seeds]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r07-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; RED #1 — stable UUID for the new seed
;; =============================================================================

(deftest investigation-behavior-id-is-stable-uuid
  (testing "investigation-behavior-id is a UUID derived from the stable seed string"
    (is (uuid? seeds/investigation-behavior-id)
        "investigation-behavior-id resolves to a UUID")
    (is (= seeds/investigation-behavior-id
           (java.util.UUID/nameUUIDFromBytes (.getBytes "seed:behavior:investigation" "UTF-8")))
        "UUID is derived deterministically from 'seed:behavior:investigation' so identity survives JVM restarts + JSON round-trips")))

;; =============================================================================
;; RED #2 — body validates against description-body schema
;; =============================================================================

(deftest investigation-seed-body-validates
  (let [body (:body seeds/investigation-behavior-seed)]
    (testing "body validates against the description-body schema"
      (is (m/validate ontology-schemas/description-body body)
          "Investigation's body is well-formed (additive only; same shape as R05a's 11)"))

    (testing "scope is :behavioral-subtree"
      (is (= :behavioral-subtree (:scope body))))

    (testing "top-level competency: parent-behavior is nil/absent"
      (is (nil? (:parent-behavior body))))

    (testing "target-id matches the stable UUID"
      (is (= seeds/investigation-behavior-id
             (:target-id seeds/investigation-behavior-seed))))))

;; =============================================================================
;; RED #3 — every strength + weakness entry is principle-shaped
;; =============================================================================

(deftest investigation-seed-entries-are-principle-shaped
  (let [body (:body seeds/investigation-behavior-seed)]
    (testing "every strength entry passes principle-shaped?"
      (is (seq (:strengths body)) "at least one strength entry")
      (doseq [s (:strengths body)]
        (is (seeds/principle-shaped? s)
            (str "strength is principle-shaped: " (pr-str (:trait s))))))

    (testing "every weakness entry passes principle-shaped?"
      (is (seq (:weaknesses body)) "at least one weakness entry")
      (doseq [w (:weaknesses body)]
        (is (seeds/principle-shaped? w)
            (str "weakness is principle-shaped: " (pr-str (:trait w))))))))

;; =============================================================================
;; RED #4 — every recommended-pattern snippet passes rlm-dsl/rlm-dsl->orc-dsl
;;            (R06 floor — uses :fn not :code, :items not :from, body OUTSIDE :map-each opts)
;; =============================================================================

(deftest investigation-seed-snippets-validate-via-rlm-dsl
  (let [body (:body seeds/investigation-behavior-seed)
        snippets (->> (concat (:strengths body) (:weaknesses body))
                      (keep :recommended-pattern)
                      (filter string?))]
    (testing "at least one recommended-pattern snippet exists"
      (is (seq snippets)
          "Investigation publishes at least one principle-pattern snippet"))

    (testing "every recommended-pattern snippet parses + transforms"
      (doseq [s snippets]
        (is (true? (seeds/snippet-validates? s))
            (str "snippet validates: " s))))))

;; =============================================================================
;; RED #5 — :composes-into references the 3 abstract structural patterns
;; =============================================================================

(deftest investigation-composes-into-3-abstract-patterns
  (let [body (:body seeds/investigation-behavior-seed)
        ci (set (:composes-into body))]
    (testing "composes-into is non-empty"
      (is (seq ci) "Investigation declares at least one structural shell"))

    (testing "composes-into includes chunked-extraction-fp (large-evidence investigation)"
      (is (contains? ci seeds/chunked-extraction-fp)
          "Investigation composes into chunked-extraction for large log/codebase investigations"))

    (testing "composes-into includes fallback-recovery-fp (try-another-hypothesis path)"
      (is (contains? ci seeds/fallback-recovery-fp)
          "Investigation composes into fallback-recovery when one hypothesis fails and another must be tried"))

    (testing "composes-into includes sequential-pipeline-fp (canonical hypothesis chain)"
      (is (contains? ci seeds/sequential-pipeline-fp)
          "Investigation composes into sequential-pipeline for the canonical hypothesis → evidence → recommendation chain"))))

;; =============================================================================
;; RED #6 — summary is self-contained (no file paths, no slice names, no SHAs)
;; =============================================================================

(deftest investigation-summary-is-self-contained
  (let [summary (-> seeds/investigation-behavior-seed :body :summary)]
    (testing "summary is a non-empty string"
      (is (string? summary))
      (is (> (count summary) 100) "summary is substantive (not a one-liner)"))

    (testing "summary has no file-path leakage"
      (is (not (re-find #"\bcomponents/" summary)))
      (is (not (re-find #"\bdevelopment/" summary)))
      (is (not (re-find #"\.clj\b" summary))))

    (testing "summary has no slice-name leakage"
      (is (not (re-find #"\bR0[0-9]\b" summary)) "no R0x slice references")
      (is (not (re-find #"\bC-2[a-z]?-[0-9]\b" summary)) "no C-2x-n references"))

    (testing "summary disambiguates from Research and Analysis"
      ;; principle-shaped disambiguation: the summary mentions both neighbors
      ;; by name and points to the boundary. Not phrase-matching production
      ;; behavior; this is verifying the AUTHORED text honors the discipline.
      (is (re-find #"(?i)Research" summary)
          "summary names Research as a neighbor for disambiguation")
      (is (re-find #"(?i)Analysis" summary)
          "summary names Analysis as a neighbor for disambiguation"))))

;; =============================================================================
;; RED #7 — registry inclusion: count + identity
;; =============================================================================

(deftest investigation-seed-in-registry
  (testing "all-behavioral-subtree-seeds count grows from 11 to 12"
    (is (= 12 (count seeds/all-behavioral-subtree-seeds))
        "R05a's 11 + R07's 1 Investigation seed"))

  (testing "investigation-behavior-seed is present in the registry"
    (is (some #(= seeds/investigation-behavior-id (:target-id %))
              seeds/all-behavioral-subtree-seeds)
        "Investigation seed registered for seed-all! to emit")))

;; =============================================================================
;; RED #8 — end-to-end: concept-graph projection after seed-all! + R05a processor
;; =============================================================================

(defn- drive-behavioral-subtree-processor! [ctx]
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e)))))

(deftest investigation-concept-projects-with-3-edges
  (with-test-ctx [ctx]
    (seeds/seed-all! ctx)
    (drive-behavioral-subtree-processor! ctx)
    (let [concepts (rmp/project ctx :ontology/concepts)
          investigation-uri (str "behavioral-subtree:" seeds/investigation-behavior-id)
          ci (get-in concepts [investigation-uri :composes-into] #{})]

      (testing "behavioral-subtree:<investigation-id> concept exists in graph"
        (is (contains? concepts investigation-uri)
            "R05a processor projected Investigation's concept after seed-all!"))

      (testing "concept has :scope :behavioral-subtree"
        (is (= :behavioral-subtree (get-in concepts [investigation-uri :scope]))))

      (testing "behavior:composes-into → chunked-extraction-fp"
        (is (contains? ci (str "tree-class:" seeds/chunked-extraction-fp))
            "edge to chunked-extraction-fp projected"))

      (testing "behavior:composes-into → fallback-recovery-fp"
        (is (contains? ci (str "tree-class:" seeds/fallback-recovery-fp))
            "edge to fallback-recovery-fp projected"))

      (testing "behavior:composes-into → sequential-pipeline-fp"
        (is (contains? ci (str "tree-class:" seeds/sequential-pipeline-fp))
            "edge to sequential-pipeline-fp projected"))

      (testing "the 11 R05a behavioral concepts are STILL present (no regression)"
        (doseq [bh-sym '[research-behavior-id extraction-behavior-id analysis-behavior-id
                         synthesis-behavior-id ideation-behavior-id design-behavior-id
                         critique-behavior-id validation-behavior-id code-building-behavior-id
                         transformation-behavior-id classification-behavior-id]]
          (let [bh-id @(ns-resolve 'seed-descriptions bh-sym)]
            (is (contains? concepts (str "behavioral-subtree:" bh-id))
                (str bh-sym " concept preserved after R07's additive landing"))))))))
