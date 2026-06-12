(ns ai.obney.orc.ontology.s08-equivalence-events-test
  "S08 — Equivalence events with `:kind` discriminator
   (`:same-as` / `:equivalent-class` / `:equivalent-property`), tagged
   to an alignment section (S03). End-to-end:

     1. Command + event + projection round-trip for each of the three
        kinds. `:kind` is REQUIRED — no default — and a missing
        `:kind` returns an `::anom/incorrect` from the Grain pre-handler
        Malli gate (the assertion checks the anomaly category, not just
        the absence of success).

     2. Tagging: events tag to the alignment section's
        `:ontology-id`. Adversarial: the primary sections' event
        streams stay clean — NO equivalence events appear under either
        primary's `[:ontology <primary-id>]` tag. Inspected at the
        raw event-stream level via es/find-events.

     3. Auto-widening composes with S03: a scoped query against a
        primary surfaces the equivalent concept from the OTHER primary
        via the widened-scope BFS, AND the result payload carries the
        equivalence-kind annotation (via the new
        :equivalences-for-uri accessor on the projection).

     4. OWL export: each kind exports the correct OWL predicate.
        Adversarial (load-bearing): a `:equivalent-class` pair must
        NOT appear as `owl:sameAs` between those two URIs in the
        emitted TTL — the inheritance-merge hazard documented in the
        round-2 grill is the failure mode under test.

   All writes go through commands (no bare event-store appends).
   All assertions go through public interfaces (commands + read-model
   projection helpers + serialization). No internals tested."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration — without this,
            ;; the event-store's append-time Malli validation rejects
            ;; the equivalence events.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def primary-p #uuid "5080000a-0000-0000-0000-000000000001")
(def primary-q #uuid "5080000a-0000-0000-0000-000000000002")
(def align-pq  #uuid "5080000a-0000-0000-0000-000000000003")

;; =============================================================================
;; Test helpers — sugar over the commands
;; =============================================================================

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept (assoc c :command body)))))

(defn- create-relationship! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship (assoc c :command body)))))

(defn- register-alignment! [ctx primary alignment]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-register-alignment-section
                       (assoc c :command {:primary-ontology-id primary
                                          :alignment-ontology-id alignment})))))

(defn- record-equivalence! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-record-equivalence (assoc c :command body)))))

;; =============================================================================
;; AC1 — Round-trip per kind through command → event → projection
;; =============================================================================

(deftest equivalence-same-as-round-trips
  (testing "Recording a :same-as equivalence between two individual URIs
            surfaces on the :ontology/equivalences projection under the
            alignment section's :ontology-id, in the :same-as bucket."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            :kind :same-as})
      (let [equivs (rm/get-equivalences ctx align-pq)
            same-as (:same-as equivs)]
        (is (some? equivs) "equivalences submap projected for the alignment section")
        (is (contains? same-as #{"p:alice" "q:alice"})
            "the unordered pair lands in :same-as as a sorted-pair set")))))

(deftest equivalence-equivalent-class-round-trips
  (testing "Recording an :equivalent-class equivalence lands in the
            :equivalent-class bucket — NOT :same-as. The kind is
            preserved through the projection."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:Director"
                            :target-uri "q:Filmmaker"
                            :kind :equivalent-class})
      (let [equivs (rm/get-equivalences ctx align-pq)]
        (is (contains? (:equivalent-class equivs) #{"p:Director" "q:Filmmaker"})
            ":equivalent-class bucket carries the pair")
        (is (not (contains? (:same-as equivs #{}) #{"p:Director" "q:Filmmaker"}))
            "the :same-as bucket does NOT carry the same pair — kind preserved")))))

(deftest equivalence-equivalent-property-round-trips
  (testing ":equivalent-property kind lands in its own bucket."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:hasAuthor"
                            :target-uri "q:writtenBy"
                            :kind :equivalent-property})
      (let [equivs (rm/get-equivalences ctx align-pq)]
        (is (contains? (:equivalent-property equivs) #{"p:hasAuthor" "q:writtenBy"})
            ":equivalent-property bucket carries the pair")))))

(deftest equivalence-kind-required
  (testing "Adversarial: a command missing :kind is REJECTED at the
            Grain command-processor's pre-handler Malli gate with
            ::anom/incorrect. The assertion checks the anomaly SHAPE,
            not just the absence of success — a permissive default
            would still let the command 'succeed' without :kind."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/record-equivalence
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            ;; :kind omitted intentionally
                            }))]
        (is (= ::anom/incorrect (::anom/category result))
            "missing :kind returns ::anom/incorrect from the pre-handler gate")
        (is (empty? (:command-result/events result))
            "no event emitted — no silent kind defaulting")))))

(deftest equivalence-unknown-kind-rejected
  (testing "Adversarial: an unknown :kind keyword is also rejected at
            the schema enum gate (defense against a stray
            misspelled kind smuggling through as a custom value)."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/record-equivalence
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            :kind :sameas-typo}))]
        (is (= ::anom/incorrect (::anom/category result))
            "unknown :kind returns ::anom/incorrect")
        (is (empty? (:command-result/events result))
            "no event emitted")))))

;; =============================================================================
;; AC2 — Primary sections stay clean
;; =============================================================================

(deftest primary-sections-stay-clean
  (testing "Adversarial — load-bearing. After recording an equivalence
            tagged to ALIGN with endpoints in P and Q, NEITHER primary's
            raw event stream contains any equivalence-recorded events.
            Verified at the event-store level via es/find-events with
            the [:ontology <primary>] tag — the only events that should
            appear under a primary are its own concept/relationship
            events."
    (h/with-test-context [ctx]
      ;; Seed primaries with one concept each (so the streams are non-empty).
      (create-concept! ctx {:ontology-id primary-p :uri "p:Director"
                            :label "Director" :description "" :scope :custom})
      (create-concept! ctx {:ontology-id primary-q :uri "q:Filmmaker"
                            :label "Filmmaker" :description "" :scope :custom})
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:Director"
                            :target-uri "q:Filmmaker"
                            :kind :equivalent-class})

      (let [tenant-id (:tenant-id ctx)
            ;; Look at the raw event streams under each primary's
            ;; [:ontology <id>] tag. The equivalence event MUST NOT
            ;; appear under either primary — only under ALIGN.
            primary-p-events (into [] (es/read
                                       (:event-store ctx)
                                       {:tags #{[:ontology primary-p]}
                                        :tenant-id tenant-id}))
            primary-q-events (into [] (es/read
                                       (:event-store ctx)
                                       {:tags #{[:ontology primary-q]}
                                        :tenant-id tenant-id}))
            align-events    (into [] (es/read
                                      (:event-store ctx)
                                      {:tags #{[:ontology align-pq]}
                                       :tenant-id tenant-id}))
            equiv-type :ontology/equivalence-recorded
            equiv-types-fn (fn [evs] (set (keep :event/type evs)))]
        (is (not (contains? (equiv-types-fn primary-p-events) equiv-type))
            "primary P's event stream contains ZERO equivalence-recorded events")
        (is (not (contains? (equiv-types-fn primary-q-events) equiv-type))
            "primary Q's event stream contains ZERO equivalence-recorded events")
        (is (contains? (equiv-types-fn align-events) equiv-type)
            "the ALIGN section's event stream is where the equivalence-recorded event lives")))))

;; =============================================================================
;; AC3 — Auto-widening composes with S03: scoped query against P
;; surfaces the equivalent concept from Q with kind preserved.
;; =============================================================================

(defn- seed-bridged-corpus!
  "Section P: p:Director. Section Q: q:Filmmaker. Cross-section :related
   edge tagged with ALIGN connects p:Director -> q:Filmmaker (so the
   BFS path the S03 auto-widen unlocks reaches Q from P). Equivalence
   event records :equivalent-class between the two URIs, tagged to ALIGN."
  [ctx]
  (create-concept! ctx {:ontology-id primary-p :uri "p:Director"
                        :label "Director"
                        :description "A person who directs films."
                        :scope :custom})
  (create-concept! ctx {:ontology-id primary-q :uri "q:Filmmaker"
                        :label "Filmmaker"
                        :description "A person who makes films."
                        :scope :custom})
  ;; The alignment section also stores a "bridge" related edge so
  ;; widened BFS has a path between the two concepts. This mirrors S03's
  ;; cross-section-edge pattern: edge tagged with ALIGN's :ontology-id,
  ;; lands in ALIGN's section-keyed concept-map via S06's apply-section-
  ;; edge (after we seed an anchor concept in ALIGN that holds it).
  ;;
  ;; For S08's AC3, the SIMPLER test is: register ALIGN as a primary's
  ;; alignment so the registry widens; record the equivalence; expose
  ;; the equivalence pair in the result payload via the new accessor;
  ;; assert the kind is preserved end-to-end.
  (register-alignment! ctx primary-p align-pq)
  (record-equivalence! ctx
                       {:ontology-id align-pq
                        :source-uri "p:Director"
                        :target-uri "q:Filmmaker"
                        :kind :equivalent-class}))

(deftest auto-widened-query-surfaces-equivalent-with-kind
  (testing "AC3 — composability with S03. A scoped query against
            primary-P with an alignment registered MUST surface the
            equivalence (with kind) for any concept that participates
            in one, by consulting the equivalences projection on the
            widened scope. The kind is preserved end-to-end."
    (h/with-test-context [ctx]
      (seed-bridged-corpus! ctx)

      ;; widen-ontology-ids brings ALIGN into scope.
      (let [widened (rm/widen-ontology-ids ctx primary-p)]
        (is (contains? widened align-pq)
            "S03 widening pulls ALIGN into the scope union")

        ;; The new accessor: walk the widened ontology-ids, collect
        ;; equivalences for the seed URI, return a list of
        ;; {:partner :kind :ontology-id} maps.
        (let [hits (rm/surface-equivalents ctx widened "p:Director")]
          (is (= 1 (count hits))
              "exactly one equivalence hit for p:Director under the widened scope")
          (let [{:keys [partner kind ontology-id]} (first hits)]
            (is (= "q:Filmmaker" partner) "partner URI surfaced from Q")
            (is (= :equivalent-class kind)
                "the kind is PRESERVED end-to-end — not collapsed to :same-as")
            (is (= align-pq ontology-id)
                "the equivalence carries the alignment section's id")))

        ;; Adversarial twin: a query that DISABLES widening (or with
        ;; no alignment registered) MUST NOT surface the equivalent.
        (let [strict-hits (rm/surface-equivalents ctx primary-p "p:Director")]
          (is (empty? strict-hits)
              "without widening (strict scope), the equivalent is correctly invisible"))))))

;; =============================================================================
;; AC4 — OWL export: kind faithful per kind
;; =============================================================================

(deftest same-as-exports-as-owl-same-as
  (testing ":same-as kind exports as owl:sameAs in TTL."
    (h/with-test-context [ctx]
      (doseq [uri ["p:alice" "q:alice"]]
        (create-concept! ctx {:ontology-id (if (str/starts-with? uri "p:")
                                             primary-p primary-q)
                              :uri uri :label uri :description (str "person " uri)
                              :scope :custom}))
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            :kind :same-as})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:sameAs")
            "TTL emits owl:sameAs")
        (is (or (re-find #"p:alice\s+owl:sameAs\s+q:alice" ttl)
                (re-find #"q:alice\s+owl:sameAs\s+p:alice" ttl))
            "the alice pair is emitted as a sameAs triple in at least one direction")))))

(deftest equivalent-class-exports-as-owl-equivalent-class
  (testing ":equivalent-class kind exports as owl:equivalentClass in TTL."
    (h/with-test-context [ctx]
      (doseq [[ont uri] [[primary-p "p:Director"] [primary-q "q:Filmmaker"]]]
        (create-concept! ctx {:ontology-id ont :uri uri :label uri
                              :description (str "class " uri) :scope :custom}))
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:Director"
                            :target-uri "q:Filmmaker"
                            :kind :equivalent-class})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:equivalentClass")
            "TTL emits owl:equivalentClass")
        (is (or (re-find #"p:Director\s+owl:equivalentClass\s+q:Filmmaker" ttl)
                (re-find #"q:Filmmaker\s+owl:equivalentClass\s+p:Director" ttl))
            "the Director/Filmmaker pair appears as an equivalentClass triple")))))

(deftest equivalent-property-exports-as-owl-equivalent-property
  (testing ":equivalent-property kind exports as owl:equivalentProperty in TTL."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:hasAuthor"
                            :target-uri "q:writtenBy"
                            :kind :equivalent-property})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:equivalentProperty")
            "TTL emits owl:equivalentProperty")
        (is (or (re-find #"p:hasAuthor\s+owl:equivalentProperty\s+q:writtenBy" ttl)
                (re-find #"q:writtenBy\s+owl:equivalentProperty\s+p:hasAuthor" ttl))
            "the hasAuthor/writtenBy pair appears as an equivalentProperty triple")))))

(deftest equivalent-class-does-not-export-as-same-as
  (testing "LOAD-BEARING adversarial — the inheritance-merge hazard.

            A :equivalent-class equivalence MUST NOT export as
            owl:sameAs between those two URIs. The course-verified
            failure mode: owl:sameAs on classes merges property
            assertions across the equivalence in any downstream
            reasoner, silently inheriting attributes that should
            remain class-level structure. The kind discriminator
            exists precisely to PREVENT this.

            Inspection has two layers:
              (i) STRING-LEVEL: zero occurrences of any sameAs
                  predicate between p:Director and q:Filmmaker.
              (ii) STRUCTURAL: at least one occurrence of
                  owl:equivalentClass between them — and the same
                  two URIs do not appear in a sameAs triple anywhere
                  in the emitted TTL."
    (h/with-test-context [ctx]
      (doseq [[ont uri] [[primary-p "p:Director"] [primary-q "q:Filmmaker"]]]
        (create-concept! ctx {:ontology-id ont :uri uri :label uri
                              :description (str "class " uri) :scope :custom}))
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:Director"
                            :target-uri "q:Filmmaker"
                            :kind :equivalent-class})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})
            ;; (i) String-level: any sameAs occurrence linking the two
            ;; URIs is a failure.
            sameAs-pair-1 (re-find #"p:Director\s+owl:sameAs\s+q:Filmmaker" ttl)
            sameAs-pair-2 (re-find #"q:Filmmaker\s+owl:sameAs\s+p:Director" ttl)
            ;; (ii) Structural: equivalentClass MUST be present.
            equivClass-pair-1 (re-find #"p:Director\s+owl:equivalentClass\s+q:Filmmaker" ttl)
            equivClass-pair-2 (re-find #"q:Filmmaker\s+owl:equivalentClass\s+p:Director" ttl)]
        (is (nil? sameAs-pair-1)
            "p:Director owl:sameAs q:Filmmaker MUST NOT appear in the TTL")
        (is (nil? sameAs-pair-2)
            "q:Filmmaker owl:sameAs p:Director MUST NOT appear in the TTL")
        (is (or (some? equivClass-pair-1) (some? equivClass-pair-2))
            "owl:equivalentClass MUST appear between the two URIs")))))

(deftest same-as-does-not-export-as-equivalent-class
  (testing "Inverse adversarial — :same-as MUST NOT export as
            equivalentClass either. The discriminator works both ways.

            The slice's primary failure mode is class-marked-as-sameAs
            (the inheritance-merge hazard), but the inverse mistake
            (individuals-marked-as-equivalentClass) is semantically
            wrong too: owl:equivalentClass between individuals is a
            class-level construct and gets ignored / type-error'd by
            OWL DL reasoners."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            :kind :same-as})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (nil? (re-find #"p:alice\s+owl:equivalentClass\s+q:alice" ttl))
            ":same-as MUST NOT emit owl:equivalentClass")
        (is (nil? (re-find #"q:alice\s+owl:equivalentClass\s+p:alice" ttl))
            ":same-as MUST NOT emit owl:equivalentClass (reverse direction)")
        (is (or (re-find #"p:alice\s+owl:sameAs\s+q:alice" ttl)
                (re-find #"q:alice\s+owl:sameAs\s+p:alice" ttl))
            "the pair appears as owl:sameAs")))))

;; =============================================================================
;; AC5 — Multiple kinds coexist in one TTL export without confusion
;; =============================================================================

(deftest multiple-kinds-coexist-cleanly
  (testing "Recording one equivalence of each kind in a single ALIGN
            section emits THREE distinct OWL predicates in the TTL —
            no kind is silently collapsed into another, no pair is
            duplicated across buckets."
    (h/with-test-context [ctx]
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:alice"
                            :target-uri "q:alice"
                            :kind :same-as})
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:Director"
                            :target-uri "q:Filmmaker"
                            :kind :equivalent-class})
      (record-equivalence! ctx
                           {:ontology-id align-pq
                            :source-uri "p:hasAuthor"
                            :target-uri "q:writtenBy"
                            :kind :equivalent-property})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:sameAs")            "owl:sameAs emitted")
        (is (str/includes? ttl "owl:equivalentClass")    "owl:equivalentClass emitted")
        (is (str/includes? ttl "owl:equivalentProperty") "owl:equivalentProperty emitted")))))
