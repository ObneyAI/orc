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

(deftest code-node-with-inline-fn-transforms-to-sheet-code
  (testing ":code with an inline (fn ...) value preserves the fn and key shape"
    (let [my-fn (fn [{:keys [inputs]}] {:n (count (:chunks inputs))})
          result (rlm-dsl/rlm-dsl->orc-dsl
                   [:code {:reads [:chunks] :writes [:n] :fn my-fn}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= [:chunks] (:reads opts)))
        (is (= [:n] (:writes opts)))
        (is (fn? (:fn opts)))
        (is (identical? my-fn (:fn opts))
            ":fn is passed through by identity, not wrapped or replaced")))))

(deftest code-node-with-qualified-symbol-string-transforms-through
  (testing ":code with a qualified-symbol string :fn is passed through unchanged"
    (let [result (rlm-dsl/rlm-dsl->orc-dsl
                   [:code {:reads [:x] :writes [:y] :fn "my.ns/transform"}])]
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= "my.ns/transform" (:fn opts)))))))

(deftest code-node-without-fn-throws
  (testing ":code without :fn raises a clear error"
    (is (thrown-with-msg? Exception #":code node missing required :fn"
                          (rlm-dsl/rlm-dsl->orc-dsl
                            [:code {:reads [:x] :writes [:y]}])))))

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
;; U5: Phase-1 sub-LLM image routing via blackboard schema :field-type
;; =============================================================================
;;
;; When a Phase-1 sub-LLM call reads a blackboard key whose Malli schema
;; carries :field-type :image, the dscloj module's corresponding input
;; field must be marked :type :image so dscloj's build-message-content
;; routes the value as a multimodal content block (image_url), not as
;; inline text. Without this, vision tasks ship base64 data URIs as
;; inline text — wrong content shape AND ~480K tokens per image vs ~1K
;; for image-tile billing.

(deftest llm-primitive-propagates-image-field-type-to-module
  (testing "blackboard schema [:string {:field-type :image}] -> module input :type :image"
    (let [captured (atom nil)]
      (with-redefs [dscloj/predict
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
