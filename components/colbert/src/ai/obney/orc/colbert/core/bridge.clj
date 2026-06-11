(ns ai.obney.orc.colbert.core.bridge
  "Python subprocess bridge for ColBERT operations.

   Protocol: JSON-RPC over stdin/stdout
   - Lazy subprocess start on first call
   - Thread-safe via locking
   - Automatic restart on failure
   - Configurable timeout

   Why Python Bridge is Required:
   - ColBERT is a 110M parameter PyTorch neural network
   - PLAID indexing is a specialized algorithm (2000+ lines)
   - Training requires PyTorch autograd for gradient computation
   - Bridge adds ~50ms latency (negligible vs model inference)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu])
  (:import [java.io BufferedReader BufferedWriter]
           [java.util.concurrent TimeoutException]
           [java.util.concurrent.atomic AtomicLong]))

;; =============================================================================
;; Bridge State Management
;; =============================================================================

(defonce ^:private bridge-state
  (atom {:process nil
         :writer nil
         :reader nil
         :request-id (AtomicLong. 0)}))

(defonce ^:private bridge-lock (Object.))

;; ColBERT venv path - defaults to .venv-colbert in project root
;; Can be overridden via system property -Dcolbert.venv.path=...
(def ^:private venv-path
  (or (System/getProperty "colbert.venv.path")
      ".venv-colbert"))

;; ColBERT bridge script path - defaults to scripts/colbert_bridge.py relative to
;; the JVM cwd (correct when running FROM the orc repo). Consumers that depend on
;; orc as a read-only git/SHA library (e.g. a separate app) run from their own cwd
;; where that relative path does not resolve, so they override it with an absolute
;; path via -Dcolbert.bridge.script=... (typically the script in their gitlibs
;; checkout of orc, alongside an absolute -Dcolbert.venv.path).
(def ^:private bridge-script-path
  (or (System/getProperty "colbert.bridge.script")
      "scripts/colbert_bridge.py"))

(defn- get-python-cmd
  "Get the Python command, preferring venv if available."
  []
  (let [venv-python (str venv-path "/bin/python")]
    (if (.exists (java.io.File. venv-python))
      [venv-python "-u" bridge-script-path]
      ;; Fallback to system python
      ["python" "-u" bridge-script-path])))

(def ^:private default-timeout-ms 60000)

;; =============================================================================
;; Subprocess Management
;; =============================================================================

(defn- start-bridge!
  "Start the Python bridge subprocess.

   This is called automatically on first use, but can be called
   explicitly for eager initialization."
  []
  (locking bridge-lock
    (when-not (:process @bridge-state)
      (mu/log ::starting-bridge)
      (let [python-cmd (get-python-cmd)
            pb (ProcessBuilder. ^java.util.List python-cmd)
            _ (.redirectErrorStream pb false)
            ;; Add venv bin to PATH so ninja can be found
            env (.environment pb)
            venv-bin (str venv-path "/bin")
            current-path (or (.get env "PATH") "")
            _ (.put env "PATH" (str venv-bin ":" current-path))
            process (.start pb)
            writer (BufferedWriter. (io/writer (.getOutputStream process)))
            reader (BufferedReader. (io/reader (.getInputStream process)))]
        (swap! bridge-state assoc
               :process process
               :writer writer
               :reader reader)
        (mu/log ::bridge-started :pid (.pid process) :python-cmd python-cmd)))))

(defn- ensure-bridge!
  "Ensure bridge is running, restart if dead."
  []
  (locking bridge-lock
    (let [{:keys [process]} @bridge-state]
      (when (or (nil? process) (not (.isAlive ^Process process)))
        (when process
          (mu/log ::bridge-died-restarting))
        (start-bridge!)))))

(defn- next-request-id
  "Generate next unique request ID."
  []
  (.incrementAndGet ^AtomicLong (:request-id @bridge-state)))

(defn stop-bridge!
  "Gracefully stop the bridge subprocess.

   This should be called during application shutdown."
  []
  (locking bridge-lock
    (when-let [process (:process @bridge-state)]
      (mu/log ::stopping-bridge)
      (try
        ;; Try graceful shutdown first
        (let [writer (:writer @bridge-state)
              request {:id (next-request-id)
                       :method "shutdown"
                       :params {}}]
          (.write ^BufferedWriter writer (json/write-str request))
          (.newLine ^BufferedWriter writer)
          (.flush ^BufferedWriter writer)
          ;; Give it a moment to shut down gracefully
          (Thread/sleep 100))
        (catch Exception _))
      (.destroy ^Process process)
      (swap! bridge-state assoc :process nil :writer nil :reader nil)
      (mu/log ::bridge-stopped))))

;; =============================================================================
;; JSON-RPC Protocol
;; =============================================================================

(defn call-bridge
  "Call the Python bridge with a JSON-RPC request.

   Args:
     method - Method name (keyword or string)
     params - Parameters map

   Options:
     :timeout-ms - Request timeout (default 60000)

   Returns the result map or throws on error.

   Example:
     (call-bridge :search {:alias \"my-model\" :query \"test\" :k 10})"
  [method params & {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
  (ensure-bridge!)
  (locking bridge-lock
    (let [{:keys [writer reader]} @bridge-state
          req-id (next-request-id)
          request {:id req-id
                   :method (name method)
                   :params params}
          request-json (json/write-str request)]

      (mu/log ::bridge-call :method method :request-id req-id)

      ;; Send request
      (.write ^BufferedWriter writer request-json)
      (.newLine ^BufferedWriter writer)
      (.flush ^BufferedWriter writer)

      ;; Read response with timeout
      (let [response-future (future (.readLine ^BufferedReader reader))
            response-line (deref response-future timeout-ms ::timeout)]

        (when (= response-line ::timeout)
          (future-cancel response-future)
          (throw (TimeoutException.
                  (str "Bridge call timed out after " timeout-ms "ms for method " method))))

        (when (nil? response-line)
          (throw (ex-info "Bridge process died unexpectedly"
                          {:method method :request-id req-id})))

        (let [response (json/read-str response-line :key-fn keyword)]
          (if-let [error (:error response)]
            (throw (ex-info (str "Bridge error: " (:message error))
                            {:method method
                             :code (:code error)
                             :traceback (:traceback error)}))
            (:result response)))))))

;; =============================================================================
;; High-Level API - Model Management
;; =============================================================================

(defn load-model!
  "Load a ColBERT model.

   Args:
     alias - Unique identifier for this model instance

   Options:
     :model-name - HuggingFace model name (default: colbert-ir/colbertv2.0)
     :index-path - Load from existing index instead of pretrained

   Returns:
     {:status \"loaded\" :alias \"...\"}"
  [alias & {:keys [model-name index-path]}]
  (call-bridge :load_model
               {:alias alias
                :model_name model-name
                :index_path index-path}))

(defn unload-model!
  "Unload a model to free memory.

   Returns:
     {:status \"unloaded\" :alias \"...\"}"
  [alias]
  (call-bridge :unload_model {:alias alias}))

(defn list-models
  "List all loaded model aliases.

   Returns:
     {:models [\"alias1\" \"alias2\" ...]}"
  []
  (call-bridge :list_models {}))

;; =============================================================================
;; High-Level API - Index Operations
;; =============================================================================

(defn create-index!
  "Create a PLAID index from documents.

   Args:
     alias - Model alias to use
     opts - Options map:
       :collection         - Vector of document strings (required)
       :index-name         - Name for the index (required)
       :document-ids       - Vector of unique IDs (optional)
       :document-metadatas - Vector of metadata maps (optional)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256)
       :use-faiss?         - Use FAISS instead of PLAID (default: false)

   Returns:
     {:index_path \"...\" :index_name \"...\" :num_passages int}"
  [alias {:keys [collection document-ids document-metadatas
                 index-name split-documents? max-document-length use-faiss?]
          :or {split-documents? true
               max-document-length 256
               use-faiss? false}}]
  (call-bridge :create_index
               {:alias alias
                :collection (vec collection)
                :document_ids (when document-ids (vec document-ids))
                :document_metadatas (when document-metadatas (vec document-metadatas))
                :index_name index-name
                :split_documents split-documents?
                :max_document_length max-document-length
                :use_faiss use-faiss?}))

(defn add-to-index!
  "Add documents to an existing index.

   Returns:
     {:status \"updated\" :documents_added int}"
  [alias {:keys [collection document-ids document-metadatas index-name]}]
  (call-bridge :add_to_index
               {:alias alias
                :new_collection (vec collection)
                :new_document_ids (when document-ids (vec document-ids))
                :new_document_metadatas (when document-metadatas (vec document-metadatas))
                :index_name index-name}))

(defn delete-from-index!
  "Delete documents from an existing index by ID.

   Returns:
     {:status \"updated\" :documents_removed int}"
  [alias {:keys [document-ids index-name]}]
  (call-bridge :delete_from_index
               {:alias alias
                :document_ids (vec document-ids)
                :index_name index-name}))

;; =============================================================================
;; High-Level API - Search Operations
;; =============================================================================

(defn search
  "Search indexed corpus using ColBERT late-interaction.

   Args:
     alias - Model alias to use
     opts - Options map:
       :query      - Search query string (required)
       :k          - Number of results (default: 10)
       :index-name - Specific index to search (optional)
       :doc-ids    - Filter to specific document IDs (optional)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1 :document-id \"...\" :document-metadata {...}}]"
  [alias {:keys [query k index-name doc-ids]
          :or {k 10}}]
  (:results
   (call-bridge :search
                {:alias alias
                 :query query
                 :k k
                 :index_name index-name
                 :doc_ids (when doc-ids (vec doc-ids))})))

(defn search-batch
  "Batch search for multiple queries.

   Returns:
     [[{...} ...] ...]  ; List of result lists"
  [alias {:keys [queries k index-name]
          :or {k 10}}]
  (:results
   (call-bridge :search_batch
                {:alias alias
                 :queries (vec queries)
                 :k k
                 :index_name index-name})))

(defn rerank
  "Rerank documents in-memory (no index required).

   This encodes query and documents on-the-fly and scores them.
   Useful for reranking candidates from other retrieval methods.

   Args:
     alias - Model alias to use
     opts - Options map:
       :query     - Query string (required)
       :documents - Vector of document strings to rerank (required)
       :k         - Number of results (default: all documents)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1}]"
  [alias {:keys [query documents k]}]
  (:results
   (call-bridge :rerank
                {:alias alias
                 :query query
                 :documents (vec documents)
                 :k k})))

;; =============================================================================
;; High-Level API - Training Operations
;; =============================================================================

(defn create-trainer!
  "Create a trainer instance for fine-tuning.

   Args:
     alias - Unique identifier for this trainer
     opts - Options map:
       :model-name           - Name for the new model being trained
       :pretrained-model-name - Base model to fine-tune from (default: colbert-ir/colbertv2.0)
       :language-code        - Language code (default: en)

   Returns:
     {:status \"created\" :alias \"...\"}"
  [alias {:keys [model-name pretrained-model-name language-code]
          :or {pretrained-model-name "colbert-ir/colbertv2.0"
               language-code "en"}}]
  (call-bridge :create_trainer
               {:alias alias
                :model_name model-name
                :pretrained_model_name pretrained-model-name
                :language_code language-code}))

(defn prepare-training-data!
  "Prepare training data with optional hard negative mining.

   Raw data formats supported:
   - pairs: [[query positive] ...]
   - labeled-pairs: [[query passage label] ...] where label is 0/1
   - triplets: [[query positive negative] ...]

   Args:
     alias - Trainer alias
     opts - Options map:
       :raw-data                 - Training data (required)
       :data-out-path            - Path to write processed data (required)
       :all-documents            - Corpus for hard negative mining (optional)
       :mine-hard-negatives?     - Whether to mine negatives (default: true)
       :num-new-negatives        - Negatives per positive (default: 10)
       :hard-negative-minimum-rank - Skip top-N as too easy (default: 10)

   Returns:
     {:data_path \"...\" :num_triplets int}"
  [alias {:keys [raw-data data-out-path all-documents
                 mine-hard-negatives? num-new-negatives hard-negative-minimum-rank]
          :or {mine-hard-negatives? true
               num-new-negatives 10
               hard-negative-minimum-rank 10}}]
  (call-bridge :prepare_training_data
               {:alias alias
                :raw_data (vec raw-data)
                :data_out_path data-out-path
                :all_documents (when all-documents (vec all-documents))
                :mine_hard_negatives mine-hard-negatives?
                :num_new_negatives num-new-negatives
                :hard_negative_minimum_rank hard-negative-minimum-rank}))

(defn train!
  "Train/fine-tune a ColBERT model.

   Training happens on the prepared data from prepare-training-data!

   Args:
     alias - Trainer alias
     opts - Options map:
       :batch-size      - Training batch size (default: 32)
       :nbits           - Compression bits for PLAID (default: 2)
       :maxsteps        - Maximum training steps (default: 500000)
       :learning-rate   - Learning rate (default: 5e-6)
       :dim             - Embedding dimension (default: 128)
       :doc-maxlen      - Maximum document length (default: 256)
       :use-ib-negatives - Use in-batch negatives (default: true)
       :warmup-steps    - LR warmup steps (default: 0)
       :accumsteps      - Gradient accumulation steps (default: 1)

   Returns:
     {:checkpoint_path \"...\" :final_step int}

   Note: This is a long-running operation. Consider increasing timeout."
  [alias {:keys [batch-size nbits maxsteps learning-rate dim doc-maxlen
                 use-ib-negatives warmup-steps accumsteps]
          :or {batch-size 32
               nbits 2
               maxsteps 500000
               learning-rate 5e-6
               dim 128
               doc-maxlen 256
               use-ib-negatives true
               warmup-steps 0
               accumsteps 1}}]
  (call-bridge :train
               {:alias alias
                :batch_size batch-size
                :nbits nbits
                :maxsteps maxsteps
                :learning_rate learning-rate
                :dim dim
                :doc_maxlen doc-maxlen
                :use_ib_negatives use-ib-negatives
                :warmup_steps warmup-steps
                :accumsteps accumsteps}
               :timeout-ms (* 24 60 60 1000)))  ; 24h timeout for training

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn ping
  "Health check for the bridge.

   Returns:
     {:status \"ok\"}"
  []
  (call-bridge :ping {}))

(defn get-index-info
  "Get information about a loaded index.

   Returns:
     {:index_path \"...\" :model_name \"...\"}"
  [alias]
  (call-bridge :get_index_info {:alias alias}))

(comment
  ;; Development REPL examples

  ;; Start bridge and load model
  (load-model! "default" :model-name "colbert-ir/colbertv2.0")

  ;; Create an index
  (create-index! "default"
    {:collection ["The quick brown fox jumps over the lazy dog."
                  "Machine learning is a subset of artificial intelligence."
                  "Natural language processing enables computers to understand text."]
     :index-name "test-index"
     :split-documents? false})

  ;; Search
  (search "default" {:query "AI and machine learning" :k 2})

  ;; Rerank without index
  (rerank "default"
    {:query "AI systems"
     :documents ["Deep learning models" "Machine learning algorithms" "Weather forecasts"]})

  ;; Check health
  (ping)

  ;; Clean up
  (stop-bridge!)
  )
