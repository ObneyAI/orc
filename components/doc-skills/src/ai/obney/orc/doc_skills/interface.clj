(ns ai.obney.orc.doc-skills.interface
  "Public interface for the doc-skills component.

   Three thin wrappers over Apache PDFBox + Apache POI, exposed in two
   complementary shapes:

   1. `sci-bindings` — namespaced symbol map suitable for orc's
      repl-researcher :extra-bindings (RLM mode) or as :namespaces in a
      caller-built SCI context. The LLM-authored Clojure code can then
      call (pdf/page-count …), (xlsx/write-workbook …), etc.

   2. `call-tool-fn` — a function (tool-name args-map) -> result that maps
      MCP-style tool names like \"pdf/page-image\" onto the underlying
      Clojure functions. Suitable for the existing :mcp-tools / :call-tool-fn
      path that orc's repl-researcher already supports without any RLM
      changes.

   Plus `instructions` — bundled markdown prose describing each skill,
   intended to be concatenated into a node's :instruction field so the LLM
   knows the API surface."
  (:require [clojure.java.io :as io]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [ai.obney.orc.doc-skills.core.xlsx :as xlsx]
            [ai.obney.orc.doc-skills.core.docx :as docx]))

;; =============================================================================
;; SCI-friendly bindings
;; =============================================================================

(def ^:private pdf-fns
  {'page-count            pdf/page-count
   'page-text             pdf/page-text
   'document-text         pdf/document-text
   'page-image            pdf/page-image
   'page-image-data-uri   pdf/page-image-data-uri
   'search-text           pdf/search-text
   'redact-rects          pdf/redact-rects
   'page-bounds           pdf/page-bounds})

(def ^:private xlsx-fns
  {'write-workbook        xlsx/write-workbook
   'list-sheets           xlsx/list-sheets
   'read-sheet            xlsx/read-sheet
   'read-sheet-as-maps    xlsx/read-sheet-as-maps})

(def ^:private docx-fns
  {'write-docx               docx/write-docx
   'markdown->elements       docx/markdown->elements
   'write-markdown-as-docx   docx/write-markdown-as-docx})

(def sci-bindings
  "Namespaced symbol map for SCI exposure. Keys are quoted symbols matching
   the namespace they should appear under to LLM-authored code:

     (pdf/page-count …)
     (xlsx/write-workbook …)
     (docx/write-docx …)

   Pass to `build-sci-context :extra-bindings` after flattening if you want
   them in the user namespace, or wrap each fn into an orc-MCP-style flat
   symbol with namespace prefix in its name (the executor's predict-mode
   :extra-bindings expects flat keys).

   For pure RLM mode, the executor flattens these into the user ns so the
   LLM writes (pdf/page-count …) directly."
  {'pdf  pdf-fns
   'xlsx xlsx-fns
   'docx docx-fns})

(def sci-flat-bindings
  "Flat (symbol -> fn) map of all skill functions, with namespace prefix
   baked into the symbol name. Suitable for direct merge into
   build-sci-context :extra-bindings — LLM code calls them as
   (pdf/page-count …) thanks to SCI's symbol resolution."
  (reduce-kv
    (fn [acc ns-sym fns]
      (reduce-kv
        (fn [acc' fn-sym f]
          (assoc acc' (symbol (name ns-sym) (name fn-sym)) f))
        acc
        fns))
    {}
    sci-bindings))

;; =============================================================================
;; MCP-style call-tool-fn dispatcher
;; =============================================================================

(defn- read-resource [path]
  (some-> (io/resource path) slurp))

(defn- ->positional
  "Translate a {:keyword args} map into positional args based on the fn's
   parameter names. Provided for tool-call ergonomics."
  [args-map keys]
  (mapv (fn [k] (or (get args-map k) (get args-map (clojure.core/name k)))) keys))

(def ^:private tool-specs
  "Per-tool required-arg specifications. Each entry maps a tool name to its
   required keys (in arg order) so the dispatcher can produce precise
   error messages when the LLM passes the wrong shape (missing keys,
   typo'd keys like :path instead of :out-path, etc.)."
  {"pdf/page-count"            [:path]
   "pdf/page-text"             [:path :n]
   "pdf/document-text"         [:path]
   "pdf/page-image"            [:path :n :out-path]
   "pdf/page-image-data-uri"   [:path :n]
   "pdf/search-text"           [:path :n :query]
   "pdf/redact-rects"          [:path :out-path :rect-specs]
   "pdf/page-bounds"           [:path :n]
   "xlsx/write-workbook"       [:out-path :sheets-spec]
   "xlsx/list-sheets"          [:path]
   "xlsx/read-sheet"           [:path :sheet-name]
   "xlsx/read-sheet-as-maps"   [:path :sheet-name]
   "docx/write-docx"           [:out-path :elements]
   "docx/markdown->elements"   [:md]
   "docx/write-markdown-as-docx" [:out-path :md]})

(defn- require-args!
  "Validate that `args` (a map) contains every key in `required`. Throws
   ex-info with a precise 'expected … got …' message when keys are
   missing — no silent slip-through. The thrown error reaches SCI as the
   tool-call's exception, which the executor records on the iteration
   for the next LLM prompt."
  [tool-name required args]
  (when-not (map? args)
    (throw (ex-info (str tool-name " expects a single map argument with keys "
                         (pr-str required) ", got " (pr-str args))
                    {:type :doc-skills/bad-arg-shape
                     :tool tool-name
                     :expected required
                     :got args})))
  (let [missing (vec (remove #(contains? args %) required))]
    (when (seq missing)
      (throw (ex-info (str tool-name " missing required keys " (pr-str missing)
                           ". Expected " (pr-str required)
                           "; got keys " (pr-str (vec (keys args))))
                      {:type :doc-skills/missing-keys
                       :tool tool-name
                       :expected required
                       :missing missing
                       :got-keys (vec (keys args))})))))

(defn call-tool-fn
  "Build a (fn [tool-name args-map] -> result) suitable for orc's
   repl-researcher :call-tool-fn. Tool names follow MCP convention with the
   skill namespace as prefix; each tool's required arg keys are validated
   strictly — wrong/missing keys throw an ex-info with a precise message
   (NO silent defaulting / coercion).

   Required-arg shapes:
     \"pdf/page-count\"            {:path \"…\"}                    -> int
     \"pdf/page-text\"             {:path \"…\" :n 0}               -> string
     \"pdf/document-text\"         {:path \"…\"}                    -> string
     \"pdf/page-image\"            {:path :n :out-path [:dpi]}      -> path
     \"pdf/page-image-data-uri\"   {:path :n [:dpi]}                -> data uri
     \"pdf/search-text\"           {:path :n :query}                -> [{:rect :match}]
     \"pdf/redact-rects\"          {:path :out-path :rect-specs}    -> path
     \"pdf/page-bounds\"           {:path :n}                       -> {:width :height}
     \"xlsx/write-workbook\"       {:out-path :sheets-spec}         -> path
     \"xlsx/list-sheets\"          {:path}                          -> [string]
     \"xlsx/read-sheet\"           {:path :sheet-name}              -> [[…]]
     \"xlsx/read-sheet-as-maps\"   {:path :sheet-name}              -> [{…}]
     \"docx/write-docx\"           {:out-path :elements}            -> path
     \"docx/markdown->elements\"   {:md}                            -> elements
     \"docx/write-markdown-as-docx\" {:out-path :md}                -> path

   Unknown tool names return {:error \"Unknown tool: …\"}."
  [& {:keys [allow-deny] :or {allow-deny :allow}}]
  (fn dispatch [tool-name args]
    (let [args (if (map? args) args (or args {}))]
      (when (contains? tool-specs tool-name)
        (require-args! tool-name (get tool-specs tool-name) args))
      (case tool-name
        ;; PDF
        "pdf/page-count"          (pdf/page-count (:path args))
        "pdf/page-text"           (pdf/page-text (:path args) (:n args))
        "pdf/document-text"       (pdf/document-text (:path args))
        "pdf/page-image"          (pdf/page-image (:path args) (:n args) (:out-path args)
                                                  :dpi (or (:dpi args) 200))
        "pdf/page-image-data-uri" (pdf/page-image-data-uri (:path args) (:n args)
                                                           :dpi (or (:dpi args) 200))
        "pdf/search-text"         (pdf/search-text (:path args) (:n args) (:query args))
        "pdf/redact-rects"        (pdf/redact-rects (:path args) (:out-path args) (:rect-specs args))
        "pdf/page-bounds"         (pdf/page-bounds (:path args) (:n args))
        ;; XLSX
        "xlsx/write-workbook"     (xlsx/write-workbook (:out-path args) (:sheets-spec args))
        "xlsx/list-sheets"        (xlsx/list-sheets (:path args))
        "xlsx/read-sheet"         (xlsx/read-sheet (:path args) (:sheet-name args))
        "xlsx/read-sheet-as-maps" (xlsx/read-sheet-as-maps (:path args) (:sheet-name args))
        ;; DOCX
        "docx/write-docx"             (docx/write-docx (:out-path args) (:elements args))
        "docx/markdown->elements"     (docx/markdown->elements (:md args))
        "docx/write-markdown-as-docx" (docx/write-markdown-as-docx (:out-path args) (:md args))
        {:error (str "Unknown tool: " tool-name)}))))

(def all-tool-names
  "Vector of all tool names this skill exposes via call-tool-fn. Suitable
   for `:mcp-tools` on a repl-researcher node."
  ["pdf/page-count" "pdf/page-text" "pdf/document-text"
   "pdf/page-image" "pdf/page-image-data-uri"
   "pdf/search-text" "pdf/redact-rects" "pdf/page-bounds"
   "xlsx/write-workbook" "xlsx/list-sheets"
   "xlsx/read-sheet" "xlsx/read-sheet-as-maps"
   "docx/write-docx" "docx/markdown->elements" "docx/write-markdown-as-docx"])

;; =============================================================================
;; Instruction prose (LM-facing)
;; =============================================================================

(def instructions
  "Map of skill keyword → markdown instruction string. Concatenate into a
   node's :instruction (or repl-researcher :rlm context) so the LLM knows
   the API surface."
  {:pdf       (delay (read-resource "ai/obney/orc/doc_skills/instructions/pdf.md"))
   :xlsx      (delay (read-resource "ai/obney/orc/doc_skills/instructions/xlsx.md"))
   :docx      (delay (read-resource "ai/obney/orc/doc_skills/instructions/docx.md"))
   :redaction (delay (read-resource "ai/obney/orc/doc_skills/instructions/redaction.md"))})

(defn instruction
  "Realize and return the instruction text for a skill keyword."
  [k]
  (some-> (get instructions k) deref))

(defn compose-instructions
  "Concatenate instructions for the given skill keywords (in order),
   separated by blank lines. Useful for building a Style-B repl-researcher
   :instruction prompt."
  [& ks]
  (clojure.string/join "\n\n" (keep instruction ks)))
