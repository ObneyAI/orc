(ns ai.obney.orc.orc-service.researcher-native-evidence-test
  "Public HTTP/SIO/SQLite mechanics. Fixed programs are not model-adherence proof."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io] [clojure.string :as str] [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress] [java.nio.file Files]
           [java.util.concurrent Executors TimeUnit]))

(def last-proofs (atom []))
(def evidence-schema
  [:vector {:description "Authoritative observations in their original order; repeated identities remain distinct observations."}
   [:map {:closed true :description "One original observation; its identifiers and literal values belong to the source."}
    [:source-id [:string {:description "Opaque source identity; duplicates are permitted and meaningful."}]]
    [:reference [:string {:description "Exact opaque read reference, including slash count, escapes, query and fragment."}]]
    [:payload [:map {:closed true :description "Observed literal payload; negative, zero and null are actual values."}
               [:enabled? [:boolean {:description "Original boolean, including false."}]]
               [:count [:int {:min 0 :description "Original count, including zero."}]]
               [:note [:maybe {:description "Explicit absent note remains null."}
                       [:string {:description "Original note when present."}]]]]]]])
(def interpretation-schema
  [:map {:closed true :description "Semantic interpretation only; no copied or regenerated observation payload."}
   [:summary [:string {:description "Interpretive explanation, distinct from source-owned facts."}]]
   [:decision [:enum {:description "Interpretive recommendation; not a replacement for observed evidence."}
               "hold" "proceed" "unknown"]]])
(def result-schema
  [:map {:closed true :description "Code-assembled result separating original observations from semantic judgment."}
   [:evidence evidence-schema]
   [:interpretation interpretation-schema]])
(def native-evidence
  [{:source-id "id:Z#dup" :reference "read://harbor/%52?rev=8&sig=b17#current"
    :payload {:enabled? false :count 0 :note nil}}
   {:source-id "id:A" :reference "read://archive/A%2F7?opaque=Q+z&version=2"
    :payload {:enabled? true :count 7 :note "Original middle observation."}}
   {:source-id "id:Z#dup" :reference "read://harbor/other~Z?rev=2&sig=e44"
    :payload {:enabled? false :count 0 :note nil}}])
(def interpretation {:summary "SYNTHETIC interpretation of the supplied observations; no model-adherence claim."
                     :decision "hold"})
(def guidance-clauses
  ["Native evidence ownership"
   "Preserve native evidence unchanged"
   "Keep interpretation separate"
   "Use code to assemble"
   "Do not fuzzy-match, normalize, or repair identifiers"])

(def recursive-program
  (pr-str
    '(emit-tree!
       '[:sequence
         [:llm {:instruction "Interpret the supplied observations. Write only the declared interpretation; do not return a literal evidence payload."
                :reads [:evidence] :writes [:interpretation]}]
         [:code {:reads [:evidence :interpretation] :writes [:result]
                 :fn (fn [{:keys [inputs]}]
                       {:result {:evidence (:evidence inputs)
                                 :interpretation (:interpretation inputs)}})}]
         [:final {:keys [:result]}]])))
(defn programs [mode]
  (case mode
    ;; Construct the legacy marker only after SCI executes: a literal marker
    ;; in the generated source can be accepted as an already-terminal answer,
    ;; which would not prove that the native tool/assembly code actually ran.
    :legacy [(str "(let [observed (native/evidence {}) interpretation " (pr-str interpretation) "] "
                  "(str (str \"FINAL\" \"_ANSWER: \") (pr-str {:result {:evidence observed :interpretation interpretation}})))")]
    :terminal [(str "(let [observed (get-input :evidence) interpretation " (pr-str interpretation) "] "
                    "(final! {:result {:evidence observed :interpretation interpretation}}))")]
    :recursive [recursive-program "(final! {:result (get-var :result)})"]))

(defn request-text [request]
  (str/join "\n" (mapcat #(if (string? (:content %)) [(:content %)] (keep :text (:content %))) (:messages request))))
(defn start-http! [directory mode]
  (let [requests (atom []) errors (atom []) primes (atom 0)
        pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (.readAllBytes (.getRequestBody exchange))
                  request (json/parse-string (String. ^bytes raw "UTF-8") true)
                  fields (set (keys (get-in request [:response_format :json_schema :schema :properties])))
                  prime? (contains? fields :code)
                  output (if prime?
                           (let [ordinal (swap! primes inc)]
                             (assert (<= ordinal (count (programs mode))) "Unexpected extra prime call")
                             (cond-> {:code (nth (programs mode) (dec ordinal))}
                               (contains? fields :reasoning)
                               (assoc :reasoning "SYNTHETIC native-evidence mechanics control.")))
                           (do (assert (= :recursive mode) "Only recursive control has a semantic child")
                               interpretation))
                  response (json/generate-string
                             {:id (str "local-native-evidence-" (count @requests)) :model (:model request)
                              :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string output)}}]
                              :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
                  bytes (.getBytes response "UTF-8")
                  index (count @requests)]
              (Files/write (.toPath (io/file directory (str "request-" index ".json"))) raw (make-array java.nio.file.OpenOption 0))
              (spit (io/file directory (str "response-" index ".json")) response)
              (swap! requests conj {:request request :prime? prime? :response response :output output})
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e)) :message (ex-message e)})
              (try (.sendResponseHeaders exchange 503 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn run-case! [mode]
  (let [directory (str (Files/createTempDirectory "researcher-native-evidence-" (make-array java.nio.file.attribute.FileAttribute 0)))
        server (start-http! directory mode) provider (keyword (str "native-evidence-" (random-uuid)))
        model (str "fixture/native-evidence-" (name mode)) tool-calls (atom []) proof (atom nil)]
    (try
      (llm/register-provider! provider {:provider :openrouter :model model
                                      :config {:api-base (:api-base server) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider
                        :call-tool-fn (fn [tool args & _]
                                        (assert (= "native/evidence" tool)) (assert (= {} args))
                                        (swap! tool-calls conj {:tool tool :arguments args}) native-evidence)}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [rlm (case mode
                    :recursive {:recursive? true :checkpointed? true :auto-classify? false :sub-provider provider :sub-model model
                                :sub-options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                                :timeouts {:provider-ms 10000 :iteration-ms 30000 :phase2-ms 30000 :campaign-ms 60000}}
                    :terminal {:recursive? false :checkpointed? false :auto-classify? false
                               :timeouts {:provider-ms 10000 :iteration-ms 30000 :phase2-ms 30000 :campaign-ms 60000}}
                    nil)
              node (apply orc/repl-researcher "native-evidence-owner"
                     (concat [:provider provider :model model :reads [:evidence] :writes [:result]
                              :instruction "Investigate the supplied observations and return the declared result."
                              :mcp-tools ["native/evidence"] :browser-tools [] :max-iterations 2
                              :tool-contracts {"native/evidence" {:arguments [:map {:closed true :description "Retrieve the original native observations without edits."}]
                                                                  :result evidence-schema :checkpoint-safe? true}}
                              :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}]
                             (when rlm [:rlm rlm])))
              sheet (orc/build-workflow! ctx
                      (orc/workflow (str "native-evidence-" mode)
                        (orc/blackboard {:evidence evidence-schema :interpretation interpretation-schema :result result-schema}) node))
              tick (random-uuid)
              result (orc/execute ctx sheet {:evidence native-evidence} :tick-id tick :timeout-ms 60000 :max-ticks 1 :llm-call-budget 4)
              events (h/read-all-events ctx)
              completion (last (filter #(and (= tick (:tick-id %)) (= :sheet/node-execution-completed (:event/type %))) events))
              projected (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick completion)
              family (orc/get-execution-family ctx tick)
              value {:mode mode :directory directory :result result :projected projected
                     :canonical (orc/get-execution-result ctx tick) :events events :family family
                     :requests @(:requests server) :errors @(:errors server) :tool-calls @tool-calls
                     :synthetic-control? true}]
          (reset! proof value) (swap! last-proofs conj value)
          (spit (str directory "/proof.edn") (pr-str value))
          (println "RESEARCHER-NATIVE-EVIDENCE-PROOF" mode directory)
          value))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdown ^java.util.concurrent.ExecutorService (:pool server))
        (let [joined? (.awaitTermination ^java.util.concurrent.ExecutorService (:pool server) 10 TimeUnit/SECONDS)]
          (spit (str directory "/http-closure.edn") (pr-str {:executor-joined? joined? :request-count (count @(:requests server))}))
          (assert joined? "Local HTTP executor did not join"))))))

(deftest shared-guidance-and-executed-native-assembly-cover-every-researcher-mode
  (reset! last-proofs [])
  (doseq [mode [:legacy :recursive :terminal]]
    (testing (name mode)
      (let [p (run-case! mode) expected {:evidence native-evidence :interpretation interpretation}
            actual (get-in p [:result :outputs :result])
            first-wire (request-text (get-in p [:requests 0 :request]))]
        (is (empty? (:errors p)) (pr-str (:errors p)))
        (is (= :success (get-in p [:result :status])) (pr-str (:result p)))
        (is (= expected actual))
        (is (= expected (get-in p [:projected :result])))
        (is (= expected (get-in p [:canonical :outputs :result])))
        (is (vector? (:evidence actual)))
        (is (= ["id:Z#dup" "id:A" "id:Z#dup"] (mapv :source-id (:evidence actual))))
        (is (= {"id:Z#dup" 2 "id:A" 1} (frequencies (map :source-id (:evidence actual)))))
        (is (= (mapv :reference native-evidence) (mapv :reference (:evidence actual))))
        (is (false? (get-in actual [:evidence 0 :payload :enabled?])))
        (is (= 0 (get-in actual [:evidence 0 :payload :count])))
        (is (contains? (get-in actual [:evidence 0 :payload]) :note))
        (is (nil? (get-in actual [:evidence 0 :payload :note])))
        (is (= (if (= mode :legacy) [{:tool "native/evidence" :arguments {}}] []) (:tool-calls p))
            "Legacy proof must actually execute its source capability, not just parse a terminal literal")
        (doseq [clause guidance-clauses]
          (is (str/includes? first-wire clause) (str mode " first real HTTP request lacks shared guidance clause: " clause)))
        (when (= mode :recursive)
          (is (str/includes? first-wire "Missing required evidence, changed native values or material qualifications")
              "Hard evidence obligations remain visible before finalization")
          (is (not (str/includes? first-wire "completeness questions are fine"))
              "Cosmetic-polish advice cannot excuse required evidence failures")
          (let [semantic (filterv #(= :ai (:executor %)) (:family p))
                child (first semantic)
                assembly (first (filter #(and (= :code (:executor %)) (= expected (get-in % [:outputs :result]))) (:family p)))
                child-http (first (remove :prime? (:requests p)))
                child-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                (= (:tick-id child) (:tick-id %))) (:events p)))
                schemas (map :schema (vals (get-in child-start [:execution-snapshot :blackboard-entries])))]
            (is (= 3 (count (:requests p))))
            (is (= 1 (count semantic)))
            (is (= native-evidence (get-in child [:inputs :evidence])))
            (is (= {:interpretation interpretation} (:outputs child)) "Semantic child writes only interpretation")
            (is (= #{:summary :decision} (set (keys (:output child-http)))))
            (is (not (contains? (:output child-http) :evidence)))
            (is (some? assembly) "Ordinary executed code assembles the final typed record")
            (is (= native-evidence (get-in assembly [:inputs :evidence])))
            (is (= interpretation (get-in assembly [:inputs :interpretation])))
            (is (not-any? #(some #{:any} (tree-seq coll? seq %)) schemas))
            (is (str/includes? (request-text (:request child-http)) "Opaque source identity; duplicates are permitted and meaningful."))))))))
