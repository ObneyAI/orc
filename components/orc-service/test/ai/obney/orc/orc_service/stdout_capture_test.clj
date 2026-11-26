(ns ai.obney.orc.orc-service.stdout-capture-test
  "Actual public sandbox/executor calls; no providers, tools or stores mocked."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [ai.obney.orc.orc-service.core.sci-sandbox :as plain]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm]
            [ai.obney.orc.orc-service.core.executor :as executor])
  (:import [java.io StringWriter]))

(defn- restored-call [f]
  (let [host (StringWriter.) sci-writer (StringWriter.)]
    (binding [*out* host]
      (sci/with-bindings {sci/out sci-writer}
        (let [result (f)]
          (is (identical? host *out*) "Host output binding is restored")
          (is (identical? sci-writer @sci/out) "SCI output binding is restored")
          (is (= "" (str host) (str sci-writer)) "Iteration output never leaks to outer writers")
          result)))))

(defn- eval-plain [code]
  (plain/execute-code (plain/build-sci-context {}) code))
(defn- eval-rlm [code]
  (rlm/execute-rlm-code (rlm/build-rlm-context {:blackboard {} :declared-writes []}) code))

(deftest plain-and-researcher-output-preserve-results-and-normal-errors
  (doseq [[surface evaluate] [[:plain eval-plain] [:researcher eval-rlm]]]
    (testing (name surface)
      (let [result (restored-call #(evaluate "(do (print :one) (println :two true) (prn :three false) 42)"))]
        (is (= ":one:two true\n:three false\n" (:stdout result)))
        (is (= 42 (:raw-result result)))
        (is (nil? (:error result))))
      (let [result (restored-call #(evaluate "(do (println :before) (prn :retained true) (/ 1 0))"))]
        (is (= ":before\n:retained true\n" (:stdout result)))
        (is (= "Divide by zero" (:error result)))
        (is (nil? (:raw-result result))))
      (let [result (restored-call #(evaluate "(+ 20 22)"))]
        (is (= 42 (:raw-result result)))
        (is (= "" (:stdout result))))
      (let [result (restored-call #(evaluate "(slurp \"/etc/passwd\")"))]
        (is (= "Could not resolve symbol: slurp" (:error result)))
        (is (nil? (:raw-result result)))))))

(deftest generated-functions-print-through-the-native-code-executor
  (doseq [[source expected-status expected-error]
          [["(fn [_] (println :host true) (prn :sci false) {:answer 42})" :success nil]
           ["(fn [_] (println :host true) (prn :sci false) (/ 1 0))" :failure "Divide by zero"]]]
    (let [f (:raw-result (eval-plain source)) writer (StringWriter.)
          host-root *out* sci-root @sci/out
          result (binding [*out* writer]
                   (let [r (executor/execute-code {:fn f :reads [] :writes [:answer]} {} {})]
                     (is (identical? writer *out*))
                     (is (identical? sci-root @sci/out)) r))]
      (is (identical? host-root *out*))
      (is (identical? sci-root @sci/out))
      (is (= ":host true\n:sci false\n" (str writer)))
      (is (= expected-status (:status result)))
      (is (= expected-error (:error result)))
      (when (= :success expected-status)
        (is (= {:answer 42} (:outputs result)))))))

(deftest authored-native-functions-retain-host-output-and-error-shape
  (doseq [[f expected-status expected-error]
          [[(fn [_] (prn :authored true) {:answer 42}) :success nil]
           [(fn [_] (prn :authored true) (throw (ex-info "authored-error" {:preserved true}))) :failure "authored-error"]]]
    (let [writer (StringWriter.) so @sci/out
          result (binding [*out* writer]
                   (let [r (executor/execute-code {:fn f :reads [] :writes [:answer]} {} {})]
                     (is (identical? writer *out*))
                     (is (identical? so @sci/out)) r))]
      (is (= ":authored true\n" (str writer)))
      (is (= expected-status (:status result)))
      (is (= expected-error (:error result))))))

(deftest output-scope-restores-different-outer-bindings-and-propagates-original-exception
  (let [host (StringWriter.) sci-outer (StringWriter.) inner (StringWriter.)
        sentinel (ex-info "sentinel" {:preserved true})
        root-host *out* root-sci @sci/out]
    (binding [*out* host]
      (sci/with-bindings {sci/out sci-outer}
        (let [caught (try
                       (plain/call-with-output-writer inner
                         #(do (is (identical? inner *out*))
                              (is (identical? inner @sci/out))
                              (println :host true)
                              (sci/eval-string* (plain/build-sci-context {}) "(prn :sci false)")
                              (throw sentinel)))
                       (catch Exception e e))]
          (is (identical? sentinel caught) "The helper propagates the exact exception object")
          (is (identical? host *out*))
          (is (identical? sci-outer @sci/out)))))
    (is (= ":host true\n:sci false\n" (str inner)))
    (is (= "" (str host) (str sci-outer)))
    (is (identical? root-host *out*))
    (is (identical? root-sci @sci/out))))
