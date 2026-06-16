(ns ai.obney.orc.orc-service.core.source-tools-text
  "V19 — TEXT-file SOURCE-ACCESS tools for the discovery RLM.

   The text leg of the per-format source-tool family (alongside csv / sql /
   excel). Where those explore a STRUCTURED source, this explores an unstructured
   TEXT source by its bounded UNITS (lines) WITHOUT loading the whole file into
   context — the same bounded-read invariant the other legs hold, applied to a
   plain-text source.

   ## Why a text SPECIALIST (and the V19 ergonomics it shares)

   Before V19, a `:text` source routed to NO source tools — text discovery used
   the blackboard `:content` path, which loads the whole text into the prompt.
   That is fine for a SMALL doc but cannot bound a large one. The text specialist
   gives the same affordances the structured legs have, with the SAME calling
   convention so a builder fluent in one isn't tripped switching mediums:

   - peek-text   — the first units + a quick shape profile (unit count seen,
                   blank-line ratio) so you can decide how to read the source.
   - sample-units — at most N text units (lines), bounded; an :offset window
                    pages deeper. A wrong-shape arg is a TEACHING error, not an
                    arity crash.
   - count-units — total unit (line) count WITHOUT loading the file.
   - stream-all  — iterate the FULL unit set in bounded windows (the substrate a
                   deterministic full-extraction transform runs over).

   A 'unit' is a LINE: the natural bounded, offsettable unit of a text file, and
   the one that lets count + offset + stream be exact and cheap. Nothing here is
   domain-specific — it is medium ergonomics + capability.

   ## Read-side only

   Every tool opens the file read-only and projects bytes into Clojure data; none
   emits a Grain event. Pure filesystem reads, exactly like the other legs.

   ## Wiring

   `text-source-tools` returns the {symbol -> fn} map a SCI sandbox merges into
   its bindings, bound to the source at `:text-path`. It returns nil when no
   path is supplied — the sandbox MUST NOT expose source-less tools. (The unified
   registry's `:text` dispatch is unchanged in V19: it still routes to the
   blackboard path by default; this specialist is available for direct use and as
   the substrate V20's full-extraction can apply over a large text source.)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Bounds (sample-never-dump)
;; =============================================================================

(def max-sample-units
  "Hard cap on sample-units — an absurd N is clamped to this so a model can never
   dump the file through sample-units."
  500)

(def peek-units
  "peek-text returns at most this many leading units."
  20)

;; =============================================================================
;; Bounded readers (stream via line-seq, never realize the whole file)
;; =============================================================================

(defn- read-units-bounded
  "Read AT MOST `n` text units (lines) from `path`, skipping the first `offset`
   units first. Streams via line-seq + drop/take — never realizes the whole file.
   Returns {:units [..] :more? bool} (:more? true when at least one unit existed
   beyond the window) or {:error ..} for a missing/unreadable file."
  ([path n] (read-units-bounded path n 0))
  ([path n offset]
   (try
     (if (and path (.exists (io/file path)))
       (with-open [rdr (io/reader path)]
         (let [skip (max 0 (or offset 0))
               want (max 0 n)
               after (drop skip (line-seq rdr))
               taken (vec (take (inc want) after))
               more? (> (count taken) want)
               units (vec (take want taken))]
           {:units units :more? more?}))
       {:error (str "Text source not found or unreadable: " path)})
     (catch Exception e
       {:error (str "Failed to read text source " path ": " (.getMessage e))}))))

(defn- count-units-streaming
  "COUNT the units (lines) in `path` WITHOUT accumulating them — only a counter
   is kept, so this is safe on a multi-GB file. Returns {:unit-count n} or
   {:error ..}."
  [path]
  (try
    (if (and path (.exists (io/file path)))
      (with-open [rdr (io/reader path)]
        {:unit-count (reduce (fn [acc _] (inc acc)) 0 (line-seq rdr))})
      {:error (str "Text source not found or unreadable: " path) :unit-count 0})
    (catch Exception e
      {:error (str "Failed to read text source " path ": " (.getMessage e))
       :unit-count 0})))

;; =============================================================================
;; Tool: peek-text
;; =============================================================================

(defn- make-peek-text-fn
  [{:keys [text-path]}]
  (fn peek-text []
    (let [{:keys [units more? error]} (read-units-bounded text-path peek-units)]
      (if error
        {:units [] :error error}
        (let [blank (count (filter str/blank? units))]
          {:units units
           :unit-count-sampled (count units)
           :blank-ratio (if (pos? (count units))
                          (/ (double blank) (double (count units)))
                          0.0)
           :more? (boolean more?)})))))

(def peek-text-doc
  "PURPOSE — Inspect a text source's SHAPE without loading it: the first units
   (lines) plus a quick profile (how many were sampled, the blank-line ratio).
   Call this FIRST to decide how to read the source.

   EXAMPLE
     (peek-text)
     ;; => {:units [\"# Title\" \"\" \"First paragraph of body text.\" ...]
     ;;     :unit-count-sampled 20 :blank-ratio 0.15 :more? true}

   RETURNS — {:units :unit-count-sampled :blank-ratio :more?}. A unit is a LINE.
   :more? is true when more units exist beyond the peek window. A missing file
   returns {:units [] :error ...} (data, not a crash).

   SCOPE — reads only the first units, never the whole file.")

;; =============================================================================
;; Tool: sample-units
;; =============================================================================

(defn- make-sample-units-fn
  [{:keys [text-path]}]
  (fn sample-units
    ([] (sample-units 20))
    ([n-or-opts]
     ;; V19 — a wrong-shape arg is a TEACHING error, not a confusing cast.
     (when-not (or (integer? n-or-opts) (map? n-or-opts))
       (throw (ex-info (str "sample-units takes (sample-units) , (sample-units N) , "
                            "or (sample-units {:limit N :offset K}). The arg must be "
                            "an integer unit count OR an options map with :limit / "
                            ":offset — got " (pr-str n-or-opts) ". To page, put "
                            ":offset INSIDE the opts map.")
                       {:bad-arg n-or-opts})))
     (let [opts? (map? n-or-opts)
           n (cond (integer? n-or-opts) n-or-opts
                   opts? (or (:limit n-or-opts) (:n n-or-opts) 20)
                   :else 20)
           offset (if opts? (or (:offset n-or-opts) 0) 0)
           requested (if (and (integer? n) (pos? n)) n 20)
           capped-n (min requested max-sample-units)
           {:keys [units more? error]} (read-units-bounded text-path capped-n offset)]
       (cond
         error {:units [] :returned 0 :offset offset :capped? false :error error}
         :else {:units units
                :returned (count units)
                :requested requested
                :offset offset
                :capped? (boolean more?)})))
    ;; V19 — an extra positional arg (the Excel-style 4th-arg mistake) is a
    ;; teaching error instead of a raw arity exception. The fix: opts is ONE map.
    ([n-or-opts & extra]
     (throw (ex-info (str "sample-units takes at most 1 arg: an integer N or an "
                          "opts map {:limit N :offset K}. You passed an extra "
                          (pr-str (vec extra)) ". Put :limit and :offset INSIDE the "
                          "single opts map, not as separate positional args.")
                     {:n-or-opts n-or-opts :extra (vec extra)})))))

(def sample-units-doc
  "PURPOSE — Read at most N text units (lines) of a source, so you can see real
   content without loading the file. Use after peek-text to inspect a window.

   EXAMPLE
     (sample-units 5)
     ;; => {:units [\"line 1\" \"line 2\" ...] :returned 5 :requested 5 :offset 0
     ;;     :capped? true}

   OFFSET — the arg may instead be a map {:limit <int> :offset <int>} to skip the
   first :offset units and read a window DEEPER in the file:
     (sample-units {:limit 40 :offset 2000})   ; units 2001..2040

   RETURNS — {:units :returned :requested :offset :capped?}. A unit is a LINE. N
   is hard-capped (an absurd N is clamped) so this can never dump the file;
   :capped? is true when more units existed beyond what was returned. A
   wrong-shape arg yields a clear teaching error, not an arity crash.

   SCOPE — reads only the requested window, never the whole file.")

;; =============================================================================
;; Tool: count-units
;; =============================================================================

(defn- make-count-units-fn
  [{:keys [text-path]}]
  (fn count-units []
    (let [{:keys [unit-count error]} (count-units-streaming text-path)]
      (cond-> {:unit-count unit-count :capped? false}
        error (assoc :error error)))))

(def count-units-doc
  "PURPOSE — Report the text source's total UNIT (line) count WITHOUT loading the
   file, so you know how much remains before you page or stream. The sample tools
   cap at 500 units; this tells you the real size.

   EXAMPLE
     (count-units)
     ;; => {:unit-count 18342 :capped? false}

   RETURNS — {:unit-count :capped?}. :unit-count is the exact number of units
   (lines), computed by streaming and counting only — no content is held in
   memory, so it is safe on a multi-GB file. :capped? is always false (exact).")

;; =============================================================================
;; Tool: stream-all
;; =============================================================================

(defn- make-stream-all-fn
  [cfg]
  (let [sample (make-sample-units-fn cfg)]
    (fn stream-all
      ([] (stream-all {}))
      ([opts]
       (let [opts (or opts {})]
         (when-not (map? opts)
           (throw (ex-info (str "stream-all opts must be a map {:window N :offset K}; "
                                "got " (pr-str opts))
                           {:bad-arg opts})))
         (let [req-win (or (:window opts) (:limit opts) max-sample-units)
               win (min (max 1 (long (if (number? req-win) req-win max-sample-units)))
                        max-sample-units)
               start (long (or (:offset opts) 0))
               max-windows (long (or (:max-windows opts) 1000000))]
           (loop [offset start
                  windows []
                  guard 0]
             (if (>= guard max-windows)
               windows
               (let [w (sample {:limit win :offset offset})
                     n (:returned w)]
                 (cond
                   (zero? n) windows
                   (< n win) (conj windows w)
                   :else (recur (+ offset n) (conj windows w) (inc guard))))))))))))

(def stream-all-doc
  "PURPOSE — Iterate a text source's ENTIRE unit (line) set in bounded windows,
   so you can cover every unit without ever loading the whole file. Builds on the
   :offset paging sample-units already has: each window is one bounded
   sample-units result, and consecutive windows step by :offset so together they
   cover every unit exactly once. This is the substrate a deterministic
   full-extraction transform runs over.

   EXAMPLE
     (stream-all)                       ; default window
     (stream-all {:window 200})
     ;; => [{:units [..] :returned 200 :offset 0 :capped? true}
     ;;     {:units [..] :returned 200 :offset 200 :capped? true}
     ;;     ... last window is short when the file is exhausted]
     ;; Concatenate the windows' :units to get every unit, in order:
     (mapcat :units (stream-all {:window 200}))

   WINDOW — :window is units-per-window, CLAMPED to the 500-unit per-call hard
   cap (the cap is never lifted — coverage comes from MANY windows, not one big
   call). Start partway with :offset; bound the loop with :max-windows.

   RETURNS — a VECTOR of window maps, each shaped like sample-units
   ({:units :returned :requested :offset :capped?}). Iteration stops at the first
   short/empty window (the file is exhausted).")

;; =============================================================================
;; Public binding builder + docs map
;; =============================================================================

(def text-source-tool-docs
  "The {symbol -> docstring} map for the text source tools. Exposed so an
   orientation surface or seed-corpus author can pull the same docstrings the
   sandbox sees without building bindings (which needs a :text-path)."
  {'peek-text     peek-text-doc
   'sample-units  sample-units-doc
   'count-units   count-units-doc
   'stream-all    stream-all-doc})

(defn text-source-tools
  "Return the SCI {symbol -> fn} map for the text source-access tools, bound to
   the source at `:text-path`. Same flavor as the csv/sql/excel legs.

   cfg keys:
     :text-path  REQUIRED. Absolute path to the text source to explore.

   Returns nil when no :text-path is supplied — the sandbox MUST NOT silently
   expose source-less tools (mirrors the other legs' no-grant -> nil default).

   Each fn carries its docstring on metadata (via with-meta) so a sandbox's
   (meta sample-units) introspection returns the same doc the docstring-quality
   test reads."
  [{:keys [text-path] :as cfg}]
  (when (and text-path (string? text-path) (seq text-path))
    (let [with-doc (fn [f doc] (with-meta f {:doc doc}))]
      {'peek-text    (with-doc (make-peek-text-fn cfg) peek-text-doc)
       'sample-units (with-doc (make-sample-units-fn cfg) sample-units-doc)
       'count-units  (with-doc (make-count-units-fn cfg) count-units-doc)
       'stream-all   (with-doc (make-stream-all-fn cfg) stream-all-doc)})))
