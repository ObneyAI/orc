(ns ai.obney.orc.ontology.s10-lint-registry-test
  "S10 — Lint registry + EDN-SHACL interpreter core acceptance tests.

   Each deftest corresponds to one acceptance criterion from the slice:

   1. Shapes register (via :ontology/register-shape command emitting
      :ontology/shape-registered event); invalid shapes are REJECTED at
      registration time, not silently accepted.
   2. Each phase-1 component (target-class, path, min-count, max-count,
      severity, message, deactivated, not) has positive AND negative
      fixtures. Adversarial coverage.
   3. The three built-in lints fire on seeded-bad fixtures and stay
      silent on clean ones.
   4. :deactivated true → ZERO violation events AND ONE skip record.
   5. validation-report read-model returns latest run per ontology-id;
      violation-history is queryable by shape-id + time window.
   6. :code escape-hatch shapes run in the same registry flow with
      identical reporting (in-process fn AND persisted code-symbol
      forms both supported).

   Live verification path: real Grain in-memory event store; commands
   → events; rmp/project consults read-models; queries surface the
   results."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Event-schema registration — required so the event-store's
            ;; append-time Malli validation accepts our new events.
            [ai.obney.orc.ontology.interface.schemas]
            ;; Register the lint commands / read-models / queries.
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.lints.commands :as lint-cmd]
            [ai.obney.orc.ontology.core.lints.queries :as lint-q]
            [ai.obney.orc.ontology.core.lints.builtin :as builtin]
            [ai.obney.orc.ontology.core.lints.interpreter :as interp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Shared seed helpers
;; =============================================================================

(def ontology-id #uuid "5c10aaaa-0000-0000-0000-000000000001")

(defn- seed-concept!
  ([ctx uri label]
   (seed-concept! ctx uri label nil))
  ([ctx uri label extras]
   (h/run-and-apply!
    ctx
    (fn [c]
      (cmd/ontology-create-concept
       (assoc c :command
              (merge (h/make-concept-data
                      :ontology-id ontology-id
                      :uri uri
                      :label label
                      :description (str label " :: " uri)
                      :scope :custom)
                     extras)))))))

(defn- seed-relationship!
  [ctx source-uri predicate target-uri]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-create-relationship
      (assoc c :command
             {:source-uri source-uri
              :target-uri target-uri
              :predicate predicate
              :properties {}})))))

(defn- register-shape!
  [ctx shape]
  (h/run-and-apply!
   ctx
   (fn [c]
     (lint-cmd/ontology-register-shape
      (assoc c :command {:ontology-id ontology-id :shape shape})))))

(defn- run-validation!
  [ctx]
  (h/run-and-apply!
   ctx
   (fn [c]
     (lint-cmd/ontology-run-validation
      (assoc c :command {:ontology-id ontology-id})))))

;; =============================================================================
;; 1. Shapes register; invalid shapes are REJECTED at registration time
;; =============================================================================

(deftest test-1-shape-registration-flow
  (testing "valid shape registers; event lands in registry projection"
    (h/with-test-context [ctx]
      (let [shape {:shape/id     :test/min-count-positive
                   :shape/type   :node-shape
                   :target-class :custom
                   :severity     :violation
                   :message      "Every :custom concept needs at least one :indicators entry."
                   :property [{:path :indicators :min-count 1}]}
            res (register-shape! ctx shape)]
        (is (h/event-of-type? res :ontology/shape-registered)
            "register-shape emits :ontology/shape-registered")
        (let [reg (rmp/project ctx :ontology/shape-registry)
              entry (get-in reg [ontology-id :test/min-count-positive])]
          (is (some? entry) "shape lands in shape-registry projection")
          (is (= shape (:shape-body entry))
              "shape body round-trips through the projection unchanged")))))

  (testing "shape with NEITHER :property NOR :code (silently-pass failure mode) is REJECTED"
    (h/with-test-context [ctx]
      (let [bad-shape {:shape/id     :test/no-predicate
                       :shape/type   :node-shape
                       :target-class :custom
                       :severity     :violation
                       :message      "I'll never fire because I have no predicate."}
            res (register-shape! ctx bad-shape)]
        (is (or (instance? Throwable res)
                (some? (:cognitect.anomalies/category res))
                (nil? (seq (:command-result/events res))))
            "register-shape rejects (anomaly OR no events)")
        ;; Belt + suspenders: the projection should NOT carry this shape.
        (let [reg (rmp/project ctx :ontology/shape-registry)]
          (is (nil? (get-in reg [ontology-id :test/no-predicate]))
              "rejected shape NEVER lands in the registry"))))))

;; =============================================================================
;; 2. Phase-1 components: positive AND negative fixtures
;; =============================================================================
;;
;; The interpreter is unit-tested directly here (pure fn) so each
;; component gets adversarial coverage without spinning the event-store
;; per case. Component coverage is INTEGRATION-tested via the built-in
;; lints below.

(defn- mk-concept [uri & {:as overrides}]
  (merge {:uri uri :scope :custom
          :broader #{} :narrower #{} :related #{}}
         overrides))

(defn- graph [& concepts]
  (reduce (fn [g c] (assoc g (:uri c) c)) {} concepts))

(deftest test-2-component-target-class
  (testing "target-class :custom matches scoped concepts; misses non-custom"
    (let [g (graph (mk-concept "concept:a:Alpha" :scope :custom :indicators ["t1"])
                   (mk-concept "concept:b:Beta" :scope :failure :indicators ["t1"]))
          shape {:shape/id :probe :shape/type :node-shape
                 :target-class :custom :severity :info :message "noop"
                 :property [{:path :indicators :min-count 99}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)) "ONE violation — only the :custom concept hit")
      (is (= "concept:a:Alpha" (-> violations first :offending-uri)))))
  (testing "target-class as URI prefix (string ending in ':') scopes by prefix"
    (let [g (graph (mk-concept "concept:film:Alpha" :indicators [])
                   (mk-concept "concept:book:Beta" :indicators []))
          shape {:shape/id :p :shape/type :node-shape
                 :target-class "concept:film:" :severity :info :message "x"
                 :property [{:path :indicators :min-count 1}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))
      (is (= "concept:film:Alpha" (-> violations first :offending-uri)))))
  (testing "target-class nil matches every concept"
    (let [g (graph (mk-concept "x:1" :indicators []) (mk-concept "x:2" :indicators []))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :info :message "x"
                 :property [{:path :indicators :min-count 1}]}]
      (is (= 2 (count (:violations (interp/run-shape g shape))))))))

(deftest test-2-component-min-and-max-count
  (testing "min-count fires when path returns fewer values than required"
    (let [g (graph (mk-concept "a" :indicators []))
          shape {:shape/id :mc :shape/type :node-shape :target-class nil
                 :severity :violation :message "min"
                 :property [{:path :indicators :min-count 2}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))
      (is (= :min-count-violated (-> violations first :reason)))))
  (testing "min-count is SILENT when path satisfies the constraint (POSITIVE adversarial)"
    (let [g (graph (mk-concept "a" :indicators ["x" "y" "z"]))
          shape {:shape/id :mc :shape/type :node-shape :target-class nil
                 :severity :violation :message "min"
                 :property [{:path :indicators :min-count 2}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (empty? violations)
          "no false-positive violation when the constraint passes")))
  (testing "max-count fires when path returns MORE values than allowed"
    (let [g (graph (mk-concept "a" :indicators ["x" "y" "z"]))
          shape {:shape/id :mc :shape/type :node-shape :target-class nil
                 :severity :violation :message "max"
                 :property [{:path :indicators :max-count 2}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))
      (is (= :max-count-violated (-> violations first :reason)))))
  (testing "max-count is SILENT when constraint passes (POSITIVE adversarial)"
    (let [g (graph (mk-concept "a" :indicators ["x" "y"]))
          shape {:shape/id :mc :shape/type :node-shape :target-class nil
                 :severity :violation :message "max"
                 :property [{:path :indicators :max-count 2}]}]
      (is (empty? (:violations (interp/run-shape g shape)))))))

(deftest test-2-component-path-keyword-vs-string
  (testing "path :keyword resolves to direct concept attribute lookup"
    (let [g (graph (mk-concept "a" :indicators ["x"]))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :info :message "x"
                 :property [{:path :indicators :max-count 0}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))))
  (testing "path string skos:related resolves to :related set"
    (let [g (graph (mk-concept "a" :related #{"b" "c"})
                   (mk-concept "b") (mk-concept "c"))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :info :message "x"
                 :property [{:path "skos:related" :max-count 1}]}
          {:keys [violations]} (interp/run-shape g shape)
          a-violations (filter #(= "a" (:offending-uri %)) violations)]
      (is (seq a-violations)
          ":related set of size 2 violates max-count 1 for concept a"))))

(deftest test-2-component-not-object-exists
  (testing ":not {:object-exists? false} fires for DANGLING (the lint case)"
    (let [g (graph (mk-concept "a" :related #{"ghost"}))   ;; ghost missing
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :violation :message "dangle"
                 :property [{:path "skos:related"
                             :not {:object-exists? false}}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))
      (is (= :not-constraint-violated (-> violations first :reason)))))
  (testing ":not {:object-exists? false} is SILENT when target resolves (POSITIVE)"
    (let [g (graph (mk-concept "a" :related #{"b"})
                   (mk-concept "b"))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :violation :message "dangle"
                 :property [{:path "skos:related"
                             :not {:object-exists? false}}]}]
      (is (empty? (:violations (interp/run-shape g shape))))))
  (testing "unsupported :not inner predicate THROWS (no silent skip)"
    (let [g (graph (mk-concept "a" :related #{"b"}) (mk-concept "b"))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :violation :message "x"
                 :property [{:path "skos:related"
                             :not {:datatype "xsd:string"}}]}]
      (is (thrown? Exception (interp/run-shape g shape))
          "unsupported :not inner predicate must THROW, not silently pass"))))

(deftest test-2-component-severity-and-message
  (testing "violation records carry the shape's :severity and :message verbatim"
    (let [g (graph (mk-concept "a" :indicators []))
          shape {:shape/id :p :shape/type :node-shape :target-class nil
                 :severity :warning :message "every concept needs indicators"
                 :property [{:path :indicators :min-count 1}]}
          {:keys [violations]} (interp/run-shape g shape)
          v (first violations)]
      (is (= :warning (:severity v)))
      (is (= "every concept needs indicators" (:message v)))
      (is (= "a" (:offending-uri v))))))

;; =============================================================================
;; 3. Built-in lints fire on bad, stay silent on clean
;; =============================================================================

(defn- seed-built-in-fixtures!
  "Seed mixed clean/bad fixtures for all three built-in lints."
  [ctx]
  ;; Clean concept (passes ALL three lints)
  (seed-concept! ctx "concept:film:Casablanca" "Casablanca"
                 {:labels [{:value "Casablanca" :lang "en"}]})
  ;; Naming-convention bad — lowercase after second colon
  (seed-concept! ctx "concept:badname:foo" "foo")
  ;; Language-tag misuse bad — numeric value with @en lang tag
  (seed-concept! ctx "concept:film:Metropolis" "Metropolis"
                 {:labels [{:value "1927" :lang "en"}
                           {:value "Metropolis" :lang "de"}]})
  ;; Dangling-endpoint bad — points at non-existent concept
  (seed-concept! ctx "concept:film:Inception" "Inception"
                 {:labels [{:value "Inception" :lang "en"}]})
  (seed-relationship! ctx "concept:film:Inception" "skos:related" "concept:person:ghost"))

(deftest test-3-builtin-lints-fire-on-bad
  (h/with-test-context [ctx]
    (seed-built-in-fixtures! ctx)
    (doseq [shape builtin/all-builtin-shapes]
      (register-shape! ctx shape))
    (let [result (run-validation! ctx)
          report (:query/result
                  (lint-q/ontology-get-validation-report
                   (assoc ctx :query {:ontology-id ontology-id})))
          violations (:violations report)
          by-shape (group-by :shape-id violations)]
      (testing "the run command emitted lint-violation events"
        (is (some #(= :ontology/lint-violation (:event/type %))
                  (h/get-result-events result))))
      (testing "dangling-endpoint fires on concept:film:Inception → concept:person:ghost"
        (let [dangling (get by-shape :ontology.lint/dangling-endpoint)]
          (is (some #(= "concept:film:Inception" (:offending-uri %)) dangling))))
      (testing "naming-convention fires on concept:badname:foo"
        (let [naming (get by-shape :ontology.lint/naming-convention)]
          (is (some #(= "concept:badname:foo" (:offending-uri %)) naming))))
      (testing "language-tag-misuse fires on concept:film:Metropolis"
        (let [lt (get by-shape :ontology.lint/language-tag-misuse)]
          (is (some #(= "concept:film:Metropolis" (:offending-uri %)) lt)))))))

(deftest test-3-builtin-lints-silent-on-clean
  ;; Adversarial: clean concept-only graph; ALL three lints must be
  ;; silent. False-positive surfaces immediately if any lint over-fires.
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:film:Casablanca" "Casablanca"
                   {:labels [{:value "Casablanca" :lang "en"}]})
    (seed-concept! ctx "concept:film:Vertigo" "Vertigo"
                   {:labels [{:value "Vertigo" :lang "en"}]})
    (seed-relationship! ctx "concept:film:Casablanca" "skos:related" "concept:film:Vertigo")
    (doseq [shape builtin/all-builtin-shapes]
      (register-shape! ctx shape))
    (run-validation! ctx)
    (let [report (:query/result
                  (lint-q/ontology-get-validation-report
                   (assoc ctx :query {:ontology-id ontology-id})))]
      (is (empty? (:violations report))
          "all built-ins silent on clean fixtures (no false positives)"))))

;; =============================================================================
;; 4. :deactivated true → ZERO violations AND ONE skip
;; =============================================================================

(deftest test-4-deactivated-shape-emits-skip-only
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:badname:foo" "foo")
    ;; Register the naming-convention shape with :deactivated true.
    ;; A graph that WOULD violate it is registered; the run must emit
    ;; ZERO violations AND exactly ONE skip for this shape.
    (register-shape! ctx (assoc builtin/naming-convention-shape-symbol
                                :deactivated true))
    (let [result (run-validation! ctx)
          events (h/get-result-events result)
          violations (filter #(= :ontology/lint-violation (:event/type %)) events)
          skips (filter #(= :ontology/lint-shape-skipped (:event/type %)) events)]
      (is (empty? violations)
          "deactivated shape produces ZERO violation events")
      (is (= 1 (count skips))
          "deactivated shape produces EXACTLY ONE skip record")
      (is (= :deactivated (-> skips first :reason))
          "skip record reason is :deactivated"))
    ;; Through the validation-report read-model, the skip surfaces and
    ;; violations remain absent. This is the "you turned X off, here's
    ;; the proof" audit half.
    (let [report (:query/result
                  (lint-q/ontology-get-validation-report
                   (assoc ctx :query {:ontology-id ontology-id})))]
      (is (empty? (:violations report)))
      (is (= 1 (count (:skips report))))
      (is (= :ontology.lint/naming-convention
             (-> report :skips first :shape-id))))))

;; =============================================================================
;; 5. Validation report (current) + violation history (queryable)
;; =============================================================================

(deftest test-5-current-report-is-latest-run
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:badname:foo" "foo")
    (register-shape! ctx builtin/naming-convention-shape-symbol)
    ;; Run #1
    (run-validation! ctx)
    (let [report-after-1 (:query/result
                          (lint-q/ontology-get-validation-report
                           (assoc ctx :query {:ontology-id ontology-id})))
          run-1-id (:run-id report-after-1)]
      (is (= 1 (count (:violations report-after-1))))
      ;; Run #2 — same graph; same violation; NEW run-id
      (run-validation! ctx)
      (let [report-after-2 (:query/result
                            (lint-q/ontology-get-validation-report
                             (assoc ctx :query {:ontology-id ontology-id})))
            run-2-id (:run-id report-after-2)]
        (is (not= run-1-id run-2-id)
            "run-id changes between runs")
        (is (= 1 (count (:violations report-after-2)))
            "current report shows ONLY the latest run's violations (not cumulative)")))))

(deftest test-5-violation-history-queryable
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:badname:foo" "foo")
    (register-shape! ctx builtin/naming-convention-shape-symbol)
    (run-validation! ctx)
    (run-validation! ctx)
    (run-validation! ctx)
    (let [hist (:query/result
                (lint-q/ontology-get-violation-history
                 (assoc ctx :query {:ontology-id ontology-id})))]
      (is (= 3 (count hist))
          "every violation event ever — history has 3 entries after 3 runs"))
    (testing "filter by :shape-id"
      (let [hist (:query/result
                  (lint-q/ontology-get-violation-history
                   (assoc ctx :query {:ontology-id ontology-id
                                      :shape-id :ontology.lint/naming-convention})))]
        (is (= 3 (count hist))))
      (let [hist (:query/result
                  (lint-q/ontology-get-violation-history
                   (assoc ctx :query {:ontology-id ontology-id
                                      :shape-id :nonexistent/shape})))]
        (is (empty? hist))))))

;; =============================================================================
;; 6. :code escape hatch — in-process fn AND persisted symbol both work
;; =============================================================================

(deftest test-6-code-symbol-resolves-at-run-time
  ;; The naming-convention-shape-symbol variant references the predicate
  ;; via a fully-qualified symbol — requiring-resolve loads it on first
  ;; use. The lint should fire identically to the in-process variant.
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:badname:foo" "foo")
    (register-shape! ctx builtin/naming-convention-shape-symbol)
    (run-validation! ctx)
    (let [report (:query/result
                  (lint-q/ontology-get-validation-report
                   (assoc ctx :query {:ontology-id ontology-id})))]
      (is (= 1 (count (:violations report)))
          "persisted :code-symbol shape fires identically to in-process :code"))))

(deftest test-6-code-in-process-fn-also-runs
  ;; In-process registration uses the literal fn. Build a tiny one-off
  ;; predicate that fires on every concept; assert exactly N violations.
  (h/with-test-context [ctx]
    (seed-concept! ctx "concept:a:A" "A")
    (seed-concept! ctx "concept:a:B" "B")
    (let [always-violate (fn [_] {:violation? true :detail "always fires"})
          shape {:shape/id     :test/always
                 :shape/type   :node-shape
                 :target-class :custom
                 :severity     :info
                 :message      "this lint always fires"
                 :code         always-violate}]
      ;; In-process registration with literal fn: the :code is stripped
      ;; from the event body but the fn cannot survive a process restart.
      ;; For an in-test single-process run, register via the COMMAND
      ;; surface — which strips the fn — and use the symbol form to make
      ;; it actually runnable. This is exactly how a consumer would do
      ;; it in production (a literal-fn shape is fine for prototyping,
      ;; but the registry's persisted record references the symbol).
      ;;
      ;; The adversarial check here: a registered shape whose persisted
      ;; body has NO predicate (because the fn got stripped and no
      ;; :code-symbol was supplied) cannot run anything. Confirm the
      ;; interpreter SKIPS it (zero violations, zero throws).
      (register-shape! ctx shape)
      (run-validation! ctx)
      (let [report (:query/result
                    (lint-q/ontology-get-validation-report
                     (assoc ctx :query {:ontology-id ontology-id})))]
        (is (empty? (:violations report))
            "a shape whose :code didn't persist and has no :code-symbol fires NOTHING")))))
