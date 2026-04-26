(ns ai.obney.orc.examples.image-analysis.schemas
  "Malli blackboard schemas for the image_analysis port.

   The predict-rlm original has no schema.py — its outputs are a freeform
   string. We declare blackboard fields directly.")

(def image-paths
  "Vector of image file paths (PNG/JPG/WEBP). Marked with :field-type :file
   as a convention so future tooling can spot inputs that should be
   resolved relative to a sandbox/input dir."
  [:vector {:field-type :file} :string])

(def image-uris
  "Vector of base64 data URIs. Marked with :field-type :image so the
   executor passes them through to DSCloj as raw image inputs (no JSON
   serialization)."
  [:vector {:field-type :image} :string])

(def image-uri
  "Single base64 data URI (for use inside map-each iterations)."
  [:string {:field-type :image}])

(def query :string)

(def answer :string)

(def per-image-finding
  "Per-image observation produced inside map-each."
  :string)

(def per-image-findings
  "Aggregated per-image observations."
  [:vector :string])

(def blackboard
  "Full blackboard schema for the image_analysis workflow."
  {:image-paths        image-paths
   :image-uris         image-uris
   :image-uri          image-uri
   :query              query
   :per-image-finding  per-image-finding
   :per-image-findings per-image-findings
   :answer             answer})
