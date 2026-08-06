(ns ai.obney.orc.orc-service.deterministic-mcp-tools-e2e-test
  "Deterministic end-to-end coverage for MCP generation and host tool pipelines."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.ative.docjure.spreadsheet :as ss]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]
            [ai.obney.orc.predict-rlm-pdf.interface :as pdf]
            [ai.obney.orc.predict-rlm-redaction-tools.interface :as redaction]
            [ai.obney.orc.predict-rlm-invoice-tools.interface :as invoice]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def echo-tool
  {:name "typed_echo"
   :description "Transform a typed nested request"
   :inputSchema {"type" "object"
                 "required" ["request"]
                 "properties"
                 {"request" {"type" "object"
                             "required" ["message"]
                             "properties"
                             {"message" {"type" "string"}
                              "limit" {"type" "integer"}}}}}})

(defn invoke-fake-tool [{:keys [inputs call-tool-fn]}]
  {:tool-output (call-tool-fn "typed_echo" (:request inputs))})

(defn invoke-throwing-tool [{:keys [inputs call-tool-fn]}]
  {:tool-output (call-tool-fn "explode" inputs)})

(defn throwing-tool-caller [_blackboard _context]
  (fn [tool args]
    (throw (ex-info "deterministic MCP explosion" {:tool tool :args args}))))

(defn- fq [name]
  (str "ai.obney.orc.orc-service.deterministic-mcp-tools-e2e-test/" name))

(defn- analyze-static [conn]
  (let [tools (mcp/list-tools conn)]
    {:tools (analyzer/analyze-tools tools)
     :relationships []
     :patterns [{:pattern :sequential-pipeline :confidence 1.0}]}))

(defn- fixture-path [name]
  (.getPath (io/file (io/resource (str "fixtures/" name)))))

(def invoices
  [{:vendor-name "Acme Corp" :invoice-number "A-100"
    :date "2026-08-01" :due-date "2026-09-01"
    :subtotal 30.0 :tax 3.0 :total 33.0
    :line-items [{:description "Widget" :quantity 2 :unit-price 10.0 :amount 20.0}
                 {:description "Service" :quantity 1 :unit-price 10.0 :amount 10.0}]}
   {:vendor-name "Beta LLC" :invoice-number "B-200"
    :date "2026-08-02" :due-date "2026-09-02"
    :subtotal 40.0 :tax 4.0 :total 44.0
    :line-items [{:description "Consulting" :quantity 2 :unit-price 20.0 :amount 40.0}]}])

(deftest det-e2e-093-mcp-schema-workflow-execution
  (testing "a fixed MCP schema generates a code-only workflow that invokes the typed fake tool"
    (h/with-async-test-context [ctx]
      (let [calls (atom [])
            conn (mcp/connect {:type :static :tools [echo-tool]
                               :call-tool-handler
                               (fn [tool-name args]
                                 (swap! calls conj [tool-name args])
                                 {:echo (:message args) :length (count (:message args))})})
            analysis (analyze-static conn)
            generated (mcp/generate-sheet-data analysis {:pattern :sequential-pipeline})
            workflow (:workflow-data generated)
            sheet-id (sheet/build-workflow! ctx workflow)
            result (sheet/execute
                    (assoc ctx :mcp-session conn)
                    sheet-id
                    {:request {:message "hello" :limit 3}})]
        (is (= :success (:status result)))
        (is (= [["typed_echo" {:message "hello" :limit 3}]] @calls))
        (is (= {:echo "hello" :length 5}
               (get-in result [:outputs :typed_echo-result])))
        (is (every? #(= :code (:executor %))
                    (filter :executor (sheet/get-nodes-for-sheet ctx sheet-id))))))))

(deftest det-e2e-094-required-optional-schema-propagation
  (testing "nested required/optional fields survive generation and are enforced at runtime"
    (h/with-async-test-context [ctx]
      (let [conn (mcp/connect {:type :static :tools [echo-tool]
                               :call-tool-handler (fn [_ args] args)})
            analysis (analyze-static conn)
            generated (mcp/generate-sheet-data analysis {:pattern :sequential-pipeline})
            request-schema (get (:blackboard generated) :request)
            sheet-id (sheet/build-workflow! ctx (:workflow-data generated))
            valid (sheet/execute (assoc ctx :mcp-session conn) sheet-id
                                 {:request {:message "valid"}})
            missing-required (sheet/execute (assoc ctx :mcp-session conn) sheet-id
                                            {:request {:limit 2}})]
        (is (= [:map
                [:message :string]
                [:limit {:optional true} :int]]
               request-schema))
        (is (= :success (:status valid)))
        (is (= :failure (:status missing-required)))
        (is (str/includes? (str (:error missing-required)) "message"))))))

(deftest det-e2e-095-invalid-generated-workflow-rejection
  (testing "incompatible generated reads and writes are rejected before execution"
    (let [invalid {:blackboard {:declared :string}
                   :tools [{:name "typed_echo"}]
                   :workflow '(sheet/workflow "invalid"
                                (sheet/blackboard {:declared :string})
                                (sheet/sequence "main"
                                  (sheet/code "bad"
                                    :fn "example/missing"
                                    :reads [:undeclared-input]
                                    :writes [:undeclared-output])))}
          validation (mcp/validate-sheet invalid)]
      (is (false? (:valid? validation)))
      (is (= 1 (count (:errors validation))))
      (is (str/includes? (first (:errors validation)) ":undeclared-input"))
      (is (str/includes? (first (:errors validation)) ":undeclared-output")))))

(deftest det-e2e-096-tool-failure-propagation
  (testing "an in-process MCP exception fails the node and tick with durable trace detail"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow!
                      ctx
                      {:workflow-name "det-e2e-096"
                       :blackboard-schema {:request :map :tool-output :any}
                       :root-node {:node-type :leaf :name "invoke" :executor :code
                                   :fn (fq "invoke-throwing-tool")
                                   :reads [:request] :writes [:tool-output]
                                   :tool-caller-fn (fq "throwing-tool-caller")}})
            node-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            _ (h/run-and-apply!
               ctx {:command/name :sheet/set-node-executor
                    :command/id (random-uuid) :command/timestamp (time/now)
                    :sheet-id sheet-id :node-id node-id :executor :code
                    :fn (fq "invoke-throwing-tool")
                    :tool-caller-fn (fq "throwing-tool-caller")})
            result (sheet/execute ctx sheet-id {:request {:id 7}})]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) "deterministic MCP explosion"))
        (is (h/settle-until! #(h/trace-stored? ctx (:trace-id result))))
        (let [events (h/read-tick-events ctx (:trace-id result))
              failed (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                   (= :failure (:status %))) events)]
          (is (= 1 (count failed)))
          (is (str/includes? (:error (first failed)) "deterministic MCP explosion")))))))

(deftest det-e2e-097-pdf-pipeline
  (testing "fixture PDFs retain page alignment across count, render, text, and metadata aggregation"
    (doseq [fixture ["sample-invoice.pdf" "sample-employment-agreement.pdf"]]
      (let [path (fixture-path fixture)
            page-count (pdf/page-count path)
            images (pdf/render-pages-as-data-uris path {:dpi 72})
            texts (pdf/extract-pages-as-text path)
            pages (mapv (fn [idx image text]
                          {:page idx :image image :text text
                           :characters (count text)})
                        (range page-count) images texts)]
        (is (pos? page-count))
        (is (= page-count (count images) (count texts) (count pages)))
        (is (= (vec (range page-count)) (mapv :page pages)))
        (is (every? #(str/starts-with? % "data:image/png;base64,") images))
        (is (every? string? texts))
        (is (= (mapv count texts) (mapv :characters pages)))))))

(deftest det-e2e-098-redaction-pipeline
  (testing "fixture-like pages redact deterministically in order and a second pass is idempotent"
    (let [page-texts ["Alice Smith owes 100 dollars." "Email alice@example.test today."]
          targets [{:page 0 :text "Alice Smith" :category "name" :reason "PII"}
                   {:page 1 :text "alice@example.test" :category "email" :reason "PII"}]
          first-pass (redaction/apply-redactions
                      {:inputs {:page-texts page-texts :targets targets}})
          second-pass (redaction/apply-redactions
                       {:inputs {:page-texts (:redacted-text-per-page first-pass)
                                 :targets targets}})]
      (is (= 2 (:total-redactions first-pass)))
      (is (= [0 1] (mapv :page (:page-summaries first-pass))))
      (is (= [1 1] (mapv :redaction_count (:page-summaries first-pass))))
      (is (every? #(str/includes? % "█") (:redacted-text-per-page first-pass)))
      (is (= (:redacted-text-per-page first-pass)
             (:redacted-text-per-page second-pass)))
      (is (zero? (:total-redactions second-pass)))
      (is (= targets (:targets-missing second-pass))))))

(deftest det-e2e-099-invoice-workbook-pipeline
  (testing "normalized invoices produce stable sheets, rows, totals, and an output workbook"
    (let [path (str "/tmp/det-e2e-099-" (random-uuid) ".xlsx")]
      (try
        (let [result (invoice/build-invoice-workbook
                      {:inputs {:invoices invoices :output-path path}})
              workbook (ss/load-workbook (:workbook-path result))
              names (mapv ss/sheet-name (ss/sheet-seq workbook))
              summary (ss/select-sheet "Summary" workbook)
              rows (mapv #(mapv ss/read-cell (ss/cell-seq %))
                         (ss/row-seq summary))]
          (is (.exists (io/file path)))
          (is (= ["Summary" "Acme Corp" "Beta LLC"] names))
          (is (= 3 (count rows)))
          (is (= [33.0 44.0] (mapv #(double (nth % 6)) (rest rows))))
          (is (= 77.0 (reduce + (map #(double (nth % 6)) (rest rows)))))
          (is (= 3 (count (ss/row-seq (ss/select-sheet "Acme Corp" workbook)))))
          (is (= 2 (count (ss/row-seq (ss/select-sheet "Beta LLC" workbook))))))
        (finally
          (io/delete-file path true))))))

(deftest det-e2e-100-code-node-catalog
  (testing "a catalog-declared code node executes and an undeclared function cannot be invoked"
    (h/with-async-test-context [ctx]
      (let [path (str "/tmp/det-e2e-100-" (random-uuid) ".xlsx")]
        (try
          (is (str/includes? invoice/available-code-nodes
                             "build-invoice-workbook"))
          (let [declared-id
                (sheet/build-workflow!
                 ctx
                 (sheet/workflow "declared-code"
                   (sheet/blackboard {:invoices [:vector :map]
                                      :output-path :string :workbook-path :string})
                   (sheet/code "write" :fn "ai.obney.orc.predict-rlm-invoice-tools.interface/build-invoice-workbook"
                     :reads [:invoices :output-path] :writes [:workbook-path])))
                declared (sheet/execute ctx declared-id
                                        {:invoices invoices :output-path path})
                undeclared-id
                (sheet/build-workflow!
                 ctx
                 (sheet/workflow "undeclared-code"
                   (sheet/blackboard {:input :string :output :string})
                   (sheet/code "identity" :fn "clojure.core/identity"
                     :reads [:input] :writes [:output])))
                undeclared (sheet/execute ctx undeclared-id {:input "must be blocked"})]
            (is (= :success (:status declared)))
            (is (.exists (io/file (get-in declared [:outputs :workbook-path]))))
            (is (= :failure (:status undeclared)))
            (is (str/includes? (str (:error undeclared)) "not declared")))
          (finally
            (io/delete-file path true)))))))
