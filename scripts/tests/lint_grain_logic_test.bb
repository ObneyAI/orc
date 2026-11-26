#!/usr/bin/env bb

(ns lint-grain-logic-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [babashka.fs :as fs]))

(load-file "scripts/lint_grain_logic.bb")

(def file "components/orc-service/src/ai/obney/orc/orc_service/core/logic.clj")
(def provider "ai.obney.grain.read-model-processor-v2.interface")
(defn inspect [source] (lint-grain-logic/lint-source file source))

(deftest runtime-surfaces-and-internals
  (doseq [provider ["command-processor-v2.interface" "query-processor.interface"
                    "read-model-processor-v2.interface" "read-model-processor-v2.core"
                    "event-store-v3.interface" "event-store-v3.interface.protocol"
                    "event-store-postgres-v3.core" "event-store-sqlite-v3.interface"
                    "event-store-v2.interface" "todo-processor-v2.interface"]]
    (is (= 1 (count (inspect (str "(ai.obney.grain." provider "/operation {})")))))))

(deftest resolution-and-function-values
  (doseq [source [(str "(" provider "/value ctx :model id)")
                  (str "(ns example (:require [" provider " :as r])) (r/value ctx :model id)")
                  (str "(ns example (:require [" provider " :as-alias r])) #'r/value")
                  (str "(ns example (:require [" provider " :refer [value] :rename {value fetch}])) (fetch ctx)")
                  (str "(ns example (:require [" provider " :refer [value]])) (partial value ctx)")
                  (str "(ns example (:require [" provider " :as r])) (def lookup r/value)")
                  (str "(ns example (:require [ai.obney.grain [read-model-processor-v2.interface :as r]])) (r/value ctx)")]]
    (is (= 1 (count (inspect source))) source)))

(deftest prefix-lists-and-wildcard-imports
  (is (= 1 (count (inspect "(ns example (:require (ai.obney.grain [read-model-processor-v2.interface :as r]))) (r/value ctx)"))))
  (is (re-find #"explicit refers" (:message (first (inspect (str "(ns example (:require [" provider " :refer :all])) (value ctx)")))))))

(deftest java-imports-and-dependency-declarations
  (let [imports "(:import [java.util UUID] (java.nio ByteBuffer ByteOrder) [java.io File Reader Writer])"
        clean (str "(ns example " imports " (:require [clojure.set :refer [union difference]])) "
                   "(UUID/randomUUID) (union #{} #{})")]
    (is (empty? (inspect clean)))
    (doseq [clause [(str "(:require [" provider " :as r])")
                    (str "#?(:clj (:require [" provider " :as r]) :cljs (:require [safe :as r]))")
                    (str "(:require #?@(:clj [[" provider " :as r]] :cljs [[safe :as r]]))")
                    "(:require (ai.obney.grain #?(:clj [read-model-processor-v2.interface :as r] :cljs [other :as r])))"
                    (str "(:require [" provider " #?@(:clj [:as r] :cljs [:as other])])")
                    (str "(:require [" provider " :as #?(:clj r :cljs other)])")]]
      (let [hits (inspect (str "(ns example " imports " " clause ") (r/value ctx)"))]
        (is (= [(symbol provider "value")] (mapv :symbol hits)) clause)))))

(deftest qualified-and-selective-namespace-imports
  (doseq [clause [(str "(:use [" provider " :only [value] :rename {value fetch}])")
                  (str "(:require [" provider " :refer-macros [value] :rename {value fetch}])")
                  (str "(:require-macros [" provider " :refer [value] :rename {value fetch}])")]]
    (is (= [(symbol provider "value")]
           (mapv :symbol (inspect (str "(ns example " clause ") (fetch ctx)")))) clause))
  (is (re-find #"explicit refers" (:message (first (inspect (str "(ns example (:use " provider "))")))))))

(deftest constructor-data-and-unrelated-effects
  (doseq [source ["(ns example (:require [ai.obney.grain.event-store-v3.interface :as es])) (es/->event {:type :sample/created})"
                  "(random-uuid) (swap! state inc) (slurp path) (time/now)"
                  "'(rmp/value ctx) \"rmp/value\" #_(rmp/value ctx) :rmp/value"
                  "(ns example (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp])) `(rmp/value ctx)"]]
    (is (empty? (inspect source)) source))
  (is (= 1 (count (inspect (str "`(data ~(" provider "/value ctx))"))))))

(deftest local-shadowing-and-binding-initializers
  (let [prefix (str "(ns example (:require [" provider " :refer [value]])) ")]
    (doseq [form ["(fn [value] (value 1))"
                  "(defn f [{:keys [value]}] (value 1))"
                  "(let [value identity] (value 1))"
                  "(letfn [(value [x] x)] (value 1))"
                  "(letfn [(f [value] (value 1))] (f identity))"
                  "(as-> identity value (value 1))"
                  "(defn f ([value] (value 1)) ([value x] (value x)))"]]
      (is (empty? (inspect (str prefix form))) form))
    (is (= 1 (count (inspect (str prefix "(let [value (value ctx)] (value 1))")))))
    (is (= 1 (count (inspect (str prefix "(if-let [value input] (value 1) (value ctx))")))))
    (is (= 1 (count (inspect (str prefix "(fn [value] #'value)")))))))

(deftest all-reader-branches-and-source-locations
  (is (= 2 (count (inspect (str "#?(:clj (" provider "/value x) :cljs (" provider "/values x))")))))
  (is (= 1 (count (inspect (str "(ns example (:require #?(:clj [" provider " :as r] :cljs [other :as r]))) (r/value x)")))))
  (is (= [[2 3]] (mapv (juxt :row :col) (inspect (str "(do\n  " provider "/value)"))))))

(deftest service-logic-scope
  (doseq [path ["components/orc-service/src/nested/logic.cljc"
                "components/future-service-v2/src/example/core/logic.clj"]]
    (is (= 1 (count (lint-grain-logic/lint-source path (str "(" provider "/value x)"))))))
  (doseq [path ["components/orc-service/src/example/core/queries.clj"
                "components/orc-service/test/example/logic.clj"
                "components/crypto/src/example/logic.clj" "development/src/logic.clj"]]
    (is (empty? (lint-grain-logic/lint-source path (str "(" provider "/value x)"))))))

(deftest fail-closed-without-evaluation
  (is (= :parse-error (:kind (first (inspect "(broken")))))
  (is (thrown? clojure.lang.ExceptionInfo (lint-grain-logic/source-files ["/nonexistent-grain-logic-input"])))
  (let [target (str (fs/path (fs/temp-dir) (str "grain-no-eval-" (random-uuid))))]
    (inspect (str "#=(spit " (pr-str target) " \"bad\")"))
    (is (not (fs/exists? target)))))

(let [{:keys [fail error]} (run-tests 'lint-grain-logic-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
