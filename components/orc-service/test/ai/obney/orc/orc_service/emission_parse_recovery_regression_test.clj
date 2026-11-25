(ns ai.obney.orc.orc-service.emission-parse-recovery-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.native-regression-http :as http]
            [ai.obney.grain.event-store-sqlite-v3.interface]))

(def ^:private tree
  '[:sequence
    [:code {:reads [:packet] :writes [:child-value]
            :fn (fn [{:keys [inputs]}] {:child-value "executed-A"})}]
    [:final {:keys [:child-value]}]])
(def ^:private emission
  (str "(get-input :packet) (emit-tree! (quote " (pr-str tree) "))"))
(def ^:private probe-schema
  [:map {:closed true} [:count :int] [:reference :string]])

(defn- first-code [kind]
  (case kind
    :valid emission
    :after (str emission " )")
    :prior (str "(store! :retained (lab/probe {})) " emission)
    :keyseq "(store! :unsafe (keys {:a 1}))"
    :function "(store! :unsafe (fn [x] x))"))

(defn- source [kind n]
  (cond
    (= n 1) (first-code kind)
    (and (= kind :prior) (= n 2))
    (str "(store! :prior-raw (get-var :generated-tree-raw)) "
         "(store! :prior-source (get-var :generated-tree-source)) "
         (str/replace emission "executed-A" "REJECTED-B") " )")
    (= kind :prior)
    (str "(final! {:answer (str (get-var :child-value) \"|\" "
         "(= (get-var :prior-raw) (get-var :generated-tree-raw)) \"|\" "
         "(= (get-var :prior-source) (get-var :generated-tree-source)) \"|\" "
         "(:count (get-var :retained)) \"|\" (:reference (get-var :retained)))})")
    :else "(final! {:answer \"repaired\"})"))

(defn- run-case [kind]
  (let [effects (atom [])]
    (http/with-provider
      (fn [request n]
        (let [fields (keys (get-in request [:response_format :json_schema :schema :properties]))]
          (select-keys {:reasoning "Scripted native regression control." :code (source kind n)} fields)))
      (fn [provider calls]
        (h/with-async-test-context
          [ctx {:event-store-conn {:type :sqlite
                                  :database-file (str "/tmp/emission-recovery-regression-" (random-uuid) ".db")
                                  :maximum-pool-size 2}
                :context {:llm-provider provider
                          :call-tool-fn (fn [tool arguments _]
                                          (assert (= ["lab/probe" {}] [tool arguments]))
                                          (let [value {:count (inc (count @effects)) :reference (str (random-uuid))}]
                                            (swap! effects conj value) value))}}]
          (let [built (orc/build-workflow! ctx
                        (orc/workflow (str "emission-recovery-regression-" (random-uuid))
                          (orc/blackboard {:packet [:map {:closed true} [:goal :string]]
                                           :answer :string :child-value :string :retained probe-schema})
                          (orc/repl-researcher "checkpointed-recursive-error-repair"
                            :reads [:packet] :writes [:answer] :model http/model :options http/options
                            :instruction "Use quoted reconstructible code. Repair syntax errors in a later iteration."
                            :mcp-tools ["lab/probe"]
                            :tool-contracts {"lab/probe" {:arguments [:map {:closed true}]
                                                         :result probe-schema :checkpoint-safe? true}}
                            :max-iterations 3
                            :rlm {:recursive? true :checkpointed? true
                                  :timeouts {:provider-ms 30000 :iteration-ms 40000
                                             :phase2-ms 40000 :campaign-ms 120000}})))
                root (random-uuid)
                _ (orc/execute ctx built {:packet {:goal "Preserve supported recovery and durable value policy"}}
                               :tick-id root :timeout-ms 120000 :max-ticks 4 :llm-call-budget 8)
                canonical (orc/get-execution-result ctx root)
                family (orc/get-execution-family ctx root)
                researchers (filter #(= :repl-researcher (:node-type %)) family)
                receipts (mapcat :tool-receipts researchers)
                error-iteration (if (= kind :prior) 1 0)]
            {:canonical canonical :family family :requests @calls :effects @effects
             :code-dispatches (count (filter #(= :code (:executor %)) family))
             :completed-tool-receipts (filterv #(and (= :tool (:kind %)) (= :completed (:status %))) receipts)
             :original-error-retained?
             (boolean (some #(and (= (source kind (inc error-iteration)) (:code %))
                                  (str/includes? (str (:error %) (:error-excerpt %)) "Unmatched delimiter"))
                            (mapcat #(get-in % [:outputs :iterations]) researchers)))}))))))

(deftest failed-partial-emission-repairs-without-dispatching-rejected-tree
  (doseq [[kind requests dispatches] [[:valid 2 1] [:after 2 0] [:prior 3 1]]]
    (testing (name kind)
      (let [{:keys [canonical effects code-dispatches original-error-retained? completed-tool-receipts]
             :as result} (run-case kind)]
        (is (= :success (:status canonical)))
        (is (= requests (count (:requests result))))
        (is (= dispatches code-dispatches))
        (if (= :prior kind)
          (do
            (is (= 1 (count effects) (count completed-tool-receipts)))
            (is (= (first effects) (:result (first completed-tool-receipts))))
            (is (= (str "executed-A|true|true|1|" (:reference (first effects)))
                   (get-in canonical [:outputs :answer])))
            (is original-error-retained?))
          (do
            (is (= "repaired" (get-in canonical [:outputs :answer])))
            (when (= :after kind) (is original-error-retained?))))))))

(deftest user-nondurable-scratch-is-still-refused
  (doseq [[kind marker] [[:keyseq "KeySeq"] [:function "kind function"]]]
    (testing (name kind)
      (let [{:keys [canonical requests code-dispatches family]} (run-case kind)
            failures (keep #(get-in % [:failure :message]) family)]
        (is (= :failure (:status canonical)))
        (is (= 1 (count requests)))
        (is (zero? code-dispatches))
        (is (some #(and (str/includes? % "[:sandbox-vars :unsafe]")
                        (str/includes? % marker)) failures))))))
