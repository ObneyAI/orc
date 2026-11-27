#!/usr/bin/env bb

(ns lint-no-declare-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(load-file "scripts/lint_no_declare.bb")
(alias 'lint 'lint-no-declare)

(deftest direct-qualified-and-higher-order-references-are-banned
  (doseq [form ["(declare forward)"
                "(clojure.core/declare forward)"
                "(apply declare ['forward])"
                "(def resolver clojure.core/declare)"
                "#'clojure.core/declare"
                "(let [lookup declare] (lookup 'forward))"]]
    (is (= 1 (count (lint/lint-source "source.clj" form))) form)))

(deftest core-aliases-and-renamed-imports-are-banned
  (doseq [source ["(ns example (:require [clojure.core :as c])) (c/declare forward)"
                  "(ns example (:require [clojure.core :as-alias c])) (c/declare forward)"
                  "(ns example (:require (clojure [core :as c]))) (c/declare forward)"
                  "(require '[clojure.core :as c]) (c/declare forward)"
                  "(alias 'c 'clojure.core) (c/declare forward)"
                  "(ns example (:require [clojure.core :refer [declare] :rename {declare lazy!}])) (lazy! forward)"
                  "(ns example (:refer-clojure :rename {declare lazy!})) (apply lazy! ['forward])"]]
    (is (= 1 (count (lint/lint-source "source.clj" source))))))

(deftest test-and-gallery-code-have-no-exemption
  (doseq [file ["components/orc-service/test/example.clj"
                "components/ui/src/ai/obney/orc/ui/gallery/queries.clj"
                "development/src/probe.clj" "localstack-init/probe.clj" "scripts/probe.bb" "bases/orc-dev/src/probe.clj" "projects/web-api/src/probe.clj"]]
    (is (= 1 (count (lint/lint-source file "(declare forward)"))))))

(deftest source-is-inspected-across-reader-branches-and-nested-forms
  (is (= 2 (count (lint/lint-source "source.cljc"
                                   "#?(:clj (declare forward) :cljs (clojure.core/declare other))")))))

(deftest alternate-imports-and-conditional-aliases-cannot-bypass-the-ban
  (doseq [source ["(ns example (:use [clojure.core :only [declare] :rename {declare ahead}])) (ahead forward)"
                  "(use '[clojure.core :only [declare] :rename {declare ahead}]) (ahead forward)"
                  "(refer 'clojure.core :only '[declare] :rename '{declare ahead}) (ahead forward)"
                  "(clojure.core/refer 'clojure.core :rename '{declare ahead}) (ahead forward)"
                  "(ns example (:require #?(:clj [clojure.core :as c] :cljs [cljs.core :as c]))) #?(:clj (c/declare forward) :cljs nil)"]]
    (is (= 1 (count (lint/lint-source "source.cljc" source))))))

(deftest data-comments-and-strings-are-not-executable-references
  (is (empty? (lint/lint-source "source.clj"
                               "; declare\n\"declare\"\n'(declare forward)\n(quote declare)\n#_(declare forward)\n`(declare forward)\n{:declare :example/declare}")))
  (is (= 1 (count (lint/lint-source "source.clj" "`(data ~(declare forward))")))))

(deftest comment-form-does-not-hide-code
  (is (= 1 (count (lint/lint-source "source.clj" "(comment (declare forward))")))))

(deftest dependency-order-and-local-recursion-remain-allowed
  (is (empty? (lint/lint-source "source.clj"
                               "(ns example (:require [example.read :as read])) (read/get-record {}) #'read/get-record")))
  (is (empty? (lint/lint-source "source.clj" "(ns example (:refer-clojure :exclude [declare]))"))))

(deftest reports-exact-locations-and-malformed-source
  (is (= [[2 3]] (mapv (juxt :row :col)
                       (lint/lint-source "source.clj" "(do\n  declare)"))))
  (is (re-find #"Cannot lint source" (:message (first (lint/lint-source "broken.clj" "(unterminated"))))))

(deftest inspection-cannot-evaluate-source
  (let [dir (fs/create-temp-dir {:prefix "orc-declare-"}) target (str (fs/path dir "not-created"))]
    (try
      (lint/lint-source "source.clj" (str "#=(spit " (pr-str target) " \"bad\")"))
      (is (not (fs/exists? target)))
      (finally (fs/delete-tree dir)))))

(deftest missing-input-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo (lint/source-files ["/nonexistent-orc-declare-input"]))))


(deftest cljs-qualified-and-aliased-declarations-are-banned
  (doseq [source ["(cljs.core/declare forward)"
                  "(ns example (:require [cljs.core :as c])) (c/declare forward)"
                  "(ns example (:require [cljs.core :refer [declare] :rename {declare ahead}])) (ahead forward)"]]
    (is (= 1 (count (lint/lint-source "source.cljs" source))))))

(deftest plain-declarations-report-once-and-valid-recursion-is-allowed
  (is (= 1 (count (lint/lint-source "source.clj" "(declare first-fn second-fn)"))))
  (is (empty? (lint/lint-source "source.clj"
                               "(defn f [n] (if (zero? n) 0 (f (dec n))))
(letfn [(a [n] (when (pos? n) (b (dec n)))) (b [n] (a n))] (a 1))"))))

(let [{:keys [fail error]} (clojure.test/run-tests 'lint-no-declare-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
