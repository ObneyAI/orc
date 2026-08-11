(ns ai.obney.orc.ontology.cc22b-bounded-inference-query-test
  "CC-22b — contract ParentInferenceQuery (spec'd at b18112eb): a bounded,
   de-duplicated parent-inference query rendering, bound at the SIGNATURE
   seam, not the fold.

   Fixtures are BANKED REAL DATA, loaded never transcribed:

     * `cc22b_claimset_25.edn` — the real 25-claim target
       (:node-instance dfef4afc…/705d4cbd…) copied programmatically from
       CC-22a's inspect-accepted `replay-claimsets.edn`. Its PRE-CC-22b
       render measures 476 word-piece tokens against the 461 budget; its
       DE-DUPLICATED one-pass render measures 317 and FITS — de-duplication
       alone reclaims this target, so the bound must ABSTAIN here (report
       zero exclusions), which is itself asserted.
     * `cc22b_claimset_fire.edn` — the real FIRE case: the tree-fingerprint
       760be698… target (the measurement's worst assembled render, 764
       duplicated tokens), whose de-duplicated render still measures 524 —
       the bound MUST fire, exclude a ranked tail, and report it.
     * `cc22b_legacy_baseline.edn` — all 86 production targets' legacy
       bodies (folded from the production dump via the real descriptions
       read model) plus the OLD builder's byte-exact output, captured
       BEFORE any CC-22b code change.

   Budget and token counts come from the CONFIGURED encoder value and the
   REAL DJL tokenizer (never a hardcoded 461, never chars/4).

   invariant.BoundedInferenceQuery — the rendered query fits
     `maximum_query_tokens - query_specials`; the rendered subset is a
     GLOBAL support-rank prefix (tie-break :claim-id); exclusion is
     OBSERVABLE, never silent.
   invariant.NoDuplicateClaimContent — no claim's content appears twice in
     one rendered query (the measured 1.64x inflation was summary+sections
     double-rendering).
   invariant.RenderingNeverWeakensEnforcement — PINNED here: suppression
     strings derive from `get-enforcing-claims`, never from the rendered
     body. That test was EXPECTED GREEN BEFORE ANY CC-22b CHANGE — it pins
     behavior that was already true in code."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; The real tokenizer + configured budget (the way CC-22a's harness read them)
;; =============================================================================

(def ^:private enc (delay (encoder/get-encoder (model-store/resolve-model-dir))))

(defn- tok [s] (count (encoder/encode-ids @enc (str s))))

(defn- budget []
  (- (encoder/configured-maximum-query-tokens) encoder/query-specials))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private claimset-fixture
  (delay (edn/read-string (slurp (io/resource "cc22b_claimset_25.edn")))))

(def ^:private fire-fixture
  (delay (edn/read-string (slurp (io/resource "cc22b_claimset_fire.edn")))))

(def ^:private legacy-baseline
  (delay (edn/read-string (slurp (io/resource "cc22b_legacy_baseline.edn")))))

(def ^:private old-builder
  "The pre-CC-22b builder, via its own var — RED 1/2's 'today' render path
   until the bounded seam exists."
  @#'ai.obney.orc.ontology.core.consolidator/build-parent-inference-signature)

(defn- seam
  "The CC-22b production seam, when it exists."
  []
  (ns-resolve 'ai.obney.orc.ontology.core.consolidator 'bounded-inference-query))

(defn- production-render
  "The production claim-backed parent-inference render. Once CC-22b lands
   this is `bounded-inference-query`; until then it falls back to TODAY's
   builder over the assembled body — which renders everything and reports
   nothing, so the RED run shows the REAL production failure (476 tokens,
   no exclusion report), not a missing-var error."
  [body claims]
  (if-let [f (seam)]
    (f body claims)
    {:query (old-builder body)
     :rendered-claim-ids ::unreported
     :excluded-claim-ids ::unreported}))

(defn- ranked
  "The spec's global support rank: strongest first, tie-break :claim-id —
   computed here INDEPENDENTLY of the implementation."
  [claims]
  (sort-by (juxt (comp - :support) :claim-id) claims))

(defn- occurrences [haystack needle]
  (loop [from 0 n 0]
    (if-let [i (str/index-of haystack needle from)]
      (recur (long (+ i (count needle))) (inc n))
      n)))

;; =============================================================================
;; RED 1 — invariant.BoundedInferenceQuery
;; =============================================================================

(defn- assert-bounded-prefix-render
  "The invariant's three legs on one claim-backed fixture: the render fits
   the configured budget; the kept subset is the GLOBAL support-rank prefix
   (tie-break :claim-id); the rendered/excluded partition is complete and
   OBSERVABLE — reported, never silently capped."
  [body claims b]
  (let [{:keys [query rendered-claim-ids excluded-claim-ids]} (production-render body claims)
        ranked-ids (mapv :claim-id (ranked claims))]
    (is (<= (tok query) b)
        (format "rendered inference query is %d word-piece tokens against the configured budget %d"
                (tok query) b))
    (is (sequential? excluded-claim-ids)
        "the unrendered claim ids must be REPORTED (structured return), not silently capped")
    (is (sequential? rendered-claim-ids)
        "the rendered claim ids must be reported alongside the exclusions")
    (when (and (sequential? rendered-claim-ids) (sequential? excluded-claim-ids))
      (is (seq rendered-claim-ids)
          "the bound keeps the best-supported head — it never renders nothing for a real claim set")
      (is (= (vec (take (count rendered-claim-ids) ranked-ids))
             (vec rendered-claim-ids))
          "rendered ids = the leading prefix of the global support rank")
      (is (= (vec (drop (count rendered-claim-ids) ranked-ids))
             (vec excluded-claim-ids))
          "excluded ids = exactly the ranked tail — the partition is complete, nothing vanishes"))
    {:query query
     :rendered-claim-ids rendered-claim-ids
     :excluded-claim-ids excluded-claim-ids}))

(deftest bounded-inference-query-fits-budget-with-observable-exclusions
  (let [b (budget)]
    (testing "the 25-claim target: de-duplication alone reclaims it (476 -> 317), so the bound ABSTAINS — and says so"
      (let [{:keys [claims body]} @claimset-fixture]
        (is (= 25 (count claims)) "the banked fixture is the real 25-claim target")
        (let [{:keys [rendered-claim-ids excluded-claim-ids]}
              (assert-bounded-prefix-render body claims b)]
          (when (sequential? excluded-claim-ids)
            (is (empty? excluded-claim-ids)
                "this target's de-duplicated render fits, so nothing may be excluded —
                 an abstaining bound renders the WHOLE claim set")
            (is (= (count claims) (count rendered-claim-ids))
                "all 25 claims render")))))
    (testing "the FIRE target (tree-fingerprint 760be698…, de-duplicated render 524 > budget): the bound fires, and the exclusion is listed"
      (let [{:keys [claims body]} @fire-fixture]
        (is (= 15 (count claims)) "the banked fixture is the real 15-claim fire target")
        (let [{:keys [excluded-claim-ids rendered-claim-ids]}
              (assert-bounded-prefix-render body claims b)]
          (when (sequential? excluded-claim-ids)
            (is (seq excluded-claim-ids)
                "this target's full de-duplicated render exceeds the budget, so a
                 fitting render MUST exclude a ranked tail — and report it")
            (testing "the kept prefix is the LARGEST that fits (first-overflow stop)"
              (let [render-var (ns-resolve 'ai.obney.orc.ontology.core.consolidator
                                           'claim-query-render)]
                (is (some? render-var) "the one-pass claim render exists")
                (when render-var
                  (let [ranked-claims (vec (ranked claims))
                        one-more (subvec ranked-claims 0 (inc (count rendered-claim-ids)))]
                    (is (> (tok (@render-var one-more)) b)
                        "rendering even ONE more ranked claim would overflow the budget —
                         the prefix is maximal, not merely valid")))))))))))

;; =============================================================================
;; RED 2 — invariant.NoDuplicateClaimContent
;; =============================================================================

(deftest no-claim-content-renders-twice
  (doseq [[label fixture] [["25-claim target" @claimset-fixture]
                           ["fire target" @fire-fixture]]]
    (testing label
      (let [{:keys [claims body]} fixture
            {:keys [query rendered-claim-ids]} (production-render body claims)
            by-id (into {} (map (juxt :claim-id identity)) claims)
            kept-ids (if (sequential? rendered-claim-ids)
                       (set rendered-claim-ids)
                       ;; pre-seam fallback renders everything and reports nothing
                       (set (map :claim-id claims)))
            kept-contents (set (map (comp :content by-id) kept-ids))]
        (is (pos? (count (filter #(pos? (occurrences query %)) kept-contents)))
            "non-vacuous: the query actually renders claim content")
        (doseq [{:keys [claim-id content]} claims]
          (if (contains? kept-ids claim-id)
            (is (= 1 (occurrences query content))
                (format "a RENDERED claim's content must appear EXACTLY once (appears %d times): %s"
                        (occurrences query content) (pr-str content)))
            (when-not (contains? kept-contents content)
              (is (zero? (occurrences query content))
                  (format "an EXCLUDED claim's content must not appear at all (appears %d times): %s"
                          (occurrences query content) (pr-str content))))))))))

;; =============================================================================
;; RED 3 — legacy byte-identity (the regression guard for all 86 production
;; targets; red until the seam exists, then byte-checked against the banked
;; PRE-CC-22b outputs)
;; =============================================================================

(deftest legacy-bodies-render-byte-identically-through-the-new-seam
  (let [f (seam)]
    (is (some? f)
        "the CC-22b seam must exist — until it does, the legacy path through it is unproven")
    (when f
      (let [{:keys [rows]} @legacy-baseline]
        (is (= 86 (count rows)) "all 86 production targets are guarded")
        (doseq [{:keys [granularity target-id body expected-signature]} rows]
          (let [{:keys [query excluded-claim-ids]} (f body [])]
            (is (= expected-signature query)
                (format "legacy body must render BYTE-IDENTICALLY to the pre-CC-22b builder: %s %s"
                        granularity (pr-str target-id)))
            (is (empty? excluded-claim-ids)
                "a legacy body has no claims, so nothing can be excluded")))))))

;; =============================================================================
;; WIRING — the two production call sites (the consolidator's parent and
;; behavioral hydration inference calls) must hand the classifier the
;; BOUNDED query for a claim-backed target, not the legacy duplicated render.
;; =============================================================================

(deftest call-sites-render-through-the-bounded-seam
  (let [{:keys [claims body target]} @fire-fixture
        [_ target-id] target
        f (seam)]
    (is (some? f) "the CC-22b seam must exist before wiring can be proven")
    (when f
      (let [expected (:query (f body claims))
            b (budget)
            captured (atom {})]
        (with-redefs [ontology/get-claims (fn [_ctx _g _t] claims)
                      ontology/classify-task
                      (fn [_ctx {:keys [task-signature]}]
                        (swap! captured assoc :parent task-signature)
                        {:assigned-tree-id (random-uuid) :confidence 0.9 :was-fresh-mint? false})
                      ontology/classify-behaviors
                      (fn [_ctx {:keys [task-signature]}]
                        (swap! captured assoc :behavioral task-signature)
                        {:behaviors [{:behavior-id (random-uuid) :was-fresh-mint? false}]})]
          (consolidator/maybe-hydrate-parent-tree-id {} :tree-fingerprint target-id nil body)
          (consolidator/maybe-hydrate-behavioral-subtree-ids {} :tree-fingerprint target-id nil body))
        (testing "the parent-inference call site"
          (is (= expected (:parent @captured))
              "classify-task must receive the bounded de-duplicated query for a claim-backed target")
          (is (<= (tok (str (:parent @captured))) b)
              (format "the signature handed to the classifier is %d tokens against budget %d"
                      (tok (str (:parent @captured))) b)))
        (testing "the behavioral-inference call site"
          (is (= expected (:behavioral @captured))
              "classify-behaviors must receive the same bounded query"))))))

;; =============================================================================
;; PIN — invariant.RenderingNeverWeakensEnforcement
;;
;; EXPECTED GREEN BEFORE ANY CC-22b CHANGE: it pins behavior CC-9a already
;; made true (avoid-strings reads ::enforcing-avoid-when stamped from
;; get-enforcing-claims — the CLAIM SET — and positive-strings reads the
;; enriched strengths' :good-when, assembled from the claim set). If this
;; test EVER goes red, the change is wrong, not the test.
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc22b-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                        :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro ^:private with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- ground-episodes! [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid) :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {} :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- delta [op overrides]
  (merge {:operation op :kind :guard :content "a guard"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))

(defn- record! [ctx target deltas]
  (ground-episodes! ctx deltas)
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid) :command/timestamp (time/now)
            :granularity :tree-class :target-identifier target :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- claim-id-for [ctx target content]
  (:claim-id (first (filter #(= content (:content %))
                            (ontology/get-claims ctx :tree-class target)))))

(defn- validated! [ctx target d]
  (record! ctx target [d])
  (let [cid (claim-id-for ctx target (:content d))]
    (dotimes [_ 6]
      (record! ctx target [(delta :support {:target-claim cid :content (:content d)})]))
    cid))

(defn- enriched
  "The candidate map as EL-2 hands it to EL-5 — the real seam between what a
   body carries and what the penalty consumes (cc9a precedent)."
  [ctx target]
  (#'ontology/enrich-candidate-evidence
    ctx {:document-identifier (str target)
         :content "a candidate"
         :document-metadata {:granularity :tree-class :target-id (str target)}}))

(deftest PIN-enforcement-derives-from-the-claim-set-never-the-render
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (validated! ctx target (delta :add {:kind :guard
                                          :content "avoid when the diff is empty and no verification command ran"}))
      (validated! ctx target (delta :add {:kind :strength
                                          :content "applies small mechanical edits reliably"
                                          :context-guard "when the change is a single-file mechanical edit"}))
      ;; an UNPROVEN guard: visible, and it must NOT enforce (cc9a) — included
      ;; so the pin distinguishes 'derives from get-enforcing-claims' from
      ;; 'derives from the body/render'.
      (record! ctx target [(delta :add {:kind :guard
                                        :content "avoid when the file is very large"})])
      (let [c (enriched ctx target)
            enforcing (ontology/get-enforcing-claims ctx :tree-class target)
            expected-avoid (into []
                                 (comp (keep (fn [{:keys [kind content context-guard]}]
                                               (case kind
                                                 :guard    content
                                                 :weakness context-guard
                                                 nil)))
                                       (distinct))
                                 enforcing)]
        (is (seq enforcing) "non-vacuous: the target HAS enforcing claims")
        (is (= expected-avoid (dp/avoid-strings c))
            "retrieval-suppression strings are EXACTLY the get-enforcing-claims
             derivation (guard -> :content, weakness -> :context-guard) — the
             claim set, never the rendered body, so no rendering bound can
             remove an enforcing guard")
        (is (not-any? #{"avoid when the file is very large"} (dp/avoid-strings c))
            "an unproven guard still cannot suppress — the gate is status, not rendering")
        (is (= ["when the change is a single-file mechanical edit"] (dp/positive-strings c))
            "the positive signal is the enriched strengths' :good-when — assembled
             from the claim set, not read from any rendered query")))))
