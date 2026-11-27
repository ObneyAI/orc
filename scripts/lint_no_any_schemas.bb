#!/usr/bin/env bb

(ns lint-no-any-schemas
  "Check registered schema fields for :any. ORC has no grandfathered schema slots."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def default-roots ["components" "bases"])
(def schemas-file-re #"(^|/)interface/(?:[^/]+/)*(?:[^/]+_)?schemas\.clj$")

;; --- Form-aware detection ---

(defn- any-token?
  "True when `zloc` is the exact keyword `:any` — not `:any-checked?` or any
   other keyword that merely starts with \"any\"."
  [zloc]
  (and (= :token (z/tag zloc))
       (= :any (z/sexpr zloc))))

(defn- node-seq [src]
  (->> (z/of-string src {:track-position? true})
       (iterate z/next)
       (take-while (complement z/end?))))

(defn- field-name
  "The field name a matched `:any` types — the leading keyword of its enclosing
   `[field-name ... :any]` vector, or nil if the shape doesn't match."
  [any-zloc]
  (let [parent (z/up any-zloc)]
    (when (and parent (= :vector (z/tag parent)))
      (let [head (z/down parent)]
        (when (and head (= :token (z/tag head)) (keyword? (z/sexpr head)))
          (z/sexpr head))))))

(defn- defschemas-map-entries
  "For a `(defschemas name {...})` top-level form zloc, return
   [schema-key value-zloc] pairs from its map literal."
  [form-zloc]
  (let [map-zloc (some-> form-zloc z/down z/right z/right)]
    (when (and map-zloc (= :map (z/tag map-zloc)))
      (loop [k (z/down map-zloc) acc []]
        (if (nil? k)
          acc
          (let [v (z/right k)]
            (recur (some-> v z/right)
                   (conj acc [(z/sexpr k) v]))))))))

(defn- defschemas-form? [zloc]
  (and (= :list (z/tag zloc))
       (let [head (z/down zloc)
             op (when (= :token (some-> head z/tag)) (z/sexpr head))]
         (and (symbol? op) (= "defschemas" (name op))))))

(defn- any-hits-in-file [file src]
  (->> (node-seq src)
       (filter defschemas-form?)
       (mapcat defschemas-map-entries)
       (mapcat (fn [[schema-key value-zloc]]
                 (for [zloc (node-seq (z/string value-zloc))
                       :when (any-token? zloc)]
                   {:file file :schema-key schema-key :field (field-name zloc)})))))

;; --- File walking ---

(defn- schemas-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (let [p (fs/path root)]
                   (cond
                     (not (fs/exists? p)) (throw (ex-info "Lint input does not exist" {:path root}))
                     (fs/directory? p)    (fs/glob p "**.clj")
                     :else                [p]))))
       (map str)
       (filter #(re-find schemas-file-re %))
       distinct
       sort))

(defn- relativize [file]
  (let [cwd (str (fs/cwd) "/")]
    (if (str/starts-with? file cwd) (subs file (count cwd)) file)))

(defn- scan [roots]
  (->> (schemas-files roots)
       (mapcat (fn [file] (any-hits-in-file (relativize file) (slurp file))))))

(defn -main [& args]
  (let [hits (scan (if (seq args) args default-roots))]
    (doseq [{:keys [file schema-key field]} hits]
      (println (format "%s: NEW :any in %s %s" file schema-key (or field "<schema>"))))
    (if (seq hits)
      (do (println (format "FAIL: %d :any schema field(s)." (count hits))) 1)
      (do (println "OK: no new :any schema fields.") 0))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
