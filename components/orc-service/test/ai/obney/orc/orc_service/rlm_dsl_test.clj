(ns ai.obney.orc.orc-service.rlm-dsl-test
  "Tests for RLM DSL transformer and emit-tree! primitive.

   The transformer converts S-expr DSL (what the LLM outputs) to canonical
   ORC DSL (what the executor runs). This enables storing generated trees
   in the ontology for learning."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]))

;; =============================================================================
;; Tracer Bullet #1: :sequence node
;; =============================================================================

(deftest sequence-node-transforms-to-orc-sequence
  (testing "Empty sequence"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl [:sequence])]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))))

  (testing "Sequence with single child"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:summary]}]])]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))
      ;; Should have one child after the sequence symbol
      (is (= 1 (count (rest result)))))))

;; =============================================================================
;; Tracer Bullet #2: :llm node
;; =============================================================================

(deftest llm-node-transforms-to-orc-llm
  (testing "LLM node with reads and writes"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates :entities]}])]
      (is (list? result))
      (is (= 'sheet/llm (first result)))
      ;; Should contain the key options as keyword args
      (let [opts (apply hash-map (rest result))]
        (is (= "Extract dates" (:instruction opts)))
        (is (= [:chunk] (:reads opts)))
        (is (= [:dates :entities] (:writes opts))))))

  (testing "LLM node with model specified"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:llm {:instruction "Analyze"
                          :model "google/gemini-2.5-flash"
                          :reads [:data]
                          :writes [:analysis]}])]
      (let [opts (apply hash-map (rest result))]
        (is (= "google/gemini-2.5-flash" (:model opts)))))))

;; =============================================================================
;; Issue 007: Default retry config for LLM nodes
;; =============================================================================

(deftest llm-node-gets-default-retry-config
  (testing "LLM node without explicit retry gets default config"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates]}])
          opts (apply hash-map (rest result))]
      ;; Should have retry config added automatically
      (is (some? (:retry opts)) "LLM node should have :retry config")
      (is (= 3 (get-in opts [:retry :max-attempts])) "Should have 3 max attempts")
      (is (vector? (get-in opts [:retry :backoff-ms])) "Should have backoff delays")))

  (testing "Explicit retry config takes precedence"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates]
                          :retry {:max-attempts 5 :backoff-ms [500 1000]}}])
          opts (apply hash-map (rest result))]
      ;; Should preserve explicit config, not override
      (is (= 5 (get-in opts [:retry :max-attempts])) "Should preserve explicit max-attempts")
      (is (= [500 1000] (get-in opts [:retry :backoff-ms])) "Should preserve explicit backoff")))

  (testing "Non-LLM nodes do not get retry config"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:chunk-document {:from :document :size 5000 :into :chunks}])
          ;; chunk-document transforms to sheet/code
          opts (when (and (list? result) (> (count result) 1))
                 (apply hash-map (rest result)))]
      ;; Should NOT have retry config
      (is (nil? (:retry opts)) "Non-LLM nodes should not get retry config"))))

;; =============================================================================
;; Tracer Bullet #3: :map-each node
;; =============================================================================

(deftest map-each-node-transforms-to-orc-map-each
  (testing "map-each with nested llm child"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:map-each {:from :chunks :as :chunk :into :results}
                    [:llm {:instruction "Extract dates"
                           :reads [:chunk]
                           :writes [:dates]}]])]
      (is (list? result))
      (is (= 'sheet/map-each (first result)))
      ;; Should contain the :from, :as, :into options
      (let [flat-args (rest result)
            ;; Last element is the child, everything before is keyword args
            child (last flat-args)
            kw-args (butlast flat-args)
            opts (apply hash-map kw-args)]
        (is (= :chunks (:from opts)))
        (is (= :chunk (:as opts)))
        (is (= :results (:into opts)))
        ;; Child should be the transformed llm node
        (is (list? child))
        (is (= 'sheet/llm (first child)))))))

;; =============================================================================
;; Tracer Bullet #4: :chunk-document node
;; =============================================================================

(deftest chunk-document-transforms-to-code-node
  (testing "chunk-document generates code node with chunking logic"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:chunk-document {:from :document :size 5000 :into :chunks}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      ;; Should have :reads containing the source
      ;; Should have :writes containing the target
      (let [opts (apply hash-map (rest result))]
        (is (= [:document] (:reads opts)))
        (is (= [:chunks] (:writes opts)))
        ;; Should have a function that does the chunking
        (is (fn? (:fn opts)))))))

;; =============================================================================
;; Tracer Bullet #5: :aggregate and :parallel nodes
;; =============================================================================

(deftest aggregate-transforms-to-code-node
  (testing "aggregate generates code node for combining results"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:aggregate {:from :results
                                :writes [:all-dates :all-entities]}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= [:results] (:reads opts)))
        (is (= [:all-dates :all-entities] (:writes opts)))
        (is (fn? (:fn opts)))))))

(deftest parallel-transforms-to-orc-parallel
  (testing "parallel with multiple children"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:parallel
                    [:llm {:instruction "Extract A" :reads [:doc] :writes [:a]}]
                    [:llm {:instruction "Extract B" :reads [:doc] :writes [:b]}]])]
      (is (list? result))
      (is (= 'sheet/parallel (first result)))
      ;; Should have two children
      (is (= 2 (count (rest result)))))))

;; =============================================================================
;; PR02: :code node — references a pre-built Clojure function by qualified symbol
;; =============================================================================

(deftest code-node-transforms-to-orc-code
  (testing "code node with :fn, :reads, :writes"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:code {:fn "ai.obney.orc.example/some-fn"
                           :reads [:input]
                           :writes [:output]}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= "ai.obney.orc.example/some-fn" (:fn opts)))
        (is (= [:input] (:reads opts)))
        (is (= [:output] (:writes opts)))))))

(deftest code-node-composes-inside-sequence
  (testing "code node nested in a sequence with neighbors round-trips correctly"
    (let [tree [:sequence
                [:llm {:instruction "Find PII"
                       :reads [:doc] :writes [:targets]}]
                [:code {:fn "ai.obney.orc.example/apply-redactions"
                        :reads [:doc :targets]
                        :writes [:redacted-text :total-count]}]
                [:final {:keys [:redacted-text :total-count]}]]
          result (rlm-dsl/rlm-dsl->orc-dsl tree)]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))
      (is (= 3 (count (rest result))))
      (let [code-node (nth (rest result) 1)
            opts (apply hash-map (rest code-node))]
        (is (= 'sheet/code (first code-node)))
        (is (= "ai.obney.orc.example/apply-redactions" (:fn opts)))
        (is (= [:doc :targets] (:reads opts)))
        (is (= [:redacted-text :total-count] (:writes opts)))))))

(deftest code-node-accepts-inline-fn
  (testing "code node :fn can be an inline (fn ...) form, not just a qualified-symbol string"
    (let [inline-fn (fn [{:keys [inputs]}]
                      {:result (count inputs)})
          result (rlm-dsl/rlm-dsl->orc-dsl
                   [:code {:fn inline-fn
                           :reads [:a :b]
                           :writes [:result]}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (fn? (:fn opts))
            "inline fn value should pass through as a function (no string conversion)")
        (is (= [:a :b] (:reads opts)))
        (is (= [:result] (:writes opts)))))))

(deftest code-node-missing-fn-throws-clear-error
  (testing "code node missing :fn throws ex-info with clear message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":code node missing required :fn"
                          (rlm-dsl/rlm-dsl->orc-dsl
                            [:code {:reads [:a] :writes [:b]}]))))
  (testing "code node with nil :fn throws ex-info"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":code node missing required :fn"
                          (rlm-dsl/rlm-dsl->orc-dsl
                            [:code {:fn nil :reads [:a] :writes [:b]}])))))

;; =============================================================================
;; Tracer Bullet #6: Full nested structure (document analysis pattern)
;; =============================================================================

(deftest full-document-analysis-tree-transforms-correctly
  (testing "Complete document analysis tree with chunking + map-each + aggregate"
    (let [tree [:sequence
                [:chunk-document {:from :document :size 5000 :into :chunks}]
                [:map-each {:from :chunks :as :chunk :into :results}
                 [:llm {:instruction "Extract dates and entities"
                        :reads [:chunk]
                        :writes [:dates :entities]}]]
                [:aggregate {:from :results :writes [:all-dates :all-entities]}]
                [:final {:keys [:summary :key-dates :entities]}]]
          result (rlm-dsl/rlm-dsl->orc-dsl tree)]
      ;; Top level should be sequence
      (is (list? result))
      (is (= 'sheet/sequence (first result)))
      ;; Should have 4 children
      (is (= 4 (count (rest result))))
      ;; First child should be code (chunk-document)
      (is (= 'sheet/code (first (nth (rest result) 0))))
      ;; Second child should be map-each
      (is (= 'sheet/map-each (first (nth (rest result) 1))))
      ;; Third child should be code (aggregate)
      (is (= 'sheet/code (first (nth (rest result) 2))))
      ;; Fourth child should be final!
      (is (= 'final! (first (nth (rest result) 3)))))))

;; =============================================================================
;; Tracer Bullet #7: Error handling
;; =============================================================================

(deftest invalid-node-type-throws-error
  (testing "Unknown node type throws with useful message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown node type: :invalid-node"
                          (rlm-dsl/rlm-dsl->orc-dsl
                            [:invalid-node {:foo :bar}]))))

  (testing "Nil tree throws error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot transform nil tree"
                          (rlm-dsl/rlm-dsl->orc-dsl nil)))))

;; =============================================================================
;; Tracer Bullet #8: emit-tree! sandbox primitive
;; =============================================================================

(deftest emit-tree!-primitive-stores-tree
  (testing "emit-tree! stores raw and canonical DSL in sandbox vars"
    (let [context {:provider :openrouter
                   :blackboard {}
                   :inputs {}}
          sandbox (rlm-sandbox/build-rlm-context context)
          code "(emit-tree! [:sequence
                             [:llm {:instruction \"Test\" :reads [:doc] :writes [:out]}]
                             [:final {:keys [:summary]}]])"
          result (rlm-sandbox/execute-rlm-code sandbox code)]
      ;; Should not error
      (is (nil? (:error result)))
      ;; Should have stored the tree
      (let [vars @(:sandbox-vars sandbox)]
        (is (contains? vars :generated-tree))
        (is (contains? vars :generated-tree-raw))
        ;; Raw should be the S-expr
        (is (vector? (:generated-tree-raw vars)))
        (is (= :sequence (first (:generated-tree-raw vars))))
        ;; Canonical should be the transformed form
        (is (list? (:generated-tree vars)))
        (is (= 'sheet/sequence (first (:generated-tree vars)))))))

  (testing "emit-tree! validates the tree structure"
    (let [context {:provider :openrouter
                   :blackboard {}
                   :inputs {}}
          sandbox (rlm-sandbox/build-rlm-context context)
          code "(emit-tree! [:invalid-node {:foo :bar}])"
          result (rlm-sandbox/execute-rlm-code sandbox code)]
      ;; Should error on invalid node type
      (is (some? (:error result)))
      (is (re-find #"Unknown node type" (:error result))))))

;; =============================================================================
;; Tracer Bullet #9: RLM event schemas
;; =============================================================================

(deftest rlm-tree-generated-event-schema-validates
  (testing "Valid tree-generated event validates"
    (let [schema (schemas/events :rlm/tree-generated)
          valid-event {:tree-id (random-uuid)
                       :execution-id (random-uuid)
                       :raw-dsl [:sequence [:final {:keys [:summary]}]]
                       :canonical-dsl '(sheet/sequence (final! {:keys [:summary]}))
                       :iteration-count 2
                       :input-metadata {:size 50000 :type :document}
                       :generated-at "2026-05-15T12:00:00Z"}]
      (is (m/validate schema valid-event))))

  (testing "Missing required fields fail validation"
    (let [schema (schemas/events :rlm/tree-generated)
          invalid-event {:tree-id (random-uuid)}]  ;; Missing required fields
      (is (not (m/validate schema invalid-event))))))

(deftest rlm-tree-executed-event-schema-validates
  (testing "Valid tree-executed event validates"
    (let [schema (schemas/events :rlm/tree-executed)
          valid-event {:tree-id (random-uuid)
                       :execution-id (random-uuid)
                       :status :success
                       :outputs {:summary "Test summary"}
                       :duration-ms 1234}]
      (is (m/validate schema valid-event))))

  (testing "Failure status with error validates"
    (let [schema (schemas/events :rlm/tree-executed)
          valid-event {:tree-id (random-uuid)
                       :execution-id (random-uuid)
                       :status :failure
                       :duration-ms 500
                       :error "Something went wrong"}]
      (is (m/validate schema valid-event)))))

;; =============================================================================
;; Tracer Bullet #10: Two-phase execution (emit-tree! detection)
;; =============================================================================

;; =============================================================================
;; PR03: :available-code-nodes plumbing through :rlm config
;; =============================================================================

(deftest available-code-nodes-flows-to-dscloj-when-set
  (testing ":rlm {:available-code-nodes \"...\"} on node config makes the catalog visible to dscloj"
    (let [captured (atom nil)
          catalog "## Available Code Nodes\n- ai.obney.orc.example/foo: does X"]
      (with-redefs [dscloj.core/predict
                    (fn [_provider module inputs _opts]
                      (reset! captured {:module module :inputs inputs})
                      {:outputs {:code "(final! {:summary \"done\"})"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [node {:type :repl-researcher
                    :instruction "Test"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm {:available-code-nodes catalog}
                    :max-iterations 1}
              blackboard {:doc {:key :doc :schema :string :value "test" :version 1}}]
          (executor/execute-repl-researcher-rlm node blackboard :openrouter {})
          (let [{:keys [module inputs]} @captured]
            (is (some? module) "dscloj/predict should have been called")
            (is (some #(= :available-code-nodes (:name %)) (:inputs module))
                "module :inputs should include the :available-code-nodes field")
            (is (= catalog (:available-code-nodes inputs))
                "dscloj inputs map should carry the catalog value")))))))

(deftest available-code-nodes-absent-leaves-module-shape-unchanged
  (testing "module :inputs do not include :available-code-nodes when not configured on the node"
    (let [captured (atom nil)]
      (with-redefs [dscloj.core/predict
                    (fn [_provider module inputs _opts]
                      (reset! captured {:module module :inputs inputs})
                      {:outputs {:code "(final! {:summary \"done\"})"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [node {:type :repl-researcher
                    :instruction "Test"
                    :reads [:doc]
                    :writes [:summary]
                    :rlm true  ;; truthy but not a map — equivalent to existing benchmarks
                    :max-iterations 1}
              blackboard {:doc {:key :doc :schema :string :value "test" :version 1}}]
          (executor/execute-repl-researcher-rlm node blackboard :openrouter {})
          (let [{:keys [module inputs]} @captured]
            (is (some? module) "dscloj/predict should have been called")
            (is (not (some #(= :available-code-nodes (:name %)) (:inputs module)))
                "module :inputs should NOT include :available-code-nodes when not configured")
            (is (not (contains? inputs :available-code-nodes))
                "dscloj inputs map should NOT carry :available-code-nodes key")
            (is (= #{:task :inputs-info :history} (set (keys inputs)))
                "dscloj inputs map should contain only the baseline keys")))))))

(deftest executor-detects-emit-tree-and-includes-raw-tree
  (testing "execute-repl-researcher-rlm detects emit-tree! and includes raw tree in result"
    (let [;; Mock dscloj/predict to return code with emit-tree!
          call-count (atom 0)]
      (with-redefs [dscloj.core/predict
                    (fn [provider module inputs opts]
                      (swap! call-count inc)
                      {:outputs {:code "(emit-tree!
                                          [:sequence
                                            [:llm {:instruction \"Test\" :reads [:doc] :writes [:out]}]
                                            [:final {:keys [:summary]}]])"}
                       :usage {:prompt_tokens 100 :completion_tokens 80 :total_tokens 180}})
                    ;; Mock tree-executor to return immediately (avoids timeout without full infrastructure)
                    tree-executor/execute-tree
                    (fn [tree context options]
                      {:status :success
                       :outputs {:summary "Mock Phase 2 output"}
                       :duration-ms 1})]
        (let [node {:type :repl-researcher
                    :instruction "Generate a BT"
                    :reads [:document]
                    :writes [:summary]
                    :rlm true
                    :max-iterations 5}
              blackboard {:document {:key :document :schema :string :value "test doc" :version 1}}
              result (ai.obney.orc.orc-service.core.executor/execute-repl-researcher-rlm
                       node blackboard :openrouter {})]
          ;; Phase 2 auto-executes and returns success
          (is (= :success (:status result)) "Should return :success from Phase 2 execution")
          ;; Should have the generated raw tree for observability
          (is (some? (:generated-tree-raw result)) "Should have :generated-tree-raw")
          ;; Raw should be S-expr
          (is (vector? (:generated-tree-raw result)) "Raw tree should be vector")
          (is (= :sequence (first (:generated-tree-raw result))) "Should start with :sequence")
          ;; Full Phase 2 integration tested in rlm-mode-test/rlm-emit-tree-generates-tree-result-test
          )))))

;; =============================================================================
;; PR-Pre03: Phase-1 sub-LLM image routing
;; =============================================================================
;;
;; Bug fixed: execute-llm-primitive in rlm_sandbox.clj must propagate
;; :field-type from the blackboard schema to the dscloj module input,
;; so multimodal content blocks (image_url) are used instead of raw text.

(deftest llm-primitive-propagates-image-field-type-to-module
  (testing "blackboard schema [:string {:field-type :image}] -> module input :type :image"
    (let [captured (atom nil)]
      (with-redefs [dscloj.core/predict
                    (fn [_provider module inputs _opts]
                      (reset! captured {:module module :inputs inputs})
                      {:outputs {:answer "ok"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [blackboard {:image {:key :image
                                   :schema [:string {:field-type :image}]
                                   :value "data:image/png;base64,abc123"
                                   :version 1}}
              sandbox-vars (atom {})
              usage-tracker (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
              context {:provider :openrouter
                       :blackboard blackboard
                       :sandbox-vars @sandbox-vars
                       :usage-tracker usage-tracker}]
          (rlm-sandbox/execute-llm-primitive
            "vision-call"
            {:instruction "What is in this image?"
             :reads [:image]
             :writes [:answer]}
            context)
          (let [{:keys [module]} @captured
                image-input (first (filter #(= :image (:name %)) (:inputs module)))]
            (is (some? image-input) "module :inputs should include :image entry")
            (is (= :image (:type image-input))
                "image-typed blackboard schema must propagate :type :image to the dscloj module input")))))))

;; =============================================================================
;; U11: :llm output schemas drive structured-output parsing
;; =============================================================================
;;
;; When the model emits a tree with an :llm node declaring :output-schemas,
;; those schemas propagate to the child sheet's blackboard key declarations.
;; Downstream, build-module looks up the blackboard schema, and dscloj's
;; existing complex-spec? path triggers JSON-parsing of the LLM response.
;; This closes the LLM-output → :code consumer gap that document_redaction
;; surfaced (the LLM produced JSON-text but downstream :code expected
;; parsed Clojure data).

(deftest llm-node-preserves-output-schemas
  (testing ":llm node with :output-schemas → canonical form preserves the schemas map"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract targets"
                          :reads [:page_text]
                          :writes [:targets]
                          :output-schemas {:targets [:vector [:map-of :any :any]]}}])
          opts (apply hash-map (rest result))]
      (is (= 'sheet/llm (first result)))
      (is (= [:targets] (:writes opts)))
      (is (= {:targets [:vector [:map-of :any :any]]}
             (:output-schemas opts))
          ":output-schemas must round-trip through the DSL translator"))))

(deftest extract-key-schemas-collects-from-llm-nodes
  (testing "extract-key-schemas walks a tree and collects {write-key → schema} from :llm :output-schemas"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "Pass 1"
                             :reads [:page_text]
                             :writes [:targets]
                             :output-schemas {:targets [:vector [:map-of :any :any]]})
                  (sheet/llm :instruction "Pass 2 with no schemas declared"
                             :reads [:targets]
                             :writes [:summary]))
          schemas (#'ai.obney.orc.orc-service.core.rlm-tree-executor/extract-key-schemas tree)]
      (is (= [:vector [:map-of :any :any]] (get schemas :targets))
          "schema for :targets collected from first :llm node")
      (is (nil? (get schemas :summary))
          "no schema collected when :output-schemas wasn't declared"))))

;; =============================================================================
;; PR-Dual-Model: sub-model tree-walk injection
;; =============================================================================
;;
;; When the runner is configured with :sub-model, the executor walks the
;; canonical Phase-2 tree and injects :model sub-model into each (sheet/llm ...)
;; form that does not already specify :model. Phase-2 leaf executor then
;; routes those calls through the sub-model. :llm nodes with explicit :model
;; are left untouched.

(deftest inject-sub-model-injects-into-llm-without-model
  (testing "walks canonical tree, injects :model into sheet/llm nodes lacking it"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "extract" :reads [:image] :writes [:text])
                  (sheet/llm :instruction "count" :reads [:text] :writes [:answer])
                  (final! {:keys [:answer]}))
          injected (#'ai.obney.orc.orc-service.core.executor/inject-sub-model
                     tree "openai/gpt-5.1-chat")
          llm-forms (filter (fn [x] (and (seq? x) (= 'sheet/llm (first x))))
                            (tree-seq seq? rest injected))]
      (is (= 2 (count llm-forms))
          "should keep both :llm nodes")
      (doseq [llm llm-forms]
        (let [opts (apply hash-map (rest llm))]
          (is (= "openai/gpt-5.1-chat" (:model opts))
              (str ":model should be injected into " (pr-str llm))))))))

(deftest inject-sub-model-respects-explicit-model
  (testing "if :llm already has :model, it is NOT overwritten"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "extract" :reads [:image] :writes [:text]
                             :model "openai/gpt-4o")
                  (sheet/llm :instruction "count" :reads [:text] :writes [:answer])
                  (final! {:keys [:answer]}))
          injected (#'ai.obney.orc.orc-service.core.executor/inject-sub-model
                     tree "openai/gpt-5.1-chat")
          llm-forms (filter (fn [x] (and (seq? x) (= 'sheet/llm (first x))))
                            (tree-seq seq? rest injected))]
      (is (= 2 (count llm-forms)))
      (let [opts-first (apply hash-map (rest (first llm-forms)))
            opts-second (apply hash-map (rest (second llm-forms)))]
        (is (= "openai/gpt-4o" (:model opts-first))
            "first :llm's explicit :model should be preserved")
        (is (= "openai/gpt-5.1-chat" (:model opts-second))
            "second :llm should get the injected sub-model")))))

(deftest inject-sub-model-nil-sub-model-is-noop
  (testing "when sub-model is nil, tree is returned unchanged"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "x" :reads [:a] :writes [:b])
                  (final! {:keys [:b]}))
          injected (#'ai.obney.orc.orc-service.core.executor/inject-sub-model
                     tree nil)]
      (is (= tree injected) "no-op when sub-model is nil"))))

;; =============================================================================
;; PR-Prompt: emit-tree! default policy
;; =============================================================================
;;
;; The RLM prompt must explicitly state that emit-tree! is the default mode
;; for non-trivial work, warn against chained sequential (llm ...) calls in
;; Phase 1, and recommend :code for deterministic transforms.

(deftest rlm-prompt-states-emit-tree-as-default
  (testing "rendered prompt contains the new emit-tree! default policy strings"
    (let [node {:type :repl-researcher
                :instruction "Test goal"
                :reads [:doc]
                :writes [:out]
                :rlm true}
          module (#'ai.obney.orc.orc-service.core.executor/build-rlm-code-generation-module
                   node {} [] {} {} {})
          prompt (:instructions module)]
      (is (re-find #"(?i)default" prompt)
          "prompt should describe emit-tree! as the default mode")
      (is (re-find #"(?i)sequential|chained|2\+ " prompt)
          "prompt should warn about chained sequential (llm ...) calls in Phase 1")
      (is (re-find #"deterministic" prompt)
          "prompt should recommend :code for deterministic transforms")
      (is (re-find #"hallucinat" prompt)
          "prompt should call out hallucination-prone counting via LLM as an anti-pattern"))))

(deftest llm-primitive-leaves-non-image-inputs-untyped
  (testing "blackboard schema :string (no :field-type) -> module input has no :type"
    (let [captured (atom nil)]
      (with-redefs [dscloj.core/predict
                    (fn [_provider module _inputs _opts]
                      (reset! captured {:module module})
                      {:outputs {:answer "ok"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [blackboard {:doc {:key :doc :schema :string :value "hello" :version 1}}
              sandbox-vars (atom {})
              usage-tracker (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
              context {:provider :openrouter
                       :blackboard blackboard
                       :sandbox-vars @sandbox-vars
                       :usage-tracker usage-tracker}]
          (rlm-sandbox/execute-llm-primitive
            "text-call"
            {:instruction "Summarize"
             :reads [:doc]
             :writes [:answer]}
            context)
          (let [{:keys [module]} @captured
                doc-input (first (filter #(= :doc (:name %)) (:inputs module)))]
            (is (some? doc-input) "module :inputs should include :doc entry")
            (is (not (contains? doc-input :type))
                "non-image-typed blackboard schemas must NOT add :type to the module input")))))))
