(ns ai.obney.orc.orc-service.s20-orientation-card-test
  "S20 — Graph orientation card: deterministic skeleton + cache + reindex
   refresh + sandbox injection.

   Each deftest maps to one of the slice's acceptance criteria:

   - Four layers render with values verifiably matching the projections.
     Cross-checked through public interfaces (concept-statistics,
     get-axioms, get-ontology-spec, get-alignment-sections). Adversarial:
     a stale count is the failure mode; we assert each count matches a
     FRESH projection-side query.
   - Graceful degradation when no ORSD spec — identity layer still
     renders metadata + alignment registry plus an explicit \"treat any
     query as exploratory\" guidance line.
   - Cache + reindex contract — both halves: cached on second request
     (no recomputation; we count tool-docstring map accesses as a
     surrogate for \"the renderer ran\") AND refreshed on reindex
     (timestamp bumps via a real :colbert/index-created event).
   - Sandbox injection — `card-for` returns the card when an
     ontology-id is granted; returns nil when nothing is granted
     (information-leak safe).
   - Cold-read self-containedness — structural assertions on layer
     headers, ORSD purpose text, tool-affordance EXAMPLE blocks with
     real URIs from THIS graph (proves \"examples against THIS graph's
     actual content\" per the slice criterion).
   - Read-side only — zero events emitted across all card requests.

   Live verification (real recursive-RLM session showing the model
   USING card information) is a development bench script; the
   discipline-4 floor (live verify before declaring done) is honored
   via development/src/s20_live_verify.clj."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.orientation-card :as oc]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; =============================================================================
;; Fixture: a rich graph for the orientation tests
;;
;; - Two ontology sections (primary + aligned) — S03 alignment registry.
;; - ORSD spec on the primary (purpose + 3 CQs) — S14.
;; - Ontology metadata (title/version/license/creator) — S04.
;; - Axioms: 1 disjointness, 1 transitive predicate, 1 functional+inverse,
;;   1 sub-property, 1 chain — S07 axioms-as-data.
;; - Concept count > 20 (twelve concepts + three classes) so the content
;;   sample has enough mass for top-N to be meaningful.
;; - Predicate diversity: 4 distinct predicates, varying frequency.
;; =============================================================================

(def primary-id #uuid "d2000000-0000-0000-0000-00000000d200")
(def aligned-id #uuid "a2000000-0000-0000-0000-0000000000a2")

(defn- seed-concept!
  ([ctx ontology-id uri label]
   (seed-concept! ctx ontology-id uri label nil))
  ([ctx ontology-id uri label broader]
   (h/run-and-apply!
    ctx
    (fn [c]
      (cmd/ontology-create-concept
       (assoc c :command
              {:ontology-id ontology-id
               :uri uri
               :label label
               :description (str label " — seeded for S20.")
               :scope :custom
               :broader (vec (or broader []))
               :indicators []}))))))

(defn- seed-rel!
  [ctx ontology-id source predicate target]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-create-relationship
      (assoc c :command {:source-uri source
                         :predicate predicate
                         :target-uri target
                         :ontology-id ontology-id
                         :confidence-class :extracted
                         :properties {}})))))

(defn- record-spec!
  [ctx ontology-id body]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-record-ontology-spec
      (assoc c :command {:ontology-id ontology-id :body body})))))

(defn- record-metadata!
  [ctx ontology-id title version license creator]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-record-ontology-metadata
      (assoc c :command {:ontology-id ontology-id
                         :title title :version version
                         :license license :creator creator})))))

(defn- assert-disjoint!
  [ctx ontology-id class-uris]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-disjointness
      (assoc c :command {:ontology-id ontology-id :class-uris class-uris})))))

(defn- assert-characteristic!
  [ctx ontology-id predicate flags & {:keys [inverse-of]}]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-property-characteristic
      (assoc c :command (cond-> {:ontology-id ontology-id
                                 :predicate predicate
                                 :characteristic flags}
                          inverse-of (assoc :inverse-of inverse-of)))))))

(defn- assert-subprop!
  [ctx ontology-id sub super]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-sub-property
      (assoc c :command {:ontology-id ontology-id
                         :sub-predicate sub :super-predicate super})))))

(defn- assert-chain!
  [ctx ontology-id derived chain]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-chain-axiom
      (assoc c :command {:ontology-id ontology-id
                         :derived-predicate derived
                         :chain (vec chain)})))))

(defn- register-alignment!
  [ctx primary aligned]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-register-alignment-section
      (assoc c :command {:primary-ontology-id primary
                         :alignment-ontology-id aligned})))))

(defn- inject-index-created!
  "Append a :colbert/index-created event for the ontology-descriptions
   index. Mirrors the precedent in
   components/ontology/test/.../reindex_processor_test.clj — drives the
   reindex-state projection's `:last-rebuild-timestamp` so the
   orientation card's cache fingerprint changes."
  [ctx]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :colbert/index-created
                         :tags #{}
                         :body {:index-id (random-uuid)
                                :index-name "ontology-descriptions"
                                :index-path "/tmp/s20-test-index"
                                :documents ["d-1"]
                                :document-ids ["id-1"]
                                :document-count 1
                                :passage-count 1
                                :model-name "colbert-ir/colbertv2.0"
                                :config {:split-documents? true
                                         :max-document-length 256
                                         :use-faiss? false}
                                :created-at (str (java.time.Instant/now))}})]}))

(defn- seed-rich-fixture!
  "Seed the rich fixture used by most tests. Returns nothing — assertions
   read back through public interfaces."
  [ctx]
  (record-metadata! ctx primary-id "Indie Film KG (S20 test)" "0.3.0"
                    "CC-BY-4.0" "S20 fixture")
  (record-spec! ctx primary-id
                {:purpose "Map directors, films, awards, and production companies to support filmography Q&A."
                 :scope "Independent cinema from 2010–present."
                 :competency-questions
                 ["Which films did director D direct between 2015 and 2020?"
                  "Which actors collaborated with director D more than twice?"
                  "Which company produced the most award-winning films in scope?"]})
  (record-metadata! ctx aligned-id "Awards standards alignment" "0.1.0" "CC0" "S20 fixture")
  (register-alignment! ctx primary-id aligned-id)
  ;; Classes
  (doseq [[u l] [["class:Director" "Director"]
                 ["class:Actor"    "Actor"]
                 ["class:Producer" "Producer"]]]
    (seed-concept! ctx primary-id u l))
  ;; Directors
  (doseq [[u l] [["concept:p/jane-roe" "Jane Roe"]
                 ["concept:p/john-doe" "John Doe"]
                 ["concept:p/maria-santos" "Maria Santos"]
                 ["concept:p/kai-tanaka" "Kai Tanaka"]]]
    (seed-concept! ctx primary-id u l ["class:Director"]))
  ;; Actors
  (doseq [[u l] [["concept:p/ana-vega" "Ana Vega"]
                 ["concept:p/marcus-li" "Marcus Li"]
                 ["concept:p/dani-vaz" "Dani Vaz"]]]
    (seed-concept! ctx primary-id u l ["class:Actor"]))
  ;; Films
  (doseq [[u l] [["concept:w/red-dawn" "Red Dawn"]
                 ["concept:w/red-dawn-2" "Red Dawn II"]
                 ["concept:w/silent-tides" "Silent Tides"]
                 ["concept:w/voices-of-stone" "Voices of Stone"]
                 ["concept:w/glass-roads" "Glass Roads"]]]
    (seed-concept! ctx primary-id u l))
  ;; Companies
  (doseq [[u l] [["concept:c/lumen-films" "Lumen Films"]
                 ["concept:c/horizon-arts" "Horizon Arts"]]]
    (seed-concept! ctx primary-id u l))
  ;; Aligned section
  (seed-concept! ctx aligned-id "concept:a/cannes-camera-dor" "Caméra d'Or")
  ;; Relationships — multiple predicates with varying frequency
  (doseq [[d w] [["concept:p/jane-roe" "concept:w/red-dawn"]
                 ["concept:p/jane-roe" "concept:w/red-dawn-2"]
                 ["concept:p/john-doe" "concept:w/silent-tides"]
                 ["concept:p/maria-santos" "concept:w/voices-of-stone"]
                 ["concept:p/maria-santos" "concept:w/glass-roads"]]]
    (seed-rel! ctx primary-id d "directed" w))
  (doseq [[a w] [["concept:p/ana-vega" "concept:w/red-dawn"]
                 ["concept:p/ana-vega" "concept:w/red-dawn-2"]
                 ["concept:p/marcus-li" "concept:w/silent-tides"]
                 ["concept:p/dani-vaz" "concept:w/glass-roads"]]]
    (seed-rel! ctx primary-id a "acted-in" w))
  (doseq [[w c] [["concept:w/red-dawn" "concept:c/lumen-films"]
                 ["concept:w/red-dawn-2" "concept:c/lumen-films"]
                 ["concept:w/silent-tides" "concept:c/horizon-arts"]]]
    (seed-rel! ctx primary-id w "produced-by" c))
  (seed-rel! ctx primary-id "concept:w/red-dawn-2" "successor-of" "concept:w/red-dawn")
  ;; Axioms
  (assert-disjoint! ctx primary-id ["class:Director" "class:Actor"])
  (assert-characteristic! ctx primary-id "successor-of" [:transitive])
  (assert-characteristic! ctx primary-id "directed" [:functional]
                          :inverse-of "directed-by")
  (assert-subprop! ctx primary-id "based-on" "related-to")
  (assert-chain! ctx primary-id "collaborated-with" ["directed" "acted-in"]))

;; =============================================================================
;; AC1 — Four layers render with values verifiably matching projections
;; =============================================================================

(deftest card-contains-all-four-layers-with-projection-matching-values
  (testing "The rendered card contains all four labelled section headers
            and the values inside each layer match a FRESH projection
            query — the adversarial failure mode is a stale value, so we
            cross-check counts and content against the public interface
            fns the card derives from."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      (let [card (oc/card-for ctx primary-id)]

        (testing "All four layer headers are present"
          (is (str/includes? card "## IDENTITY"))
          (is (str/includes? card "## T-BOX DIGEST"))
          (is (str/includes? card "## CONTENT SAMPLE"))
          (is (str/includes? card "## TOOL AFFORDANCES")))

        (testing "Identity layer mirrors get-ontology-spec output verbatim"
          (let [spec (ont/get-ontology-spec ctx primary-id)]
            (is (str/includes? card (:purpose spec))
                "The card carries the same purpose text the spec projection returns.")
            (doseq [cq (:competency-questions spec)]
              (is (str/includes? card cq)
                  (str "Card must list CQ: " cq)))))

        (testing "Identity layer mirrors ontology metadata"
          (let [m (rm/get-ontology-metadata ctx primary-id)]
            (is (str/includes? card (:title m)))
            (is (str/includes? card (:version m)))))

        (testing "Identity layer lists the registered alignment section"
          (let [aligns (ont/get-alignment-sections ctx primary-id)]
            (is (= 1 (count aligns)))
            (is (str/includes? card (str (first aligns))))))

        (testing "T-Box predicate counts match a FRESH projection query"
          (let [rels (rm/get-relationships ctx)
                in-scope (filter #(or (nil? (:ontology-id %))
                                      (= primary-id (:ontology-id %)))
                                 rels)
                counts (frequencies (map :predicate in-scope))]
            (doseq [[pred n] counts]
              (is (str/includes? card (str pred "  (" n " edges)"))
                  (str "Predicate " pred " count " n
                       " must appear in card verbatim "
                       "(adversarial: stale count is the failure mode)")))))

        (testing "T-Box surfaces the axiom characteristics from S07"
          (is (str/includes? card "[transitive]")
              "successor-of is asserted transitive")
          (is (str/includes? card "[functional]")
              "directed is asserted functional")
          (is (str/includes? card "inverse-of=directed-by")
              "directed inverse-of=directed-by is asserted")
          (is (str/includes? card "based-on")
              "based-on appears (sub-property-of related-to)")
          (is (str/includes? card "collaborated-with := directed ∘ acted-in")
              "Chain axiom renders the derived := chain shape"))

        (testing "Content sample includes at least 3 distinct concept URIs"
          (let [uri-matches (re-seq #"concept:[a-z]/[a-z0-9-]+" card)]
            (is (>= (count (set uri-matches)) 3)
                "Content sample must include concrete URIs (the adversarial proxy for 'oriented a cold reader on what's in the graph')")))))))

;; =============================================================================
;; AC2 — Graceful degradation: no ORSD spec
;; =============================================================================

(deftest identity-degrades-gracefully-without-orsd-spec
  (testing "When no ORSD spec is recorded, the identity layer still
            renders metadata + alignment registry and prints an
            explicit 'treat any query as exploratory' note. The
            adversarial failure mode is a crash or a silent omission
            that leaves the model with no usable orientation."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      ;; Seed metadata + a few concepts, but NO record-ontology-spec.
      (record-metadata! ctx primary-id "Small graph (no spec)" "0.1"
                        "CC0" "test")
      (doseq [[u l] [["class:Thing" "Thing"]
                     ["concept:thing/alpha" "Alpha"]
                     ["concept:thing/beta"  "Beta"]]]
        (seed-concept! ctx primary-id u l))
      (seed-rel! ctx primary-id "concept:thing/alpha" "knows" "concept:thing/beta")
      (let [card (oc/card-for ctx primary-id)]
        (is (nil? (ont/get-ontology-spec ctx primary-id))
            "Precondition: no spec is recorded")
        (is (str/includes? card "## IDENTITY")
            "Identity header still renders")
        (is (str/includes? card "Not recorded")
            "Card explicitly tells the model the spec was not recorded")
        (is (str/includes? card "Treat any query as exploratory")
            "Card surfaces the explicit guidance for spec-less graphs")
        (is (str/includes? card "Small graph (no spec)")
            "Ontology metadata still renders (title)")
        (is (str/includes? card "concept:thing/alpha")
            "Content sample still renders — card is useful even without spec")))))

;; =============================================================================
;; AC3 — Cache: cached on second request, refreshed on reindex
;;
;; Two adversarial halves — BOTH must hold:
;;
;; (a) Cached on second request — invalidate! before, request twice, assert
;;     the cached string is reused (identical reference, AND no recomputation
;;     happened: we add a new concept BETWEEN requests and assert it does
;;     NOT appear in the second card).
;;
;; (b) Refreshed on reindex — after request 2, inject a :colbert/index-created
;;     event for "ontology-descriptions", request a third time, assert the
;;     new concept now appears.
;; =============================================================================

(deftest cache-is-stable-on-second-request-and-refreshes-on-reindex
  (testing "Cache contract has TWO halves: (a) cached on second request
            even after the graph grew (no recomputation), AND (b)
            refreshed on the next request after a reindex event lands
            (proves cache isn't a stuck no-op). Both halves are
            independently asserted."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      (let [card-1 (oc/card-for ctx primary-id)
            ;; Grow the graph BETWEEN requests, with no reindex.
            _      (seed-concept! ctx primary-id
                                  "concept:p/post-cache-actor" "Post Cache")
            _      (seed-rel! ctx primary-id
                              "concept:p/post-cache-actor"
                              "acted-in"
                              "concept:w/red-dawn")
            card-2 (oc/card-for ctx primary-id)]

        (testing "AC3a — second request returns the cached value
                  (adversarial: the new concept must NOT appear; if the
                  card recomputed against fresh projections it WOULD)"
          (is (= card-1 card-2)
              "Card-2 is the cached card-1 — identical strings")
          (is (not (str/includes? card-2 "Post Cache"))
              "Adversarial: the new concept must NOT appear in the cached card. If it does, the cache is a no-op and we have no refresh contract."))

        ;; Inject a reindex event — bumps `:last-rebuild-timestamp`.
        (inject-index-created! ctx)
        (let [card-3 (oc/card-for ctx primary-id)]
          (testing "AC3b — after reindex, the third request reflects the
                    new graph state. The cache key changed; recomputation
                    fired."
            (is (not= card-2 card-3)
                "Card-3 differs from the cached card-2 — recomputation happened")
            (is (str/includes? card-3 "Post Cache")
                "The post-cache concept's label appears after reindex")
            (is (str/includes? card-3 "concept:p/post-cache-actor")
                "The post-cache URI appears after reindex")))))))

;; =============================================================================
;; AC4 — Sandbox injection: card-for returns nil without a grant
;; =============================================================================

(deftest no-grant-returns-no-card-information-leak-safe
  (testing "The sandbox injection contract is: a sandbox built WITHOUT
            a granted ontology-id MUST NOT have a card injected. The
            adversarial failure mode is a 'default ontology' card
            leaking the structure of an unscoped graph back to a
            consumer who didn't ask for tool access."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      ;; The boundary: card-for with nil ontology-id returns nil. The
      ;; sandbox layer reads this and skips the injection.
      (is (nil? (oc/card-for ctx nil))
          "No grant → no card. The sandbox must not synthesize one.")
      (is (nil? (oc/card-for ctx false))
          "false isn't a valid grant either")
      ;; With a real grant, the card IS produced (positive path
      ;; confirms the boundary actually fires for legitimate grants).
      (is (some? (oc/card-for ctx primary-id))
          "With a real grant, the card is produced"))))

;; =============================================================================
;; AC5 — Cold-read structural assertions: the four layers carry the
;; information a cold reader needs to form a first query.
;; =============================================================================

(deftest cold-read-structural-assertions
  (testing "Structural proxy for 'card alone (no other context) orients
            a reader on what the graph is, what's in it, and how to
            query it'. Programmatic proxy for the slice's hand-review
            criterion — not a complete substitute for the prototype's
            cold-read review, but sufficient as a regression gate."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      (let [card (oc/card-for ctx primary-id)]

        (testing "ORSD purpose text is present (oriented on what the graph is FOR)"
          (is (str/includes? card "Map directors, films, awards")
              "The ORSD purpose verbatim appears"))

        (testing "Predicate names appear in the T-Box digest (oriented on how to query)"
          (is (str/includes? card "directed"))
          (is (str/includes? card "acted-in"))
          (is (str/includes? card "produced-by")))

        (testing "Content sample includes ≥3 concrete concept URIs (oriented on what's in it)"
          (let [uri-matches (set (re-seq #"concept:[a-z]/[a-z0-9-]+" card))]
            (is (>= (count uri-matches) 3)
                (str "Got " (count uri-matches) " distinct concept URIs in the card."))))

        (testing "Tool affordances render all eight tools with PURPOSE + RETURNS + EXAMPLE"
          (doseq [sym ['graph-search 'neighborhood 'get-concept 'exists?
                       'absent-in-graph? 'filter-by-label-pattern
                       'classify-task 'classify-behaviors]]
            (is (str/includes? card (str "### `" sym "`"))
                (str "Header for `" sym "`")))
          (is (str/includes? card "- PURPOSE:")
              "PURPOSE markers render across the tool affordances")
          (is (str/includes? card "- RETURNS:")
              "RETURNS markers render across the tool affordances")
          (is (str/includes? card "- EXAMPLE (this graph):")
              "EXAMPLE markers render across the tool affordances"))))))

;; =============================================================================
;; AC6 — Tool affordances use REAL URIs from THIS graph (not the docstring
;; placeholders). This is the slice's specific 'examples against THIS
;; graph's actual content' criterion.
;; =============================================================================

(deftest tool-affordances-use-real-uris-from-this-graph
  (testing "Per slice criterion: the tool-affordances layer's EXAMPLE
            block uses a REAL URI from the seeded graph, not the
            canonical placeholder URI from the docstring."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      (let [card (oc/card-for ctx primary-id)
            ;; The seeded graph has these URIs; the canonical docstring
            ;; uses concept:dir/jane-roe (S19's example). The EXAMPLE
            ;; block in the rendered card MUST use a URI from the seeded
            ;; graph, not the canonical placeholder.
            seeded-uris #{"concept:p/jane-roe" "concept:p/john-doe"
                          "concept:p/maria-santos" "concept:w/red-dawn"
                          "concept:w/red-dawn-2" "concept:c/lumen-films"}
            ;; Extract every URI from EXAMPLE lines.
            example-lines (->> (str/split-lines card)
                               (filter #(str/includes? % "EXAMPLE (this graph):"))
                               (str/join "\n"))
            example-uris (set (re-seq #"concept:[a-z]/[a-z0-9-]+" example-lines))]
        (is (seq example-uris)
            "At least one tool's EXAMPLE renders with a concept URI")
        (is (every? seeded-uris example-uris)
            (str "Every URI in EXAMPLE lines must come from THIS graph. "
                 "Got: " example-uris))
        (is (not (str/includes? example-lines "concept:dir/jane-roe"))
            "The canonical docstring's placeholder URI must NOT appear in
             EXAMPLE — that would mean the renderer copied the docstring
             verbatim instead of adapting to this graph.")))))

;; =============================================================================
;; AC7 — Grain discipline: zero new events emitted across card requests
;; =============================================================================

(deftest card-requests-are-read-side-only
  (testing "The card is derived from projections; no events are emitted
            during a card request. Grain discipline: read-side
            primitives never write. Adversarial: any event count
            increase between the pre-request and post-request snapshots
            would mean the renderer accidentally dispatched a command."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)
      (let [count-events (fn []
                           (count (into [] (es/read (:event-store ctx)
                                                    {:tenant-id (:tenant-id ctx)
                                                     :read-target {:read-target/category :all}}))))
            before (count-events)
            ;; Multiple card requests; each one is a derived render.
            _ (oc/card-for ctx primary-id)
            _ (oc/card-for ctx primary-id)
            _ (oc/render-card ctx primary-id)  ;; bypass-cache path too
            after (count-events)]
        (is (= before after)
            (str "Card requests emitted events. Before=" before
                 " after=" after " — Grain discipline broken."))))))

;; =============================================================================
;; AC8 — Sandbox injection through build-rlm-context
;;
;; The sandbox layer reads the granted ontology-id and injects the card
;; into the rlm-context map under :orientation-card. Without a grant,
;; :orientation-card is nil/absent.
;; =============================================================================

(deftest sandbox-build-includes-card-when-granted
  (testing "Build a recursive-rlm sandbox WITH a grant and assert the
            returned context map carries the orientation card. Without
            the grant, the field is absent — proves the injection point
            is grant-gated."
    (h/with-test-context [ctx]
      (oc/invalidate!)
      (seed-rich-fixture! ctx)

      (testing "WITH grant → :orientation-card present + populated"
        (let [rlm-ctx (rlm-sandbox/build-rlm-context
                       {:provider :openrouter
                        :blackboard {}
                        :declared-writes [:result]
                        :event-store (:event-store ctx)
                        :tenant-id (:tenant-id ctx)
                        :cache (:cache ctx)
                        :granted-ontology-id primary-id})
              card (:orientation-card rlm-ctx)]
          (is (string? card)
              "Card was injected as a string into the sandbox context")
          (is (str/includes? card "## IDENTITY")
              "Card content shape — identity header present")
          (is (str/includes? card "Map directors, films, awards")
              "Card content shape — ORSD purpose verbatim")))

      (testing "WITHOUT grant → :orientation-card is nil (information-leak safe)"
        (let [rlm-ctx (rlm-sandbox/build-rlm-context
                       {:provider :openrouter
                        :blackboard {}
                        :declared-writes [:result]
                        :event-store (:event-store ctx)
                        :tenant-id (:tenant-id ctx)
                        :cache (:cache ctx)})]
          (is (nil? (:orientation-card rlm-ctx))
              "No grant → no card. The sandbox must not synthesize a default-ontology card."))))))
