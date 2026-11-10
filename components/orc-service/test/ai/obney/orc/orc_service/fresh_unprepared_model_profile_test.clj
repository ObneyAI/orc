(ns ai.obney.orc.orc-service.fresh-unprepared-model-profile-test
  "Controlled actual HTTP/SQLite fresh opt-out profile inheritance. No recovery claim."
  (:require [clojure.java.io :as io] [clojure.string :as str]
            [clojure.test :refer [deftest is]] [cheshire.core :as json]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as p]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.llm.interface :as llm]))

(def packet {:label "original-source" :nested {:count 0 :enabled false :note nil} :flags [true false]})
(def packet-schema
  [:map {:closed true :description "Original nested packet; preserve every native value."}
   [:label [:string {:description "Original source label, copied verbatim."}]]
   [:nested [:map {:closed true :description "Nested observations, including zero, false and null."}
             [:count [:int {:description "Exact observed count; zero is a real value."}]]
             [:enabled [:boolean {:description "Observed enabled flag; false must be retained."}]]
             [:note [:maybe {:description "Original nullable note; null is not an omitted field."} :string]]]]
   [:flags [:vector {:description "Ordered boolean observations."} :boolean]]])
(def prime-code "(final! {:summary (get-input :packet)})")
(def inline-code "(do (let [observed (touch {})] (when-not (= {:ok true} observed) (throw (ex-info \"Tool did not return its contracted result\" {})))) (llm \"inline\" :instruction \"Copy the original nested packet verbatim, preserving zero, false and null.\" :reads [:source :packet] :writes [:detail]) (final! {:summary (get-var :detail)}))")
(def reasoning "Preserve the supplied packet through the requested typed execution path.")
(def proofs (atom []))

(defn response-for [role body]
  ;; Like the existing public fixture, answer in the protocol ACTUALLY requested.
  ;; This lets the intended wire-profile assertions fail even if both protocols
  ;; happen to decode successfully. It never repairs production model output.
  (let [outer? (or (= role :prime) (= "fixture/outer" (:model body)))
        outputs (if outer? {:reasoning reasoning :code (if (= role :prime) prime-code inline-code)} {:detail packet})
        tools? (seq (:tools body))
        content (if (:response_format body) (json/generate-string outputs)
                    (apply str (concat (mapcat (fn [[k v]] [(str "[[ ## " (name k) " ## ]]\n")
                                                       (if (string? v) v (json/generate-string v)) "\n"]) outputs)
                                       ["[[ ## completed ## ]]\n"])))]
    {:id (str "controlled-optout-" (name role) "-response")
     :model (str (:model body) "-resolved")
     :choices [{:index 0 :finish_reason (if tools? "tool_calls" "stop")
                :message (if tools?
                           {:role "assistant" :content nil
                            :tool_calls [{:id "controlled-optout-call" :type "function"
                                          :function {:name "submit_response" :arguments (json/generate-string outputs)}}]}
                           {:role "assistant" :content content})}]
     :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}}))

(defn definition [role primary backup primary-only?]
  (orc/workflow (str "fresh-optout-profile-" (name role) "-" (random-uuid))
    (orc/blackboard {:source [:string {:description "Original prefix label supplied once."}]
                     :packet packet-schema :detail packet-schema :summary packet-schema})
    (orc/sequence "task"
      (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!" :reads [] :writes [:source])
      (apply orc/repl-researcher "research"
        (concat
         [:instruction "Preserve the original typed packet and its false, zero and null values. Use final! to deliver summary."
          :reads [:source :packet] :writes [:summary] :max-iterations 1
          :options (cond-> {:max-retries 0 :max-tokens 65536 :with-provider-evidence? true}
                     (= role :inline) (assoc :output-mode :json-schema))
          :rlm (cond-> {:recursive? true :checkpointed? false
                        :timeouts {:provider-ms 10000 :iteration-ms 30000 :campaign-ms 60000}}
                 (= role :inline)
                 (assoc :sub-provider primary :sub-model "fixture/prime"
                        :sub-options {:max-tokens 16384 :max-retries 0 :with-provider-evidence? true}
                        :sub-fallback-models [{:provider backup :model "fixture/sub-backup"}]))]
         (if (= role :prime)
           (if primary-only? (cond-> [:model "fixture/primary-only"]
                              (= :empty primary-only?) (into [:fallback-models []]))
                             [:model "fixture/prime" :fallback-models [{:provider backup :model "fixture/prime-backup"}]])
           [:model "fixture/outer" :mcp-tools ["touch"]
            :tool-contracts {"touch" {:arguments [:map {:closed true}]
                                     :result [:map {:closed true} [:ok :boolean]] :checkpoint-safe? true}}]))))))


(defn run-case! [role primary-only?]
  (let [case-name (if (= :empty primary-only?) "explicit-empty" (if primary-only? "primary-only" (name role)))
        parent (or (System/getProperty "orc.probe.out") (p/fresh-directory))
        out (io/file parent (str "fresh-optout-" case-name))
        _ (assert (not (.exists out)) "Never overwrite prior proof") _ (assert (.mkdirs out))
        db (io/file out "events.sqlite")
        fixture (p/server! (partial response-for role))
        primary (keyword (str "optout-primary-" (random-uuid)))
        backup (keyword (str "optout-backup-" (random-uuid)))
        outer (keyword (str "optout-outer-" (random-uuid)))
        tenant (random-uuid) tick (random-uuid) prefix-count (atom 0) tool-calls (atom [])
        predictor-calls (atom []) actual-predict llm/predict
        config {:provider :openrouter :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 10000}}]
    (try
      (llm/register-provider! primary (assoc config :model (if primary-only? "fixture/primary-only" "fixture/prime") :output-mode :function-calling))
      (llm/register-provider! backup (assoc config :model (if (= role :prime) "fixture/prime-backup" "fixture/sub-backup")))
      (llm/register-provider! outer (assoc config :model "fixture/outer" :output-mode :json-schema))
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str db) :maximum-pool-size 2}
                                     :context {:tenant-id tenant :llm-provider (if (= role :prime) primary outer)
                                               :probe-effects prefix-count
                                               ;; Context-free opt-out SCI uses the supported TWO-arg legacy seam.
                                               :call-tool-fn (fn [tool args] (swap! tool-calls conj [tool args]) {:ok true})}}]
        (let [workflow-definition (definition role primary backup primary-only?)
              sheet (orc/build-workflow! ctx workflow-definition)
              result (with-redefs [llm/predict (fn [provider module inputs options]
                                               (swap! predictor-calls conj {:provider provider :options options})
                                               (actual-predict provider module inputs options))]
                       (orc/execute ctx sheet {:packet packet} :tick-id tick :timeout-ms 60000 :max-ticks 1
                                    :llm-call-budget (if primary-only? 1 (if (= role :prime) 2 3))))
              events (orc/value-log-read-tick-events ctx tenant tick)
              bodies (mapv :body @(:requests fixture))
              candidates (filterv #(not= "fixture/outer" (:model %)) bodies)
              completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                              (= :repl-researcher (:node-type %))) events))
              writes (when completion (orc/value-log-resolve-writes ctx tenant tick completion))
              expected-models (cond primary-only? ["fixture/primary-only"]
                                    (= role :prime) ["fixture/prime" "fixture/prime-backup"]
                                    :else ["fixture/outer" "fixture/prime" "fixture/sub-backup"])
              proof {:kind :fresh-explicit-checkpoint-optout :role role :primary-only? primary-only?
                     :database (str db) :tenant tenant :sheet-id sheet :tick-id tick :definition workflow-definition
                     :result result :canonical-writes writes :tick (orc/get-tick ctx tick)
                     :events events :nodes (orc/get-nodes-for-sheet ctx sheet)
                     :requests @(:requests fixture) :responses @(:responses fixture) :fixture-errors @(:errors fixture)
                     :prefix-count @prefix-count :tool-calls @tool-calls :expected-packet packet
                     :predictor-calls @predictor-calls :paid-calls 0 :recovery-claim false}]
          (swap! proofs conj proof)
          (spit (io/file out "proof.edn") (pr-str proof))
          (is (empty? @(:errors fixture)))
          (is (= expected-models (mapv :model bodies)))
          (is (= (if primary-only? 1 2) (count candidates)))
          (doseq [body candidates]
            (is (= "auto" (:tool_choice body)))
            (is (= "submit_response" (get-in body [:tools 0 :function :name])))
            (is (true? (get-in body [:tools 0 :function :strict])))
            (is (not (contains? body :response_format)))
            (is (= (if (= role :prime) #{"reasoning" "code"} #{"detail"})
                   (set (get-in body [:tools 0 :function :parameters :required])))))
          (when-not primary-only?
            (is (= (get-in (first candidates) [:tools 0 :function :parameters])
                   (get-in (second candidates) [:tools 0 :function :parameters]))))
          (when (= role :inline)
            (doseq [body candidates]
              (let [guide (pr-str (:messages body))]
                (is (str/includes? guide "Original nested packet; preserve every native value."))
                (is (str/includes? guide "Observed enabled flag; false must be retained."))
                (is (str/includes? guide "Exact observed count; zero is a real value."))
                (is (str/includes? guide "Original nullable note; null is not an omitted field."))))
            (let [content (get-in (first candidates) [:messages 0 :content])
                  input-json (some-> (re-find #"(?m)^packet: (.+)$" content) second)]
              (is (= packet (when input-json (json/parse-string input-json true))))))
          (is (= :success (:status result)) (pr-str result))
          (is (= :success (:status completion)))
          (is (= packet (get-in result [:outputs :summary])))
          (is (= packet (:summary writes)))
          (is (= 1 @prefix-count))
          (is (= (if (= role :inline) [["touch" {}]] []) @tool-calls))
          (is (= (count expected-models) (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (let [reservations (filter #(= :sheet/provider-call-reserved (:event/type %)) events)]
            (is (every? #(= :ordinary (:admission-kind %)) reservations))
            (is (every? #(not-any? (partial contains? %) [:campaign-sheet-id :campaign-tick-id :campaign-node-id :ownership-epoch]) reservations)))
          (is (not-any? #(#{:rlm/researcher-frontier-claimed :sheet/model-plan-retained
                           :rlm/researcher-resume-state-saved :rlm/researcher-effect-claimed
                           :rlm/researcher-effect-completed :rlm/researcher-effect-indeterminate} (:event/type %)) events))
          (is (false? (get-in (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))) [:rlm :checkpointed?])))
          ;; These options govern predictor return/validation, not retained wire controls.
          ;; The spy delegates every call to the REAL predictor and loopback HTTP.
          (is (= (count expected-models) (count @predictor-calls)))
          (is (every? #(= true (get-in % [:options :with-metadata?])) @predictor-calls))
          (is (every? #(= false (get-in % [:options :validate?])) @predictor-calls))
          (is (every? #(= true (get-in % [:options :with-provider-evidence?])) @predictor-calls))))
      (finally (p/stop-fixture! fixture)))))

(deftest fresh-optout-prime-preserves-inherited-profile-through-backup
  (run-case! :prime false))

(deftest fresh-optout-inline-preserves-inherited-profile-through-backup
  (run-case! :inline false))

(deftest primary-only-optout-keeps-existing-predictor-contract
  (run-case! :prime true))

(deftest explicit-empty-optout-alternatives-keep-primary-contract
  (run-case! :prime :empty))
