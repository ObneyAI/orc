(ns ai.obney.orc.ontology.s18-rlm-discovery-test
  "S18 — recursive-RLM discovery wiring + ontology-discovery seed
   corpus tests.

   Verifies:
     - Seed corpus mechanics (loader, schema discipline, no truncation
       of :recommended-pattern, self-containment of :summary).
     - Discovery wiring construction (precondition guards on
       :ontology-id + :event-store, recursive-only).
     - Discovery output → S17 adapter (`compile-discovery-source!`)
       emits events through commands + the source stub is shaped for
       S17 ingest.
     - HITL status filtering (`require-hitl-reviewed-patterns?`).
     - Adversarial: malformed drafts surface clear anomalies; nothing
       silently drops.

   Discipline: tests go through the public interface
   (`ontology/run-discovery!`, `ontology/compile-discovery-source!`,
   `ontology/ontology-discovery-patterns`) — never internal helpers.
   Real Grain in-memory event store. No mocks of the event store, no
   try/catch swallowing exceptions."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Test context
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/s18-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

;; =============================================================================
;; Seed corpus mechanics
;; =============================================================================

(deftest discovery-seed-corpus-loads-five-AFK-derived-patterns
  (testing "The ontology-discovery-patterns.edn ships exactly 5
            AFK-derived patterns, each with :hitl-status :auto-derived
            and required body fields."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (is (= 5 (count patterns))
          "5 patterns derived from bench RESULTS")
      (doseq [p patterns]
        (is (some? (:target-id p))
            "Every seed has a :target-id")
        (is (some? (get-in p [:body :summary]))
            "Every seed has :body :summary")
        (is (some? (get-in p [:body :provenance]))
            "Every seed has :body :provenance documenting bench origin")
        (is (= :auto-derived (get-in p [:body :hitl-status]))
            "Every initial seed is :hitl-status :auto-derived")
        (is (true? (get-in p [:body :discovery-pattern?]))
            "Every seed flagged :discovery-pattern? true")
        (is (= :behavioral-subtree (get-in p [:body :scope]))
            "Every seed routes through :scope :behavioral-subtree so
             classify-behaviors retrieves it")
        (is (seq (get-in p [:body :strengths]))
            "Every seed has at least one :strengths entry")
        (is (seq (get-in p [:body :representative-uses]))
            "Every seed has :representative-uses examples")))))

(deftest discovery-seed-recommended-patterns-not-truncated
  (testing "Each :strengths :recommended-pattern is preserved
            verbatim — no truncation. Adversarially: assert the
            snippet contains substantive DSL keywords proving it's
            not a stub."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (doseq [p patterns]
        (doseq [s (get-in p [:body :strengths])]
          (let [snippet (:recommended-pattern s)]
            (is (string? snippet) "Recommended-pattern is a string")
            (is (> (count snippet) 40)
                (str "Recommended-pattern non-trivial; got "
                     (count snippet) " chars"))
            (is (str/includes? snippet "[")
                "Recommended-pattern is a DSL form (starts with [")
            (is (or (str/includes? snippet ":llm")
                    (str/includes? snippet ":sequence")
                    (str/includes? snippet ":chunk-document")
                    (str/includes? snippet ":map-each")
                    (str/includes? snippet ":parallel")
                    (str/includes? snippet ":final"))
                "Recommended-pattern contains real DSL keywords")))))))

(deftest discovery-seed-summary-fields-are-self-contained
  (testing "Adversarial: a :summary leaking a file path or slice
            name would be a discipline violation. The model can't
            dereference those. Provenance is its own field."
    (let [patterns (ontology/ontology-discovery-patterns)]
      (doseq [p patterns]
        (let [summary (get-in p [:body :summary])]
          (is (string? summary))
          (is (> (count summary) 30) "Summary is substantive")
          ;; The summary must not contain file paths or slice
          ;; markers — those don't dereference for the model.
          (is (not (str/includes? summary "/seeds/"))
              "Summary doesn't reference filesystem paths")
          (is (not (re-find #"\b[Ss][0-9]+[a-z]?\b" summary))
              (str "Summary doesn't reference slice names; got: " summary))
          (is (not (re-find #"\b[a-f0-9]{7,}\b" summary))
              "Summary doesn't contain commit SHAs"))))))

(deftest discovery-seeds-emit-via-seed-baseline-corpus
  (testing "Calling seed-baseline-corpus! dispatches the 5 discovery
            patterns alongside the existing baseline dispatches.
            Total: 68 baseline + 5 E3 behavioral-children + 5 discovery = 78."
    (with-ctx [ctx]
      (let [results (ontology/seed-baseline-corpus! ctx)]
        (is (= 78 (count results))
            (str "Expected 68 baseline + 5 E3 behavioral-children + 5 discovery = 78; got "
                 (count results)))))))

;; =============================================================================
;; Discovery wiring construction — precondition guards
;; =============================================================================

(deftest run-discovery-requires-ontology-id
  (testing "Discovery without :ontology-id is meaningless — the
            session refuses to construct (Disciplines #5)."
    (with-ctx [ctx]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"requires :ontology-id"
            (ontology/run-discovery!
              ctx
              {:sources [{:name :doc :type :text :content "some content"}]}))))))

(deftest run-discovery-requires-event-store
  (testing "Discovery requires :event-store on the ctx — without it
            the session can't grant S19 tools / S20 card. Refuse to
            construct."
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"requires :event-store"
          (ontology/run-discovery!
            {}
            {:ontology-id (random-uuid)
             :sources [{:name :doc :type :text :content "x"}]})))))

(deftest run-discovery-requires-sources
  (testing "Empty :sources is meaningless. Refuse."
    (with-ctx [ctx]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"requires :sources"
            (ontology/run-discovery!
              ctx
              {:ontology-id (random-uuid)
               :sources []}))))))

;; =============================================================================
;; HITL status filtering
;; =============================================================================

(deftest ontology-discovery-patterns-default-returns-all-seeds
  (testing "Default mode returns all patterns including auto-derived."
    (is (= 5 (count (ontology/ontology-discovery-patterns))))
    (is (= 5 (count (ontology/ontology-discovery-patterns false))))))

(deftest ontology-discovery-patterns-hitl-only-returns-empty-when-none-reviewed
  (testing "Adversarial: under :require-hitl-reviewed? true with
            zero reviewed seeds shipped, the filter returns empty —
            and that empty IS a legal return (the session proceeds
            with no patterns; it does not crash). Surfaces in the
            rlm-trace separately."
    (let [reviewed-only (ontology/ontology-discovery-patterns true)]
      (is (= 0 (count reviewed-only))
          "No shipped seeds are :hitl-reviewed yet — empty is the
           correct, transparent return"))))

;; =============================================================================
;; Discovery output → S17 adapter
;; =============================================================================
;; Build a synthetic discovery-output (the shape run-discovery! would
;; produce) and verify compile-discovery-source! emits events via
;; commands AND returns the source stub S17 can consume.

(def ^:private sample-discovery-output
  "A synthetic discovery output mirroring the shape `run-discovery!`
   produces on `:status :emitted-drafts`. Captured by hand rather than
   from a live LLM call so the adapter tests are reproducible. The
   live LLM verification lives in the s18 live-verify driver."
  {:status :emitted-drafts
   :emitted-concepts
   [{:uri "concept:legal/agreement"
     :label "Employment Agreement"
     :description "A signed contract between employer and employee."
     :scope :custom
     :evidence [{:source "doc-1"
                 :quote "This Employment Agreement is entered into..."}]}
    {:uri "concept:legal/employee"
     :label "Employee"
     :description "The individual party to an employment agreement."
     :scope :custom
     :evidence [{:source "doc-1" :quote "the Employee agrees to..."}]}]
   :emitted-relationships
   [{:source-uri "concept:legal/agreement"
     :target-uri "concept:legal/employee"
     :predicate "binds"
     :confidence-class :extracted
     :evidence [{:source "doc-1"
                 :quote "This Employment Agreement... binds the Employee..."}]}]
   :emitted-axioms []
   :rlm-trace [{:iteration 1
                :classified-pattern "DirectExtractionDiscovery"
                :tree-shape "single-llm"
                :outcome :emitted}]
   :iteration-reasonings ["Source is small; direct extraction is appropriate."]
   :patterns-offered 5})

(deftest compile-discovery-source-emits-concept-events
  (testing "The adapter emits concept events through
            :ontology/create-concept and returns a source stub the
            S17 build! can ingest as a parse-stage no-op."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            stub (ontology/compile-discovery-source!
                   ctx oid sample-discovery-output)]
        (is (= :inline-concepts (:type stub))
            "Source stub uses S17's existing :inline-concepts type")
        (is (= [] (:concepts stub))
            "Stub carries no inline concepts — events already emitted")
        (is (= :ingested (get-in stub [:discovery-provenance :status])))
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted])))
        (is (= 1 (get-in stub [:discovery-provenance :relationships-emitted])))
        (is (= 0 (get-in stub [:discovery-provenance :axioms-emitted])))
        ;; Verify the concept events actually landed.
        (let [concepts (filter #(= oid (:ontology-id %))
                               (rm/get-concepts ctx {}))]
          (is (= 2 (count concepts))
              "Two concept events landed in the event store"))))))

(deftest compile-discovery-source-coerces-invalid-scope
  (testing "A discovery session (general-purpose extraction) often invents a
            domain :scope the ontology-scope enum doesn't include (e.g.
            :policy). The adapter coerces unknown scopes to :custom rather than
            failing the create-concept command — the S18 live-verify ingest
            finding."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out {:status :emitted-drafts
                 :emitted-concepts
                 [{:uri "concept:policy/employee" :label "Employee"
                   :description "A person hired under this policy."
                   :scope :policy          ;; NOT a valid ontology-scope
                   :evidence [{:source "policy" :quote "'Employee' means a person hired"}]}
                  {:uri "concept:policy/manager" :label "Manager"
                   :description "An employee with direct reports."
                   ;; no :scope at all → also coerces to :custom
                   :evidence [{:source "policy" :quote "'Manager' means an Employee with"}]}]
                 ;; JSON round-trip leaves keyword fields as STRINGS — the
                 ;; relationship's confidence-class arrives as "extracted",
                 ;; and the scope as "employment". Both must coerce.
                 :emitted-relationships
                 [{:source-uri "concept:policy/manager" :target-uri "concept:policy/employee"
                   :predicate "supervises"
                   :confidence-class "extracted"   ;; STRING, not :extracted
                   :evidence [{:source "policy" :quote "Every Manager supervises"}]}]
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted]))
            "Both concepts ingested despite the invalid/absent scope")
        (is (= 1 (get-in stub [:discovery-provenance :relationships-emitted]))
            "Relationship ingested despite the string confidence-class")
        (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))]
          (is (= 2 (count concepts)))
          (is (every? #(= :custom (:scope %)) concepts)
              "Invalid :policy scope and absent scope both coerced to :custom"))))))

(deftest compile-discovery-source-forwards-numeric-attributes
  (testing "V09 — a concept-draft's :attributes (numeric outcomes the model
            attaches, e.g. earnings/wages) land as QUERYABLE concept
            attributes on the projection, with values kept as their native
            type. String attribute keys (the JSON round-trip leaves them as
            strings) coerce to keywords; numeric values are NOT stringified.
            This is the V02 acceptance-critical earnings carrier — the prior
            draft->command path dropped :attributes entirely."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out {:status :emitted-drafts
                 :emitted-concepts
                 [{:uri "cip:42.0101" :label "Psychology, General."
                   :description "Psychology program."
                   :scope :custom
                   ;; String keys + numeric values — exactly the JSON
                   ;; round-trip shape from a real discovery session.
                   :attributes {"earnings-y1" 38500
                                "earnings-y5" 52547
                                "earnings-y10" 61200}
                   :evidence [{:source "PSEO" :quote "52547"}]}
                  {:uri "soc:19-3033" :label "Clinical Psychologists"
                   :description "Occupation."
                   :scope :custom
                   ;; keyword key already (pass-through) + a string key
                   :attributes {:median-wage 89000 "growth-pct" 6.0}
                   :evidence [{:source "wages" :quote "89000"}]}]
                 :emitted-relationships []
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted])))
        (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
              by-uri (into {} (map (juxt :uri identity)) concepts)
              prog (get by-uri "cip:42.0101")
              occ (get by-uri "soc:19-3033")]
          (is (= 2 (count concepts)))
          (is (= {:earnings-y1 38500 :earnings-y5 52547 :earnings-y10 61200}
                 (:attributes prog))
              "Earnings forwarded as keyword-keyed numeric attributes (queryable)")
          (is (number? (get-in prog [:attributes :earnings-y5]))
              "Earnings value kept as a NUMBER, not stringified")
          (is (= {:median-wage 89000 :growth-pct 6.0} (:attributes occ))
              "Mixed keyword/string keys both coerce; wage stays numeric"))))))

(deftest compile-discovery-source-emits-relationship-events
  (testing "The adapter emits relationship events through
            :ontology/create-relationship with evidence preserved."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source!
                ctx oid sample-discovery-output)
            relationships (filter #(= oid (:ontology-id %))
                                  (rm/get-relationships ctx))]
        (is (= 1 (count relationships))
            "Relationship event landed")
        (let [r (first relationships)]
          (is (= "binds" (:predicate r))))))))

(deftest compile-discovery-source-rejects-malformed-concept-draft
  (testing "Adversarial: a concept-draft missing :uri or :label
            raises ex-info — NO silent skip (Disciplines #5)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            bad {:status :emitted-drafts
                 :emitted-concepts [{:uri nil :label "missing uri"}]
                 :emitted-relationships []
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed concept-draft"
              (ontology/compile-discovery-source! ctx oid bad)))))))

(deftest compile-discovery-source-rejects-malformed-relationship-draft
  (testing "Adversarial: a relationship-draft missing required fields
            raises ex-info."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            bad {:status :emitted-drafts
                 :emitted-concepts []
                 :emitted-relationships [{:source-uri "x"
                                          :target-uri nil
                                          :predicate "y"}]
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed relationship-draft"
              (ontology/compile-discovery-source! ctx oid bad)))))))

(deftest compile-discovery-source-rejects-non-emitted-status
  (testing "Adversarial: a discovery-output whose :status is
            :failed-at-session or :no-output cannot be compiled.
            The adapter raises explicitly so callers don't pass an
            empty / failed result through silently."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            failed {:status :failed-at-session
                    :error "session crashed"}]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #":status :emitted-drafts"
              (ontology/compile-discovery-source! ctx oid failed)))))))

;; =============================================================================
;; V07 — Axiom-draft ingest
;; =============================================================================
;; Discovery EXTRACTS axiom drafts; V07 routes them to the S07 axiom
;; commands instead of recording them as `:axioms-skipped`. Same
;; JSON-string→keyword coercion discipline the scope / confidence-class
;; paths already use, applied here to axiom-type + characteristic enums.
;;
;; Axiom-draft shape: {:axiom-type <kw|str> :body <map> :evidence [...]}.
;; The :body carries exactly the S07 command's payload fields per type:
;;   :disjointness            {:class-uris [<str> <str> ...]}
;;   :property-characteristic {:predicate <str>
;;                             :characteristic [<kw|str> ...]
;;                             :inverse-of <str?>}
;;   :sub-property            {:sub-predicate <str> :super-predicate <str>}
;;   :chain                   {:chain [<str> <str> ...] :derived-predicate <str>}

(deftest compile-discovery-source-ingests-disjointness-axiom
  (testing "A :disjointness axiom-draft routes to :ontology/assert-disjointness
            and lands in the :ontology/axioms projection (NOT skipped)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :disjointness
                         :body {:class-uris ["concept:legal/agreement"
                                             "concept:legal/employee"]}
                         :evidence [{:source "doc-1"
                                     :quote "An Agreement is not an Employee."}]}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 1 (get-in stub [:discovery-provenance :axioms-emitted]))
            "Provenance reports one axiom EMITTED")
        (is (not (contains? (:discovery-provenance stub) :axioms-skipped))
            "The old :axioms-skipped key is gone — axioms are no longer skipped")
        (let [axioms (rm/get-axioms ctx oid)]
          (is (some? axioms) "Axiom projection populated for the ontology-id")
          (is (= #{"concept:legal/employee"}
                 (get-in axioms [:disjointness "concept:legal/agreement"]))
              "Disjointness recorded symmetrically in the S07 projection"))))))

(deftest compile-discovery-source-ingests-property-characteristic-axiom
  (testing "A :property-characteristic axiom-draft routes to
            :ontology/assert-property-characteristic and lands in the
            projection."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :property-characteristic
                         :body {:predicate "supervises"
                                :characteristic [:transitive]
                                :inverse-of "supervised-by"}
                         :evidence [{:source "doc-1" :quote "supervises chain"}]}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 1 (get-in stub [:discovery-provenance :axioms-emitted])))
        (let [axioms (rm/get-axioms ctx oid)]
          (is (contains? (get-in axioms [:characteristics "supervises"]) :transitive)
              "Transitive flag recorded")
          (is (= "supervised-by" (get-in axioms [:inverse-of "supervises"]))
              "Inverse-of recorded bidirectionally"))))))

(deftest compile-discovery-source-ingests-sub-property-axiom
  (testing "A :sub-property axiom-draft routes to :ontology/assert-sub-property."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :sub-property
                         :body {:sub-predicate "supervises"
                                :super-predicate "manages"}
                         :evidence []}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 1 (get-in stub [:discovery-provenance :axioms-emitted])))
        (let [axioms (rm/get-axioms ctx oid)]
          (is (= "manages" (get-in axioms [:sub-property-of "supervises"]))
              "Sub-property recorded"))))))

(deftest compile-discovery-source-ingests-chain-axiom
  (testing "A :chain axiom-draft routes to :ontology/assert-chain-axiom."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :chain
                         :body {:chain ["supervises" "supervises"]
                                :derived-predicate "indirectly-supervises"}
                         :evidence []}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 1 (get-in stub [:discovery-provenance :axioms-emitted])))
        (let [axioms (rm/get-axioms ctx oid)]
          (is (= ["supervises" "supervises"]
                 (get-in axioms [:chains "indirectly-supervises"]))
              "Chain definition recorded"))))))

(deftest compile-discovery-source-coerces-string-axiom-type-and-characteristic
  (testing "JSON round-trip leaves :axiom-type and characteristic enum values
            as STRINGS. Both must coerce to the keyword enums (same discipline
            as scope / confidence-class)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type "property-characteristic"  ;; STRING type
                         :body {:predicate "supervises"
                                :characteristic ["functional" "transitive"]  ;; STRING flags
                                :inverse-of "supervised-by"}
                         :evidence []}
                        {:axiom-type "disjointness"  ;; STRING type
                         :body {:class-uris ["concept:legal/agreement"
                                             "concept:legal/employee"]}
                         :evidence []}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 2 (get-in stub [:discovery-provenance :axioms-emitted]))
            "Both string-typed axioms ingested")
        (let [axioms (rm/get-axioms ctx oid)]
          (is (= #{:functional :transitive}
                 (get-in axioms [:characteristics "supervises"]))
              "String characteristic flags coerced to keyword enum")
          (is (some? (get-in axioms [:disjointness "concept:legal/agreement"]))
              "String axiom-type coerced + routed"))))))

(deftest compile-discovery-source-accepts-owl-standard-axiom-vocab
  (testing "The discovery model gravitates to OWL/RDF-standard term
            names. These shapes are VERBATIM from a real live discovery
            run: axiom-type \"disjointClasses\" with body {:classes ...},
            and \"transitiveProperty\" with body {:property ...} and NO
            explicit :characteristic (the flag is encoded in the term).
            Both must route + land."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type "disjointClasses"
                         :body {:classes ["policy:Employee" "policy:Onboarding"]}
                         :evidence [{:source "Section 4"
                                     :quote "mutually exclusive categories"}]}
                        {:axiom-type "transitiveProperty"
                         :body {:property "supervises"}
                         :evidence [{:source "Section 4"
                                     :quote "Supervision is transitive"}]}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 2 (get-in stub [:discovery-provenance :axioms-emitted]))
            "Both OWL-vocab axioms ingested")
        (let [axioms (rm/get-axioms ctx oid)]
          (is (= #{"policy:Onboarding"}
                 (get-in axioms [:disjointness "policy:Employee"]))
              "disjointClasses → disjointness via :classes synonym")
          (is (contains? (get-in axioms [:characteristics "supervises"]) :transitive)
              "transitiveProperty term → :transitive flag on :property predicate"))))))

(deftest compile-discovery-source-accepts-owl-subproperty-and-chain-vocab
  (testing "OWL-standard subPropertyOf + propertyChainAxiom term names
            and their body-field synonyms route correctly."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type "subPropertyOf"
                         :body {:sub "supervises" :super "manages"}
                         :evidence []}
                        {:axiom-type "propertyChainAxiom"
                         :body {:properties ["supervises" "supervises"]
                                :derived "indirectly-supervises"}
                         :evidence []}])
            stub (ontology/compile-discovery-source! ctx oid out)]
        (is (= 2 (get-in stub [:discovery-provenance :axioms-emitted])))
        (let [axioms (rm/get-axioms ctx oid)]
          (is (= "manages" (get-in axioms [:sub-property-of "supervises"])))
          (is (= ["supervises" "supervises"]
                 (get-in axioms [:chains "indirectly-supervises"]))))))))

(deftest compile-discovery-source-rejects-unknown-axiom-type-loudly
  (testing "Adversarial: an axiom-type with no S07 command routing target
            must fail LOUDLY — no silent drop, no fabricated axiom
            (Disciplines #5)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :closure          ;; not a real axiom family
                         :body {:set [:a :b]}
                         :evidence []}])]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"unknown axiom-type"
              (ontology/compile-discovery-source! ctx oid out)))
        ;; Nothing fabricated: no axioms landed for the ontology-id.
        (is (nil? (rm/get-axioms ctx oid))
            "No partial / fabricated axiom landed after the loud failure")))))

(deftest compile-discovery-source-rejects-unknown-characteristic-loudly
  (testing "Adversarial: a characteristic flag outside the
            [:functional :transitive :symmetric] enum must fail LOUDLY —
            never coerced to a default, never dropped."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :property-characteristic
                         :body {:predicate "supervises"
                                :characteristic ["reflexive"]}  ;; not in the enum
                         :evidence []}])]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"unknown characteristic"
              (ontology/compile-discovery-source! ctx oid out)))))))

(deftest compile-discovery-source-rejects-malformed-axiom-draft-loudly
  (testing "Adversarial: an axiom-draft missing :axiom-type or :body raises
            ex-info — NO silent skip (Disciplines #5)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            no-type (assoc sample-discovery-output
                           :emitted-axioms
                           [{:body {:class-uris ["a" "b"]}}])
            no-body (assoc sample-discovery-output
                           :emitted-axioms
                           [{:axiom-type :disjointness}])]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed axiom-draft"
              (ontology/compile-discovery-source! ctx oid no-type)))
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"malformed axiom-draft"
              (ontology/compile-discovery-source! ctx oid no-body)))))))

(deftest compile-discovery-source-surfaces-command-anomaly-loudly
  (testing "Adversarial: if the routed S07 command itself rejects the body
            (e.g. a singleton class-uris set the Malli gate refuses), the
            adapter raises rather than swallowing — root cause visible."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            out (assoc sample-discovery-output
                       :emitted-axioms
                       [{:axiom-type :disjointness
                         :body {:class-uris ["only-one"]}  ;; singleton — schema rejects
                         :evidence []}])]
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"axiom emission anomaly"
              (ontology/compile-discovery-source! ctx oid out)))))))

(deftest compile-discovery-source-zero-axioms-reports-zero-emitted
  (testing "With no axiom-drafts, provenance reports zero emitted (and the
            old :axioms-skipped key is gone)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            stub (ontology/compile-discovery-source! ctx oid sample-discovery-output)]
        (is (= 0 (get-in stub [:discovery-provenance :axioms-emitted])))
        (is (not (contains? (:discovery-provenance stub) :axioms-skipped)))))))
