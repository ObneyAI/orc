#!/usr/bin/env bb

(ns lint-explicit-dependencies
  "Ban executable requiring-resolve references without evaluating source.
   Includes application, test, development and tooling code.
   Usage: bb scripts/lint_explicit_dependencies.bb [directory-or-file ...]"
  (:require [babashka.fs :as fs]
            [rewrite-clj.zip :as z]))

(def default-roots (cond-> ["components" "bases" "projects" "development" "scripts" "localstack-init"]
    (fs/directory? "qa") (conj "qa")))

(defn- nodes [loc]
  (take-while (complement z/end?) (iterate z/next loc)))

(defn- value [loc]
  (when loc (try (z/sexpr loc) (catch Exception _ nil))))

(defn- data? [loc]
  (loop [ancestor loc unquotes 0]
    (when ancestor
      (let [tag (z/tag ancestor)]
        (cond
          (or (contains? #{:quote :uneval} tag)
              (and (= :list tag)
                   (contains? #{'quote 'clojure.core/quote} (value (z/down ancestor))))) true
          (contains? #{:unquote :unquote-splicing} tag) (recur (z/up ancestor) (inc unquotes))
          (= :syntax-quote tag) (if (pos? unquotes) (recur (z/up ancestor) (dec unquotes)) true)
          :else (recur (z/up ancestor) unquotes))))))

(defn- in-ns? [loc]
  (some #(and (= :list (z/tag %)) (= 'ns (value (z/down %))))
        (take-while some? (iterate z/up loc))))

(defn- unquoted [form]
  (if (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form)))
    (second form) form))

(defn- core-references [[provider & options]]
  (when (= 'clojure.core provider)
    (let [{:keys [as as-alias rename]} (apply hash-map options)]
      (cond-> []
        (or as as-alias) (conj (symbol (str (or as as-alias)) "requiring-resolve"))
        (get rename 'requiring-resolve) (conj (get rename 'requiring-resolve))))))

(defn- libspec-references [form prefix]
  (cond
    (and (sequential? form) (symbol? (first form)))
    (let [[provider & options] form
          provider (if prefix (symbol (str prefix "." provider)) provider)]
      (if (sequential? (first options))
        (mapcat #(libspec-references % provider) options)
        (core-references (cons provider options))))
    :else []))

(defn- references [root]
  (into #{'requiring-resolve 'clojure.core/requiring-resolve}
        (mapcat
         (fn [loc]
           (when (= :list (z/tag loc))
             (let [[head & options] (value loc)]
               (cond
                 (and (in-ns? loc) (= :require head))
                 (mapcat #(libspec-references % nil) options)
                 (and (in-ns? loc) (= :refer-clojure head))
                 (when-let [renamed (get-in (apply hash-map options) [:rename 'requiring-resolve])]
                   [renamed])
                 (contains? #{'require 'clojure.core/require} head)
                 (mapcat #(libspec-references (unquoted %) nil) options)
                 (and (contains? #{'alias 'clojure.core/alias} head)
                      (= 'clojure.core (unquoted (second options))))
                 [(symbol (str (unquoted (first options))) "requiring-resolve")]))))
         (remove data? (nodes root)))))

(defn lint-source [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string source {:track-position? true})
            banned (references root)]
        (vec
         (for [loc (nodes root)
               :when (and (= :token (z/tag loc))
                          (contains? banned (value loc))
                          (not (data? loc)) (not (in-ns? loc)))
               :let [[row col] (z/position loc)]]
           {:file file :row row :col col
            :message "requiring-resolve is banned; use explicit namespace dependencies and direct calls or Var references"}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root)
                   (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root)
                   (fs/glob root "**.{clj,cljc,cljs,bb}")
                   [(fs/path root)])))
       (map str)
       (remove #(re-find #"(^|/)(target|node_modules|\.git)/" %))
       distinct sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args default-roots))
          findings (mapcat #(lint-source % (slurp %)) files)]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d requiring-resolve reference(s)." (count findings))) 1)
        (do (println (format "OK: explicit dependencies only; no requiring-resolve (%d source files checked)."
                             (count files))) 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
