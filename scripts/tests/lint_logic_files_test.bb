#!/usr/bin/env bb

(ns lint-logic-files-test
  (:require [clojure.test :refer [deftest is testing run-tests]]))

(load-file "scripts/lint_logic_files.bb")

(defn inspect [text]
  (lint-logic-files/inspect-sources {"components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj" text}))

(deftest ordinary-functions-and-reader-variants
  (doseq [form ["(defn f [] 1)" "(defn- f [] 1)"
                "(clojure.core/defn f [] 1)" "(cljs.core/defn f [] 1)"
                "(defn ^:private f [] 1)" "^:private (defn f [] 1)"
                "(defn\nf\n[]\n1)" "(do (defn f [] 1))"
                "(def f (fn [] 1))" "(def ^:private f #(+ % 1))"
                "(defonce f (fn [] 1))" "(clojure.core/defonce f #(+ % 1))"
                "(def f (partial + 1))" "(def f (comp inc inc))"]]
    (is (= 1 (count (inspect form))) form)))

(deftest static-core-aliases-and-renamed-refers
  (doseq [text ["(ns example (:require [clojure.core :as c])) (c/defn f [] 1)"
                "(ns example (:require [cljs.core :as c])) (c/defn- f [] 1)"
                "(ns example (:require [clojure.core :refer [defn] :rename {defn define}])) (define f [] 1)"
                "(ns example (:require [clojure.core :as c])) (c/def f (c/fn [] 1))"
                "(ns example (:refer-clojure :rename {defn define})) (define f [] 1)"
                "(ns example (:require [clojure [core :as c]])) (c/defn f [] 1)"]]
    (is (= 1 (count (inspect text))) text)))

(deftest every-conditional-branch-is-checked
  (is (= 3 (count (inspect "#?(:clj (defn a [] 1) :cljs (defn b [] 2) :default (defn c [] 3))"))))
  (is (= 2 (count (inspect "(do #?@(:clj [(defn a [] 1)] :cljs [(defn b [] 2)]))"))))
  (is (= 1 (count (inspect "(def f #?(:clj 1 :cljs (fn [] 2)))"))))
  (is (= 1 (count (inspect "(ns example (:require #?(:clj [clojure.core :as c] :cljs [cljs.core :as c]))) (c/defn f [] 1)")))))

(deftest conditional-aliases-retain-every-possible-target
  (is (= 1 (count (inspect "(ns example (:require #?(:clj [clojure.core :as c] :cljs [unrelated.library :as c]))) (c/defn f [] 1)"))))
  (is (= 1 (count (inspect "'(ns fake (:require [unrelated.library :as c])) (ns real (:require [clojure.core :as c])) (c/defn f [] 1)")))))

(deftest quoted-data-and-inline-functions-are-allowed
  (doseq [text ["'(defn f [] 1)" "(quote (defn f [] 1))" "`(defn f [] 1)"
                "(clojure.core/comment (defn f [] 1))" "(comment (defn f [] 1))"
                "\"(defn fake [] 1)\"" "#\"\\(defn fake\""
                "(map (fn [x] x) [])" "(map #(inc %) [])"
                "(letfn [(f [] 1)] (f))" "(def callbacks {:f (fn [] 1)})"
                "(defmethod example :x [_] 1)"
                "(defprotocol Example (method [x]))" "(defrecord Example [] ExampleProtocol (method [_] 1))"
                "(ns example (:require [ai.obney.grain.command-processor-v2.interface :refer [defcommand]])) (defcommand :example run {} [ctx] (letfn [(f [] ctx)] (f)))"
                "(def f ::unknown/value) (def g #::unknown{:key 1}) (def h #::{:key 2})"
                "(def text \"::unknown/value #::{:key} (defn x [])\")"]]
    (is (empty? (inspect text)) text)))

(deftest named-aliases-cannot-bypass-the-rule
  (let [findings (lint-logic-files/inspect-sources
                  {"components/orc-service/src/ai/obney/orc/orc_service/core/logic.clj" "(ns market.logic) (defn f [] 1)"
                   "components/orc-service/src/ai/obney/orc/orc_service/core/helpers.clj"
                   "(ns market.api (:require [market.logic :as l])) (def f l/f) (def g f) (def h #'l/f)"})]
    (is (= 3 (count findings)))
    (is (every? #(= :function-home (:kind %)) findings))))

(deftest exact-source-area-scope
  (is (empty? (lint-logic-files/inspect-sources
               {"components/orc-service/src/ai/obney/orc/orc_service/core/logic.clj" "(defn f [] 1)"
                "src/trading_system/runtime/core.clj" "(defn f [] 1)"
                "src/trading_system/mcp/core.clj" "(defn f [] 1)"
                "src/trading_system/ids.clj" "(defn f [] 1)"
                "test/trading_system/market_data/helpers.clj" "(defn f [] 1)"
                "dev/trading_system/market_data/probe.clj" "(defn f [] 1)"})))
  (doseq [path ["components/orc-service/src/ai/obney/orc/orc_service/nested/logic.clj"
                "components/orc-service/src/ai/obney/orc/orc_service/core/logic.cljc"
                "components/orc-service/src/ai/obney/orc/orc_service/core/helpers.cljs"
                "components/orc-service/src/rogue.clj"
                "components/orc-service/src/ai/obney/orc/rogue/views.clj"
                "components/orc-service/src/test/helpers.clj"]]
    (is (= 1 (count (lint-logic-files/inspect-sources {path "(defn f [] 1)"}))) path)))

(deftest only-canonical-view-files-are-exempt
  (doseq [file ["components/orc-service/src/ai/obney/orc/orc_service/views.clj"]]
    (is (empty? (lint-logic-files/inspect-sources {file "(defn render-page [] [:div])"}))))
  (doseq [file ["components/orc-service/src/ai/obney/orc/orc_service/views/detail.clj"
                "components/orc-service/src/ai/obney/orc/orc_service/views.cljc"
                "components/orc-service/src/ai/obney/orc/orc_service/core/views.clj"
                "components/ontology-service/src/ai/obney/orc/ontology/core/payment_plan_panels.clj"
                "components/gepa-service/src/ai/obney/orc/gepa/core/admin_views.clj"]]
    (is (= 1 (count (lint-logic-files/inspect-sources {file "(defn hidden-backend [] 1)"}))))))

(deftest interfaces-permit-forwards-but-not-backend-implementations
  (let [file "components/orc-service/src/ai/obney/orc/orc_service/interface.clj"]
    (doseq [source ["(ns api (:require [domain.logic :as l])) (defn f [x] (l/f x))"
                    "(ns api (:require [domain.logic :as l])) (defn f [& xs] (apply l/f xs))"
                    "(ns api (:require [domain.logic :as l])) (defn f ([x] (l/f x)) ([x y] (l/f x y)))"]]
      (is (empty? (lint-logic-files/inspect-sources {file source})) source))
    (doseq [source ["(defn f [x] (inc x))" "(defn f [x] (let [y (inc x)] y))"
                    "(ns api (:require [domain.logic :as l])) (defn f [x] (l/f (inc x)))"
                    "(def f (partial + 1))"]]
      (is (= 1 (count (lint-logic-files/inspect-sources {file source}))) source))
    (is (empty? (lint-logic-files/inspect-sources
                 {"components/orc-service/src/ai/obney/orc/orc_service/core/logic.clj" "(ns domain.logic) (defn f [x] x)"
                  file "(ns api (:require [domain.logic :as l])) (def f l/f) (def g #'l/f)"})))))

(deftest empty-duplicate-logic-files-are-rejected
  (is (= [:duplicate-logic]
         (mapv :kind (lint-logic-files/inspect-sources
                      {"components/orc-service/src/ai/obney/orc/orc_service/core/nested/logic.clj" ""})))))

(deftest future-service-components-are-covered-without-a-baseline
  (is (= 1 (count (lint-logic-files/inspect-sources
                   {"components/future-service/src/ai/obney/orc/future_service/core/helper.clj" "(defn f [] 1)"})))))

(deftest orchestration-helpers-follow-runtime-dependencies
  (let [prefix "components/orc-service/src/ai/obney/orc/orc_service/core/"
        sources {(str prefix "read_models.clj")
                 "(ns fixture.reads (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp])) (defn lookup [ctx] (rmp/value ctx :fixture/rows))"
                 (str prefix "commands.clj")
                 "(ns fixture.commands (:require [fixture.reads :as reads])) (defn execute [ctx] (reads/lookup ctx))"}]
    (is (empty? (lint-logic-files/inspect-sources sources)))
    (is (= 1 (count (lint-logic-files/inspect-sources
                     (assoc sources (str prefix "queries.clj") "(ns fixture.queries) (defn calculation [x] (inc x))")))))
    (is (= 1 (count (lint-logic-files/inspect-sources
                     (assoc sources (str prefix "effects.clj")
                            "(ns fixture.effects (:require [fixture.reads :as reads])) (defn hidden [ctx] (reads/lookup ctx))")))))))

(deftest versioned-service-placement-is-enforced
  (is (= 1 (count (lint-logic-files/inspect-sources
                   {"components/future-service-v2/src/ai/obney/orc/future_service_v2/core/helpers.clj"
                    "(defn misplaced [] 1)"})))))

(deftest parsing-fails-closed-without-evaluation
  (doseq [source ["(defn f [" "#=(throw (Exception. \"must not execute\"))"]]
    (is (= [:parse-error] (mapv :kind (inspect source)))))
  (is (= 3 (:row (first (inspect "\n\n(defn f [] 1)"))))))

(deftest missing-input-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo (lint-logic-files/source-files ["/nonexistent-orc-logic-input"]))))


(let [{:keys [fail error]} (run-tests 'lint-logic-files-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
