(require '[clojure.test :as t] '[clojure.java.io :as io])

(def report-directory (io/file (or (first *command-line-args*)
                                  (str (java.nio.file.Files/createTempDirectory
                                        "orc-researcher-verification-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))))))
(.mkdirs report-directory)
(def repository (.getCanonicalFile (io/file "../..")))
(assert (.isFile (io/file repository "components/orc-service/deps.edn"))
        "Run from projects/orc-service")
(defn save! [name value] (spit (io/file report-directory name) (pr-str value)))
(defn sha [file]
  (format "%064x" (java.math.BigInteger. 1
                   (.digest (java.security.MessageDigest/getInstance "SHA-256")
                            (java.nio.file.Files/readAllBytes (.toPath (io/file file)))))))
(def loaded-sources
  (vec (for [component ["orc-service" "llm" "file-store" "langfuse"]
             :let [source-root (io/file repository "components" component "src")]
             file (sort-by str (file-seq source-root))
             :when (and (.isFile file) (.endsWith (.getName file) ".clj"))
             :let [resource (str (.relativize (.toPath source-root) (.toPath file)))
                   url (io/resource resource)
                   loaded (when (and url (= "file" (.getProtocol url))) (io/file (.toURI url)))]]
         (do (assert (and loaded (= (.getCanonicalPath file) (.getCanonicalPath loaded)))
                     (str "Unexpected runtime resource: " resource))
             (assert (= (sha file) (sha loaded)) (str "Unexpected runtime bytes: " resource))
             {:resource resource :url (str url) :sha256 (sha loaded)}))))
(save! "loaded-sources.edn" loaded-sources)
(save! "process.edn" {:pid (.pid (java.lang.ProcessHandle/current))
                       :arguments (vec (.orElse (.arguments (.info (java.lang.ProcessHandle/current)))
                                               (make-array String 0)))})
(def namespaces '[ai.obney.orc.orc-service.emission-parse-recovery-regression-test
                     ai.obney.orc.orc-service.mixed-target-admission-regression-test
                     ai.obney.orc.orc-service.researcher-setup-diagnostics-test
                     ai.obney.orc.orc-service.researcher-admission-feedback-test
                     ai.obney.orc.orc-service.stdout-capture-test
                     ai.obney.orc.orc-service.researcher-timeout-roundtrip-test
                     ai.obney.orc.orc-service.emitted-model-authority-test
                     ai.obney.orc.orc-service.late-emitted-model-authority-test
                     ai.obney.orc.orc-service.late-emitted-model-http-test
                     ai.obney.orc.orc-service.recoverable-generated-function-test
                     ai.obney.orc.orc-service.recoverable-generated-function-context-test
                     ai.obney.orc.orc-service.recoverable-generated-function-lifecycle-test
                     ai.obney.orc.orc-service.recoverable-generated-function-normal-lifetime-test
                     ai.obney.orc.orc-service.recoverable-generated-function-cold-test
                     ai.obney.orc.orc-service.recoverable-cleanup-reducible-test
                     ai.obney.orc.llm.transport-failure-evidence-test
                     ai.obney.orc.orc-service.researcher-timeout-contract-test
                     ai.obney.orc.orc-service.provider-timeout-category-precedence-test
                     ai.obney.orc.llm.native-timeout-evidence-test
                     ai.obney.orc.orc-service.researcher-repair-composition-test])
(try
  (doseq [n namespaces] (require n))
  (require 'ai.obney.orc.orc-service.retained-prime-model-plan-test)
  (let [results (mapv (fn [n] {:namespace n :result (t/run-tests n)}) namespaces)
        retained (binding [t/*report-counters* (ref t/*initial-report-counters*)]
                   (t/test-vars [(resolve 'ai.obney.orc.orc-service.retained-prime-model-plan-test/configured-timeout-receipt-replays-without-provider-redispatch)])
                   @t/*report-counters*)
        summaries (conj (mapv :result results) retained)
        total (apply merge-with + (map #(select-keys % [:test :pass :fail :error]) summaries))]
    (save! "results.edn" {:namespaces results :retained-timeout-replay retained :total total})
    (prn {:total total :report-directory (str report-directory)})
    (shutdown-agents)
    (System/exit (+ (:fail total) (:error total))))
  (catch Throwable e
    (.printStackTrace e)
    (shutdown-agents)
    (System/exit 1)))
