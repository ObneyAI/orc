#!/usr/bin/env bb

(ns lint-view-files
  "Require one canonical views.clj per service with presentation code.
   Usage: bb scripts/lint_view_files.bb [directory-or-file ...]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(load-file (str (fs/path (fs/parent *file*) "lint_logic_files.bb")))

(defn source-area [path]
  (lint-logic-files/source-area path))

(defn canonical-file [service]
  (str "components/" service "/src/ai/obney/orc/"
       (lint-service-components/interface-directory service) "/views.clj"))

(defn canonical-namespace [service]
  (symbol (str "ai.obney.orc." (lint-service-components/interface-name service) ".views")))

(defn view-name? [name]
  (boolean (re-find #"(^|[/._-])views?($|[/._-])" (str name))))

(defn namespace-declarations [forms]
  (letfn [(children [form]
            (when-not (and (seq? form)
                           (contains? '#{quote clojure.core/quote cljs.core/quote
                                         comment clojure.core/comment cljs.core/comment}
                                      (first form)))
              (lint-logic-files/children form)))]
    (for [form (mapcat #(tree-seq (constantly true) children %) forms)
          :when (and (seq? form) (contains? '#{ns clojure.core/ns cljs.core/ns} (first form)))]
      form)))

(defn lint-source [file source]
  (if-let [service (source-area file)]
    (try
      (let [declarations (namespace-declarations (lint-logic-files/read-forms source))
            canonical (canonical-file service)
            expected-ns (canonical-namespace service)
            view-declarations (filter #(view-name? (second %)) declarations)
            view-path? (view-name? (last (str/split file #"/src/")))
            report (fn [row message] {:file file :row (or row 1) :col 1 :message message})]
        (cond
          (= file canonical)
          (if (and (= 1 (count declarations)) (= expected-ns (second (first declarations))))
            []
            [(report 1 (str "Service view file must declare exactly one namespace: " expected-ns))])

          (or view-path? (seq view-declarations))
          [(report (:line (meta (first view-declarations)))
                   (str "Service views must live in " canonical
                        "; consolidate page functions and presentation helpers into this single file"))]

          :else []))
      (catch Exception error
        [{:file file :row 1 :col 1 :message (str "Cannot lint source: " (ex-message error))}]))
    []))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root)
                   (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root)
                   (fs/glob root "**.{clj,cljc,cljs,bb}")
                   [(fs/path root)])))
       (map #(str (fs/relativize (fs/absolutize ".") (fs/normalize (fs/absolutize %)))))
       (filter source-area)
       distinct sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args ["components"]))
          findings (mapcat #(lint-source % (slurp %)) files)]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d service view placement violation(s)." (count findings))) 1)
        (do (println "OK: service presentation code uses one canonical views.clj per service.") 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
