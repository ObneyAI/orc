(ns ai.obney.orc.ontology.v18-referential-integrity-test
  "V18 — referential integrity as an always-on STRUCTURAL INVARIANT.

   V17 built a graph with NO supplied validation shapes, so the
   shape-gated `validate-stage` short-circuited to `{:skipped? true}`
   and 119 of 249 relationship edges referenced concept URIs that were
   never minted — those dangling edges survived into the final artifact
   while the build reported success.

   The fix is domain-agnostic engine behavior in the deterministic
   COMPILE path (`compile-discovery-source!`), NOT behind the optional
   validate-stage:

     1. Scan every relationship draft. For any :source-uri/:target-uri
        not in the minted concept set, auto-mint a minimal IMPLIED
        concept (low-confidence / flagged-for-enrichment; label derived
        GENERICALLY from the URI's own id segment).
     2. If the dangling URI is a near-variant of an existing concept URI
        (different identifier encoding), record it as an AMBIGUITY via
        the general structural-similarity path (reusing the S12
        dedup-cascade primitives) rather than silently minting a twin.
     3. Surface counts (implied minted / ambiguities / unresolved); a
        graph that still carries truly-dangling edges does NOT report a
        clean success.

   Discipline: tests go through the PUBLIC interface
   (`ontology/compile-discovery-source!`) and assert behavior via the
   real Grain projection — never internal helpers. Real in-memory event
   store; no mocks; no try/catch swallowing.

   The fixtures use structurally-similar URIs (NOT a CIP/SOC code-format
   rule) so the near-variant detection is exercised generally, per
   Discipline 12 (domain-agnostic)."
  (:require [clojure.test :refer [deftest testing is]]
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
;; Test context (mirrors the s18 suite harness)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v18-test-" (random-uuid))
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

(defn- concepts-by-uri [ctx oid]
  (into {} (map (juxt :uri identity))
        (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))))

;; A discovery output mirroring the V17 dangling shape: relationships whose
;; endpoints were never minted as concepts. The URI ids here are arbitrary /
;; domain-neutral (entity:* / record:*) so the behavior is exercised WITHOUT
;; relying on any CIP/SOC/education code format.
(def ^:private dangling-output
  {:status :emitted-drafts
   :emitted-concepts
   [{:uri "entity:alpha"
     :label "Alpha"
     :description "An explicitly-discovered entity."
     :scope :custom
     :evidence [{:source "src" :quote "Alpha appears here"}]}]
   :emitted-relationships
   ;; entity:alpha exists; entity:beta and entity:gamma do NOT — they dangle.
   [{:source-uri "entity:alpha" :target-uri "entity:beta"
     :predicate "links-to"
     :confidence-class :extracted
     :evidence [{:source "src" :quote "Alpha links to Beta"}]}
    {:source-uri "entity:gamma" :target-uri "entity:alpha"
     :predicate "links-to"
     :confidence-class :extracted
     :evidence [{:source "src" :quote "Gamma links to Alpha"}]}]
   :emitted-axioms []
   :rlm-trace []
   :patterns-offered 5})

;; =============================================================================
;; 1. Every endpoint resolves after compile (the V17 condition)
;; =============================================================================

(deftest compile-auto-mints-implied-concepts-for-dangling-endpoints
  (testing "After compile, every relationship endpoint resolves — the
            missing ones (entity:beta, entity:gamma) were auto-minted as
            implied concepts. This is the V17 condition: NO supplied
            shapes, yet referential integrity holds."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            stub (ontology/compile-discovery-source! ctx oid dangling-output)
            by-uri (concepts-by-uri ctx oid)]
        ;; The one explicit concept + two implied concepts = 3.
        (is (contains? by-uri "entity:alpha") "explicit concept landed")
        (is (contains? by-uri "entity:beta") "dangling target auto-minted")
        (is (contains? by-uri "entity:gamma") "dangling source auto-minted")
        ;; Every edge endpoint now resolves to a concept.
        (let [uris (set (keys by-uri))
              rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
          (is (every? (fn [r] (and (contains? uris (:source-uri r))
                                   (contains? uris (:target-uri r))))
                      rels)
              "every-edge-endpoint-resolves is TRUE")
          (is (= 2 (count rels)) "both relationships landed"))
        ;; The provenance must surface that integrity was repaired and 0 remain.
        (is (= 2 (get-in stub [:discovery-provenance :implied-concepts-minted]))
            "two implied concepts minted")
        (is (= 0 (get-in stub [:discovery-provenance :unresolved-endpoints]))
            "no endpoint left unresolved")
        (is (true? (get-in stub [:discovery-provenance :every-edge-endpoint-resolves?]))
            "provenance reports referential integrity holds")))))

;; =============================================================================
;; 2. Auto-minted concepts are flagged implied / low-confidence
;; =============================================================================

(deftest implied-concepts-are-flagged-low-confidence
  (testing "Auto-minted concepts are distinguishable from explicitly
            discovered concepts: they carry an :implied? flag and an
            enrichment-pending marker, and their label is derived
            GENERICALLY from the URI id segment (not a domain label)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            _ (ontology/compile-discovery-source! ctx oid dangling-output)
            by-uri (concepts-by-uri ctx oid)
            beta (get by-uri "entity:beta")
            alpha (get by-uri "entity:alpha")]
        (is (true? (get-in beta [:attributes :implied?]))
            "implied concept flagged :implied? true")
        (is (true? (get-in beta [:attributes :enrichment-pending?]))
            "implied concept flagged for enrichment")
        ;; The explicit concept must NOT be flagged implied — they are
        ;; distinguishable.
        (is (not (get-in alpha [:attributes :implied?]))
            "explicitly-discovered concept is NOT flagged implied")
        ;; Label derived generically from the id segment of the URI.
        (is (string? (:label beta)))
        (is (not (clojure.string/blank? (:label beta))))
        ;; The generic label is derived from the URI's own id segment
        ;; ("beta"), case-insensitively — NOT a fabricated domain label.
        (is (clojure.string/includes? (clojure.string/lower-case (:label beta)) "beta")
            "implied label derived from the URI id segment")))))

;; =============================================================================
;; 3. Near-variant dangling URI → ambiguity (NOT a silent twin)
;; =============================================================================

(deftest near-variant-dangling-uri-surfaced-as-ambiguity
  (testing "When a dangling endpoint is a near-variant of an EXISTING
            concept URI (different identifier encoding), it is recorded
            as an AMBIGUITY for the dedup/alignment layer rather than
            silently minting a twin. The near-variant detection is
            structural (reuses the S12 dedup-cascade similarity), NOT a
            hardcoded code-format/truncation rule — the fixture uses
            structurally-similar generic URIs."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; The minted concept uses "record:identifier-000123456" and a
            ;; relationship dangles to "record:identifier-0123456" — a
            ;; near-variant by an extra/dropped digit (an encoding variant),
            ;; structurally very close. This must surface as an ambiguity,
            ;; not a twin mint.
            out {:status :emitted-drafts
                 :emitted-concepts
                 [{:uri "record:identifier-000123456"
                   :label "Canonical Record"
                   :description "The canonical record."
                   :scope :custom
                   :evidence [{:source "s" :quote "record 000123456"}]}]
                 :emitted-relationships
                 [{:source-uri "record:identifier-000123456"
                   :target-uri "record:identifier-0123456"  ;; near-variant — dangles
                   :predicate "supersedes"
                   :confidence-class :extracted
                   :evidence [{:source "s" :quote "supersedes the prior encoding"}]}]
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}
            stub (ontology/compile-discovery-source! ctx oid out)
            by-uri (concepts-by-uri ctx oid)]
        ;; The ambiguity is surfaced with both URIs + a structural-similarity
        ;; basis (the existing canonical it likely aliases).
        (is (= 1 (get-in stub [:discovery-provenance :ambiguities-flagged]))
            "one ambiguity flagged")
        (let [amb (first (get-in stub [:discovery-provenance :ambiguities]))]
          (is (= "record:identifier-0123456" (:dangling-uri amb))
              "ambiguity records the dangling URI")
          (is (= "record:identifier-000123456" (:near-existing-uri amb))
              "ambiguity records the near-variant existing URI it likely aliases")
          (is (number? (:similarity amb))
              "ambiguity carries a structural similarity score"))
        ;; The dangling URI is STILL auto-minted as an implied concept so the
        ;; edge resolves (no false green / no silent loss) — but it is ALSO
        ;; flagged as an ambiguity for the alignment layer to resolve. It is
        ;; NOT silently treated as a brand-new unrelated entity.
        (is (contains? by-uri "record:identifier-0123456")
            "endpoint still resolves (implied concept minted)")
        (is (true? (get-in stub [:discovery-provenance :every-edge-endpoint-resolves?]))
            "every endpoint resolves")
        (is (true? (get-in (get by-uri "record:identifier-0123456")
                           [:attributes :ambiguous?]))
            "the near-variant implied concept is flagged :ambiguous? for alignment")))))

;; =============================================================================
;; 4. A graph with truly-unresolvable endpoints does NOT report a clean success
;; =============================================================================

(deftest no-false-green-when-endpoint-cannot-resolve
  (testing "A relationship whose endpoint cannot be minted (e.g. a blank
            URI that the create-concept command would reject) does NOT
            pass through as a clean success — the unresolved count is
            surfaced and every-edge-endpoint-resolves? is false. No false
            green (Disciplines #5)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; A relationship-draft is well-formed enough to pass the
            ;; relationship validator (non-blank source/target/predicate),
            ;; but the target URI is whitespace-only after trim — there is
            ;; no resolvable id to mint a concept for. This must surface as
            ;; an UNRESOLVED endpoint, not a fabricated concept.
            out {:status :emitted-drafts
                 :emitted-concepts
                 [{:uri "entity:alpha" :label "Alpha"
                   :description "x" :scope :custom
                   :evidence [{:source "s" :quote "Alpha"}]}]
                 :emitted-relationships
                 [{:source-uri "entity:alpha" :target-uri "   "
                   :predicate "links-to"
                   :confidence-class :extracted
                   :evidence [{:source "s" :quote "dangles to nothing"}]}]
                 :emitted-axioms []
                 :rlm-trace []
                 :patterns-offered 5}]
        ;; The unresolvable endpoint must be surfaced loudly — either as an
        ;; explicit unresolved count with a falsy integrity flag, OR a loud
        ;; ex-info. Either way: NO clean :ingested success that hides it.
        (let [result (try
                       (ontology/compile-discovery-source! ctx oid out)
                       (catch clojure.lang.ExceptionInfo e
                         {::threw (ex-data e) ::msg (.getMessage e)}))]
          (if (::threw result)
            (is (re-find #"(?i)unresolved|dangl|integrity" (::msg result))
                "loud failure names the unresolved/dangling endpoint")
            (do
              (is (pos? (get-in result [:discovery-provenance :unresolved-endpoints]))
                  "unresolved endpoint count is surfaced (> 0)")
              (is (false? (get-in result [:discovery-provenance :every-edge-endpoint-resolves?]))
                  "integrity flag is FALSE — no false green"))))))))

;; =============================================================================
;; 5. Regression: a clean graph with all endpoints present is unaffected
;; =============================================================================

(deftest clean-graph-with-all-endpoints-needs-no-implied-mints
  (testing "Regression: when every relationship endpoint is already a
            minted concept, NO implied concepts are minted, NO ambiguity
            is flagged, and the existing provenance counts are unchanged.
            Referential integrity holds trivially."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            clean {:status :emitted-drafts
                   :emitted-concepts
                   [{:uri "entity:alpha" :label "Alpha" :description "a"
                     :scope :custom :evidence [{:source "s" :quote "Alpha"}]}
                    {:uri "entity:beta" :label "Beta" :description "b"
                     :scope :custom :evidence [{:source "s" :quote "Beta"}]}]
                   :emitted-relationships
                   [{:source-uri "entity:alpha" :target-uri "entity:beta"
                     :predicate "links-to" :confidence-class :extracted
                     :evidence [{:source "s" :quote "Alpha links Beta"}]}]
                   :emitted-axioms []
                   :rlm-trace []
                   :patterns-offered 5}
            stub (ontology/compile-discovery-source! ctx oid clean)
            by-uri (concepts-by-uri ctx oid)]
        (is (= 2 (get-in stub [:discovery-provenance :concepts-emitted]))
            "exactly the two explicit concepts — no extras")
        (is (= 0 (get-in stub [:discovery-provenance :implied-concepts-minted]))
            "no implied concepts minted for a clean graph")
        (is (= 0 (get-in stub [:discovery-provenance :ambiguities-flagged]))
            "no ambiguities flagged for a clean graph")
        (is (= 0 (get-in stub [:discovery-provenance :unresolved-endpoints])))
        (is (true? (get-in stub [:discovery-provenance :every-edge-endpoint-resolves?])))
        (is (= 2 (count by-uri)) "exactly two concepts in the graph")
        (is (not (get-in (get by-uri "entity:alpha") [:attributes :implied?]))
            "explicit concept not flagged implied")))))

;; =============================================================================
;; 6. Implied concept does not duplicate an EXISTING graph concept
;; =============================================================================

(deftest endpoint-resolving-to-pre-existing-graph-concept-is-not-reminted
  (testing "When a dangling endpoint URI EXACTLY matches a concept already
            in the graph (from a prior source / prior compile), it
            resolves against that existing concept — NOT re-minted as an
            implied twin. Referential integrity is graph-wide, not just
            within-batch."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; First compile mints entity:beta as an explicit concept.
            _ (ontology/compile-discovery-source!
                ctx oid
                {:status :emitted-drafts
                 :emitted-concepts
                 [{:uri "entity:beta" :label "Beta" :description "b"
                   :scope :custom :evidence [{:source "s" :quote "Beta"}]}]
                 :emitted-relationships [] :emitted-axioms []
                 :rlm-trace [] :patterns-offered 5})
            ;; Second compile references entity:beta from a NEW source whose
            ;; own concept-drafts do NOT include it — but it already exists.
            stub (ontology/compile-discovery-source!
                   ctx oid
                   {:status :emitted-drafts
                    :emitted-concepts
                    [{:uri "entity:alpha" :label "Alpha" :description "a"
                      :scope :custom :evidence [{:source "s" :quote "Alpha"}]}]
                    :emitted-relationships
                    [{:source-uri "entity:alpha" :target-uri "entity:beta"
                      :predicate "links-to" :confidence-class :extracted
                      :evidence [{:source "s" :quote "Alpha links Beta"}]}]
                    :emitted-axioms [] :rlm-trace [] :patterns-offered 5})
            by-uri (concepts-by-uri ctx oid)]
        (is (= 0 (get-in stub [:discovery-provenance :implied-concepts-minted]))
            "entity:beta already exists in the graph — no implied re-mint")
        (is (= 2 (count by-uri)) "still exactly two concepts (no twin)")
        (is (not (get-in (get by-uri "entity:beta") [:attributes :implied?]))
            "the pre-existing concept is not retroactively flagged implied")
        (is (true? (get-in stub [:discovery-provenance :every-edge-endpoint-resolves?])))))))
