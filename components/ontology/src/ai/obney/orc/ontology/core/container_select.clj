(ns ai.obney.orc.ontology.core.container-select
  "MT-2 — survey-driven relevance rank + bounded container SELECTION. Replaces the
   blind `(take cap (list-source-containers source))` in the Extract orchestrator
   with a SELECTED, ranked container list:

     structural pre-filter (deterministic, MT-1) → LLM relevance rank (over the
     survivors) → bounded take — with an HONEST total-vs-selected report.

   This ns holds the PURE, INJECTABLE logic (no live LLM, no real source file in a
   test): the effects — sampling the source's containers and the LLM relevance rank
   — are INJECTED capabilities that DEFAULT to the real impl and are FAKED in tests.
   The deterministic pre-filter consumes MT-1's `classify-container-shape` verdict
   (do not re-derive it); the relevance rank is the central seam's delegated `:llm`
   sheet, passed in as `rank-fn`.

   Domain/format-agnostic (#12): it reaches the source ONLY through the uniform
   `container-contract` (`:list-containers` + `:sample-rows`) and reasons on
   structure + goal-relevance — it names NO domain column, entity, or table, and
   holds NO hardcoded table allow/deny list."
  (:require [ai.obney.orc.ontology.core.container-shape :as cs]))

(def default-sample-limit
  "The over-sample size handed to the container sampler. The uniform contract
   exposes NO row-count tool, so the tiny?-signal is recovered by OVER-sampling:
   ask for `limit` rows comfortably above MT-1's `tiny-row-count` (50). If FEWER
   than `limit` came back the container has exactly that many rows (a genuine tiny
   reference is caught); if `limit` came back it has >= limit rows (not tiny). The
   ~64-row sample also gives the classifier a fine distinct-ratio signal."
  64)

(def selected-containers-schema
  "The STRUCTURED shape of the `:selected-containers` list threaded from the central
   selection seam to the Extract orchestrator. Each entry is the ORIGINAL container
   map (carrying the medium-specific `:name`/`:path`/`:sheet` addressing the
   per-container child tick needs) MERGED with MT-1's `:shape` + `:roles` tags
   (carried forward for MT-3). `{:closed false}` + `:any` leaves tolerate the
   per-medium container shape."
  [:vector [:map {:closed false}
            [:name {:optional true} :any]
            [:shape {:optional true} :any]
            [:roles {:optional true} :any]]])

;; ---------------------------------------------------------------------------
;; The INJECTED capabilities — default to the uniform container contract, faked in
;; tests. Resolved via requiring-resolve so this pure ns keeps no hard brick edge
;; to orc-service (the same discipline `list-source-containers` uses).
;; ---------------------------------------------------------------------------

(defn- container-contract-for [source]
  (let [f (requiring-resolve
           'ai.obney.orc.orc-service.core.source-tools/container-contract)]
    (try (f {:type (:type source) :format (:format source) :path (:path source)})
         (catch Throwable _ nil))))

(defn default-list-fn
  "Default `:list-fn` — enumerate the source's containers via the uniform contract's
   `:list-containers` (always `[{:name …}]`). `[]` when the source exposes none."
  [source]
  (let [cc (container-contract-for source)]
    (if (and (map? cc) (fn? (:list-containers cc)))
      (vec (try ((:list-containers cc)) (catch Throwable _ nil)))
      [])))

(defn normalize-sample-result
  "Normalize a uniform-contract `:sample-rows` RESULT to a bare vector of keyed
   row-maps (what `classify-source-containers` consumes). The real contract WRAPS
   its rows: the excel/sql/csv `:sample-rows` return `{:rows [{col val} …]
   :row-count N …}` (excel also carries `:header`), NOT a bare vector — so unwrap
   `:rows` when the result is that wrapper map, accept a bare sequential as-is, and
   keep only the map rows. A `{:error …}` marker (no `:rows`) or a nil result → `[]`
   (surfaced downstream as an unreadable → dropped container, #5). Pure + total."
  [res]
  (let [rows (cond
               (map? res)        (:rows res)   ; the real {:rows … :row-count …} wrapper
               (sequential? res) res           ; already a bare vector of row-maps
               :else             nil)]
    (vec (filter map? rows))))

(defn default-sample-fn
  "Default `:sample-fn` — over-sample ONE container's rows via the uniform contract's
   `:sample-rows`, NORMALIZED (`normalize-sample-result`) to the bare vector of keyed
   row-maps `classify-source-containers` consumes. `[]` when the source exposes no
   contract or the read throws."
  [source container opts]
  (let [cc (container-contract-for source)]
    (if (and (map? cc) (fn? (:sample-rows cc)))
      (normalize-sample-result (try ((:sample-rows cc) container opts)
                                    (catch Throwable _ nil)))
      [])))

;; ---------------------------------------------------------------------------
;; TRACER 1 — classify EVERY container structurally (MT-1), via the injected sampler
;; ---------------------------------------------------------------------------

(defn classify-source-containers
  "Structurally classify every container in `source` (MT-1), via the INJECTED
   sampler. `list-fn`/`sample-fn` default to the uniform container contract; tests
   inject fakes. For each container: OVER-sample up to `sample-limit` rows, build the
   header from the row keys `(vec (distinct (mapcat keys rows)))`, pass
   `{:header :sample :row-count (count rows)}` to `classify-container-shape`, and
   carry its `:shape`/`:keep?`/`:roles` forward alongside the ORIGINAL container map.

   Returns `[{:name :container :shape :keep? :roles :header :row-count} …]` (one per
   container, in list order). A container whose sample is empty/unreadable is tagged
   `:unreadable` + `:keep? false` (structural noise, dropped) — surfaced honestly,
   never silently swallowed (#5). Pure + total given pure injected fns."
  [source {:keys [list-fn sample-fn sample-limit]}]
  (let [list-fn (or list-fn default-list-fn)
        sample-fn (or sample-fn default-sample-fn)
        limit (or sample-limit default-sample-limit)
        containers (vec (list-fn source))]
    (mapv
     (fn [container]
       (let [rows (try (vec (sample-fn source container {:limit limit}))
                       (catch Throwable _ nil))]
         (if (seq rows)
           (let [header (vec (distinct (mapcat keys rows)))
                 row-count (count rows)
                 verdict (cs/classify-container-shape
                          {:header header :sample rows :row-count row-count})]
             {:name (:name container)
              :container container
              :shape (:shape verdict)
              :keep? (:keep? verdict)
              :roles (:roles verdict)
              :header header
              :row-count row-count})
           {:name (:name container)
            :container container
            :shape :unreadable
            :keep? false
            :roles nil
            :header []
            :row-count 0})))
     containers)))

;; ---------------------------------------------------------------------------
;; TRACER 2/3 — select: structural PRE-FILTER → relevance RANK (reconciled) → BOUND
;; ---------------------------------------------------------------------------

(defn- selected-entry
  "The threaded `:selected-containers` entry for a survivor candidate: the ORIGINAL
   container map (medium-specific `:name`/`:path`/`:sheet` addressing) MERGED with
   MT-1's `:shape` + `:roles` tags (carried forward for MT-3)."
  [c]
  (merge (:container c) {:name (:name c) :shape (:shape c) :roles (:roles c)}))

(defn select-containers
  "Turn classified candidates into a SELECTED, ranked, bounded container list.

   1. DETERMINISTIC PRE-FILTER — drop every `:keep? false` candidate (structural
      noise: bridge/reference/unreadable), the drop `:reason` = its `:shape`.
   2. RELEVANCE RANK — `rank-fn` (injected; the real one is the delegated `:llm`
      rank sheet) orders the SURVIVORS by relevance to `goal`, returning a vector of
      survivor NAMES. RECONCILE its output against the KNOWN survivor names: keep
      only known names (an LLM-invented name is IGNORED — never an invented
      identity), preserve first-occurrence order, then APPEND any survivor the
      ranker omitted at the END (an omitted survivor is NEVER silently dropped —
      honest, #5). `rank-fn` nil / a failed rank → list order (the honest degrade).
   3. BOUND — `take cap` (nil cap → all survivors).

   Returns `{:selected [<container+shape+roles> …] :dropped [{:name :shape :reason}]
             :report {:containers-total :survivors :selected :dropped}}`. The report
   is the total-vs-selected honesty signal (no false-green, #4). Pure + total."
  [candidates {:keys [goal cap rank-fn]}]
  (let [candidates (vec candidates)
        survivors (filterv :keep? candidates)
        dropped (mapv (fn [c] {:name (:name c) :shape (:shape c) :reason (:shape c)})
                      (remove :keep? candidates))
        survivor-names (mapv :name survivors)
        survivor-name-set (set survivor-names)
        survivor-by-name (into {} (map (juxt :name identity)) survivors)
        ;; the ranker's ordered names, RECONCILED — known-only, de-duped, order-stable.
        ranked (when rank-fn
                 (try (rank-fn goal survivors) (catch Throwable _ nil)))
        known-order (->> (or ranked [])
                         (filter survivor-name-set)
                         (distinct)
                         (vec))
        ;; survivors the ranker OMITTED (or dropped by a nil/failed rank) → appended
        ;; at the END in their original list order (never lost).
        omitted (->> survivor-names
                     (remove (set known-order))
                     (vec))
        final-order (into known-order omitted)
        ordered-survivors (mapv survivor-by-name final-order)
        bounded (if cap (vec (take cap ordered-survivors)) ordered-survivors)
        selected (mapv selected-entry bounded)]
    {:selected selected
     :dropped dropped
     :report {:containers-total (count candidates)
              :survivors (count survivors)
              :selected (count selected)
              :dropped dropped}}))
