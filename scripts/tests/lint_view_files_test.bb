#!/usr/bin/env bb

(ns lint-view-files-test
  (:require [clojure.test :refer [deftest is run-tests]]))

(load-file "scripts/lint_view_files.bb")

(def prefix "components/orc-service/src/ai/obney/orc/orc_service/")
(def canonical-ns "(ns ai.obney.orc.orc-service.views)")

(defn inspect [path source]
  (lint-view-files/lint-source (str prefix path) source))

(deftest canonical-views-are-optional-and-have-no-size-limit
  (is (empty? (inspect "core/logic.clj" "(ns example) (defn calculation [] 1)")))
  (is (empty? (inspect "views.clj" (str canonical-ns "\n(defn page [] [:div])"))))
  (is (empty? (inspect "views.clj" (str canonical-ns (apply str (repeat 600 "\n")) "(defn page [] [:div])"))))
  (is (empty? (inspect "views.clj" (str "^#:allium{:open [\"ALLIUM-OPEN: question\"]} " canonical-ns)))))

(deftest split-and-legacy-paths-fail-even-when-empty
  (doseq [path ["views/detail.clj" "views/common.cljc" "views/nested/panel.cljs"
                "views.cljc" "views.cljs" "core/views.clj" "core/admin_views.clj"
                "other/views.clj" "admin_view.clj" "views/empty.bb"]]
    (let [findings (inspect path "")]
      (is (= 1 (count findings)) path)
      (is (re-find #"Service views must live in .*orc_service/views.clj" (:message (first findings))) path))))

(deftest namespace-renaming-cannot-hide-split-views
  (doseq [source ["(ns ai.obney.orc.orc-service.views.detail)"
                  "(ns ai.obney.orc.orc-service.views)"
                  "(ns ai.obney.orc.orc-service.core.admin-views)"
                  "#?(:clj (ns ordinary) :cljs (ns ai.obney.orc.orc-service.views.extra))"
                  "(do (clojure.core/ns ai.obney.orc.orc-service.views.extra))"]]
    (is (= 1 (count (inspect "helper.clj" source))) source)))

(deftest canonical-file-must-declare-only-its-own-namespace
  (doseq [source ["" "(ns other.views)" "(ns ai.obney.orc.orc-service.views.detail)"
                  (str canonical-ns " (ns ai.obney.orc.orc-service.views.extra)")]]
    (is (re-find #"must declare exactly one namespace" (:message (first (inspect "views.clj" source)))))))

(deftest declarations-in-data-are-not-namespaces
  (doseq [source ["'(ns ai.obney.orc.orc-service.views.detail)"
                  "#_(ns ai.obney.orc.orc-service.views.detail) (ns ordinary)"
                  "\"(ns ai.obney.orc.orc-service.views.detail)\""
                  "(ns ordinary (:require [ai.obney.orc.orc-service.views :as views]))"]]
    (is (empty? (inspect "core/queries.clj" source)) source)))

(deftest all-service-areas-and-only-service-source-are-covered
  (doseq [service ["orc-service" "future-service-v2" "future-service"]]
    (is (= 1 (count (lint-view-files/lint-source (str "components/" service "/src/rogue/views.clj") "")))))
  (doseq [path ["components/ui/src/ai/obney/orc/ui/views/detail.clj"
                "components/payment-plan-insights/src/views.clj"
                "components/orc-service/test/views/detail.clj"
                "development/src/views/detail.clj" "bases/orc-dev/src/views.clj"]]
    (is (empty? (lint-view-files/lint-source path "(ns example.views.detail)")) path)))

(deftest malformed-source-and-reader-evaluation-fail-closed
  (doseq [source ["(ns broken" "#=(throw (Exception. \"must not execute\"))"]]
    (is (re-find #"Cannot lint source" (:message (first (inspect "core/queries.clj" source)))))))

(deftest missing-input-fails-closed-and-absolute-paths-are-normalized
  (is (thrown? clojure.lang.ExceptionInfo (lint-view-files/source-files ["/nonexistent-orc-view-input"])))
  (is (= (lint-view-files/source-files ["components/orc-service"])
         (lint-view-files/source-files [(str (System/getProperty "user.dir") "/components/orc-service")]))))

(let [{:keys [fail error]} (run-tests 'lint-view-files-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
