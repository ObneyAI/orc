(ns ai.obney.orc.ontology.core.harvest
  "EL-4 (ADR 0015): HARVEST — the emergence loop's terminus. Crystallizes a
   recurring + well-scored + coherent :tree-class into a named durable
   behavioral-subtree via the existing mint-behavioral-subtree command.

   Re-orchestration, not reinvention: reuses the standing judge-averages
   read-model (Slice 1) + get-consolidation-total + the ALREADY-consolidated
   tree-class description (the consolidator's synthesis — NO second LLM path)
   + the mint-behavioral-subtree command (stable derived id). The conservative
   gate (Slice 2) is the safety."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Slice 2 — the conservative harvest GATE (pure)
;; =============================================================================
;;
;; The gate IS the safety: harvest is the ONLY automatic path that CREATES a
;; durable behavior, so the bar is deliberately HIGH and conservative. A
;; class must clear ALL THREE conditions:
;;   1. RECURRING       — occurrences >= :min-occurrences
;;   2. WELL-SCORED     — judge-average >= :min-judge-average (nil => fail)
;;   3. COHERENT        — a tight cluster, not a grab-bag: the count of
;;                        distinct tree-shapes seen for the class is small
;;                        relative to occurrences
;;                        (distinct-tree-shapes / occurrences <= :max-shapes-ratio).
;;                        A recurring pattern converges on a few shapes; a
;;                        grab-bag scatters across many.
;;
;; Knobs are tunable and started HIGH (measurement-first: raise the bar,
;; lower it only with data). config = {:min-occurrences :min-judge-average
;; :max-shapes-ratio}.

(def default-harvest-config
  "Conservative defaults — started HIGH. Only a class with real recurring
   volume (>= 10), a strong judge baseline (>= 0.8), and shape-convergence
   (<= half as many distinct shapes as occurrences) is harvested."
  {:min-occurrences   10
   :min-judge-average 0.8
   :max-shapes-ratio  0.5})

(defn harvest-candidate?
  "Pure conservative dual+coherence gate. Returns true iff the class is
   recurring AND well-scored AND coherent, per config. A nil :judge-average
   (no judge signal) never passes. A non-positive occurrence count never
   passes (avoids divide-by-zero + seeds/total=0 slipping through)."
  [{:keys [occurrences judge-average distinct-tree-shapes]}
   {:keys [min-occurrences min-judge-average max-shapes-ratio]}]
  (boolean
    (and (number? occurrences)
         (pos? occurrences)
         (>= occurrences min-occurrences)
         (number? judge-average)
         (>= judge-average min-judge-average)
         (number? distinct-tree-shapes)
         (<= (/ (double distinct-tree-shapes) (double occurrences))
             max-shapes-ratio))))

;; =============================================================================
;; Slice 3 — harvest orchestration + processor
;; =============================================================================

(defn- behavioral-subtree-uri [id] (str "behavioral-subtree:" id))

(defn- class-sheet-ids
  "The source-sheet-ids of every task-classified event assigned to this class."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(= class-id (:assigned-tree-id %)))
       (map :source-sheet-id)
       (into #{})))

(defn distinct-tree-shapes
  "EL-4: count of distinct tree-fingerprints across executions on sheets
   assigned to this class. Mirrors the consolidator's aggregate
   :distinct-tree-shapes — the coherence signal the gate reads. Computed by
   a targeted scan; reached only past the cheap occurrence pre-gate, so it
   runs rarely."
  [ctx class-id]
  (let [sheet-ids (class-sheet-ids ctx class-id)]
    (->> (es/read (:event-store ctx)
                  {:types #{:sheet/rlm-tree-execution-completed}
                   :tenant-id (:tenant-id ctx)})
         (into [])
         (filter #(contains? sheet-ids (:sheet-id %)))
         (keep :tree-fingerprint)
         distinct
         count)))

(defn- latest-classified-behavior-id
  "The top behavior-id from the most-recent task-classified event for this
   class that carries a non-empty :behavioral-subtrees — the live signal of
   which behavior this class composes into. nil when the class has never been
   behaviorally classified."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(and (= class-id (:assigned-tree-id %))
                     (seq (:behavioral-subtrees %))))
       last
       :behavioral-subtrees
       first
       :behavior-id))

(defn nearest-abstract-behavior
  "Walk skos:broader UP from the class's classified behavior to the top
   abstract behavior (auto-waterfall). Returns the abstract behavior's id
   (the parent-behavior for the mint), or nil when the class has no
   behavioral signal to anchor under — in which case harvest is skipped
   rather than creating an orphan."
  [ctx class-id]
  (when-let [behavior-id (latest-classified-behavior-id ctx class-id)]
    (let [concepts (rmp/project ctx :ontology/concepts)]
      (loop [uri (behavioral-subtree-uri behavior-id)
             id  behavior-id
             seen #{}]
        (let [parent-uri (->> (get-in concepts [uri :broader])
                              (filter #(and (string? %)
                                            (str/starts-with? % "behavioral-subtree:")))
                              first)]
          (if (and parent-uri (not (contains? seen parent-uri)))
            (recur parent-uri
                   (subs parent-uri (count "behavioral-subtree:"))
                   (conj seen uri))
            id))))))

(defn already-harvested?
  "Fire-once guard, keyed on the STABLE class-id (independent of any name or
   parent drift): true when a :harvested behavioral-subtree already records
   this class as its source."
  [ctx class-id]
  (boolean
    (some #(and (= :harvested (:provenance %))
                (= class-id (:harvested-from-tree-class %)))
          (into [] (es/read (:event-store ctx)
                            {:types #{:ontology/behavioral-subtree-minted}
                             :tenant-id (:tenant-id ctx)})))))

(defn- best-recommended-pattern
  "The worked DSL for the class = the highest-confidence strength's
   :recommended-pattern from the consolidated description."
  [desc]
  (->> (:strengths desc)
       (filter :recommended-pattern)
       (sort-by :confidence >)
       first
       :recommended-pattern))

(defn harvest-body
  "Assemble the harvested behavior's body by REUSING the consolidator's
   already-synthesized tree-class description (no second synthesis LLM):
   transplant :capabilities/:strengths/:weaknesses/:representative-uses/
   :avoid-when/:summary, add the worked DSL as :recommended-pattern, and
   stamp :version + :consolidated-from-event-count so anti-recency engages.
   Returns nil when the class has no consolidated description yet."
  [desc occurrences]
  (when desc
    (cond-> {:capabilities         (vec (:capabilities desc))
             :strengths            (vec (:strengths desc))
             :weaknesses           (vec (:weaknesses desc))
             :representative-uses  (vec (:representative-uses desc))
             :avoid-when           (vec (:avoid-when desc))
             :summary              (or (:summary desc) "")
             :version              1
             :consolidated-from-event-count occurrences}
      (best-recommended-pattern desc)
      (assoc :recommended-pattern (best-recommended-pattern desc)))))

(defn- harvest-name [class-id] (str "harvested-tree-class-" class-id))

(defn- mint-harvested! [ctx class-id body parent-behavior]
  (command-processor/process-command
    (assoc ctx :command
           {:command/name :ontology/mint-behavioral-subtree
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :name (harvest-name class-id)
            :body body
            :parent-behavior parent-behavior
            :provenance :harvested
            :harvested-from-tree-class class-id})))

(defn maybe-harvest!
  "The harvest decision for one :tree-class. Cheap pre-gate first (recurring
   volume + not-already-harvested), then the full conservative gate + mint.
   Idempotent: fires ONCE per class (the class-id guard). No-op below the
   gate — the conservative bar IS the safety."
  ([ctx class-id] (maybe-harvest! ctx class-id default-harvest-config))
  ([ctx class-id config]
   (let [occurrences (rm/get-consolidation-total ctx :tree-class class-id)]
     (when (and (>= occurrences (:min-occurrences config))
                (not (already-harvested? ctx class-id)))
       (let [judge-avgs (rm/get-tree-class-judge-averages ctx class-id)
             judge-average (when (seq judge-avgs)
                             (/ (reduce + 0.0 (vals judge-avgs))
                                (double (count judge-avgs))))
             shapes (distinct-tree-shapes ctx class-id)
             metrics {:occurrences occurrences
                      :judge-average judge-average
                      :distinct-tree-shapes shapes}]
         (when (harvest-candidate? metrics config)
           (let [desc (rm/get-description ctx :tree-class class-id)
                 parent (nearest-abstract-behavior ctx class-id)
                 body (harvest-body desc occurrences)]
             (cond
               (nil? body)
               (u/log ::harvest-skipped-no-description :class-id class-id)

               (nil? parent)
               (u/log ::harvest-skipped-no-parent
                      :class-id class-id
                      :note "no behavioral anchor via skos:broader — not creating an orphan")

               :else
               (do (u/log ::harvest-minting
                          :class-id class-id
                          :parent-behavior parent
                          :occurrences occurrences
                          :judge-average judge-average
                          :distinct-tree-shapes shapes)
                   (mint-harvested! ctx class-id body parent))))))))))

(defprocessor :ontology on-tree-class-check-harvest
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/task-classified}}
  "EL-4 (ADR 0015): after a task-classified event, check whether the class
   has crossed the conservative harvest gate; if so (and not already
   harvested), crystallize it into a durable behavioral-subtree. Only
   :ontology/task-classified identifies a :tree-class target; the other
   topics are subscribed for symmetry with the threshold processor but are
   no-ops here."
  [{:keys [event] :as context}]
  (when (= :ontology/task-classified (:event/type event))
    (when-let [class-id (:assigned-tree-id event)]
      (try
        (maybe-harvest! context class-id)
        (catch Exception e
          (u/log ::harvest-error :class-id class-id :error (.getMessage e)))))))
