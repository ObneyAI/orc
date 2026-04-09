(ns ai.obney.orc.agent-browser.interface
  "Public interface for agent-browser integration.

   agent-browser is a token-efficient browser automation CLI for AI agents.
   Unlike MCP-based solutions, it:

   - Uses shell commands (no session management)
   - Returns compact accessibility tree snapshots (~200-400 tokens)
   - Uses @ref markers (e.g., @e1, @e2) designed for LLMs
   - Supports headed mode for bypassing bot detection

   ## Quick Start

   ```clojure
   (require '[ai.obney.orc.agent-browser.interface :as browser])

   ;; Navigate and get interactive elements
   (browser/open \"https://example.com\")
   (browser/snapshot)
   ;; => {:success true :output \"- heading \\\"Example\\\" [ref=e1]\\n- link \\\"More\\\" [ref=e2]\"}

   ;; Interact using refs
   (browser/click \"@e2\")
   (browser/fill \"@e1\" \"search query\")
   ```

   ## Headed Mode (Bot Detection Bypass)

   Sites like Zillow and apartments.com block headless browsers.
   Use headed mode for human-like browsing:

   ```clojure
   ;; Option 1: Wrap in macro
   (browser/with-human-like-browser
     (browser/open \"https://zillow.com\")
     (browser/snapshot))

   ;; Option 2: Set globally for REPL session
   (browser/set-headed-mode! true)
   ```

   ## For ORC repl-researcher Nodes

   The SCI sandbox integration exposes these as simple functions:

   ```clojure
   (sheet/repl-researcher \"search\"
     :model \"google/gemini-2.5-flash\"
     :instruction \"Navigate to the URL, find the search box, and search for apartments\"
     :reads [:url]
     :writes [:results]
     :browser-tools [\"open\" \"snapshot\" \"click\" \"fill\" \"press\"]
     :max-iterations 5)
   ```"
  (:require [ai.obney.orc.agent-browser.core :as core]))

;; ============================================================================
;; Navigation
;; ============================================================================

(defn open
  "Navigate to a URL.

   Returns: {:success true/false :output string}"
  [url]
  (core/open url))

(defn back
  "Go back in browser history."
  []
  (core/back))

(defn forward
  "Go forward in browser history."
  []
  (core/forward))

(defn reload
  "Reload the current page."
  []
  (core/reload))

;; ============================================================================
;; Accessibility Snapshot (The Key Feature)
;; ============================================================================

(defn snapshot
  "Get accessibility tree snapshot with element refs.

   This is the primary way AI agents understand page content.
   Returns compact output with @ref markers for interaction.

   Options:
   - :interactive - Only interactive elements (default true)
   - :compact - Remove empty structural elements
   - :depth - Limit tree depth

   Example output:
   ```
   - heading \"Example Domain\" [level=1, ref=e1]
   - link \"Learn more\" [ref=e2]
   - textbox \"Email\" [ref=e3]
   ```"
  ([] (core/snapshot))
  ([opts] (core/snapshot opts)))

;; ============================================================================
;; Interaction
;; ============================================================================

(defn click
  "Click an element by ref or selector.

   Examples:
   (click \"@e1\")           ; Click by ref from snapshot
   (click \"button.submit\") ; Click by CSS selector"
  [selector]
  (core/click selector))

(defn fill
  "Clear and fill a form field.

   Example: (fill \"@e3\" \"user@example.com\")"
  [selector text]
  (core/fill selector text))

(defn type-text
  "Type into an element (appends to existing text).

   Example: (type-text \"@e2\" \"search query\")"
  [selector text]
  (core/type-text selector text))

(defn press
  "Press a key or key combination.

   Examples:
   (press \"Enter\")
   (press \"Tab\")
   (press \"Control+a\")"
  [key]
  (core/press key))

(defn scroll
  "Scroll the page.

   Direction: :up, :down, :left, :right
   Pixels: optional scroll amount

   Examples:
   (scroll :down)
   (scroll :down 500)"
  ([direction] (core/scroll direction))
  ([direction pixels] (core/scroll direction pixels)))

(defn wait
  "Wait for element or time.

   Examples:
   (wait 2000)    ; Wait 2 seconds
   (wait \"@e1\") ; Wait for element to appear"
  [selector-or-ms]
  (core/wait selector-or-ms))

;; ============================================================================
;; Get Information
;; ============================================================================

(defn get-text
  "Get text content of an element.

   Example: (get-text \"@e1\")"
  [selector]
  (core/get-text selector))

(defn get-html
  "Get HTML of an element."
  [selector]
  (core/get-html selector))

(defn get-url
  "Get current page URL."
  []
  (core/get-url))

(defn get-title
  "Get current page title."
  []
  (core/get-title))

(defn get-value
  "Get value of a form field."
  [selector]
  (core/get-value selector))

(defn get-count
  "Get count of matching elements."
  [selector]
  (core/get-count selector))

;; ============================================================================
;; State Checks
;; ============================================================================

(defn visible?
  "Check if element is visible."
  [selector]
  (core/visible? selector))

(defn enabled?
  "Check if element is enabled."
  [selector]
  (core/enabled? selector))

(defn checked?
  "Check if checkbox is checked."
  [selector]
  (core/checked? selector))

;; ============================================================================
;; Semantic Locators
;; ============================================================================

(defn find-by-role
  "Find element by accessibility role.

   Example: (find-by-role \"button\" {:name \"Submit\"})"
  ([role] (core/find-by-role role))
  ([role opts] (core/find-by-role role opts)))

(defn find-by-text
  "Find element by text content."
  [text]
  (core/find-by-text text))

(defn find-by-label
  "Find form element by its label."
  [label]
  (core/find-by-label label))

(defn find-by-placeholder
  "Find input by placeholder text."
  [placeholder]
  (core/find-by-placeholder placeholder))

;; ============================================================================
;; Mouse Operations (Bot Detection Bypass)
;; ============================================================================

(defn mouse-down
  "Press mouse button without releasing.

   Button: :left (default), :right, :middle

   Example:
   (mouse-down)         ;; left button
   (mouse-down :right)  ;; right button"
  ([] (core/mouse-down))
  ([btn] (core/mouse-down btn)))

(defn mouse-up
  "Release mouse button.

   Button: :left (default), :right, :middle"
  ([] (core/mouse-up))
  ([btn] (core/mouse-up btn)))

(defn mouse-move
  "Move mouse to absolute coordinates.

   Example: (mouse-move 500 300)"
  [x y]
  (core/mouse-move x y))

(defn mouse-wheel
  "Scroll mouse wheel.

   dy: vertical scroll (negative = up, positive = down)
   dx: horizontal scroll (optional)"
  ([dy] (core/mouse-wheel dy))
  ([dy dx] (core/mouse-wheel dy dx)))

(defn get-box
  "Get bounding box of an element.

   Returns: {:success true :box {:x :y :width :height}} or error"
  [selector]
  (core/get-box selector))

(defn press-and-hold
  "Press and hold on element for duration-ms milliseconds.

   This is the key operation for bot detection bypass (e.g., Zillow's
   'press and hold to verify' button).

   How it works:
   1. Hovers on the element (moves mouse to it)
   2. Presses left mouse button
   3. Waits for duration-ms
   4. Releases mouse button

   Example:
   (press-and-hold \"@e123\" 2500)  ;; hold for 2.5 seconds"
  [selector duration-ms]
  (core/press-and-hold selector duration-ms))

(defn dblclick
  "Double-click on element.

   Example: (dblclick \"@e1\")"
  [selector]
  (core/dblclick selector))

(defn hover
  "Hover over element (triggers mouseover events).

   Useful for dropdowns, tooltips, and menus that activate on hover.

   Example: (hover \"@e5\")"
  [selector]
  (core/hover selector))

(defn drag
  "Drag from source element to destination element.

   Useful for slider CAPTCHAs, drag-and-drop interfaces.

   Example: (drag \"@e10\" \"@e11\")"
  [src-selector dst-selector]
  (core/drag src-selector dst-selector))

;; ============================================================================
;; Screenshots
;; ============================================================================

(defn screenshot
  "Take a screenshot.

   Options:
   - :path - Output file path
   - :full - Full page screenshot
   - :annotate - Add numbered labels for AI vision models"
  ([] (core/screenshot))
  ([opts] (core/screenshot opts)))

;; ============================================================================
;; JavaScript
;; ============================================================================

(defn eval-js
  "Execute JavaScript in page context.

   Example: (eval-js \"document.querySelectorAll('.item').length\")"
  [js-code]
  (core/eval-js js-code))

;; ============================================================================
;; Browser Management
;; ============================================================================

(defn close
  "Close the browser.

   Options:
   - :all - Close all sessions"
  ([] (core/close-browser))
  ([opts] (core/close-browser opts)))

(defn list-sessions
  "List active browser sessions."
  []
  (core/list-sessions))

;; ============================================================================
;; Batch Execution
;; ============================================================================

(defn batch
  "Execute multiple commands in sequence.

   This reduces process overhead for multi-step workflows.

   Example:
   (batch [\"open https://example.com\"
           \"snapshot -i\"
           \"click @e1\"
           \"wait 1000\"
           \"snapshot -i\"])"
  ([commands] (core/batch commands))
  ([commands opts] (core/batch commands opts)))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn navigate-and-snapshot
  "Navigate to URL and return accessibility snapshot.

   This is the most common workflow for AI agents."
  [url]
  (core/navigate-and-snapshot url))

(defn extract-page-info
  "Extract page title, URL, and interactive elements."
  []
  (core/extract-page-info))

(defn fill-form
  "Fill multiple form fields.

   Fields is a map of selector -> value.

   Example:
   (fill-form {\"@e1\" \"user@example.com\"
               \"@e2\" \"password123\"})"
  [fields]
  (core/fill-form fields))

(defn click-and-wait
  "Click an element and wait for navigation/content.

   Example: (click-and-wait \"@e2\" 3000)"
  ([selector] (core/click-and-wait selector))
  ([selector wait-ms] (core/click-and-wait selector wait-ms)))

;; ============================================================================
;; SCI Sandbox Tools (For ORC Integration)
;; ============================================================================

(def browser-tools
  "Tool definitions for SCI sandbox integration.

   These can be injected into repl-researcher nodes."
  {"open" open
   "snapshot" snapshot
   "click" click
   "fill" fill
   "type" type-text
   "press" press
   "scroll" scroll
   "wait" wait
   "get-text" get-text
   "get-url" get-url
   "get-title" get-title
   "back" back
   "forward" forward
   "screenshot" screenshot
   "eval-js" eval-js
   "find-by-role" find-by-role
   "find-by-text" find-by-text
   "find-by-label" find-by-label
   ;; Mouse operations for bot detection bypass
   "mouse-down" mouse-down
   "mouse-up" mouse-up
   "mouse-move" mouse-move
   "mouse-wheel" mouse-wheel
   "press-and-hold" press-and-hold
   "dblclick" dblclick
   "hover" hover
   "drag" drag
   "get-box" get-box})

(defn create-tool-bindings
  "Create SCI bindings for browser tools.

   Returns a map suitable for merging into SCI :bindings."
  []
  (reduce-kv
   (fn [acc name fn]
     (assoc acc (symbol name) fn))
   {}
   browser-tools))

;; ============================================================================
;; Headed Mode (Bot Detection Bypass)
;; ============================================================================

(def ^:dynamic *browser-config*
  "Browser configuration for controlling browser behavior.

   Options:
   - :headed  - Run visible browser (bypasses bot detection)
   - :slow-mo - Milliseconds between actions (human-like behavior)

   Re-exported from core for convenience."
  core/*browser-config*)

(defmacro with-headed-browser
  "Execute browser commands with headed (visible) browser.

   Use this to bypass bot detection on sites like Zillow, apartments.com.

   Example:
   (with-headed-browser
     (open \"https://zillow.com\")
     (snapshot))"
  [& body]
  `(core/with-headed-browser ~@body))

(defmacro with-human-like-browser
  "Execute browser commands with headed browser and 100ms slow-mo.

   This creates more human-like browsing patterns to avoid bot detection.

   Example:
   (with-human-like-browser
     (open \"https://zillow.com\")
     (wait 2000)
     (scroll :down)
     (snapshot))"
  [& body]
  `(core/with-human-like-browser ~@body))

(defn set-headed-mode!
  "Globally enable or disable headed mode.

   Use for REPL sessions where you want all browser operations to be headed.

   Example:
   (set-headed-mode! true)   ;; all commands now use visible browser
   (set-headed-mode! false)  ;; back to headless"
  [headed?]
  (core/set-headed-mode! headed?))
