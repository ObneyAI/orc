(ns tavily-client
  "Native Tavily API client for ORC.

   Direct HTTP integration with Tavily's REST API - no external MCP server needed.
   ORC manages its own API connection.

   Usage:
   ```clojure
   ;; Initialize client (reads TAVILY_API_KEY from env)
   (def tavily (create-client))

   ;; Search the web
   (search tavily {:query \"apartments for rent San Francisco\"})

   ;; Get direct answer
   (qna tavily {:query \"What are the best apartment listing sites?\"})

   ;; Extract content from URLs
   (extract tavily {:urls [\"https://apartments.com/...\"]})
   ```

   For ORC repl-researcher integration:
   ```clojure
   ;; Register as MCP-compatible tool
   (set-mcp-call-tool-fn! (tavily-call-tool-fn tavily))
   ```"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:private tavily-api-base "https://api.tavily.com")

(defn- get-api-key
  "Get Tavily API key from environment."
  []
  (or (System/getenv "TAVILY_API_KEY")
      (throw (ex-info "TAVILY_API_KEY environment variable not set"
                      {:hint "Set TAVILY_API_KEY in your environment"}))))

;; =============================================================================
;; HTTP Client
;; =============================================================================

(defn- make-request
  "Make HTTP POST request to Tavily API."
  [endpoint body api-key]
  (try
    (let [response (http/post (str tavily-api-base endpoint)
                              {:headers {"Content-Type" "application/json"}
                               :body (json/generate-string (assoc body :api_key api-key))
                               :as :json
                               :throw-exceptions false})]
      (if (= 200 (:status response))
        {:success true
         :data (:body response)}
        {:success false
         :error (get-in response [:body :detail] "Unknown error")
         :status (:status response)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

;; =============================================================================
;; Client Record
;; =============================================================================

(defrecord TavilyClient [api-key])

(defn create-client
  "Create a Tavily client instance.

   Options:
   - :api-key - Tavily API key (defaults to TAVILY_API_KEY env var)"
  [& [{:keys [api-key]}]]
  (->TavilyClient (or api-key (get-api-key))))

;; =============================================================================
;; API Functions
;; =============================================================================

(defn search
  "Search the web using Tavily.

   Args:
   - client: TavilyClient instance
   - opts: Search options
     - :query (required) - Search query string
     - :search-depth - \"basic\" or \"advanced\" (default: \"basic\")
     - :include-answer - Include AI summary (default: true)
     - :max-results - Number of results (default: 5)
     - :include-domains - List of domains to include
     - :exclude-domains - List of domains to exclude

   Returns:
   {:success true
    :data {:query \"...\"
           :answer \"AI summary...\"
           :results [{:title :url :content :score}]}}"
  [client {:keys [query search-depth include-answer max-results
                  include-domains exclude-domains]
           :or {search-depth "basic"
                include-answer true
                max-results 5}}]
  (make-request "/search"
                (cond-> {:query query
                         :search_depth search-depth
                         :include_answer include-answer
                         :max_results max-results}
                  (seq include-domains) (assoc :include_domains include-domains)
                  (seq exclude-domains) (assoc :exclude_domains exclude-domains))
                (:api-key client)))

(defn qna
  "Get a direct answer to a question.

   Uses Tavily's QnA endpoint for concise, factual answers.

   Args:
   - client: TavilyClient instance
   - opts:
     - :query (required) - Question to answer

   Returns:
   {:success true
    :data {:answer \"Direct answer to the question\"}}"
  [client {:keys [query]}]
  (make-request "/search"
                {:query query
                 :search_depth "advanced"
                 :include_answer true
                 :max_results 3}
                (:api-key client)))

(defn extract
  "Extract content from URLs.

   Fetches and parses content from one or more URLs.

   Args:
   - client: TavilyClient instance
   - opts:
     - :urls (required) - Vector of URLs to extract

   Returns:
   {:success true
    :data {:results [{:url :raw_content :...}]}}"
  [client {:keys [urls]}]
  (make-request "/extract"
                {:urls (if (string? urls) [urls] urls)}
                (:api-key client)))

;; =============================================================================
;; MCP-Compatible Tool Interface
;; =============================================================================

(defn tavily-call-tool-fn
  "Create a call-tool function compatible with ORC's MCP interface.

   Usage:
   ```clojure
   (def tavily (create-client))
   (set-mcp-call-tool-fn! (tavily-call-tool-fn tavily))
   ```

   Supports tool names:
   - tavily_search / tavily/search
   - tavily_qna / tavily/qna
   - tavily_extract / tavily/extract"
  [client]
  (fn [tool-name args]
    (let [tool (-> tool-name
                   (str/replace #"^tavily[/_]" "")
                   keyword)]
      (case tool
        :search (search client args)
        :qna (qna client args)
        :extract (extract client args)
        {:success false
         :error (str "Unknown Tavily tool: " tool-name)}))))

;; =============================================================================
;; ORC Registration Helper
;; =============================================================================

(defn register-with-orc!
  "Register Tavily as an MCP tool source with ORC.

   Creates client and sets up the call-tool function.

   Usage:
   ```clojure
   (register-with-orc!)
   ;; Now repl-researcher nodes can use tavily tools
   ```"
  [& [opts]]
  (let [client (create-client opts)]
    (require '[ai.obney.orc.orc-dev.core :as dev])
    ((resolve 'ai.obney.orc.orc-dev.core/set-mcp-call-tool-fn!)
     (tavily-call-tool-fn client))
    (println "Tavily registered with ORC")
    client))

;; =============================================================================
;; Convenience Functions for REPL
;; =============================================================================

(defn find-apartment-sites
  "Search for apartment listing websites in a location.

   Convenience wrapper for apartment discovery workflow."
  [client location]
  (search client
          {:query (str location " apartments for rent listing websites")
           :search-depth "advanced"
           :include-answer true
           :max-results 10}))

(defn verify-site
  "Verify if a domain is a legitimate apartment listing site.

   Searches for information about the site to assess trustworthiness."
  [client domain]
  (search client
          {:query (str domain " apartment listing site review legitimate")
           :search-depth "basic"
           :include-answer true
           :max-results 5}))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; ====== CREATE CLIENT ======
  ;; Make sure TAVILY_API_KEY is set
  (def tavily (create-client))

  ;; ====== SEARCH ======
  (search tavily {:query "San Francisco apartments for rent"
                  :max-results 5})

  ;; ====== QNA ======
  (qna tavily {:query "What are the best apartment listing websites in 2024?"})

  ;; ====== FIND APARTMENT SITES ======
  (find-apartment-sites tavily "San Francisco, CA")

  ;; ====== REGISTER WITH ORC ======
  (register-with-orc!)

  ;; Now repl-researcher nodes can use:
  ;; (tavily/search {:query "..."})
  ;; (tavily/qna {:query "..."})
  )
