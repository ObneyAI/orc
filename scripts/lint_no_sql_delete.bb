#!/usr/bin/env bb

(ns lint-no-sql-delete
  "Reject literal SQL deletion statements without evaluating source.
   Usage: bb scripts/lint_no_sql_delete.bb [directory-or-file ...]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def default-roots (cond-> ["components" "bases" "projects" "development" "scripts" "localstack-init"]
    (fs/directory? "qa") (conj "qa")))
(def clojure-extensions #{"clj" "cljc" "cljs" "bb" "edn"})
(def source-extensions (into clojure-extensions #{"sql" "sh" "bash" "zsh" "py" "js" "jsx" "ts" "tsx"}))
(def deletion-pattern #"(?i)\bdelete\s+from\b")
(def failure-message "SQL DELETE statements are banned; preserve event history and use the owning service's commands")

(defn- literal-string [loc]
  (case (z/tag loc)
    (:token :multi-line) (let [v (z/sexpr loc)] (when (string? v) v))
    :list (let [head (z/down loc)]
            (when (and head (= :token (z/tag head))
                       (contains? #{'str 'clojure.core/str} (z/sexpr head)))
              (let [parts (mapv literal-string (take-while some? (iterate z/right (z/right head))))]
                (when (every? string? parts) (apply str parts)))))
    nil))

(defn- mask-sql-comments [^String text]
  (let [delimiters (re-matcher #"--|/\*|\*/|[\r\n]" text)
        chars (.toCharArray text)]
    (loop [start nil depth 0]
      (if (.find delimiters)
        (let [mark (.group delimiters)]
          (cond
            (and start (zero? depth))
            (if (#{"\r" "\n"} mark)
              (do (java.util.Arrays/fill chars (int start) (.start delimiters) \space)
                  (recur nil 0))
              (recur start 0))
            (= "/*" mark) (recur (or start (.start delimiters)) (inc depth))
            (and (pos? depth) (= "*/" mark))
            (if (= 1 depth)
              (do (java.util.Arrays/fill chars (int start) (.end delimiters) \space)
                  (recur nil 0))
              (recur start (dec depth)))
            (and (zero? depth) (= "--" mark)) (recur (.start delimiters) 0)
            :else (recur start depth)))
        (do (when start (java.util.Arrays/fill chars (int start) (alength chars) \space))
            (String. chars))))))

(defn- text-findings [file text row col]
  (let [matcher (re-matcher deletion-pattern (mask-sql-comments text))]
    (loop [findings []]
      (if (.find matcher)
        (let [prefix (subs text 0 (.start matcher))
              lines (str/split prefix #"\n" -1)
              line-offset (dec (count lines))]
          (recur (conj findings {:file file :row (+ row line-offset)
                                :col (+ (if (zero? line-offset) col 1) (count (last lines)))
                                :message failure-message})))
        findings))))

(defn lint-source [file source]
  (try
    (if (contains? clojure-extensions (fs/extension file))
      (binding [*read-eval* false]
        (let [root (z/of-string source {:track-position? true})]
          (loop [loc root findings []]
            (if (z/end? loc)
              findings
              (let [text (literal-string loc)
                    hits (when (and text (re-find #"(?i)delete" text))
                           (let [[row col] (z/position loc)]
                             (map #(assoc % :row row :col col)
                                  (text-findings file text row col))))]
                (recur (z/next loc)
                       (into findings hits)))))))
      (text-findings file source 1 1))
    (catch Exception error
      [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}])))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root)
                   (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root) (fs/glob root "**") [(fs/path root)])))
       (filter fs/regular-file?)
       (filter #(contains? source-extensions (fs/extension %)))
       (map str)
       (remove #(re-find #"(^|/)(target|node_modules|\.git|\.gitlibs|\.cpcache)/" %))
       distinct sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args default-roots))
          findings (mapcat #(lint-source % (slurp %)) files)]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d SQL deletion violation(s)." (count findings))) 1)
        (do (println (format "OK: no SQL DELETE statements (%d source files checked)." (count files))) 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
