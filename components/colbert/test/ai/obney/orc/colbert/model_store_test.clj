(ns ai.obney.orc.colbert.model-store-test
  "Slice 1, cycle 5: model-store resolution behavior.

     - `-Dcolbert.model.path` override WINS (and never fetches)
     - the injected fake fetch runs once per artifact, then the cache serves
     - size verification failure throws a precise ex-info

   The fetch capability is ALWAYS a fake here — no test touches the network."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.model-store :as model-store]))

(defn- with-model-path-property
  "Run thunk with -Dcolbert.model.path set to `value` (nil => cleared),
   restoring the previous value afterwards."
  [value thunk]
  (let [previous (System/getProperty model-store/model-path-property)]
    (try
      (if value
        (System/setProperty model-store/model-path-property value)
        (System/clearProperty model-store/model-path-property))
      (thunk)
      (finally
        (if previous
          (System/setProperty model-store/model-path-property previous)
          (System/clearProperty model-store/model-path-property))))))

(defn- forbidden-fetch
  "A fetch capability that fails the test if it is ever invoked — no test may
   touch the network."
  [url dest]
  (throw (ex-info "Test attempted a network fetch — forbidden in tests"
                  {:url url :dest (str dest)})))

(defn- temp-dir ^java.io.File [label]
  (.toFile (java.nio.file.Files/createTempDirectory
            (str "colbert-model-store-test-" label)
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-recursively! child)))
  (.delete f))

(defn- copying-fake-fetch
  "A fake fetch capability that 'downloads' by copying the artifact from the
   resolved local model dir. Counts invocations in `calls`."
  [calls]
  (let [local (io/file (support/model-dir))]
    (fn [url ^java.io.File dest]
      (swap! calls conj url)
      (io/make-parents dest)
      (io/copy (io/file local (.getName dest)) dest))))

(deftest override-property-wins-and-never-fetches
  (with-model-path-property (support/model-dir)
    (fn []
      (let [empty-cache (temp-dir "unused-cache")
            dir (model-store/resolve-model-dir {:fetch-fn forbidden-fetch
                                                :cache-dir empty-cache})]
        (is (= (.getCanonicalFile (io/file (support/model-dir)))
               (.getCanonicalFile dir))
            "resolution returns the override directory")
        (is (empty? (seq (.listFiles empty-cache)))
            "the cache is never touched when the override wins")))))

(deftest override-missing-artifacts-throws-precise-ex-info
  (with-model-path-property (str (temp-dir "incomplete-override"))
    (fn []
      (let [ex (try (model-store/resolve-model-dir {:fetch-fn forbidden-fetch})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "an incomplete override dir throws")
        (is (= :colbert-model-override-incomplete (:error (ex-data ex))))
        (is (= model-store/required-files (:missing (ex-data ex)))
            "every required artifact is named missing")))))

(deftest fake-fetch-called-once-per-artifact-then-cached
  (with-model-path-property nil
    (fn []
      (let [cache (temp-dir "cache")
            calls (atom [])
            fetch (copying-fake-fetch calls)]
        (try
          (let [first-dir (model-store/resolve-model-dir {:fetch-fn fetch :cache-dir cache})]
            (is (= (.getCanonicalFile cache) (.getCanonicalFile first-dir))
                "resolution materializes into the injected cache dir")
            (is (= (count model-store/required-files) (count @calls))
                "one fetch per required artifact")
            (is (= (set (map #(str model-store/hf-base-url "/" %) model-store/required-files))
                   (set @calls))
                "each artifact is fetched from its CDN url")
            (testing "second resolve serves from the cache — fetch NOT called again"
              (let [second-dir (model-store/resolve-model-dir {:fetch-fn fetch :cache-dir cache})]
                (is (= (.getCanonicalFile cache) (.getCanonicalFile second-dir)))
                (is (= (count model-store/required-files) (count @calls))
                    "call count unchanged"))))
          (finally
            (delete-recursively! cache)))))))

(deftest size-verification-failure-throws-precise-ex-info
  (with-model-path-property nil
    (fn []
      (let [cache (temp-dir "bad-cache")
            truncating-fetch (fn [_url ^java.io.File dest]
                               (io/make-parents dest)
                               (spit dest "truncated"))
            ex (try (model-store/resolve-model-dir {:fetch-fn truncating-fetch
                                                    :cache-dir cache})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a wrong-size download throws")
        (let [data (ex-data ex)]
          (is (= :colbert-model-artifact-size-mismatch (:error data)))
          (is (contains? model-store/artifact-sizes (:file data)))
          (is (= (get model-store/artifact-sizes (:file data)) (:expected-bytes data)))
          (is (= 9 (:actual-bytes data)) "the actual byte count is reported"))))))
