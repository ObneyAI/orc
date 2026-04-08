(ns ai.obney.orc.orc-service.core.read-models
  "Behavior Tree Sheet read models - projections built from events.

   This namespace provides:
   - Event type sets for querying
   - Multimethod projections for sheets, nodes, blackboard, and ticks
   - defreadmodel registrations for L1/L2 caching
   - Helper functions for common queries (via rmp/project)"
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def sheet-core-events
  "Core events that directly affect sheet read model"
  #{:sheet/sheet-created
    :sheet/sheet-renamed
    :sheet/sheet-deleted
    :sheet/node-created    ;; first root node sets root-node-id
    :sheet/node-deleted    ;; deleting root clears root-node-id
    ;; Versioning events
    :sheet/version-published
    :sheet/draft-stashed
    :sheet/draft-reverted
    :sheet/stash-restored
    :sheet/execution-mode-set
    :sheet/content-hash-set})

(def node-events
  "Events that affect node read model"
  #{:sheet/node-created
    :sheet/node-moved
    :sheet/node-reordered
    :sheet/node-deleted
    :sheet/node-name-set
    :sheet/node-instruction-set
    :sheet/node-context-set
    :sheet/node-io-set
    :sheet/node-decorators-set
    :sheet/node-check-set
    :sheet/node-executor-set
    :sheet/node-retry-set
    :sheet/node-judges-set
    :sheet/parallel-config-set
    :sheet/map-each-config-set
    :sheet/llm-condition-config-set
    :sheet/repl-researcher-config-set
    :sheet/delegate-config-set
    :sheet/node-execution-started
    :sheet/node-execution-completed})

(def blackboard-events
  "Events that affect blackboard read model"
  #{:sheet/key-declared
    :sheet/key-schema-updated
    :sheet/key-value-set
    :sheet/key-deleted})

(def judge-events
  "Events that affect judge read model"
  #{:sheet/judge-declared})

(def tick-events
  "Events that affect tick/execution read model"
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled})

(def version-events
  "Events that affect version/stash read model"
  #{:sheet/version-published
    :sheet/draft-stashed
    :sheet/draft-reverted
    :sheet/stash-restored
    :sheet/execution-mode-set
    :sheet/content-hash-set})

(def draft-dirty-events
  "Events that mark draft as dirty (differs from published)"
  #{:sheet/node-created
    :sheet/node-moved
    :sheet/node-reordered
    :sheet/node-deleted
    :sheet/node-name-set
    :sheet/node-instruction-set
    :sheet/node-context-set
    :sheet/node-io-set
    :sheet/node-decorators-set
    :sheet/node-check-set
    :sheet/node-executor-set
    :sheet/node-retry-set
    :sheet/node-judges-set
    :sheet/parallel-config-set
    :sheet/map-each-config-set
    :sheet/llm-condition-config-set
    :sheet/repl-researcher-config-set
    :sheet/delegate-config-set
    :sheet/key-declared
    :sheet/key-schema-updated
    :sheet/key-deleted
    :sheet/judge-declared})

(def sheet-events
  "Events that affect sheet read model (includes draft-dirty tracking and versioning)"
  (clojure.set/union sheet-core-events draft-dirty-events version-events))

(def all-sheet-events
  "All events for a complete sheet view"
  (clojure.set/union sheet-events node-events blackboard-events tick-events version-events))

;; =============================================================================
;; Sheets Projection
;; =============================================================================

(defmulti sheets*
  "Apply event to sheets read model"
  (fn [_state event] (:event/type event)))

(defmethod sheets* :sheet/sheet-created
  [state event]
  (assoc state (:sheet-id event)
         {:id (:sheet-id event)
          :name (:name event)
          :root-node-id nil
          :created-at (str (:event/timestamp event))}))

(defmethod sheets* :sheet/sheet-renamed
  [state event]
  (assoc-in state [(:sheet-id event) :name] (:new-name event)))

(defmethod sheets* :sheet/node-created
  [state event]
  ;; When creating a node with no parent, it becomes the root
  (if (nil? (:parent-id event))
    (assoc-in state [(:sheet-id event) :root-node-id] (:node-id event))
    state))

(defmethod sheets* :sheet/node-deleted
  [state event]
  ;; If deleting the root node, clear root-node-id
  (let [sheet-id (:sheet-id event)
        node-id (:node-id event)
        current-root (get-in state [sheet-id :root-node-id])]
    (if (= current-root node-id)
      (assoc-in state [sheet-id :root-node-id] nil)
      state)))

(defmethod sheets* :sheet/sheet-deleted
  [state event]
  (dissoc state (:sheet-id event)))

;; Versioning methods
(defmethod sheets* :sheet/version-published
  [state event]
  (-> state
      (assoc-in [(:sheet-id event) :published-version] (:version-number event))
      (assoc-in [(:sheet-id event) :draft-dirty?] false)))

(defmethod sheets* :sheet/draft-stashed
  [state event]
  (assoc-in state [(:sheet-id event) :has-stash?] true))

(defmethod sheets* :sheet/draft-reverted
  [state event]
  (assoc-in state [(:sheet-id event) :draft-dirty?] false))

(defmethod sheets* :sheet/stash-restored
  [state event]
  ;; Restoring stash clears the stash (single stash per sheet)
  (-> state
      (assoc-in [(:sheet-id event) :has-stash?] false)
      (assoc-in [(:sheet-id event) :draft-dirty?] true)))

(defmethod sheets* :sheet/execution-mode-set
  [state event]
  (assoc-in state [(:sheet-id event) :execution-mode] (:mode event)))

(defmethod sheets* :sheet/content-hash-set
  [state event]
  (assoc-in state [(:sheet-id event) :content-hash] (:content-hash event)))

(defmethod sheets* :default
  [state event]
  ;; For events that modify the tree structure, mark draft as dirty
  ;; (only if a published version exists)
  (let [sheet-id (:sheet-id event)
        event-type (:event/type event)]
    (if (and sheet-id
             (contains? draft-dirty-events event-type)
             (get-in state [sheet-id :published-version]))
      (assoc-in state [sheet-id :draft-dirty?] true)
      state)))

(defn sheets
  "Build sheets read model from events"
  [initial-state events]
  (reduce sheets* initial-state events))

(defreadmodel :sheet sheets
  {:events sheet-events :version 1}
  [state event] (sheets* state event))

;; =============================================================================
;; Nodes Projection
;; =============================================================================

(defmulti nodes*
  "Apply event to nodes read model"
  (fn [_state event] (:event/type event)))

(defmethod nodes* :sheet/node-created
  [state event]
  (let [node-id (:node-id event)
        parent-id (:parent-id event)
        index (or (:index event) 0)]
    (-> state
        ;; Create the new node
        (assoc node-id
               {:id node-id
                :sheet-id (:sheet-id event)
                :type (:type event)
                :name (case (:type event)
                        :leaf "New Leaf"
                        :sequence "Sequence"
                        :fallback "Fallback"
                        :condition "Condition"
                        :llm-condition "LLM Condition"
                        :parallel "Parallel"
                        :map-each "Map Each"
                        :repl-researcher "REPL Researcher"
                        :delegate "Delegate")
                :parent-id parent-id
                :children-ids []
                :status :idle
                ;; Leaf fields
                :instruction nil
                :reads []
                :writes []
                :decorators []
                :executor nil
                :model nil
                :fn nil
                :tools nil
                :retry nil
                ;; Condition fields
                :check nil
                ;; Parallel fields
                :success-policy nil
                :failure-policy nil
                ;; Map-each fields
                :source-key nil
                :item-key nil
                :output-key nil
                :max-concurrency nil
                ;; Execution tracking
                :last-error nil})
        ;; Add to parent's children if parent exists
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (let [children (or children [])]
                         (vec (concat (take index children)
                                      [node-id]
                                      (drop index children))))))))))

(defmethod nodes* :sheet/node-moved
  [state event]
  (let [node-id (:node-id event)
        old-parent-id (:old-parent-id event)
        new-parent-id (:new-parent-id event)
        index (:index event)]
    (-> state
        ;; Update node's parent-id
        (assoc-in [node-id :parent-id] new-parent-id)
        ;; Remove from old parent's children
        (cond-> old-parent-id
          (update-in [old-parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        ;; Add to new parent's children at index
        (update-in [new-parent-id :children-ids]
                   (fn [children]
                     (let [children (or children [])]
                       (vec (concat (take index children)
                                    [node-id]
                                    (drop index children)))))))))

(defmethod nodes* :sheet/node-reordered
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])
        new-index (:new-index event)]
    (if parent-id
      (update-in state [parent-id :children-ids]
                 (fn [children]
                   (let [without-node (vec (remove #(= % node-id) children))]
                     (vec (concat (take new-index without-node)
                                  [node-id]
                                  (drop new-index without-node))))))
      state)))

(defmethod nodes* :sheet/node-deleted
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])]
    (-> state
        ;; Remove from parent's children
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        ;; Remove the node itself
        (dissoc node-id))))

(defmethod nodes* :sheet/node-name-set
  [state event]
  (assoc-in state [(:node-id event) :name] (:name event)))

(defmethod nodes* :sheet/node-instruction-set
  [state event]
  (assoc-in state [(:node-id event) :instruction] (:instruction event)))

(defmethod nodes* :sheet/node-context-set
  [state event]
  (assoc-in state [(:node-id event) :context] (:context event)))

(defmethod nodes* :sheet/node-io-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))))

(defmethod nodes* :sheet/node-decorators-set
  [state event]
  (assoc-in state [(:node-id event) :decorators] (:decorators event)))

(defmethod nodes* :sheet/node-check-set
  [state event]
  (assoc-in state [(:node-id event) :check] (:check event)))

(defmethod nodes* :sheet/node-executor-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :executor] (:executor event))
      (assoc-in [(:node-id event) :model] (:model event))
      (assoc-in [(:node-id event) :fn] (:fn event))
      (assoc-in [(:node-id event) :tools] (:tools event))))

(defmethod nodes* :sheet/node-retry-set
  [state event]
  (assoc-in state [(:node-id event) :retry] (:retry event)))

(defmethod nodes* :sheet/parallel-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :success-policy] (:success-policy event))
      (assoc-in [(:node-id event) :failure-policy] (:failure-policy event))))

(defmethod nodes* :sheet/map-each-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :source-key] (:source-key event))
      (assoc-in [(:node-id event) :item-key] (:item-key event))
      (assoc-in [(:node-id event) :output-key] (:output-key event))
      (assoc-in [(:node-id event) :max-concurrency] (:max-concurrency event))))

(defmethod nodes* :sheet/llm-condition-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :instruction] (:instruction event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :model] (:model event))))

(defmethod nodes* :sheet/repl-researcher-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :instruction] (:instruction event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))
      (assoc-in [(:node-id event) :mcp-tools] (:mcp-tools event))
      (assoc-in [(:node-id event) :browser-tools] (:browser-tools event))
      (assoc-in [(:node-id event) :model] (:model event))
      (assoc-in [(:node-id event) :max-iterations] (:max-iterations event))))

(defmethod nodes* :sheet/delegate-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :target-sheet-id] (:target-sheet-id event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))
      (assoc-in [(:node-id event) :delegate-timeout-ms] (:timeout-ms event))
      (assoc-in [(:node-id event) :inherit-ontology?] (:inherit-ontology? event))))

(defmethod nodes* :sheet/node-judges-set
  [state event]
  (assoc-in state [(:node-id event) :judges] (:judges event)))

(defmethod nodes* :sheet/node-execution-started
  [state event]
  (assoc-in state [(:node-id event) :status] :running))

(defmethod nodes* :sheet/node-execution-completed
  [state event]
  (-> state
      (assoc-in [(:node-id event) :status] (:status event))
      (assoc-in [(:node-id event) :last-error] (:error event))))

(defmethod nodes* :default [state _] state)

(defn nodes
  "Build nodes read model from events"
  [initial-state events]
  (reduce nodes* initial-state events))

(defreadmodel :sheet nodes
  {:events node-events :version 1}
  [state event] (nodes* state event))

;; =============================================================================
;; Blackboard Projection
;; =============================================================================

(defmulti blackboard*
  "Apply event to blackboard read model"
  (fn [_state event] (:event/type event)))

(defmethod blackboard* :sheet/key-declared
  [state event]
  (assoc state (:key event)
         {:sheet-id (:sheet-id event)
          :key (:key event)
          :schema (:schema event)
          :value nil
          :version 0}))

(defmethod blackboard* :sheet/key-schema-updated
  [state event]
  (assoc-in state [(:key event) :schema] (:schema event)))

(defmethod blackboard* :sheet/key-value-set
  [state event]
  (-> state
      (assoc-in [(:key event) :value] (:value event))
      (assoc-in [(:key event) :version] (:version event))))

(defmethod blackboard* :sheet/key-deleted
  [state event]
  (dissoc state (:key event)))

(defmethod blackboard* :default [state _] state)

(defn blackboard
  "Build blackboard read model from events"
  [initial-state events]
  (reduce blackboard* (or initial-state {}) events))

(defreadmodel :sheet blackboard
  {:events blackboard-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :key}
  [state event] (blackboard* state event))

;; =============================================================================
;; Judges Projection
;; =============================================================================

(defmulti judges*
  "Apply event to judges read model"
  (fn [_state event] (:event/type event)))

(defmethod judges* :sheet/judge-declared
  [state event]
  (assoc state (:judge-name event)
         (merge (:judge-config event)
                {:sheet-id (:sheet-id event)
                 :judge-name (:judge-name event)
                 :criteria-version (or (:criteria-version event) 1)})))

(defmethod judges* :default [state _] state)

(defn judges
  "Build judges read model from events"
  [initial-state events]
  (reduce judges* (or initial-state {}) events))

(defreadmodel :sheet judges
  {:events judge-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :judge-name}
  [state event] (judges* state event))

;; =============================================================================
;; Ticks Projection (for tracking execution state)
;; =============================================================================

(defmulti ticks*
  "Apply event to ticks read model"
  (fn [_state event] (:event/type event)))

(defmethod ticks* :sheet/tree-tick-started
  [state event]
  (let [tick-id (:tick-id event)
        iteration (or (:iteration event) 1)
        existing (get state tick-id)]
    (if existing
      ;; Re-tick - update iteration count
      (-> state
          (assoc-in [tick-id :iteration] iteration)
          (assoc-in [tick-id :status] :running))
      ;; New tick
      (assoc state tick-id
             {:id tick-id
              :sheet-id (:sheet-id event)
              :status :running
              :iteration iteration
              :started-at (str (:event/timestamp event))
              :completed-at nil
              :root-status nil}))))

(defmethod ticks* :sheet/tree-tick-completed
  [state event]
  (-> state
      (assoc-in [(:tick-id event) :status] :completed)
      (assoc-in [(:tick-id event) :root-status] (:root-status event))
      (assoc-in [(:tick-id event) :completed-at] (str (:event/timestamp event)))))

(defmethod ticks* :sheet/tick-cancelled
  [state event]
  (-> state
      (assoc-in [(:tick-id event) :status] :cancelled)
      (assoc-in [(:tick-id event) :cancelled-at] (str (:event/timestamp event)))))

(defmethod ticks* :default [state _] state)

(defn ticks
  "Build ticks read model from events"
  [initial-state events]
  (reduce ticks* (or initial-state {}) events))

(defreadmodel :sheet ticks
  {:events tick-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (ticks* state event))

;; =============================================================================
;; Versions Projection
;; =============================================================================

(defmulti versions*
  "Apply event to versions read model.
   State structure: {sheet-id {version-number version-snapshot}}"
  (fn [_state event] (:event/type event)))

(defmethod versions* :sheet/version-published
  [state event]
  (let [sheet-id (:sheet-id event)
        version-num (:version-number event)]
    (assoc-in state [sheet-id version-num]
              {:snapshot-id (:snapshot-id event)
               :sheet-id sheet-id
               :version-number version-num
               :published-at (str (:event/timestamp event))
               :description (:description event)
               :snapshot (:snapshot event)})))

(defmethod versions* :default [state _] state)

(defn versions
  "Build versions read model from events"
  [initial-state events]
  (reduce versions* (or initial-state {}) events))

(defreadmodel :sheet versions
  {:events version-events :version 1}
  [state event] (versions* state event))

;; =============================================================================
;; Stash Projection
;; =============================================================================

(defmulti stashes*
  "Apply event to stashes read model.
   State structure: {sheet-id stash} - only one stash per sheet"
  (fn [_state event] (:event/type event)))

(defmethod stashes* :sheet/draft-stashed
  [state event]
  (let [sheet-id (:sheet-id event)]
    (assoc state sheet-id
           {:stash-id (:stash-id event)
            :sheet-id sheet-id
            :stashed-at (str (:event/timestamp event))
            :snapshot (:snapshot event)})))

(defmethod stashes* :sheet/stash-restored
  [state event]
  ;; Restoring a stash consumes it
  (dissoc state (:sheet-id event)))

(defmethod stashes* :sheet/draft-reverted
  [state event]
  ;; A new stash was created before revert, which overwrites old stash
  ;; The stash event comes before the revert event, so nothing to do here
  state)

(defmethod stashes* :default [state _] state)

(defn stashes
  "Build stashes read model from events"
  [initial-state events]
  (reduce stashes* (or initial-state {}) events))

(defreadmodel :sheet stashes
  {:events version-events :version 1}
  [state event] (stashes* state event))

;; =============================================================================
;; Query Helper Functions
;; =============================================================================

(defn get-sheet
  "Get a single sheet by ID"
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/sheets {:tags #{[:sheet sheet-id]}})
       sheet-id))

(defn get-sheets-all
  "Get all sheets"
  [ctx]
  (vals (rmp/project ctx :sheet/sheets)))

(defn get-sheet-by-name
  "Find a sheet by name. Returns nil if not found."
  [ctx name]
  (->> (vals (rmp/project ctx :sheet/sheets))
       (filter #(= (:name %) name))
       first))

(defn get-node
  "Get a single node by ID"
  [ctx sheet-id node-id]
  (get (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}})
       node-id))

(defn get-nodes-for-sheet
  "Get all nodes in a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}})))

(defn get-nodes-by-id
  "Get all nodes in a sheet as a map keyed by node-id"
  [ctx sheet-id]
  (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}}))

(defn get-blackboard-for-sheet
  "Get the blackboard for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/blackboard {:partition-key sheet-id})))

(defn get-blackboard-by-key
  "Get the blackboard for a sheet as a map keyed by key name"
  [ctx sheet-id]
  (rmp/project ctx :sheet/blackboard {:partition-key sheet-id}))

(defn get-judges
  "Get all judges declared for a sheet as a map keyed by judge name"
  [ctx sheet-id]
  (rmp/project ctx :sheet/judges {:partition-key sheet-id}))

(defn get-judge
  "Get a single judge by name"
  [ctx sheet-id judge-name]
  (get (get-judges ctx sheet-id) judge-name))

(defn get-tick
  "Get a single tick by ID"
  [ctx tick-id]
  (get (rmp/project ctx :sheet/ticks {:tags #{[:tick tick-id]}})
       tick-id))

(defn is-tick-cancelled?
  "Check if a tick has been cancelled"
  [ctx tick-id]
  (let [tick (get-tick ctx tick-id)]
    (= :cancelled (:status tick))))

(defn get-ticks-for-sheet
  "Get all ticks for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/ticks {:partition-key sheet-id})))

(defn get-current-tick
  "Get the current running tick for a sheet, if any"
  [ctx sheet-id]
  (->> (get-ticks-for-sheet ctx sheet-id)
       (filter #(= :running (:status %)))
       (sort-by :started-at)
       last))

(defn get-root-node
  "Get the root node for a sheet"
  [ctx sheet-id]
  (let [sheet (get-sheet ctx sheet-id)]
    (when-let [root-id (:root-node-id sheet)]
      (get-node ctx sheet-id root-id))))

(defn get-children
  "Get child nodes of a parent node"
  [ctx sheet-id parent-id]
  (let [parent (get-node ctx sheet-id parent-id)]
    (when parent
      (mapv #(get-node ctx sheet-id %)
            (:children-ids parent)))))

(defn get-descendants
  "Get all descendant nodes of a node (recursive)"
  [ctx sheet-id node-id]
  (let [nodes-by-id (get-nodes-by-id ctx sheet-id)]
    (letfn [(descendants [nid]
              (let [node (get nodes-by-id nid)]
                (cons node
                      (mapcat descendants (:children-ids node)))))]
      (rest (descendants node-id))))) ;; rest to exclude the node itself

(defn is-descendant?
  "Check if potential-descendant is a descendant of ancestor-id"
  [nodes-by-id ancestor-id potential-descendant-id]
  (loop [to-check #{potential-descendant-id}
         visited #{}]
    (if (empty? to-check)
      false
      (let [current (first to-check)
            remaining (disj to-check current)]
        (if (visited current)
          (recur remaining visited)
          (let [node (get nodes-by-id current)
                parent-id (:parent-id node)]
            (cond
              (= parent-id ancestor-id) true
              (nil? parent-id) (recur remaining (conj visited current))
              :else (recur (conj remaining parent-id) (conj visited current)))))))))

;; =============================================================================
;; Version Query Helpers
;; =============================================================================

(defn get-versions-for-sheet
  "Get all published versions for a sheet, sorted by version number"
  [ctx sheet-id]
  (->> (get (rmp/project ctx :sheet/versions {:tags #{[:sheet sheet-id]}})
            sheet-id)
       vals
       (sort-by :version-number)))

(defn get-version
  "Get a specific published version by version number"
  [ctx sheet-id version-number]
  (get-in (rmp/project ctx :sheet/versions {:tags #{[:sheet sheet-id]}})
          [sheet-id version-number]))

(defn get-latest-version
  "Get the latest published version for a sheet"
  [ctx sheet-id]
  (let [sheet (get-sheet ctx sheet-id)
        version-num (:published-version sheet)]
    (when version-num
      (get-version ctx sheet-id version-num))))

(defn get-stash
  "Get the stash for a sheet, if any"
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/stashes {:tags #{[:sheet sheet-id]}})
       sheet-id))

;; =============================================================================
;; Traces Projection
;; =============================================================================

(def trace-events
  "Events that affect execution traces read model"
  #{:sheet/execution-traced})

(defmulti traces*
  "Apply event to traces read model.
   State structure: {trace-id trace}"
  (fn [_state event] (:event/type event)))

(defmethod traces* :sheet/execution-traced
  [state event]
  (let [trace-id (:trace-id event)]
    (assoc state trace-id
           {:trace-id trace-id
            :sheet-id (:sheet-id event)
            :version-number (:version-number event)
            :started-at (str (:started-at event))
            :completed-at (str (:completed-at event))
            :duration-ms (:duration-ms event)
            :status (:status event)
            :input-snapshot (:input-snapshot event)
            :output-snapshot (:output-snapshot event)
            :node-traces (:node-traces event)
            :error (:error event)})))

(defmethod traces* :default [state _] state)

(defn traces
  "Build traces read model from events"
  [initial-state events]
  (reduce traces* (or initial-state {}) events))

(defreadmodel :sheet traces
  {:events trace-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :trace-id}
  [state event] (traces* state event))

;; =============================================================================
;; Trace Query Helpers
;; =============================================================================

(defn get-trace
  "Get a single execution trace by ID"
  [ctx trace-id]
  (get (rmp/project ctx :sheet/traces {:tags #{[:trace trace-id]}})
       trace-id))

(defn get-traces-for-sheet
  "Get all execution traces for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/traces {:partition-key sheet-id})))

(defn get-traces-for-version
  "Get all execution traces for a specific version of a sheet"
  [ctx sheet-id version-number]
  (->> (get-traces-for-sheet ctx sheet-id)
       (filter #(= version-number (:version-number %)))))

(defn get-all-traces
  "Get all execution traces across all sheets"
  [ctx]
  (vals (rmp/project ctx :sheet/traces)))

(defn get-sheets-name-map
  "Get map of sheet-id -> sheet-name for denormalization"
  [ctx]
  (into {} (map (juxt :id :name) (get-sheets-all ctx))))

;; =============================================================================
;; Tick Execution Contexts (Tick-Scoped Isolation)
;; =============================================================================
;;
;; When a tick is started with an execution-snapshot (async path), this read
;; model stores the full execution context per tick: nodes-by-id, blackboard
;; (with schemas), options, etc. The blackboard values are updated as
;; execution-value-written events arrive.
;;
;; For ticks without execution-snapshot (legacy UI ticks), no context is stored
;; and processors fall back to reading live sheet state.

(def tick-execution-context-events
  "Events that affect tick-scoped execution contexts"
  #{:sheet/tree-tick-started
    :sheet/execution-value-written})

(defmulti tick-execution-contexts*
  "Apply event to tick execution contexts read model.
   State structure: {tick-id {:nodes-by-id {...}
                              :root-node-id uuid
                              :blackboard {\"key\" {:key k :schema s :value v :version n}}
                              :version-number int?
                              :options {...}}}"
  (fn [_state event] (:event/type event)))

(defmethod tick-execution-contexts* :sheet/tree-tick-started
  [state event]
  (if-let [snapshot (:execution-snapshot event)]
    ;; Snapshot-based tick: store full execution context
    (let [bb-entries (:blackboard-entries snapshot)
          inputs (or (:inputs event) {})
          ;; Merge inputs into blackboard entries
          ;; Note: inputs may have string keys from runtime/execute, but blackboard uses keyword keys
          ;; If bb-entries is empty (cache miss), create entries from inputs directly
          blackboard (reduce (fn [bb [key-name value]]
                               (let [kw-key (if (string? key-name) (keyword key-name) key-name)]
                                 (if (get bb kw-key)
                                   ;; Key exists - update value
                                   (-> bb
                                       (assoc-in [kw-key :value] value)
                                       (assoc-in [kw-key :version] 1))
                                   ;; Key doesn't exist - create entry from input
                                   ;; This handles cache misses where bb-entries is empty
                                   (assoc bb kw-key
                                          {:sheet-id (:sheet-id event)
                                           :key kw-key
                                           :schema :any  ;; Unknown schema, but value is provided
                                           :value value
                                           :version 1}))))
                             bb-entries
                             inputs)]
      (assoc state (:tick-id event)
             (cond-> {:sheet-id (:sheet-id event)
                      :nodes-by-id (:nodes-by-id snapshot)
                      :root-node-id (:root-node-id snapshot)
                      :blackboard blackboard
                      :version-number (:version-number snapshot)
                      :options (:options event)}
               (:instruction-overrides snapshot)
               (assoc :instruction-overrides (:instruction-overrides snapshot)))))
    ;; No snapshot - legacy tick, no context stored
    state))

(defmethod tick-execution-contexts* :sheet/execution-value-written
  [state event]
  (let [tick-id (:tick-id event)
        k (:key event)
        v (:value event)]
    (if (contains? state tick-id)
      (-> state
          (assoc-in [tick-id :blackboard k :value] v)
          (update-in [tick-id :blackboard k :version] (fnil inc 0)))
      state)))

(defmethod tick-execution-contexts* :default [state _] state)

(defn tick-execution-contexts
  "Build tick execution contexts read model from events"
  [initial-state events]
  (reduce tick-execution-contexts* (or initial-state {}) events))

(defreadmodel :sheet tick-execution-contexts
  {:events tick-execution-context-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (tick-execution-contexts* state event))

(defn get-tick-execution-context
  "Get the full execution context for a tick-scoped execution.
   Returns nil if this tick has no snapshot (legacy UI tick)."
  [ctx tick-id]
  (get (rmp/project ctx :sheet/tick-execution-contexts {:tags #{[:tick tick-id]}})
       tick-id))

(defn get-tick-blackboard
  "Get the blackboard for a tick-scoped execution.
   Returns nil if this tick has no snapshot."
  [ctx tick-id]
  (:blackboard (get-tick-execution-context ctx tick-id)))

(defn get-tick-nodes-by-id
  "Get the nodes-by-id for a tick-scoped execution.
   Returns nil if this tick has no snapshot."
  [ctx tick-id]
  (:nodes-by-id (get-tick-execution-context ctx tick-id)))

;; =============================================================================
;; In-Progress Executions Projection
;; =============================================================================

(def in-progress-execution-events
  "Events that affect in-progress execution tracking"
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled
    :sheet/execution-traced
    :sheet/sequence-progress-updated
    :sheet/map-each-progress-updated})

(defmulti in-progress-executions*
  "Apply event to in-progress executions read model.
   State structure: {tick-id execution-context}
   where execution-context contains:
   - :tick-id, :sheet-id, :status, :started-at
   - :current-node-id - which node is currently executing
   - :nodes-in-progress - set of node-ids currently executing
   - :sequence-progress - {node-id {:index N :total M}}
   - :map-each-progress - {node-id {:index N :total M}}"
  (fn [_state event] (:event/type event)))

(defmethod in-progress-executions* :sheet/tree-tick-started
  [state event]
  (let [tick-id (:tick-id event)]
    (assoc state tick-id
           {:tick-id tick-id
            :sheet-id (:sheet-id event)
            :status :running
            :started-at (str (:event/timestamp event))
            :current-node-id nil
            :nodes-in-progress #{}
            :sequence-progress {}
            :map-each-progress {}})))

(defmethod in-progress-executions* :sheet/node-execution-started
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (-> state
          (assoc-in [tick-id :current-node-id] node-id)
          (update-in [tick-id :nodes-in-progress] conj node-id))
      state)))

(defmethod in-progress-executions* :sheet/node-execution-completed
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (-> state
          (update-in [tick-id :nodes-in-progress] disj node-id)
          ;; Update current-node-id to another running node if any
          (update tick-id
                  (fn [ctx]
                    (let [remaining (disj (:nodes-in-progress ctx) node-id)]
                      (assoc ctx :current-node-id (first remaining)))))
          ;; Clear progress for completed node
          (update-in [tick-id :sequence-progress] dissoc node-id)
          (update-in [tick-id :map-each-progress] dissoc node-id))
      state)))

(defmethod in-progress-executions* :sheet/tree-tick-completed
  [state event]
  (let [tick-id (:tick-id event)]
    (-> state
        (assoc-in [tick-id :status] :completed)
        (assoc-in [tick-id :completed-at] (str (:event/timestamp event)))
        (assoc-in [tick-id :root-status] (:root-status event)))))

(defmethod in-progress-executions* :sheet/tick-cancelled
  [state event]
  (let [tick-id (:tick-id event)]
    (-> state
        (assoc-in [tick-id :status] :cancelled)
        (assoc-in [tick-id :cancelled-at] (str (:event/timestamp event))))))

(defmethod in-progress-executions* :sheet/execution-traced
  [state event]
  ;; Execution traced means it's complete - remove from in-progress
  ;; But keep completed status for querying recently completed
  (let [trace-id (:trace-id event)]
    ;; The tick-id might not match trace-id, so we need to find it
    ;; For now, leave the state as-is since tree-tick-completed handles completion
    state))

(defmethod in-progress-executions* :sheet/sequence-progress-updated
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (assoc-in state [tick-id :sequence-progress node-id]
                {:index (:child-index event)
                 :total (:total-children event)})
      state)))

(defmethod in-progress-executions* :sheet/map-each-progress-updated
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (assoc-in state [tick-id :map-each-progress node-id]
                {:index (:item-index event)
                 :total (:total-items event)})
      state)))

(defmethod in-progress-executions* :default [state _] state)

(defn in-progress-executions
  "Build in-progress executions read model from events"
  [initial-state events]
  (reduce in-progress-executions* (or initial-state {}) events))

(defreadmodel :sheet in-progress-executions
  {:events in-progress-execution-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (in-progress-executions* state event))

;; =============================================================================
;; In-Progress Execution Query Helpers
;; =============================================================================

(defn get-active-executions
  "Get all currently running executions for a sheet.
   Returns a list of execution contexts with status :running."
  [ctx sheet-id]
  (->> (rmp/project ctx :sheet/in-progress-executions {:partition-key sheet-id})
       vals
       (filter #(= :running (:status %)))))

(defn get-all-active-executions
  "Get all currently running executions across all sheets.
   Returns a list of execution contexts with status :running."
  [ctx]
  (->> (rmp/project ctx :sheet/in-progress-executions)
       vals
       (filter #(= :running (:status %)))))

(defn get-execution-context
  "Get the execution context for a specific tick.
   Returns nil if not found."
  [ctx tick-id]
  (get (rmp/project ctx :sheet/in-progress-executions {:tags #{[:tick tick-id]}})
       tick-id))

(defn get-in-progress-node
  "Get the current node being executed for a tick.
   Returns the node-id or nil if no node is running."
  [ctx tick-id]
  (:current-node-id (get-execution-context ctx tick-id)))

(defn get-sequence-progress
  "Get sequence progress for a specific node in a tick.
   Returns {:index N :total M} or nil."
  [ctx tick-id node-id]
  (get-in (get-execution-context ctx tick-id) [:sequence-progress node-id]))

(defn get-map-each-progress
  "Get map-each progress for a specific node in a tick.
   Returns {:index N :total M} or nil."
  [ctx tick-id node-id]
  (get-in (get-execution-context ctx tick-id) [:map-each-progress node-id]))

;; =============================================================================
;; Tree Metadata Read Model
;; =============================================================================

(def tree-metadata-events
  "Events for tree metadata"
  #{:sheet/tree-metadata-extracted
    :sheet/sheet-deleted})

(defmulti tree-metadata* (fn [_state event] (:event/type event)))

(defmethod tree-metadata* :default [state _] state)

(defmethod tree-metadata* :sheet/tree-metadata-extracted [state event]
  (assoc state (:sheet-id event) (:metadata event)))

(defmethod tree-metadata* :sheet/sheet-deleted [state event]
  (dissoc state (:sheet-id event)))

(defn tree-metadata
  "Reduce function for tree metadata.
   Returns map of sheet-id -> metadata."
  [initial-state events]
  (reduce tree-metadata* initial-state events))

(defreadmodel :sheet tree-metadata
  {:events tree-metadata-events :version 1}
  [state event] (tree-metadata* state event))

(defn get-tree-metadata
  "Get metadata for a specific sheet.
   Returns the most recent metadata or nil."
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/tree-metadata {:tags #{[:sheet sheet-id]}})
       sheet-id))

(defn get-all-tree-metadata
  "Get metadata for all sheets.
   Returns map of sheet-id -> metadata."
  [ctx]
  (rmp/project ctx :sheet/tree-metadata))

(defn find-trees-by-problem-type
  "Find sheets that solve a specific problem type.
   Returns vector of metadata maps (with :sheet-id) sorted by avg-score desc."
  [ctx problem-type {:keys [min-score limit]
                     :or {min-score 0 limit 10}}]
  (let [all-metadata (get-all-tree-metadata ctx)]
    (->> all-metadata
         (filter (fn [[_id meta]]
                   (and (some #{problem-type} (:problem-types meta))
                        (>= (or (:avg-score meta) 0) min-score))))
         (sort-by (comp #(or (:avg-score %) 0) second) >)
         (take limit)
         (mapv (fn [[id meta]] (assoc meta :sheet-id id))))))

;; =============================================================================
;; Rolling Metrics Read Model
;; =============================================================================

(def rolling-window-size
  "Number of recent executions to track for rolling metrics"
  100)

(defmulti rolling-metrics* (fn [_state event] (:event/type event)))

(defmethod rolling-metrics* :default [state _] state)

(defmethod rolling-metrics* :sheet/node-execution-completed [state event]
  (let [{:keys [sheet-id node-id status duration-ms]} event
        key [sheet-id node-id]
        success? (= status :success)]
    (update state key
      (fn [metrics]
        (let [metrics (or metrics {:sheet-id sheet-id
                                   :executions []
                                   :success-count 0
                                   :failure-count 0
                                   :total-duration 0})]
          (-> metrics
              (update :executions #(->> (conj % {:status status
                                                  :duration-ms duration-ms
                                                  :timestamp (str (:event/timestamp event))})
                                        (take-last rolling-window-size)
                                        vec))
              (update :success-count #(if success? (inc %) %))
              (update :failure-count #(if (not success?) (inc %) %))
              (update :total-duration #(+ % (or duration-ms 0)))))))))

(def rolling-metrics-events
  "Events that affect rolling metrics read model"
  #{:sheet/node-execution-completed})

(defn rolling-metrics
  "Reduce function for rolling metrics.
   Returns map of [sheet-id node-id] -> metrics."
  [initial-state events]
  (reduce rolling-metrics* initial-state events))

(defreadmodel :sheet rolling-metrics
  {:events rolling-metrics-events :version 2
   :partition-fn :sheet-id
   :entity-id-fn (fn [event] [(:sheet-id event) (:node-id event)])}
  [state event] (rolling-metrics* state event))

(defn calculate-trend
  "Calculate recent trend: :improving, :declining, or :stable.
   Requires at least 10 executions to calculate."
  [executions]
  (when (>= (count executions) 10)
    (let [recent (take-last 10 executions)
          older (take 10 (drop (- (count executions) 20) executions))
          recent-rate (/ (count (filter #(= :success (:status %)) recent)) 10.0)
          older-rate (when (seq older)
                       (/ (count (filter #(= :success (:status %)) older))
                          (double (count older))))]
      (cond
        (nil? older-rate) :stable
        (> recent-rate (+ older-rate 0.1)) :improving
        (< recent-rate (- older-rate 0.1)) :declining
        :else :stable))))

(defn compute-node-metrics
  "Compute metrics from a rolling metrics entry.
   Returns map with success-rate, failure-rate, avg-duration-ms, trend."
  [metrics]
  (let [n (count (:executions metrics))
        durations (keep :duration-ms (:executions metrics))]
    (when (pos? n)
      {:execution-count n
       :success-rate (double (/ (:success-count metrics) n))
       :failure-rate (double (/ (:failure-count metrics) n))
       :avg-duration-ms (when (seq durations)
                          (double (/ (reduce + durations) (count durations))))
       :recent-trend (calculate-trend (:executions metrics))})))

(defn get-node-rolling-metrics
  "Get rolling metrics for a specific node.
   Returns metrics map or nil if no data."
  [ctx sheet-id node-id]
  (let [all-metrics (rmp/project ctx :sheet/rolling-metrics {:partition-key sheet-id})
        metrics (get all-metrics [sheet-id node-id])]
    (when metrics
      (merge {:sheet-id sheet-id
              :node-id node-id}
             (compute-node-metrics metrics)))))

(defn get-tree-rolling-metrics
  "Get rolling metrics for all nodes in a sheet.
   Returns map with :nodes vector and :total-executions."
  [ctx sheet-id]
  (let [all-metrics (rmp/project ctx :sheet/rolling-metrics {:partition-key sheet-id})
        sheet-metrics (filter (fn [[[sid _nid] _]] (= sid sheet-id)) all-metrics)]
    {:sheet-id sheet-id
     :nodes (->> sheet-metrics
                 (keep (fn [[[_ nid] metrics]]
                         (when metrics
                           (merge {:node-id nid}
                                  (compute-node-metrics metrics)))))
                 vec)
     :total-executions (->> sheet-metrics
                            (map (comp count :executions second))
                            (reduce + 0))}))
