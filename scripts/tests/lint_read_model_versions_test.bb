(ns lint-read-model-versions-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is run-tests]]))

(load-file "scripts/lint_read_model_versions.bb")
(require '[lint-read-model-versions :as lint])

(deftest read-model-declarations
  (doseq [head ["defreadmodel" "rmp/defreadmodel"
               "ai.obney.grain.read-model-processor-v2.interface/defreadmodel"]]
    (is (empty? (lint/lint-source "fixture.clj" (str "(" head " :sample rows {:version 1} [s e] s)"))))
    (doseq [version ["0" "2" "11" "nil" "current-version" "(inc 0)"]]
      (is (= 1 (count (lint/lint-source "fixture.clj"
                                      (str "(" head " :sample rows {:version " version "} [s e] s)")))))))
  (is (empty? (lint/lint-source "fixture.clj" "(defreadmodel :sample rows [s e] s)")))
  (is (= 1 (count (lint/lint-source "fixture.clj"
                                  "(rmp/register-read-model! :sample/rows reducer {:version 2})"))))
  (is (= 1 (count (lint/lint-source "fixture.clj"
                                  "(rmp/register-read-model! :sample/rows reducer options)")))))

(deftest query-subscriptions
  (is (empty? (lint/lint-source "fixture.clj" "{:grain/read-models {:sample/rows 1}}")))
  (is (empty? (lint/lint-source "fixture.clj" "{:grain/read-models {}}")))
  (is (empty? (lint/lint-source "fixture.clj" "{:grain/read-models (rmp/query-models student-list)}")))
  (is (= 2 (count (lint/lint-source "fixture.clj"
                                  "{:grain/read-models {:sample/rows 2 :sample/totals version}}"))))
  (is (= 1 (count (lint/lint-source "fixture.clj" "{:grain/read-models subscriptions}")))))

(deftest reports-real-source-locations-and-fails-on-parse-errors
  (let [hits (lint/lint-source "fixture.clj" "\n(defreadmodel :sample rows\n  {:version 3} [s e] s)")]
    (is (= [{:file "fixture.clj" :row 3 :col 13
             :message "Read-model :version must use literal version 1, found 3"}] hits)))
  (is (= 1 (count (lint/lint-source "broken.clj" "(defreadmodel :sample rows {"))))
  (is (empty? (lint/lint-source "fixture.clj"
                              "(def template {:version 9}) ; unrelated domain version\n\"{:grain/read-models {:sample/rows 3}}\"")))
  (is (empty? (lint/lint-source "fixture.clj"
                              "(comment (defreadmodel :sample rows {:version 7} [s e] s))\n#_(defreadmodel :sample rows {:version 8} [s e] s)\n(defreadmodel :sample rows {:version 1} [s e] s)"))))

(deftest cli-exit-status
  (let [dir (fs/create-temp-dir {:prefix "aa-read-model-lint-"})
        file (str (fs/path dir "fixture.clj"))
        run #(process/shell {:out :string :err :string :continue true}
                            "bb" "scripts/lint_read_model_versions.bb" file)]
    (try
      (spit file "(defreadmodel :sample rows {:version 1} [s e] s)")
      (is (zero? (:exit (run))))
      (spit file "(defreadmodel :sample rows {:version 2} [s e] s)")
      (let [result (run)]
        (is (= 1 (:exit result)))
        (is (re-find #"fixture.clj:1:[0-9]+: error:" (:out result))))
      (spit file "(defreadmodel :sample rows {")
      (is (= 1 (:exit (run))))
      (finally (fs/delete-tree dir)))))

(deftest source-scope
  (let [dir (fs/create-temp-dir {:prefix "aa-read-model-lint-scope-"})
        src (fs/create-dirs (fs/path dir "src"))
        tests (fs/create-dirs (fs/path dir "test"))]
    (try
      (doseq [path [src tests]]
        (spit (str (fs/path path "fixture.clj")) "(defreadmodel :sample rows {:version 2} [s e] s)"))
      (is (= [(str (fs/path src "fixture.clj"))] (lint/source-files [(str dir)])))
      (is (= 1 (count (mapcat #(lint/lint-source % (slurp %)) (lint/source-files [(str dir)])))))
      (is (thrown? Exception (lint/source-files [(str (fs/path dir "missing"))])))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (run-tests 'lint-read-model-versions-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
