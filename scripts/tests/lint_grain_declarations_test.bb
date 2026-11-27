#!/usr/bin/env bb

(ns lint-grain-declarations-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]))

(load-file "scripts/lint_grain_declarations.bb")
(alias 'lint 'lint-grain-declarations)

(def service "components/orc-service/src/ai/obney/orc/orc_service/")

(deftest every-primitive-has-one-canonical-file
  (doseq [[primitive filename] lint/primitive-files]
    (testing primitive
      (let [source (str "(" primitive " :sample example {})")]
        (is (empty? (lint/lint-source (lint/canonical-file "orc-service" filename) source)))
        (is (= 1 (count (lint/lint-source (str service "core/capability/" filename) source))))
        (let [hits (lint/lint-source (str service "core/helper.clj") source)]
          (is (= 1 (count hits)))
          (is (re-find (re-pattern filename) (:message (first hits)))))))))

(deftest only-permitted-service-source-trees-can-declare-primitives
  (doseq [path ["bases/orc-dev/src/commands.clj"
                "projects/web-api/src/commands.clj"
                "components/ui/src/commands.clj"
                "components/gepa-service/resources/commands.clj"
                "components/unknown-service/src/commands.clj"
                "components/nested/orc-service/src/commands.clj"
                "development/src/commands.clj" "scripts/commands.clj"]]
    (is (= 1 (count (lint/lint-source path "(defcommand :sample example {})"))) path))
  (is (empty? (lint/lint-source "components/ui/src/widget.clj" "(defn widget [] [:div])"))))

(deftest only-test-fixtures-are-exempt
  (doseq [path ["components/read-model-warmer/test/core_test.clj"
                "bases/orc-dev/test/core_test.clj"
                "scripts/tests/synthetic.clj"]]
    (is (empty? (lint/lint-source path "(defquery :sample example {})")) path))
  (is (= 1 (count (lint/lint-source "components/ui/src/ai/obney/orc/ui/gallery/queries.clj"
                                   "(defquery :sample example {})"))))
  (is (= 1 (count (lint/lint-source (str service "tests/helper.clj")
                                   "(defquery :sample example {})")))))

(deftest multiline-nested-and-qualified-declarations-are-checked
  (let [hits (lint/lint-source (str service "helper.clj")
                             "(do\n  (cp/defcommand\n    :sample example {})\n  (ai.obney.grain.query-processor.interface/defquery :sample page {}))")]
    (is (= 2 (count hits)))
    (is (= [[2 3] [4 3]] (mapv (juxt :row :col) hits)))))

(deftest renamed-imports-do-not-bypass-placement
  (let [source "(ns sample (:require [ai.obney.grain.query-processor.interface :refer [defquery] :rename {defquery page!}]))\n(page! :sample page {})"]
    (is (= 1 (count (lint/lint-source (str service "helper.clj") source))))
    (is (empty? (lint/lint-source (str service "core/queries.clj") source)))))

(deftest reader-conditionals-check-every-branch
  (is (= 2 (count (lint/lint-source (str service "helper.clj")
                                   "#?(:clj (defcommand :sample a {}) :cljs (defquery :sample b {}))"))))
  (is (= 1 (count (lint/lint-source (str service "helper.clj")
                                   "#?(:clj (ns sample (:require [ai.obney.grain.schema-util.interface :rename {defschemas schemas!} :refer [defschemas]])))\n(schemas! schemas {})")))))

(deftest literal-data-and-discarded-forms-do-not-register-declarations
  (is (empty? (lint/lint-source "development/src/notes.clj"
                               "; (defcommand :sample comment {})\n\"(defquery :sample string {})\"\n'(defschemas quoted {})\n(quote (defprocessor :sample data {}))\n`(defreadmodel :sample data {})\n#_(defevent :sample discarded {})"))))

(deftest comment-macro-is-not-a-placement-escape
  (is (= 1 (count (lint/lint-source (str service "helper.clj")
                                   "(comment (defcommand :sample example {}))")))))

(deftest unquoted-code-within-syntax-quoted-data-is-checked
  (is (= 1 (count (lint/lint-source (str service "helper.clj")
                                   "`(data ~(defcommand :sample active {}))")))))

(deftest malformed-source-fails-closed
  (let [hits (lint/lint-source (str service "commands.clj") "(defcommand :sample missing")]
    (is (= 1 (count hits)))
    (is (re-find #"Cannot lint source" (:message (first hits))))))

(deftest inspection-never-evaluates-source
  (let [dir (fs/create-temp-dir {:prefix "orc-grain-lint-"})
        target (str (fs/path dir "must-not-exist"))]
    (try
      (lint/lint-source (str service "commands.clj") (str "#=(spit " (pr-str target) " \"bad\")"))
      (is (not (fs/exists? target)))
      (finally (fs/delete-tree dir)))))

(deftest missing-input-cannot-pass
  (is (thrown? clojure.lang.ExceptionInfo (lint/source-files ["/nonexistent-orc-grain-lint-input"]))))

(deftest duplicate-primitive-files-are-rejected-even-without-declarations
  (doseq [filename (vals lint/primitive-files)]
    (is (empty? (lint/file-location-findings [(lint/canonical-file "orc-service" filename)])))
    (is (= 1 (count (lint/file-location-findings [(str service "core/nested/" filename)]))))
    (is (= 1 (count (lint/file-location-findings
                     [(str service (if (= filename "schemas.clj") "core/" "interface/") filename)]))))))

(deftest every-existing-service-requires-its-interface-schemas
  (let [root (fs/create-temp-dir {:prefix "orc-schema-layout-"})
        canonical (fs/path root "orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj")]
    (try
      (fs/create-dirs (fs/parent canonical))
      (is (= 1 (count (lint/missing-schema-findings root))))
      (spit (str canonical) "(ns fixture.schemas)")
      (is (empty? (lint/missing-schema-findings root)))
      (fs/create-dirs (fs/path root "ui"))
      (is (empty? (lint/missing-schema-findings root)))
      (finally (fs/delete-tree root)))))

(let [{:keys [fail error]} (clojure.test/run-tests 'lint-grain-declarations-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
