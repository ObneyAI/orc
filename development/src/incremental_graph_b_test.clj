(ns incremental-graph-b-test
  "INC-1 — pure-fn tests for the incremental runner's manifest logic:
   next-source selection (skip completed, nil when done) and the
   advance-only-on-reconcile-:success rule (a :partial-reconcile run must
   leave :completed-sources unchanged so the next invocation retries it).

   Dev-path tests (development/src is on :dev, not a poly brick) — run via
   clj -M:dev -e \"(require 'incremental-graph-b-test)
                   (clojure.test/run-tests 'incremental-graph-b-test)\""
  (:require [clojure.test :refer [deftest is testing]]
            [incremental-graph-b :as inc-b]))

(def five-sources
  [{:name :ipeds     :type :sql   :path "/x/ipeds.db"}
   {:name :crosswalk :type :csv   :path "/x/crosswalk.csv"}
   {:name :onet      :type :excel :path "/x/onet"}
   {:name :wages     :type :csv   :path "/x/wages.csv"}
   {:name :pseo      :type :excel :path "/x/pseo.xlsx"}])

(deftest next-source-selection
  (testing "no manifest / nothing completed → the FIRST source"
    (is (= :ipeds (:name (inc-b/next-source five-sources nil))))
    (is (= :ipeds (:name (inc-b/next-source five-sources [])))))
  (testing "skips completed sources by :name, in order"
    (is (= :crosswalk (:name (inc-b/next-source five-sources [:ipeds]))))
    (is (= :onet (:name (inc-b/next-source five-sources [:ipeds :crosswalk]))))
    (is (= :pseo (:name (inc-b/next-source five-sources [:ipeds :crosswalk :onet :wages])))))
  (testing "completion order does not matter — set semantics over names"
    (is (= :crosswalk (:name (inc-b/next-source five-sources [:pseo :ipeds])))))
  (testing "ALL completed → nil (the ACCRETION COMPLETE signal)"
    (is (nil? (inc-b/next-source five-sources [:ipeds :crosswalk :onet :wages :pseo]))))
  (testing "empty sources → nil"
    (is (nil? (inc-b/next-source [] [])))))

(deftest advance-completed-only-on-success
  (testing ":success appends the source name"
    (is (= [:ipeds] (inc-b/advance-completed [] :ipeds :success)))
    (is (= [:ipeds :crosswalk] (inc-b/advance-completed [:ipeds] :crosswalk :success))))
  (testing "a NON-:success reconcile leaves :completed-sources UNCHANGED (retry next run)"
    (is (= [:ipeds] (inc-b/advance-completed [:ipeds] :crosswalk :failed)))
    (is (= [] (inc-b/advance-completed [] :ipeds :timeout)))
    (is (= [] (inc-b/advance-completed [] :ipeds :not-run)))
    (is (= [] (inc-b/advance-completed [] :ipeds nil))))
  (testing "nil completed coerces to a vector"
    (is (= [:ipeds] (inc-b/advance-completed nil :ipeds :success)))
    (is (= [] (inc-b/advance-completed nil :ipeds :failed)))))

(deftest reconcile-status-read-from-source-reports
  (let [reports [{:source {:type :sql :path "/x/ipeds.db"}
                  :extracted {:concepts 100 :relationships 50}
                  :reconcile {:status :success :landed 90}
                  :axiom {:status :success} :embed {:status :success}}
                 {:source {:type :csv :path "/x/crosswalk.csv"}
                  :extracted {:concepts 10 :relationships 5}
                  :reconcile {:status :failed :landed nil}
                  :axiom {:status :not-run} :embed {:status :not-run}}]]
    (testing "reads the matching source's reconcile status by :path"
      (is (= :success (inc-b/reconcile-status-for reports "/x/ipeds.db")))
      (is (= :failed (inc-b/reconcile-status-for reports "/x/crosswalk.csv"))))
    (testing "absent source / nil or empty reports → :not-run (never a fabricated :success)"
      (is (= :not-run (inc-b/reconcile-status-for reports "/x/other.csv")))
      (is (= :not-run (inc-b/reconcile-status-for nil "/x/ipeds.db")))
      (is (= :not-run (inc-b/reconcile-status-for [] "/x/ipeds.db"))))))

(deftest partial-reconcile-run-retries-the-same-source
  (testing "END-TO-END manifest logic: a :partial-reconcile run (this source's
            reconcile non-:success) leaves the manifest so the NEXT invocation
            picks the SAME source again"
    (let [completed [:ipeds]
          src (inc-b/next-source five-sources completed)          ; → :crosswalk
          reports [{:source {:type :csv :path "/x/crosswalk.csv"}
                    :reconcile {:status :timeout :landed nil}}]
          status (inc-b/reconcile-status-for reports (:path src))
          completed' (inc-b/advance-completed completed (:name src) status)]
      (is (= :crosswalk (:name src)))
      (is (= [:ipeds] completed') "NOT advanced")
      (is (= :crosswalk (:name (inc-b/next-source five-sources completed')))
          "next invocation retries crosswalk")))
  (testing "and a :success run advances to the next source"
    (let [completed [:ipeds]
          src (inc-b/next-source five-sources completed)
          reports [{:source {:type :csv :path "/x/crosswalk.csv"}
                    :reconcile {:status :success :landed 42}}]
          completed' (inc-b/advance-completed
                      completed (:name src)
                      (inc-b/reconcile-status-for reports (:path src)))]
      (is (= [:ipeds :crosswalk] completed'))
      (is (= :onet (:name (inc-b/next-source five-sources completed')))))))
