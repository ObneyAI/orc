(ns ai.obney.orc.orc-service.core.tool-invocation
  "The single tool-invocation seam.

   Every node that calls a tool (researcher sandbox, checkpointed researcher,
   code leaf) goes through the functions here, so a declared contract is
   enforced identically everywhere (LeafExecutor invariants
   DeclaredToolContractsAreEnforced, ToolArgumentIdentityIsExact and
   ToolOutcomesAreStructured).

   Only explicitly declared `:arguments` / `:result` schemas are enforced; a
   side with no declared contract is a transparent pass-through. The tool
   always receives the caller's ORIGINAL arguments and the caller always
   receives the tool's ORIGINAL result: validation runs against a decoded view
   (string keys -> the schema's keyword keys) and never replaces a value.

   Every failure is an ex-info whose data carries `:orc.tool/outcome` (one of
   :invalid-arguments, :invalid-result, :tool-error) and `:orc.tool/name`. The
   message is safe: it names the tool and failing paths, never the values and
   never the underlying exception's message or data."
  (:require [malli.core :as m]
            [malli.transform :as mt]))

(defn- declared
  "The explicitly declared schema for `side` (:arguments / :result) of `tool`,
   or nil when none is declared."
  [contracts tool side]
  (let [schema (get-in contracts [tool side])]
    (when (and (some? schema) (not= :untyped schema))
      schema)))

(defn- outcome
  ([kind tool message] (outcome kind tool message nil))
  ([kind tool message cause]
   (ex-info message
            {:orc.tool/outcome kind
             :orc.tool/name tool}
            cause)))

(defn- key-spelling [k]
  (if (keyword? k) (name k) k))

(defn- alias-collision?
  "True when any map in `x`, at any depth, holds one name under two spellings
   (e.g. both :k and \"k\")."
  [x]
  (cond
    (map? x)
    (or (let [spellings (map key-spelling (keys x))]
          (not= (count spellings) (count (set spellings))))
        (some alias-collision? (vals x)))

    (sequential? x)
    (boolean (some alias-collision? x))

    (set? x)
    (boolean (some alias-collision? x))

    :else false))

(def ^:private key-decoder
  "Decodes ONLY map keys: a string key becomes the schema's keyword key of the
   same name. Values are never coerced, so validation judges what was supplied."
  (mt/transformer
   {:name :tool-contract-keys
    :decoders
    {:map {:compile
           (fn [schema _]
             (let [declared-keys (into #{} (filter keyword?) (map first (m/entries schema)))
                   by-name (into {} (map (juxt name identity)) declared-keys)]
               (fn [x]
                 (if (map? x)
                   (reduce-kv (fn [acc k v]
                                (assoc acc (if (string? k) (get by-name k k) k) v))
                              {} x)
                   x))))}}}))

(defn- decoded-view [schema value]
  (m/decode schema value key-decoder))

(defn- declared-keys
  "Every map-entry key declared anywhere in `schema`."
  [schema]
  (let [acc (volatile! #{})]
    (m/walk schema
            (fn [sch _ children _]
              (when (#{:map :multi} (m/type sch))
                (when (= :map (m/type sch))
                  (vswap! acc into (map first) (m/entries sch))))
              children))
    @acc))

(defn- safe-path
  "A failing path rendered so it can never echo what the caller supplied: an
   element survives only when it is an index or a key DECLARED in the schema;
   any other (a supplied undeclared or map-of key) becomes a placeholder."
  [declared path]
  (mapv (fn [el] (if (or (integer? el) (contains? declared el)) el "<undeclared>"))
        path))

(defn- failing-paths
  [schema value]
  (let [declared (declared-keys schema)]
    (->> (:errors (m/explain schema (decoded-view schema value)))
         (map (comp (partial safe-path declared) :in))
         distinct
         vec)))

(defn- check!
  "Throw the structured outcome `kind` unless `value` honours `schema` with no
   aliased keys."
  [kind tool schema value what]
  (when schema
    (when (alias-collision? value)
      (throw (outcome kind tool
                      (str "Tool " tool ": " what
                           " supply one field under two spellings of the same name"))))
    (let [decoded (decoded-view schema value)]
      (when-not (m/validate schema decoded)
        (throw (outcome kind tool
                        (str "Tool " tool ": " what
                             " violate the declared contract at "
                             (pr-str (failing-paths schema value)))))))))

(defn validate-arguments!
  "Throw {:orc.tool/outcome :invalid-arguments} unless `args` honour the
   declared argument contract of `tool`. No-op without a declared contract."
  [contracts tool args]
  (check! :invalid-arguments tool (declared contracts tool :arguments) args "arguments"))

(defn validate-result!
  "Throw {:orc.tool/outcome :invalid-result} unless `result` honours the
   declared result contract of `tool`. No-op without a declared contract."
  [contracts tool result]
  (check! :invalid-result tool (declared contracts tool :result) result "result"))

(defn tool-error
  "The structured :tool-error outcome for an exception thrown by the tool.
   The safe message carries the exception class, plus the message the host
   explicitly declared safe to share as `:orc.tool/message` in its ex-data.
   The exception's own message and data are never copied (they may echo
   credentials or request contents)."
  [tool ^Throwable e]
  (let [declared (:orc.tool/message (ex-data e))]
    (outcome :tool-error tool
             (str "Tool " tool " failed (" (.getName (class e)) ")"
                  (when (string? declared) (str ": " declared)))
             e)))

(defn contracted?
  "True when `tool` has any declared argument or result contract."
  [contracts tool]
  (boolean (or (declared contracts tool :arguments)
               (declared contracts tool :result))))

(defn guarded-call-tool-fn
  "Wrap `call-tool-fn` so each call is checked against `contracts` (tool name ->
   {:arguments schema :result schema}). The result has the same arities as
   `call-tool-fn` ([tool args] and [tool args tool-context]).

   `:wrap-tool-errors?` (default true) turns any exception the tool itself
   throws into the safe :tool-error outcome, for every tool, typed or not —
   the model-facing (researcher) behaviour. A code leaf passes false: consumer
   code owns its host capability and sees the tool's own exception unchanged;
   it still receives the contract outcomes."
  [call-tool-fn contracts & {:keys [wrap-tool-errors?] :or {wrap-tool-errors? true}}]
  (if (or (nil? call-tool-fn) (and (empty? contracts) (not wrap-tool-errors?)))
    call-tool-fn
    (let [guarded (fn [tool args invoke]
                    (let [typed? (contracted? contracts tool)]
                      (when typed? (validate-arguments! contracts tool args))
                      (let [result (if wrap-tool-errors?
                                     (try
                                       (invoke)
                                       (catch Exception e
                                         (throw (tool-error tool e))))
                                     (invoke))]
                        (when typed? (validate-result! contracts tool result))
                        result)))]
      (fn
        ([tool args] (guarded tool args #(call-tool-fn tool args)))
        ([tool args tool-context]
         (guarded tool args #(call-tool-fn tool args tool-context)))))))
