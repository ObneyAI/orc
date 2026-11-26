#!/usr/bin/env bb

(ns lint-code-comments-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(load-file "scripts/lint_code_comments.bb")
(alias 'lint 'lint-code-comments)

(defn- long-source [header]
  (str header "\n" (str/join "\n" (map #(str "(def value-" % " " % ")") (range 200)))))

(deftest comments-are-rejected-in-all-source-areas
  (doseq [file ["components/orc-service/src/a.clj" "components/ui/src/ai/obney/orc/ui/gallery/a.clj"
                "components/orc-service/test/a.clj" "bases/orc-dev/src/a.clj"
                "projects/web-api/src/a.clj" "development/src/a.clj" "scripts/a.bb" "localstack-init/a.clj"]]
    (is (= 1 (count (lint/lint-source file "; leading prose\n(ns example)")))))
  (is (= [[2 10]] (mapv (juxt :row :col)
                       (lint/lint-source "source.clj" "(ns example)\n(def x 1); inline prose")))))

(deftest only-standalone-framed-section-headers-are-allowed
  (doseq [header [";; --- Query helpers ---" ";; === Query helpers ==="
                  ";; ===============\n;; Query helpers\n;; ==============="
                  ";; ---------------\n;; Query helpers\n;; ---------------"]]
    (is (empty? (lint/lint-source "source.clj" (long-source header))))
    (is (empty? (lint/lint-source "short.clj" (str header "\n(def x 1)"))))
    (is (empty? (lint/lint-source "nested.clj"
                                 (str "(do\n  " (str/replace header "\n" "\n  ") "\n  (def x 1))")))))
  (is (empty? (lint/lint-source "schema.clj" "{:a :int\n ;; --- Optional fields ---\n :b :string}")))
  (doseq [header [";; A prose comment" ";; ---------" ";; --- Why this works. ---"
                  ";; --- Bad ---\n;; explanatory prose" ";; ====\n;; Heading\n;; ----"
                  (str ";; --- " (apply str (repeat 61 "A")) " ---")
                  "(do\n  ;; ===\n ;; Heading\n  ;; ===\n :value)"
                  "(def x 1) ;; --- Inline section ---"]]
    (is (seq (lint/lint-source "source.clj" (long-source header))))))

(deftest interpreter-directives-are-script-syntax
  (is (empty? (lint/lint-source "scripts/a.bb" "#!/usr/bin/env bb\n(println :ok)")))
  (is (seq (lint/lint-source "scripts/a.bb" "\n#!/usr/bin/env bb\n(println :ok)"))))

(deftest docstrings-strings-regexes-and-characters-stay-intact
  (is (empty? (lint/lint-source
               "source.clj"
               "(ns example \"Namespace documentation\")\n(defn f \"Useful documentation\" [] \"; #_ (comment data)\")\n(def pattern #\"; #_ \\\"\")\n(def chars [\\; \\# \\newline])"))))

(deftest discarded-code-is-permitted-only-in-the-development-project
  (is (empty? (lint/lint-source "development/src/probe.clj" "#_(println :example)")))
  (doseq [file ["components/example/test/fixture.clj" "scripts/a.bb" "localstack-init/a.clj"
                "projects/development/src/a.clj" "development-other/a.clj"
                "development/../components/example/src/a.clj"]]
    (is (= 1 (count (lint/lint-source file "#_(println :example)")))))
  (is (= 2 (count (lint/lint-source "source.clj" "#_#_(def a 1) (def b 2)"))))
  (is (seq (lint/lint-source "development/src/probe.clj" "#_(do ; prose\n :example)"))))

(deftest comment-macros-cannot-hide-inactive-code
  (doseq [source ["(comment (println :example))" "(clojure.core/comment :example)"
                  "(cljs.core/comment :example)"
                  "(ns example (:require (clojure [core :as c]))) (c/comment :example)"
                  "(alias 'c 'clojure.core) (c/comment :example)"
                  "(require '[clojure.core :as c]) (c/comment :example)"
                  "(ns example (:require [clojure.core :refer [comment] :rename {comment hidden}])) (hidden :example)"
                  "(ns example (:require [clojure.core :as c])) (c/comment :example)"
                  "(ns example (:refer-clojure :rename {comment ignored})) (ignored :example)"]]
    (is (= 1 (count (lint/lint-source "development/src/probe.clj" source))))))

(deftest reader-branches-are-inspected-and-quoted-data-stays-data
  (is (= 2 (count (lint/lint-source "source.cljc" "#?(:clj (comment :a) :cljs #_(def x 1) :other nil)")))))
  (is (empty? (lint/lint-source "source.clj" "'(comment :a) `(comment :b) (quote (comment :c))")))
  (is (= 1 (count (lint/lint-source "source.clj" "`(data ~(comment :a))"))))

(deftest open-question-metadata-is-not-a-comment-exemption
  (is (empty? (lint/lint-source "source.clj" "^{:allium/open [\"ALLIUM-OPEN: SF-SVC-OQ-01\"]} (defn f [] :safe)")))
  (is (seq (lint/lint-source "source.clj" ";; ALLIUM-OPEN: SF-SVC-OQ-01\n(defn f [] :safe)"))))

(deftest malformed-source-and-missing-input-fail-closed
  (is (re-find #"Cannot lint source" (:message (first (lint/lint-source "broken.clj" "(def x")))))
  (is (thrown? clojure.lang.ExceptionInfo (lint/source-files ["/nonexistent-orc-comment-lint-input"]))))

(deftest inspection-never-executes-source
  (let [dir (fs/create-temp-dir {:prefix "orc-comments-"}) target (str (fs/path dir "not-created"))]
    (try
      (lint/lint-source "source.clj" (str "#=(spit " (pr-str target) " \"bad\")"))
      (is (not (fs/exists? target)))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (clojure.test/run-tests 'lint-code-comments-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
