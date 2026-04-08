(ns ai.obney.orc.agent-browser.core
  "Clojure wrapper for agent-browser CLI.

   agent-browser is a fast, token-efficient browser automation CLI
   designed for AI agents. This namespace provides:

   - Shell-based execution (no session management needed)
   - Accessibility tree snapshots with @ref markers
   - Batch command execution for efficiency
   - Direct integration with ORC's SCI sandbox"
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cheshire.core :as json]
            [com.brunobonacci.mulog :as u]))

;; ============================================================================
;; Shell Execution
;; ============================================================================

(defn- run-command
  "Execute an agent-browser command and return result.

   Returns:
   {:success true/false
    :output  string
    :error   string (if failed)}"
  [& args]
  (u/trace ::run-command {:args args}
    (let [result (apply shell/sh "agent-browser" (map str args))]
      (if (zero? (:exit result))
        {:success true
         :output (str/trim (:out result))}
        {:success false
         :output (str/trim (:out result))
         :error (str/trim (:err result))}))))

(defn- run-command-json
  "Execute command with --json flag and parse result."
  [& args]
  (let [result (apply run-command (concat args ["--json"]))]
    (if (:success result)
      (try
        (assoc result :data (json/parse-string (:output result) true))
        (catch Exception _
          result))
      result)))

;; ============================================================================
;; Core Browser Commands
;; ============================================================================

(defn open
  "Navigate to a URL.

   Example: (open \"https://example.com\")"
  [url]
  (run-command "open" url))

(defn snapshot
  "Get accessibility tree snapshot with element refs.

   Options:
   - :interactive - Only interactive elements (default true)
   - :compact - Remove empty structural elements
   - :depth - Limit tree depth
   - :selector - Scope to CSS selector

   Returns snapshot with refs like @e1, @e2 for element targeting."
  ([] (snapshot {}))
  ([opts]
   (let [args (cond-> ["snapshot"]
                (:interactive opts true) (conj "-i")
                (:compact opts) (conj "-c")
                (:depth opts) (conj "-d" (str (:depth opts)))
                (:selector opts) (conj "-s" (:selector opts)))]
     (apply run-command args))))

(defn click
  "Click an element by ref or selector.

   Examples:
   (click \"@e1\")
   (click \"button.submit\")"
  [selector]
  (run-command "click" selector))

(defn fill
  "Clear and fill a form field.

   Example: (fill \"@e3\" \"user@example.com\")"
  [selector text]
  (run-command "fill" selector text))

(defn type-text
  "Type into an element (appends to existing).

   Example: (type-text \"@e2\" \"search query\")"
  [selector text]
  (run-command "type" selector text))

(defn press
  "Press a key.

   Examples:
   (press \"Enter\")
   (press \"Control+a\")"
  [key]
  (run-command "press" key))

(defn scroll
  "Scroll the page.

   Direction: :up, :down, :left, :right
   Pixels: optional scroll amount"
  ([direction]
   (run-command "scroll" (name direction)))
  ([direction pixels]
   (run-command "scroll" (name direction) (str pixels))))

(defn wait
  "Wait for element or time.

   Examples:
   (wait 2000)          ; wait 2 seconds
   (wait \"@e1\")       ; wait for element"
  [selector-or-ms]
  (run-command "wait" (str selector-or-ms)))

(defn screenshot
  "Take a screenshot.

   Options:
   - :path - Output file path (optional)
   - :full - Full page screenshot
   - :annotate - Add numbered labels"
  ([] (screenshot {}))
  ([opts]
   (let [args (cond-> ["screenshot"]
                (:full opts) (conj "--full")
                (:annotate opts) (conj "--annotate")
                (:path opts) (conj (:path opts)))]
     (apply run-command args))))

;; ============================================================================
;; Get Information
;; ============================================================================

(defn get-text
  "Get text content of an element."
  [selector]
  (run-command "get" "text" selector))

(defn get-html
  "Get HTML of an element."
  [selector]
  (run-command "get" "html" selector))

(defn get-value
  "Get value of a form element."
  [selector]
  (run-command "get" "value" selector))

(defn get-url
  "Get current page URL."
  []
  (run-command "get" "url"))

(defn get-title
  "Get current page title."
  []
  (run-command "get" "title"))

(defn get-count
  "Get count of matching elements."
  [selector]
  (run-command "get" "count" selector))

;; ============================================================================
;; Check State
;; ============================================================================

(defn visible?
  "Check if element is visible."
  [selector]
  (let [result (run-command "is" "visible" selector)]
    (and (:success result)
         (str/includes? (:output result) "true"))))

(defn enabled?
  "Check if element is enabled."
  [selector]
  (let [result (run-command "is" "enabled" selector)]
    (and (:success result)
         (str/includes? (:output result) "true"))))

(defn checked?
  "Check if checkbox is checked."
  [selector]
  (let [result (run-command "is" "checked" selector)]
    (and (:success result)
         (str/includes? (:output result) "true"))))

;; ============================================================================
;; Find Elements (Semantic Locators)
;; ============================================================================

(defn find-by-role
  "Find element by accessibility role.

   Example: (find-by-role \"button\" {:name \"Submit\"})"
  ([role] (find-by-role role {}))
  ([role opts]
   (let [args (cond-> ["find" "role" role "snapshot"]
                (:name opts) (conj "--name" (:name opts)))]
     (apply run-command args))))

(defn find-by-text
  "Find element by text content."
  [text]
  (run-command "find" "text" text "snapshot"))

(defn find-by-label
  "Find form element by label."
  [label]
  (run-command "find" "label" label "snapshot"))

(defn find-by-placeholder
  "Find input by placeholder text."
  [placeholder]
  (run-command "find" "placeholder" placeholder "snapshot"))

;; ============================================================================
;; Navigation
;; ============================================================================

(defn back
  "Go back in browser history."
  []
  (run-command "back"))

(defn forward
  "Go forward in browser history."
  []
  (run-command "forward"))

(defn reload
  "Reload the current page."
  []
  (run-command "reload"))

;; ============================================================================
;; Advanced
;; ============================================================================

(defn eval-js
  "Execute JavaScript in page context.

   Example: (eval-js \"document.title\")"
  [js-code]
  (run-command "eval" js-code))

(defn close-browser
  "Close the browser.

   Options:
   - :all - Close all sessions"
  ([] (run-command "close"))
  ([opts]
   (if (:all opts)
     (run-command "close" "--all")
     (run-command "close"))))

;; ============================================================================
;; Batch Execution
;; ============================================================================

(defn batch
  "Execute multiple commands in sequence.

   Commands is a vector of command strings.

   Example:
   (batch [\"open https://example.com\"
           \"snapshot -i\"
           \"click @e1\"])"
  ([commands] (batch commands {}))
  ([commands opts]
   (let [json-commands (json/generate-string commands)
         args (cond-> ["batch"]
                (:bail opts) (conj "--bail"))]
     (apply run-command (conj args json-commands)))))

;; ============================================================================
;; Session Management
;; ============================================================================

(defn list-sessions
  "List active browser sessions."
  []
  (run-command "session" "list"))

(defn current-session
  "Get current session name."
  []
  (run-command "session"))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn navigate-and-snapshot
  "Navigate to URL and return accessibility snapshot.

   This is the most common workflow for AI agents."
  [url]
  (let [nav-result (open url)]
    (if (:success nav-result)
      (let [snap-result (snapshot {:interactive true})]
        (assoc snap-result :url url))
      nav-result)))

(defn extract-page-info
  "Extract page title, URL, and interactive elements."
  []
  (let [url-result (get-url)
        title-result (get-title)
        snap-result (snapshot {:interactive true})]
    {:url (:output url-result)
     :title (:output title-result)
     :elements (:output snap-result)
     :success (and (:success url-result)
                   (:success title-result)
                   (:success snap-result))}))

(defn fill-form
  "Fill multiple form fields.

   Fields is a map of selector -> value.

   Example:
   (fill-form {\"@e1\" \"user@example.com\"
               \"@e2\" \"password123\"})"
  [fields]
  (let [results (for [[selector value] fields]
                  (fill selector value))]
    {:success (every? :success results)
     :results results}))

(defn click-and-wait
  "Click an element and wait for navigation/content."
  ([selector] (click-and-wait selector 2000))
  ([selector wait-ms]
   (let [click-result (click selector)]
     (when (:success click-result)
       (wait wait-ms))
     click-result)))
