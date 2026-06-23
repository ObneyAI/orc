(ns ai.obney.orc.ontology.v20-full-extraction-test
  "V20 — deterministic full-extraction apply-step tests.

   The builder designs an extraction TRANSFORM on a sample; the deterministic
   skeleton applies it over the FULL source via V19's stream-all. These tests
   verify the COVERAGE GUARANTEE that does NOT depend on the model looping:

     - A model-authored transform applied over a MULTI-WINDOW source produces
       drafts for ALL rows (coverage == row count minus surfaced skips).
     - A transform that throws on some rows → those rows skipped + COUNTED +
       SURFACED; the rest extract; no abort.
     - The full draft set compiles with V18 referential integrity intact
       (every endpoint resolves).
     - Entity-as-node: a source whose rows describe attribute-bearing entities
       yields concept NODES for them (not edge-only).

   Discipline: the apply-step is exercised through the public
   `rlm-discovery/apply-extraction-transform!`; ingest through the public
   `ontology/compile-discovery-source!`. Real Grain in-memory event store. The
   source is a REAL csv file (generated to a temp path) streamed via the REAL
   V19 stream-all — NO mocked source, NO mocked stream. The transform is REAL
   Clojure source eval'd in the SCI sandbox (no hand-faked fn). Domain-agnostic
   fixtures — no education/CIP/SOC specifics."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.orc-service.core.source-tools :as source-tools]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Test context
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v20-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

;; =============================================================================
;; Multi-window CSV fixture (domain-agnostic — abstract "entity"/"category")
;; =============================================================================
;; 250 data rows > the 100-row per-call ceiling, so stream-all spans 3 windows.
;; Each row describes an attribute-bearing ENTITY belonging to a CATEGORY — a
;; generic shape, no domain baked in.

(def ^:private fixture-row-count 250)

(defn- write-fixture-csv!
  "Write a temp CSV with `n` data rows + a header. Each row: entity_id,
   category_code, score. Returns the absolute path. `bad-rows` (a set of 1-based
   row numbers) get a NON-NUMERIC score so a numeric-coercing transform throws on
   exactly those rows (used to test per-row error counting)."
  ([n] (write-fixture-csv! n #{}))
  ([n bad-rows]
   (let [f (java.io.File/createTempFile "v20-fixture" ".csv")
         path (.getAbsolutePath f)]
     (.deleteOnExit f)
     (with-open [w (io/writer f)]
       (.write w "entity_id,category_code,score\n")
       (doseq [i (range 1 (inc n))]
         (let [score (if (contains? bad-rows i) "N/A" (str (* i 10)))]
           (.write w (str "E" i "," "C" (mod i 7) "," score "\n")))))
     path)))

;; A REAL model-shaped transform authored as Clojure SOURCE (eval'd in the
;; sandbox). Entity-as-node: each row's entity is a NODE bearing :score; each
;; distinct category is a NODE; an edge links entity -> category. clojure.core
;; only (matching the sandbox the builder validates in).
;; CSV row keys are STRINGS (the csv tool returns header-keyed maps), so the
;; transform reads (get row "col") — the same convention the discovery prompt
;; documents for csv sources (sql/excel keys are keywords).
(def ^:private entity-as-node-transform
  "(fn [row]
     (let [eid (get row \"entity_id\")
           cat (get row \"category_code\")
           score (get row \"score\")]
       {:concept-drafts
        [{:uri (str \"entity:\" eid)
          :label (str \"Entity \" eid)
          :scope :custom
          :attributes {:score score}
          :evidence [{:source \"entity_id\" :quote (str eid)}]}
         {:uri (str \"category:\" cat)
          :label (str \"Category \" cat)
          :scope :custom
          :evidence [{:source \"category_code\" :quote (str cat)}]}]
        :relationship-drafts
        [{:source-uri (str \"entity:\" eid)
          :target-uri (str \"category:\" cat)
          :predicate \"inCategory\"
          :confidence-class :extracted
          :evidence [{:source \"row\" :quote (str cat)}]}]}))")

;; A transform that THROWS on rows whose score is non-numeric. Demonstrates
;; per-row error counting (a transform that works on most rows but throws on
;; some). clojure.core only — no regex, no interop: a numeric string is one
;; whose every char is a digit (checked via the digit set).
(def ^:private throws-on-bad-rows-transform
  "(fn [row]
     (let [eid (get row \"entity_id\")
           score (str (get row \"score\"))
           digits (set (seq \"0123456789\"))
           numeric? (and (seq score) (every? digits (seq score)))]
       (when-not numeric?
         (throw (ex-info \"non-numeric score\" {:score score})))
       {:concept-drafts
        [{:uri (str \"entity:\" eid)
          :label (str \"Entity \" eid)
          :scope :custom
          :attributes {:score score}
          :evidence [{:source \"entity_id\" :quote (str eid)}]}]
        :relationship-drafts []}))")

;; =============================================================================
;; Coverage: ALL rows across multiple windows
;; =============================================================================

(deftest apply-transform-covers-all-rows-across-windows
  (testing "A model-authored transform applied over a MULTI-WINDOW csv source
            (250 rows > the 100-row per-call ceiling) produces drafts for EVERY
            row — coverage is comprehensive, not one window. The per-call ceiling
            is preserved (coverage comes from iterating windows)."
    (let [path (write-fixture-csv! fixture-row-count)
          descriptor {:name :fixture :type :csv :path path}
          result (rlm-discovery/apply-extraction-transform!
                  {:descriptor descriptor
                   :transform-source entity-as-node-transform})]
      (is (= fixture-row-count (:rows-streamed result))
          "Every data row was streamed + transformed (no one-window cutoff)")
      (is (= fixture-row-count (:rows-ok result))
          "Every row produced drafts (zero errors on a clean source)")
      (is (= 0 (:rows-errored result)))
      (is (> (:windows result) 1)
          "More than one window — coverage genuinely spans the ceiling")
      ;; 2 concept-drafts per row (entity + category) = coverage == count.
      (is (= (* 2 fixture-row-count) (count (:concept-drafts result)))
          "Two concept-drafts per row across the full source")
      (is (= fixture-row-count (count (:relationship-drafts result)))
          "One edge per row across the full source"))))

;; =============================================================================
;; Per-row errors: caught + counted + surfaced, no abort
;; =============================================================================

(deftest apply-transform-counts-per-row-errors-without-aborting
  (testing "A transform that throws on SOME rows skips + counts + surfaces them;
            the rest extract; the source is NOT aborted (Disciplines #4/#5 — no
            false green, no silent swallow)."
    (let [bad #{10 50 123 200 249}
          path (write-fixture-csv! fixture-row-count bad)
          descriptor {:name :fixture :type :csv :path path}
          result (rlm-discovery/apply-extraction-transform!
                  {:descriptor descriptor
                   :transform-source throws-on-bad-rows-transform})]
      (is (= fixture-row-count (:rows-streamed result))
          "Every row was attempted — the bad rows did NOT abort the stream")
      (is (= (count bad) (:rows-errored result))
          "Exactly the bad rows are counted as errors")
      (is (= (- fixture-row-count (count bad)) (:rows-ok result))
          "Every good row still extracted")
      (is (= (- fixture-row-count (count bad)) (count (:concept-drafts result)))
          "Drafts produced for every good row (one per row in this transform)")
      (is (seq (:errors-sample result))
          "The per-row errors are SURFACED (sampled), not silent")
      (is (every? #(str/includes? (str (:error %)) "non-numeric")
                  (:errors-sample result))
          "The surfaced error carries the real root-cause message"))))

(deftest apply-transform-high-failure-rate-surfaces-loudly
  (testing "Adversarial: a transform that fails MOST rows is not a success — the
            error COUNT surfaces the high failure rate (the caller can gate on
            it; no false green)."
    (let [;; Make EVERY row bad for the numeric transform.
          path (write-fixture-csv! 120 (set (range 1 121)))
          descriptor {:name :fixture :type :csv :path path}
          result (rlm-discovery/apply-extraction-transform!
                  {:descriptor descriptor
                   :transform-source throws-on-bad-rows-transform})]
      (is (= 120 (:rows-streamed result)))
      (is (= 120 (:rows-errored result))
          "All 120 rows errored — the count makes the total failure loud")
      (is (= 0 (:rows-ok result)))
      (is (empty? (:concept-drafts result))
          "No fabricated drafts from a transform that fails every row"))))

;; =============================================================================
;; Bad-shape transform result is a counted row error (not coerced)
;; =============================================================================

(deftest apply-transform-non-map-result-is-counted-error
  (testing "A transform whose per-row result is not a map (a bad shape) is a
            COUNTED row error — not silently coerced to empty."
    (let [path (write-fixture-csv! 30)
          descriptor {:name :fixture :type :csv :path path}
          result (rlm-discovery/apply-extraction-transform!
                  {:descriptor descriptor
                   :transform-source "(fn [row] [:not :a :map])"})]
      (is (= 30 (:rows-streamed result)))
      (is (= 30 (:rows-errored result))
          "Every row's non-map result is counted as an error")
      (is (= 0 (:rows-ok result)))
      (is (empty? (:concept-drafts result))))))

;; =============================================================================
;; Loud failures (Disciplines #5)
;; =============================================================================

(deftest apply-transform-rejects-non-fn-source-loudly
  (testing "A transform source that does not evaluate to a fn fails LOUDLY —
            no silent fallback."
    (let [path (write-fixture-csv! 5)
          descriptor {:name :fixture :type :csv :path path}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"must evaluate to a fn"
            (rlm-discovery/apply-extraction-transform!
             {:descriptor descriptor
              :transform-source "{:not :a :fn 1}"}))))))

(deftest apply-transform-rejects-blank-source-loudly
  (testing "A blank / nil transform source fails LOUDLY."
    (let [path (write-fixture-csv! 5)
          descriptor {:name :fixture :type :csv :path path}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":transform-source must be a non-blank string"
            (rlm-discovery/apply-extraction-transform!
             {:descriptor descriptor :transform-source "   "}))))))

;; =============================================================================
;; V18 integration: the full draft set compiles with referential integrity
;; =============================================================================

(deftest full-extraction-compiles-with-referential-integrity
  (testing "The FULL collected draft set flows through the existing compile +
            V18 referential integrity: every relationship endpoint resolves
            (entity nodes + category nodes were all minted), zero unresolved."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            path (write-fixture-csv! fixture-row-count)
            descriptor {:name :fixture :type :csv :path path}
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor descriptor
                     :transform-source entity-as-node-transform})
            disc-out {:status :emitted-drafts
                      :emitted-concepts (:concept-drafts result)
                      :emitted-relationships (:relationship-drafts result)
                      :emitted-axioms []
                      :rlm-trace []}
            stub (ontology/compile-discovery-source! ctx oid disc-out)
            prov (:discovery-provenance stub)]
        (is (true? (:every-edge-endpoint-resolves? prov))
            "Every edge endpoint resolves after compile (V18 intact)")
        (is (= 0 (:unresolved-endpoints prov))
            "Zero unresolved endpoints")
        ;; The transform minted every endpoint itself, so V18 auto-mints nothing.
        (is (= 0 (:implied-concepts-minted prov))
            "No implied concepts needed — the transform minted both endpoints")))))

;; =============================================================================
;; Entity-as-node: attribute-bearing entities become NODES (not edge-only)
;; =============================================================================

;; =============================================================================
;; MC-0 fix #2 — SQL selector resolution / validation against the real table list
;; =============================================================================
;; REGRESSION: the AUTHOR node emits sql table selectors inconsistently — the
;; literal string "null"/blank (→ "no such table: null"), a name with baked-in
;; quotes ("\"C2022_A\"" → FROM ""C2022_A"" syntax error), or a hallucinated name.
;; The fix RESOLVES + VALIDATES the selector against the real `list-tables` and
;; falls back to the LARGEST table for ANY selector that doesn't name a real
;; table. These tests drive apply-extraction-transform! against a STUB sql
;; tools-map (no real .db) so the selector resolution is deterministic — the
;; production source-tools-for is redef'd to return the stub.
;;
;; The stub's stream-all records the table-name it was actually asked to stream,
;; so a test can assert the BAD selector NEVER reaches stream-all (it was resolved
;; to a real table first). Pre-fix, the raw "null"/quoted string flows straight
;; through to stream-all → the captured table is the bad string (RED).

(def ^:private sql-stub-tables ["small_t" "big_t" "mid_t"])
(def ^:private sql-stub-row-counts {"small_t" 10 "mid_t" 100 "big_t" 5000})

(defn- sql-stub-tools
  "A stub sql tools-map mirroring the V19 sql source-tools surface that
   apply-extraction-transform! uses for the sql selector path: list-tables,
   count-rows, and a stream-all that RECORDS the table-name it was handed (into
   `streamed`) and returns one window of keyword-keyed rows for that table."
  [streamed]
  {'list-tables (fn [] sql-stub-tables)
   'count-rows  (fn [t]
                  (let [nm (if (map? t) (or (:name t) (:table t)) t)]
                    {:table nm :row-count (get sql-stub-row-counts nm 0)}))
   'stream-all  (fn stream-all
                  ([selector] (stream-all selector {}))
                  ([selector _opts]
                   (let [nm (if (map? selector)
                              (or (:name selector) (:table selector))
                              selector)]
                     (reset! streamed nm)
                     ;; one window of keyword-keyed rows (the V19 sql shape)
                     [{:table nm
                       :rows [{:id "1" :name "Alpha"}
                              {:id "2" :name "Beta"}]
                       :row-count 2 :offset 0}])))})

;; A transform that keys off the keyword :id/:name (the V19 sql row shape).
(def ^:private id-name-transform
  "(fn [row]
     {:concept-drafts
      [{:uri (str \"thing:\" (get row :id))
        :label (str (get row :name))
        :scope :custom
        :evidence [{:source \"id\" :quote (str (get row :id))}]}]
      :relationship-drafts []})")

(defn- resolve-sql-selector
  "Run apply-extraction-transform! with a STUB sql tools-map and return the
   table-name stream-all was actually asked to stream — i.e. the RESOLVED
   selector. `bad-selector` is the raw selector the author emitted."
  [bad-selector]
  (let [streamed (atom :never-called)]
    (with-redefs [source-tools/source-tools-for (fn [_descriptor] (sql-stub-tools streamed))]
      (let [result (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :sql :path "/tmp/does-not-exist.db"}
                     :transform-source id-name-transform
                     :selector bad-selector})]
        {:streamed @streamed :result result}))))

(deftest sql-selector-null-string-resolves-to-real-largest-table
  (testing "a literal \"null\" selector (the no-such-table:null bug) is NOT passed
            through — it resolves to a REAL table (the largest, big_t)."
    (let [{:keys [streamed result]} (resolve-sql-selector "null")]
      (is (= "big_t" streamed)
          "the bad \"null\" selector must resolve to the largest REAL table, never \"null\"")
      (is (= "big_t" (:selector result)))
      (is (pos? (count (:concept-drafts result)))
          "real rows streamed → non-empty drafts (no SQLITE no-such-table error)"))))

(deftest sql-selector-quoted-name-is-unwrapped-to-real-table
  (testing "a name with baked-in quotes (\"\\\"big_t\\\"\") is unwrapped and matched
            against the real table list — NOT passed through as a quoted string
            (which would be a FROM \"\"big_t\"\" syntax error)."
    (let [{:keys [streamed result]} (resolve-sql-selector "\"big_t\"")]
      (is (= "big_t" streamed)
          "the quoted selector must be unwrapped to the real table name")
      (is (= "big_t" (:selector result))))))

(deftest sql-selector-hallucinated-name-falls-back-to-largest-table
  (testing "a hallucinated table name (not in list-tables) falls back to the
            largest REAL table rather than erroring."
    (let [{:keys [streamed result]} (resolve-sql-selector "NONEXISTENT_TABLE")]
      (is (= "big_t" streamed)
          "a hallucinated name resolves to the largest real table")
      (is (= "big_t" (:selector result))))))

(deftest sql-selector-real-name-is-respected
  (testing "a selector that DOES name a real table is honored as-is (the fix does
            not over-correct a valid selector)."
    (let [{:keys [streamed result]} (resolve-sql-selector "mid_t")]
      (is (= "mid_t" streamed)
          "a valid real-table selector must be respected, not overridden")
      (is (= "mid_t" (:selector result))))))

;; =============================================================================
;; MC-0 fix #4 — each row carries BOTH keyword AND string keys before xform
;; =============================================================================
;; REGRESSION: the LLM author is inconsistent about row key TYPE. csv rows are
;; string-keyed; sql/excel rows are keyword-keyed. A transform that reads the
;; "wrong" key type silently gets nil for EVERY field → empty-URI/empty-label
;; drafts (a false-green: "0 errors" but garbage concepts). The fix presents each
;; row with BOTH keyword and string keys, so the transform works either way.
;;
;; Driven against a STUB tools-map yielding rows of a KNOWN key type, with a
;; transform that reads the OPPOSITE key type. Pre-fix → empty URIs (RED).

(defn- stub-csv-tools
  "A stub csv-shaped tools-map: csv stream-all is selector-less (0/1-arg). Rows
   are STRING-keyed (the real csv shape)."
  []
  {'stream-all (fn stream-all
                 ([] (stream-all {}))
                 ([_opts]
                  [{:rows [{"id" "1" "name" "Alpha"}
                           {"id" "2" "name" "Beta"}]}]))})

;; A transform that reads KEYWORD keys (:id/:name) — the "wrong" type for a
;; string-keyed csv row. With dual-key, the keyword lookups still resolve.
(def ^:private keyword-reading-transform
  "(fn [row]
     {:concept-drafts
      [{:uri (str \"thing:\" (get row :id))
        :label (str (get row :name))
        :scope :custom
        :evidence [{:source \"id\" :quote (str (get row :id))}]}]
      :relationship-drafts []})")

(deftest dual-key-keyword-transform-over-string-keyed-rows-yields-nonempty-uris
  (testing "a transform reading KEYWORD keys (:id) over STRING-keyed csv rows still
            produces non-empty URIs/labels — the dual-key presentation bridges the
            key-type mismatch. Pre-fix: every (get row :id) is nil → \"thing:\" /
            empty label (false-green)."
    (let [result (with-redefs [source-tools/source-tools-for (fn [_descriptor] (stub-csv-tools))]
                   (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :csv :path "/tmp/does-not-exist.csv"}
                     :transform-source keyword-reading-transform}))
          uris (map :uri (:concept-drafts result))
          labels (map :label (:concept-drafts result))]
      (is (= 2 (:rows-streamed result)))
      (is (= 0 (:rows-errored result)))
      (is (= #{"thing:1" "thing:2"} (set uris))
          "keyword reads resolved over string-keyed rows → real URIs, not \"thing:\"")
      (is (every? seq labels)
          "labels are non-empty (no empty-label false-green)")
      (is (not-any? #(= "thing:" %) uris)
          "no empty-suffix URI from a nil field read"))))

(defn- stub-sql-tools-for-dualkey
  "A stub sql tools-map yielding KEYWORD-keyed rows (the V19 sql shape)."
  []
  {'list-tables (fn [] ["t"])
   'count-rows  (fn [_] {:table "t" :row-count 2})
   'stream-all  (fn stream-all
                  ([_selector] (stream-all nil {}))
                  ([_selector _opts]
                   [{:table "t"
                     :rows [{:id "1" :name "Alpha"}
                            {:id "2" :name "Beta"}]
                     :row-count 2 :offset 0}]))})

;; A transform that reads STRING keys ("id"/"name") — the "wrong" type for a
;; keyword-keyed sql row. With dual-key, the string lookups still resolve.
(def ^:private string-reading-transform
  "(fn [row]
     {:concept-drafts
      [{:uri (str \"thing:\" (get row \"id\"))
        :label (str (get row \"name\"))
        :scope :custom
        :evidence [{:source \"id\" :quote (str (get row \"id\"))}]}]
      :relationship-drafts []})")

(deftest dual-key-string-transform-over-keyword-keyed-rows-yields-nonempty-uris
  (testing "the reverse mismatch: a transform reading STRING keys (\"id\") over
            KEYWORD-keyed sql rows still produces non-empty URIs — dual-key bridges
            both directions."
    (let [result (with-redefs [source-tools/source-tools-for (fn [_descriptor] (stub-sql-tools-for-dualkey))]
                   (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :sql :path "/tmp/does-not-exist.db"}
                     :transform-source string-reading-transform
                     :selector "t"}))
          uris (map :uri (:concept-drafts result))]
      (is (= 2 (:rows-streamed result)))
      (is (= 0 (:rows-errored result)))
      (is (= #{"thing:1" "thing:2"} (set uris))
          "string reads resolved over keyword-keyed rows → real URIs"))))

(deftest full-extraction-yields-entity-nodes-not-edge-only
  (testing "Entity-as-node scaffolding goal: a source whose rows describe
            attribute-bearing entities yields concept NODES for them (carrying
            their attributes), NOT merely edges between other concepts. Read back
            from the projection."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            path (write-fixture-csv! fixture-row-count)
            descriptor {:name :fixture :type :csv :path path}
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor descriptor
                     :transform-source entity-as-node-transform})
            disc-out {:status :emitted-drafts
                      :emitted-concepts (:concept-drafts result)
                      :emitted-relationships (:relationship-drafts result)
                      :emitted-axioms []
                      :rlm-trace []}
            _ (ontology/compile-discovery-source! ctx oid disc-out)
            concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            entity-nodes (filter #(str/starts-with? (str (:uri %)) "entity:") concepts)
            category-nodes (filter #(str/starts-with? (str (:uri %)) "category:") concepts)]
        (is (= fixture-row-count (count entity-nodes))
            "One entity NODE per row landed in the graph (not edge-only)")
        (is (every? #(contains? (:attributes %) :score) entity-nodes)
            "Each entity node bears its own attribute (it's a real node)")
        ;; 7 distinct categories (mod 7) — they are NODES, deduped by shared uri.
        (is (= 7 (count category-nodes))
            "Distinct categories are NODES, merged by shared canonical uri")))))
