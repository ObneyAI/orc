#!/usr/bin/env bb

(ns lint-read-model-versions
  "Require version 1 in ORC read-model declarations and query subscriptions.
   Scan production Clojure source without loading the application. Tests may
   deliberately exercise other versions; Grain dependencies are outside scope.

   Usage: bb scripts/lint_read_model_versions.bb [directory-or-file ...]"
  (:require [babashka.fs :as fs]
            [rewrite-clj.zip :as z]))

(def default-roots ["components" "bases" "development" "scripts"])

(defn- children [loc]
  (take-while some? (iterate z/right (z/down loc))))

(defn- value [loc]
  (when loc
    (try (z/sexpr loc) (catch Exception _ ::expression))))

(defn- call-named? [loc names]
  (and (= :list (z/tag loc))
       (let [head (value (z/down loc))]
         (and (symbol? head) (contains? names (name head))))))

(defn- inactive? [loc]
  (some #(or (= :uneval (z/tag %)) (call-named? % #{"comment"}))
        (take-while some? (iterate z/up loc))))

(defn- finding [file loc message]
  (let [[row col] (when loc (z/position loc))]
    {:file file :row row :col col :message message}))

(defn- check-version [file loc label]
  (when-not (= 1 (value loc))
    (finding file loc (str label " must use literal version 1, found " (z/string loc)))))

(defn- map-entries [loc]
  (partition 2 (children loc)))

(defn- check-options [file loc]
  (for [[key version] (map-entries loc)
        :when (= :version (value key))
        :let [hit (check-version file version "Read-model :version")]
        :when hit]
    hit))

(defn- check-subscriptions [file loc]
  (cond
    (= :map (z/tag loc))
    (keep (fn [[model version]]
            (check-version file version (str "Read-model dependency " (z/string model))))
          (map-entries loc))

    (and (call-named? loc #{"query-models"}) (= 2 (count (children loc)))) []

    :else
    [(finding file loc ":grain/read-models must be a literal version-1 map or a query-models call")]))

(defn lint-source [file source]
  (try
    (vec
     (mapcat
      (fn [loc]
        (when-not (inactive? loc)
          (cond
            (call-named? loc #{"defreadmodel" "register-read-model!"})
            (let [options (nth (children loc) 3 nil)]
              (cond
                (= :map (some-> options z/tag)) (check-options file options)
                (call-named? loc #{"register-read-model!"})
                [(finding file loc "Read-model registration options must be a literal map with version 1")]))

            (= :map (z/tag loc))
            (mapcat (fn [[key subscriptions]]
                      (when (= :grain/read-models (value key))
                        (check-subscriptions file subscriptions)))
                    (map-entries loc)))))
      (take-while (complement z/end?)
                  (iterate z/next (z/of-string source {:track-position? true})))))
    (catch Exception error
      [(finding file nil (str "Cannot lint source: " (ex-message error)))])))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root)
                   (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root)
                   (fs/glob root "**.{clj,cljc,cljs,bb}")
                   [(fs/path root)])))
       (map str)
       (remove #(re-find #"(^|/)(test|tests|target|node_modules)/" %))
       distinct
       sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args default-roots))
          findings (mapcat #(lint-source % (slurp %)) files)]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file (or row 1) (or col 1) message)))
      (if (seq findings)
        (do (println (format "FAIL: %d read-model version violation(s)." (count findings))) 1)
        (do (println (format "OK: read-model versions are 1 (%d source files checked)." (count files))) 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error))
      1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
