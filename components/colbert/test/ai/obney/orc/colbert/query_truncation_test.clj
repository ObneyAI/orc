(ns ai.obney.orc.colbert.query-truncation-test
  "CC-17 — `invariant.OverlongQueriesTruncateVisibly` (specs/colbert.allium).

     for search in Searches:
         search.query_token_count > configuration.maximum_query_tokens implies
             search.truncated = true

   A query longer than the configured limit is TRUNCATED, not rejected: the
   excess word-piece tokens are discarded and retrieval proceeds against the
   prefix. Before this slice NOTHING anywhere recorded that it happened — the
   discard was invisible to every caller, and a retrieval key can never be
   matched against text the encoder never saw.

   `maximum_query_tokens` is the encoder's per-query ROW count (the spec:
   'it is also the encoder's per-query row count, so it sets the score
   ceiling'), so `query_token_count` is measured on the same scale: the rows
   the query WOULD need = its word-piece tokens + the 3 ColBERT specials
   ([CLS] [Q] [SEP]).

   Real encoder, real tokenizer, real Grain commands — no stubs on the
   measurement path."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.commands]
            [cognitect.anomalies :as anom]))

(def ^:dynamic *ctx* nil)

(defn with-ctx [f]
  (let [ctx (tu/create-test-context "colbert")]
    (try (binding [*ctx* ctx] (f))
         (finally (tu/stop-context ctx)))))

(use-fixtures :once support/with-model-path)
(use-fixtures :each with-ctx)

(defn- test-encoder []
  (encoder/get-encoder (model-store/resolve-model-dir)))

(def specials
  "[CLS] [Q] [SEP] — the 3 rows every ColBERT query sequence spends on
   structure, so the usable content budget is maximum_query_tokens - 3."
  3)

(def signature-block
  "A REAL production-shaped classifier signature: `build-task-signature`'s
   INSTRUCTION/READS/WRITES/MCP-TOOLS block. Measured over the real
   production corpus (evidence/cc17), these run 150-455 word-piece tokens."
  (str "INSTRUCTION:\n"
       "You EDIT FILES. Implement the latest user message by actually modifying "
       "files in the workspace with the tools, then verifying the result — "
       "designing or describing a change is not implementing it. Your REPL has "
       "the workspace tools bound as directly callable functions: fs/read, "
       "fs/list, apply_patch, fs/write, shell/exec. Make the edit inline in your "
       "code: read the file first, then apply the change. Then VERIFY your "
       "change by running a command that exercises the changed behaviour and "
       "confirm it passes before reporting success."
       "\n\nREADS: :session :turns :active-plan :workspace-root :user-message"
       "\nWRITES: :assistant-response"
       "\nMCP-TOOLS: shell/exec fs/read fs/write fs/list apply_patch"
       "\nBROWSER-TOOLS: (none)"))

(defn configured-limit
  "The limit the encoder ACTUALLY applies — the configured
   maximum_query_tokens, not the checkpoint's own query_maxlen. These tests
   must hold at whatever value is configured, never only at 32."
  ^long [enc]
  (encoder/resolve-maximum-query-tokens enc nil))

(defn overlong-query
  "The real signature block repeated until it EXCEEDS the configured limit, so
   the overlong probe stays non-vacuous by construction at ANY configured
   maximum_query_tokens."
  [enc]
  (let [limit (configured-limit enc)]
    (loop [s signature-block]
      (if (> (+ specials (count (encoder/encode-ids enc s))) limit)
        s
        (recur (str s "\n\n" signature-block))))))

(def documents
  ["Any dispute arising under this agreement shall be settled by binding arbitration."
   "Either party may terminate this agreement with thirty days written notice."
   "The midfielder scored twice in the final minutes of the championship match."])

;; =============================================================================
;; Cycle 1 — the invariant has NO implementation today: truncation is invisible.
;; =============================================================================

(deftest overlong-query-truncation-is-visible-on-the-encoder-seam
  (let [enc (test-encoder)
        overlong-query (overlong-query enc)
        raw (count (encoder/encode-ids enc overlong-query))
        limit (configured-limit enc)
        built (encoder/build-query-ids enc overlong-query)]
    (testing "the probe is non-vacuous by construction — it MUST exceed the limit"
      (println "  [N] overlong probe raw word-piece tokens =" raw
               "| would need rows =" (+ raw specials)
               "| encoder limit =" limit)
      (is (> (+ raw specials) limit)
          "probe must exceed the configured limit or this test proves nothing"))
    (testing "build-query-ids reports what the query WOULD have needed"
      (is (= (+ raw specials) (:query-token-count built))
          "query_token_count = word-piece tokens + [CLS] [Q] [SEP]"))
    (testing "build-query-ids reports the limit it truncated against"
      (is (= limit (:maximum-query-tokens built))))
    (testing "OverlongQueriesTruncateVisibly: over the limit implies truncated"
      (is (true? (:truncated? built))))
    (testing "the caller can tell HOW MUCH content was discarded"
      (is (= (- (+ raw specials) limit) (:discarded-token-count built))))
    (testing "truncation still TRUNCATES (not rejects) — the prefix is encoded"
      (is (= limit (count (:ids built))))
      (is (= limit (count (:attention built)))))))

(deftest overlong-query-truncation-is-visible-on-the-rerank-audit-event
  (testing "the :colbert/rerank-performed audit event carries the truncation signal"
    (let [enc (test-encoder)
          overlong-query (overlong-query enc)
          raw (count (encoder/encode-ids enc overlong-query))
          limit (configured-limit enc)
          result (tu/process-command! *ctx*
                   {:command/name :colbert/rerank
                    :query overlong-query
                    :documents documents
                    :k 3})]
      (is (not (::anom/category result)) "rerank must succeed, not reject")
      (let [event (tu/find-event result :colbert/rerank-performed)]
        (println "  [N] rerank audit truncation fields ="
                 (pr-str (select-keys event [:query-token-count
                                             :maximum-query-tokens
                                             :query-truncated?
                                             :discarded-token-count])))
        (is (= (+ raw specials) (:query-token-count event)))
        (is (= limit (:maximum-query-tokens event)))
        (is (true? (:query-truncated? event)))
        (is (= (- (+ raw specials) limit) (:discarded-token-count event)))))))

;; =============================================================================
;; Cycle 2 — the limit is real CONFIGURATION (entity-fields.IndexConfiguration,
;; value-equality.IndexConfiguration), validated positive exactly the way
;; `configuration.maximum_passage_tokens > 0` is, and it is what
;; build-query-ids ACTUALLY READS — not a hard-coded 32.
;; =============================================================================

(defn- temp-index-root ^String []
  (str (.toFile (java.nio.file.Files/createTempDirectory
                 "colbert-cc17-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(defmacro with-index-root [& body]
  `(let [prev# (System/getProperty operations/index-root-property)]
     (System/setProperty operations/index-root-property (temp-index-root))
     (try ~@body
          (finally (if prev#
                     (System/setProperty operations/index-root-property prev#)
                     (System/clearProperty operations/index-root-property))))))

(deftest maximum-query-tokens-is-configuration-not-a-hard-coded-32
  ;; One signature block (not the repeated overlong probe): this test needs a
  ;; query it can bracket from BOTH sides with limits that stay inside the
  ;; encoder's 512-position ceiling.
  (let [enc (test-encoder)
        probe signature-block
        raw (count (encoder/encode-ids enc probe))
        needed (+ raw specials)
        ;; A limit that FITS the probe. Deliberately not a multiple of the
        ;; checkpoint's 32, so nothing can accidentally pass by reading the
        ;; artifact constant.
        roomy (+ needed 7)]
    (testing "raising the configured limit stops the discard"
      (let [built (encoder/build-query-ids enc probe {:maximum-query-tokens roomy})]
        (println "  [N] configured limit =" roomy "for a query needing" needed "rows")
        (is (= roomy (:maximum-query-tokens built)))
        (is (false? (:truncated? built)))
        (is (= 0 (:discarded-token-count built)))
        (is (= roomy (count (:ids built))) "rows = the CONFIGURED limit")
        (is (= needed (count (filter #(= 1 %) (:attention built))))
            "every real token is attended; the rest is [MASK] pedestal")))
    (testing "lowering the configured limit tightens the discard"
      (let [built (encoder/build-query-ids enc probe {:maximum-query-tokens 16})]
        (is (= 16 (:maximum-query-tokens built)))
        (is (true? (:truncated? built)))
        (is (= (- needed 16) (:discarded-token-count built)))
        (is (= 16 (count (:ids built))))))))

(deftest maximum-query-tokens-is-validated-positive
  (testing "a non-positive limit is rejected at the source, never silently used"
    (doseq [bad [0 -1]]
      (let [ex (try (encoder/build-query-ids (test-encoder) "hello"
                                             {:maximum-query-tokens bad})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str "limit " bad " must throw"))
        (is (= :colbert-invalid-maximum-query-tokens (:error (ex-data ex)))))))
  (testing "a limit the encoder physically cannot carry is rejected too"
    (let [enc (test-encoder)
          positions (get-in enc [:consts :max-position-embeddings])]
      (is (pos-int? positions)
          "max_position_embeddings must be read from the checkpoint's own config.json")
      (let [ex (try (encoder/build-query-ids enc "hello"
                                             {:maximum-query-tokens (inc positions)})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :colbert-maximum-query-tokens-exceeds-model-positions
               (:error (ex-data ex))))))))

(deftest index-configuration-carries-and-governs-maximum-query-tokens
  (with-index-root
    (let [configured 48
          result (operations/create-index! {}
                   {:collection documents
                    :document-ids ["d1" "d2" "d3"]
                    :index-name "cc17-config"
                    :split-documents? false
                    :maximum-query-tokens configured})]
      (testing "entity-fields.IndexConfiguration: the field is part of the configuration"
        (is (= configured (get-in result [:config :maximum-query-tokens]))))
      (testing "value-equality.IndexConfiguration: the configuration survives the projection intact"
        (let [projected (read-models/apply-index-events
                         [{:event/type :colbert/index-created
                           :index-id (:index-id result)
                           :index-name (:index-name result)
                           :index-path (:index-path result)
                           :documents documents
                           :document-ids ["d1" "d2" "d3"]
                           :document-count 3
                           :passage-count (:num-passages result)
                           :model-name (:model-name result)
                           :config (:config result)
                           :created-at "2026-08-07T00:00:00Z"}])]
          (is (= (:config result)
                 (get-in projected [:indexes (:index-id result) :config]))
              "an IndexConfiguration is a VALUE — projecting it must not change it")))
      (testing "the index's OWN configuration is what its searches truncate against"
        (with-redefs [read-models/get-index
                      (fn [_ id] (when (= id (:index-id result))
                                   {:index-id id
                                    :index-path (:index-path result)
                                    :config (:config result)
                                    :status :active}))]
          (let [report (operations/query-truncation
                        {} {:query (overlong-query (test-encoder))
                            :maximum-query-tokens (get-in result [:config :maximum-query-tokens])})]
            (is (= configured (:maximum-query-tokens report)))
            (is (true? (:query-truncated? report))))
          (is (= configured
                 (:maximum-query-tokens
                  (operations/search-query-truncation {} (:index-id result)
                                                      (overlong-query (test-encoder)))))
              "search resolves the limit from the index it is searching"))))))

;; =============================================================================
;; Cycle 3 — the exemption must not leak: a query UNDER the limit is not marked
;; truncated, and the boundary is exact (implies, not iff-by-accident).
;; =============================================================================

(def short-query "binding arbitration of contract disputes")

(deftest under-limit-query-is-not-marked-truncated
  (let [enc (test-encoder)
        raw (count (encoder/encode-ids enc short-query))
        limit (configured-limit enc)
        built (encoder/build-query-ids enc short-query {:maximum-query-tokens limit})]
    (testing "the probe is non-vacuous — it MUST fit"
      (println "  [N] short probe raw word-piece tokens =" raw
               "| would need rows =" (+ raw specials) "| limit =" limit)
      (is (< (+ raw specials) limit)))
    (testing "no truncation flag, no phantom discard"
      (is (false? (:truncated? built)))
      (is (= 0 (:discarded-token-count built))))
    (testing "the report still states the true counts"
      (is (= (+ raw specials) (:query-token-count built)))
      (is (= limit (:maximum-query-tokens built))))
    (testing "the sequence is [MASK]-padded to the limit, all real tokens attended"
      (is (= limit (count (:ids built))))
      (is (= (+ raw specials) (count (filter #(= 1 %) (:attention built))))))))

(deftest truncation-boundary-is-exact
  (let [enc (test-encoder)
        raw (count (encoder/encode-ids enc short-query))
        needed (+ raw specials)
        at (encoder/build-query-ids enc short-query {:maximum-query-tokens needed})
        over (encoder/build-query-ids enc short-query {:maximum-query-tokens (inc needed)})
        under (encoder/build-query-ids enc short-query {:maximum-query-tokens (dec needed)})]
    (testing "exactly AT the limit is NOT truncated (the spec says >, not >=)"
      (is (false? (:truncated? at)))
      (is (= 0 (:discarded-token-count at))))
    (testing "one row of headroom is not truncated either"
      (is (false? (:truncated? over))))
    (testing "one row short IS truncated, by exactly one token"
      (is (true? (:truncated? under)))
      (is (= 1 (:discarded-token-count under))))))

(deftest under-limit-rerank-audit-event-is-not-marked-truncated
  (testing "a fitting query leaves :query-truncated? false on the audit event"
    (let [enc (test-encoder)
          raw (count (encoder/encode-ids enc short-query))
          result (tu/process-command! *ctx*
                   {:command/name :colbert/rerank
                    :query short-query
                    :documents documents
                    :k 3})
          event (tu/find-event result :colbert/rerank-performed)]
      (is (not (::anom/category result)))
      (println "  [N] short-query rerank audit ="
               (pr-str (select-keys event [:query-token-count :maximum-query-tokens
                                           :query-truncated? :discarded-token-count])))
      (is (= (+ raw specials) (:query-token-count event)))
      (is (false? (:query-truncated? event)))
      (is (= 0 (:discarded-token-count event))))))

;; =============================================================================
;; Cycle 4 — and ONLY now, the VALUE, chosen from the real-corpus measurement
;; (doc/build-timeline/evidence/cc17): 221 real consolidator inference
;; signatures + the real classifier task signature run 150-455 word-piece
;; tokens (p50 439, p95 439, max 455) => 153..458 ROWS. 100% of them were
;; truncated at the checkpoint's own query_maxlen 32.
;;
;; The move also RELOCATES the MaxSim ceiling: 32.0 was never a constant of
;; nature, it IS query_maxlen. Anything fitted to that scale has to follow it.
;; =============================================================================

(def production-signature-token-floor
  "The p50 of the measured real-corpus distribution, in word-piece tokens. The
   shipped default must clear this by construction, not by luck."
  439)

(deftest shipped-default-fits-the-measured-production-corpus
  (let [enc (test-encoder)
        limit (encoder/resolve-maximum-query-tokens enc nil)
        ;; A synthetic query of exactly the measured p50 length, built from the
        ;; real tokenizer so the count is the encoder's own, not an estimate.
        long-query (clojure.string/join " " (repeat production-signature-token-floor "consolidation"))
        raw (count (encoder/encode-ids enc long-query))
        built (encoder/build-query-ids enc long-query)]
    (println "  [N] shipped default maximum_query_tokens =" limit
             "| p50-length probe raw tokens =" raw)
    (is (>= raw production-signature-token-floor)
        "probe must be at least the measured p50 or it proves nothing")
    (is (>= limit (+ production-signature-token-floor specials))
        "the shipped default must cover the measured p50 production query")
    (is (false? (:truncated? built))
        "a p50-length production query must no longer be silently truncated")
    (is (= limit (count (:ids built))))))

(deftest maxsim-ceiling-follows-the-configured-limit
  (testing "the normalization ceiling IS maximum_query_tokens — never a frozen 32.0"
    (let [enc (test-encoder)
          limit (encoder/resolve-maximum-query-tokens enc nil)]
      (is (= 1.0 (operations/normalize-colbert-score (double limit)))
          "a score at the ceiling normalizes to 1.0")
      (is (= 0.5 (operations/normalize-colbert-score (/ (double limit) 2.0)))
          "half the ceiling normalizes to 0.5")
      (is (= (double limit) (operations/maxsim-ceiling limit))
          "maxsim-ceiling is the limit itself, as a double")
      (is (= (double limit) (operations/maxsim-ceiling))
          "and with no argument it resolves the configured default"))))

;; =============================================================================
;; entity-fields.IndexConfiguration / value-equality.IndexConfiguration through
;; the REAL Grain command -> schema-validated event -> projection path.
;; =============================================================================

(deftest index-configuration-round-trips-through-the-real-event-pipeline
  (with-index-root
    (let [configured 96
          result (tu/process-command! *ctx*
                   {:command/name :colbert/create-index
                    :collection documents
                    :index-name "cc17-command"
                    :document-ids ["d1" "d2" "d3"]
                    :split-documents? false
                    :maximum-query-tokens configured})]
      (is (not (::anom/category result))
          (str "create-index must succeed: " (pr-str (::anom/message result))))
      (let [event (tu/find-event result :colbert/index-created)]
        (println "  [N] emitted IndexConfiguration =" (pr-str (:config event)))
        (testing "entity-fields: maximum_query_tokens is on the emitted configuration"
          (is (= configured (get-in event [:config :maximum-query-tokens]))))
        (testing "entity-fields: alongside the passage-side limit it mirrors"
          (is (contains? (:config event) :max-document-length))
          (is (contains? (:config event) :split-documents?)))
        (testing "value-equality: the projection reproduces the configuration verbatim"
          (let [projected (read-models/apply-index-events [(assoc event :event/type :colbert/index-created)])]
            (is (= (:config event)
                   (get-in projected [:indexes (:index-id event) :config])))))))))

;; =============================================================================
;; The ONTOLOGY HOT PATH does not go through a Grain command (it calls
;; operations/search and operations/rerank directly through the interface), so
;; the audit event is not on it. The truncation report rides along as result
;; METADATA there — additive, so the frozen result contract is untouched.
;; =============================================================================

(deftest truncation-is-visible-on-the-direct-rerank-path
  (let [enc (test-encoder)
        overlong (overlong-query enc)
        raw (count (encoder/encode-ids enc overlong))
        limit (configured-limit enc)
        results (operations/rerank {} {:query overlong :documents documents})
        report (:query-truncation (meta results))]
    (println "  [N] direct-path rerank metadata =" (pr-str report))
    (testing "the report is reachable without an event store"
      (is (= (+ raw specials) (:query-token-count report)))
      (is (= limit (:maximum-query-tokens report)))
      (is (true? (:query-truncated? report)))
      (is (= (- (+ raw specials) limit) (:discarded-token-count report))))
    (testing "and the frozen result contract is untouched"
      (is (= 3 (count results)))
      (doseq [r results]
        (is (= #{:content :score :rank} (set (keys r))))))
    (testing "a fitting query reports no truncation on the same path"
      (let [ok (operations/rerank {} {:query short-query :documents documents})]
        (is (false? (:query-truncated? (:query-truncation (meta ok)))))))))

(deftest truncation-survives-the-rrf-adapter-the-ontology-actually-calls
  ;; The ontology hot path is colbert-search-concepts -> search-for-rrf, which
  ;; rebuilds the result vector ({:uri :score}). Without an explicit carry the
  ;; metadata would be dropped exactly where it is needed most.
  (with-index-root
    (let [enc (test-encoder)
          overlong (overlong-query enc)
          raw (count (encoder/encode-ids enc overlong))
          created (operations/create-index! {}
                    {:collection documents
                     :document-ids ["d1" "d2" "d3"]
                     :index-name "cc17-rrf"
                     :split-documents? false
                     :maximum-query-tokens 64})]
      (with-redefs [read-models/get-index
                    (fn [_ id] (when (= id (:index-id created))
                                 {:index-id id :index-path (:index-path created)
                                  :config (:config created) :status :active}))]
        (let [fused (operations/search-for-rrf {} {:query overlong
                                                  :index-id (:index-id created)
                                                  :k 3})
              report (:query-truncation (meta fused))]
          (println "  [N] search-for-rrf metadata =" (pr-str report))
          (is (= 3 (count fused)))
          (is (= #{:uri :score} (set (keys (first fused)))) "fusion shape untouched")
          (is (= 64 (:maximum-query-tokens report))
              "the INDEX's configured limit, not the global default")
          (is (true? (:query-truncated? report)))
          (is (= (+ raw specials) (:query-token-count report))))))))

(deftest search-audit-event-carries-the-truncation-signal
  ;; The rerank side is covered above; this closes the same loop on :colbert/search,
  ;; and pins that the audit reports the INDEX's own configured limit.
  (with-index-root
    (let [enc (test-encoder)
          overlong (overlong-query enc)
          raw (count (encoder/encode-ids enc overlong))
          created (operations/create-index! {}
                    {:collection documents
                     :document-ids ["d1" "d2" "d3"]
                     :index-name "cc17-search-audit"
                     :split-documents? false
                     :maximum-query-tokens 64})]
      (with-redefs [read-models/get-index
                    (fn [_ id] (when (= id (:index-id created))
                                 {:index-id id :index-path (:index-path created)
                                  :config (:config created) :status :active}))]
        (let [result (tu/process-command! *ctx*
                       {:command/name :colbert/search
                        :index-id (:index-id created)
                        :query overlong
                        :k 3})]
          (is (not (::anom/category result))
              (str "search must succeed: " (pr-str (::anom/message result))))
          (let [event (tu/find-event result :colbert/search-performed)]
            (println "  [N] search audit truncation ="
                     (pr-str (select-keys event [:query-token-count :maximum-query-tokens
                                                 :query-truncated? :discarded-token-count])))
            (is (= (+ raw specials) (:query-token-count event)))
            (is (= 64 (:maximum-query-tokens event))
                "the audit reports the INDEX's configured limit")
            (is (true? (:query-truncated? event)))
            (is (= (- (+ raw specials) 64) (:discarded-token-count event)))))))))
