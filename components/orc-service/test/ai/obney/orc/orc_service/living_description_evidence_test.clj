(ns ai.obney.orc.orc-service.living-description-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.living-description-evidence :as evidence])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.file Files]))

(defn- with-server [status body f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/completion"
      (reify HttpHandler
        (handle [_ exchange]
          (let [bytes (.getBytes body "UTF-8")]
            (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.sendResponseHeaders exchange status (alength bytes))
            (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/completion"))
         (finally (.stop server 0)))))

(defn- directory []
  (str (Files/createTempDirectory "orc-live-evidence-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- records [dir]
  (mapv #(edn/read-string {:default tagged-literal} (slurp %))
        (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")) (file-seq (io/file dir)))))

(deftest captures-exact-async-bodies-without-changing-decoding
  (let [dir (directory)
        raw "{ \"answer\" : \"line\\nnext\", \"ok\":true }\n"
        request "{ \"model\": \"fixture\" }\n"]
    (with-server 200 raw
      (fn [url]
        (evidence/capture-http dir
          #(let [response (deref (http/post url {:body request :as :json :async? true})
                                5000 ::expired)]
             (is (= 200 (:status response)))
             (is (= {:answer "line\nnext" :ok true} (:body response)))))))
    (is (some #(= request (:request-body %)) (records dir)))
    (is (some #(= raw (:response-body %)) (records dir)))))

(defn- terminal-error [f]
  (try (f) nil
       (catch Throwable error
         (loop [cause error]
           (if-let [next (.getCause cause)] (recur next) cause)))))

(deftest preserves-malformed-json-and-http-error-behavior
  (doseq [[status body] [[200 "{broken-json"] [429 "{\"error\":\"limited\"}"]]]
    (with-server status body
      (fn [url]
        (let [dir (directory)
              call #(deref (http/post url {:body "{}" :as :json :async? true}) 5000 ::expired)
              plain (terminal-error call)
              observed (terminal-error #(evidence/capture-http dir call))]
          (is (some? plain))
          (is (= (class plain) (class observed)))
          (is (= (.getMessage plain) (.getMessage observed)))
          (is (some #(and (= status (:status %)) (= body (:response-body %))) (records dir))))))))

(deftest credential-bodies-are-withheld-and-headers-never-persist
  (let [secret "synthetic-private-credential"
        dir (directory)]
    (with-server 200 (str "{\"answer\":\"" secret "\"}")
      (fn [url]
        (evidence/capture-http dir
          #(is (= secret (get-in @(http/post url {:body secret :as :json :async? true
                                                  :headers {"Authorization" (str "Bearer " secret)}})
                                 [:body :answer]))))))
    (is (not (str/includes? (pr-str (records dir)) secret)))
    (is (= 2 (count (filter :evidence-withheld (records dir)))))
    (is (not (str/includes? (pr-str (records dir)) "Authorization")))))

(defn copy-input [{:keys [inputs]}] {:answer (:question inputs)})

(deftest failed-test-body-is-recorded-before-fixture-teardown
  (let [dir (directory)
        failure (ex-info "intentional-test-body-failure" {})]
    (h/with-async-test-context [ctx]
      (let [sheet (bt/build-workflow! ctx
                    (bt/workflow "evidence-copy"
                      (bt/blackboard {:question :string :answer :string})
                      (bt/code "copy" :reads [:question] :writes [:answer]
                        :fn "ai.obney.orc.orc-service.living-description-evidence-test/copy-input")))
            result (bt/execute ctx sheet {:question "fixture-answer"} :timeout-ms 20000)]
        (is (= :success (:status result)))
        (is (h/settle-until!
              #(seq (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                            [:query/result :trace :node-traces]))))
        (is (identical? failure
              (try (evidence/with-evidence ctx "case-a" dir #(throw failure))
                   (catch Throwable e e))))
        (let [first-snapshot (slurp (io/file dir "case-a/snapshot.edn"))]
          (evidence/with-evidence ctx "case-b" dir (constantly :done))
          (is (= first-snapshot (slurp (io/file dir "case-a/snapshot.edn"))))
          (let [snapshot (edn/read-string {:default tagged-literal} first-snapshot)]
            (is (some #(= :sheet/tree-tick-completed (:event/type %)) (:events snapshot)))
            (is (seq (:traces snapshot)))))
        (is (.exists (io/file dir "case-b/snapshot.edn")))))))

(deftest evidence-write-failure-does-not-change-http-result
  (let [blocked (str (Files/createTempFile "orc-evidence-blocked-" ".file"
                                         (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-server 200 "{\"ok\":true}"
      (fn [url]
        (evidence/capture-http blocked
          #(is (= {:ok true} (:body (deref (http/post url {:body "{}" :as :json :async? true})
                                          5000 ::expired)))))))))

(deftest unrelated-http-is-not-attributed-to-the-observed-execution
  (let [dir (directory)]
    (with-server 200 "{\"ok\":true}"
      (fn [url]
        (evidence/capture-http dir #(= :owned (:scope evidence/*execution*))
          (fn []
            (is (= 200 (:status @(http/post url {:body "unrelated" :as :json :async? true}))))
            (binding [evidence/*execution* {:scope :owned :node-id "fixture-node"}]
              (is (= 200 (:status @(http/post url {:body "owned" :as :json :async? true})))))))))
    (is (= 2 (count (records dir))))
    (is (= ["owned"] (vec (keep :request-body (records dir)))))))

(deftest real-leaf-links-http-and-command-failure-evidence
  (let [dir (directory)]
    (with-server 200 "{\"answer\":\"fixture-answer\"}"
      (fn [url]
        (h/with-async-test-context [ctx]
          (with-redefs [llm/predict (fn [& _]
                                     {:outputs (:body @(http/post url {:body "{}" :as :json :async? true}))})]
            (let [result
                  (evidence/with-evidence ctx "linked" dir
                    (fn []
                      (try (cp/process-command (assoc ctx :command {:command/name :fixture/unknown}))
                           (catch Throwable _ nil))
                      (let [sheet (bt/build-workflow! ctx
                                    (bt/workflow "evidence-linked-leaf"
                                      (bt/blackboard {:answer :string})
                                      (bt/llm "answer" :reads [] :writes [:answer]
                                        :options {:max-retries 0}
                                        :retry {:max-attempts 1 :backoff-ms []}))) ]
                        (bt/execute (assoc ctx :llm-provider :controlled) sheet {} :timeout-ms 20000))))
                  snapshot (edn/read-string {:default tagged-literal}
                                           (slurp (io/file dir "linked/snapshot.edn")))
                  request (first (filter :request-body (records (str dir "/linked/http"))))
                  completion (first (filter #(= :sheet/node-execution-completed (:event/type %))
                                            (:events snapshot)))]
              (is (= :success (:status result)))
              (is (= (:trace-id result) (get-in request [:execution :tick-id])))
              (is (= (:node-id completion) (get-in request [:execution :node-id])))
              (is (seq (:command-failures snapshot))))))))))
