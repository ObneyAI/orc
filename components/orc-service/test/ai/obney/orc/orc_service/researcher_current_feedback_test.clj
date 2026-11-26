(ns ai.obney.orc.orc-service.researcher-current-feedback-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.grain.time.interface :as time]))

(def contract
  [:map {:closed true :description "Complete measured result, preserving explicit empty values."}
   [:detail [:map {:closed true :description "Original measurement details."}
             [:count [:int {:description "Measured count; zero remains explicit."}]]
             [:active [:boolean {:description "False means explicitly inactive."}]]
             [:note [:maybe {:description "Null means explicitly withheld, not absent."} :string]]]]])
(def value {:detail {:count 0 :active false :note nil}})
(defn receive [{:keys [inputs]}] {:received (:research-result inputs)})
(defn emitted [extra]
  [:sequence [:llm (merge {:instruction "Preserve the complete supplied packet as summary."
                          :reads [:packet] :writes [:summary]} extra)]
   [:final {:keys [:summary]}]])

(defn run-case!
  ([tree] (run-case! tree false))
  ([tree reopen?]
  (let [dir (str (java.nio.file.Files/createTempDirectory "generated-contract-" (make-array java.nio.file.attribute.FileAttribute 0)) "/")
        requests (atom []) errors (atom []) primes (atom 0)
        phase (atom :initial) current-context (atom nil)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  _ (swap! requests conj (assoc request :fixture-phase @phase))
                  prime? (contains? (get-in request [:response_format :json_schema :schema :properties]) :code)
                  response (if prime?
                             {:reasoning "Execute the requested typed child."
                              :code (case (swap! primes inc)
                                      1 (str "(emit-tree! (quote " (pr-str tree) "))")
                                      2 "(do (when (contains? (list-vars) :nullable) (when-not (nil? (get-var :nullable)) (throw (ex-info \"Current null binding disagrees with completed producer\" {})))) (get-var :summary))"
                                      "(final! {:research-result (get-var :summary)})")}
                             value)
                  bytes (.getBytes (json/generate-string
                                    {:id (str (random-uuid)) :model "fixture/inherited-contract"
                                     :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string response)}}]
                                     :usage {:prompt_tokens 1 :completion_tokens 1}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [body (.getResponseBody exchange)] (.write body bytes)))
            (catch Throwable e
              (swap! errors conj (.getMessage e))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! :generated-contract
        {:provider :openrouter :model "fixture/inherited-contract" :output-mode :json-schema
         :config {:api-key "fixture" :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}})
      (let [tenant (random-uuid)
            context-options {:context {:tenant-id tenant :llm-provider :generated-contract}
                             :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite")}}
            ctx (h/create-async-test-context context-options)
            _ (reset! current-context ctx)
            sheet (bt/build-workflow! ctx
                      (bt/workflow "inherited-output-contract"
                        (bt/blackboard {:packet contract :summary contract :research-result contract :received contract})
                        (bt/sequence "compose"
                          (bt/repl-researcher "research" :model "fixture/inherited-contract"
                            :instruction "Emit the child, inspect its result, then return its typed summary."
                            :reads [:packet] :writes [:research-result] :max-iterations 3
                            :options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}
                            :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inherited-contract"
                                  :sub-options {:output-mode :json-schema :max-retries 0 :timeout-ms 10000}})
                          (bt/code "consume" :fn "ai.obney.orc.orc-service.researcher-current-feedback-test/receive"
                            :reads [:research-result] :writes [:received]))))
            researcher (:id (first (filter #(= "research" (:name %)) (bt/get-nodes-for-sheet ctx sheet))))
            tick (random-uuid) held (promise) dispatches (atom 0)
            original-dispatch processors/execute-repl-researcher-node]
        (with-redefs [processors/execute-repl-researcher-node
                      (fn [context]
                        (if (and reopen? (= :initial @phase)
                                 (= researcher (get-in context [:event :node-id]))
                                 (= 2 (swap! dispatches inc)))
                          (do (deliver held (:event context)) nil)
                          (original-dispatch context)))]
          (h/run-and-apply! ctx {:command/id (random-uuid) :command/timestamp (time/now)
                                :command/name :sheet/tick-tree :sheet-id sheet :tick-id tick
                                :inputs {:packet value} :options {:timeout-ms 60000 :llm-call-budget 5}})
          (when reopen?
            (let [frontier (deref held 20000 ::timeout)]
              (when (= ::timeout frontier) (throw (ex-info "Saved researcher frontier not reached" {:dispatches @dispatches :researcher researcher})))
              (h/stop-async-context ctx)
              (reset! current-context nil)
              (reset! phase :reopened)
              (let [fresh (h/create-async-test-context context-options)
                    clock (.plusSeconds (.toInstant ^java.time.OffsetDateTime (:event/timestamp frontier)) 3600)]
                (reset! current-context fresh)
                (bt/resume-in-progress! (assoc fresh :orc/clock-fn (constantly clock))))))
          (let [ctx @current-context]
            (when-not (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 30000)
              (throw (ex-info "Researcher did not finish" {})))
            (let [result (runtime/durable-terminal-result ctx tick)
                  events (h/read-all-events ctx)
                  family (bt/get-execution-family ctx tick)
                  writes (mapv #(bt/value-log-resolve-writes ctx tenant (:tick-id %) %)
                               (filter #(= :sheet/node-execution-completed (:event/type %)) events))
                  proof {:path dir :sheet sheet :result result :events events :family family :writes writes
                         :requests @requests :errors @errors :reopened? reopen?}]
              (spit (str dir "proof.edn") (pr-str proof))
              (println "GENERATED-CONTRACT-PROOF" dir)
              proof))))
      (finally
        (when-let [ctx @current-context] (h/stop-async-context ctx))
        (.stop server 0))))))

(deftest current-binding-preview-survives-checkpoint
  (let [{:keys [requests writes result]} (run-case! (emitted {}))
        primes (filter #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests)
        text (str/join "\n" (map :content (:messages (second primes))))]
    (is (= :success (:status result)))
    (is (some #(= value (:summary %)) writes))
    (is (str/includes? text ":summary (map"))
    (is (not (str/includes? text ":summary (nil) [from blackboard]")))
    (is (str/includes? text ":received (absent)"))))

(defn prime-texts [requests]
  (mapv #(str/join "\n" (map :content (:messages %)))
        (filter #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests)))

(deftest child-outcome-is-scoped-and-keeps-its-identity-after-inspection
  (let [{:keys [requests family sheet]} (run-case! (emitted {}))
        texts (prime-texts requests)
        child (first (filter #(and (not= sheet (:sheet-id %)) (= :ai (:executor %))) family))]
    (is (= 3 (count texts)))
    (is (uuid? (:tick-id child)))
    (doseq [text (rest texts)]
      (is (str/includes? text "Latest completed child outcome (previews)"))
      (is (str/includes? text "Tree executed — status: :success"))
      (is (str/includes? text (str (:tick-id child))))
      (is (str/includes? text "Outputs merged (readable via get-var)"))
      (is (str/includes? text "  :summary = "))
      (is (not (str/includes? text "NIL/EMPTY WRITES: :research-result")))
      (is (not (str/includes? text "  :packet = "))))))

(deftest completed-null-empty-false-zero-and-mixed-keys-are-truthful
  (let [schemas {:nullable [:maybe :string] :flag :boolean :count :int
                 :empty-items [:vector :string]
                 :mixed [:map {:closed true} [:same :int] ["same" :int] [0 :string]]}
        values {:summary value :nullable nil :flag false :count 0 :empty-items []
                :mixed {:same 1 "same" 2 0 "zero"}}
        tree [:sequence
              [:code {:fn '(fn [_] {:nullable "old"}) :writes [:nullable]
                      :output-schemas {:nullable [:maybe :string]}}]
              [:code {:fn (list 'fn ['_] values) :writes (vec (keys values))
                      :output-schemas schemas}]
              [:final {:keys [:summary :nullable :flag :count :empty-items :mixed]}]]
        {:keys [requests writes result]} (run-case! tree)
        text (second (prime-texts requests))]
    (is (= :success (:status result)))
    (is (some #(= "old" (:nullable %)) writes))
    (is (some #(and (contains? % :nullable) (nil? (:nullable %))) writes))
    (is (str/includes? text ":nullable (nil) [current local binding"))
    (is (str/includes? text "Value: false"))
    (is (str/includes? text "Value: 0"))
    (is (str/includes? text ":empty-items (vector, 0 items)"))
    (is (str/includes? text "  :nullable = nil"))
    (is (str/includes? text "  :flag = false"))
    (is (str/includes? text "  :count = 0"))
    (is (str/includes? text "  :mixed = "))
    (is (not (str/includes? text "did NOT land")))))

(deftest saved-child-outcome-reopens-without-replaying-completed-child
  (let [{:keys [requests result family sheet]} (run-case! (emitted {}) true)
        texts (prime-texts requests)
        child (first (filter #(and (not= sheet (:sheet-id %)) (= :ai (:executor %))) family))
        child-requests (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests)]
    (is (= :success (:status result)))
    (is (= 3 (count texts)))
    (is (uuid? (:tick-id child)))
    (is (= 1 (count child-requests)))
    (is (= :initial (:fixture-phase (first child-requests))))
    (is (= 2 (count (filter #(= :reopened (:fixture-phase %)) requests))))
    (doseq [text (rest texts)]
      (is (str/includes? text ":summary (map"))
      (is (str/includes? text "Tree executed — status: :success"))
      (is (str/includes? text (str (:tick-id child))))
      (is (str/includes? text "source iteration: 1")))))

(deftest partial-map-keeps-aggregate-and-item-ownership-separate
  (let [tree [:sequence
              [:code {:fn '(fn [_] {:items [1 2]}) :writes [:items]
                      :output-schemas {:items [:vector :int]}}]
              [:map-each {:from :items :as :item :into :mapped
                          :output-schemas {:item :int :mapped [:vector [:map [:part :int]]]}}
               [:code {:fn '(fn [{:keys [inputs]}]
                              (if (= 2 (:item inputs)) (throw (ex-info "Deliberate item failure" {}))
                                  {:part (:item inputs)}))
                       :reads [:item] :writes [:part] :output-schemas {:part :int}}]]
              [:code {:fn (list 'fn ['_] {:summary value :tree-results ["must not replace control state"]})
                      :writes [:summary :tree-results]
                      :output-schemas {:tree-results [:vector :string]}}]
              [:final {:keys [:summary :mapped]}]]
        {:keys [requests result family writes]} (run-case! tree)
        text (second (prime-texts requests))]
    (is (= 3 (count (prime-texts requests))))
    (is (some #(= :partial (:status %)) family))
    (is (some #(contains? % :mapped) writes) "Canonical parent write resolution includes its aggregate")
    (is (str/includes? text "Tree executed — status: :partial"))
    (is (str/includes? text "  :mapped = "))
    (is (str/includes? text "Per-item completed outputs"))
    (is (not (str/includes? text ":part (Long")))
    (is (str/includes? text "Child writes excluded from researcher control state: [:tree-results]"))
    (is (= :success (:status result)))))
