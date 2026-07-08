(ns ai.obney.orc.orc-service.core.sci-sandbox
  "SCI (Safe Clojure Interpreter) sandbox for repl-researcher code execution.

   Key features:
   - Safe execution environment (no eval, no IO, no requires)
   - MCP tools injected as callable functions
   - Stdout capture for iteration feedback
   - FINAL_ANSWER detection for convergence

   Note: This namespace has NO dependency on mcp-sheet-builder.
   MCP tool invocation is injected via :call-tool-fn parameter."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import [java.io StringWriter]))

;; ============================================================================
;; Safe Function Whitelist
;; ============================================================================

(def safe-clojure-core
  "Safe clojure.core functions allowed in the sandbox."
  '[+ - * / mod quot rem
    = not= < > <= >= compare
    str pr-str prn-str println print
    inc dec min max abs
    first rest next last butlast
    cons conj concat into
    map filter remove reduce
    take drop take-while drop-while
    partition partition-all partition-by
    sort sort-by reverse shuffle
    get get-in assoc assoc-in dissoc update update-in
    select-keys merge merge-with
    keys vals contains? find
    count empty? not-empty seq vec set list
    apply comp partial juxt
    identity constantly
    some every? not-any? not-every?
    group-by frequencies
    zipmap interleave interpose
    repeat range iterate
    true? false? nil? some? boolean
    keyword keyword? symbol symbol? string? number? integer? float? map? vector? set? list? coll? seq? fn?
    name namespace
    re-find re-matches re-seq
    subs format
    type class])

;; ============================================================================
;; MCP Tool Binding
;; ============================================================================

(defn- create-mcp-tool-fn
  "Create a function that calls an MCP tool and returns the result.

   call-tool-fn is a function of (tool-name args-map) -> result.

   The returned function accepts either:
   - A map of arguments: (tool {:query \"search term\"})
   - Keyword arguments: (tool :query \"search term\")"
  [call-tool-fn tool-name]
  (fn [& args]
    (let [args-map (cond
                     ;; Single map argument
                     (and (= 1 (count args)) (map? (first args)))
                     (first args)

                     ;; Keyword arguments
                     (even? (count args))
                     (apply hash-map args)

                     ;; Single non-map argument (assume it's the first required param)
                     :else
                     {"arg" (first args)})]
      (u/trace ::sci-mcp-call {:tool tool-name :args args-map}
        (try
          (call-tool-fn tool-name args-map)
          (catch Exception e
            {:error (.getMessage e)}))))))

(defn- parse-tool-name
  "Parse a tool name into [server-sym fn-sym] or [nil fn-sym].
   \"linear/list_issues\" -> ['linear 'list_issues]
   \"lookup\"             -> [nil 'lookup]"
  [tool-name]
  (if-let [idx (str/index-of tool-name "/")]
    [(symbol (subs tool-name 0 idx))
     (symbol (subs tool-name (inc idx)))]
    [nil (symbol tool-name)]))

(defn build-tool-bindings
  "Build SCI bindings for MCP tools.

   Flat names ('lookup') are bound in the user namespace (backward compat).
   Namespaced names ('linear/list_issues') are bound in per-server SCI namespaces,
   enabling namespace-qualified calls like (linear/list_issues {:project \"abc\"}).

   Returns {:flat {sym fn} :namespaces {ns-sym {fn-sym fn}}}

   Public so the recursive RLM sandbox (rlm_sandbox.clj) can wire the SAME MCP
   tool bindings the plain researcher gets — see build-rlm-context."
  [call-tool-fn mcp-tools]
  (if (nil? call-tool-fn)
    {:flat {} :namespaces {}}
    (let [parsed (map (fn [t]
                        (let [[server sym] (parse-tool-name t)]
                          {:full t :server server :sym sym}))
                      mcp-tools)
          flat-tools (filter (comp nil? :server) parsed)
          ns-tools   (remove (comp nil? :server) parsed)]
      {:flat (reduce (fn [acc {:keys [full sym]}]
                       (assoc acc sym (create-mcp-tool-fn call-tool-fn full)))
                     {} flat-tools)
       :namespaces (reduce-kv
                     (fn [acc server tools]
                       (assoc acc server
                              (reduce (fn [m {:keys [full sym]}]
                                        (assoc m sym (create-mcp-tool-fn call-tool-fn full)))
                                      {} tools)))
                     {}
                     (group-by :server ns-tools))})))

;; ============================================================================
;; Browser Tool Bindings (agent-browser CLI)
;; ============================================================================

(defn- build-browser-tool-bindings
  "Build SCI bindings for agent-browser tools.

   browser-tools is a vector of tool names to expose.
   Returns a map of {symbol -> fn}."
  [browser-tools]
  (when (seq browser-tools)
    ;; Lazily require agent-browser to avoid circular deps
    (try
      (require '[ai.obney.orc.agent-browser.interface :as browser])
      (let [all-tools (deref (resolve 'ai.obney.orc.agent-browser.interface/browser-tools))
            selected (select-keys all-tools browser-tools)]
        (reduce-kv
         (fn [acc name fn]
           (assoc acc (symbol name) fn))
         {}
         selected))
      (catch Exception e
        (u/log ::browser-tools-not-available :error (.getMessage e))
        {}))))

;; ============================================================================
;; SCI Context Building
;; ============================================================================

(defn build-sci-context
  "Build a SCI execution context with MCP and browser tools injected.

   The context provides:
   - Safe subset of clojure.core
   - MCP tools as callable functions (via :call-tool-fn)
   - Browser tools as direct functions (via :browser-tools)
   - Custom bindings for print functions (capture stdout)

   Options:
   - :call-tool-fn - Function (tool-name args-map) -> result for MCP calls
   - :mcp-tools - Vector of MCP tool names to inject
   - :browser-tools - Vector of browser tool names to inject (e.g., [\"open\" \"snapshot\" \"click\"])
   - :stdout-writer - StringWriter to capture stdout (optional)"
  [{:keys [call-tool-fn mcp-tools browser-tools stdout-writer]}]
  (let [{:keys [flat namespaces]} (build-tool-bindings call-tool-fn mcp-tools)

        ;; Browser tool bindings (shell-based, no session management)
        browser-bindings (build-browser-tool-bindings browser-tools)

        ;; Build safe clojure.core namespace
        core-publics (ns-publics 'clojure.core)
        safe-core (select-keys core-publics safe-clojure-core)

        ;; Custom print functions that write to our StringWriter.
        ;; When no stdout-writer is provided, we leave the real clojure.core
        ;; print functions in place so that execute-code's
        ;; (binding [*out* ...]) captures output naturally.
        safe-core-final (if stdout-writer
                          (let [print-fn (fn [& args]
                                          (.write stdout-writer (str (apply str args))))
                                println-fn (fn [& args]
                                             (.write stdout-writer (str (apply str args) "\n")))
                                prn-fn (fn [& args]
                                         (.write stdout-writer (str (apply str (map pr-str args)) "\n")))]
                            (assoc safe-core
                                   'print print-fn
                                   'println println-fn
                                   'prn prn-fn))
                          safe-core)

        print-bindings (when stdout-writer
                         {'print (fn [& args]
                                   (.write stdout-writer (str (apply str args))))
                          'println (fn [& args]
                                     (.write stdout-writer (str (apply str args) "\n")))
                          'prn (fn [& args]
                                 (.write stdout-writer (str (apply str (map pr-str args)) "\n")))})

        ;; Helper to realize all lazy sequences recursively
        realize-all (fn realize-all [x]
                      (cond
                        (map? x) (into {} (map (fn [[k v]] [k (realize-all v)]) x))
                        (coll? x) (into (empty x) (map realize-all x))
                        :else x))

        ;; FINAL_ANSWER helper that properly realizes lazy seqs
        final-answer-fn (fn [value]
                          (let [realized (realize-all value)]
                            (str "FINAL_ANSWER: " (pr-str realized))))

        ;; Merge all bindings: MCP tools + browser tools + print overrides + helpers
        all-bindings (merge flat browser-bindings print-bindings
                            {'FINAL_ANSWER final-answer-fn
                             'realize-all realize-all})]

    (sci/init
     {:namespaces (merge {'clojure.core safe-core-final
                          'user (merge flat browser-bindings)}
                         namespaces)  ;; e.g., {'linear {'list_issues <fn>}}
      :bindings all-bindings
      ;; Expose JVM classes SCI's evaluator references internally when
      ;; constructing collections from model-generated code. Without these,
      ;; map literals with computed values in model-written fns fail at
      ;; invocation with "Could not resolve symbol: clojure.lang.PersistentArrayMap/...".
      ;; The classes listed here are the impl types Clojure's reader/compiler
      ;; references when building persistent collections.
      :classes {'clojure.lang.PersistentArrayMap clojure.lang.PersistentArrayMap
                'clojure.lang.PersistentHashMap clojure.lang.PersistentHashMap
                'clojure.lang.PersistentVector clojure.lang.PersistentVector
                'clojure.lang.PersistentHashSet clojure.lang.PersistentHashSet
                'clojure.lang.PersistentTreeMap clojure.lang.PersistentTreeMap}})))

;; ============================================================================
;; Code Execution
;; ============================================================================

(defn execute-code
  "Execute Clojure code in the SCI sandbox.

   Returns a map with:
   - :stdout - Captured stdout as string
   - :result - The evaluation result (pr-str'd)
   - :error - Error message if execution failed
   - :raw-result - The actual Clojure value (not stringified)"
  [sci-ctx code-string]
  (let [stdout-writer (StringWriter.)]
    (try
      (let [result (binding [*out* stdout-writer]
                     (sci/eval-string* sci-ctx code-string))]
        {:stdout (str stdout-writer)
         :result (pr-str result)
         :raw-result result
         :error nil})
      (catch Exception e
        {:stdout (str stdout-writer)
         :result nil
         :raw-result nil
         :error (.getMessage e)}))))

;; ============================================================================
;; FINAL_ANSWER Detection
;; ============================================================================

(def final-answer-patterns
  "Patterns that indicate a final answer in LLM output or code result.
   Order matters - more specific patterns first, generic patterns last.
   Uses non-greedy matching and explicit boundary handling to avoid
   capturing trailing quotes. Uses (?s) for multi-line JSON support."
  [;; Most specific: (str \"FINAL_ANSWER: \" value) form
   #"\(str\s+\"FINAL_ANSWER:\s*\"\s*([^)\s]+)\s*\)"
   ;; Quoted: \"FINAL_ANSWER: value\"
   #"\"FINAL_ANSWER:\s*([^\"]+)\""
   ;; Generic - multi-line support with (?s) flag, captures to end of string
   #"(?s)FINAL_ANSWER:\s*(.+?)\"?\z"
   #"(?s)FINAL-ANSWER:\s*(.+?)\"?\z"])

(defn- try-parse-edn
  "Try to parse a string as EDN. Returns parsed value or nil on failure.

   Handles escaped quotes from pr-str output by unescaping first."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [;; Unescape the string if it contains escaped quotes from pr-str
          unescaped (-> s
                        (str/replace "\\\"" "\"")
                        (str/replace "\\\\" "\\"))]
      (try
        (clojure.edn/read-string unescaped)
        (catch Exception _
          ;; Try original string if unescaping broke something
          (try
            (clojure.edn/read-string s)
            (catch Exception _ nil)))))))

(defn extract-final-answer
  "Extract the final answer from a string if present.
   Returns nil if no final answer pattern is found.

   Attempts to parse the extracted value as EDN if it looks like a data structure.
   Returns the parsed EDN if successful, otherwise returns the raw string."
  [s]
  (when (string? s)
    (some (fn [pattern]
            (when-let [[_ answer] (re-find pattern s)]
              (let [trimmed (str/trim answer)]
                ;; Try to parse as EDN if it looks like a data structure
                (if (or (str/starts-with? trimmed "[")
                        (str/starts-with? trimmed "{")
                        (str/starts-with? trimmed "("))
                  (or (try-parse-edn trimmed) trimmed)
                  trimmed))))
          final-answer-patterns)))

(defn contains-final-answer?
  "Check if a string contains a FINAL_ANSWER marker."
  [s]
  (boolean (extract-final-answer s)))

;; ============================================================================
;; Convergence Detection
;; ============================================================================

(defn repeated-output?
  "Check if the current output matches previous outputs (indicating convergence)."
  [history current-result]
  (let [current-str (str (:stdout current-result) (:result current-result))
        recent-outputs (->> history
                            (take-last 2)
                            (map #(str (:stdout %) (:result %))))]
    (some #(= % current-str) recent-outputs)))

;; ============================================================================
;; High-Level Execution
;; ============================================================================

(defn execute-with-mcp
  "Execute code with MCP and/or browser tools available.

   This is a convenience function that builds context and executes in one step.

   Options:
   - :call-tool-fn - Function (tool-name args-map) -> result for MCP tools
   - :mcp-tools - Vector of MCP tool names to inject
   - :browser-tools - Vector of agent-browser tool names to inject
   - :code - Code string to execute

   Returns:
   - :stdout - Captured output
   - :result - Evaluation result
   - :error - Error if any
   - :final-answer - Extracted answer if FINAL_ANSWER found"
  [{:keys [call-tool-fn mcp-tools browser-tools code]}]
  (let [ctx (build-sci-context {:call-tool-fn call-tool-fn
                                :mcp-tools mcp-tools
                                :browser-tools browser-tools})
        exec-result (execute-code ctx code)
        ;; Try raw-result first (unescaped), then fall back to pr-str'd result
        raw (when (string? (:raw-result exec-result))
              (:raw-result exec-result))
        final-answer (or (when raw (extract-final-answer raw))
                         (extract-final-answer (:result exec-result))
                         (extract-final-answer (:stdout exec-result)))]
    (assoc exec-result :final-answer final-answer)))
