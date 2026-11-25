(ns ai.obney.orc.orc-service.recoverable-generated-function-context-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [ai.obney.orc.orc-service.core.rlm-dsl :as dsl]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as sandbox]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree]))

(defn emit-program [prefix form]
  (str prefix "\n(emit-tree! [:code {:reads [:n] :writes [:total]"
       " :output-schemas {:total [:int {:description \"Exact computed quantity.\"}]}"
       " :fn '" (pr-str form) "}])"))

(defn run-source [prefix form]
  (let [context (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
        result (sandbox/execute-rlm-code context (emit-program prefix form))]
    [context result]))

(deftest captured-or-shadowed-global-resolution-is-rejected-before-publication
  (doseq [[prefix form]
          [["(def inc (fn [n] (+ n 999)))"
            '(fn [{:keys [inputs]}] {:total (inc (:n inputs))})]
           ["(def helper (fn [n] (+ n 999)))"
            '(fn [{:keys [inputs]}] {:total (helper (:n inputs))})]]]
    (let [[context result] (run-source prefix form)]
      (is (some? (:error result)) (pr-str result))
      (is (nil? (:generated-tree @(:sandbox-vars context)))))))

(deftest lexical-shadowing-preserves-accepted-and-restored-function-semantics
  (let [[context result]
        (run-source "(def inc (fn [n] (+ n 999)))"
                    '(fn [{:keys [inputs]}]
                       (let [inc (fn [n] (+ n 10))]
                         {:total (inc (:n inputs))})))
        opts (when-let [tree (:generated-tree @(:sandbox-vars context))]
               (apply hash-map (rest tree)))
        f (:fn opts)
        key (str "ephemeral-fn-" (random-uuid))]
    (try
      (is (nil? (:error result)) (pr-str result))
      (is (fn? f) "Accepted source must publish an executable function")
      (when f
        (let [first-result (f {:inputs {:n 4}})
              restored (executor/execute-code
                        {:fn key :recoverable-fn (:orc/recoverable-fn (meta f))
                         :reads [:n] :writes [:total]}
                        {:n {:value 4}} {})]
          (is (= {:total 14} first-result))
          (is (= :success (:status restored)))
          (is (= first-result (:outputs restored)))))
      (finally (tree/clear-ephemeral-fn! key)))))

(defn- rejected [f value]
  (try (f value) nil (catch Exception e (ex-data e))))

(deftest prevalidation-reuses-the-existing-vector-grammar-without-changing-it
  (doseq [valid [[:sequence] [:parallel] [:final {:keys [:x]}]
                 [:llm {:reads [] :writes [:x] :instruction "Existing model form."}]
                 [:chunk-document {:from :doc :size 10 :into :chunks}]
                 [:aggregate {:from :items :writes [:out]}]
                 [:code {:reads [] :writes [:x] :fn "clojure.core/identity"}]
                 [:map-each {:from :items :as :item :into :results}
                  [:code {:reads [:item] :writes [:x] :fn "clojure.core/identity"}]]]]
    (is (= valid (dsl/validate-emitted-tree! valid)))
    (is (seq? (dsl/rlm-dsl->orc-dsl valid))))
  (doseq [invalid [nil '() [:new-node {}] [:code {} :extra]
                   [:map-each {}] [:sequence [:code {}] [:bad {}]]]]
    (let [validation (rejected dsl/validate-emitted-tree! invalid)
          transformation (rejected dsl/rlm-dsl->orc-dsl invalid)]
      (is (some? validation))
      (is (= validation transformation))))
  (let [quoted [:code {:reads [] :writes [:total] :fn '(fn [_] {:total 7})}]]
    (is (= quoted (dsl/validate-emitted-tree! quoted)))
    (is (seq? (get-in quoted [1 :fn])))))

(deftest malformed-emission-feedback-retains-only-authored-data
  (let [context (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
        malformed [:sequence [:code {:reads [] :writes [:total] :fn '(fn [_] {:total 7})}]
                   [:new-node {}]]
        result (sandbox/execute-rlm-code context (str "(emit-tree! '" (pr-str malformed) ")"))
        data (:error-data result)]
    (is (some? (:error result)))
    (is (= :invalid-emitted-tree (:failure-kind data)))
    (is (not-any? fn? (tree-seq coll? seq data)))
    (is (= data (edn/read-string (pr-str data))))
    (is (nil? (:generated-tree @(:sandbox-vars context))))))
