(ns ai.obney.orc.colbert.core.model-store
  "Resolves the on-disk encoder checkpoint directory for the pure-JVM ColBERT
   signal (answerdotai/answerai-colbert-small-v1, fp32 model.onnx — ADR 0002).

   Resolution order:

     1. `-Dcolbert.model.path` — a directory containing model.onnx +
        tokenizer.json + the config files. Skips the network entirely
        (air-gapped / test override).
     2. The local cache dir `~/.cache/orc/colbert/answerai-colbert-small-v1/`
        when it already holds every artifact at the expected byte size.
     3. Download the missing artifacts from the HuggingFace CDN into the cache,
        each verified by expected byte size.

   The FETCH is an injected capability (`:fetch-fn`, default = real HTTP
   download; tests inject a fake) — no test ever touches the network."
  (:require [clojure.java.io :as io]))

(def checkpoint
  "The encoder checkpoint (HF repo id)."
  "answerdotai/answerai-colbert-small-v1")

(def model-id
  "Local cache directory name for the checkpoint."
  "answerai-colbert-small-v1")

(def hf-base-url
  (str "https://huggingface.co/" checkpoint "/resolve/main"))

(def artifact-sizes
  "file name -> expected byte size. Recorded from the live HF repo
   (https://huggingface.co/api/models/answerdotai/answerai-colbert-small-v1/tree/main,
   read 2026-07-08). fp32 model.onnx is the production default (P-0: int8 drift
   flipped a near-tie ranking)."
  {"model.onnx"              133298178
   "tokenizer.json"          711396
   "config.json"             702
   "tokenizer_config.json"   1242
   "special_tokens_map.json" 695
   "artifact.metadata"       2219
   "onnx_config.json"        767})

(def required-files (vec (sort (keys artifact-sizes))))

(def model-path-property "colbert.model.path")

(defn default-cache-dir
  ^java.io.File []
  (io/file (System/getProperty "user.home") ".cache" "orc" "colbert" model-id))

(defn http-fetch!
  "The REAL fetch capability: stream `url` into `dest` (a File). Downloads to a
   sibling temp file first, then atomically moves into place, so a crashed
   download never leaves a plausible-but-truncated artifact at the final path."
  [url ^java.io.File dest]
  (io/make-parents dest)
  (let [tmp (io/file (.getParentFile dest) (str (.getName dest) ".part"))]
    (try
      (with-open [in (io/input-stream (java.net.URL. ^String url))]
        (io/copy in tmp))
      (java.nio.file.Files/move
       (.toPath tmp) (.toPath dest)
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (when (.exists tmp) (.delete tmp))))))

(defn- file-size ^long [^java.io.File f]
  (if (.exists f) (.length f) -1))

(defn- verify-size!
  "Throw a precise ex-info when `f` does not match its expected byte size."
  [^java.io.File f file-name context]
  (let [expected (long (get artifact-sizes file-name))
        actual (file-size f)]
    (when (not= expected actual)
      (throw (ex-info (str "ColBERT model artifact failed size verification: "
                           file-name " expected " expected " bytes, got "
                           (if (neg? actual) "MISSING" actual))
                      {:error :colbert-model-artifact-size-mismatch
                       :file file-name
                       :path (.getAbsolutePath f)
                       :expected-bytes expected
                       :actual-bytes (when-not (neg? actual) actual)
                       :context context})))))

(defn- override-dir
  "The -Dcolbert.model.path directory, verified to contain every required
   artifact (existence only — an override dir is the operator's authority;
   byte sizes are enforced on the DOWNLOAD path, where truncation is the
   realistic failure)."
  ^java.io.File []
  (when-let [path (System/getProperty model-path-property)]
    (let [dir (io/file path)
          missing (vec (remove #(.exists (io/file dir %)) required-files))]
      (when (seq missing)
        (throw (ex-info (str "-D" model-path-property " points at " path
                             " but it is missing required model artifacts: " missing)
                        {:error :colbert-model-override-incomplete
                         :path (.getAbsolutePath dir)
                         :missing missing
                         :required required-files})))
      dir)))

(defn- cache-complete? [^java.io.File dir]
  (every? (fn [file-name]
            (= (long (get artifact-sizes file-name))
               (file-size (io/file dir file-name))))
          required-files))

(defn- populate-cache!
  "Fetch every missing/size-mismatched artifact into `dir` via `fetch-fn`,
   verifying each fetched file's byte size."
  [^java.io.File dir fetch-fn]
  (doseq [file-name required-files
          :let [dest (io/file dir file-name)]
          :when (not= (long (get artifact-sizes file-name)) (file-size dest))]
    (fetch-fn (str hf-base-url "/" file-name) dest)
    (verify-size! dest file-name :download))
  dir)

(defn resolve-model-dir
  "Resolve (and if necessary materialize) the encoder checkpoint directory.
   Returns a java.io.File. Options:

     :fetch-fn  - (fn [url dest-file]) capability used to download a missing
                  artifact. Defaults to the real HTTP download; tests MUST
                  inject a fake (no test touches the network).
     :cache-dir - cache directory override (default
                  ~/.cache/orc/colbert/answerai-colbert-small-v1/)."
  (^java.io.File [] (resolve-model-dir {}))
  (^java.io.File [{:keys [fetch-fn cache-dir]
                   :or {fetch-fn http-fetch!}}]
   (or (override-dir)
       (let [dir (io/file (or cache-dir (default-cache-dir)))]
         (if (cache-complete? dir)
           dir
           (populate-cache! dir fetch-fn))))))
