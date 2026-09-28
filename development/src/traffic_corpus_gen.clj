(ns traffic-corpus-gen
  "RS-7 Part A — the realistic-traffic corpus generator.

   Produces `development/bench/ood-corpus-traffic/`: `tasks/NNN-<group>-vVV.txt`
   (one file per generated variant, a `;`-prefixed header for humans only,
   loadable by `ood/load-corpus` exactly like every other OOD corpus dir) and
   `manifest.edn` (slug -> ground truth + generator provenance + review
   status + flags, plus a top-level `:corpus-sha256` written by `freeze!`).

   Ground truth is the BRIEF, never the model's own label: each brief names
   its `:subject-matter`, `:material`, `:output-kind` and `:instance-space`
   (the axis THIS variant should vary along) and an optional `:must-not-be`
   guard; those are the only per-brief fields ever placed in a generation
   prompt. The brief's `:group`, `:brief-id`, `:in-domain?` and
   `:expected-seed-id` are NEVER sent to the model — they are the withheld
   ground truth the harness checks classification against.

   Two `llm/predict :openrouter` calls per (brief, variant): `generate!`
   drafts, then polishes, each variant's instruction text. Both calls use the
   `gepa/core/todo_processors.clj` `make-llm-fn` module shape (one :prompt
   input, one :string output, `:with-metadata? true` so :usage is captured)
   — see `development/src/diagnose_llm_hang.clj` for the minimal single-call
   shape this mirrors.

   Review flags (`flag-near-dups!`) are HITL aids only, never a metric and
   never ground truth — `REVIEW.md` / `manifest.edn`'s `:review-status` is
   what a human decides; `freeze!` computes `corpus-sha256` over the
   ACCEPTED set only."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]))

(def corpus-dir "development/bench/ood-corpus-traffic")

;; =============================================================================
;; Small pure helpers
;; =============================================================================

(defn sha256
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

;; =============================================================================
;; Slug scheme — NNN-<group>-vVV, a seeded round-robin interleave
;; =============================================================================

(def slug-re #"^(\d{3})-([a-z][a-z0-9]*(?:-[a-z0-9]+)*)-v(\d{2})$")

(defn slug
  [idx group variant]
  (format "%03d-%s-v%02d" (int idx) (name group) (int variant)))

(defn parse-slug
  "Parse a `NNN-<group>-vVV` slug back into `{:index :group :variant}`, or
   nil if `s` doesn't match the scheme. Pure — no LLM, no IO. This parses a
   slug format WE ourselves generate (a structural id, not model-authored
   prose), not a phrase-matching decision over content."
  [s]
  (when-let [[_ idx group variant] (re-matches slug-re s)]
    {:index (Long/parseLong idx)
     :group group
     :variant (Long/parseLong variant)}))

(defn interleave-order
  "Seeded round-robin interleave: for variant 1..`n-variants`, walk every
   (group, brief) pair in `briefs` in a SHUFFLED (but seed-stable) group
   order, emitting one slot per pair at that variant number, before moving to
   variant 2. Pure and deterministic for a fixed `seed` (`java.util.Random` +
   `Collections/shuffle` are specified to be reproducible for the same
   seed). Returns an ordered vector of `{:index :group :brief-id :variant}`,
   `:index` assigned sequentially 1.. across the WHOLE interleaved order."
  [briefs n-variants seed]
  (let [groups (->> briefs (map :group) distinct vec)
        shuffled (let [arr (java.util.ArrayList. ^java.util.Collection groups)]
                   (java.util.Collections/shuffle arr (java.util.Random. (long seed)))
                   (vec arr))
        briefs-by-group (group-by :group briefs)
        slots (for [variant (range 1 (inc n-variants))
                    group shuffled
                    brief (get briefs-by-group group)]
                {:group group :brief-id (:brief-id brief) :variant variant})]
    (vec (map-indexed (fn [i s] (assoc s :index (inc i))) slots))))

;; =============================================================================
;; Style cards — six fixed styles; PLAN.md Part A names all six under "5
;; variants": every named style is implemented, `:n-variants` picks how many
;; of them (in this fixed order) `generate!` actually produces.
;; =============================================================================

(def style-cards
  [{:style :ticket
    :desc "a terse support-ticket style: imperative, no greeting, minimal context"
    :words [60 150]}
   {:style :long-email
    :desc "a long, somewhat rambling email with a greeting and a sign-off, including some irrelevant personal context"
    :words [250 600]}
   {:style :bullet-spec
    :desc "a bullet-point spec that gives an EXPLICIT schema for the desired output"
    :words [120 300]}
   {:style :conversational
    :desc "a casual, conversational chat-message ask"
    :words [80 200]}
   {:style :numbered
    :desc "a numbered, step-by-step list of requirements"
    :words [100 250]}
   {:style :inline-data-excerpt
    :desc "prose that embeds an inline data excerpt (a small table or key:value block) the task should act on"
    :words [150 400]}])

;; =============================================================================
;; Generation — two calls per variant: draft, then polish
;; =============================================================================

(defn- brief-by-id [briefs brief-id]
  (or (some #(when (= brief-id (:brief-id %)) %) briefs)
      (throw (ex-info "unknown brief-id" {:brief-id brief-id}))))

(defn- variant-prompt
  [brief style-card prior-texts]
  (str "Write ONE realistic task instruction a busy person would actually send to an AI assistant.\n\n"
       "SUBJECT MATTER (what the task is fundamentally about): " (:subject-matter brief) "\n"
       "MATERIAL (what the person provides for the assistant to read or use): " (:material brief) "\n"
       "DESIRED OUTPUT KIND: " (:output-kind brief) "\n"
       "INSTANCE VARIATION (this specific request should differ from other requests in this "
       "same space by): " (:instance-space brief) "\n"
       (when-let [mnb (:must-not-be brief)]
         (str "MUST NOT be confused with, or read as: " mnb "\n"))
       "\nSTYLE for this instance: " (:desc style-card) ". Target length "
       (first (:words style-card)) "-" (second (:words style-card)) " words.\n"
       (when (seq prior-texts)
         (str "\nDo NOT reuse the wording, scenario, or specific numbers/names of these PRIOR "
              "instances of this SAME space — write something genuinely different, not a "
              "synonym-swap:\n" (str/join "\n---\n" prior-texts) "\n"))
       "\nNever mention that this is a generated example, a test, a corpus, or a category/domain "
       "name. Write it exactly as the real person would — in their own voice, with their own "
       "concerns — never as a textbook description of the domain."))

(defn- polish-prompt
  [draft-text style-card]
  (str "Revise the task instruction below so it reads as a genuine, naturally-written request in "
       "this style: " (:desc style-card) ". Fix anything that reads like a generated example, "
       "tighten or expand toward " (first (:words style-card)) "-" (second (:words style-card))
       " words, and keep every concrete detail (numbers, names, constraints) unless it is "
       "obviously filler.\n\nDRAFT:\n" draft-text))

(def ^:private one-string-json-module
  {:inputs [{:name :prompt :spec :string :description "the drafting or revision brief"}]
   :outputs [{:name :variants :spec :string
              :description "a JSON array of exactly one string: the instruction text"}]
   :instructions (str "Respond with ONLY a JSON array containing exactly one string: the "
                       "instruction text itself. No preamble, no markdown fence, no explanation, "
                       "no extra keys.")})

(defn- extract-json-array-string
  "Pull the first element out of a JSON-array-of-one-string payload, tolerant
   of stray text around the array (mirrors rs_p1_coverage_probe's
   `probe-parse` bracket-scan)."
  [raw]
  (when (string? raw)
    (let [start (.indexOf raw "[")
          end (.lastIndexOf raw "]")]
      (when (and (>= start 0) (> end start))
        (let [payload (subs raw start (inc end))
              parsed (try (json/read-str payload) (catch Throwable _ nil))]
          (when (and (sequential? parsed) (string? (first parsed)))
            (first parsed)))))))

(def predict-timeout-ms
  "Per-call provider timeout. The router's default (30 s) is too short for a
   600-word polish; a timeout is an exception the CALLER owns (llm/predict
   propagates it), so `predict-one-string!` retries it below."
  120000)

(def predict-attempts
  "Bounded attempts per call (first try + retries). Exhaustion rethrows the
   last exception — the run then stops loudly and `generate!` resumes from
   disk on the next launch."
  3)

(defn- with-bounded-retries
  "Call `f` up to `attempts` times, sleeping `delays-ms` between failures;
   rethrows the last Throwable. Pure control flow — no provider knowledge."
  [attempts delays-ms f]
  (loop [n 1]
    (let [r (try {:ok (f)} (catch Throwable t {:error t}))]
      (if (contains? r :ok)
        (:ok r)
        (if (>= n attempts)
          (throw (:error r))
          (do (Thread/sleep (long (nth delays-ms (dec n) (last delays-ms))))
              (recur (inc n))))))))

(defn- predict-one-string!
  [ctx prompt model]
  (let [provider (:llm-provider ctx :openrouter)
        result (with-bounded-retries predict-attempts [5000 15000]
                 #(llm/predict provider one-string-json-module {:prompt prompt}
                    {:model model
                     :use-function-calling? true
                     :validate? false
                     :with-metadata? true
                     :timeout-ms predict-timeout-ms}))
        text (or (extract-json-array-string (get-in result [:outputs :variants]))
                 (str/trim (str (get-in result [:outputs :variants]))))]
    {:text text
     :usage (:usage result)
     :model (or (:model result) model)
     :prompt-sha256 (sha256 prompt)}))

(defn generate-variant!
  "Two llm/predict calls (draft, then polish) for ONE (brief, style-card,
   prior-texts) slot. Returns `{:text :draft :polish}` — `:text` is the
   polished instruction; `:draft`/`:polish` each carry `:usage` and
   `:prompt-sha256` for the manifest's generator provenance."
  [ctx brief style-card prior-texts model]
  (let [draft (predict-one-string! ctx (variant-prompt brief style-card prior-texts) model)
        polish (predict-one-string! ctx (polish-prompt (:text draft) style-card) model)]
    {:text (:text polish) :draft draft :polish polish}))

(defn generate!
  "Generate the full traffic corpus from `briefs-path` (an EDN file of brief
   maps, see the ns docstring for the field shape) into `out-dir`. `opts`:
     :ctx          - REQUIRED, a started runner context (`runner/start!` +
                     `runner/register-models!` already done by the caller)
     :model        - OpenRouter model id (default the runner's own model)
     :n-variants   - how many of `style-cards`, in order, to produce per
                     brief (default: all six)
     :seed         - interleave seed (default 42)

   Writes `tasks/<slug>.txt` (one file per variant, `;`-header + body) and
   `manifest.edn` (entries WITHOUT the generated text — `freeze!` and
   `flag-near-dups!` re-read the `.txt` files by slug, exactly as
   `ood/load-corpus` would, so the manifest is never a second source of
   truth for the instruction body).

   Resumable: a slot whose `tasks/<slug>.txt` already exists is NOT
   regenerated — its text is re-read from disk (header stripped, as
   `ood/load-corpus` does) and still feeds later variants' do-not-reuse
   context; its provenance comes from `entries/<slug>.edn` when that sidecar
   exists, else `{:provenance :resumed-from-disk}`. Each generated slot writes
   its sidecar immediately, so a crash mid-run loses at most one slot's
   provenance. `:generate-fn` (default `generate-variant!`) is the seam tests
   fake."
  [briefs-path out-dir {:keys [ctx model n-variants seed generate-fn]
                         :or {n-variants (count style-cards) seed 42
                              generate-fn generate-variant!}}]
  (when-not ctx (throw (ex-info "generate! requires :ctx (a started runner context)" {})))
  (let [briefs (edn/read-string (slurp briefs-path))
        tasks-dir (io/file out-dir "tasks")
        entries-dir (io/file out-dir "entries")
        _ (.mkdirs tasks-dir)
        _ (.mkdirs entries-dir)
        ;; trimmed: generated text is trimmed before it is written, and the loader
        ;; keeps the blank line after the header, so parity for prior-texts
        on-disk (into {} (map (juxt :slug (comp str/trim :instruction))) (ood/load-corpus (str tasks-dir)))
        order (interleave-order briefs n-variants seed)
        entries (atom [])]
    (doseq [{:keys [index group brief-id variant]} order
            :let [brief (brief-by-id briefs brief-id)
                  style-card (nth style-cards (mod (dec variant) (count style-cards)))
                  prior-texts (->> @entries
                                   (filter #(= brief-id (:brief-id %)))
                                   (map :text))
                  slug' (slug index group variant)
                  sidecar (io/file entries-dir (str slug' ".edn"))
                  ground {:slug slug' :brief-id brief-id :group group :variant variant
                          :in-domain? (:in-domain? brief) :confounder-of (:confounder-of brief)
                          :expected-seed-id (:expected-seed-id brief)
                          :expected-output-kind (:output-kind brief)
                          :fixture? (boolean (:fixture? brief))
                          :review-status :pending
                          :flags []}
                  entry (if-let [existing (get on-disk slug')]
                          (assoc (if (.exists sidecar)
                                   (edn/read-string (slurp sidecar))
                                   (assoc ground :generator {:provenance :resumed-from-disk}))
                                 :text existing)
                          (let [{:keys [text draft polish]} (generate-fn ctx brief style-card prior-texts model)
                                e (assoc ground
                                         :generator {:draft-usage (:usage draft) :draft-prompt-sha256 (:prompt-sha256 draft)
                                                     :polish-usage (:usage polish) :polish-prompt-sha256 (:prompt-sha256 polish)
                                                     :model (:model polish)})]
                            (spit (io/file tasks-dir (str slug' ".txt"))
                                  (str "; brief-id: " brief-id "\n; group: " group "\n; variant: " variant "\n\n" text))
                            (spit sidecar (with-out-str (pp/pprint e)))
                            (assoc e :text text)))]]
      ;; :text is kept transiently for prior-text bookkeeping ONLY
      (swap! entries conj entry))
    (let [manifest {:entries (mapv #(dissoc % :text) @entries)
                     :seed seed
                     :n-variants n-variants
                     :generated-at (str (java.time.Instant/now))}]
      (spit (io/file out-dir "manifest.edn") (with-out-str (pp/pprint manifest)))
      manifest)))

;; =============================================================================
;; Review flags — HITL aids only, NEVER a metric, NEVER ground truth
;; =============================================================================

(def near-dup-neighbour-threshold
  "Normalized (0-1) ColBERT-rerank score above which another group's variant
   is flagged as a suspiciously close neighbour. A review-aid constant, not a
   production threshold — see the ns docstring."
  0.75)

(defn- tokens
  [s]
  (into #{} (remove str/blank? (str/split (str/lower-case (str s)) #"[^a-z0-9]+"))))

(defn jaccard
  [a b]
  (let [ta (tokens a) tb (tokens b)]
    (if (or (empty? ta) (empty? tb))
      0.0
      (double (/ (count (set/intersection ta tb)) (count (set/union ta tb)))))))

(defn- default-neighbour-fn
  "The REAL capability: ColBERT `rerank` over the candidate's text against
   every other group's texts, NO INDEX required (see colbert.interface/rerank
   docstring). Faked in tests — see the injected-capability seam pattern."
  [ctx]
  (fn [text other-texts]
    (when (seq other-texts)
      (let [results (colbert/rerank ctx {:query text :documents other-texts :k 1})]
        (when (seq results)
          (let [top (first (colbert/normalize-results-to-ceiling results))]
            {:from-other-group? (> (:score top) near-dup-neighbour-threshold)
             :score (:score top)
             :neighbour-text (:content top)}))))))

;; NOTE: ood/load-corpus reads a whole directory; re-reading the whole tasks
;; dir once and indexing by slug is far cheaper than one dir-scan per entry.
(defn- texts-by-slug
  [corpus-dir]
  (into {} (map (juxt :slug :instruction)) (ood/load-corpus (str corpus-dir "/tasks"))))

(defn flag-near-dups!
  "Return `manifest` with each ACCEPTED (non-`:rejected`) entry's `:flags`
   populated. Flags are review aids only — `analyse` and `compare-runs` in
   `rs7_traffic_sweep.clj` NEVER read `:flags`; ground truth is always the
   manifest's `:group`. `opts`:
     :neighbour-fn - capability seam, defaults to `default-neighbour-fn` over
                     a live colbert; tests fake this."
  [corpus-dir manifest & [{:keys [neighbour-fn]}]]
  (let [by-slug (texts-by-slug corpus-dir)
        entries (:entries manifest)
        accepted (filterv #(not= :rejected (:review-status %)) entries)
        by-group (group-by :group accepted)
        text-of #(get by-slug (:slug %))
        flag (fn [e k] (update e :flags (fnil conj []) k))]
    (update manifest :entries
      (fn [es]
        (mapv
          (fn [e]
            (if (not= :rejected (:review-status e))
              (let [text (text-of e)
                    same-group-earlier (->> (get by-group (:group e))
                                            (filter #(< (:variant %) (:variant e))))
                    repeat? (boolean (some #(> (jaccard text (text-of %)) 0.6) same-group-earlier))
                    other-group-entries (mapcat val (dissoc by-group (:group e)))
                    ambiguous? (boolean (some #(> (jaccard text (text-of %)) 0.35) other-group-entries))
                    neighbour (when (and neighbour-fn text)
                                (neighbour-fn text (keep text-of other-group-entries)))
                    neighbour-flag? (boolean (:from-other-group? neighbour))
                    kind-tokens (tokens (:expected-output-kind e))
                    output-kind-missing? (boolean (and (:in-domain? e)
                                                       (seq kind-tokens)
                                                       text
                                                       (empty? (set/intersection kind-tokens (tokens text)))))]
                (cond-> (assoc e :flags [])
                  repeat? (flag :repeat)
                  ambiguous? (flag :ambiguous-truth)
                  neighbour-flag? (flag :neighbour-other-group)
                  output-kind-missing? (flag :output-kind-missing)))
              e))
          es)))))

(defn flag-near-dups-live!
  "Convenience wrapper: `flag-near-dups!` with the REAL colbert neighbour
   capability bound to `ctx`."
  [ctx corpus-dir manifest]
  (flag-near-dups! corpus-dir manifest {:neighbour-fn (default-neighbour-fn ctx)}))

;; =============================================================================
;; freeze! — corpus-sha256 over the sorted accepted (slug, instruction-sha256)
;; pairs
;; =============================================================================

(defn freeze!
  "Read `manifest.edn` from `corpus-dir`, compute `corpus-sha256` over the
   SORTED (by slug) `(slug, sha256(instruction-text))` pairs of every
   non-`:rejected` entry, write it back into `manifest.edn` (`:corpus-sha256`
   + `:frozen-at`), and return the frozen manifest. Deterministic: depends
   only on which slugs are accepted and their instruction TEXT, never on
   on-disk entry order, map key order, or any other manifest field —
   changing a flag or a review note never moves the sha."
  [corpus-dir]
  (let [manifest-path (io/file corpus-dir "manifest.edn")
        manifest (edn/read-string (slurp manifest-path))
        by-slug (texts-by-slug corpus-dir)
        accepted (->> (:entries manifest) (remove #(= :rejected (:review-status %))))
        pairs (->> accepted
                   (keep (fn [e] (when-let [t (get by-slug (:slug e))] [(:slug e) (sha256 t)])))
                   (sort-by first)
                   vec)
        corpus-sha (sha256 (pr-str pairs))
        frozen (assoc manifest :corpus-sha256 corpus-sha :frozen-at (str (java.time.Instant/now)))]
    (spit manifest-path (with-out-str (pp/pprint frozen)))
    frozen))

(comment
  ;; Launcher (from the worktree root, OPENROUTER_API_KEY in the environment):
  ;; clojure -J-Djava.awt.headless=true -J-Xmx1600m -M:dev:test -e "(require 'runner) (runner/start!) (Thread/sleep 45000) (require 'traffic-corpus-gen) (traffic-corpus-gen/generate! \"development/bench/ood-corpus-traffic/briefs.edn\" traffic-corpus-gen/corpus-dir {:ctx (deref @(requiring-resolve 'runner/system-state))}) (runner/stop!) (shutdown-agents) (System/exit 0)"
  )
