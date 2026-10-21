(ns ai.obney.orc.orc-service.living-description-evidence
  "Opt-in test diagnostics. Observes HTTP entities without changing model options."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http]
            [hato.middleware :as middleware]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [java.io InputStream ByteArrayInputStream]))

(def ^:dynamic *execution* nil)

(defn- environment-secrets []
  (keep (fn [[k v]]
          (when (and (seq v) (re-find #"API_KEY|TOKEN|SECRET|PASSWORD" k)) v))
        (System/getenv)))

(defn- write!
  ([directory name value] (write! directory name value (environment-secrets)))
  ([directory name value secrets]
   (let [file (io/file directory (str name ".edn"))
         encoded (pr-str value)
         safe (if (some #(str/includes? encoded %) secrets)
                (pr-str {:evidence-withheld :credential-value-detected}) encoded)]
     (try
       (io/make-parents file)
       (spit file safe)
       (catch java.io.IOException _
         ;; Diagnostics must not replace the model result or strand an async
         ;; callback when the CI artifact filesystem is unavailable.
         (binding [*out* *err*]
           (println "Living-description evidence write failed; artifact incomplete.")))))))

(defn- observe-response [directory id secrets response]
  ;; Hato's JSON coercion consumes this stream next. Replay the identical bytes
  ;; through that original coercion; do not parse or reconstruct the JSON here.
  (if (instance? InputStream (:body response))
    (let [bytes (with-open [body ^InputStream (:body response)] (.readAllBytes body))]
      (write! directory (str id "-response")
              {:status (:status response) :response-body (String. bytes "UTF-8")} secrets)
      (assoc response :body (ByteArrayInputStream. bytes)))
    response))

(defn capture-http
  "Run f with per-request observers. The original Future, callbacks, request
   options, HTTP error behavior and JSON decoder remain Hato's responsibility."
  ([directory f] (capture-http directory (constantly true) f))
  ([directory eligible? f]
  (let [post http/post
        counter (atom 0)]
    (with-redefs [http/post
                  (fn [url options & callbacks]
                    (if-not (eligible?)
                      (apply post url options callbacks)
                    (let [id (swap! counter inc)
                          secrets (concat (environment-secrets)
                                    (mapcat (fn [[k v]]
                                              (when (and (string? v) (seq v)
                                                         (contains? #{"authorization" "api-key" "x-api-key"}
                                                                    (str/lower-case (name k))))
                                                [v (str/replace v #"(?i)^Bearer +" "")]))
                                            (:headers options)))
                          observe #(observe-response directory id secrets %)
                          raise (fn [error]
                                  (write! directory (str id "-error")
                                          {:error-class (.getName (class error))}))
                          wrapper (fn [client]
                                    (fn
                                      ([request]
                                       (try (observe (client request))
                                            (catch Throwable e (raise e) (throw e))))
                                      ([request respond fail]
                                       (client request (fn [response]
                                                         (try (respond (observe response))
                                                              (catch Throwable e (raise e) (fail e))))
                                               (fn [e] (raise e) (fail e))))))
                          stack (or (:middleware options) middleware/default-middleware)
                          observed-stack (vec (mapcat #(if (= % middleware/wrap-output-coercion)
                                                        [wrapper %] [%]) stack))]
                      (write! directory (str id "-request")
                              {:request-body (:body options) :execution *execution*} secrets)
                      (apply post url (assoc options :middleware observed-stack) callbacks))))]
      (f)))))

(defn with-evidence
  "Opt-in evidence around one living-description test body, INSIDE its existing
   context fixture. No waiting, timeout, model, assertion or lifecycle changes."
  ([ctx name f]
   (if-let [directory (System/getenv "ORC_LIVING_DESCRIPTION_EVIDENCE_DIR")]
     (with-evidence ctx name directory f)
     (f)))
  ([ctx name root f]
   (let [directory (str (io/file root name))
         failures (atom [])
         ticks (atom #{})
         command cp/process-command
         execute-ai executor/execute-ai]
     (when (.exists (io/file directory))
       (throw (ex-info "Living-description evidence directory already exists" {})))
     (capture-http (str directory "/http")
       (fn [] (some? *execution*))
       #(with-redefs
          [cp/process-command
           (fn [context]
             (if-not (identical? (:event-store ctx) (:event-store context))
               (command context)
               (let [intent (:command context)]
                 (when (:tick-id intent) (swap! ticks conj (:tick-id intent)))
                 (try
                   (let [result (command context)]
                     (when (:cognitect.anomalies/category result)
                       (let [entry {:command intent :result result}
                             all (swap! failures conj entry)]
                         (write! directory (str "command-failure-" (count all)) entry)))
                     result)
                   (catch Throwable error
                     (let [entry {:command-name (:command/name intent)
                                  :error-class (.getName (class error))}
                           all (swap! failures conj entry)]
                       (write! directory (str "command-failure-" (count all)) entry))
                     (throw error))))))
           executor/execute-ai
           (fn [node blackboard provider & args]
             (let [options (:options (apply hash-map args))]
               (binding [*execution* (when (contains? @ticks (:tick-id options))
                                      (merge {:node-id (:id node) :node-name (:name node)}
                                             (select-keys options [:tick-id :node-attempt :exec-context])))]
                 (apply execute-ai node blackboard provider args))))]
          (try (f)
               (finally
                 (try
                   (let [events (h/read-all-events ctx)
                         ids (distinct (keep :tick-id events))]
                     (write! directory "snapshot"
                             {:events events :command-failures @failures
                              :traces (mapv (fn [id]
                                              {:tick-id id
                                               :query (h/run-query ctx (h/make-get-trace-query id))}) ids)}))
                   (catch Throwable error
                     (write! directory "snapshot-error" {:error-class (.getName (class error))}))))))))))
