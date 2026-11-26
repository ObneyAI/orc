(ns lint-service-components-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(load-file "scripts/lint_service_components.bb")
(require '[lint-service-components :as lint])

(defn with-components [f]
  (let [root (fs/create-temp-dir {:prefix "aa-service-lint-"})]
    (try (f root) (finally (fs/delete-tree root)))))

(deftest exact-allowlist
  (is (= #{"colbert-service" "evaluation-service" "gepa-service" "ontology-service" "orc-service"}
         lint/allowed-services))
  (with-components
    (fn [root]
      (doseq [name lint/allowed-services] (fs/create-dirs (fs/path root name)))
      (fs/create-dirs (fs/path root "ui" "nested-service"))
      (fs/create-dirs (fs/path root "service-adapter"))
      (spit (str (fs/path root "file-service")) "not a component directory")
      (is (empty? (lint/unapproved-components root)))
      (is (= 0 (lint/-main (str root)))))))

(deftest every-unapproved-directory-fails
  (with-components
    (fn [root]
      (doseq [name ["z-new-service" "promo-service" "orc-service-copy-service" "unapproved-service-v2"]]
        (fs/create-dirs (fs/path root name)))
      (is (= (mapv #(str (fs/path root %))
                   ["orc-service-copy-service" "promo-service" "unapproved-service-v2" "z-new-service"])
             (lint/unapproved-components root)))
      (let [{:keys [exit out]} (process/shell {:out :string :err :string :continue true}
                                           "bb" "scripts/lint_service_components.bb" (str root))]
        (is (= 1 exit))
        (doseq [name ["orc-service-copy-service" "promo-service" "unapproved-service-v2" "z-new-service"]]
          (is (str/includes? out (str name ": error:"))))
        (is (str/includes? out "README.md#service-ownership-and-base-review"))
        (is (str/includes? out "BLOCKED"))
        (is (str/includes? out "ask a human"))))))

(deftest invalid-input-fails-closed
  (with-components
    (fn [root]
      (is (thrown? Exception (lint/unapproved-components (fs/path root "missing"))))
      (is (= 1 (lint/-main (str (fs/path root "missing")))))
      (is (= 1 (lint/-main (str root) (str root)))))))

(let [{:keys [fail error]} (run-tests 'lint-service-components-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
