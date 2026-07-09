(ns ai.obney.orc.colbert.index-search-test
  "Slice 2, cycles 4-6: JVM-backed create-index! + search in operations.clj.

   - Cycle 4: golden test — create-index! over the 6 golden-fixture docs
     (unsplit, ids d1..d6), then search with the 3 golden queries: per-query
     rankings equal python_scores.json and scores match within 1e-3 (a
     full-index search over unsplit short docs IS a rerank of all 6 — same
     MaxSim).

   Uses the REAL local model via -Dcolbert.model.path and a stubbed
   read-model (the commands_test idiom, via with-redefs); the index root is a
   per-test temp dir via -Dcolbert.index.root. No Python, no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.corpus :as corpus]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.index-store :as index-store]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]))

(use-fixtures :once support/with-model-path)

(def index-root-property "colbert.index.root")

(defn- temp-dir ^java.io.File [label]
  (.toFile (java.nio.file.Files/createTempDirectory
            (str "colbert-index-search-test-" label)
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn with-temp-index-root
  "Point -Dcolbert.index.root at a fresh temp dir for each test, restoring
   the previous value afterwards (never write indexes into the repo cwd)."
  [f]
  (let [previous (System/getProperty index-root-property)]
    (System/setProperty index-root-property
                        (str (temp-dir "root")))
    (try
      (f)
      (finally
        (if previous
          (System/setProperty index-root-property previous)
          (System/clearProperty index-root-property))))))

(use-fixtures :each with-temp-index-root)

(defn- stub-index
  "A read-model row as get-index would return it for a just-created index."
  [{:keys [index-id index-path]}]
  {:index-id index-id
   :index-path index-path
   :status :active})

(defmacro with-stubbed-read-model
  "Stub read-models/get-index to serve `result` (a create-index! return map)
   for its own index-id, nil otherwise."
  [result & body]
  `(let [result# ~result]
     (with-redefs [read-models/get-index
                   (fn [~'_ index-id#]
                     (when (= index-id# (:index-id result#))
                       (stub-index result#)))]
       ~@body)))

(def score-tolerance 1e-3)

;; =============================================================================
;; Cycle 4: golden rankings + scores through create-index! -> search
;; =============================================================================

(deftest search-reproduces-python-golden-rankings-and-scores
  (let [golden (support/read-golden "python_scores.json")
        doc-ids (vec (sort (keys (get golden "documents"))))
        docs (mapv #(get-in golden ["documents" %]) doc-ids)
        result (operations/create-index! {}
                 {:collection docs
                  :document-ids doc-ids
                  :index-name "golden-6"
                  :split-documents? false})]
    (is (= 6 (:num-passages result)) "6 unsplit docs -> 6 passages")
    (with-stubbed-read-model result
      (doseq [[qid qtext] (get golden "queries")]
        (testing (str qid ": " qtext)
          (let [expected (->> (get-in golden ["scores" qid])
                              (sort-by #(get % "rank")))
                results (operations/search {} {:query qtext
                                               :index-id (:index-id result)
                                               :k 6})]
            (is (= (count expected) (count results)))
            (is (= (mapv #(get % "doc") expected)
                   (mapv :document_id results))
                (str qid ": per-query ranking must equal the Python golden"))
            (doseq [[entry r] (map vector expected results)]
              (is (< (Math/abs (- (double (get entry "score"))
                                  (double (:score r))))
                     score-tolerance)
                  (str qid " " (get entry "doc")
                       ": python " (get entry "score")
                       " vs jvm " (:score r))))
            (testing "content is the document text"
              (doseq [r results]
                (is (= (get-in golden ["documents" (:document_id r)])
                       (:content r)))))))))))

;; =============================================================================
;; Cycle 5: passage -> document aggregation (max passage score, once per doc)
;; =============================================================================

(def long-text
  (str "Any dispute arising under this agreement shall be settled by binding "
       "arbitration before a panel of three neutral arbitrators seated in Geneva. "
       "Either party may terminate the engagement with thirty days written notice "
       "delivered by registered mail to the registered office. All intellectual "
       "property created during the collaboration remains the sole property of the "
       "originating inventor unless assigned in a separate instrument. The hurricane "
       "weakened to a tropical storm before making landfall near the coastal wetlands, "
       "sparing the fishing villages from the worst of the storm surge. Meanwhile the "
       "midfielder scored twice in the final minutes of the championship match, "
       "sending the crowd into raptures and the commentators into hyperbole. Preheat "
       "the oven to two hundred twenty degrees and bake the sourdough loaf for forty "
       "minutes until the crust turns a deep golden brown and sounds hollow when "
       "tapped. Quantum error correction encodes logical qubits across many physical "
       "qubits, trading hardware overhead for resilience against decoherence. The "
       "glacier retreated eleven metres last summer, exposing gravel beds that had "
       "been sealed beneath ice since the seventeenth century."))

(def distractor-text
  "The recipe calls for fresh basil, ripe tomatoes, and a drizzle of olive oil.")

(deftest multi-passage-document-aggregates-to-max-passage-score
  (let [chunk-size 40
        result (operations/create-index! {}
                 {:collection [long-text distractor-text]
                  :document-ids ["doc-long" "doc-short"]
                  :document-metadatas [{:kind "contract"} {:kind "recipe"}]
                  :index-name "aggregation"
                  :split-documents? true
                  :max-document-length chunk-size})]
    (is (> (:num-passages result) 2)
        "precondition: the long doc split into multiple passages")
    (with-stubbed-read-model result
      (let [query "binding arbitration of contract disputes"
            results (operations/search {} {:query query
                                           :index-id (:index-id result)
                                           :k 10})
            ;; independent cross-check: split the long doc the same way the
            ;; indexer does, score each passage via rerank (same MaxSim), and
            ;; take the max
            enc (encoder/get-encoder (model-store/resolve-model-dir))
            passages (corpus/split-collection enc
                       {:collection [long-text]
                        :document-ids ["doc-long"]
                        :document-metadatas nil
                        :split-documents? true
                        :max-document-length chunk-size})
            passage-scores (operations/rerank {}
                             {:query query
                              :documents (mapv :text passages)})
            best-passage (first passage-scores)]
        (testing "each document appears exactly ONCE (documents, not passages)"
          (is (= 2 (count results)))
          (is (= ["doc-long" "doc-short"]
                 (sort (map :document_id results))))
          (is (= 1 (count (filter #(= "doc-long" (:document_id %)) results)))))
        (let [long-result (first (filter #(= "doc-long" (:document_id %)) results))]
          (testing "document score = MAX of its passages' scores"
            (is (= (double (:score best-passage))
                   (double (:score long-result)))
                "search's doc score equals the independently-computed max passage MaxSim"))
          (testing ":content is the best-scoring passage's text (not the whole doc)"
            (is (= (:content best-passage) (:content long-result)))
            (is (not= long-text (:content long-result))))
          (testing "snake_case keys, exactly the bridge's result shape"
            (doseq [r results]
              (is (= #{:content :score :rank :document_id :document_metadata}
                     (set (keys r))))))
          (testing "document metadata rides along"
            (is (= {:kind "contract"} (:document_metadata long-result)))))
        (testing "ranks are 1-indexed consecutive, scores descending"
          (is (= [1 2] (map :rank results)))
          (is (>= (:score (first results)) (:score (second results)))))))))

;; =============================================================================
;; Cycle 6: contract details — ex-infos, k, nil-coalescing, frozen return map,
;; in-memory cache hit
;; =============================================================================

(deftest search-not-found-and-deleted-ex-infos-preserved
  (testing "unknown index throws the EXACT pre-existing ex-info"
    (with-redefs [read-models/get-index (fn [_ _] nil)]
      (let [index-id (random-uuid)
            ex (try (operations/search {} {:query "q" :index-id index-id :k 3})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "Index not found" (ex-message ex)))
        (is (= {:index-id index-id} (ex-data ex))))))
  (testing "deleted index throws the EXACT pre-existing ex-info"
    (with-redefs [read-models/get-index (fn [_ _] {:status :deleted})]
      (let [index-id (random-uuid)
            ex (try (operations/search {} {:query "q" :index-id index-id :k 3})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "Index has been deleted" (ex-message ex)))
        (is (= {:index-id index-id} (ex-data ex)))))))

(deftest create-index-return-map-contract-is-frozen
  (let [golden (support/read-golden "python_scores.json")
        doc-ids (vec (sort (keys (get golden "documents"))))
        docs (mapv #(get-in golden ["documents" %]) doc-ids)
        metas (mapv (fn [id] {:source id}) doc-ids)
        result (operations/create-index! {}
                 {:collection docs
                  :document-ids doc-ids
                  :document-metadatas metas
                  :index-name "contract-check"
                  :split-documents? false})]
    (testing "EXACTLY the frozen key set"
      (is (= #{:index-id :index-path :num-passages :duration-ms
               :document-ids :document-metadatas :document-count
               :model-name :index-name :config}
             (set (keys result)))))
    (is (uuid? (:index-id result)))
    (is (string? (:index-path result)))
    (is (.isDirectory (io/file (:index-path result)))
        "index-path names the artifact directory on disk")
    (is (= 6 (:num-passages result)))
    (is (int? (:duration-ms result)))
    (is (= doc-ids (:document-ids result)))
    (is (= metas (:document-metadatas result)))
    (is (= 6 (:document-count result)))
    (is (= model-store/checkpoint (:model-name result))
        "the default model-name is the encoder checkpoint actually used")
    (is (= "contract-check" (:index-name result)))
    (is (= {:split-documents? false
            :max-document-length 256
            :use-faiss? false}
           (:config result)))))

(deftest create-index-nil-coalesces-explicit-nil-optionals
  ;; the :colbert/create-index defcommand forwards omitted optionals as
  ;; EXPLICIT nil, which bypasses :or defaults — the documented trap
  (let [result (operations/create-index! {}
                 {:collection ["one short document"]
                  :document-ids nil
                  :document-metadatas nil
                  :index-name "nil-coalesce"
                  :model-name nil
                  :split-documents? nil
                  :max-document-length nil})]
    (is (= model-store/checkpoint (:model-name result))
        "explicit nil model-name coalesces to the checkpoint default")
    (is (= {:split-documents? true
            :max-document-length 256
            :use-faiss? false}
           (:config result))
        "explicit nil split-documents?/max-document-length coalesce to defaults")
    (is (= 1 (count (:document-ids result)))
        "nil document-ids are auto-generated")
    (is (every? string? (:document-ids result)))
    (testing "explicit FALSE split-documents? is preserved, not coalesced"
      (let [r2 (operations/create-index! {}
                 {:collection ["another document"]
                  :document-ids ["x"]
                  :document-metadatas nil
                  :index-name "nil-coalesce-false"
                  :model-name nil
                  :split-documents? false
                  :max-document-length nil})]
        (is (= false (get-in r2 [:config :split-documents?])))))))

(deftest search-respects-k
  (let [golden (support/read-golden "python_scores.json")
        doc-ids (vec (sort (keys (get golden "documents"))))
        docs (mapv #(get-in golden ["documents" %]) doc-ids)
        result (operations/create-index! {}
                 {:collection docs
                  :document-ids doc-ids
                  :index-name "k-check"
                  :split-documents? false})]
    (with-stubbed-read-model result
      (let [query (get-in golden ["queries" "q1"])
            all-results (operations/search {} {:query query
                                               :index-id (:index-id result)
                                               :k 6})
            top-2 (operations/search {} {:query query
                                         :index-id (:index-id result)
                                         :k 2})]
        (is (= 2 (count top-2)))
        (is (= (map :document_id (take 2 all-results))
               (map :document_id top-2))
            "k results are the TOP k of the full ranking")
        (is (= [1 2] (map :rank top-2)))
        (testing "k defaults to 10 when passed as explicit nil"
          (is (= 6 (count (operations/search {} {:query query
                                                 :index-id (:index-id result)
                                                 :k nil})))
              "6 docs < default 10 -> all come back"))))))

(deftest search-serves-from-the-in-memory-cache
  (let [result (operations/create-index! {}
                 {:collection ["alpha document about arbitration"
                               "beta document about baking bread"]
                  :document-ids ["a" "b"]
                  :index-name "cache-check"
                  :split-documents? false})
        reads (atom 0)
        real-read index-store/read-index]
    ;; cold start: make sure nothing from THIS path is cached yet
    (index-store/evict-index! (:index-path result))
    (with-stubbed-read-model result
      (with-redefs [index-store/read-index (fn [dir]
                                             (swap! reads inc)
                                             (real-read dir))]
        (let [r1 (operations/search {} {:query "arbitration" :index-id (:index-id result) :k 2})
              r2 (operations/search {} {:query "baking bread" :index-id (:index-id result) :k 2})]
          (is (= 1 @reads)
              "the artifact is read from disk ONCE across two searches")
          (is (= "a" (:document_id (first r1))))
          (is (= "b" (:document_id (first r2)))))))))

;; =============================================================================
;; Through the grain harness: the FROZEN defcommand consumes the return map,
;; the emitted event validates against its unchanged schema, the read-model
;; resolves the artifact path, and search runs end-to-end — zero Python
;; =============================================================================

(deftest create-index-defcommand-round-trip-through-grain
  (let [tu (requiring-resolve 'ai.obney.orc.grain-test-utils.interface/create-test-context)
        stop (requiring-resolve 'ai.obney.orc.grain-test-utils.interface/stop-context)
        process! (requiring-resolve 'ai.obney.orc.grain-test-utils.interface/process-command!)
        apply-events! (requiring-resolve 'ai.obney.orc.grain-test-utils.interface/apply-events!)
        find-event (requiring-resolve 'ai.obney.orc.grain-test-utils.interface/find-event)]
    (require 'ai.obney.orc.colbert.interface.schemas
             'ai.obney.orc.colbert.core.commands)
    (let [ctx (tu "colbert")]
      (try
        (let [result (process! ctx
                       {:command/name :colbert/create-index
                        :collection ["Any dispute shall be settled by binding arbitration."
                                     "Preheat the oven and bake the sourdough loaf."]
                        :document-ids ["d-arb" "d-bread"]
                        :index-name "grain-round-trip"})]
          (is (not (:cognitect.anomalies/category result))
              (str "create-index command must succeed, got: " (pr-str result)))
          (let [event (find-event result :colbert/index-created)]
            (is (some? event) "the :colbert/index-created event is emitted")
            (is (= "grain-round-trip" (:index-name event)))
            (is (= 2 (:passage-count event)))
            (is (= model-store/checkpoint (:model-name event))))
          (apply-events! ctx result)
          (let [index-id (get-in result [:command/result :index-id])
                index (read-models/get-index ctx index-id)]
            (is (some? index) "the read-model resolves the new index")
            (is (= (get-in result [:command/result :index-path])
                   (:index-path index)))
            (testing "search through the REAL read-model lookup (no stub)"
              (let [results (operations/search ctx {:query "binding arbitration"
                                                    :index-id index-id
                                                    :k 2})]
                (is (= 2 (count results)))
                (is (= "d-arb" (:document_id (first results))))))))
        (finally
          (stop ctx))))))
