(ns ai.obney.orc.mcp-sheet-builder.core.executor-runtime
  "Loads generated executors into dynamic namespaces at runtime.

   This module implements the dynamic code loading pattern from ObneyAI/assisstant:
   1. Create isolated namespace
   2. Setup requires in that namespace
   3. Evaluate source code via load-string
   4. Cache resolved functions in registry

   Key functions:
   - load-executor! - Load a single executor from source code
   - load-all-executors! - Load all executors from event store at startup
   - get-executor - Resolve executor function by tool-id or qualified name"
  (:require [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]))

;; ============================================================================
;; Executor Registry
;; ============================================================================

(defonce ^{:doc "Registry of loaded executors.
                 Maps tool-id -> {:fn function
                                  :namespace ns-symbol
                                  :tool-name string
                                  :source-code string}"}
  executor-registry
  (atom {}))

(defonce ^{:doc "Index of tool names to tool IDs for lookup."}
  name->id-index
  (atom {}))

;; ============================================================================
;; Dynamic Namespace Loading
;; ============================================================================

(defn- create-executor-namespace!
  "Create a fresh namespace for the executor.
   Returns the namespace symbol."
  [ns-sym]
  (create-ns ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (refer-clojure))
  ns-sym)

(defn- setup-namespace-requires!
  "Setup required namespaces within the executor namespace."
  [ns-sym requires]
  (binding [*ns* (the-ns ns-sym)]
    (doseq [req requires]
      (try
        (require (symbol req))
        (catch Exception e
          (u/log ::require-failed :namespace ns-sym :require req :error (.getMessage e)))))))

(defn- evaluate-source-code!
  "Evaluate source code in the context of the given namespace.
   Returns the namespace where code was evaluated."
  [ns-sym source-code]
  (binding [*ns* (the-ns ns-sym)]
    (load-string source-code))
  ns-sym)

(defn- resolve-executor-fn
  "Resolve the executor function from the loaded namespace."
  [ns-sym fn-name]
  (when-let [var (ns-resolve (the-ns ns-sym) (symbol fn-name))]
    @var))

;; ============================================================================
;; Executor Loading
;; ============================================================================

(defn load-executor!
  "Load executor source code into isolated namespace.

   Pattern from assisstant: create-ns → binding *ns* → load-string

   Args:
   - tool-id: UUID of the tool
   - tool-name: Name of the MCP tool
   - source-code: Complete namespace source code
   - requires: Vector of namespace strings to require

   Options:
   - :checksum - If provided, verify source code integrity
   - :force? - If true, reload even if already loaded

   Returns {:success? bool :namespace ns-sym :fn executor-fn :error msg}"
  [tool-id tool-name source-code requires & {:keys [checksum force?]}]
  (let [tool-id-str (str tool-id)
        ns-suffix (subs tool-id-str 0 8)
        ns-sym (symbol (str "mcp.executors.dynamic.t" ns-suffix))
        fn-name (str "call-" (gen/sanitize-name tool-name))]

    (if (and (not force?)
             (contains? @executor-registry tool-id))
      (do
        (u/log ::executor-already-loaded :tool-id tool-id :tool-name tool-name)
        {:success? true
         :namespace (get-in @executor-registry [tool-id :namespace])
         :fn (get-in @executor-registry [tool-id :fn])})
      (try
        (when checksum
          (gen/verify-checksum source-code checksum))

        (u/log ::loading-executor :tool-id tool-id :tool-name tool-name :namespace ns-sym)

        (create-executor-namespace! ns-sym)


        (evaluate-source-code! ns-sym source-code)

        (let [executor-fn (resolve-executor-fn ns-sym fn-name)]
          (if executor-fn
            (do
              (swap! executor-registry assoc tool-id
                     {:fn executor-fn
                      :namespace ns-sym
                      :tool-name tool-name
                      :source-code source-code})

              (swap! name->id-index assoc tool-name tool-id)

              (u/log ::executor-loaded :tool-id tool-id :tool-name tool-name :fn fn-name)
              {:success? true
               :namespace ns-sym
               :fn executor-fn})
            {:success? false
             :error (str "Could not resolve function " fn-name " in namespace " ns-sym)}))

        (catch Exception e
          (u/log ::executor-load-failed
                 :tool-id tool-id
                 :tool-name tool-name
                 :error (.getMessage e))
          {:success? false
           :error (.getMessage e)})))))

(defn unload-executor!
  "Unload an executor from the registry and remove its namespace."
  [tool-id]
  (when-let [{:keys [namespace tool-name]} (get @executor-registry tool-id)]
    (swap! executor-registry dissoc tool-id)
    (swap! name->id-index dissoc tool-name)

    (try
      (remove-ns namespace)
      (catch Exception _))

    (u/log ::executor-unloaded :tool-id tool-id :tool-name tool-name)
    true))

;; ============================================================================
;; Bulk Loading from Event Store
;; ============================================================================

(defn load-all-executors!
  "Load all stored executors from event store at startup.

   Args:
   - get-executors-fn: Function that returns all executor definitions from event store
                       Should return seq of {:tool-id :tool-name :source-code :namespace-requires}

   Returns {:loaded count :failed count :errors [...]}"
  [get-executors-fn]
  (let [executors (get-executors-fn)
        results (for [{:keys [tool-id tool-name source-code namespace-requires checksum]} executors]
                  (load-executor! tool-id tool-name source-code namespace-requires
                                  :checksum checksum))]
    {:loaded (count (filter :success? results))
     :failed (count (remove :success? results))
     :errors (keep :error (remove :success? results))}))

;; ============================================================================
;; Executor Resolution
;; ============================================================================

(defn get-executor
  "Resolve executor function by tool-id, tool-name, or qualified name.

   Args:
   - identifier: One of:
     - UUID tool-id
     - String tool-name
     - String qualified name like \"mcp.executors.dynamic.t3a4b5c6d/call-tool\"

   Returns the executor function or nil if not found."
  [identifier]
  (cond
    (uuid? identifier)
    (get-in @executor-registry [identifier :fn])

    (string? identifier)
    (or
     (when-let [tool-id (get @name->id-index identifier)]
       (get-in @executor-registry [tool-id :fn]))

     (when (str/includes? identifier "/")
       (let [[ns-str fn-str] (str/split identifier #"/")]
         (when-let [ns (find-ns (symbol ns-str))]
           (when-let [var (ns-resolve ns (symbol fn-str))]
             @var))))

     (some (fn [[_ {:keys [fn namespace]}]]
             (when (str/ends-with? (str namespace) (first (str/split identifier #"/")))
               fn))
           @executor-registry))

    :else nil))

(defn get-executor-info
  "Get full information about a loaded executor.

   Returns {:tool-id :tool-name :namespace :fn :source-code} or nil."
  [tool-id]
  (get @executor-registry tool-id))

(defn list-loaded-executors
  "List all currently loaded executors.

   Returns seq of {:tool-id :tool-name :namespace :fn-reference}."
  []
  (for [[tool-id {:keys [tool-name namespace]}] @executor-registry]
    {:tool-id tool-id
     :tool-name tool-name
     :namespace namespace
     :fn-reference (gen/generate-executor-fn-reference tool-id tool-name)}))

;; ============================================================================
;; Registry Management
;; ============================================================================

(defn clear-registry!
  "Clear all loaded executors. Useful for testing."
  []
  (doseq [[tool-id _] @executor-registry]
    (unload-executor! tool-id))
  (reset! executor-registry {})
  (reset! name->id-index {}))

(defn registry-stats
  "Get statistics about the executor registry."
  []
  {:loaded-count (count @executor-registry)
   :tool-names (keys @name->id-index)})
