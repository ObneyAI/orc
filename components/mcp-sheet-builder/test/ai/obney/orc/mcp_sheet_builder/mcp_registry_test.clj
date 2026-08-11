(ns ai.obney.orc.mcp-sheet-builder.mcp-registry-test
  "Tests for the multi-MCP server registry."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-static-conn
  "Create a static MCP connection with the given tools and handler."
  [tools handler]
  (mcp/connect-static {:type :static
                :tools tools
                :call-tool-handler handler}))

(def linear-tools
  [{:name "list_issues"
    :description "List issues"
    :inputSchema {:type "object" :properties {"project" {:type "string"}}}}
   {:name "get_issue"
    :description "Get issue details"
    :inputSchema {:type "object" :properties {"id" {:type "string"}}}}])

(def github-tools
  [{:name "list_pulls"
    :description "List pull requests"
    :inputSchema {:type "object" :properties {"repo" {:type "string"}}}}
   {:name "get_pull"
    :description "Get PR details"
    :inputSchema {:type "object" :properties {"id" {:type "string"}}}}])

;; =============================================================================
;; Registry Creation
;; =============================================================================

(deftest create-registry-test
  (testing "creates registry with prefixed tool catalog"
    (let [linear-conn (make-static-conn linear-tools nil)
          github-conn (make-static-conn github-tools nil)
          registry (mcp/create-registry {"linear" linear-conn
                                          "github" github-conn})]
      (is (= 2 (count (:connections registry))))
      (is (= 4 (count (:catalog registry))))
      (is (contains? (:catalog registry) "linear/list_issues"))
      (is (contains? (:catalog registry) "github/list_pulls"))
      (is (= "linear" (get-in registry [:catalog "linear/list_issues" :server])))
      (is (= "list_issues" (get-in registry [:catalog "linear/list_issues" :name])))))

  (testing "rejects invalid server names"
    (let [conn (make-static-conn [] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid server name"
            (mcp/create-registry {"123bad" conn})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid server name"
            (mcp/create-registry {"has space" conn}))))))

;; =============================================================================
;; Tool Routing
;; =============================================================================

(deftest registry-call-tool-fn-test
  (testing "routes namespaced tool to correct server"
    (let [linear-conn (make-static-conn linear-tools
                        (fn [tool-name _args] {"server" "linear" "tool" tool-name}))
          github-conn (make-static-conn github-tools
                        (fn [tool-name _args] {"server" "github" "tool" tool-name}))
          registry (mcp/create-registry {"linear" linear-conn "github" github-conn})
          call-fn (mcp/registry->call-tool-fn registry)]
      ;; Route to linear
      (let [result (call-fn "linear/list_issues" {:project "abc"})]
        (is (= "linear" (get result "server")))
        (is (= "list_issues" (get result "tool"))))
      ;; Route to github
      (let [result (call-fn "github/list_pulls" {:repo "workshop"})]
        (is (= "github" (get result "server")))
        (is (= "list_pulls" (get result "tool"))))))

  (testing "strips prefix before calling MCP server"
    (let [received-name (atom nil)
          conn (make-static-conn linear-tools
                 (fn [tool-name _args]
                   (reset! received-name tool-name)
                   {"ok" true}))
          registry (mcp/create-registry {"linear" conn})
          call-fn (mcp/registry->call-tool-fn registry)]
      (call-fn "linear/list_issues" {})
      ;; The actual MCP server receives the bare tool name, not the prefixed one
      (is (= "list_issues" @received-name))))

  (testing "resolves unique unprefixed tool across servers"
    (let [linear-conn (make-static-conn linear-tools
                        (fn [tool-name _] {"from" "linear"}))
          github-conn (make-static-conn github-tools
                        (fn [tool-name _] {"from" "github"}))
          registry (mcp/create-registry {"linear" linear-conn "github" github-conn})
          call-fn (mcp/registry->call-tool-fn registry)]
      ;; "list_issues" only exists on linear
      (is (= "linear" (get (call-fn "list_issues" {}) "from")))
      ;; "list_pulls" only exists on github
      (is (= "github" (get (call-fn "list_pulls" {}) "from")))))

  (testing "errors on ambiguous unprefixed tool"
    (let [;; Both servers have a tool named "search"
          conn1 (make-static-conn [{:name "search" :description "Search 1" :inputSchema {}}] nil)
          conn2 (make-static-conn [{:name "search" :description "Search 2" :inputSchema {}}] nil)
          registry (mcp/create-registry {"exa" conn1 "tavily" conn2})
          call-fn (mcp/registry->call-tool-fn registry)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Ambiguous tool"
            (call-fn "search" {})))))

  (testing "errors on unknown server prefix"
    (let [conn (make-static-conn linear-tools nil)
          registry (mcp/create-registry {"linear" conn})
          call-fn (mcp/registry->call-tool-fn registry)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown MCP server"
            (call-fn "unknown/list_issues" {})))))

  (testing "errors on unknown tool"
    (let [conn (make-static-conn linear-tools nil)
          registry (mcp/create-registry {"linear" conn})
          call-fn (mcp/registry->call-tool-fn registry)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown tool"
            (call-fn "nonexistent" {}))))))

;; =============================================================================
;; List & Close
;; =============================================================================

(deftest list-all-tools-test
  (testing "returns all tools with prefixed names"
    (let [linear-conn (make-static-conn linear-tools nil)
          github-conn (make-static-conn github-tools nil)
          registry (mcp/create-registry {"linear" linear-conn "github" github-conn})
          all-tools (mcp/list-all-tools registry)]
      (is (= 4 (count all-tools)))
      (is (= #{"linear/list_issues" "linear/get_issue" "github/list_pulls" "github/get_pull"}
             (set (map :prefixed-name all-tools)))))))

(deftest close-all-test
  (testing "close-all does not throw"
    (let [linear-conn (make-static-conn linear-tools nil)
          github-conn (make-static-conn github-tools nil)
          registry (mcp/create-registry {"linear" linear-conn "github" github-conn})]
      (is (nil? (mcp/close-all registry))))))
