(ns ai.obney.orc.integrations.interface
  "Bindings exported by the optional ORC packages on an application's classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private providers
  {:agent-browser 'ai.obney.orc.agent-browser.interface
   :colbert 'ai.obney.orc.colbert.interface
   :evaluation 'ai.obney.orc.evaluation.interface
   :gepa 'ai.obney.orc.gepa.interface
   :ontology 'ai.obney.orc.ontology.interface
   :orc-service 'ai.obney.orc.orc-service.interface})

(defonce ^:private bindings (atom {}))

(defn register-capabilities!
  "Publish named operations as Vars so REPL reloads and dynamic bindings apply."
  [provider operations]
  (swap! bindings assoc-in [provider :operations] operations)
  nil)

(defn register-code-functions!
  "Bind qualified names from persisted sheets to their current implementation Vars."
  [provider functions]
  (swap! bindings assoc-in [provider :functions] functions)
  nil)

(defn load-provider!
  "Load an installed ORC interface, including its declarations and bindings."
  [provider]
  (when-let [interface (get providers provider)]
    (let [stem (-> (str interface) (str/replace "." "/") (str/replace "-" "_"))]
      (when (some #(io/resource (str stem %)) [".clj" ".cljc" "__init.class"])
        (require interface)
        true))))

(defn capability
  "Return the Var for an optional operation, loading its provider on first use."
  [provider operation]
  (or (get-in @bindings [provider :operations operation])
      (when (load-provider! provider)
        (get-in @bindings [provider :operations operation]))))

(defn code-function
  "Resolve a historical ORC function name through its owning package."
  [qualified-symbol]
  (when-let [provider (some->> (namespace qualified-symbol)
                               (re-matches #"ai\.obney\.orc\.([^.]+)(?:\..*)?")
                               second keyword)]
    (or (get-in @bindings [provider :functions qualified-symbol])
        (when (load-provider! provider)
          (get-in @bindings [provider :functions qualified-symbol])))))
