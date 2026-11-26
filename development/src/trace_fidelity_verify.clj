(ns trace-fidelity-verify
  "Round-3 verification for the trace-fidelity fixes (F1, F2a, F2b, F3).

   Paste-and-run against a live event store over nREPL AFTER a fresh
   generation on the current code. Every number below is measured the same way
   docs/trace-fidelity-assessment.md measured rounds 1 and 2, so the results
   are directly comparable.

     (require '[trace-fidelity-verify :as v])
     (v/report ctx)          ;; ctx needs :event-store and :tenant-id

   Expected on a clean run — anything else is a live defect:

     :unresolved-reads        0     (round 2: 172)
     :input-profile-mismatch  0     (round 2: 153 of 250)
     :output-profile-mismatch 0     (round 2: 0 — must stay 0)
     :reading-nodes-missing-read-keys 0   (round 1: 222 of 222)
     :node-traces-missing-exec-context 0
     :nil-node-type           small (round 2: 8 of 250; not chased down)
     :map-each-item-collisions 0    (round 2: 94 of 154 misattributed)"
  (:require [ai.obney.orc.evaluation.core.queries :as eval-queries]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.logic :as logic]))

(def ^:private map-each-index-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-index)

(defn- read-type [ctx t]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{t}})))

(defn- rows
  "One row per node trace, with its rehydrated I/O attached."
  [ctx]
  (let [traces (read-type ctx :sheet/execution-traced)]
    (for [t traces
          :let [io (eval-queries/tick-node-io ctx (:trace-id t) (:node-traces t))]
          nt (:node-traces t)]
      {:trace-id (:trace-id t)
       :nt nt
       :io (get io [(:node-id nt) (or (:exec-context nt) {})])})))

(defn- map-each-collisions
  "Iterations of one node that rehydrate to the SAME input value.

   Ground-truth-free stand-in for the index-order oracle: distinct items must
   rehydrate distinctly, so collisions among iterations of the same node mean
   somebody got another iteration's item. Round 2 saw 26 iterations collapse
   onto ~9 distinct values."
  [rs]
  (->> rs
       (filter #(contains? (:exec-context (:nt %)) map-each-index-key))
       (group-by #(get-in % [:nt :node-id]))
       (mapcat (fn [[node-id group]]
                 (let [vals (map #(:inputs (:io %)) group)
                       n (count group)
                       d (count (distinct vals))]
                   (when (< d n)
                     [{:node-id node-id :iterations n :distinct-inputs d
                       :collisions (- n d)}]))))
       vec))

(defn report
  "Whole-run fidelity report. ctx needs :event-store and :tenant-id."
  [ctx]
  (let [rs (vec (rows ctx))
        completions (read-type ctx :sheet/node-execution-completed)
        declared-reads (reduce (fn [acc e] (assoc acc (:node-id e) (:reads e)))
                               {}
                               (read-type ctx :sheet/node-io-set))
        reading (filter #(seq (:read-keys (:nt %))) rs)
        writing (filter #(seq (:write-keys (:nt %))) rs)
        collisions (map-each-collisions rs)]
    {:traces (count (distinct (map :trace-id rs)))
     :node-traces (count rs)
     :completions (count completions)

     :nodes-declaring-reads (count (filter #(seq (get declared-reads (:node-id (:nt %)))) rs))
     :reading-nodes-missing-read-keys
     (count (filter #(and (seq (get declared-reads (:node-id (:nt %))))
                          (empty? (:read-keys (:nt %))))
                    rs))

     :node-traces-missing-exec-context
     (count (remove #(contains? (:nt %) :exec-context) rs))

     :unresolved-reads
     (reduce + (for [r rs]
                 (count (remove #(contains? (:inputs (:io r)) %)
                                (:read-keys (:nt r))))))

     :input-profile-mismatch
     (count (remove #(= (:input-profile (:nt %))
                        (logic/profile-values (:inputs (:io %))))
                    reading))
     :output-profile-mismatch
     (count (remove #(= (:output-profile (:nt %))
                        (logic/profile-values (:outputs (:io %))))
                    writing))

     :map-each-item-collisions (reduce + 0 (map :collisions collisions))
     :map-each-collision-detail collisions

     :nil-node-type (count (filter #(nil? (:node-type %)) completions))

     :completions-with-inlined-writes
     (count (filter #(seq (:writes %)) completions))
     :completions-with-simple-keyword-inputs
     (count (filter #(some (fn [k] (and (keyword? k) (nil? (namespace k))))
                           (keys (:inputs %)))
                    completions))}))

(defn clean?
  "True when every fidelity counter is zero. :nil-node-type is excluded — it
   is pre-existing and was already non-zero before this work."
  [r]
  (every? zero? [(:reading-nodes-missing-read-keys r)
                 (:node-traces-missing-exec-context r)
                 (:unresolved-reads r)
                 (:input-profile-mismatch r)
                 (:output-profile-mismatch r)
                 (:map-each-item-collisions r)
                 (:completions-with-inlined-writes r)
                 (:completions-with-simple-keyword-inputs r)]))
