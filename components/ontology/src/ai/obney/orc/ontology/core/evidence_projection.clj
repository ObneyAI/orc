(ns ai.obney.orc.ontology.core.evidence-projection
  "Shape, not values — the projection the consolidator applies to every
   observation before the reflection LLM sees it.

   WHY THIS NAMESPACE EXISTS, AND WHY IT IS NOT IN `consolidator.clj`
   -----------------------------------------------------------------
   For target `:node-type :repl-researcher` the evidence window is 178 real
   observations at ~36 KB each — 6,401,543 bytes — and the provider rejects
   the call outright:

     This endpoint's maximum context length is 1048576 tokens.
     However, you requested about 1571414 tokens

   0/3 successful, non-recoverable. That target and its `:node-instance` twin
   are only 2 of 638 targets, but they are 34 of 145 consolidation requests —
   23.4% of every consolidation ever attempted, at zero success, on the node
   that writes code.

   `:inputs` (65.3%) + `:writes` (32.6%) = 97.9% of those bytes, and inside
   them the orc-sessions session transcript (`:turns`) alone is 74.7% of the
   whole window. It rides in on `:inputs` and is written straight back out in
   `:writes`. It grows within a session, so the window is
   O(observations x session length) and the NEWEST observations are the
   biggest — which is why capping the window is the weakest available lever.

   THE TRADE IS NOT NEW. The emitter already made it for this exact data:
   `orc_service/core/commands.clj` keeps only the NAMESPACED `:inputs` keys
   ('correlation metadata that must survive'; `trace-execution-key` and
   `matches-execution-context?` correlate on them and map-each correctness
   depends on it) and reduces the read VALUES to `:read-keys` +
   `:input-profile`, because those values 'are already durable elsewhere'.
   `externalize-writes?` does the same for `:writes`, pushing the values into
   `:sheet/execution-value-written`. The consolidator was the last place still
   asking for the values.

   RETENTION IS THE POINT. Measured on the real window: 6.40 MB -> ~0.19 MB
   with ALL 178 occurrences retained, so `evidence-window-episodes`, CC-4's
   grounding guard and CC-7's support counts see exactly what they saw before.
   The alternatives all pay in evidence instead: a 1 MB byte budget keeps
   17 of 178 (90% of that target's evidence dropped) and is STILL at 45% of
   the limit; a 64 KB budget yields an EMPTY window, straight into CC-4's
   guard — silence-by-exception becomes silence-by-starvation. A 500-char
   string truncation buys 7.5%, because the payload is thousands of small
   strings, not a few big ones.

   WHAT IS DELIBERATELY *NOT* PROJECTED. `:block-payload` (why a node blocked)
   is 0.9% of the window and does not grow with the session. Eliding it would
   cost the reflection the block reason to buy nothing that matters. `:usage`
   (0.2%) is numbers. `:error` and `:raw-response` are the node's own account
   of a failure — the thing a reflection is FOR.

   This lives outside `consolidator.clj` on purpose: that file is a merge
   collision point, and every line changed there is a line resolved by hand.
   `clean-event-for-llm` delegates here."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

(defn- namespaced-key?
  "The discriminator the emitter uses: blackboard keys are always SIMPLE
   keywords (see `declare-key`); the engine's map-each correlation keys are
   namespaced. So namespaced => correlation metadata that must survive;
   simple => a read value that is durable elsewhere and becomes shape here."
  [k]
  (and (keyword? k) (some? (namespace k))))

(defn- retain
  "assoc that never overwrites. An observation emitted AFTER the producer-side
   fix already carries `:read-keys`/`:input-profile`/`:write-keys`/
   `:write-profile` computed at the source; the source's own account wins over
   anything derived here."
  [m k v]
  (if (contains? m k) m (assoc m k v)))

(defn project-observation
  "Reduce one observation's value payloads to shape, keeping every field the
   evidence identity depends on.

   `:inputs` keeps only its namespaced (execution-context) keys; the read
   VALUES become `:read-keys` + `:input-profile`. `:writes` becomes
   `:write-keys` + `:write-profile`. Everything else — including the
   `[sheet-id tick-id]` occurrence pair CC-4's guard and CC-7's support counts
   resolve against — is passed through untouched.

   Pure, total, and idempotent: a non-map, or an observation with no value
   payload (every `:tree-class` observation, for instance), comes back
   unchanged."
  [observation]
  (if-not (map? observation)
    observation
    (let [inputs (when (map? (:inputs observation)) (:inputs observation))
          writes (when (map? (:writes observation)) (:writes observation))
          exec-context (into {} (filter (fn [[k _]] (namespaced-key? k))) inputs)
          read-values (into {} (remove (fn [[k _]] (namespaced-key? k))) inputs)]
      (cond-> observation
        (some? inputs)     (dissoc :inputs)
        (seq exec-context) (assoc :inputs exec-context)
        (seq read-values)  (-> (retain :read-keys (vec (keys read-values)))
                               (retain :input-profile (orc/profile-values read-values)))
        (some? writes)     (dissoc :writes)
        (seq writes)       (-> (retain :write-keys (vec (keys writes)))
                               (retain :write-profile (orc/profile-values writes)))))))
