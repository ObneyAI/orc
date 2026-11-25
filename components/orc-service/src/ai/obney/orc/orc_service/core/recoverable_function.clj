(ns ai.obney.orc.orc-service.core.recoverable-function
  "Versioned source for self-contained generated functions. Stores no runtime
   capabilities; invocation receives those from the current code executor."
  (:require [clojure.edn :as edn]
            [sci.core :as sci]
            [ai.obney.orc.orc-service.core.sci-sandbox :as sandbox]))

(defn source-sha256 [source]
  (format "%064x" (java.math.BigInteger. 1
                   (.digest (java.security.MessageDigest/getInstance "SHA-256")
                            (.getBytes ^String source "UTF-8")))))

(defn compile-binding
  "Verify and compile one exact function form in a fresh bounded SCI context.
   Captured bindings are intentionally unsupported in version 1."
  [{:keys [version context-version source sha256 bindings]}]
  (when-not (and (= 1 version) (= 1 context-version)
                 (string? source) (= sha256 (source-sha256 source)) (= {} bindings))
    (throw (ex-info "Invalid recoverable generated function binding"
                    {:reason :invalid-recoverable-function-binding})))
  (let [form (try
               (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
                 (let [form (edn/read {:eof ::eof} reader)]
                   (when-not (= ::eof (edn/read {:eof ::eof} reader))
                     (throw (ex-info "Recoverable source contains trailing forms" {})))
                   form))
               (catch Exception e
                 (throw (ex-info "Recoverable source must contain exactly one EDN function form"
                                 {:reason :invalid-recoverable-function-source} e))))]
    (when-not (and (seq? form) (#{'fn 'fn*} (first form)))
      (throw (ex-info "Recoverable source must be one function form"
                      {:reason :invalid-recoverable-function-source})))
    (try
      (let [f (sci/eval-form (sandbox/build-sci-context {}) form)]
        (when-not (fn? f)
          (throw (ex-info "Recoverable source did not compile to a function" {})))
        (with-meta f (assoc (meta f) :orc/recoverable-fn
                            {:version version :context-version context-version
                             :source source :sha256 sha256 :bindings bindings})))
      (catch Exception e
        (throw (ex-info "Generated function requires unsupported compilation bindings"
                        {:reason :unsupported-recoverable-function-bindings} e))))))

(defn prepare-binding [form]
  (let [source (pr-str form)
        binding {:version 1 :context-version 1 :source source
                 :sha256 (source-sha256 source) :bindings {}}]
    (compile-binding binding)
    binding))

(defn- resolution-symbol [resolved]
  (when (and (:name (meta resolved)) (:ns (meta resolved)))
    (sci/var->symbol resolved)))

(defn- resolution-value [resolved]
  (if (instance? clojure.lang.IDeref resolved) @resolved resolved))

(defn- same-resolution? [actual canonical]
  (and (= (resolution-symbol actual) (resolution-symbol canonical))
       (= (:macro (meta actual)) (:macro (meta canonical)))
       (let [a (resolution-value actual) b (resolution-value canonical)]
         (if (or (fn? a) (fn? b)) (identical? a b) (= a b)))))

(defn prepare-function
  "Accept only functions independent of captured or shadowed producer globals.
   SCI checks denied globals after lexical lookup, preserving real fn/let locals.
   The accepted function uses the same versioned context as later restoration."
  [form producer-context]
  (let [binding (prepare-binding form)
        canonical-context (sandbox/build-sci-context {})
        differing (into #{}
                        (mapcat (fn [sym]
                                  (let [actual (sci/resolve producer-context sym)
                                        canonical (sci/resolve canonical-context sym)]
                                    (when-not (same-resolution? actual canonical)
                                      (remove nil? [sym (resolution-symbol actual)
                                                       (resolution-symbol canonical)])))))
                        (filter symbol? (tree-seq coll? seq form)))]
    (when (seq differing)
      (try
        ;; SCI applies deny only after lexical lookup. Compile the one form
        ;; with differing global names denied; do not evaluate the producer or
        ;; its function body. Initializing deny here also avoids SCI 0.8's
        ;; merge-opts nil-permission collection behavior.
        (sci/eval-form (sandbox/build-sci-context {:deny differing}) form)
        (catch Exception e
          (throw (ex-info "Generated function uses captured or shadowed compilation bindings"
                          {:reason :unsupported-recoverable-function-context} e)))))
    (compile-binding binding)))
