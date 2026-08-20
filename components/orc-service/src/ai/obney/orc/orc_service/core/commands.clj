(ns ai.obney.orc.orc-service.core.commands
  "Behavior Tree Sheet command handlers.

   All commands follow the Grain pattern:
   - Take context including event-store and command data
   - Return {:command-result/events [...]} on success
   - Return cognitect anomaly on failure
   - Last write wins (no optimistic concurrency)"
  (:require [ai.obney.orc.orc-service.core.blackboard-schema :as blackboard-schema]
            [ai.obney.orc.orc-service.core.profile :as profile]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.metadata :as metadata]
            [ai.obney.orc.orc-service.core.value-storage :as value-storage]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [cognitect.anomalies :as anom]
            [malli.core :as m]
            [malli.error :as me]))

;; =============================================================================
;; Auth Predicate
;; =============================================================================

(defn authenticated?
  "Authorization predicate - checks if auth-claims are present in context."
  [ctx]
  (some? (:auth-claims ctx)))

(defn- schema-error
  "Return a stable explanation when value violates schema, otherwise nil."
  [key schema value]
  (try
    (when-not (m/validate schema value)
      (str "Blackboard schema validation failed for " (pr-str key) ": "
           (me/humanize (m/explain schema value))))
    (catch Exception e
      (str "Blackboard schema validation failed for " (pr-str key)
           " because its schema is invalid: " (.getMessage e)))))

(defn- invalid-seed-input
  "Validate only caller-supplied keys. Blackboard defaults are already durable
   values and are not reinterpreted as required execution inputs here."
  [snapshot inputs]
  (some (fn [[key value]]
          (when-let [schema (get-in snapshot [:blackboard-entries key :schema])]
            (schema-error key schema value)))
        inputs))

(defn- snapshot-schema-feedback
  [snapshot]
  (when-let [violations (seq (blackboard-schema/schema-map-violations
                              (:blackboard-schema snapshot)))]
    (blackboard-schema/feedback violations)))

;; =============================================================================
;; Sheet Commands
;; =============================================================================

(defcommand :sheet create-sheet
  {:authorized? authenticated?}
  "Create a new sheet. Name must be unique.
   If sheet-id is provided, uses that ID (for deterministic UUIDs).
   Otherwise generates a random UUID."
  [{{:keys [name sheet-id]} :command
    :as ctx}]
  (let [existing (rm/get-sheet-by-name ctx name)]
    (if existing
      {::anom/category ::anom/conflict
       ::anom/message (str "Sheet with name '" name "' already exists")
       :existing-sheet-id (:id existing)}
      (let [new-sheet-id (or sheet-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/sheet-created
            :tags #{[:sheet new-sheet-id]}
            :body {:sheet-id new-sheet-id
                   :name name}})]}))))

(defcommand :sheet rename-sheet
  {:authorized? authenticated?}
  "Rename an existing sheet. New name must be unique."
  [{{:keys [sheet-id name]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        existing (rm/get-sheet-by-name ctx name)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (and existing (not= (:id existing) sheet-id))
      {::anom/category ::anom/conflict
       ::anom/message (str "Sheet with name '" name "' already exists")
       :existing-sheet-id (:id existing)}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/sheet-renamed
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :old-name (:name sheet)
                 :new-name name}})]})))

(defcommand :sheet delete-sheet
  {:authorized? authenticated?}
  "Delete a sheet and all its nodes."
  [{{:keys [sheet-id]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/sheet-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id}})]})))

(defcommand :sheet set-content-hash
  {:authorized? authenticated?}
  "Store a content hash on a sheet for idempotent rebuild detection."
  [{{:keys [sheet-id content-hash]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/content-hash-set
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :content-hash content-hash}})]})))

;; =============================================================================
;; Node Commands
;; =============================================================================

(defcommand :sheet create-node
  {:authorized? authenticated?}
  "Create a new node in a sheet.
   If parent-id is nil, creates a root node.
   Index specifies position among siblings (default 0)."
  [{{:keys [sheet-id node-id type parent-id index]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        has-root? (some? (:root-node-id sheet))
        parent (when parent-id (rm/get-node ctx sheet-id parent-id))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      ;; If no parent, this would be a root node - check if one exists
      (and (nil? parent-id) has-root?)
      {::anom/category ::anom/conflict
       ::anom/message "Sheet already has a root node"}

      ;; If parent specified, it must exist
      (and parent-id (not parent))
      {::anom/category ::anom/not-found
       ::anom/message "Parent node not found"}

      :else
      (let [new-node-id (or node-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/node-created
            :tags #{[:sheet sheet-id]
                    [:node new-node-id]}
            :body (cond-> {:sheet-id sheet-id
                           :node-id new-node-id
                           :type type}
                    parent-id (assoc :parent-id parent-id)
                    index (assoc :index index))})]}))))

(defcommand :sheet move-node
  {:authorized? authenticated?}
  "Move a node to a new parent at specified index."
  [{{:keys [sheet-id node-id new-parent-id index]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        new-parent (rm/get-node ctx sheet-id new-parent-id)
        nodes-by-id (rm/get-nodes-by-id ctx sheet-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not new-parent)
      {::anom/category ::anom/not-found
       ::anom/message "New parent node not found"}

      ;; Check for cycles - can't move node under its own descendant
      (rm/is-descendant? nodes-by-id node-id new-parent-id)
      {::anom/category ::anom/conflict
       ::anom/message "Cannot move node under its own descendant"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-moved
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :new-parent-id new-parent-id
                         :index index}
                  (:parent-id node) (assoc :old-parent-id (:parent-id node)))})]})))

(defcommand :sheet reorder-node
  {:authorized? authenticated?}
  "Reorder a node among its siblings."
  [{{:keys [sheet-id node-id index]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        parent-id (:parent-id node)
        parent (when parent-id (rm/get-node ctx sheet-id parent-id))
        current-children (when parent (:children-ids parent))
        current-index (when current-children
                        (.indexOf (vec current-children) node-id))]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (nil? parent-id)
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot reorder root node"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-reordered
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id
                 :old-index (or current-index 0)
                 :new-index index}})]})))

(defcommand :sheet delete-node
  {:authorized? authenticated?}
  "Delete a node from a sheet.
   Node must have no children (delete children first)."
  [{{:keys [sheet-id node-id]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (seq (:children-ids node))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot delete node with children. Delete children first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-deleted
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id}})]})))

(defcommand :sheet set-node-name
  {:authorized? authenticated?}
  "Set the name of a node."
  [{{:keys [sheet-id node-id name]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-name-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :name name}
                  (:name node) (assoc :previous-name (:name node)))})]})))

(defcommand :sheet set-node-instruction
  {:authorized? authenticated?}
  "Set the instruction for a leaf node."
  [{{:keys [sheet-id node-id instruction]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have instructions"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-instruction-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction}
                  (:instruction node) (assoc :previous-instruction (:instruction node)))})]})))

(defcommand :sheet set-node-context
  {:authorized? authenticated?}
  "Set the ontology context for a node (for self-learning injection).

   Originally restricted to :leaf nodes; relaxed in C-1 to also accept
   :repl-researcher nodes, since the runtime apply-ontology-context
   pipeline (todo_processors.clj) reads :context from any node with
   :instruction and prepends formatted principles to it. The read-model
   projection is generic so no other change was needed."
  [{{:keys [sheet-id node-id context]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not (#{:leaf :repl-researcher} (:type node)))
      {::anom/category ::anom/incorrect
       ::anom/message "Only :leaf and :repl-researcher nodes can have context"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-context-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :context context}
                  (:context node) (assoc :previous-context (:context node)))})]})))

(defcommand :sheet set-node-io
  {:authorized? authenticated?}
  "Set the reads/writes (blackboard keys) for a leaf node."
  [{{:keys [sheet-id node-id reads writes]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have reads/writes"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-io-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :reads (vec reads)
                         :writes (vec writes)}
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node)))})]})))

(defcommand :sheet set-node-decorators
  {:authorized? authenticated?}
  "Set decorators for a node."
  [{{:keys [sheet-id node-id decorators]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-decorators-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :decorators (vec decorators)}
                  (seq (:decorators node)) (assoc :previous-decorators (:decorators node)))})]})))

(defcommand :sheet set-node-check
  {:authorized? authenticated?}
  "Set the condition check for a condition node."
  [{{:keys [sheet-id node-id check]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        check-key (:key check)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :condition (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only condition nodes can have checks"}

      (not (contains? blackboard check-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Blackboard key '" check-key "' not declared")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-check-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :check check}
                  (:check node) (assoc :previous-check (:check node)))})]})))

(defcommand :sheet set-node-executor
  {:authorized? authenticated?}
  "Set the executor configuration for a leaf node.
   - :ai executor uses ORC LLM with optional model selection
   - :code executor runs a Clojure function
   - :tool executor directly invokes a tool"
  [{{:keys [sheet-id node-id executor model fn tools options tool-caller-fn]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have executors"}

      (and (= executor :code) (not fn))
      {::anom/category ::anom/incorrect
       ::anom/message "Code executor requires :fn (fully-qualified function symbol)"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-executor-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :executor executor}
                  model (assoc :model model)
                  fn (assoc :fn fn)
                  tools (assoc :tools (vec tools))
                  options (assoc :options options)
                  tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
                  (:executor node) (assoc :previous-executor (:executor node))
                  (:model node) (assoc :previous-model (:model node))
                  (:fn node) (assoc :previous-fn (:fn node))
                  (:tools node) (assoc :previous-tools (:tools node))
                  (:options node) (assoc :previous-options (:options node)))})]})))

(defcommand :sheet set-node-retry
  {:authorized? authenticated?}
  "Set retry configuration for a leaf node."
  [{{:keys [sheet-id node-id retry]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can have retry configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-retry-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :retry retry}
                  (:retry node) (assoc :previous-retry (:retry node)))})]})))

(defcommand :sheet set-parallel-config
  {:authorized? authenticated?}
  "Set configuration for a parallel node."
  [{{:keys [sheet-id node-id success-policy failure-policy]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :parallel (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only parallel nodes can have parallel configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/parallel-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id}
                  success-policy (assoc :success-policy success-policy)
                  failure-policy (assoc :failure-policy failure-policy)
                  (:success-policy node) (assoc :previous-success-policy (:success-policy node))
                  (:failure-policy node) (assoc :previous-failure-policy (:failure-policy node)))})]})))

(defcommand :sheet set-map-each-config
  {:authorized? authenticated?}
  "Set configuration for a map-each node."
  [{{:keys [sheet-id node-id source-key item-key output-key max-concurrency preserve-failures?]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :map-each (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only map-each nodes can have map-each configuration"}

      (not (contains? blackboard source-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Source key '" source-key "' not declared in blackboard")}

      (not (contains? blackboard item-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Item key '" item-key "' not declared in blackboard")}

      (not (contains? blackboard output-key))
      {::anom/category ::anom/not-found
       ::anom/message (str "Output key '" output-key "' not declared in blackboard")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/map-each-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :source-key source-key
                         :item-key item-key
                         :output-key output-key}
                  max-concurrency (assoc :max-concurrency max-concurrency)
                  (some? preserve-failures?) (assoc :preserve-failures? preserve-failures?)
                  (:source-key node) (assoc :previous-source-key (:source-key node))
                  (:item-key node) (assoc :previous-item-key (:item-key node))
                  (:output-key node) (assoc :previous-output-key (:output-key node))
                  (:max-concurrency node) (assoc :previous-max-concurrency (:max-concurrency node)))})]})))

(defcommand :sheet set-llm-condition-config
  {:authorized? authenticated?}
  "Set configuration for an llm-condition node."
  [{{:keys [sheet-id node-id instruction reads model]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :llm-condition (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only llm-condition nodes can have llm-condition configuration"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/llm-condition-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction
                         :reads (vec reads)}
                  model (assoc :model model)
                  (:instruction node) (assoc :previous-instruction (:instruction node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (:model node) (assoc :previous-model (:model node)))})]})))

(defcommand :sheet set-repl-researcher-config
  {:authorized? authenticated?}
  "Set configuration for a repl-researcher node."
  [{{:keys [sheet-id node-id instruction reads writes mcp-tools tool-contracts tool-caller-fn browser-tools model max-iterations rlm timeout-ms options]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :repl-researcher (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only repl-researcher nodes can have repl-researcher configuration"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/repl-researcher-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction
                         :reads (vec reads)
                         :writes (vec writes)
                         :mcp-tools (vec (or mcp-tools []))}
                  tool-contracts (assoc :tool-contracts tool-contracts)
                  browser-tools (assoc :browser-tools (vec browser-tools))
                  tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
                  model (assoc :model model)
                  max-iterations (assoc :max-iterations max-iterations)
                  (some? rlm) (assoc :rlm rlm)
                  options (assoc :options options)
                  ;; D-003: optional total budget (Phase 1 + Phase 2) in ms
                  timeout-ms (assoc :timeout-ms timeout-ms)
                  (:instruction node) (assoc :previous-instruction (:instruction node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node))
                  (seq (:mcp-tools node)) (assoc :previous-mcp-tools (:mcp-tools node))
                  (:tool-contracts node) (assoc :previous-tool-contracts (:tool-contracts node))
                  (:tool-caller-fn node) (assoc :previous-tool-caller-fn (:tool-caller-fn node))
                  (seq (:browser-tools node)) (assoc :previous-browser-tools (:browser-tools node))
                  (:model node) (assoc :previous-model (:model node))
                  (:max-iterations node) (assoc :previous-max-iterations (:max-iterations node))
                  (:options node) (assoc :previous-options (:options node))
                  (:timeout-ms node) (assoc :previous-timeout-ms (:timeout-ms node)))})]})))

(defcommand :sheet set-delegate-config
  {:authorized? authenticated?}
  "Set configuration for a delegate node.
   Delegate nodes execute another sheet with isolated blackboard,
   mapping inputs from parent and outputs back to parent."
  [{{:keys [sheet-id node-id target-sheet-id reads writes timeout-ms inherit-ontology?]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        target-sheet (when target-sheet-id
                       (rm/get-sheet ctx target-sheet-id))
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :delegate (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only delegate nodes can have delegate configuration"}

      (and target-sheet-id (not target-sheet))
      {::anom/category ::anom/not-found
       ::anom/message "Target sheet not found"}

      (seq unknown-reads)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {::anom/category ::anom/incorrect
       ::anom/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/delegate-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :target-sheet-id target-sheet-id
                         :reads (vec reads)
                         :writes (vec writes)}
                  timeout-ms (assoc :timeout-ms timeout-ms)
                  (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)
                  (:target-sheet-id node) (assoc :previous-target-sheet-id (:target-sheet-id node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node))
                  (:delegate-timeout-ms node) (assoc :previous-timeout-ms (:delegate-timeout-ms node))
                  (some? (:inherit-ontology? node)) (assoc :previous-inherit-ontology? (:inherit-ontology? node)))})]})))

;; =============================================================================
;; Blackboard Commands
;; =============================================================================

(defn- valid-malli-schema?
  "Check if a value is a valid Malli schema."
  [schema]
  (try
    (m/schema schema)
    true
    (catch Exception _
      false)))

(defn- schema-specificity-feedback
  [key schema]
  (when-let [violations (seq (map #(assoc % :key key)
                                  (blackboard-schema/violations schema)))]
    (blackboard-schema/feedback violations)))

(defn- validate-against-schema
  "Validate a value against a Malli schema.
   Returns nil if valid, or an error map if invalid."
  [schema value]
  (try
    (let [malli-schema (m/schema schema)]
      (if (m/validate malli-schema value)
        nil
        {:error :validation-failed
         :message (me/humanize (m/explain malli-schema value))}))
    (catch Exception e
      {:error :invalid-schema
       :message (.getMessage e)})))

(defcommand :sheet declare-key
  {:authorized? authenticated?}
  "Declare a new key in the blackboard with a Malli schema."
  [{{:keys [sheet-id key schema]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        specificity-feedback (schema-specificity-feedback key schema)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (contains? blackboard key)
      {::anom/category ::anom/conflict
       ::anom/message (str "Key '" key "' already declared")}

      specificity-feedback
      {::anom/category ::anom/incorrect
       ::anom/message (str "Blackboard key " (pr-str key) " is invalid: "
                           specificity-feedback)}

      (not (valid-malli-schema? schema))
      {::anom/category ::anom/incorrect
       ::anom/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-declared
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema}})]})))

(defcommand :sheet update-key-schema
  {:authorized? authenticated?}
  "Update the schema for an existing blackboard key."
  [{{:keys [sheet-id key schema]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        specificity-feedback (schema-specificity-feedback key schema)]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      specificity-feedback
      {::anom/category ::anom/incorrect
       ::anom/message (str "Blackboard key " (pr-str key) " is invalid: "
                           specificity-feedback)}

      (not (valid-malli-schema? schema))
      {::anom/category ::anom/incorrect
       ::anom/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-schema-updated
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema
                 :previous-schema (:schema entry)}})]})))

(defcommand :sheet set-key-value
  {:authorized? authenticated?}
  "Set a value for a blackboard key. Value is validated against the key's Malli schema."
  [{{:keys [sheet-id key value]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        schema (:schema entry)
        validation-error (when schema (validate-against-schema schema value))]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      validation-error
      {::anom/category ::anom/incorrect
       ::anom/message (str "Value doesn't match schema: " (:message validation-error))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-value-set
          :tags #{[:sheet sheet-id]}
          :body (cond-> {:sheet-id sheet-id
                         :key key
                         :value value
                         :version (inc (:version entry))}
                  (some? (:value entry)) (assoc :previous-value (:value entry)))})]})))

(defcommand :sheet delete-key
  {:authorized? authenticated?}
  "Delete a key from the blackboard."
  [{{:keys [sheet-id key]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        ;; Check if any nodes reference this key
        nodes (rm/get-nodes-for-sheet ctx sheet-id)
        nodes-using-key (filter (fn [node]
                                  (or (some #{key} (:reads node))
                                      (some #{key} (:writes node))))
                                nodes)]
    (cond
      (not entry)
      {::anom/category ::anom/not-found
       ::anom/message (str "Key '" key "' not declared")}

      (seq nodes-using-key)
      {::anom/category ::anom/conflict
       ::anom/message (str "Cannot delete key '" key "': still in use by " (count nodes-using-key) " node(s)")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key}})]})))

;; =============================================================================
;; Judge Commands
;; =============================================================================

(defcommand :sheet declare-judge
  {:authorized? authenticated?}
  "Declare an evaluation judge for a sheet.
   Judge config specifies the judge type and custom criteria:
   {:type :completeness  ;; :grounding, :completeness, :instruction-following, :reasoning, :custom
    :criteria \"Must include X, Y, Z\"
    :weight 0.35
    :sheet-id UUID}  ;; For :custom type only"
  [{{:keys [sheet-id judge-name judge-config]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        judges (rm/get-judges ctx sheet-id)
        existing-judge (get judges judge-name)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      existing-judge
      {::anom/category ::anom/conflict
       ::anom/message (str "Judge '" judge-name "' already declared")}

      (and (= :custom (:type judge-config))
           (not (:sheet-id judge-config)))
      {::anom/category ::anom/incorrect
       ::anom/message "Custom judge type requires :sheet-id"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/judge-declared
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :judge-name judge-name
                 :judge-config judge-config
                 :criteria-version 1}})]})))

(defcommand :sheet set-node-judges
  {:authorized? authenticated?}
  "Set which evaluation judges apply to a node.

   Gap-5: accepts both `:leaf` and `:repl-researcher` node types. The
   judge runtime fires on `:sheet/node-execution-completed` events
   regardless of executor kind; allowing repl-researcher attachment
   lets consumers override the Gap-5 default-attachment behavior on
   any repl-researcher node."
  [{{:keys [sheet-id node-id judges]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        declared-judges (rm/get-judges ctx sheet-id)
        unknown-judges (remove #(contains? declared-judges %) judges)]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not (contains? #{:leaf :repl-researcher} (:type node)))
      {::anom/category ::anom/incorrect
       ::anom/message "Only :leaf and :repl-researcher nodes can have evaluation judges"}

      (seq unknown-judges)
      {::anom/category ::anom/not-found
       ::anom/message (str "Unknown judges: " (vec unknown-judges)
                          ". Declare them first using sheet/judges.")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-judges-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :judges (vec judges)}
                  (:judges node) (assoc :previous-judges (:judges node)))})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet tick-tree
  {:authorized? authenticated?}
  "Start a tree tick (execute from root).
   When inputs are provided, stores a structure-only execution snapshot and
   canonical seed writes/references for isolated async execution."
  [{{:keys [sheet-id tick-id parent-tick-id correlation-id inputs input-sources use-version force-draft options
            tool-context]} :command
    :as context}]
  (let [new-tick-id (or tick-id (random-uuid))
        ;; Blackboard keys are simple keywords (see declare-key), but callers
        ;; may pass string-keyed inputs — runtime/execute documents that form,
        ;; and execute-delegate-node builds them that way. The tick-execution
        ;; -context read model already keywordizes on projection; normalizing
        ;; HERE means the stored event agrees with the invariant too, so a
        ;; consumer reading the event directly resolves a seeded key by the
        ;; same keyword the node declares in its :reads.
        inputs (when inputs
                 (reduce-kv (fn [acc k v]
                              (assoc acc (if (string? k) (keyword k) k) v))
                            {} inputs))
        input-sources (reduce-kv (fn [acc k source]
                                   (assoc acc (if (string? k) (keyword k) k) source))
                                 {} (or input-sources {}))]
    (if inputs
      ;; Snapshot-based execution: build full snapshot for isolation
      (let [instruction-overrides (:gepa/patched-instructions context)
            sheet-tenant-id (when (not= (:system-tenant-id context) (:tenant-id context))
                              (:system-tenant-id context))
            snapshot (runtime/build-execution-snapshot
                       context sheet-id
                       :use-version use-version
                       :force-draft force-draft
                       :instruction-overrides instruction-overrides
                       :sheet-tenant-id sheet-tenant-id)]
        (if (::anom/category snapshot)
          snapshot ;; Return anomaly if snapshot building failed
          (if-let [validation-error (invalid-seed-input snapshot inputs)]
            {::anom/category ::anom/incorrect
             ::anom/message validation-error}
            (let [snapshot-values (reduce-kv
                                  (fn [acc k entry]
                                    (if (some? (:value entry))
                                      (assoc acc k (:value entry))
                                      acc))
                                  {}
                                  (:blackboard-entries snapshot))
                seed-values (merge snapshot-values inputs)
                new-seed-events (into {}
                                      (for [[k v] seed-values
                                            :when (not (contains? input-sources k))]
                                        (let [value-id (random-uuid)]
                                          [k (->event
                                             {:type :sheet/execution-value-written
                                              :tags #{[:sheet sheet-id] [:tick new-tick-id]}
                                              :body (value-storage/prepare-write
                                                     context
                                                     {:tick-id new-tick-id
                                                      :sheet-id sheet-id
                                                      :key k
                                                      :value v
                                                      :value-id value-id
                                                      :tick-seed? true})})])))
                seed-sources (merge input-sources
                                    (into {} (map (fn [[k event]]
                                                   [k {:tick-id new-tick-id
                                                       :event-id (:value-id event)}])
                                                 new-seed-events)))
                structural-snapshot (update snapshot :blackboard-entries
                                            (fn [entries]
                                              (reduce-kv (fn [acc k entry]
                                                           (assoc acc k (dissoc entry :value)))
                                                         {} (or entries {}))))
                started-event (->event
             {:type :sheet/tree-tick-started
              :tags (cond-> #{[:sheet sheet-id]
                              [:tick new-tick-id]}
                      ;; Durable lineage index. Result delivery uses this to
                      ;; collect only this execution's descendant ticks rather
                      ;; than scanning every completion in the tenant.
                      parent-tick-id (conj [:parent-tick parent-tick-id])
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:sheet-id sheet-id
                             :tick-id new-tick-id
                             :seed-sources seed-sources
                             :execution-snapshot structural-snapshot}
                      parent-tick-id (assoc :parent-tick-id parent-tick-id)
                      correlation-id (assoc :correlation-id correlation-id)
                      (:version-number snapshot) (assoc :version-number (:version-number snapshot))
                      options (assoc :options options)
                      ;; G1 (ADR 0018): carry the opaque :tool-context across the
                      ;; async boundary so the tick-execution-context read model
                      ;; can surface it back at the Phase-2 leaf.
                      tool-context (assoc :tool-context tool-context))})]
              {:command-result/events
               (into [started-event] (vals new-seed-events))}))))
      ;; Legacy UI tick: no snapshot, reads live sheet state
      (let [read-ctx (if (not= (:system-tenant-id context) (:tenant-id context))
                       (assoc context :tenant-id (:system-tenant-id context))
                       context)
            sheet (rm/get-sheet read-ctx sheet-id)
            root-node-id (:root-node-id sheet)]
        (cond
          (not sheet)
          {::anom/category ::anom/not-found
           ::anom/message "Sheet not found"}

          (not root-node-id)
          {::anom/category ::anom/incorrect
           ::anom/message "Sheet has no root node"}

          :else
          {:command-result/events
           [(->event
             {:type :sheet/tree-tick-started
              :tags (cond-> #{[:sheet sheet-id]
                              [:tick new-tick-id]}
                      parent-tick-id (conj [:parent-tick parent-tick-id])
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:sheet-id sheet-id
                             :tick-id new-tick-id}
                      parent-tick-id (assoc :parent-tick-id parent-tick-id)
                      correlation-id (assoc :correlation-id correlation-id)
                      ;; G1 (ADR 0018): opaque :tool-context also rides the
                      ;; snapshot-less UI tick path for symmetry.
                      tool-context (assoc :tool-context tool-context))})]})))))
(defcommand :sheet tick-node
  {:authorized? authenticated?}
  "Start a single node tick (for testing or manual execution)."
  [{{:keys [sheet-id node-id tick-id overrides]} :command
    :as ctx}]
  (let [read-ctx (if (not= (:system-tenant-id ctx) (:tenant-id ctx))
                   (assoc ctx :tenant-id (:system-tenant-id ctx))
                   ctx)
        node (rm/get-node read-ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key read-ctx sheet-id)
        ;; Get input values from blackboard (for reads)
        inputs (reduce (fn [acc k]
                         (if-let [entry (get blackboard k)]
                           (assoc acc k (:value entry))
                           acc))
                       {}
                       (:reads node))
        ;; Apply overrides
        inputs-with-overrides (merge inputs (or overrides {}))]
    (cond
      (not node)
      {::anom/category ::anom/not-found
       ::anom/message "Node not found"}

      (not= :leaf (:type node))
      {::anom/category ::anom/incorrect
       ::anom/message "Only leaf nodes can be ticked directly"}

      (not (:instruction node))
      {::anom/category ::anom/incorrect
       ::anom/message "Node has no instruction"}

      :else
      (let [new-tick-id (or tick-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick new-tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id new-tick-id
                   :node-id node-id
                   :inputs inputs-with-overrides}})]}))))

(defcommand :sheet resume-node-execution
  {:authorized? authenticated?}
  "Re-enqueue one abandoned leaf start after a processor restart.

   The original start event id is a durable idempotency key. If that start has
   already completed, or a recovery start already points at it, this command is
   a no-op. This deliberately resumes only a leaf frontier; composite parents
   remain in progress and consume the recovered leaf's normal completion."
  [{{:keys [sheet-id tick-id node-id original-start-event-id inputs]} :command
    :as ctx}]
  (let [tick-events (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :tags #{[:tick tick-id]}}))
        original-index (first (keep-indexed
                               (fn [index event]
                                 (when (= original-start-event-id (:event/id event))
                                   index))
                               tick-events))
        events-after-original (if (some? original-index)
                                (subvec tick-events (inc original-index))
                                [])
        completed? (some #(and (= :sheet/node-execution-completed (:event/type %))
                               (= node-id (:node-id %)))
                         events-after-original)
        already-resumed? (some #(and (= :sheet/node-execution-started (:event/type %))
                                     (= original-start-event-id
                                        (:resumed-from-event-id %)))
                               tick-events)]
    (cond
      (nil? original-index)
      {::anom/category ::anom/not-found
       ::anom/message "Original node execution start not found"}

      (or completed? already-resumed?)
      {:command-result/events []}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-execution-started
          :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
          :body {:sheet-id sheet-id
                 :tick-id tick-id
                 :node-id node-id
                 :inputs inputs
                 :resumed-from-event-id original-start-event-id}})]})))

(defcommand :sheet complete-node-execution
  {:authorized? authenticated?}
  "Complete a node execution (internal command from todo processor).
   For tick-scoped executions, also emits execution-value-written events
   atomically with the completion event to avoid race conditions.

   Optional :usage carries per-node token counts from LLM calls."
  [{{:keys [sheet-id tick-id node-id status writes rejected-writes write-sources write-references? duration-ms error inputs usage model
            node-type completion-kind raw-response failure-kind provider-evidence
            block-payload read-sources]} :command
    :as ctx}]
  (if (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
    {:command-result/events []}
    (let [;; Gap-7: when the dispatch site didn't explicitly set
        ;; :completion-kind but the node is a recursive repl-researcher,
        ;; derive the kind from :status. :tree-generated marks an
        ;; intermediate Phase 1 emit-tree iteration; :success/:failure
        ;; marks the terminal (final!) call.
        derived-kind (when (and (nil? completion-kind)
                                (= :repl-researcher node-type))
                       (cond
                         (= :tree-generated status) :tree-iteration
                         (contains? #{:success :failure :timeout} status) :terminal
                         :else nil))
        effective-completion-kind (or completion-kind derived-kind)
        ;; Split :inputs into execution context vs read values.
        ;;
        ;; Blackboard keys are always SIMPLE keywords (see declare-key); the
        ;; engine's map-each correlation keys are namespaced. So the namespace
        ;; is the discriminator: namespaced => correlation metadata that must
        ;; travel on the event; simple => a blackboard read value that is
        ;; already durable elsewhere and is reduced to shape here.
        exec-context (into {} (filter (fn [[k _]] (and (keyword? k) (namespace k))) inputs))
        read-values (into {} (filter (fn [[k _]] (and (keyword? k) (nil? (namespace k)))) inputs))
        ;; Whether this completion's writes get their own
        ;; :sheet/execution-value-written events. Only tick-scoped
        ;; executions have a blackboard to write into; a snapshot-less UI
        ;; (snapshot-less) tick has none.
        ;;
        ;; This gate decides whether :writes may be reduced to shape on the
        ;; completion event. When the write events are NOT emitted, the
        ;; completion event is the only record the values have, so dropping
        ;; them here would lose them outright.
        ;;
        ;; CC-21b (O7): `:blocked` belongs in this set. Durability is the whole
        ;; of the condition, and `tick-scoped?` is what supplies it — a blocked
        ;; node's writes are as durable in the write log as a successful one's.
        ;; WS-2a added `:blocked`; this gate never learned about it, and the
        ;; cost was measured: 57 of 178 `:repl-researcher` observations are
        ;; `:blocked`, and their inlined `:writes` were 1,331,342 B — 90.8% of
        ;; everything left after the rest of the storage-amplification fix.
        tick-scoped? (some? (rm/get-tick-execution-context ctx tick-id))
        externalize-writes? (and tick-scoped?
                                 (#{:success :tree-generated :blocked} status)
                                 (seq writes))
        forwarded-sources (into {}
                                (for [[k source] (or write-sources {})]
                                  [k source]))
        cross-tick-sources (if write-references?
                             (into {}
                                   (filter (fn [[_ source]]
                                             (not= tick-id (:tick-id source))))
                                   forwarded-sources)
                             {})
        reference-events (mapv (fn [[k source]]
                                 (->event {:type :sheet/execution-value-referenced
                                           :tags #{[:sheet sheet-id] [:tick tick-id]}
                                           :body (cond-> {:tick-id tick-id
                                                          :sheet-id sheet-id
                                                          :key k
                                                          :source source
                                                          :node-id node-id}
                                                   (seq exec-context)
                                                   (assoc :exec-context exec-context))}))
                               cross-tick-sources)
        completion-event (->event
                           {:type :sheet/node-execution-completed
                            :tags #{[:sheet sheet-id]
                                    [:node node-id]
                                    [:tick tick-id]}
                            :body (cond-> {:sheet-id sheet-id
                                           :tick-id tick-id
                                           :node-id node-id
                                           :status status}
                                    ;; Shape, not values — but only when the
                                    ;; values are durable elsewhere. The write
                                    ;; events carry :node-id and :exec-context
                                    ;; so consumers can attribute them to this
                                    ;; exact node execution.
                                    (or (seq writes) (seq forwarded-sources))
                                    (assoc :write-keys (vec (distinct (concat (keys writes)
                                                                             (keys forwarded-sources))))
                                           :write-profile (profile/profile-values writes))
                                    (seq rejected-writes)
                                    (assoc :rejected-write-keys (vec (keys rejected-writes))
                                           :rejected-write-profile (profile/profile-values rejected-writes))
                                    (seq forwarded-sources)
                                    (assoc :write-sources forwarded-sources)
                                    ;; No write events for this completion =>
                                    ;; this event is the values' only home.
                                    (and (seq writes) (not externalize-writes?))
                                    (assoc :writes writes)
                                    duration-ms (assoc :duration-ms duration-ms)
                                    error (assoc :error error)
                                    ;; Verbatim raw LLM response for parse
                                    ;; failures — retrievable post-hoc via the
                                    ;; (node-output <node-id>) drill-down.
                                    raw-response (assoc :raw-response raw-response)
                                    failure-kind (assoc :failure-kind failure-kind)
                                    provider-evidence (assoc :provider-evidence provider-evidence)
                                    ;; :inputs keeps ONLY the namespaced
                                    ;; execution-context keys. Those are not
                                    ;; observability — trace-execution-key and
                                    ;; matches-execution-context? correlate on
                                    ;; them, and map-each correctness depends on
                                    ;; it. The read VALUES (simple keywords, the
                                    ;; blackboard keys) become shape only.
                                    (seq exec-context) (assoc :inputs exec-context)
                                    (seq read-values)
                                    (assoc :read-keys (vec (keys read-values))
                                           :input-profile (profile/profile-values read-values))
                                    ;; Which write event each read resolved to.
                                    ;; Lets a consumer rehydrate this node's
                                    ;; inputs exactly, rather than resolving the
                                    ;; key name to whichever write landed last.
                                    (seq read-sources)
                                    (assoc :read-sources read-sources)
                                    (seq usage) (assoc :usage usage)
                                    model (assoc :model model)
                                    ;; C-2a-2: propagate :node-type so the
                                    ;; per-node-type aggregator can partition
                                    ;; without looking up via the sheets RM.
                                    (some? node-type) (assoc :node-type node-type)
                                    ;; Gap-7: distinguish intermediate vs
                                    ;; terminal repl-researcher completions so
                                    ;; judge routing can pick the right grader
                                    ;; per kind.
                                    (some? effective-completion-kind)
                                    (assoc :completion-kind effective-completion-kind)
                                    ;; WS-2a: carry the OPAQUE block payload onto
                                    ;; the completion event when the node blocked.
                                    (= :blocked status)
                                    (assoc :block-payload block-payload))})
        ;; For tick-scoped executions with successful writes, emit bb writes
        ;; atomically. Also handles :tree-generated (RLM two-phase execution).
        bb-write-events (when externalize-writes?
                          (mapv (fn [[k v]]
                                  (->event
                                    {:type :sheet/execution-value-written
                                     :tags #{[:sheet sheet-id]
                                             [:tick tick-id]}
                                     :body (value-storage/prepare-write
                                            ctx
                                            (cond-> {:tick-id tick-id
                                                     :sheet-id sheet-id
                                                     :key k
                                                     :value v
                                                     :node-id node-id}
                                              ;; Identifies the ITERATION, not
                                              ;; just the node — see the schema.
                                              (seq exec-context)
                                              (assoc :exec-context exec-context)))}))
                                writes))
        ;; Rejected values are durable trace evidence, not blackboard writes.
        ;; They use the same configured storage policy but have no read-model
        ;; reducer, so they can never become canonical execution state.
        rejected-write-events
        (mapv (fn [[k v]]
                (->event
                 {:type :sheet/execution-value-rejected
                  :tags #{[:sheet sheet-id] [:tick tick-id]}
                  :body (value-storage/prepare-write
                         ctx
                         (cond-> {:tick-id tick-id
                                  :sheet-id sheet-id
                                  :key k
                                  :value v
                                  :node-id node-id}
                           (seq exec-context)
                           (assoc :exec-context exec-context)))}))
              rejected-writes)]
      {:command-result/events
       (into [] (concat bb-write-events rejected-write-events reference-events [completion-event]))})))

(defcommand :sheet record-rlm-tree-node-completion
  {:authorized? authenticated?}
  "Emit a :sheet/rlm-tree-node-completed event with the precomputed
   structured node-path, the node's :usage, and an optional :input-profile
   capturing input characteristics for each :reads key. Fires alongside
   the generic complete-node-execution event for LLM leaf calls.
   Reserved for the RLM learning loop — future fields (scores, feedback)
   will be added by downstream judges."
  [{{:keys [sheet-id tick-id node-id node-path usage input-profile]} :command
    :as _ctx}]
  {:command-result/events
   [(->event
      {:type :sheet/rlm-tree-node-completed
       :tags #{[:sheet sheet-id]
               [:node node-id]
               [:tick tick-id]}
       :body (cond-> {:sheet-id sheet-id
                      :tick-id tick-id
                      :node-id node-id
                      :node-path node-path
                      :usage usage}
               (seq input-profile) (assoc :input-profile input-profile))})]})

(defcommand :sheet record-rlm-tree-execution-completion
  {:authorized? authenticated?}
  "Emit a :sheet/rlm-tree-execution-completed bookend event when a Phase 2
   RLM tree-execution finishes. Carries the full trajectory of events for
   the tick, total usage, task-fingerprint placeholder (issue 012), and
   the C-2a-2 fields :tree-fingerprint + :status + :duration-ms that drive
   the per-tree-fingerprint rolling-metrics aggregator. The new fields are
   carried in the event body — the partition-by-fingerprint read-model
   reads :tree-fingerprint from the event body directly (tag values must
   be UUIDs in event-store-v3, so we don't tag with the string fingerprint)."
  [{{:keys [sheet-id tick-id trajectory total-usage task-fingerprint
            tree-fingerprint status duration-ms generated-tree source-sheet-id
            source-tick-id]} :command
    :as _ctx}]
  {:command-result/events
   [(->event
      (cond-> {:type :sheet/rlm-tree-execution-completed
               :tags #{[:sheet sheet-id]
                       [:tick tick-id]}
               :body {:sheet-id sheet-id
                      :tick-id tick-id
                      :trajectory trajectory
                      :total-usage total-usage
                      :timestamp (java.time.Instant/now)
                      :task-fingerprint task-fingerprint}}
        (some? tree-fingerprint) (assoc-in [:body :tree-fingerprint] tree-fingerprint)
        (some? status)           (assoc-in [:body :status] status)
        (some? duration-ms)      (assoc-in [:body :duration-ms] duration-ms)
        ;; CV-2 (ADR 0017 decision 3): carry the emitted worked-DSL + the
        ;; SOURCE (host/classified) sheet-id so the post-emit enrichment
        ;; processor can resolve the tree-class (sheet->class join) and
        ;; record the DSL as a :strengths[].:recommended-pattern. Both
        ;; optional/backward-compatible — a turn that times out before emit
        ;; carries neither, so no enrichment fires (CV-1 floor still stands).
        (some? generated-tree)   (assoc-in [:body :generated-tree] generated-tree)
        (some? source-sheet-id)  (assoc-in [:body :source-sheet-id] source-sheet-id)
        ;; HP-2: the hosting TURN's tick — pairs with :source-sheet-id as the
        ;; per-occurrence execution<->classification linkage (the shared/static
        ;; host sheet alone cannot attribute an execution to an occurrence).
        (some? source-tick-id)   (assoc-in [:body :source-tick-id] source-tick-id)))]})

(defcommand :sheet fail-node-execution
  {:authorized? authenticated?}
  "Mark a node execution as failed (internal command from todo processor)."
  [{{:keys [sheet-id tick-id node-id error duration-ms]} :command
    :as ctx}]
  (if (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
    {:command-result/events []}
    {:command-result/events
     [(->event
       {:type :sheet/node-execution-completed
        :tags #{[:sheet sheet-id]
                [:node node-id]
                [:tick tick-id]}
        :body (cond-> {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :status :failure}
                error (assoc :error error)
                duration-ms (assoc :duration-ms duration-ms))})]}))

(defcommand :sheet cancel-tick
  {:authorized? authenticated?}
  "Cancel a running tick. Prevents further re-ticks."
  [{{:keys [sheet-id tick-id reason]} :command
    :as ctx}]
  (if (contains? #{:completed :cancelled} (:status (rm/get-tick ctx tick-id)))
    {:command-result/events []}
    {:command-result/events
     [(->event
       {:type :sheet/tick-cancelled
        :tags #{[:sheet sheet-id]
                [:tick tick-id]}
        :body {:sheet-id sheet-id
               :tick-id tick-id
               :reason reason}})]}))

;; =============================================================================
;; System Commands (called internally via cp/process-command, not via HTTP)
;; =============================================================================

(defcommand :sheet emit-tick-started
  {:authorized? authenticated?}
  "System command: record that a tree tick execution has started (for in-progress tracking)."
  [{{:keys [sheet-id tick-id]} :command}]
  {:command-result/events
   [(->event {:type :sheet/tree-tick-started
              :tags #{[:sheet sheet-id] [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id}})]})

(defcommand :sheet emit-tick-completed
  {:authorized? authenticated?}
  "System command: record that a tree tick execution has completed."
  [{{:keys [sheet-id tick-id correlation-id root-status]} :command
    :as ctx}]
  (if (contains? #{:completed :cancelled} (:status (rm/get-tick ctx tick-id)))
    {:command-result/events []}
    {:command-result/events
     [(->event {:type :sheet/tree-tick-completed
                :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                        correlation-id (conj [:correlation correlation-id]))
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :root-status root-status}
                        correlation-id (assoc :correlation-id correlation-id))})]}))

(defcommand :sheet store-execution-trace
  {:authorized? authenticated?}
  "System command: store a full execution trace for analytics and debugging."
  [{{:keys [trace-id sheet-id parent-trace-id correlation-id root-trace-id child-trace-ids
            version-number started-at completed-at
            duration-ms status input-snapshot output-snapshot node-traces error]} :command}]
  {:command-result/events
   [(->event {:type :sheet/execution-traced
              :tags (cond-> #{[:sheet sheet-id] [:trace trace-id] [:tick trace-id]}
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:trace-id trace-id
                             :sheet-id sheet-id
                             :root-trace-id root-trace-id
                             :child-trace-ids child-trace-ids
                             :started-at started-at
                             :completed-at completed-at
                             :duration-ms duration-ms
                             :status status
                             :input-snapshot input-snapshot
                             :output-snapshot output-snapshot
                             :node-traces node-traces}
                      parent-trace-id (assoc :parent-trace-id parent-trace-id)
                      correlation-id (assoc :correlation-id correlation-id)
                      version-number (assoc :version-number version-number)
                      error (assoc :error error))})]})

;; =============================================================================
;; Versioning Commands
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for snapshots.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          ;; Leaf-specific fields
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:tools node) (assoc :tools (:tools node))
                   (:retry node) (assoc :retry (:retry node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))))
          ;; LLM-Condition-specific
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          ;; Parallel-specific
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          ;; Map-each-specific
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          ;; Children for composite nodes
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defn- create-snapshot
  "Create a snapshot of the current sheet state."
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes-by-id (rm/get-nodes-by-id ctx sheet-id)
        blackboard (rm/get-blackboard-for-sheet ctx sheet-id)]
    {:sheet {:name (:name sheet) :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(:key bb) (:schema bb)])
                                   blackboard))
     :nodes (build-node-tree nodes-by-id (:root-node-id sheet))}))

(defcommand :sheet publish-version
  {:authorized? authenticated?}
  "Create a new published version from the current draft state.
   Captures all nodes and blackboard schema as an immutable snapshot."
  [{{:keys [sheet-id description]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not (:root-node-id sheet))
      {::anom/category ::anom/incorrect
       ::anom/message "Cannot publish sheet without root node"}

      :else
      (let [snapshot (create-snapshot ctx sheet-id)
            current-version (or (:published-version sheet) 0)
            new-version (inc current-version)
            snapshot-id (random-uuid)]
        {:command-result/events
         [(->event
           {:type :sheet/version-published
            :tags #{[:sheet sheet-id]
                    [:version snapshot-id]}
            :body (cond-> {:sheet-id sheet-id
                           :snapshot-id snapshot-id
                           :version-number new-version
                           :snapshot snapshot}
                    description (assoc :description description))})]}))))

(defcommand :sheet revert-to-version
  {:authorized? authenticated?}
  "Discard current draft and restore state from a published version.
   If the draft has changes, it will be stashed first for recovery."
  [{{:keys [sheet-id version-number]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (rm/get-version ctx sheet-id version-number)
        specificity-feedback (some-> version :snapshot snapshot-schema-feedback)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not version)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      specificity-feedback
      {::anom/category ::anom/incorrect
       ::anom/message (str "Cannot restore version: " specificity-feedback)}

      :else
      (let [;; If draft is dirty, stash it first
            draft-dirty? (:draft-dirty? sheet)
            stash-events (when draft-dirty?
                           (let [snapshot (create-snapshot ctx sheet-id)
                                 stash-id (random-uuid)]
                             [(->event
                               {:type :sheet/draft-stashed
                                :tags #{[:sheet sheet-id]}
                                :body {:sheet-id sheet-id
                                       :stash-id stash-id
                                       :snapshot snapshot}})]))
            revert-event (->event
                          {:type :sheet/draft-reverted
                           :tags #{[:sheet sheet-id]}
                           :body {:sheet-id sheet-id
                                  :target-version version-number
                                  :snapshot-id (:snapshot-id version)
                                  :snapshot (:snapshot version)}})]
        {:command-result/events
         (if stash-events
           (conj (vec stash-events) revert-event)
           [revert-event])}))))

(defcommand :sheet restore-stash
  {:authorized? authenticated?}
  "Restore the stashed draft. The stash is consumed after restoration."
  [{{:keys [sheet-id]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        stash (rm/get-stash ctx sheet-id)
        specificity-feedback (some-> stash :snapshot snapshot-schema-feedback)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not stash)
      {::anom/category ::anom/not-found
       ::anom/message "No stash found"}

      specificity-feedback
      {::anom/category ::anom/incorrect
       ::anom/message (str "Cannot restore stash: " specificity-feedback)}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/stash-restored
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :stash-id (:stash-id stash)
                 :snapshot (:snapshot stash)}})]})))

(defcommand :sheet set-execution-mode
  {:authorized? authenticated?}
  "Toggle between executing draft or published version."
  [{{:keys [sheet-id mode]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (and (= mode :published) (nil? (:published-version sheet)))
      {::anom/category ::anom/incorrect
       ::anom/message "No published version exists. Publish first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/execution-mode-set
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :mode mode
                 :previous-mode (or (:execution-mode sheet) :draft)}})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet execute-version
  {:authorized? authenticated?}
  "Execute a specific published version of a sheet.

   This command executes a sheet workflow using a specific version snapshot,
   not the current draft state. Useful for comparing behavior across versions.

   Traces are always stored and the trace-id is returned in the result."
  [{{:keys [sheet-id version-number inputs]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (when sheet (rm/get-version ctx sheet-id version-number))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (not version)
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      :else
      (let [result (runtime/execute ctx sheet-id (or inputs {})
                                    :use-version version-number)]
        {:command-result/events
         [(->event {:type :sheet/version-executed
                    :tags #{[:sheet sheet-id]}
                    :body {:sheet-id sheet-id
                           :version-number version-number
                           :trace-id (:trace-id result)
                           :status (:status result)
                           :duration-ms (:duration-ms result)}})]
         :command-result/data result}))))

(defcommand :sheet batch-execute
  {:authorized? authenticated?}
  "Execute a sheet with multiple input sets in parallel.

   This command runs the same sheet multiple times with different inputs,
   executing them concurrently for efficiency. Each execution stores its own trace.

   Returns results for all executions along with total wall-clock time."
  [{{:keys [sheet-id version-number inputs]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (when (and sheet version-number)
                  (rm/get-version ctx sheet-id version-number))]
    (cond
      (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (and version-number (not version))
      {::anom/category ::anom/not-found
       ::anom/message (str "Version " version-number " not found")}

      (empty? inputs)
      {::anom/category ::anom/incorrect
       ::anom/message "inputs cannot be empty"}

      :else
      (let [start-time (System/currentTimeMillis)
            ;; Execute all inputs in parallel using futures
            futures (mapv (fn [input-set]
                            (future
                              (try
                                (runtime/execute ctx sheet-id (or input-set {})
                                                 :use-version version-number)
                                (catch Exception e
                                  {:status :failure
                                   :error (.getMessage e)}))))
                          inputs)
            ;; Collect all results (blocks until all complete)
            results (mapv deref futures)
            end-time (System/currentTimeMillis)
            successful-count (count (filter #(= :success (:status %)) results))
            failed-count (count (filter #(not= :success (:status %)) results))]
        {:command-result/events
         [(->event {:type :sheet/batch-executed
                    :tags #{[:sheet sheet-id]}
                    :body (cond-> {:sheet-id sheet-id
                                   :total-executions (count results)
                                   :successful-count successful-count
                                   :failed-count failed-count
                                   :duration-ms (- end-time start-time)}
                             version-number (assoc :version-number version-number))})]
         :command-result/data
         {:results results
          :total-executions (count results)
          :successful-count successful-count
          :failed-count failed-count
          :duration-ms (- end-time start-time)}}))))

;; =============================================================================
;; Tree Metadata Commands
;; =============================================================================

(defcommand :sheet extract-tree-metadata
  {:authorized? authenticated?}
  "Extract and store metadata for a sheet.

   Analyzes the tree structure to identify node types, capabilities,
   and patterns. Optionally accepts user-provided problem types and
   description.

   Args:
   - sheet-id: Sheet to extract metadata from
   - problem-types: Optional manual problem type URIs (e.g., [\"problem:Classification\"])
   - description: Optional manual description

   Returns the extracted metadata and emits :sheet/tree-metadata-extracted event."
  [{{:keys [sheet-id problem-types description]} :command
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if (not sheet)
      {::anom/category ::anom/not-found
       ::anom/message "Sheet not found"}

      (let [;; Extract metadata from tree structure
            extracted (metadata/extract-tree-metadata event-store sheet-id)

            ;; Get blackboard and nodes for problem type inference
            nodes (rm/get-nodes-for-sheet ctx sheet-id)
            blackboard (rm/get-blackboard-for-sheet ctx sheet-id)

            ;; Infer problem types if not provided
            inferred-problems (when (empty? problem-types)
                                (metadata/infer-problem-types extracted nodes blackboard))

            ;; Merge user overrides
            final-metadata (-> extracted
                               (assoc :problem-types (or problem-types inferred-problems []))
                               (cond-> description (assoc :description description)))]

        {:command-result/data final-metadata
         :command-result/events
         [(->event
            {:type :sheet/tree-metadata-extracted
             :tags #{[:sheet sheet-id]}
             :body {:sheet-id sheet-id
                    :metadata final-metadata}})]}))))

;; =============================================================================
;; CC-13 — Injection Record
;; =============================================================================

(defcommand :sheet record-injection
  {:authorized? authenticated?}
  "Record ONE R-Inject render occurrence as an `:intervention/injection-recorded`
   event — what corpus content was put in front of the model on this turn.

   Dispatched in-process by the render (todo-processors
   apply-r05-classifier-context), the same way store-execution-trace is
   dispatched from trace assembly.

   Tagged [:sheet] [:tick] [:node] so the row is reachable by the same
   occurrence pair the judge score events carry."
  [{{:keys [sheet-id tick-id node-id recorded-at] :as command} :command}]
  {:command-result/events
   [(->event
      {:type :intervention/injection-recorded
       :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
       :body (assoc (dissoc command :command/name :command/id :command/timestamp)
                    :recorded-at (or recorded-at (str (java.time.Instant/now))))})]})
