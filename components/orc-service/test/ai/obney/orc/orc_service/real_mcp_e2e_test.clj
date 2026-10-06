(ns ai.obney.orc.orc-service.real-mcp-e2e-test
  "REAL MCP server inside behaviour trees: the official
   @modelcontextprotocol/server-filesystem over stdio (launched with npx),
   driven through ORC's MCP client, the tool seam and real models.
   Opt-in through ORC_OPENROUTER_E2E_TESTS; also needs npx on PATH.

   - an executor generated from the server's own discovered tool schema reaches
     the live session inside a workflow;
   - a native decision model routes to a contract-enforced tool leaf that calls
     the real server;
   - a real recursive, checkpointed researcher explores and reads files through
     the real server, with ORC's idempotency key on every call;
   - a call that violates its declared contract never reaches the server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as runtime]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def server-calls (atom []))

(defn- npx-available? []
  (try (zero? (.waitFor (.start (ProcessBuilder. ["npx" "--version"])))) (catch Exception _ false)))

(defn- fixture-dir []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "orc-real-mcp-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (io/file dir "q3-revenue.txt") "Q3 2025 revenue: 4.2 million dollars.\n")
    (spit (io/file dir "headcount.txt") "2025 headcount: 57 employees.\n")
    (.getCanonicalPath dir)))

(defn- host-caller
  "The consumer's tool capability over the live MCP connection (context-aware
   3-argument contract). Records every call that actually reaches the server."
  [conn]
  (fn
    ([tool args] (swap! server-calls conj {:tool tool :args args}) (mcp/call-tool conn tool args))
    ([tool args tool-context]
     (swap! server-calls conj {:tool tool :args args :tool-context tool-context})
     (mcp/call-tool conn tool args))))

(defn- text-of [result] (str/join "\n" (keep :text (:content result))))

(def ^:private routes
  {"list" "List which report files exist."
   "read" "Read the contents of a named report to answer a question about it."
   "clarify" "The request is too ambiguous to act on; ask the user."})

(def ^:private mcp-result-schema
  [:map [:content [:vector [:map [:type :string] [:text :string]]]]])

(deftest real-mcp-behaviour-trees
  (if-not (and (live/real-llm-enabled?) (npx-available?))
    (testing "REAL-MCP skipped: gate, key or npx absent" (is true))
    (let [dir (fixture-dir)
          conn (mcp/connect {:type :stdio :server-id "fs" :command "npx"
                             :args ["-y" "@modelcontextprotocol/server-filesystem" dir]
                             :working-directory dir})]
      (try
        (live/register-openrouter!)
        (llm/register-provider! :jev {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                                      :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})
        (h/with-async-test-context [ctx {:context {:llm-provider :openrouter
                                                   :call-tool-fn (host-caller conn)}}]

          (testing "an executor generated from the server's discovered schema reaches the live session"
            (reset! server-calls [])
            (let [tool (some #(when (= "list_directory" (:name %)) %) (mcp/list-tools conn))
                  exec-def (gen/build-executor-definition tool)
                  _ (runtime/load-executor! (:tool-id exec-def) (:tool-name exec-def)
                                            (:source-code exec-def) (:namespace-requires exec-def))
                  ;; The execution's MCP session — no node caller on this run.
                  session-ctx (assoc (dissoc ctx :call-tool-fn) :mcp-session conn)
                  result (sheet/execute session-ctx
                                        (sheet/build-workflow!
                                         ctx (sheet/workflow "real-mcp-generated"
                                               (sheet/blackboard {:path :string
                                                                  :list_directory-result mcp-result-schema})
                                               (sheet/code "call-list_directory" :fn (:fn-reference exec-def)
                                                 :reads [:path] :writes [:list_directory-result])))
                                        {:path dir} :timeout-ms 60000)
                  listing (text-of (get-in result [:outputs :list_directory-result]))]
              (println :MCP-GENERATED (:status result) (pr-str listing))
              (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
              (is (str/includes? listing "q3-revenue.txt") "a real directory listing, not a stand-in")))

          (testing "a native decision model routes to a contract-enforced tool leaf on the real server"
            (reset! server-calls [])
            (let [result (sheet/execute ctx
                                        (sheet/build-workflow!
                                         ctx (sheet/workflow "real-mcp-routed"
                                               (sheet/blackboard {:request :string :path :string
                                                                  :route (into [:enum {:descriptions routes}] (keys routes))
                                                                  :listing mcp-result-schema})
                                               (sheet/sequence "main"
                                                 (sheet/llm-decision "route" :model "jev"
                                                   :instruction "Choose the operation that addresses the user's request."
                                                   :reads [:request] :writes [:route])
                                                 (sheet/fallback "dispatch"
                                                   (sheet/sequence "list-route"
                                                     (sheet/condition "is-list" :check {:key :route :op :equals :value "list"})
                                                     (sheet/tool "list-reports" :tool "list_directory"
                                                       :reads [:path] :writes [:listing]
                                                       :tool-contracts {"list_directory" {:arguments [:map [:path :string]]
                                                                                          :result mcp-result-schema}}))
                                                   (sheet/condition "not-list" :check {:key :route :op :equals :value "clarify"})))))
                                        {:request "Which report files do we have?" :path dir} :timeout-ms 120000)
                  listing (text-of (get-in result [:outputs :listing]))]
              (println :MCP-ROUTED (:status result) (get-in result [:outputs :route]) (pr-str listing))
              (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
              (is (= "list" (get-in result [:outputs :route])))
              (is (= ["list_directory"] (mapv :tool @server-calls)))
              (is (str/includes? listing "headcount.txt"))))

          (testing "a call that violates its declared contract never reaches the server"
            (reset! server-calls [])
            (let [result (sheet/execute ctx
                                        (sheet/build-workflow!
                                         ctx (sheet/workflow "real-mcp-contract"
                                               (sheet/blackboard {:path :string :listing mcp-result-schema})
                                               (sheet/tool "list-reports" :tool "list_directory"
                                                 :reads [:path] :writes [:listing]
                                                 :tool-contracts {"list_directory" {:arguments [:map [:path :int]]}})))
                                        {:path dir} :timeout-ms 60000)]
              (is (= :failure (:status result)))
              (is (empty? @server-calls) "the real server was never called")))

          (testing "a tool error the real server reports fails the leaf instead of passing as data"
            (reset! server-calls [])
            (let [result (sheet/execute ctx
                                        (sheet/build-workflow!
                                         ctx (sheet/workflow "real-mcp-tool-error"
                                               (sheet/blackboard {:path :string :contents mcp-result-schema})
                                               (sheet/tool "read-report" :tool "read_text_file"
                                                 :reads [:path] :writes [:contents])))
                                        {:path (str dir "/q4-revenue.txt")} :timeout-ms 60000)]
              (println :MCP-TOOL-ERROR (:status result) (pr-str (:error result)))
              (is (= 1 (count @server-calls)) "the call reached the real server")
              (is (= :failure (:status result)) "a missing file is a failed call, not a value")))

          (testing "a real checkpointed researcher explores and reads files through the real server"
            (reset! server-calls [])
            (let [result (sheet/execute ctx
                                        (sheet/build-workflow!
                                         ctx (sheet/workflow "real-mcp-researcher"
                                               (sheet/blackboard {:question :string :folder :string :answer :string})
                                               (sheet/repl-researcher "investigate" :model live/openrouter-model
                                                 :instruction (str "Answer the question using only the files in `folder`. "
                                                                   "Call list_directory with {\"path\" folder} to see the files, then "
                                                                   "read_text_file with {\"path\" <folder>/<file name>} for the relevant file. "
                                                                   "Tool results are maps whose :content holds {:type \"text\" :text ...} entries. "
                                                                   "Finish with final! setting :answer to one sentence quoting the figure from the file.")
                                                 :reads [:question :folder] :writes [:answer]
                                                 :mcp-tools ["list_directory" "read_text_file"]
                                                 :tool-contracts {"list_directory" {:checkpoint-safe? true
                                                                                    :arguments [:map [:path :string]]
                                                                                    :result mcp-result-schema}
                                                                  "read_text_file" {:checkpoint-safe? true
                                                                                    :arguments [:map [:path :string]]
                                                                                    :result mcp-result-schema}}
                                                 :rlm {:timeouts {:provider-ms 60000 :iteration-ms 90000 :campaign-ms 240000}}
                                                 :max-iterations 5)))
                                        {:question "What was revenue in Q3 2025?" :folder dir}
                                        :timeout-ms 300000 :llm-call-budget 30)
                  answer (str (get-in result [:outputs :answer]))]
              (println :MCP-RESEARCHER (:status result) (pr-str answer)
                       (pr-str (mapv #(select-keys % [:tool :args]) @server-calls)))
              (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
              (is (some #(= "read_text_file" (:tool %)) @server-calls) "the researcher read a real file")
              (is (every? #(some? (get-in % [:tool-context :orc/idempotency-key])) @server-calls)
                  "every checkpointed call carried ORC's idempotency key")
              (is (str/includes? answer "4.2") "the answer carries the figure the real file holds"))))
        (finally (mcp/close conn))))))
