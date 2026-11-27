#!/usr/bin/env bb

(ns lint-logic-files
  (:require [babashka.fs :as fs] [clojure.string :as str])
  (:import [clojure.lang LineNumberingPushbackReader] [java.io StringReader]))

(load-file (str (fs/path (fs/parent *file*) "lint_grain_logic.bb")))

(defn- char-literal-end
  "Exclusive end index of character literal starting at backslash idx."
  [^String s idx]
  (let [n (.length s)
        i (inc idx)]
    (if (>= i n)
      n
      (let [c (.charAt s i)]
        (cond
          (= c \u) (min n (+ i 5))
          (= c \o)
          (loop [j (inc i) k 0]
            (if (and (< j n) (< k 3)
                     (let [d (int (.charAt s j))]
                       (and (>= d (int \0)) (<= d (int \7)))))
              (recur (inc j) (inc k))
              j))
          (Character/isLetter c)
          (loop [j (inc i)]
            (if (and (< j n) (Character/isLetter (.charAt s j)))
              (recur (inc j))
              j))
          :else (inc i))))))

(defn- neutralize-for-read
  "Rewrite ::alias/kw and ::kw outside strings/regex/comments/chars so
  Read placement forms without loading namespace aliases."
  [^String text]
  (let [n (.length text)
        sb (StringBuilder. n)]
    (loop [i 0 state :code]
      (if (>= i n)
        (str sb)
        (let [c (.charAt text i)]
          (case state
            :comment
            (do (.append sb c)
                (recur (inc i) (if (= c \newline) :code :comment)))

            :string
            (do (.append sb c)
                (cond
                  (= c \\) (recur (inc i) :string-esc)
                  (= c \") (recur (inc i) :code)
                  :else (recur (inc i) :string)))

            :string-esc
            (do (.append sb c)
                (recur (inc i) :string))

            :regex
            (do (.append sb c)
                (cond
                  (= c \\) (recur (inc i) :regex-esc)
                  (= c \") (recur (inc i) :code)
                  :else (recur (inc i) :regex)))

            :regex-esc
            (do (.append sb c)
                (recur (inc i) :regex))

            (cond
              (= c \\)
              (let [ni (char-literal-end text i)]
                (.append sb (.substring text i ni))
                (recur ni :code))

              (= c \")
              (do (.append sb c) (recur (inc i) :string))

              (= c \;)
              (do (.append sb c) (recur (inc i) :comment))

              (= c \#)
              (cond
                (>= (inc i) n)
                (do (.append sb c) (recur (inc i) :code))

                (= (.charAt text (inc i)) \")
                (do (.append sb \#) (.append sb \") (recur (+ i 2) :regex))

                (and (= (.charAt text (inc i)) \:)
                     (< (+ i 2) n)
                     (= (.charAt text (+ i 2)) \:))
                (do (.append sb \#) (.append sb \:)
                    (when (and (< (+ i 3) n) (= (.charAt text (+ i 3)) \{))
                      (.append sb "logic-lint"))
                    (recur (+ i 3) :code))

                :else
                (do (.append sb c) (recur (inc i) :code)))

              (and (= c \:)
                   (< (inc i) n)
                   (= (.charAt text (inc i)) \:))
              (do
                (.append sb \:)
                (recur (+ i 2) :code))

              :else
              (do (.append sb c) (recur (inc i) :code)))))))))

(def core-ops '#{defn defn- def defonce fn fn* quote var comment declare apply})
(def function-factories '#{partial comp juxt memoize constantly complement fnil})

(defn read-forms [text]
  (with-open [r (LineNumberingPushbackReader. (StringReader. (neutralize-for-read text)))]
    (binding [*read-eval* false *data-readers* {} *default-data-reader-fn* tagged-literal]
      (loop [out []]
        (let [form (read {:eof ::eof :read-cond :preserve} r)]
          (if (= ::eof form) out (recur (conj out form))))))))

(defn children [form]
  (cond
    (reader-conditional? form) (take-nth 2 (rest (:form form)))
    (map? form) (mapcat identity form)
    (coll? form) (seq form)
    :else nil))

(defn libspecs [form prefix]
  (cond
    (reader-conditional? form) (mapcat #(libspecs % prefix) (children form))
    (symbol? form) [[(symbol (str prefix form))]]
    (and (sequential? form) (symbol? (first form)))
    (if (and (next form) (every? sequential? (rest form)))
      (mapcat #(libspecs % (str prefix (first form) ".")) (rest form))
      [(into [(symbol (str prefix (first form)))] (rest form))])
    :else []))

(defn ns-info [forms]
  (let [namespace-children (fn [form]
                             (when-not (and (seq? form)
                                            (contains? '#{quote clojure.core/quote cljs.core/quote
                                                          comment clojure.core/comment cljs.core/comment}
                                                       (first form)))
                               (children form)))
        nodes (mapcat #(tree-seq (constantly true) namespace-children %) forms)
        declaration (first (filter #(and (seq? %) (= 'ns (first %))) nodes))
        clauses (filter #(and (seq? %) (contains? #{:require :require-macros :refer-clojure} (first %)))
                        (tree-seq (constantly true) children declaration))
        specs (mapcat #(mapcat (fn [spec] (libspecs spec "")) (rest %))
                      (remove #(= :refer-clojure (first %)) clauses))]
    (reduce (fn [info spec]
              (let [lib (first spec) opts (apply hash-map (rest spec))
                    referred (if (sequential? (:refer opts)) (:refer opts) [])]
                (-> info
                    (update :aliases #(cond-> % (:as opts) (update (:as opts) (fnil conj #{}) lib)
                                              (:as-alias opts) (update (:as-alias opts) (fnil conj #{}) lib)))
                    (update :refers #(merge-with into %
                                                 (into {} (map (fn [s] [(get (:rename opts) s s) #{(symbol (str lib) (str s))}]) referred)))))))
            {:namespace (or (second declaration) 'user) :aliases {}
             :refers (into {} (for [clause clauses :when (= :refer-clojure (first clause))
                                    [original renamed] (:rename (apply hash-map (rest clause)))]
                                [renamed #{(symbol "clojure.core" (name original))}]))}
            specs)))

(defn resolve-symbols [info sym]
  (when (symbol? sym)
    (if-let [qualifier (namespace sym)]
      (set (map #(symbol (str %) (name sym)) (get (:aliases info) (symbol qualifier) #{qualifier})))
      (or (get (:refers info) sym)
          (when (or (contains? core-ops sym) (contains? function-factories sym))
            #{(symbol "clojure.core" (name sym))})
          #{(symbol (str (:namespace info)) (name sym))}))))

(defn core-op? [info form op]
  (some #(and (contains? #{"clojure.core" "cljs.core"} (namespace %)) (= (name op) (name %)))
        (resolve-symbols info (when (seq? form) (first form)))))

(defn code-forms [info form]
  (if (or (core-op? info form 'quote)
          (core-op? info form 'comment))
    []
    (cons form (mapcat #(code-forms info %) (children form)))))

(defn declaration-forms [info form]
  (if (core-op? info form 'quote)
    []
    (cons form (mapcat #(declaration-forms info %) (children form)))))

(defn declared-function? [info form]
  (or (core-op? info form 'defn) (core-op? info form 'defn-)))

(defn var-symbol [info form]
  (when (symbol? (second form)) (symbol (str (:namespace info)) (name (second form)))))

(defn function-value? [info known value]
  (or (and (symbol? value) (some known (resolve-symbols info value)))
      (core-op? info value 'fn) (core-op? info value 'fn*)
      (and (core-op? info value 'var) (some known (resolve-symbols info (second value))))
      (some #(core-op? info value %) function-factories)
      (and (reader-conditional? value)
           (some #(function-value? info known %) (children value)))))

(defn function-def? [info known form]
  (or (declared-function? info form)
      (and (or (core-op? info form 'def) (core-op? info form 'defonce))
           (function-value? info known (if (and (string? (nth form 2 nil)) (> (count form) 3))
                                         (nth form 3) (nth form 2 nil))))))

(def default-roots ["components"])

(defn relative-path [file]
  (str/replace (str (fs/relativize (fs/cwd) (fs/normalize (fs/absolutize file)))) "\\" "/"))

(defn source-area [path]
  (let [component (second (re-matches #"components/([^/]+)/src/.+" path))]
    (when (lint-service-components/service-component? component) component)))

(defn canonical-file [component]
  (str "components/" component "/src/ai/obney/orc/" (lint-service-components/interface-directory component) "/core/logic.clj"))

(defn service-relative [path]
  (when-let [component (source-area path)]
    (let [prefix (str "components/" component "/src/ai/obney/orc/" (lint-service-components/interface-directory component) "/")]
      (when (str/starts-with? path prefix) (subs path (count prefix))))))

(defn view-file? [path]
  (= "views.clj" (service-relative path)))

(defn interface-file? [path]
  (boolean (re-matches #"interface(?:\.(?:clj|cljc|cljs)|/.+)" (or (service-relative path) ""))))

(defn orchestration-file? [path]
  (contains? #{"core/commands.clj" "core/queries.clj" "core/read_models.clj"
               "core/todo_processors.clj" "core/periodic_tasks.clj"}
             (service-relative path)))

(defn grain-bound-vars [parsed]
  (let [dependencies
        (into {} (for [{:keys [info forms]} parsed form forms
                       :when (some #(core-op? info form %) '[defn defn- def defonce defmulti])]
                   [(var-symbol info form)
                    (into #{} (mapcat #(resolve-symbols info %))
                          (filter symbol? (mapcat #(code-forms info %) (drop 2 form))))]))]
    (loop [bound #{}]
      (let [more (into bound (for [[v deps] dependencies
                                  :when (some #(or (lint-grain-logic/forbidden? %) (contains? bound %)) deps)] v))]
        (if (= bound more) bound (recur more))))))

(defn- forwarded-call? [info call]
  (when (seq? call)
    (let [apply? (core-op? info call 'apply)
          target (if apply? (second call) (first call))
          args (if apply? (drop 2 call) (rest call))]
      (and (symbol? target) (or (namespace target) (get (:refers info) target)) (every? symbol? args)
           (some #(not (contains? #{"clojure.core" "cljs.core"} (namespace %)))
                 (resolve-symbols info target))))))

(defn forwarding-form? [info form]
  (if (declared-function? info form)
    (let [tail (drop 2 form)
          tail (if (string? (first tail)) (rest tail) tail)
          tail (if (map? (first tail)) (rest tail) tail)
          arities (if (vector? (first tail)) [tail] tail)]
      (and (seq arities)
           (every? (fn [arity]
                     (and (sequential? arity) (vector? (first arity))
                          (= 2 (count arity)) (forwarded-call? info (second arity)))) arities)))
    (let [value (if (string? (nth form 2 nil)) (nth form 3 nil) (nth form 2 nil))]
      (or (symbol? value) (core-op? info value 'var)))))

(defn inspect-sources [sources]
  (let [parsed (mapv (fn [[path text]]
                       (try
                         (let [forms (read-forms text) info (ns-info forms)]
                           {:path path :area (source-area path) :info info
                            :forms (mapcat #(code-forms info %) forms)})
                         (catch Throwable error
                           {:path path :area (source-area path) :error (ex-message error)}))) sources)
        grain-bound (grain-bound-vars parsed)
        known (loop [known (set (for [{:keys [info forms]} parsed form forms
                                      :when (declared-function? info form)] (var-symbol info form)))]
                (let [more (into known (for [{:keys [info forms]} parsed form forms
                                             :when (function-def? info known form)] (var-symbol info form)))]
                  (if (= known more) known (recur more))))]
    (vec
     (mapcat
      (fn [{:keys [path area info forms error]}]
        (when area
          (cond
            error [{:file path :row 1 :col 1 :kind :parse-error :message (str "Cannot lint source: " error)}]
            (and (re-find #"(?:^|/)logic\.(?:clj|cljc|cljs)$" path) (not= path (canonical-file area)))
            [{:file path :row 1 :col 1 :kind :duplicate-logic :message (str "Only one logic.clj is allowed per service; use " (canonical-file area))}]
            (or (= path (canonical-file area)) (view-file? path)) []
            :else
            (for [form forms
                  :when (and (function-def? info known form)
                             (not (and (orchestration-file? path) (contains? grain-bound (var-symbol info form))))
                             (not (and (interface-file? path) (forwarding-form? info form))))]
              {:file path :row (or (:line (meta form)) 1) :col (or (:column (meta form)) 1)
               :kind :function-home :name (str (second form))
               :message (str "Backend function " (second form) " must live in " (canonical-file area))})))) parsed))))

(defn lint-source [file source]
  (inspect-sources {(relative-path file) source}))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root) (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root) (fs/glob root "**.{clj,cljc,cljs}") [(fs/path root)])))
       (map relative-path)
       (remove #(re-find #"(?:^|/)(?:target|node_modules|\.git)/|^(?:components|bases)/[^/]+/tests?/" %))
       distinct sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args default-roots))
          findings (inspect-sources (into (sorted-map) (map (fn [file] [file (slurp file)]) files)))]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d backend logic placement violation(s)." (count findings))) 1)
        (do (println "OK: backend helpers use one core/logic.clj per service; views and public forwards are exempt.") 0)))
    (catch Exception error (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
