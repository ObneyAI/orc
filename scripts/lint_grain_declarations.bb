#!/usr/bin/env bb

(ns lint-grain-declarations
  "Require one canonical file per Grain primitive kind in each service component.
   Inspect source without evaluation. Test fixtures are exempt.
   Usage: bb scripts/lint_grain_declarations.bb [directory-or-file ...]"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(load-file (str (fs/path (fs/parent *file*) "lint_service_components.bb")))

(def primitive-files
  {"defevent" "events.clj"
   "defcommand" "commands.clj"
   "defquery" "queries.clj"
   "defreadmodel" "read_models.clj"
   "defschemas" "schemas.clj"
   "defprocessor" "todo_processors.clj"
   "defeventmodel" "event_model.clj"
   "defperiodic" "periodic_tasks.clj"})

(def default-roots ["components" "bases" "projects" "development" "scripts"])

(defn canonical-file [component filename]
  (str "components/" component "/src/ai/obney/orc/"
       (lint-service-components/interface-directory component) "/"
       (if (= filename "schemas.clj") "interface/" "core/") filename))

(defn relative-path [file]
  (str/replace (str (fs/relativize (fs/cwd) (fs/normalize (fs/absolutize file)))) "\\" "/"))

(defn exempt? [file]
  (let [path (relative-path file)]
    (or (re-find #"^(?:(?:components|bases|projects)/[^/]+/test/|scripts/tests/|development/test/|test(?:-heavy)?/)" path)
        (re-find #"(^|/)(target|node_modules|\.git)/" path))))

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
                   (contains? #{'quote 'clojure.core/quote}
                              (value (z/down ancestor))))) true
          (contains? #{:unquote :unquote-splicing} tag) (recur (z/up ancestor) (inc unquotes))
          (= :syntax-quote tag) (if (pos? unquotes) (recur (z/up ancestor) (dec unquotes)) true)
          :else (recur (z/up ancestor) unquotes))))))

(defn- renamed-primitives [root]
  (into {}
        (for [ns-form (nodes root)
              :when (and (= :list (z/tag ns-form)) (not (data? ns-form))
                         (= 'ns (value (z/down ns-form))))
              loc (nodes ns-form)
              :when (= :vector (z/tag loc))
              :let [[provider & options] (value loc)]
              :when (and (symbol? provider) (str/starts-with? (str provider) "ai.obney.grain."))
              :let [renames (:rename (apply hash-map options))]
              [original renamed] renames
              :when (and (symbol? original) (symbol? renamed)
                         (contains? primitive-files (name original)))]
          [renamed (name original)])))

(defn- declaration [loc renames]
  (when (and (= :list (z/tag loc)) (not (data? loc)))
    (let [head (value (z/down loc))]
      (when (symbol? head)
        (or (get renames head)
            (when (contains? primitive-files (name head)) (name head)))))))

(defn- finding [file loc message]
  (let [[row col] (when loc (z/position loc))]
    {:file file :row (or row 1) :col (or col 1) :message message}))

(defn lint-source [file source]
  (if (exempt? file) []
      (try
        (binding [*read-eval* false]
          (let [path (relative-path file)
                root (z/of-string source {:track-position? true})
                renames (renamed-primitives root)
                [_ component] (re-find #"^components/([^/]+)/src/" path)
                service? (contains? lint-service-components/allowed-services component)]
            (vec
             (for [loc (nodes root)
                   :let [primitive (declaration loc renames)]
                   :when primitive
                   :let [expected (get primitive-files primitive)]
                   :when (or (not service?) (not= path (canonical-file component expected)))]
               (finding path loc
                        (str primitive " must be declared in " expected
                             " in the service's canonical "
                             (if (= expected "schemas.clj") "interface/" "core/") " file"
                             (when-not service? "; declarations outside service components are forbidden")))))))
        (catch Exception error
          [(finding (relative-path file) nil (str "Cannot lint source: " (ex-message error)))]))))

(defn file-location-findings [files]
  (for [file files
        :let [path (relative-path file)
              [_ component] (re-find #"^components/([^/]+)/src/" path)
              basename (str/replace (str (fs/file-name path)) #"\.(cljc|cljs)$" ".clj")]
        :when (and (contains? lint-service-components/allowed-services component)
                   (contains? (set (vals primitive-files)) basename)
                   (not= path (canonical-file component basename)))]
    (finding path nil (str "Only one " basename " is allowed per service; use "
                           (canonical-file component basename) ". Empty duplicate files are forbidden too."))))

(defn missing-schema-findings [components-root]
  (for [component-dir (if (contains? lint-service-components/allowed-services
                                    (str (fs/file-name components-root)))
                       [(fs/path components-root)]
                       (fs/list-dir components-root))
        :let [component (str (fs/file-name component-dir))
              relative (str "src/ai/obney/orc/" (lint-service-components/interface-directory component)
                            "/interface/schemas.clj")]
        :when (and (fs/directory? component-dir)
                   (contains? lint-service-components/allowed-services component)
                   (not (fs/regular-file? (fs/path component-dir relative))))]
    (finding (str (fs/path component-dir relative)) nil
             "Every service requires its single canonical interface/schemas.clj")))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root)
                   (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root)
                   (fs/glob root "**.{clj,cljc,cljs,bb}")
                   [(fs/path root)])))
       (map str)
       (remove exempt?)
       distinct sort))

(defn -main [& args]
  (try
    (let [roots (if (seq args) args default-roots)
          files (source-files roots)
          findings (concat (mapcat missing-schema-findings
                                   (filter #(and (fs/directory? %)
                                                 (or (= "components" (str (fs/file-name %)))
                                                     (contains? lint-service-components/allowed-services
                                                                (str (fs/file-name %))))) roots))
                           (file-location-findings files)
                           (mapcat #(lint-source % (slurp %)) files))]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d Grain declaration placement violation(s)." (count findings))) 1)
        (do (println (format "OK: Grain declarations use service-owned canonical files (%d source files checked)."
                             (count files))) 0)))
    (catch Exception error
      (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
