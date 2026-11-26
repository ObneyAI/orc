(ns ai.obney.orc.orc-service.rlm-dsl-test
  "Tests for RLM DSL transformer and emit-tree! primitive.

   The transformer converts S-expr DSL (what the LLM outputs) to canonical
   ORC DSL (what the executor runs). This enables storing generated trees
  in the ontology for learning."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.core.logic :as service-logic]
            [ai.obney.orc.orc-service.core.todo-processors :as service-processors]))


(deftest sequence-node-transforms-to-orc-sequence
  (testing "Empty sequence"
    (let [result (service-logic/rlm-dsl->orc-dsl [:sequence])]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))))

  (testing "Sequence with single child"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:sequence
                    [:final {:keys [:summary]}]])]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))
      (is (= 1 (count (rest result)))))))


(deftest llm-node-transforms-to-orc-llm
  (testing "LLM node with reads and writes"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates :entities]}])]
      (is (list? result))
      (is (= 'sheet/llm (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= "Extract dates" (:instruction opts)))
        (is (= [:chunk] (:reads opts)))
        (is (= [:dates :entities] (:writes opts))))))

  (testing "LLM node with model specified"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:llm {:instruction "Analyze"
                          :model "google/gemini-2.5-flash"
                          :reads [:data]
                          :writes [:analysis]}])]
      (let [opts (apply hash-map (rest result))]
        (is (= "google/gemini-2.5-flash" (:model opts)))))))


(deftest llm-node-gets-default-retry-config
  (testing "LLM node without explicit retry gets default config"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates]}])
          opts (apply hash-map (rest result))]
      (is (some? (:retry opts)) "LLM node should have :retry config")
      (is (= 3 (get-in opts [:retry :max-attempts])) "Should have 3 max attempts")
      (is (vector? (get-in opts [:retry :backoff-ms])) "Should have backoff delays")))

  (testing "Explicit retry config takes precedence"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract dates"
                          :reads [:chunk]
                          :writes [:dates]
                          :retry {:max-attempts 5 :backoff-ms [500 1000]}}])
          opts (apply hash-map (rest result))]
      (is (= 5 (get-in opts [:retry :max-attempts])) "Should preserve explicit max-attempts")
      (is (= [500 1000] (get-in opts [:retry :backoff-ms])) "Should preserve explicit backoff")))

  (testing "Non-LLM nodes do not get retry config"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:chunk-document {:from :document :size 5000 :into :chunks}])
          opts (when (and (list? result) (> (count result) 1))
                 (apply hash-map (rest result)))]
      (is (nil? (:retry opts)) "Non-LLM nodes should not get retry config"))))


(deftest map-each-node-transforms-to-orc-map-each
  (testing "map-each with nested llm child"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:map-each {:from :chunks :as :chunk :into :results}
                    [:llm {:instruction "Extract dates"
                           :reads [:chunk]
                           :writes [:dates]}]])]
      (is (list? result))
      (is (= 'sheet/map-each (first result)))
      (let [flat-args (rest result)
            child (last flat-args)
            kw-args (butlast flat-args)
            opts (apply hash-map kw-args)]
        (is (= :chunks (:from opts)))
        (is (= :chunk (:as opts)))
        (is (= :results (:into opts)))
        (is (list? child))
        (is (= 'sheet/llm (first child)))))))


(deftest chunk-document-transforms-to-code-node
  (testing "chunk-document generates code node with chunking logic"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:chunk-document {:from :document :size 5000 :into :chunks}])]
      (is (list? result))
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= [:document] (:reads opts)))
        (is (= [:chunks] (:writes opts)))
        (is (fn? (:fn opts)))))))


(deftest aggregate-transforms-to-code-node
  (testing "aggregate generates code node for combining results"
    (let [result (service-logic/rlm-dsl->orc-dsl
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
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:parallel
                    [:llm {:instruction "Extract A" :reads [:doc] :writes [:a]}]
                    [:llm {:instruction "Extract B" :reads [:doc] :writes [:b]}]])]
      (is (list? result))
      (is (= 'sheet/parallel (first result)))
      (is (= 2 (count (rest result)))))))

(deftest code-node-with-inline-fn-transforms-to-sheet-code
  (testing ":code with an inline (fn ...) value preserves the fn and key shape"
    (let [my-fn (fn [{:keys [inputs]}] {:n (count (:chunks inputs))})
          result (service-logic/rlm-dsl->orc-dsl
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
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:code {:reads [:x] :writes [:y] :fn "my.ns/transform"}])]
      (is (= 'sheet/code (first result)))
      (let [opts (apply hash-map (rest result))]
        (is (= "my.ns/transform" (:fn opts)))))))

(deftest code-node-without-fn-throws
  (testing ":code without :fn raises a clear error"
    (is (thrown-with-msg? Exception #":code node missing required :fn"
                          (service-logic/rlm-dsl->orc-dsl
                            [:code {:reads [:x] :writes [:y]}])))))


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
          result (service-logic/rlm-dsl->orc-dsl tree)]
      (is (list? result))
      (is (= 'sheet/sequence (first result)))
      (is (= 4 (count (rest result))))
      (is (= 'sheet/code (first (nth (rest result) 0))))
      (is (= 'sheet/map-each (first (nth (rest result) 1))))
      (is (= 'sheet/code (first (nth (rest result) 2))))
      (is (= 'final! (first (nth (rest result) 3)))))))


(deftest invalid-node-type-throws-error
  (testing "Unknown node type throws with useful message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown node type: :invalid-node"
                          (service-logic/rlm-dsl->orc-dsl
                            [:invalid-node {:foo :bar}]))))

  (testing "Nil tree throws error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot transform nil tree"
                          (service-logic/rlm-dsl->orc-dsl nil)))))


(deftest emit-tree!-primitive-stores-tree
  (testing "emit-tree! stores raw and canonical DSL in sandbox vars"
    (let [context {:provider :openrouter
                   :blackboard {}
                   :inputs {}}
          sandbox (service-processors/build-rlm-context context)
          code "(emit-tree! [:sequence
                             [:llm {:instruction \"Test\" :reads [:doc] :writes [:out]}]
                             [:final {:keys [:summary]}]])"
          result (service-logic/execute-rlm-code sandbox code)]
      (is (nil? (:error result)))
      (let [vars @(:sandbox-vars sandbox)]
        (is (contains? vars :generated-tree))
        (is (contains? vars :generated-tree-raw))
        (is (vector? (:generated-tree-raw vars)))
        (is (= :sequence (first (:generated-tree-raw vars))))
        (is (list? (:generated-tree vars)))
        (is (= 'sheet/sequence (first (:generated-tree vars)))))))

  (testing "emit-tree! validates the tree structure"
    (let [context {:provider :openrouter
                   :blackboard {}
                   :inputs {}}
          sandbox (service-processors/build-rlm-context context)
          code "(emit-tree! [:invalid-node {:foo :bar}])"
          result (service-logic/execute-rlm-code sandbox code)]
      (is (some? (:error result)))
      (is (re-find #"Unknown node type" (:error result))))))


(deftest mcp-tools-resolve-in-rlm-sandbox
  (testing "a declared :mcp-tool is a callable symbol that reaches call-tool-fn"
    (let [calls   (atom [])
          ctf     (fn [tool args] (swap! calls conj [tool args]) {:ok tool :got args})
          sandbox (service-processors/build-rlm-context
                   {:provider :openrouter :blackboard {} :inputs {}
                    :call-tool-fn ctf
                    :mcp-tools ["get-index" "get-company"]})
          r-idx   (service-logic/execute-rlm-code sandbox "(get-index)")
          r-kw    (service-logic/execute-rlm-code sandbox "(get-company :slug \"acoer-inc\")")]
      (is (nil? (:error r-idx)) "no 'Could not resolve symbol: get-index'")
      (is (nil? (:error r-kw)))
      (is (= [["get-index" {}] ["get-company" {:slug "acoer-inc"}]] @calls)
          "both calls reached call-tool-fn with the right (tool-name args-map)"))))

(deftest rlm-sandbox-tool-binding-is-additive
  (testing "no :call-tool-fn → clean no-op; bare primitives unaffected"
    (let [sandbox (service-processors/build-rlm-context {:provider :openrouter :blackboard {} :inputs {}})
          r       (service-logic/execute-rlm-code sandbox "(+ 1 2)")]
      (is (nil? (:error r)))
      (is (= "3" (:result r)))))
  (testing "an UNdeclared tool stays unresolved (only :mcp-tools are bound)"
    (let [sandbox (service-processors/build-rlm-context
                   {:provider :openrouter :blackboard {} :inputs {}
                    :call-tool-fn (fn [_ _] :x) :mcp-tools ["get-index"]})
          r (service-logic/execute-rlm-code sandbox "(no-such-tool)")]
      (is (some? (:error r))))))


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
          invalid-event {:tree-id (random-uuid)}]
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


(deftest executor-detects-emit-tree-and-includes-raw-tree
  (testing "execute-repl-researcher-rlm detects emit-tree! and includes raw tree in result"
    (let [
          call-count (atom 0)]
      (with-redefs [ai.obney.orc.llm.interface/predict
                    (fn [provider module inputs opts]
                      (swap! call-count inc)
                      {:outputs {:code "(emit-tree!
                                          [:sequence
                                            [:llm {:instruction \"Test\" :reads [:doc] :writes [:out]}]
                                            [:final {:keys [:summary]}]])"}
                       :usage {:prompt_tokens 100 :completion_tokens 80 :total_tokens 180}})
                    service-processors/execute-tree
                    (fn [tree context options]
                      {:status :success
                       :outputs {:summary "Mock Phase 2 output"}
                       :duration-ms 1})]
        (let [node {:type :repl-researcher
                    :instruction "Generate a BT"
                    :reads [:document]
                    :writes [:summary]
                    :rlm {:recursive? false}
                    :max-iterations 5}
              blackboard {:document {:key :document :schema :string :value "test doc" :version 1}}
              result (service-processors/execute-repl-researcher-rlm
                       node blackboard :openrouter {})]
          (is (= :success (:status result)) "Should return :success from Phase 2 execution")
          (is (some? (:generated-tree-raw result)) "Should have :generated-tree-raw")
          (is (vector? (:generated-tree-raw result)) "Raw tree should be vector")
          (is (= :sequence (first (:generated-tree-raw result))) "Should start with :sequence")
          )))))


(deftest llm-primitive-propagates-image-field-type-to-module
  (testing "blackboard schema [:string {:field-type :image}] -> module input :type :image"
    (let [captured (atom nil)]
      (with-redefs [llm/predict
                    (fn [_provider module inputs _opts]
                      (reset! captured {:module module :inputs inputs})
                      {:outputs {:answer "ok"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [blackboard {:image {:key :image
                                  :schema [:string {:field-type :image}]
                                  :value "data:image/png;base64,abc123"
                                  :version 1}
                      :answer {:key :answer :schema :string}}
              sandbox-vars (atom {})
              usage-tracker (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
              context {:provider :openrouter
                       :blackboard blackboard
                       :sandbox-vars @sandbox-vars
                       :usage-tracker usage-tracker}]
          (service-processors/execute-llm-primitive
            "vision-call"
            {:instruction "What is in this image?"
             :reads [:image]
             :writes [:answer]}
            context)
          (let [{:keys [module]} @captured
                image-input (first (filter #(= :image (:name %)) (:inputs module)))]
            (is (some? image-input) "module :inputs should include :image entry")
            (is (= :image (:type image-input))
                "image-typed blackboard schema must propagate :type :image to the llm module input")))))))


(deftest quoted-tree-captures-exact-source-before-compilation
  (testing "emit-tree! keeps exact source text while compiling a separate executable tree"
    (let [source-tree
          '[:sequence
            [:code {:fn (fn [{:keys [inputs]}]
                          {:doubled (* 2 (:n inputs))})
                    :reads [:n]
                    :writes [:doubled]}]
            [:final {:keys [:doubled]}]]
          sandbox (service-processors/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :inputs {}
                    :durable-source-required? true})
          result (service-logic/execute-rlm-code
                  sandbox
                  (str "(emit-tree! (quote " (pr-str source-tree) "))"))
          vars @(:sandbox-vars sandbox)]
      (is (nil? (:error result)) (pr-str result))
      (is (= source-tree (:generated-tree-raw vars)))
      (is (= source-tree (read-string (:generated-tree-source vars))))
      (is (not (str/includes? (:generated-tree-source vars) "<inline-fn>")))
      (is (some fn? (tree-seq coll? seq (:generated-tree vars)))
          "the separate canonical representation contains compiled code"))))


(deftest llm-node-preserves-output-schemas
  (testing ":llm node with :output-schemas → canonical form preserves the schemas map"
    (let [result (service-logic/rlm-dsl->orc-dsl
                   [:llm {:instruction "Extract targets"
                          :reads [:page_text]
                          :writes [:targets]
                          :output-schemas {:targets [:vector [:map [:name :string]]]}}])
          opts (apply hash-map (rest result))]
      (is (= 'sheet/llm (first result)))
      (is (= [:targets] (:writes opts)))
      (is (= {:targets [:vector [:map [:name :string]]]}
             (:output-schemas opts))
          ":output-schemas must round-trip through the DSL translator"))))

(deftest extract-key-schemas-collects-from-llm-nodes
  (testing "extract-key-schemas walks a tree and collects {write-key → schema} from :llm :output-schemas"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "Pass 1"
                             :reads [:page_text]
                             :writes [:targets]
                             :output-schemas {:targets [:vector [:map [:name :string]]]})
                  (sheet/llm :instruction "Pass 2 with no schemas declared"
                             :reads [:targets]
                             :writes [:summary]))
          schemas (#'service-logic/extract-key-schemas tree)]
      (is (= [:vector [:map [:name :string]]] (get schemas :targets))
          "schema for :targets collected from first :llm node")
      (is (nil? (get schemas :summary))
          "no schema collected when :output-schemas wasn't declared"))))


(deftest preview-value-truncates-large-string-elements-in-vector
  (testing "U12: A vector containing a 10KB string element gets a small
            preview, not the full 10KB inlined."
    (let [big-string (apply str (repeat 10000 "x"))
          small-vec [1 2 3]
          mixed-vec [big-string small-vec :keyword 42]
          preview (service-logic/preview-value mixed-vec :max-sample 4)
          sample (:sample preview)]
      (is (= :vector (:type preview))
          "Result should be a :vector preview shape")
      (is (= 4 (:length preview))
          ":length reflects the original element count")
      (let [first-sample (first sample)]
        (is (map? first-sample)
            "Large-string element should be a preview map, not the raw string")
        (is (= :string (:type first-sample))
            "Preview map for the big string should be :type :string")
        (is (= 10000 (:size first-sample))
            ":size should be the original length"))
      (is (map? (second sample))
          "Collection element should be previewed (not raw)")
      (is (= :keyword (nth sample 2))
          "Keyword primitive should pass through unchanged")
      (is (= 42 (nth sample 3))
          "Number primitive should pass through unchanged"))))


(deftest researcher-iterations-event-schema-validates
  (testing "U10: :rlm/researcher-iterations is registered as an event schema
            and validates a representative event body."
    (let [schema (get schemas/events :rlm/researcher-iterations)
          valid-body {:execution-id (random-uuid)
                      :iterations [{:code "(+ 1 2)" :result 3 :stdout ""}]
                      :iteration-count 1
                      :emitted-at "2026-05-20T19:00:00.000Z"}]
      (is (some? schema)
          ":rlm/researcher-iterations must be registered in event-schemas")
      (is (m/validate schema valid-body)
          (str "Sample event body should validate; explain: "
               (pr-str (m/explain schema valid-body)))))))


(deftest inject-sub-model-injects-into-llm-without-model
  (testing "PR-Dual-Model: walks canonical tree, injects :model into sheet/llm
            nodes lacking it. :llm with explicit :model stays untouched."
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "no model"
                             :reads [:a]
                             :writes [:b])
                  (sheet/llm :instruction "explicit model"
                             :reads [:b]
                             :writes [:c]
                             :model "openai/gpt-4o"))
          inject #'service-logic/inject-sub-model
          result (inject tree "openai/gpt-5.1-chat")
          children (rest result)
          first-llm-opts (apply hash-map (rest (first children)))
          second-llm-opts (apply hash-map (rest (second children)))]
      (is (= "openai/gpt-5.1-chat" (:model first-llm-opts))
          ":llm without :model should get sub-model injected")
      (is (= "openai/gpt-4o" (:model second-llm-opts))
          ":llm with explicit :model should be untouched")))

  (testing "PR-Dual-Model: nil sub-model is a no-op"
    (let [tree '(sheet/sequence
                  (sheet/llm :instruction "x" :reads [:a] :writes [:b]))
          inject #'service-logic/inject-sub-model]
      (is (= tree (inject tree nil))
          "nil sub-model returns tree unchanged"))))


(deftest validate-final-rejects-all-empty-output
  (testing "validate-final! throws when every declared write maps to nil/empty"
    (is (thrown? Exception
                 (service-logic/validate-final!
                   {:total-redactions nil
                    :targets-applied []
                    :redacted-text-per-page []}
                   [:total-redactions :targets-applied :redacted-text-per-page]))
        "All-empty-values output should be rejected — model called final! without doing work")

    (is (thrown? Exception
                 (service-logic/validate-final!
                   {:answer ""}
                   [:answer]))
        "Empty-string single-write output should be rejected")

    (is (thrown? Exception
                 (service-logic/validate-final!
                   {:results nil}
                   [:results]))
        "Single nil output should be rejected")))

(deftest validate-final-allows-partial-empty-output
  (testing "validate-final! ACCEPTS outputs with at least one non-empty value"
    (is (some? (service-logic/validate-final!
                 {:total-redactions 5
                  :targets-applied []
                  :targets-missing []}
                 [:total-redactions :targets-applied :targets-missing]))
        "Mixed empty + non-empty is legitimate (e.g. zero misses)")

    (is (some? (service-logic/validate-final!
                 {:answer "the answer"}
                 [:answer]))
        "Single non-empty write passes")

    (is (some? (service-logic/validate-final!
                 {:invoices [{:vendor "Acme"}] :total-amount 0.0}
                 [:invoices :total-amount]))
        "Non-empty vector + zero number is legitimate")))

(deftest validate-final-still-checks-key-presence
  (testing "Existing missing-key + extra-key validation is preserved"
    (is (thrown? Exception
                 (service-logic/validate-final!
                   {:k1 "x"}
                   [:k1 :k2]))
        "Missing key throws (unchanged from existing behavior)")

    (is (thrown? Exception
                 (service-logic/validate-final!
                   {:k1 "x" :k2 "y" :extra "oops"}
                   [:k1 :k2]))
        "Extra key throws (unchanged from existing behavior)")))


(deftest recursive-prompt-nudges-verify-before-final
  (testing "R-7c: recursive-mode prompt tells the model to peek at output values before (final! ...)"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? true}
                :writes [:answer]
                :instruction "Find facts."}
          module (build-fn node {} [] {} {} {})
          instructions (:instructions module)]
      (is (string? instructions))
      (is (re-find #"(?i)verify before final" instructions)
          "Recursive-mode prompt contains the literal sentinel 'verify before final' so future audits can pin it")
      (is (re-find #"\(get-var" instructions)
          "Prompt mentions (get-var ...) as the inspection primitive"))))

(deftest recursive-prompt-surfaces-nil-writes-recovery-signal
  (testing "C-Loop-4: the R-Recover section mentions :nil-writes as a recovery trigger so the model sees the signal even when :status :success"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? true}
                :writes [:answer]
                :instruction "Find facts."}
          module (build-fn node {} [] {} {} {})
          instructions (:instructions module)]
      (is (string? instructions))
      (is (re-find #"(?i):nil-writes" instructions)
          "Recursive-mode prompt mentions the :nil-writes summary field so the model knows the signal exists")
      (is (re-find #"(?i)nil.{0,40}empty|empty.{0,40}nil|nil/empty" instructions)
          "Prompt explains the nil-OR-empty semantics so the model treats both as the same signal")
      (is (re-find #"(?i)success.{0,300}nil-writes|nil-writes.{0,300}success" instructions)
          "Prompt explicitly connects :nil-writes to :status :success cases (not just failure) — the load-bearing point the risk-analysis case exposed"))))

(deftest recursive-prompt-leads-with-emit-tree-as-primary-work
  (testing "When :recursive? true, the system prompt frames emit-tree! as the primary work primitive"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? true}
                :writes [:answer]
                :instruction "Find facts."}
          module (build-fn node {} [] {} {} {})
          instructions (:instructions module)]
      (is (string? instructions) "module returns :instructions as a string")
      (is (re-find #"## Recursive" instructions)
          "Recursive section header is present")
      (is (re-find #"(?i)`?emit-tree!`?\s+is\s+(how|the)" instructions)
          "Recursive section should lead with 'emit-tree! is how/the [primary work]' — not 'When you call emit-tree!'")
      (is (re-find #"(?i)(should not|not your main|narrow inspection|prefer emit-tree)" instructions)
          "Prompt should warn against iterating direct (llm)/(code) calls as the main loop"))))

(deftest terminal-prompt-omitted-only-when-recursive-explicitly-false
  (testing "After R-Default: terminal mode is reached ONLY via explicit :rlm {:recursive? false}"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? false}
                :writes [:answer]
                :instruction "Find facts."}
          module (build-fn node {} [] {} {} {})
          instructions (:instructions module)]
      (is (string? instructions))
      (is (not (re-find #"## Recursive" instructions))
          "Explicit :recursive? false opts OUT of recursive mode (the escape hatch)"))))


(deftest compute-tree-result-summary-preserves-inline-source-in-tree-raw
  (testing "the recursive summary retains quoted code rather than a placeholder"
    (let [tree-raw '[:sequence
                     [:code {:fn (fn [{:keys [inputs]}]
                                   {:result (count (vals inputs))})
                             :reads [:input-a]
                             :writes [:result-a]}]
                     [:final {:keys [:result-a]}]]
          summary (service-logic/compute-tree-result-summary
                    {:phase2-result {:status :success
                                     :outputs {:result-a 42}
                                     :duration-ms 1000
                                     :trace-id (random-uuid)}
                     :tick-events []
                     :tree-raw tree-raw
                     :writes [:result-a]})]
      (is (= tree-raw (:tree-raw summary)))
      (is (seq? (get-in summary [:tree-raw 1 1 :fn])))
      (is (not (str/includes? (pr-str (:tree-raw summary)) "<inline-fn>")))
      (is (= :sequence (first (:tree-raw summary))))
      (is (= :code (first (second (:tree-raw summary))))))))

(deftest compute-tree-result-summary-leaves-string-fn-untouched
  (testing "Qualified-symbol-string :fn values are NOT replaced — already serializable"
    (let [tree-raw [:sequence
                    [:code {:fn "ai.obney.orc.predict-rlm-redaction-tools.interface/apply-redactions"
                            :reads [:targets] :writes [:out]}]
                    [:final {:keys [:out]}]]
          summary (service-logic/compute-tree-result-summary
                    {:phase2-result {:status :success
                                     :outputs {:out []}
                                     :duration-ms 500
                                     :trace-id (random-uuid)}
                     :tick-events []
                     :tree-raw tree-raw
                     :writes [:out]})
          fn-val (->> (:tree-raw summary)
                      (tree-seq coll? seq)
                      (filter #(and (map? %) (contains? % :fn)))
                      first
                      :fn)]
      (is (= "ai.obney.orc.predict-rlm-redaction-tools.interface/apply-redactions" fn-val)
          "Qualified-symbol-string :fn value passes through untouched"))))


(deftest repl-researcher-config-projection-preserves-sub-model
  (testing "Read-model projects :rlm map verbatim onto the node"
    (let [nodes* #'service-logic/nodes*
          node-id (random-uuid)
          event {:event/type :sheet/repl-researcher-config-set
                 :sheet-id (random-uuid)
                 :node-id node-id
                 :instruction "test"
                 :reads []
                 :writes [:answer]
                 :mcp-tools []
                 :browser-tools []
                 :model "openai/gpt-5.4"
                 :max-iterations 5
                 :rlm {:debug? true
                       :recursive? true
                       :sub-model "openai/gpt-5.1-chat"}}
          state (nodes* {node-id {:type :repl-researcher}} event)]
      (is (some? (get-in state [node-id :rlm]))
          "Projection includes :rlm on the node")
      (is (= "openai/gpt-5.1-chat" (get-in state [node-id :rlm :sub-model]))
          ":sub-model preserved in :rlm map")
      (is (true? (get-in state [node-id :rlm :recursive?]))
          ":recursive? preserved in :rlm map")
      (is (true? (get-in state [node-id :rlm :debug?]))
          ":debug? preserved in :rlm map"))))


(deftest recursive-prompt-contrasts-get-var-vs-get-input-for-tree-outputs
  (testing "When :recursive? true, the prompt explicitly tells the model that tree outputs use get-var (not get-input)"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? true}
                :writes [:answer]
                :instruction "Find facts."}
          module (build-fn node {} [] {} {} {})
          instructions (:instructions module)]
      (is (re-find #"(?i)`?get-var`?.*(prior|previous|tree.*output|already)" instructions)
          "Prompt should explain that get-var accesses prior tree outputs")
      (is (re-find #"(?i)(not|don'?t).*get-input.*(tree|prior|output)" instructions)
          "Prompt should warn against using get-input for tree outputs"))))


(deftest build-iteration-history-shows-parse-error-line-with-caret
  (testing "When :error is a SCI parse error with [line col], history shows the offending line + caret"
    (let [bih #'service-logic/build-iteration-history
          code (str "(emit-tree!\n"
                    " [:sequence\n"
                    "  [:llm {:reads [:x] :writes [:y]]\n"
                    "  [:final {:keys [:y]}]])")
          history [{:code code
                    :error "Unmatched delimiter: ], expected: } to match { at [3 12]"
                    :result ""
                    :vars-created []}]
          out (bih history)]
      (is (string? out) "history is a string")
      (is (re-find #"Unmatched delimiter" out)
          "Original error message preserved")
      (is (re-find #"\[:llm \{:reads \[:x\] :writes \[:y\]\]" out)
          "Offending line surfaced in history")
      (is (re-find #"\^" out)
          "Caret marker '^' appears to pin the error column"))))

(deftest build-iteration-history-leaves-non-parse-errors-untouched
  (testing "Non-parse errors (e.g. final! validation) do not get line/caret treatment"
    (let [bih #'service-logic/build-iteration-history
          code "(final! {})"
          history [{:code code
                    :error "final! called with all empty values"
                    :result ""
                    :vars-created []}]
          out (bih history)]
      (is (re-find #"final! called with all empty values" out)
          "Original error message preserved as-is")
      (is (not (re-find #"\^" out))
          "Non-parse errors do not get a caret marker"))))


(deftest recursive-prompt-includes-section-when-rlm-is-true-boolean
  (testing "When :rlm is just `true` (legacy shorthand), the recursive-mode section IS in the prompt"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm true :writes [:answer] :instruction "test"}
          module (build-fn node {} [] {} {} {})]
      (is (re-find #"## Recursive" (:instructions module))
          ":rlm true should default to recursive mode (after R-Default)"))))

(deftest recursive-prompt-omitted-when-rlm-recursive-explicitly-false
  (testing "When :rlm {:recursive? false}, the recursive-mode section is OMITTED (terminal mode escape hatch)"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:recursive? false} :writes [:answer] :instruction "test"}
          module (build-fn node {} [] {} {} {})]
      (is (not (re-find #"## Recursive" (:instructions module)))
          ":rlm {:recursive? false} should preserve terminal-mode behavior"))))

(deftest recursive-prompt-included-when-rlm-map-without-recursive-key
  (testing "When :rlm is a map without :recursive? (e.g. {:debug? true}), recursive is still the default"
    (let [build-fn #'service-logic/build-rlm-code-generation-module
          node {:rlm {:debug? true} :writes [:answer] :instruction "test"}
          module (build-fn node {} [] {} {} {})]
      (is (re-find #"## Recursive" (:instructions module))
          "Missing :recursive? key should default to recursive mode"))))
