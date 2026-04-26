(ns ai.obney.orc.orc-service.core.sci-sandbox
  "SCI (Safe Clojure Interpreter) sandbox for repl-researcher code execution.

   Key features:
   - Safe execution environment (no eval, no IO, no requires)
   - MCP tools injected as callable functions
   - Stdout capture for iteration feedback
   - FINAL_ANSWER detection for convergence
   - Pre-execution validation: parse-check + free-symbol whitelist scan

   Note: This namespace has NO dependency on mcp-sheet-builder.
   MCP tool invocation is injected via :call-tool-fn parameter."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u])
  (:import [java.io StringWriter PushbackReader StringReader]))

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

(defn- build-tool-bindings
  "Build SCI bindings for MCP tools.

   Flat names ('lookup') are bound in the user namespace (backward compat).
   Namespaced names ('linear/list_issues') are bound in per-server SCI namespaces,
   enabling namespace-qualified calls like (linear/list_issues {:project \"abc\"}).

   Returns {:flat {sym fn} :namespaces {ns-sym {fn-sym fn}}}"
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
   - Host-provided extra bindings (via :extra-bindings)

   Options:
   - :call-tool-fn - Function (tool-name args-map) -> result for MCP calls
   - :mcp-tools - Vector of MCP tool names to inject
   - :browser-tools - Vector of browser tool names to inject (e.g., [\"open\" \"snapshot\" \"click\"])
   - :stdout-writer - StringWriter to capture stdout (optional)
   - :extra-bindings - Map of {symbol value} merged into the user namespace and
                       global SCI bindings. Generic mechanism; the sandbox does
                       not interpret these. Used by callers to inject things
                       like `inputs`, `context`, or RLM `predict`/`final!` fns."
  [{:keys [call-tool-fn mcp-tools browser-tools stdout-writer extra-bindings]}]
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

        extra (or extra-bindings {})

        ;; Merge all bindings: MCP tools + browser tools + print overrides + helpers + extras
        all-bindings (merge flat browser-bindings print-bindings
                            {'FINAL_ANSWER final-answer-fn
                             'realize-all realize-all}
                            extra)]

    (sci/init
     {:namespaces (merge {'clojure.core safe-core-final
                          'user (merge flat browser-bindings extra)}
                         namespaces)  ;; e.g., {'linear {'list_issues <fn>}}
      :bindings all-bindings})))

;; ============================================================================
;; Pre-Execution Validation
;; ============================================================================

(defn validate-syntax
  "Try to read every top-level form in `code-string` using SCI's reader.
   Returns nil on success, or {:type :syntax :message … :line … :column …}
   on failure. Surfaced to the executor BEFORE eval so the LLM sees a clear
   syntax-error diagnostic on the next iteration prompt instead of an opaque
    'Output repeated' from the convergence detector."
  [code-string]
  (let [rdr (sci/reader (str code-string))]
    (try
      (loop []
        (let [form (sci/parse-next (sci/init {}) rdr)]
          (when-not (= ::sci/eof form)
            (recur))))
      nil
      (catch Exception e
        (let [msg (.getMessage e)
              data (ex-data e)]
          (cond-> {:type :syntax :message msg}
            (:line data)   (assoc :line (:line data))
            (:column data) (assoc :column (:column data))))))))

(defn- safe-bound-symbols
  "Build the union set of symbol names a SCI sandbox built with the given
   options would resolve. Includes:
     - safe-clojure-core (whitelisted clojure.core fns)
     - SCI built-ins: special forms + macros (let, fn, if, when, do, …)
     - MCP flat tool names + namespaced tool names (linear/list_issues)
     - Browser tool names
     - Caller-supplied :extra-bindings keys
   Used by `unbound-symbols` for pre-execution scanning."
  [{:keys [call-tool-fn mcp-tools browser-tools extra-bindings]}]
  (let [;; SCI special forms + clojure.core macros that are always available
        sci-builtins
        '#{let fn fn* defn defn- def let* loop recur if when when-not when-let
           if-let if-not cond condp case do doseq dotimes for ->> -> as-> some-> some->>
           and or not throw try catch finally
           binding letfn delay deref future future-call promise
           future-cancel future-cancelled? future-done?
           ;; collection/seq macros
           lazy-seq cons cycle iterate
           ;; reader macros / quoting
           quote unquote
           ;; commonly-used clojure.core macros from safe-core
           comment when-some if-some
           with-redefs with-out-str with-in-str with-bindings
           assert ignore
           ;; printing already in safe-clojure-core but include as macros too
           print println prn pr pr-str print-str println-str}
        flat-tools (when (and call-tool-fn mcp-tools)
                     (set
                       (for [t mcp-tools
                             :when (not (str/includes? t "/"))]
                         (symbol t))))
        namespaced-tools (when (and call-tool-fn mcp-tools)
                           (set
                             (for [t mcp-tools
                                   :when (str/includes? t "/")]
                               (symbol t))))
        browser-syms (when (seq browser-tools)
                       (set (map symbol browser-tools)))
        extra-syms (when (map? extra-bindings)
                     (set (filter symbol? (keys extra-bindings))))]
    (into #{}
          cat
          [safe-clojure-core
           sci-builtins
           '#{FINAL_ANSWER realize-all}
           flat-tools
           namespaced-tools
           browser-syms
           extra-syms])))

(defn- collect-free-symbols
  "Walk a parsed Clojure form and collect every (free) symbol reference.
   Locals introduced by let/fn/loop/etc. are tracked and excluded. This is
   intentionally a best-effort scan: we want to flag genuine sandbox-escape
   attempts (System/currentTimeMillis, slurp, eval) without false-positives
   on let-bound names."
  [form]
  (let [free (volatile! #{})
        walk-form
        (fn walk-form [bound f]
          (cond
            ;; Symbol — flag if not bound and not a Java class (uppercase)
            (symbol? f)
            (let [n (name f)
                  ns (namespace f)]
              ;; Skip:
              ;; - Locally bound symbols
              ;; - Bare keywords (already a different type)
              ;; - Constructor / static-method dot forms (Class.) handled separately below
              (when-not (contains? bound f)
                (vswap! free conj f)))

            ;; Vectors / sets / maps — recurse into all values
            (vector? f) (doseq [x f] (walk-form bound x))
            (set? f)    (doseq [x f] (walk-form bound x))
            (map? f)    (doseq [[k v] f] (walk-form bound k) (walk-form bound v))

            ;; Lists / cons cells — handle binding forms specially
            (sequential? f)
            (let [[head & tail] f]
              (cond
                (or (= head 'let) (= head 'let*) (= head 'when-let)
                    (= head 'when-some) (= head 'if-let) (= head 'if-some)
                    (= head 'loop) (= head 'binding) (= head 'with-redefs)
                    (= head 'doseq) (= head 'dotimes) (= head 'for))
                (let [[binding-vec & body] tail
                      ;; Binding vec like [a 1 b 2] — collect even-index symbols
                      new-bound (into bound
                                      (->> binding-vec
                                           (partition 2)
                                           (map first)
                                           (filter symbol?)))]
                  ;; Walk binding values (odd-index) with OLD bound set
                  (doseq [[_ v] (partition 2 binding-vec)] (walk-form bound v))
                  ;; Walk body with new bound set
                  (doseq [b body] (walk-form new-bound b)))

                (or (= head 'fn) (= head 'fn*) (= head 'defn) (= head 'defn-) (= head 'defmacro))
                ;; Skip the name (if any) and the arg vector(s); collect args as bound.
                (let [forms (if (symbol? (first tail)) (rest tail) tail)
                      ;; Each form may be a single-arity (args body+) or multi-arity ((args body+)…)
                      arity-forms (if (vector? (first forms))
                                    [forms]
                                    forms)]
                  (doseq [arity arity-forms]
                    (when (sequential? arity)
                      (let [[args & body] arity
                            arg-syms (->> args (filter symbol?) (remove #{'& '_}) set)
                            inner-bound (into bound arg-syms)]
                        (doseq [b body] (walk-form inner-bound b))))))

                (= head 'quote)
                ;; Don't walk inside quoted forms
                nil

                :else
                (do (walk-form bound head)
                    (doseq [arg tail] (walk-form bound arg)))))

            :else nil))]
    (walk-form #{} form)
    @free))

(defn- java-interop?
  "Symbols like Class. or Class/method or .method are Java interop, not free
   refs we'd want to flag against the SCI bindings whitelist. We surface
   them separately — they're equally not allowed in the sandbox but want a
   different message ('Java interop is disallowed' vs 'symbol not in
   sandbox').

   Defensive against edge cases (empty/nil ns, special symbols like `/`)."
  [sym]
  (when (symbol? sym)
    (let [n (name sym)
          ns (namespace sym)]
      (or (and ns (pos? (count ns))
               (Character/isUpperCase ^char (.charAt ^String ns 0)))   ;; ns name starts uppercase → Class/method
          (and (pos? (count n)) (.endsWith ^String n "."))             ;; constructor: Class.
          (and (pos? (count n)) (.startsWith ^String n "."))))))         ;; method: .method

(def ^:private dangerous-bare-symbols
  "Bare (non-namespaced) symbols that we explicitly flag as sandbox escapes.
   These are the well-known I/O / eval / process / FS shells the LLM tends
   to reach for. Anything else that's truly unbound will be caught by SCI
   itself at eval time — we surface it via the iteration error path."
  '#{slurp spit eval load load-string load-file load-reader require
     in-ns ns create-ns alias remove-ns find-ns
     read read-string read-line
     exec sh runtime
     intern resolve var var-get var-set
     System Runtime Process ProcessBuilder Thread})

(defn- safe-namespace?
  "A namespaced symbol like `clojure.string/join` or `pdf/page-count` is
   considered safe IF either:
     - the namespace is a known SCI built-in (clojure.*, sci.*)
     - the symbol matches one of the caller-provided MCP tools / extra
       bindings (passed in `bound`)
   This is the permissive rule that prevents false-positives on common
   helpers like clojure.string/join while still catching slurp / eval."
  [sym bound]
  (let [ns (namespace sym)]
    (or
      (contains? bound sym)
      (and ns
           (or (str/starts-with? ns "clojure.")
               (str/starts-with? ns "sci.")
               ;; Caller may have passed namespaced tool symbols in `bound`
               (contains? bound (symbol ns nil))
               (contains? (set (map namespace bound)) ns))))))

(defn unbound-symbols
  "Scan code for OBVIOUSLY unsafe symbol references.

   Returns {:unbound #{…} :java-interop #{…}}. An empty result means no
   known sandbox-escape patterns were found.

   This is intentionally CONSERVATIVE on false-positives:
   - Bare symbols are flagged ONLY if they appear in `dangerous-bare-symbols`
     (slurp, eval, require, …). Other unbound bare symbols (like a typo'd
     local) will fail at SCI eval time with their own error — we don't
     reject them pre-emptively.
   - Java interop forms (System/foo, Class., .method) are ALWAYS flagged
     because they unambiguously reach into the JVM beyond SCI's whitelist.
   - Namespaced symbols pass if their namespace is `clojure.*` / `sci.*`
     (SCI built-ins) OR matches an MCP tool prefix / extra-binding.

   The executor uses this to produce LLM-actionable feedback for the cases
   we KNOW are sandbox escapes, while leaving SCI itself to handle ordinary
   resolution errors with its own diagnostic."
  [code-string sandbox-opts]
  (let [bound (safe-bound-symbols sandbox-opts)
        rdr (sci/reader (str code-string))
        forms (loop [acc []]
                (let [form (try
                             (sci/parse-next (sci/init {}) rdr)
                             (catch Exception _ ::sci/eof))]
                  (if (= ::sci/eof form) acc (recur (conj acc form)))))
        all-free (apply clojure.set/union (map collect-free-symbols forms))
        {:keys [interop bare]} (group-by #(if (java-interop? %) :interop :bare) all-free)
        bare-syms (->> bare
                       (remove #(or (nil? (namespace %))
                                    (safe-namespace? % bound))))
        ;; For bare (non-namespaced) symbols: only flag the dangerous ones.
        flagged-bare (->> bare-syms
                          (filter #(contains? dangerous-bare-symbols %))
                          set)
        ;; Also catch bare references to dangerous symbols where the symbol
        ;; itself is namespaced (e.g. user/slurp). We only flag the well-
        ;; known names listed above.
        flagged-dangerous (->> all-free
                               (filter #(or (contains? dangerous-bare-symbols (symbol (name %)))
                                            (and (namespace %)
                                                 (contains? dangerous-bare-symbols (symbol (namespace %))))))
                               set)
        unbound (clojure.set/union flagged-bare flagged-dangerous)]
    (cond-> {}
      (seq unbound) (assoc :unbound unbound)
      (seq interop) (assoc :java-interop (set interop)))))

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

(defn- iter-output-fingerprint
  "Build the comparable string for a single iteration result. Errors are
   included so 'two iterations crashed identically' counts as repetition."
  [result]
  (str (:stdout result) (:result result) (:error result)))

(defn repeated-output?
  "Check if the current iteration's output matches previous iterations
   (indicating convergence — either healthy stability or a stuck loop)."
  [history current-result]
  (let [current-str (iter-output-fingerprint current-result)
        recent-outputs (->> history
                            (take-last 2)
                            (map iter-output-fingerprint))]
    (some #(= % current-str) recent-outputs)))

(defn repeat-kind
  "Classify the kind of repetition between the current iteration and the
   recent history. Returns one of:
     - :error   — same error string as a recent iteration (LLM is stuck on
                  the same crash; next prompt should call this out plainly)
     - :output  — same successful output as a recent iteration (stable
                  result, also stuck — but not crashing)
     - nil      — no repetition detected
   Used by the executor to produce a sharper failure diagnostic than
   the generic 'Output repeated - possible infinite loop'."
  [history current-result]
  (let [recent (->> history (take-last 2))
        current-str (iter-output-fingerprint current-result)
        match (first (filter #(= (iter-output-fingerprint %) current-str) recent))]
    (cond
      (nil? match) nil
      (or (:error match) (:error current-result)) :error
      :else :output)))

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
