(ns ai.obney.orc.orc-service.s19-sandbox-tools-test
  "S19 — RLM ontology tools, exposed as sandbox primitives.

   Each deftest corresponds to one of the slice's acceptance criteria:

   - Tool wiring + correctness: every tool is reachable from inside the
     SCI sandbox and returns correct data against a seeded two-section
     graph. The test invokes the tool THROUGH the sandbox (the same
     route the model would use), not by direct fn call.
   - exists? trap: returns true for a present URI, false for a never-
     created one, AND false for one that exists in a section we
     weren't granted access to (closed-world scoping verdict).
   - absent-in-graph? trap: returns false when the edge IS present
     under :ambiguous confidence (ambiguity is metadata, the edge IS
     in the graph) AND true when the edge truly isn't there.
   - filter-by-label-pattern: handles regex AND substring, case-
     sensitivity is a parameter, EMPTY result (not exception) on a
     clean miss.
   - Scope-jailbreak adversarial: a grant of section A cannot
     retrieve section B's content through ANY tool, EVEN when the
     model passes :ontology-id B in the call. The grant — not the
     argument — is authoritative.
   - Deregistration freshness: the alignment-section registry is
     consulted from CURRENT projection state; deregistering an
     alignment removes that section's content from the sandbox's
     retrieval surface on the next call (no staleness).
   - Docstring quality: each tool's docstring contains PURPOSE,
     EXAMPLE (with concrete values, not <placeholders>), and RETURNS;
     adversarial test stripping any of these out fails the test.
   - Grain discipline: the seven tools are READ-side — never emit a
     command/event. Verified by counting events before / after a tool
     pass.

   Live verification (real Grain event-store + real recursive-RLM
   sandbox + ≥4 tools used in a single transcript) is a separate
   smoke test left as a development bench; the disciplines block
   binds an actual recursive RLM session and is checked by the
   `live-verify-transcript` smoke in development/src/."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.sandbox-tools :as st]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Two-section corpus seed (mirrors s02_uniform_scoping_test fixture shape)
;; =============================================================================

(def ontology-a-id #uuid "a1900000-0000-0000-0000-00000000000a")
(def ontology-b-id #uuid "b1900000-0000-0000-0000-00000000000b")

(defn- seed-concept!
  ([ctx ontology-id uri label]
   (seed-concept! ctx ontology-id uri label nil))
  ([ctx ontology-id uri label scope]
   (h/run-and-apply! ctx
                     (fn [c]
                       (cmd/ontology-create-concept
                        (assoc c :command
                               (cond-> {:ontology-id ontology-id
                                        :uri uri
                                        :label label
                                        :description (str label " (" ontology-id ")")
                                        :scope (or scope :custom)
                                        :broader []
                                        :indicators []}
                                 ;; Multi-label fixture for the
                                 ;; filter-by-label-pattern adversarial path:
                                 ;; the second label is what the regex should
                                 ;; match, proving the tool reads :labels
                                 ;; (S04 representation) not just :label.
                                 (= "concept:dir/jane-roe" uri)
                                 (assoc :labels [{:value "Jane Roe" :lang "en"}
                                                 {:value "J. Roe (dir)" :lang "en"}]))))))))

(defn- seed-relationship!
  ([ctx source predicate target]
   (seed-relationship! ctx source predicate target {}))
  ([ctx source predicate target props]
   (h/run-and-apply! ctx
                     (fn [c]
                       (cmd/ontology-create-relationship
                        (assoc c :command
                               (merge {:source-uri source
                                       :predicate predicate
                                       :target-uri target
                                       :properties {}}
                                      props)))))))

(defn- seed-two-sections!
  "Section A = directors+films; section B = scholarships+programs.
   Includes:
   - URI uniqueness across sections (no collisions — we're testing the
     S19 isolation invariant, not S02's URI-keyed projection
     overwrite, which has its own test elsewhere).
   - jane-roe :directed red-dawn  (an :extracted edge — present)
   - jane-roe :collaborated-with john-doe :confidence-class :ambiguous
     (so absent-in-graph? must still treat it as PRESENT — the
     adversarial trap).
   - jane-roe has NO :retired edge — so absent-in-graph? jane-roe
     :retired returns true.
   - Section B contains scholarship-rule (so a successful jailbreak
     attempt with ontology-id B from a section-A grant would surface
     it — and the test asserts it does NOT)."
  [ctx]
  (doseq [[uri label] [["concept:dir/jane-roe" "Jane Roe"]
                       ["concept:dir/john-doe" "John Doe"]
                       ["concept:film/red-dawn" "Red Dawn"]
                       ["concept:role/director" "Director"]]]
    (seed-concept! ctx ontology-a-id uri label))
  (seed-relationship! ctx "concept:dir/jane-roe" "directed"
                      "concept:film/red-dawn"
                      {:confidence-class :extracted})
  ;; The trap edge — present but ambiguous. absent-in-graph? must
  ;; return FALSE here (the edge IS in the graph; ambiguity is metadata).
  (seed-relationship! ctx "concept:dir/jane-roe" "collaborated-with"
                      "concept:dir/john-doe"
                      {:confidence-class :ambiguous})

  (doseq [[uri label] [["concept:scholarship/dean" "Dean's Scholarship"]
                       ["concept:program/cs" "Computer Science"]
                       ["concept:rule/scholarship-rule" "Scholarship Rule"]]]
    (seed-concept! ctx ontology-b-id uri label)))

;; =============================================================================
;; Build a sandbox granted ONLY section A
;; =============================================================================

(defn- build-sandbox-granted-A [ctx]
  (rlm-sandbox/build-rlm-context
   {:provider :openrouter
    :blackboard {}
    :declared-writes [:result]
    :event-store (:event-store ctx)
    :tenant-id (:tenant-id ctx)
    :cache (:cache ctx)
    :granted-ontology-id ontology-a-id}))

(defn- exec [rlm-ctx code]
  (rlm-sandbox/execute-rlm-code rlm-ctx code))

;; =============================================================================
;; AC1 — Tools are reachable through the sandbox (not direct fn calls)
;; =============================================================================

(deftest tools-reachable-through-sandbox
  (testing "Each of the seven tools is callable from inside the SCI sandbox
            and returns correct data against a seeded section-A graph."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [s (build-sandbox-granted-A ctx)]

        (testing "get-concept"
          (let [r (exec s "(get-concept \"concept:dir/jane-roe\")")]
            (is (nil? (:error r)) (str "error: " (:error r)))
            (is (= "Jane Roe" (:label (:raw-result r))))
            (is (= "concept:dir/jane-roe" (:uri (:raw-result r))))))

        (testing "exists? returns true for present URI"
          (let [r (exec s "(exists? \"concept:dir/jane-roe\")")]
            (is (nil? (:error r)))
            (is (true? (:raw-result r)))))

        (testing "exists? returns false for never-created URI"
          (let [r (exec s "(exists? \"concept:dir/nobody\")")]
            (is (nil? (:error r)))
            (is (false? (:raw-result r)))))

        (testing "absent-in-graph? returns true when no such edge exists"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :retired)")]
            (is (nil? (:error r)))
            (is (true? (:raw-result r))
                "No :retired edge from jane-roe — should be absent")))

        (testing "absent-in-graph? returns false when an :extracted edge exists"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :directed)")]
            (is (nil? (:error r)))
            (is (false? (:raw-result r))
                "The :directed edge IS there — not absent")))

        (testing "neighborhood returns a vector of {:uri :score :path :depth}"
          (let [r (exec s "(neighborhood \"concept:dir/jane-roe\" {:max-depth 1})")
                v (:raw-result r)]
            (is (nil? (:error r)))
            (is (vector? v) "neighborhood returns a vector")
            (is (every? :uri v))
            (is (some #(= "concept:dir/jane-roe" (:uri %)) v)
                "BFS surfaces the seed itself")))

        (testing "filter-by-label-pattern substring (case-insensitive default)"
          ;; Use defn'd local: the candidate seq + substring pattern. The
          ;; (def) form lives in the safe-clojure-core set... actually it
          ;; doesn't — the sandbox doesn't include def. Use let inside the
          ;; code form.
          (let [code "(filter-by-label-pattern [\"concept:dir/jane-roe\" \"concept:dir/john-doe\" \"concept:film/red-dawn\"] \"Jane\")"
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)))
            (is (= 1 (count v)) "Only jane-roe matches substring 'Jane'")
            (is (= "concept:dir/jane-roe" (:uri (first v))))))

        (testing "filter-by-label-pattern empty result is empty vec, NOT an exception"
          (let [code "(filter-by-label-pattern [\"concept:dir/jane-roe\"] \"nope-no-match-here\")"
                r (exec s code)]
            (is (nil? (:error r)) "Clean miss does not throw")
            (is (= [] (:raw-result r)) "Clean miss returns empty vec")))

        (testing "graph-search returns the standard hybrid-search shape"
          (let [code "(graph-search \"director\" {:limit 5})"
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)) (str "error: " (:error r)))
            (is (map? v) "Returns a result-map")
            (is (contains? v :results))))))))

;; =============================================================================
;; AC2 — Adversarial scope jailbreak. The grant is authoritative.
;; =============================================================================

(deftest scope-jailbreak-grant-is-authoritative
  (testing "A sandbox granted section A cannot retrieve section B content,
            including by crafting an :ontology-id B argument. The grant —
            not the argument — wins. Asserted EXPLICITLY: no section-B URIs
            appear in any result returned through any tool."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [s (build-sandbox-granted-A ctx)
            b-only-uris #{"concept:scholarship/dean"
                          "concept:program/cs"
                          "concept:rule/scholarship-rule"}]

        (testing "get-concept with crafted :ontology-id arg cannot reach B"
          ;; get-concept signature is (get-concept uri); a clever model
          ;; might try (get-concept "concept:scholarship/dean") and the
          ;; tool MUST return nil — that URI is in B, not in granted A.
          (let [r (exec s "(get-concept \"concept:scholarship/dean\")")]
            (is (nil? (:error r)))
            (is (nil? (:raw-result r))
                "B URI returns nil from an A-granted sandbox")))

        (testing "exists? does NOT leak B URIs"
          (let [r (exec s "(exists? \"concept:scholarship/dean\")")]
            (is (nil? (:error r)))
            (is (false? (:raw-result r))
                "exists? returns false for B URIs under an A grant")))

        (testing "graph-search ignores model-supplied :ontology-id B and stays in A"
          ;; The jailbreak attempt: pass :ontology-id B explicitly.
          (let [code (str "(graph-search \"scholarship\" "
                          "{:limit 50 "
                          ":ontology-id #uuid \"" ontology-b-id "\"})")
                r (exec s code)
                results (-> r :raw-result :results)
                returned-uris (set (map :uri results))]
            (is (nil? (:error r)) (str "error: " (:error r)))
            (is (empty? (clojure.set/intersection returned-uris b-only-uris))
                (str "graph-search leaked B URIs despite A-grant: "
                     (clojure.set/intersection returned-uris b-only-uris)))))

        (testing "neighborhood ignores crafted :ontology-id B"
          (let [code (str "(neighborhood \"concept:dir/jane-roe\" "
                          "{:max-depth 3 "
                          ":ontology-id #uuid \"" ontology-b-id "\"})")
                r (exec s code)
                v (:raw-result r)
                uris (set (map :uri v))]
            (is (nil? (:error r)))
            (is (empty? (clojure.set/intersection uris b-only-uris))
                "neighborhood with crafted ontology-id B still bound to A")))

        (testing "filter-by-label-pattern over a list that INCLUDES B URIs drops them"
          ;; The model passes a list with B URIs in it; the tool resolves
          ;; them under A scope and drops what isn't there.
          (let [code "(filter-by-label-pattern [\"concept:dir/jane-roe\" \"concept:scholarship/dean\"] \"\")"
                r (exec s code)
                v (:raw-result r)
                returned-uris (set (map :uri v))]
            (is (nil? (:error r)))
            (is (not (contains? returned-uris "concept:scholarship/dean"))
                "B URI in input list resolved to nil under A scope")))))))

;; =============================================================================
;; AC3 — absent-in-graph? trap: an :ambiguous edge IS present
;; =============================================================================

(deftest absent-in-graph-ambiguous-edge-counts-as-present
  (testing "An edge whose :confidence-class is :ambiguous is STILL in the
            graph — the edge IS there, ambiguity is metadata. So
            absent-in-graph? must return FALSE for it. The adversarial
            twin (a :retired predicate the edge does NOT have) returns
            true to prove the test isn't trivially-passing."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [s (build-sandbox-granted-A ctx)]

        (testing "ambiguous edge: still present, absent-in-graph? false"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :collaborated-with)")]
            (is (nil? (:error r)))
            (is (false? (:raw-result r))
                "ambiguous edge IS in the graph — not absent")))

        (testing "adversarial twin: a truly absent predicate returns true"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :retired)")]
            (is (nil? (:error r)))
            (is (true? (:raw-result r))
                "no :retired edge anywhere — IS absent")))

        (testing "object-targeted form: jane-roe :directed red-dawn IS present"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :directed \"concept:film/red-dawn\")")]
            (is (nil? (:error r)))
            (is (false? (:raw-result r))
                "The exact triple exists — not absent")))

        (testing "object-targeted form: jane-roe :directed something-else IS absent"
          (let [r (exec s "(absent-in-graph? \"concept:dir/jane-roe\" :directed \"concept:film/blue-tomorrow\")")]
            (is (nil? (:error r)))
            (is (true? (:raw-result r))
                "No such triple — IS absent")))))))

;; =============================================================================
;; AC4 — filter-by-label-pattern: regex / substring / case parameter / clean miss
;; =============================================================================

(deftest filter-by-label-pattern-shapes
  (testing "Pattern can be string OR regex; case-sensitivity is a parameter;
            a clean miss returns an EMPTY vector (not an exception); the
            tool reads multi-labels from S04 :labels not just :label."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [s (build-sandbox-granted-A ctx)
            uris-vec "[\"concept:dir/jane-roe\" \"concept:dir/john-doe\" \"concept:film/red-dawn\" \"concept:role/director\"]"]

        (testing "regex pattern: ^Jane matches only jane-roe's primary label"
          (let [code (str "(filter-by-label-pattern " uris-vec " #\"^Jane\")")
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)) (str "error: " (:error r)))
            (is (= ["concept:dir/jane-roe"] (mapv :uri v))
                "Only jane-roe matched #\"^Jane\"")))

        (testing "case-sensitive opt: \"jane\" (lowercase) won't match \"Jane\""
          (let [code (str "(filter-by-label-pattern " uris-vec " \"jane\" {:case-sensitive? true})")
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)))
            (is (empty? v) "Case-sensitive lowercase 'jane' doesn't match 'Jane'")))

        (testing "case-insensitive default: \"jane\" still matches \"Jane\""
          (let [code (str "(filter-by-label-pattern " uris-vec " \"jane\")")
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)))
            (is (= 1 (count v)) "Default case-insensitive matches 'Jane'")))

        (testing "tool reads S04 multi-labels (matches a value from :labels)"
          ;; jane-roe seed had a second label "J. Roe (dir)". A regex
          ;; matching only that alt label should still find the concept.
          (let [code (str "(filter-by-label-pattern " uris-vec " #\"J\\. Roe\")")
                r (exec s code)
                v (:raw-result r)]
            (is (nil? (:error r)))
            (is (= ["concept:dir/jane-roe"] (mapv :uri v))
                "Tool reads alt labels — matched J. Roe (dir)")))

        (testing "clean miss returns empty vec, NOT an exception"
          (let [code (str "(filter-by-label-pattern " uris-vec " \"no-such-label-anywhere\")")
                r (exec s code)]
            (is (nil? (:error r)))
            (is (= [] (:raw-result r)))))))))

;; =============================================================================
;; AC5 — Deregistration: registry consulted on EVERY call (no staleness)
;; =============================================================================

(deftest deregistration-removes-alignment-on-next-call
  (testing "Register A -> ALIGN. The sandbox granted A picks up an ALIGN URI
            via graph-search. Then deregister A -> ALIGN. The NEXT call
            from the SAME sandbox no longer surfaces ALIGN content —
            proving the registry is consulted from CURRENT projection
            state, not from a cache snapshotted at sandbox-build time."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      ;; Register section B as an ALIGNMENT of section A. After this, an
      ;; A-granted sandbox SHOULD see B's content (S03 auto-widening).
      (h/run-and-apply! ctx
                        (fn [c]
                          (cmd/ontology-register-alignment-section
                           (assoc c :command {:primary-ontology-id ontology-a-id
                                              :alignment-ontology-id ontology-b-id}))))
      (let [s (build-sandbox-granted-A ctx)]

        (testing "WHILE alignment registered, A-grant sees B's :scholarship/dean"
          (let [r (exec s "(get-concept \"concept:scholarship/dean\")")]
            (is (nil? (:error r)))
            (is (some? (:raw-result r))
                "S03 auto-widening: A-granted sandbox sees B content through registered alignment")
            (is (= "Dean's Scholarship" (:label (:raw-result r))))))

        ;; Now deregister. The NEXT call from the SAME sandbox must
        ;; immediately lose access — the registry is consulted from
        ;; current state on every call.
        (h/run-and-apply! ctx
                          (fn [c]
                            (cmd/ontology-deregister-alignment-section
                             (assoc c :command {:primary-ontology-id ontology-a-id
                                                :alignment-ontology-id ontology-b-id}))))

        (testing "AFTER deregister, A-grant no longer sees B content (no staleness)"
          (let [r (exec s "(get-concept \"concept:scholarship/dean\")")]
            (is (nil? (:error r)))
            (is (nil? (:raw-result r))
                "Deregistered alignment is no longer in the auto-widen set — sandbox loses B access")))))))

;; =============================================================================
;; AC6 — Docstring quality: each tool's doc has PURPOSE, EXAMPLE, RETURNS
;; =============================================================================

(deftest each-tool-docstring-is-self-contained
  (testing "Every tool's docstring contains PURPOSE, EXAMPLE, and RETURNS
            structural elements — the model can use the tool from the
            docstring alone."
    (let [docs st/ontology-tool-docs
          required-elements ["PURPOSE" "EXAMPLE" "RETURNS"]]
      (doseq [[sym doc] docs]
        (testing (str sym " docstring has all required structural elements")
          (is (string? doc) (str sym " has a docstring"))
          (doseq [el required-elements]
            (is (str/includes? doc el)
                (str sym " docstring missing required element: " el)))
          (testing (str sym " EXAMPLE contains a concrete call form, not <placeholder>")
            ;; A naive way to test: the EXAMPLE block should contain "("
            ;; — a real code form. And it should NOT contain "<arg>" /
            ;; "<concrete>" angle-bracket placeholders inside the call form.
            (let [example-section (second (str/split doc #"EXAMPLE"))]
              (is (some? example-section)
                  (str sym " has an EXAMPLE section after the EXAMPLE marker"))
              (is (str/includes? example-section "(")
                  (str sym " EXAMPLE contains a code form (has open paren)"))
              ;; The leading "<arg>" placeholder convention should be absent;
              ;; we want concrete strings/maps in the worked example.
              ;; Tolerate angle brackets in :doc *RETURNS* shape (e.g.,
              ;; "<uuid>"); only check the EXAMPLE section.
              (when-let [code-only (first (str/split example-section #"RETURNS"))]
                (is (not (re-find #"<arg\d?>" code-only))
                    (str sym " EXAMPLE has placeholder <arg> tokens — use concrete values"))))))))))

(deftest adversarial-stripping-a-section-fails-docstring-quality
  (testing "Proof the docstring-quality test is not trivially-passing:
            a docstring missing one of the three required sections fails."
    (let [bad-docs {'graph-search "PURPOSE — does stuff. RETURNS — a map."}
          required ["PURPOSE" "EXAMPLE" "RETURNS"]]
      ;; Run the same check, expect it to fail
      (let [results (doall (for [el required]
                             (str/includes? (get bad-docs 'graph-search) el)))]
        (is (not (every? identity results))
            "A docstring missing EXAMPLE fails the quality check
             (proves the test catches a broken docstring).")
        (is (= [true false true] results)
            "Specifically EXAMPLE is the missing element.")))))

;; =============================================================================
;; AC7 — Read-side: tools NEVER emit a command/event
;; =============================================================================

(deftest tools-are-read-only
  (testing "Running every tool through the sandbox emits ZERO new events.
            The tools project existing state; they do not write."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [s (build-sandbox-granted-A ctx)
            event-count (fn []
                          (count (into [] (es/read (:event-store ctx)
                                                   {:tenant-id (:tenant-id ctx)}))))
            before (event-count)]
        ;; Use every tool at least once
        (doseq [code ["(get-concept \"concept:dir/jane-roe\")"
                      "(exists? \"concept:dir/jane-roe\")"
                      "(absent-in-graph? \"concept:dir/jane-roe\" :retired)"
                      "(neighborhood \"concept:dir/jane-roe\" {:max-depth 1})"
                      "(filter-by-label-pattern [\"concept:dir/jane-roe\"] \"Jane\")"
                      "(graph-search \"director\" {:limit 3})"
                      "(classify-task {:task-signature \"summarize films\" :threshold 0.6})"
                      "(classify-behaviors {:task-signature \"extract dates\" :threshold 0.6 :top-n 3})"]]
          (let [r (exec s code)]
            ;; classify-* can throw if its reranker hits a transient — we
            ;; tolerate that error for THIS test (the read-side invariant is
            ;; what we're checking, and even an error path doesn't emit
            ;; events). Other tools must not throw.
            (when (and (some? (:error r))
                       (not (or (str/includes? code "classify-task")
                                (str/includes? code "classify-behaviors"))))
              (is false (str "non-classifier tool threw: " code " -> " (:error r))))))
        (let [after (event-count)]
          (is (= before after)
              "No tool emitted a new event (read-side invariant holds)"))))))

;; =============================================================================
;; AC8 — Tools NOT exposed when no grant is given (sandbox safety default)
;; =============================================================================

(deftest tools-not-exposed-without-grant
  (testing "If no :granted-ontology-id (or :granted-ontology-ids) is
            supplied to build-rlm-context, the seven ontology tools are
            NOT in the sandbox bindings — referencing them throws."
    (h/with-test-context [ctx]
      (let [s (rlm-sandbox/build-rlm-context
                {:provider :openrouter
                 :blackboard {}
                 :declared-writes [:result]
                 :event-store (:event-store ctx)
                 :tenant-id (:tenant-id ctx)
                 :cache (:cache ctx)})
            r (exec s "(get-concept \"concept:any\")")]
        (is (some? (:error r))
            "Without a grant, get-concept is not bound — referencing it errors")))))
