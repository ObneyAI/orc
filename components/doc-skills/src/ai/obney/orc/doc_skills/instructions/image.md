# Image skill (file → base64 data URI)

For tasks that pass image files (PNG/JPG/WEBP/GIF/BMP) by local path, use
the `image/` namespace to load them as `data:` URIs you can pass to a
sub-LM as a vision input. The SCI sandbox does not allow direct Java
interop, so this is the only path that works.

## Loading

    (image/load-data-uri "/path/to/photo.png")
    ;; => "data:image/png;base64,iVBORw0KGgo…"

`load-data-uri` recognizes png, jpg/jpeg, webp, gif, bmp. Anything else
becomes `application/octet-stream` (still loadable, but no vision model
will accept it).

## Inspecting first

If you want to check what's available without loading the bytes:

    (image/file-info "/path/to/photo.png")
    ;; => {:path "/abs/path" :name "photo.png" :size-bytes 510990 :mime "image/png"}

## Pattern: parallel per-image extraction

When the workflow gives you a vector of image paths, fan out with
`predict-all`:

    (let [uris    (mapv image/load-data-uri image-paths)
          observations
          (predict-all
            {:name "observe-image"
             :items uris
             :as :image
             :inputs {:query query}
             :instructions "Return a concise grounded observation of THIS
                            image relevant to the query."
             :schema :string
             :max-concurrency 4})]
      …)

Then synthesize the per-image observations locally or with one
`(predict {…})` call.
