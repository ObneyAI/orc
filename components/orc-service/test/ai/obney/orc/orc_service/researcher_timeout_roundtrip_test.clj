(ns ai.obney.orc.orc-service.researcher-timeout-roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn- with-store [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "researcher-timeout-" (make-array java.nio.file.attribute.FileAttribute 0)))
        db (str dir "/events.db")
        store (es/start {:conn {:type :sqlite :database-file db :maximum-pool-size 2}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir (str dir "/cache") :db-name "proof"}))
        ctx {:event-store store :cache cache :tenant-id (random-uuid)
             :auth-claims {:sub "timeout-proof"}
             :command-registry (cp/global-command-registry) :query-registry (qp/global-query-registry)}]
    (try (is (.isFile (io/file db)) "Real SQLite file exists")
         (is (org.sqlite.SQLiteJDBCLoader/isNativeMode) "SQLite native driver loaded")
         (println "NATIVE-SQLITE" (pr-str {:database db :native? (org.sqlite.SQLiteJDBCLoader/isNativeMode)}))
         (f ctx)
         (finally
           (kv/stop cache) (es/stop store)
           (doseq [file (reverse (file-seq dir))] (io/delete-file file))
           (is (not (.exists dir)) "Native store and cache removed after close")
           (println "CLEANUP" (pr-str {:directory (str dir) :exists? (.exists dir)}))))))

(defn- workflow [name config]
  (orc/workflow name (orc/blackboard {:answer :string})
    (apply orc/repl-researcher "research" (mapcat identity (merge {:instruction "Return answer" :writes [:answer]} config)))))
(defn- events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))
(defn- node [ctx sheet-id]
  (let [result (qp/process-query (assoc ctx :query {:query/name :sheet/sheet-view-screen
                                                  :query/id (random-uuid) :query/timestamp (time/now)
                                                  :sheet-id sheet-id}))]
    (is (contains? result :query/result) "Public query succeeds")
    (first (get-in result [:query/result :nodes]))))

(deftest constructor-total-work-timeout-contract
  (is (not (contains? (orc/repl-researcher "r") :timeout-ms)))
  (is (not (contains? (orc/repl-researcher "r" :timeout-ms nil) :timeout-ms)))
  (doseq [value [1 1200000]]
    (is (= value (:timeout-ms (orc/repl-researcher "r" :timeout-ms value)))))
  (doseq [value [0 -1 1.5 "1000" false]]
    (is (thrown? clojure.lang.ExceptionInfo (orc/repl-researcher "r" :timeout-ms value)))))

(deftest public-sqlite-build-export-import-and-source-roundtrip
  (with-store
    (fn [ctx]
      (doseq [options [:absent {} {:temperature 0.2 :max-tokens 1024}]
              timeout [:absent 1200000]]
        (testing (str options " / " timeout)
          (let [config (cond-> {} (not= :absent options) (assoc :options options)
                                      (not= :absent timeout) (assoc :timeout-ms timeout))
                definition (workflow (str "timeout-proof-" (random-uuid)) config)
                sheet-id (orc/build-workflow! ctx definition)
                persisted (node ctx sheet-id)
                exported (read-string (pr-str (orc/export-sheet ctx sheet-id)))
                expected-timeout (when-not (= :absent timeout) timeout)
                expected-options (when-not (= :absent options) options)]
            (println "PUBLIC-BUILD-EXPORT" (pr-str {:expected config :node (select-keys persisted [:type :timeout-ms :options]) :export (:nodes exported)}))
            (is (= expected-timeout (:timeout-ms persisted)))
            (is (= expected-options (:options persisted)))
            (is (= expected-timeout (get-in exported [:nodes :timeout-ms])))
            (is (= (not= :absent timeout) (contains? (:nodes exported) :timeout-ms)))
            (is (= (not= :absent options) (contains? (:nodes exported) :options)))
            (is (= expected-options (get-in exported [:nodes :options])))
            (let [count-before (count (events ctx))]
              (is (= sheet-id (orc/build-workflow! ctx definition)))
              (is (= count-before (count (events ctx))) "Same definition emits zero events"))
            (let [imported (orc/import-sheet ctx (assoc-in exported [:sheet :name] (str "import-" (random-uuid)))) imported-node (node ctx imported)]
              (is (= expected-timeout (:timeout-ms imported-node)))
              (is (= expected-options (:options imported-node)))
              (println "PUBLIC-IMPORT" (pr-str {:expected config :node (select-keys imported-node [:type :timeout-ms :options])})))
            (let [source (orc/export-to-dsl exported :ns "ai.obney.orc.orc-service.interface")
                  reconstructed (eval (read-string source))
                  source-id (orc/build-workflow! ctx (assoc reconstructed :workflow-name (str "source-" (random-uuid))))]
              (is (= (not= :absent options) (contains? (:root-node reconstructed) :options)))
              (is (= (not= :absent timeout) (contains? (:root-node reconstructed) :timeout-ms)))
              (is (= expected-timeout (:timeout-ms (node ctx source-id))))
              (is (= expected-options (:options (node ctx source-id))))
              (println "PUBLIC-SOURCE" (pr-str {:expected config :node (select-keys (node ctx source-id) [:type :timeout-ms :options]) :source source})))
            (doseq [changed-config [(assoc config :timeout-ms 1300000) (dissoc config :timeout-ms)]]
              (let [changed (workflow (:workflow-name definition) changed-config)]
                (is (= sheet-id (orc/build-workflow! ctx changed)))
                (is (= (:timeout-ms changed-config) (:timeout-ms (node ctx sheet-id))))
                (println "PUBLIC-CHANGED" (pr-str {:expected changed-config :node (select-keys (node ctx sheet-id) [:type :timeout-ms :options])}))
                (let [count-before (count (events ctx))]
                  (orc/build-workflow! ctx changed)
                  (is (= count-before (count (events ctx)))))))))))))

(deftest existing-total-budget-and-immutable-campaign-deadline-semantics
  (doseq [[node tick expected source] [[{:timeout-ms 1200000} 1500000 1200000 :node]
                                      [{} 1500000 1500000 :tick]
                                      [{} nil 900000 :hardcoded]]]
    (is (= {:total-budget-ms expected :remaining-ms (max 0 (- expected 936000))
            :source source :exhausted? (<= expected 936000)}
           (executor/resolve-phase2-budget {:node node :parent-timeout-ms tick :phase1-elapsed-ms 936000}))))
  (is (= {:campaign-started-at-ms 100 :campaign-deadline-ms 1200100 :campaign-timeout-ms 1200000}
         (executor/resolve-researcher-campaign-timing {:node {:timeout-ms 1200000} :started-at-ms 100 :parent-timeout-ms 1500000})))
  (is (= 700 (get (executor/resolve-researcher-campaign-timing
                  {:node {:timeout-ms 1200000} :started-at-ms 900 :parent-timeout-ms 1500000
                   :checkpoint {:campaign-started-at-ms 100 :campaign-deadline-ms 700}}) :campaign-deadline-ms)))
  (is (= 600 (get (executor/resolve-researcher-campaign-timing
                  {:node {:timeout-ms 1200000 :rlm {:timeouts {:campaign-ms 500}}} :started-at-ms 100}) :campaign-deadline-ms))))
