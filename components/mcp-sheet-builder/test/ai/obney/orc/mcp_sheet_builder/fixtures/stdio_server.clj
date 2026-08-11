(ns ai.obney.orc.mcp-sheet-builder.fixtures.stdio-server
  (:require [cheshire.core :as json])
  (:gen-class))

(defn- respond! [message]
  (println (json/generate-string message))
  (flush))

(defn -main [& literal-args]
  (dotimes [i 200]
    (binding [*out* *err*] (println "fixture diagnostic" i)))
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (let [{:keys [id method params]} (json/parse-string line true)]
      (case method
        "initialize"
        (respond! {:jsonrpc "2.0" :id id
                   :result {:protocolVersion "2025-03-26"
                            :capabilities {:tools {}}
                            :serverInfo {:name "orc-test" :version "1"}}})

        "notifications/initialized" nil

        "tools/list"
        (respond! {:jsonrpc "2.0" :id id
                   :result {:tools [{:name "literal_args"
                                     :description "Returns literal process arguments"
                                     :inputSchema {:type "object" :properties {}}}
                                    {:name "echo"
                                     :description "Echo arguments"
                                     :inputSchema {:type "object"}}]}})

        "tools/call"
        (respond! {:jsonrpc "2.0" :id id
                   :result (if (= "literal_args" (:name params))
                             {:content [{:type "text" :text (json/generate-string literal-args)}]}
                             {:content [{:type "text"
                                         :text (json/generate-string (:arguments params))}]})})

        (when id
          (respond! {:jsonrpc "2.0" :id id
                     :error {:code -32601 :message "method not found"}}))))))
