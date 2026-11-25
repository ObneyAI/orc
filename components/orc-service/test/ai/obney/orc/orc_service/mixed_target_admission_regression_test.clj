(ns ai.obney.orc.orc-service.mixed-target-admission-regression-test
  "Portable native SQLite/HTTP regression for actual mixed target ownership."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.native-regression-http :as http]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]))
(def text [:string {:description "Complete exact controlled source text; never a summary."}])
(def value-schema
 [:map {:closed true :description "Native controlled source with meaningful false, zero, nil and nested empty collections."}
  [:run-id text] [:zero [:int {:description "Explicit zero is a measured value."}]]
  [:active [:boolean {:description "False is a legitimate observed state."}]]
  [:nested [:map {:closed true :description "Retained native nested source, including required explicit null."}
            [:annotation [:maybe text]] [:items [:vector [:map {:closed true} [:id text] [:tags [:vector text]]]]]]]])
(def board (merge (zipmap [:seed :produced :emitted-result :inline-result :analysis :consumed] (repeat value-schema))
                  (zipmap [:producer-rationale :emitted-rationale :inline-rationale :consumer-rationale] (repeat text))))
(defn leaf [p name rationale reads write]
 (orc/llm name :model (:model p) :fallback-models [] :options (:node-options p)
  :instruction "Controlled native copy only. Preserve every exact original source value, including false/zero/nil and nested collections. No semantic quality claim."
  :reads reads :writes [rationale write]))
(defn researcher [p]
 (orc/repl-researcher "checkpointed-researcher" :model (:model p) :fallback-models [] :options (:node-options p)
  :reads [:produced] :writes [:analysis] :max-iterations 4 :browser-tools [] :mcp-tools []
  :instruction "Controlled construction only. Inspect produced; emit one typed ordinary LLM then in next iteration invoke one inline ordinary LLM with declared contracts. Retain exact source and final native analysis."
  :rlm {:recursive? true :checkpointed? true :timeouts {:provider-ms 15000 :iteration-ms 25000 :phase2-ms 25000 :campaign-ms 60000}}))
(defn build! [ctx p variant]
 (let [build (fn [label node] (orc/build-workflow! ctx (orc/workflow (str label "-" (random-uuid)) (orc/blackboard board) node)))
       producer #(leaf p "ordinary-producer" :producer-rationale [:seed] :produced)
       consumer #(leaf p "ordinary-consumer" :consumer-rationale [:produced :analysis] :consumed)
       child (build "delegate-target"
               (if (= :mixed variant) (orc/sequence "mixed-target" (producer) (researcher p) (consumer)) (researcher p)))
       root (build "root"
              (if (= :mixed variant)
                (orc/delegate "same-mixed-target" :target-sheet-id child :reads [:seed] :writes [:produced :analysis :consumed] :timeout-ms 60000)
                (orc/sequence "separated-control" (producer)
                 (orc/delegate "researcher-only-target" :target-sheet-id child :reads [:produced] :writes [:analysis] :timeout-ms 60000)
                 (consumer))))]
  {:child child :root root}))
(defn emitted [p]
 [:sequence [:llm {:model (:model p) :fallback-models [] :options (:node-options p)
                   :instruction "Controlled emitted native source copy."
                   :reads [:produced] :writes [:emitted-rationale :emitted-result]
                   :output-schemas {:emitted-rationale text :emitted-result value-schema}}]
  [:final {:keys [:emitted-result]}]])
(defn inline-source [p]
 (str "(llm \"controlled-inline\" "
      (pr-str {:model (:model p) :fallback-models [] :options (:node-options p)
               :instruction "Controlled inline native source copy." :reads [:produced :emitted-result] :writes [:inline-rationale :inline-result]})
      ") (final! {:analysis (get-var :inline-result)})"))

(defn- run-native-case! [budget]
  (let [profile {:model http/model :node-options http/options}
        value {:run-id (str (random-uuid)) :zero 0 :active false
               :nested {:annotation nil :items [{:id "nested-source" :tags []}]}}
        primes (atom 0) roles (atom []) reservations (atom [])
        original cp/process-command
        directory (.toFile (java.nio.file.Files/createTempDirectory "orc-mixed-admission-" (make-array java.nio.file.attribute.FileAttribute 0)))
        database (str (java.io.File. directory "events.sqlite"))
        tenant (random-uuid) tick (random-uuid)
        script (fn [request _]
                 (let [fields (set (keys (get-in request [:response_format :json_schema :schema :properties])))
                       role (cond (contains? fields :code) :prime
                                  (contains? fields :producer-rationale) :producer
                                  (contains? fields :emitted-rationale) :emitted
                                  (contains? fields :inline-rationale) :inline
                                  (contains? fields :consumer-rationale) :consumer
                                  :else (throw (ex-info "Unexpected controlled output contract" {:fields fields})))
                       response (if (= :prime role)
                                  {:reasoning "Controlled native source copy and ownership proof."
                                   :code (if (= 1 (swap! primes inc))
                                           (str "(get-input :produced) (emit-tree! (quote " (pr-str (emitted profile)) "))")
                                           (inline-source profile))}
                                  (merge value {:inline-result value :producer-rationale "Controlled ordinary producer."
                                                :emitted-rationale "Controlled emitted child." :inline-rationale "Controlled inline child."
                                                :consumer-rationale "Controlled ordinary consumer."}))]
                   (swap! roles conj role)
                   (select-keys response fields)))]
    (http/with-provider script
      (fn [provider calls]
        (h/with-async-test-context
          [ctx {:event-store-conn {:type :sqlite :database-file database :maximum-pool-size 2}
                :context {:tenant-id tenant :llm-provider provider}}]
          (let [built (build! ctx profile :mixed)
                execution
                (with-redefs [cp/process-command
                              (fn [context]
                                (let [result (original context)]
                                  (when (= :sheet/reserve-provider-call (get-in context [:command :command/name]))
                                    (swap! reservations conj {:command (:command context) :result (dissoc result :command-result/cas)}))
                                  result))]
                  (orc/execute ctx (:root built) {:seed value} :tick-id tick :timeout-ms 65000 :llm-call-budget budget))
                canonical (orc/get-execution-result ctx tick)
                family (orc/get-execution-family ctx tick)
                events (into [] (es/read (:event-store ctx) {:tenant-id tenant}))
                by-type (group-by :event/type events)
                accepted (remove #(contains? (:result %) :cognitect.anomalies/category) @reservations)
                refused (filter #(contains? (:result %) :cognitect.anomalies/category) @reservations)]
            (is (= budget (count @calls) (count accepted)))
            (is (= (count accepted) (count (set (map #(get-in % [:command :invocation-identity]) accepted)))))
            (is (every? #(and (= tick (get-in % [:command :budget-tick-id]))
                              (= (:root built) (get-in % [:command :budget-sheet-id]))) accepted))
            (is (every? #(and (uuid? (get-in % [:command :node-id]))
                              (uuid? (get-in % [:command :node-start-id]))) accepted))
            (is (every? #(and (uuid? (get-in % [:command :campaign-sheet-id]))
                              (uuid? (get-in % [:command :campaign-tick-id]))
                              (uuid? (get-in % [:command :campaign-node-id]))
                              (integer? (get-in % [:command :iteration-index]))
                              (pos-int? (get-in % [:command :ownership-epoch])))
                        (filter #(= :campaign (get-in % [:command :admission-kind])) accepted)))
            (if (= 6 budget)
              (do
                (is (= [:producer :prime :emitted :prime :inline :consumer] @roles))
                (is (= :success (:status execution) (:status canonical)))
                (is (= value (get-in canonical [:outputs :consumed])))
                (is (some #(and (= "ordinary-consumer" (:node-name %))
                                (= value (get-in % [:inputs :analysis]))
                                (= value (get-in % [:outputs :consumed]))) family))
                (is (= {:ordinary 2 :campaign 4} (frequencies (map #(get-in % [:command :admission-kind]) accepted))))
                (is (empty? refused))
                (is (= 2 (count (get by-type :rlm/researcher-iteration-recorded))))
                (is (= 2 (count (get by-type :rlm/researcher-resume-state-saved))))
                (is (seq (get by-type :rlm/researcher-frontier-claimed)))
                (is (every? #(pos-int? (:ownership-epoch %)) (get by-type :rlm/researcher-frontier-claimed)))
                (is (= 4 (count (get by-type :rlm/researcher-effect-claimed))
                          (count (get by-type :rlm/researcher-effect-completed)))))
              (do
                (is (= [:producer :prime :emitted :prime :inline] @roles))
                (is (= :failure (:status execution)))
                (is (true? (:cancelled? execution)))
                (is (= "LLM call budget exceeded: 5/5" (:error execution)))
                (is (nil? (get-in canonical [:outputs :consumed])))
                (is (= :cognitect.anomalies/conflict (get-in (last refused) [:result :cognitect.anomalies/category])))))))))))

(deftest mixed-delegated-target-keeps-ordinary-and-real-campaign-ownership
  (testing "Ordinary producer/consumer and actual prime/emitted/inline work share one positive durable family budget"
    (run-native-case! 6)))

(deftest mixed-delegated-target-refuses-next-call-at-original-budget
  (testing "A real native cap prevents the sixth HTTP dispatch without fabricating delivery or campaign identity"
    (run-native-case! 5)))
