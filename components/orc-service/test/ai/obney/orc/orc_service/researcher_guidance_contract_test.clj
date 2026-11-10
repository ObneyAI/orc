(ns ai.obney.orc.orc-service.researcher-guidance-contract-test
  "MODEL-CONTRACT-13: actual prime requests, parser feedback, inline contract
   reuse and native results through public runtime, SQLite and loopback HTTP."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as sandbox]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def proof (atom []))
(def packet {:count 0 :confirmed false :record {:id "R1" :annotation nil}})
(def packet-schema
  [:map {:closed true}
   [:count :int] [:confirmed :boolean]
   [:record [:map {:closed true} [:id :string] [:annotation [:maybe :string]]]]])
(def summary-schema
  [:map {:closed true :description "Reusable intermediate contract for an attributed result."}
   [:count [:int {:description "Observed count, including zero."}]]
   [:confirmed [:boolean {:description "Observed confirmation, including false."}]]
   [:record [:map {:closed true :description "Complete attribution record."}
             [:id [:string {:description "Stable source identifier."}]]
             [:annotation [:maybe {:description "Explicit null records no annotation."} :string]]]]])
(def repaired-program
  "(let [selected (get-input :packet)] (store! :work selected) (let [answer (llm \"summarize\" :instruction \"Return the controlled result.\" :reads [:work] :writes [:summary])] (final! {:result (:summary answer)})))")

(defn- fixture! [invalid-program]
  (let [requests (atom []) errors (atom []) prime-count (atom 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange)) request (json/parse-string raw true)
                  prime? (= "fixture/prime" (:model request))
                  output (if prime?
                           {:reasoning "Execute the controlled task." :code (if (= 1 (swap! prime-count inc)) invalid-program repaired-program)}
                           {:summary packet})
                  response (json/generate-string {:model (:model request)
                             :choices [{:index 0 :finish_reason "stop" :message {:role "assistant" :content (json/generate-string output)}}]
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
                  bytes (.getBytes response "UTF-8")]
              (swap! requests conj {:request request :request-body raw :response-body response})
              (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest public-researcher-guidance-follows-the-callable-contracts
  (reset! proof [])
  (doseq [[kind invalid-program] [[:inline "(let [answer 1) answer)"]
                                  [:emitted "(emit-tree! [:sequence [:final {:keys [:summary]}])"]]]
    (testing (name kind)
      (let [{:keys [server pool requests errors url]} (fixture! invalid-program)
            db (str "/tmp/researcher-guidance-" (random-uuid) ".sqlite")
            options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}]
        (try
          (llm/register-provider! :researcher-guidance
            {:provider :openrouter :model "fixture/prime"
             :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}})
          (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :researcher-guidance}
                                          :event-store-conn {:type :sqlite :database-file db :maximum-pool-size 4}}]
            (orc/build-workflow! ctx
              (orc/workflow "unrelated-contract-owner"
                (orc/blackboard {:private-contract [:string {:description "PRIVATE-OTHER-SHEET-CONTRACT"}]})
                (orc/code "unused" :reads [] :writes [] :fn "clojure.core/identity")))
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow (str "researcher-guidance-" (name kind))
                            (orc/blackboard {:packet packet-schema :work packet-schema :summary summary-schema :result packet-schema})
                            (orc/repl-researcher "research" :model "fixture/prime" :reads [:packet] :writes [:result]
                              :instruction "Execute the controlled task with the existing intermediate contract."
                              :max-iterations 2 :options options
                              :rlm {:recursive? true :checkpointed? true :sub-model "fixture/specialist" :sub-options options})))
                  result (orc/execute ctx sheet {:packet packet} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 3)
                  events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                  records (mapv :iteration-record (filter #(= :rlm/researcher-iteration-recorded (:event/type %)) events))
                  bodies (mapv :request @requests)
                  prompts (mapv #(get-in % [:messages 0 :content]) (take 2 bodies))
                  parser-line (some-> records first :error-excerpt str/split-lines first)
                  specialist-prompt (get-in (last bodies) [:messages 0 :content])]
              (swap! proof conj {:kind kind :result result :events events :requests @requests :fixture-errors @errors})
              (is (empty? @errors))
              (is (= :success (:status result)) (pr-str result))
              (is (= packet (get-in result [:outputs :result])))
              (is (= :success (:root-status (orc/get-tick ctx (:trace-id result)))))
              (is (= ["fixture/prime" "fixture/prime" "fixture/specialist"] (mapv :model bodies)))
              (is (= [:failure :success] (mapv :status records)))
              (is (str/includes? parser-line "expected: ]"))
              (is (str/includes? (second prompts) parser-line) "Parser evidence remains verbatim")
              (is (str/includes? specialist-prompt (str "work: " (json/generate-string packet))))
              (is (= summary-schema (get-in (orc/get-blackboard-by-key ctx sheet) [:summary :schema]))
                  "Instruction rendering cannot rewrite the declared contract")
              (doseq [prompt prompts]
                (is (str/includes? prompt "A nil lookup is not proof of a failed producer"))
                (is (str/includes? prompt "`count` realizes a finite lazy sequence"))
                (is (not (str/includes? prompt "Downstream operations on nil throw")))
                (is (not (str/includes? prompt "if the surrounding code only checks `(count")))
                (is (str/includes? prompt "Computed set elements must be distinct"))
                (is (str/includes? prompt "Preserve nil when it is a valid domain value"))
                (is (str/includes? prompt "(contains? (list-vars) :foo)"))
                (is (str/includes? prompt "false and explicit nil are values"))
                (is (str/includes? prompt "A retained value may be from an earlier successful producer"))
                (is (str/includes? prompt "Available examples include `clojure.string/join` and `clojure.set/intersection`"))
                (is (str/includes? prompt "Typed input values do not need string parsing"))
                (is (str/includes? prompt "Unbound symbols can fail during SCI analysis"))
                (doseq [stale ["Namespaced symbols don't resolve" "Set literals with computed elements fail SCI's reader"
                               "a pure-Clojure function is definitively correct" "returns `nil` after a Phase-2 `:failure`"]]
                  (is (not (str/includes? prompt stale))))
                (is (not (str/includes? prompt "Sub-LLM calls via :reads receive previews")))
                (is (not (str/includes? prompt "Injects each item as :item-name (as preview)")))
                (is (str/includes? prompt ":reads [...] passes FULL values"))
                (is (str/includes? prompt "Lexical bindings are not automatically keyed inputs"))
                (is (str/includes? prompt "(store! :work selected)"))
                (is (str/includes? prompt (str ":summary " (pr-str summary-schema))))
                (is (str/includes? prompt "Inline calls reuse these declared contracts"))
                (is (str/includes? prompt "`:output-schemas` belongs to emitted nodes"))
                (is (str/includes? prompt "You MUST call (final! {...}) with keys: [:result]"))
                (is (not (str/includes? prompt "PRIVATE-OTHER-SHEET-CONTRACT")))
                (is (not (str/includes? prompt "almost always means a nested map schema")))
                (is (not (str/includes? prompt "fix THAT brace"))))))
          (finally
            (.stop server 0) (.shutdownNow pool)
            (doseq [suffix ["" "-shm" "-wal"]] (.delete (java.io.File. (str db suffix))))))))))

(deftest practical-guidance-matches-current-sandbox-capabilities
  (let [vars (atom {:prior 7 :false-value false :null-value nil})
        ctx (sandbox/build-rlm-context {:blackboard {} :declared-writes [] :sandbox-vars vars})
        evaluate #(sandbox/execute-rlm-code ctx %)]
    (doseq [[code value] [["(clojure.string/join \",\" [\"a\" \"b\"])" "a,b"]
                          ["(clojure.set/intersection #{1 2} #{2 3})" #{2}]
                          ["(let [m {:a 1 :b 2}] #{(get m :a) (get m :b)})" #{1 2}]
                          ["(set [(get {:a 1} :a) (get {:b 1} :b)])" #{1}]
                          ["(frequencies [nil nil false 0])" {nil 2 false 1 0 1}]
                          ["[(contains? (list-vars) :null-value) (get-var :null-value) (contains? (list-vars) :absent) (get-var :false-value) (get-var :prior)]" [true nil false false 7]]]]
      (let [result (evaluate code)] (is (nil? (:error result)) code) (is (= value (:raw-result result)) code)))
    (let [counted (evaluate "(count (for [x [1 2]] (println x)))")
          dropped (evaluate "(let [unused (for [x [1 2]] (println x))] :not-consumed)")]
      (is (= 2 (:raw-result counted)))
      (is (= "1\n2\n" (:stdout counted)))
      (is (nil? (:error counted)))
      (is (= "" (:stdout dropped)))
      (is (= :not-consumed (:raw-result dropped))))
    (is (= [nil 0 []] (:raw-result (evaluate "[(get-in nil [:a]) (count nil) (mapv inc nil)]"))))
    (is (str/includes? (:error (evaluate "(let [m {:a 1 :b 1}] #{(get m :a) (get m :b)})")) "Duplicate key"))
    (is (str/includes? (:error (evaluate "(fn [x] (unbound-function x))")) "Could not resolve symbol"))
    (is (str/includes? (:error (evaluate "(Long/parseLong \"42\")")) "Could not resolve symbol"))
    (is (str/includes? (:error (evaluate "(parse-long \"42\")")) "Could not resolve symbol"))))
