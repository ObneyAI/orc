#!/usr/bin/env bb

(ns lint-explicit-dependencies-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(load-file "scripts/lint_explicit_dependencies.bb")
(alias 'lint 'lint-explicit-dependencies)

(deftest direct-qualified-and-higher-order-references-are-banned
  (doseq [form ["(requiring-resolve 'example/run)"
                "(clojure.core/requiring-resolve 'example/run)"
                "(apply requiring-resolve ['example/run])"
                "(def resolver clojure.core/requiring-resolve)"
                "#'clojure.core/requiring-resolve"
                "(let [lookup requiring-resolve] (lookup 'example/run))"]]
    (is (= 1 (count (lint/lint-source "source.clj" form))) form)))

(deftest core-aliases-and-renamed-imports-are-banned
  (doseq [source ["(ns example (:require [clojure.core :as c])) (c/requiring-resolve 'a/b)"
                  "(ns example (:require [clojure.core :as-alias c])) (c/requiring-resolve 'a/b)"
                  "(ns example (:require (clojure [core :as c]))) (c/requiring-resolve 'a/b)"
                  "(require '[clojure.core :as c]) (c/requiring-resolve 'a/b)"
                  "(alias 'c 'clojure.core) (c/requiring-resolve 'a/b)"
                  "(ns example (:require [clojure.core :refer [requiring-resolve] :rename {requiring-resolve lazy!}])) (lazy! 'a/b)"
                  "(ns example (:refer-clojure :rename {requiring-resolve lazy!})) (apply lazy! ['a/b])"]]
    (is (= 1 (count (lint/lint-source "source.clj" source))))))

(deftest test-and-gallery-code-have-no-exemption
  (doseq [file ["components/orc-service/test/example.clj"
                "components/ui/src/ai/obney/orc/ui/gallery/queries.clj"
                "development/src/probe.clj" "localstack-init/probe.clj" "scripts/probe.bb" "bases/orc-dev/src/probe.clj"]]
    (is (= 1 (count (lint/lint-source file "(requiring-resolve 'a/b)"))))))

(deftest source-is-inspected-across-reader-branches-and-nested-forms
  (is (= 2 (count (lint/lint-source "source.cljc"
                                   "#?(:clj (requiring-resolve 'a/b) :cljs (clojure.core/requiring-resolve 'c/d))")))))

(deftest data-comments-and-strings-are-not-executable-references
  (is (empty? (lint/lint-source "source.clj"
                               "; requiring-resolve\n\"requiring-resolve\"\n'(requiring-resolve 'a/b)\n(quote requiring-resolve)\n#_(requiring-resolve 'a/b)\n`(requiring-resolve 'a/b)\n{:requiring-resolve :example/requiring-resolve}")))
  (is (= 1 (count (lint/lint-source "source.clj" "`(data ~(requiring-resolve 'a/b))")))))

(deftest comment-form-does-not-hide-code
  (is (= 1 (count (lint/lint-source "source.clj" "(comment (requiring-resolve 'a/b))")))))

(deftest explicit-imports-and-direct-vars-remain-allowed
  (is (empty? (lint/lint-source "source.clj"
                               "(ns example (:require [example.read :as read])) (read/get-record {}) #'read/get-record")))
  (is (empty? (lint/lint-source "source.clj" "(ns example (:refer-clojure :exclude [requiring-resolve]))"))))

(deftest reports-exact-locations-and-malformed-source
  (is (= [[2 3]] (mapv (juxt :row :col)
                       (lint/lint-source "source.clj" "(do\n  requiring-resolve)"))))
  (is (re-find #"Cannot lint source" (:message (first (lint/lint-source "broken.clj" "(unterminated"))))))

(deftest inspection-cannot-evaluate-source
  (let [dir (fs/create-temp-dir {:prefix "orc-explicit-deps-"}) target (str (fs/path dir "not-created"))]
    (try
      (lint/lint-source "source.clj" (str "#=(spit " (pr-str target) " \"bad\")"))
      (is (not (fs/exists? target)))
      (finally (fs/delete-tree dir)))))

(deftest missing-input-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo (lint/source-files ["/nonexistent-orc-explicit-dependency-input"]))))

(let [{:keys [fail error]} (clojure.test/run-tests 'lint-explicit-dependencies-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
