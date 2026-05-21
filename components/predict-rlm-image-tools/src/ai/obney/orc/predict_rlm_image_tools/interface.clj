(ns ai.obney.orc.predict-rlm-image-tools.interface
  "Image loading helpers for the image_analysis benchmark port.

   Pure host-side functions. Read an image file, base64-encode the bytes,
   prepend the data-URI MIME prefix (detected from extension). Used by the
   benchmark task definition to populate the blackboard with an image-typed
   value before the RLM researcher starts."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util Base64]))

(def ^:private extension->mime
  {"png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "webp" "image/webp"
   "gif"  "image/gif"})

(defn- mime-for [^String path]
  (let [ext (some-> path
                    (str/lower-case)
                    (subs (inc (.lastIndexOf ^String (str/lower-case path) "."))))]
    (or (get extension->mime ext)
        (throw (ex-info (str "Unsupported image extension: " ext)
                        {:path path
                         :extension ext
                         :supported (keys extension->mime)})))))

(defn image->data-uri
  "Read the image at `path`, detect MIME from its extension, and return a
   base64 data URI string of the form `data:<mime>;base64,<encoded-bytes>`.

   Supported extensions: png, jpg/jpeg, webp, gif. Throws ex-info on any
   other extension."
  [^String path]
  (let [mime (mime-for path)
        bytes (Files/readAllBytes (.toPath (File. path)))
        encoded (.encodeToString (Base64/getEncoder) bytes)]
    (str "data:" mime ";base64," encoded)))

;; =============================================================================
;; Code-node fns for emit-tree! :code references
;; =============================================================================
;;
;; These are deterministic pure functions referenced by the model's emitted
;; :code nodes via {:fn "ai.obney.orc.predict-rlm-image-tools.interface/<name>"}.
;; They live here so the model has concrete affordances advertised via the
;; task's :available-code-nodes catalog — avoiding LLM-based counting which
;; is hallucination-prone.
;;
;; All fns follow the standard :code-node contract:
;;   (fn [{:keys [inputs] ...}] {:write-key value})

(defn count-letter-frequencies
  "Count case-insensitive A-Z letter frequencies in the input text.

   :inputs map - must contain a single text key. Returns a map with key
   :letter-frequencies whose value is a sorted-map of {\\A int ... \\Z int}.

   Example tree usage:
     [:code {:fn \"ai.obney.orc.predict-rlm-image-tools.interface/count-letter-frequencies\"
             :reads [:reconciled-text]
             :writes [:letter-frequencies]}]"
  [{:keys [inputs]}]
  (let [text (-> inputs vals first str)
        letters (filter #(Character/isLetter ^char %) text)
        lower (map #(Character/toLowerCase ^char %) letters)
        ;; Initialize with all letters a-z at 0 so the output is dense
        zero-map (into (sorted-map) (map (fn [c] [c 0]) (map char (range (int \a) (inc (int \z))))))
        freqs (reduce (fn [m c] (update m c (fnil inc 0))) zero-map lower)
        ;; Re-key to uppercase for output
        upper-freqs (into (sorted-map)
                          (map (fn [[c n]] [(Character/toUpperCase ^char c) n]) freqs))]
    {:letter-frequencies upper-freqs}))

(defn format-letter-counts-answer
  "Format a letter-frequency map into the standard A: N\\nB: N\\n... answer
   string used by the image_analysis benchmark.

   :inputs map - must contain a single frequency-map key whose value is
   {char int} (output of count-letter-frequencies). Returns {:answer string}.

   Example tree usage:
     [:code {:fn \"ai.obney.orc.predict-rlm-image-tools.interface/format-letter-counts-answer\"
             :reads [:letter-frequencies]
             :writes [:answer]}]"
  [{:keys [inputs]}]
  (let [freqs (-> inputs vals first)
        lines (for [c (map char (range (int \A) (inc (int \Z))))]
                (str c ": " (get freqs c 0)))]
    {:answer (str/join "\n" lines)}))

(def available-code-nodes
  "Markdown catalog of code-node fns provided by this brick. Surfaced to
   the RLM researcher via the task's :available-code-nodes field so the
   model knows what deterministic fns it can reference in :code nodes."
  "Available code-node functions for use in emit-tree! :code nodes:

  - ai.obney.orc.predict-rlm-image-tools.interface/count-letter-frequencies
      :reads [<text-key>]     ; expects a single text string
      :writes [:letter-frequencies]
      Returns a sorted-map {\\A int \\B int ... \\Z int} of case-insensitive
      A-Z letter counts in the input text. All 26 letters always present
      (0 when absent). Use this instead of an LLM call for letter counting
      — pure-Clojure counting is definitively correct; LLM counting is
      hallucination-prone.

  - ai.obney.orc.predict-rlm-image-tools.interface/format-letter-counts-answer
      :reads [<freqs-key>]    ; expects the output of count-letter-frequencies
      :writes [:answer]
      Formats a frequency map into 'A: N\\nB: N\\n...\\nZ: N' as required
      by the image_analysis answer contract.

  Typical tree shape:
    [:sequence
     [:llm {:reads [:image] :writes [:text1]}]
     [:llm {:reads [:image] :writes [:text2]}]
     [:llm {:reads [:text1 :text2] :writes [:merged]}]
     [:code {:fn \"ai.obney.orc.predict-rlm-image-tools.interface/count-letter-frequencies\"
             :reads [:merged] :writes [:counts]}]
     [:code {:fn \"ai.obney.orc.predict-rlm-image-tools.interface/format-letter-counts-answer\"
             :reads [:counts] :writes [:answer]}]
     [:final {:keys [:answer]}]]")
