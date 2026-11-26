(ns ai.obney.orc.orc-service.input-identity-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(def packet-schema
  [:map {:closed true :description "Typed source packet."}
   [:same {:description "Keyword identity meaning."} :string]
   ["same" {:description "String identity meaning."} :int]
   [:reading [:or [:nil {:description "Explicit null measurement."}]
                 [:enum {:description "Empty label branch."} ""]]]
   [:items [:vector [:or
                    [:map [:kind [:enum :ready]] [:n {:description "Keyword variant meaning."} :int]]
                    [:map [:kind [:enum "ready"]] [:n {:description "String variant meaning."} :int]]]]]
   [:dictionary [:map-of [:or :keyword :string] :boolean]]])
(def packet {:same "" "same" 0 :reading nil :items [{:kind :ready :n 0}]
             :dictionary {:same false "same" true}})
(def expected-view
  {"__sio_input_key_0" "" "__sio_input_key_1" 0 "reading" nil
   "items" [{"branch" "[0]" "value" {"kind" "ready" "n" 0}}]
   "dictionary" [{"key" {"type" "keyword" "value" "same"} "value" false}
                 {"key" {"type" "string" "value" "same"} "value" true}]})
(def spec {:instructions "Assess this exact packet."
           :inputs [{:name :packet :spec packet-schema}]
           :outputs [{:name :answer :spec :boolean :description "The packet is acceptable."}]})

(defn- reply! [exchange content-type text]
  (let [bytes (.getBytes text "UTF-8")]
    (.set (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))
(defn- with-server [f]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [request (json/read-str (slurp (.getRequestBody exchange)))
                _ (swap! requests conj request)
                decision? (contains? request "questions")
                marker "[[ ## answer ## ]]\ntrue"]
            (if (get request "stream")
              (reply! exchange "text/event-stream"
                (str "data: " (json/write-str {:id "fixture" :model "fixture/input"
                     :choices [{:index 0 :delta {:content marker}}]}) "\n\n"
                     "data: " (json/write-str {:choices [{:index 0 :delta {} :finish_reason "stop"}]}) "\n\n"
                     "data: [DONE]\n\n"))
              (reply! exchange "application/json"
                (json/write-str
                  (if decision?
                    {:id "fixture" :model "fixture/input" :answers {"answer" {:type "noul" :noul 0.99}}}
                    {:id "fixture" :model "fixture/input"
                     :choices [{:index 0 :finish_reason "stop"
                                :message (cond
                                           (contains? request "tools")
                                           {:role "assistant" :tool_calls [{:id "call" :type "function"
                                             :function {:name "submit_response" :arguments "{\"answer\":true}"}}]}
                                           (contains? request "response_format") {:role "assistant" :content "{\"answer\":true}"}
                                           :else {:role "assistant" :content marker})}]}))))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) requests)
         (finally (.stop server 0)))))
(defn- register! [url mode]
  (let [provider (keyword (str "input-identity-" (name mode) "-" (random-uuid)))]
    (llm/register-provider! provider
      (cond-> {:provider :openrouter :model "fixture/input"
               :config {:api-key "loopback-only" :api-base url :decisions-url (str url "/decisions")}}
        (= mode :decision) (assoc :protocol :decision)))
    provider))
(defn- options [mode]
  (cond-> {:validate? true :timeout-ms 8000 :max-retries 0}
    (= mode :strict) (assoc :output-mode :json-schema)
    (#{:marker :stream} mode) (assoc :use-function-calling? false)
    (= mode :function) (assoc :use-function-calling? true)))
(defn- text-of [request]
  (if-let [questions (get request "questions")]
    (get-in questions ["answer" "instructions"])
    (let [content (get-in request ["messages" 0 "content"])]
      (if (string? content) content (str/join "\n" (keep #(get % "text") content))))))
(defn- assert-request [mode request]
  (let [text (text-of request)]
    (doseq [meaning ["Keyword identity meaning" "String identity meaning" "Explicit null measurement"
                     "Keyword variant meaning" "Original input key"]]
      (is (str/includes? text meaning) [mode meaning]))
    (doseq [wrong ["Empty label branch" "String variant meaning"]]
      (is (not (str/includes? text wrong)) [mode wrong]))
    (if (= mode :decision)
      (is (= expected-view (get-in request ["state" "packet"])))
      (let [line (if (= mode :stream) "[[ ## packet ## ]]\n" (if (= mode :marker) "[[ ## packet ## ]]\n" "packet: "))
            value (-> (str/split text (re-pattern (java.util.regex.Pattern/quote line))) last
                      (str/split #"\n\n" 2) first)]
        (is (= expected-view (json/read-str value)) mode)))))

(defn- collect-stream [channel]
  (let [collected (async/into [] channel)
        [events port] (async/alts!! [collected (async/timeout 12000)])]
    (when-not (= port collected)
      (async/close! channel)
      (throw (ex-info "Input fixture stream exceeded its deadline" {})))
    events))

(deftest all-request-protocols-use-the-same-lossless-input-view
  (with-server
    (fn [url requests]
      (doseq [mode [:marker :function :strict :stream :decision]]
        (let [provider (register! url mode)
              result (if (= mode :stream)
                       (let [ch (llm/predict-stream-v2 provider spec {:packet packet} (options mode))
                             events (collect-stream ch)]
                         (is (= :final (:orc/event (last events))) (pr-str events))
                         (:outputs (last events)))
                       (llm/predict provider spec {:packet packet} (options mode)))]
          (is (= {:answer true} result) mode)
          (assert-request mode (last @requests))))
      (is (= 5 (count @requests)))
      (is (= :same (ffirst packet)))
      (is (= :map (first packet-schema))))))

(deftest malformed-present-values-fail-before-any-transport
  (with-server
    (fn [url requests]
      (doseq [mode [:marker :function :strict :stream :decision]
              value [false nil]]
        (let [provider (register! url mode)
              invalid-spec (assoc spec :inputs [{:name :packet :spec :string}])]
          (if (= mode :stream)
            (let [events (collect-stream (llm/predict-stream-v2 provider invalid-spec {:packet value} (options mode)))]
              (is (= [:error] (mapv :orc/event events))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (llm/predict provider invalid-spec {:packet value} (options mode)))))))
      (is (empty? @requests)))))

(deftest image-values-bypass-textual-presentation
  (with-server
    (fn [url requests]
      (let [image "https://fixture.invalid/image.png"
            image-spec (update spec :inputs conj {:name :photo :spec :string :type :image})]
        (doseq [mode [:marker :function :strict :decision]]
          (is (= {:answer true} (llm/predict (register! url mode) image-spec
                                  {:packet packet :photo image} (options mode))))
          (let [request (last @requests)]
            (if (= mode :decision)
              (is (= image (get-in request ["state" "photo"])))
              (is (= image (some #(get-in % ["image_url" "url"])
                                    (get-in request ["messages" 0 "content"])))))))))))

(defn consume [{:keys [inputs]}] {:received inputs})

(deftest delegated-native-input-remains-exact-through-typed-consumer-and-events
  (with-server
    (fn [url requests]
      (let [provider (register! url :strict)
            received-schema [:map {:closed true} [:packet packet-schema] [:answer :boolean]]
            board {:packet packet-schema :answer :boolean :received received-schema}
            expected {:packet packet :answer true}]
        (h/with-async-test-context [ctx {:context {:llm-provider provider}
                                       :event-store-conn {:type :sqlite :database-file
                                         (str (java.nio.file.Files/createTempDirectory "input-identity-" (make-array java.nio.file.attribute.FileAttribute 0)) "/store.sqlite")}}]
          (let [child (bt/build-workflow! ctx
                        (bt/workflow "input-identity-child" (bt/blackboard board)
                          (bt/llm "assess" :instruction "Assess this exact packet."
                            :reads [:packet] :writes [:answer]
                            :options {:output-mode :json-schema :max-retries 0}
                            :retry {:max-attempts 1 :backoff-ms []})))
                root (bt/build-workflow! ctx
                       (bt/workflow "input-identity-root" (bt/blackboard board)
                         (bt/sequence "sequence"
                           (bt/delegate "delegate" :target-sheet-id child :reads [:packet] :writes [:answer])
                           (bt/code "consume" :fn "ai.obney.orc.orc-service.input-identity-contract-test/consume"
                             :reads [:packet :answer] :writes [:received]))))
                result (bt/execute ctx root {:packet packet} :timeout-ms 25000 :max-ticks 1 :llm-call-budget 1)]
            (is (= :success (:status result)) (pr-str result))
            (is (= expected (get-in result [:outputs :received])))
            (is (= 1 (count @requests)))
            (assert-request :strict (first @requests))
            (let [events (bt/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                  completed (filter #(= :sheet/node-execution-completed (:event/type %)) events)
                  writes (map #(bt/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id result) %) completed)]
              (is (some #(= expected (:received %)) writes))
              (is (some #(true? (:answer %)) writes)))))))))

(deftest top-level-namespaced-input-guide-and-wire-use-identical-identities
  (with-server
    (fn [url requests]
      (doseq [[fields values expected]
              [[[{:name :alpha/item :spec :string :description "Namespaced source."}]
                {:alpha/item "namespaced"} {"alpha/item" "namespaced"}]
               [[{:name :alpha/item :spec :string :description "Namespaced source."}
                 {:name "item" :spec :string :description "Short literal source."}
                 {:name "alpha/item" :spec :string :description "Full literal source."}]
                {:alpha/item "namespaced" "item" "short" "alpha/item" "literal"}
                {"__sio_input_field_0" "namespaced" "item" "short" "__sio_input_field_1" "literal"}]]
              mode [:marker :decision]]
        (is (= {:answer true} (llm/predict (register! url mode) (assoc spec :inputs fields) values (options mode))))
        (let [request (last @requests) text (text-of request)]
          (doseq [[wire value] expected]
            (is (str/includes? text (str "`" wire "`")))
            (when (= mode :marker)
              (is (str/includes? text (str "[[ ## " wire " ## ]]\n" value)))))
          (when (= mode :decision)
            (is (= expected (get request "state")))))))))

(deftest temporal-and-literal-branches-survive-chat-and-native-state-json
  (with-server
    (fn [url requests]
      (let [fields [{:name :values :spec [:vector [:or ['inst? {:description "Timestamp branch."}]
                                                       [:string {:description "Literal branch."}]]]}]
            timestamp (java.util.Date. 0)
            values {:values [timestamp "1970-01-01T00:00:00Z"]}
            expected [{"branch" "[0]" "value" "1970-01-01T00:00:00Z"}
                      {"branch" "[1]" "value" "1970-01-01T00:00:00Z"}]]
        (doseq [mode [:marker :decision]]
          (is (= {:answer true} (llm/predict (register! url mode) (assoc spec :inputs fields) values (options mode))))
          (let [request (last @requests) text (text-of request)
                wire (if (= mode :decision) (get-in request ["state" "values"])
                       (json/read-str (last (str/split text #"\[\[ ## values ## \]\]\n"))))]
            (is (= expected wire))
            (is (str/includes? text "Timestamp branch"))
            (is (str/includes? text "Literal branch"))))
        (is (identical? timestamp (first (:values values))))))))
