#!/usr/bin/env bb

(ns lint-no-sql-delete-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as process]))

(load-file "scripts/lint_no_sql_delete.bb")
(alias 'lint 'lint-no-sql-delete)

(def deletion-verb (str/upper-case "delete"))
(def deletion-sql (str deletion-verb " FROM grain.events WHERE id = ?"))

(deftest literal-statements-are-banned
  (doseq [sql [deletion-sql
               (str/lower-case deletion-sql)
               (str deletion-verb "\n\tFROM grain.events")
               (str deletion-verb "/* reviewed */FROM grain.events")
               (str deletion-verb "/* outer /* nested */ comment */FROM grain.events")
               (str deletion-verb " -- reviewed\n FROM grain.events")
               (str "WITH removed AS (" deletion-sql " RETURNING *) SELECT * FROM removed")]
          file ["source.clj" "source.cljc" "source.cljs" "scripts/probe.bb" "config.edn"]]
    (is (seq (lint/lint-source file (str "[" (pr-str sql) "]"))) [file sql])))

(deftest literal-concatenation-and-reader-branches-are-checked
  (is (seq (lint/lint-source "source.clj" (str "(str " (pr-str deletion-verb) " \" FROM grain.events\")"))))
  (is (seq (lint/lint-source "source.clj" "(clojure.core/str \"DEL\" \"ETE\" \" FROM grain.events\")")))
  (is (= 2 (count (lint/lint-source "source.cljc"
                                   (str "#?(:clj " (pr-str deletion-sql) " :cljs " (pr-str deletion-sql) ")"))))))

(deftest raw-sql-and-other-tooling-are-checked
  (doseq [file ["cleanup.sql" "probe.sh" "probe.py" "probe.js" "probe.ts"]]
    (is (seq (lint/lint-source file deletion-sql)) file)))

(deftest ordinary-reads-and-non-sql-deletions-remain-allowed
  (doseq [source ["(jdbc/execute! connection [\"SELECT * FROM grain.events\"])"
                  "(jdbc/execute! connection [\"INSERT INTO grain.events VALUES (?)\" value])"
                  "(http/delete url) {:method :delete} (cache/delete key) (fs/delete file)"
                  "(defn f \"Delete a cached value.\" [] ())"
                  "#\"delete.*from\" :delete-from-text"]]
    (is (empty? (lint/lint-source "source.clj" source)) source)))

(deftest source-roots-have-no-test-or-development-exemption
  (doseq [file ["components/example/test/example_test.clj" "bases/orc-dev/src/example.clj"
                "projects/web-api/src/example.clj" "development/src/probe.clj" "localstack-init/probe.clj"]]
    (is (seq (lint/lint-source file (pr-str deletion-sql))) file)))

(deftest reports-location-and-malformed-source
  (is (= 2 (:row (first (lint/lint-source "source.clj" (str "(def query\n  " (pr-str deletion-sql) ")"))))))
  (is (= 3 (:row (first (lint/lint-source "source.sql" (str "SELECT 1;\n\n" deletion-sql))))))
  (is (re-find #"Cannot lint source" (:message (first (lint/lint-source "broken.clj" "(unterminated"))))))

(deftest source-is-never-evaluated-and-missing-input-fails
  (let [dir (fs/create-temp-dir {:prefix "orc-sql-lint-"}) target (str (fs/path dir "not-created"))]
    (try
      (lint/lint-source "source.clj" (str "#=(spit " (pr-str target) " \"bad\")"))
      (is (not (fs/exists? target)))
      (is (thrown? clojure.lang.ExceptionInfo (lint/source-files [(str (fs/path dir "missing"))])))
      (finally (fs/delete-tree dir)))))

(deftest command-line-gate-fails-and-ignores-build-output
  (let [dir (fs/create-temp-dir {:prefix "orc-sql-gate-"})
        source (fs/path dir "cleanup.sql")]
    (try
      (spit (str source) deletion-sql)
      (is (= 1 (:exit (process/shell {:continue true :out :string :err :string}
                                    "bb" "scripts/lint_no_sql_delete.bb" (str dir)))))
      (fs/create-dirs (fs/path dir "target"))
      (fs/move source (fs/path dir "target" "cleanup.sql"))
      (is (= 0 (:exit (process/shell {:continue true :out :string :err :string}
                                    "bb" "scripts/lint_no_sql_delete.bb" (str dir)))))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (clojure.test/run-tests 'lint-no-sql-delete-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
