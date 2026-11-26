#!/usr/bin/env bb

(ns lint-grain-logic
  "Reject executable Grain runtime references in service logic files without evaluating source."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(load-file (str (fs/path (fs/parent *file*) "lint_service_components.bb")))

(def default-roots ["components"])

(defn runtime-namespace? [provider]
  (boolean (re-find #"^ai\.obney\.grain\.(?:command-processor|query-processor|read-model-processor|event-store|todo-processor)(?:-[^.]+)?(?:\.|$)" (str provider))))

(defn forbidden? [sym]
  (and (symbol? sym) (runtime-namespace? (namespace sym))
       (not= sym 'ai.obney.grain.event-store-v3.interface/->event)))

(defn- nodes [root]
  (take-while (complement z/end?) (iterate z/next root)))

(defn- value [loc]
  (when loc (try (z/sexpr loc) (catch Exception _ nil))))

(defn- siblings [loc]
  (take-while some? (iterate z/right loc)))

(defn- children [loc]
  (siblings (z/down loc)))

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

(defn- libspecs [form prefix]
  (cond
    (symbol? form) [[(symbol (str prefix form))]]
    (and (sequential? form) (symbol? (first form)))
    (if (and (next form) (every? sequential? (rest form)))
      (mapcat #(libspecs % (str prefix (first form) ".")) (rest form))
      [(into [(symbol (str prefix (first form)))] (rest form))])
    :else []))

(defn- declaration-items
  "Visit each ns clause or library spec, including every reader conditional branch."
  [loc]
  (let [reader-op (when (= :reader-macro (z/tag loc)) (z/string (z/down loc)))]
    (cond
      (= :uneval (z/tag loc)) []
      (#{"?" "?@"} reader-op)
      (let [branches (take-nth 2 (rest (children (z/right (z/down loc)))))]
        (mapcat (fn [branch]
                  (if (= "?@" reader-op)
                    (mapcat declaration-items (children branch))
                    (declaration-items branch)))
                branches))
      :else [loc])))

(defn- libspec-variants
  "Expand conditional options within one libspec without combining unrelated requires."
  [loc]
  (letfn [(variants [form]
            (cond
              (reader-conditional? form)
              (mapcat (fn [branch]
                        (if (:splicing? form) (items branch) (variants branch)))
                      (take-nth 2 (rest (:form form))))
              (sequential? form)
              (map (fn [xs] [(if (vector? form) (vec xs) (apply list xs))]) (items form))
              (map? form)
              (map (fn [xs] [(into {} (map vec (partition 2 xs)))]) (items (mapcat identity form)))
              :else [[form]]))
          (items [forms]
            (reduce (fn [prefixes form]
                      (let [options (variants form)]
                        (for [prefix prefixes option options] (into prefix option))))
                    [[]] forms))]
    (let [spec (binding [*read-eval* false *data-readers* {} *default-data-reader-fn* tagged-literal]
                 (read-string {:read-cond :preserve} (z/string loc)))]
      (mapcat #(libspecs (first %) "") (variants spec)))))

(defn namespace-info [root]
  (let [all (remove data? (nodes root))
        specs (for [loc all
                    :when (and (= :list (z/tag loc)) (= 'ns (value (z/down loc))))
                    clause (mapcat declaration-items (drop 2 (children loc)))
                    :when (and (= :list (z/tag clause))
                               (contains? #{:require :require-macros :use :use-macros}
                                          (value (z/down clause))))
                    item (mapcat declaration-items (rest (children clause)))
                    spec (libspec-variants item)]
                [(value (z/down clause)) spec])
        own (set (keep #(when (and (= :list (z/tag %))
                                  (contains? #{'defn 'defn- 'def 'defonce 'defmulti} (value (z/down %))))
                         (value (z/right (z/down %)))) all))]
    (reduce (fn [info [clause [provider & options]]]
              (let [{:keys [as as-alias refer refer-macros rename only]} (apply hash-map options)
                    use? (contains? #{:use :use-macros} clause)
                    wildcard? (or (= :all refer) (and use? (nil? only)))
                    referred (mapcat identity (filter sequential? [refer refer-macros only]))]
                (-> info
                    (update :aliases #(cond-> % (or as as-alias) (update (or as as-alias) (fnil conj #{}) provider)))
                    (update :wildcards #(cond-> % (and wildcard? (runtime-namespace? provider)) (conj provider)))
                    (update :refers #(merge-with into %
                                                 (into {} (for [s referred]
                                                            [(get rename s s) #{(symbol (str provider) (str s))}])))))))
            {:aliases {} :refers {} :own own :wildcards #{}} specs)))

(defn resolved-symbols [info sym]
  (when (symbol? sym)
    (if-let [qualifier (namespace sym)]
      (set (map #(symbol (str %) (name sym)) (get (:aliases info) (symbol qualifier) #{qualifier})))
      (when-not (contains? (:own info) sym) (get (:refers info) sym)))))

(defn- binding-names [form]
  (cond
    (symbol? form) (if (= '& form) #{} #{(symbol (name form))})
    (vector? form) (into #{} (mapcat binding-names) form)
    (map? form) (into #{} (mapcat (fn [[k v]]
                                  (cond
                                    (and (keyword? k) (contains? #{"keys" "syms" "strs"} (name k))) (map #(symbol (name %)) v)
                                    (= :as k) (binding-names v)
                                    (keyword? k) []
                                    :else (binding-names k)))) form)
    :else #{}))

(defn- before? [a b]
  (neg? (compare (z/position a) (z/position b))))

(defn- vector-bindings [bindings loc]
  (loop [pairs (partition-all 2 (children bindings)) out #{}]
    (if-let [[binding init] (first pairs)]
      (if (some #(= (z/position binding) (z/position %)) (take-while some? (iterate z/up loc)))
        (into out (binding-names (value binding)))
        (if (before? binding loc)
        (cond
          (= :let (value binding)) (recur (rest pairs) (into out (vector-bindings init loc)))
          (keyword? (value binding)) (recur (rest pairs) out)
          (and init (or (not (before? init loc))
                        (some #(= (z/position init) (z/position %)) (take-while some? (iterate z/up loc))))) out
          :else (recur (rest pairs) (into out (binding-names (value binding)))))
        out))
      out)))

(defn- shadowed? [loc sym]
  (when (and (symbol? sym) (nil? (namespace sym))
             (not (some #(or (= :var (z/tag %))
                             (and (= :list (z/tag %)) (contains? #{'var 'clojure.core/var} (value (z/down %)))))
                        (take-while some? (iterate z/up loc)))))
    (some
     (fn [ancestor]
       (when (= :list (z/tag ancestor))
         (let [parts (vec (children ancestor)) head (value (first parts))
               op (when (symbol? head) (name head))
               binding (second parts)
               argvec (first (filter #(= :vector (z/tag %)) parts))
               parent-head (value (some-> ancestor z/up z/down))
               letfn-method? (and (= :vector (some-> ancestor z/up z/tag))
                                   (contains? #{'letfn 'clojure.core/letfn}
                                              (value (some-> ancestor z/up z/up z/down))))]
           (cond
             (contains? #{"if-let" "if-some"} op)
             (and (not (some #(= (some-> (nth parts 3 nil) z/position) (z/position %))
                             (take-while some? (iterate z/up loc))))
                  (contains? (vector-bindings binding loc) sym))
             (contains? #{"let" "let*" "loop" "loop*" "binding" "with-open" "with-local-vars" "for" "doseq" "dotimes" "when-let" "when-some" "when-first"} op)
             (contains? (vector-bindings binding loc) sym)
             (= "as->" op)
             (and (= sym (value (nth parts 2 nil)))
                  (not (before? loc (nth parts 2))))
             (= "letfn" op)
             (contains? (set (keep #(value (z/down %)) (children binding))) sym)
             (or letfn-method? (contains? #{"fn" "fn*" "defn" "defn-" "defmacro" "defmethod"} op)
                 (and (= :vector (some-> parts first z/tag))
                      (or (contains? #{'fn 'fn* 'defn 'defn- 'defmacro} parent-head)
                          (= :vector (some-> ancestor z/up z/tag)))))
             (or (contains? (binding-names (value argvec)) sym)
                 (and (contains? #{"fn" "fn*"} op) (= sym (value binding))))
             (= "catch" op) (= sym (value (nth parts 2 nil)))
             :else false))))
     (take-while some? (iterate z/up loc)))))

(defn runtime-references [file source]
  (try
    (binding [*read-eval* false]
      (let [root (z/of-string source {:track-position? true}) info (namespace-info root)]
        (when (some #(= :eval (z/tag %)) (remove data? (nodes root)))
          (throw (ex-info "Reader evaluation is not supported" {})))
        (when (seq (:wildcards info))
          (throw (ex-info "Use explicit refers for Grain runtime namespaces" {})))
        (vec (for [loc (nodes root)
                   :when (and (= :token (z/tag loc)) (not (data? loc)) (not (in-ns? loc)))
                   :let [sym (value loc)]
                   :when (not (shadowed? loc sym))
                   target (filter forbidden? (resolved-symbols info sym))
                   :let [[row col] (z/position loc)]]
               {:file file :row row :col col :symbol target
                :message (str "Grain runtime reference " target " is banned in logic files; move orchestration to its canonical Grain handler file")}))))
    (catch Exception error
      [{:file file :row 1 :col 1 :kind :parse-error :message (str "Cannot lint source: " (ex-message error))}])))

(defn logic-file? [file]
  (lint-service-components/service-component?
   (second (re-find #"(?:^|/)components/([^/]+)/src/(?:.*/)?logic\.(?:clj|cljc|cljs)$" (str file)))))

(defn lint-source [file source]
  (if (logic-file? file) (runtime-references file source) []))

(defn source-files [roots]
  (->> roots
       (mapcat (fn [root]
                 (when-not (fs/exists? root) (throw (ex-info "Lint input does not exist" {:path root})))
                 (if (fs/directory? root) (fs/glob root "**.{clj,cljc,cljs}") [(fs/path root)])))
       (map str) (filter logic-file?) distinct sort))

(defn -main [& args]
  (try
    (let [files (source-files (if (seq args) args default-roots))
          findings (mapcat #(lint-source % (slurp %)) files)]
      (doseq [{:keys [file row col message]} findings]
        (println (format "%s:%s:%s: error: %s" file row col message)))
      (if (seq findings)
        (do (println (format "FAIL: %d Grain logic boundary violation(s)." (count findings))) 1)
        (do (println "OK: service logic files have no executable Grain runtime references.") 0)))
    (catch Exception error (println "FAIL:" (ex-message error) (ex-data error)) 1)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
