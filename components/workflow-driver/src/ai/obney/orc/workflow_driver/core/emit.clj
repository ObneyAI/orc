(ns ai.obney.orc.workflow-driver.core.emit
  "Apply LLM-emitted DSL forms to a target Sheet.

   The driver agent's per-turn output is a Clojure source string
   containing a complete (workflow …) form. This namespace parses that
   string in a tiny SCI sandbox bound only to the DSL constructors,
   validates the workflow name matches the target Sheet, calls the
   idempotent build-workflow! to apply changes, and returns a
   structural diff.

   Stable-name invariant: node IDs are deterministic from
   sheet-id + node name. If the agent keeps a node's name across
   emissions, the node ID — and all attached judge / GEPA / trace
   history — is preserved. Renames look like {:removed [old]
   :added [new]} in the diff, which is the signal to the agent that
   it's losing history."
  (:require [sci.core :as sci]
            [ai.obney.orc.orc-service.interface :as orc]))

(defn- dsl-bindings
  "Bind every DSL constructor symbol to its public orc-service
   function. The agent's emitted code can call these directly."
  []
  {'workflow         orc/workflow
   'blackboard       orc/blackboard
   'judges           orc/judges
   'sequence         orc/sequence
   'fallback         orc/fallback
   'parallel         orc/parallel
   'map-each         orc/map-each
   'condition        orc/condition
   'llm-condition    orc/llm-condition
   'llm              orc/llm
   'code             orc/code
   'repl-researcher  orc/repl-researcher
   'delegate         orc/delegate})

(defn parse-workflow-form
  "Parse a Clojure source string into a workflow-def map. SCI
   evaluates the form with only the DSL constructors in scope — no
   Java interop, no I/O, no eval.

   Returns {:status :ok :workflow-def <map>} or
           {:status :parse-error :error <message>}."
  [code-str]
  (try
    (let [ctx (sci/init {:bindings (dsl-bindings)})
          workflow-def (sci/eval-string* ctx code-str)]
      (cond
        (not (map? workflow-def))
        {:status :parse-error
         :error (str "Expected emission to return a workflow-def map, got "
                     (pr-str (type workflow-def)))}

        (nil? (:workflow-name workflow-def))
        {:status :parse-error
         :error "Workflow definition is missing :workflow-name (did you wrap with (workflow \"name\" …)?)"}

        :else
        {:status :ok :workflow-def workflow-def}))
    (catch Exception e
      {:status :parse-error :error (.getMessage e)})))

;; =============================================================================
;; Diff
;; =============================================================================

(def ^:private diff-keys
  "Fields compared when deciding if a node was modified. Excludes
   transient fields like :status, :children-ids (which change with
   structural edits), and :id (always different for a fresh node)."
  [:type :name :executor :model :fn :instruction
   :reads :writes :retry :judges
   :check :on-fail
   :success-policy :failure-policy
   :source-key :item-key :output-key :max-concurrency
   :target-sheet-id :timeout-ms :inherit-ontology?
   :rlm :max-iterations :mcp-tools :browser-tools])

(defn- node-fingerprint
  [node]
  (select-keys node diff-keys))

(defn diff-nodes
  "Compute {:added [name+id] :removed [name+id] :modified [name+id+changed-keys]}
   between two node-id→node maps."
  [before-by-id after-by-id]
  (let [before-ids (set (keys before-by-id))
        after-ids (set (keys after-by-id))
        added (clojure.set/difference after-ids before-ids)
        removed (clojure.set/difference before-ids after-ids)
        common (clojure.set/intersection before-ids after-ids)
        modified (->> common
                      (keep (fn [id]
                              (let [b (node-fingerprint (get before-by-id id))
                                    a (node-fingerprint (get after-by-id id))]
                                (when-not (= a b)
                                  (let [changed (->> diff-keys
                                                     (keep (fn [k]
                                                             (when (not= (get b k) (get a k))
                                                               k)))
                                                     vec)]
                                    {:node-id id
                                     :name (:name (get after-by-id id))
                                     :changed changed}))))))]
    {:added (mapv (fn [id]
                    {:node-id id
                     :name (:name (get after-by-id id))
                     :type (:type (get after-by-id id))})
                  added)
     :removed (mapv (fn [id]
                      {:node-id id
                       :name (:name (get before-by-id id))
                       :type (:type (get before-by-id id))})
                    removed)
     :modified (vec modified)}))

;; =============================================================================
;; submit-tree!
;; =============================================================================

(defn submit-tree!
  "Apply an LLM-emitted DSL string to the target Sheet.

   Pipeline:
     1. parse the string in the SCI sandbox
     2. verify the workflow name matches the target sheet
     3. capture pre-state node map (for diff)
     4. call orc/build-workflow! (idempotent on content hash)
     5. capture post-state, compute structural diff
     6. return {:status :ok :sheet-id … :diff {…}}

   Failure modes return {:status :parse-error|:name-mismatch|:build-error
   :error <msg>} so the driver loop can surface a structured rejection
   to the agent on its next turn.

   The agent must keep the workflow name equal to the target sheet's
   name; mismatched names would create a different sheet via
   build-workflow!'s deterministic name→sheet-id derivation."
  [ctx sheet-id code-str]
  (let [sheet (orc/get-sheet ctx sheet-id)]
    (cond
      (nil? sheet)
      {:status :build-error :error (str "No sheet for id " sheet-id)}

      :else
      (let [parsed (parse-workflow-form code-str)]
        (if (= :parse-error (:status parsed))
          parsed
          (let [{:keys [workflow-def]} parsed
                expected-name (:name sheet)
                emitted-name (:workflow-name workflow-def)]
            (if (not= emitted-name expected-name)
              {:status :name-mismatch
               :expected expected-name
               :got emitted-name
               :error (str "Emitted (workflow \"" emitted-name "\" …) but the target sheet's name is \""
                           expected-name "\". The driver's contract requires you keep the workflow name "
                           "equal to the target — mismatched names would create a different sheet.")}
              (try
                (let [before-nodes (orc/get-nodes-by-id ctx sheet-id)
                      built-id (orc/build-workflow! ctx workflow-def)
                      after-nodes (orc/get-nodes-by-id ctx sheet-id)
                      diff (diff-nodes before-nodes after-nodes)]
                  {:status :ok
                   :sheet-id built-id
                   :diff diff})
                (catch Exception e
                  {:status :build-error :error (.getMessage e)})))))))))
