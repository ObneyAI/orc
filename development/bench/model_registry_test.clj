(ns model-registry-test
  "CH-1 — tests for the bench harness's model-registration precondition.

   These run with NO network and NO API key: the guard is a pure check over
   the litellm registration registry, which is exactly why it can fire
   BEFORE any LLM call.

   Run:
     clojure -M:dev -e \"(require 'model-registry-test)(clojure.test/run-tests 'model-registry-test)\""
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [litellm.router :as litellm-router]
            [model-registry :as mr]
            [runner]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.evidence-guard :as evidence-guard]))

(defn- with-clean-registry [f]
  (litellm-router/clear-router!)
  (try (f) (finally (litellm-router/clear-router!))))

(use-fixtures :each with-clean-registry)

(def ^:private gemini "google/gemini-3-flash-preview")
(def ^:private qwen "qwen/qwen3.5-flash-02-23")

(defn- register-generic-openrouter!
  "Exactly what development/bench/runner.clj did before CH-1: a GENERIC
   :openrouter entry configured for one model, plus openrouter/<that model>."
  [model]
  (let [base {:provider :openrouter
              :model model
              :config {:api-base "https://openrouter.ai/api/v1" :api-key "test-key"}}]
    (litellm-router/register! :openrouter base)
    (litellm-router/register! (keyword (str "openrouter/" model)) base)))

;; =============================================================================
;; RED 1 — a model the harness will use but has NOT registered fails LOUDLY,
;;         naming the offending model.
;; =============================================================================

(deftest unregistered-model-throws-and-names-the-offender
  (testing "asserting a model that no registration is configured for throws, and the message names that model"
    (register-generic-openrouter! gemini)
    (let [t (try (mr/assert-models-registered! "test-harness" [gemini qwen])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? t) "an unregistered model MUST throw")
      (is (str/includes? (ex-message t) qwen)
          "the message names the offending model")
      (is (= [qwen] (:unregistered-models (ex-data t)))
          "ex-data carries exactly the offending model(s)"))))

;; =============================================================================
;; RED 2 — the failure also reports the registrations that DO exist, so the
;;         reader can see what the harness thought it was configured for.
;; =============================================================================

(deftest failure-reports-the-registrations-that-exist
  (testing "the thrown message and ex-data enumerate every existing registration and the model it is configured for"
    (register-generic-openrouter! gemini)
    (let [t (try (mr/assert-models-registered! "test-harness" [qwen])
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? t))
      (is (str/includes? (ex-message t) ":openrouter")
          "names the generic registration")
      (is (str/includes? (ex-message t) gemini)
          "names the model that registration is configured for")
      (is (= {:openrouter gemini
              (keyword (str "openrouter/" gemini)) gemini}
             (into {} (:registrations (ex-data t))))
          "ex-data carries the whole registry as name -> configured model"))))

;; =============================================================================
;; RED 3 — THE LOAD-BEARING ONE. A generic registration configured for one
;;         model must NOT satisfy a requirement for a different model. This is
;;         the exact shape development/bench/runner.clj shipped before CH-1.
;; =============================================================================

(deftest generic-registration-does-not-satisfy-a-different-model
  (testing "the runner's pre-CH-1 registration (generic :openrouter configured for gemini) does NOT count as registering qwen"
    (register-generic-openrouter! gemini)
    (let [snapshot (mr/registry-snapshot)]
      (is (seq (mr/registrations-for snapshot gemini))
          "gemini IS registered — it is the model the generic entry is configured for")
      (is (empty? (mr/registrations-for snapshot qwen))
          "qwen is NOT registered — reachable through the generic entry is not the same as declared")
      (is (= [qwen] (mr/unregistered-models snapshot [gemini qwen]))
          "only qwen is reported unregistered"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (mr/assert-models-registered! "test-harness" [gemini qwen]))
        "and the precondition therefore fires")))

;; =============================================================================
;; RED 4 — the guard PASSES once every model is explicitly registered, and is
;;         not merely 'always throws'.
;; =============================================================================

(deftest passes-when-every-model-is-explicitly-registered
  (testing "explicitly registering each model under its own id satisfies the precondition"
    (register-generic-openrouter! gemini)
    (litellm-router/register! (keyword (str "openrouter/" qwen))
                              {:provider :openrouter
                               :model qwen
                               :config {:api-base "https://openrouter.ai/api/v1"
                                        :api-key "test-key"}})
    (is (= :ok (mr/assert-models-registered! "test-harness" [gemini qwen]))
        "no throw once both models are declared")
    (is (= :ok (mr/assert-models-registered! "test-harness" []))
        "an empty requirement set is trivially satisfied")))

;; =============================================================================
;; RED 5 — the runner's REQUIRED model set is derived from the ENGINE's own
;;         defaults, not from the harness's registration list.
;;
;;         This is what makes the guard non-vacuous. If the required set were
;;         just a copy of the registered set it could never disagree with
;;         itself. Reading the reranker's and the evidence guard's own :model
;;         slots means an engine-side default change that the harness has not
;;         followed is a LOUD failure, which is precisely the CH-1 defect.
;; =============================================================================

(deftest required-models-are-read-from-the-engines-own-defaults
  (testing "the runner requires the reranker's own default model, the evidence guard's verifier model, and its own RLM model"
    (let [required (set (runner/required-models))]
      (is (contains? required (:model runner/config))
          "the runner's own RLM model")
      (is (contains? required reranker/default-model)
          "the reranker's RR-2 default — the model the CH-1 probe was using without declaring it")
      (is (contains? required evidence-guard/verifier-model)
          "the evidence guard's verifier model")))
  (testing "the required set FOLLOWS the engine: change an engine default and the requirement moves with it"
    (with-redefs [reranker/default-model "someone/a-model-the-harness-never-heard-of"]
      (is (contains? (set (runner/required-models))
                     "someone/a-model-the-harness-never-heard-of")
          "required-models re-reads the engine default rather than caching a literal"))))

;; =============================================================================
;; RED 6 — register-models! registers every DECLARED model and then asserts
;;         the REQUIRED set. An engine default the harness has not declared
;;         fails loudly.
;; =============================================================================

(deftest register-models-declares-then-asserts
  (testing "after register-models! every required model is explicitly registered"
    (runner/register-models! "test-key")
    (let [snapshot (mr/registry-snapshot)]
      (doseq [m (runner/required-models)]
        (is (seq (mr/registrations-for snapshot m))
            (str m " is explicitly registered, not merely reachable")))))
  (testing "an engine default the harness has NOT declared makes register-models! throw, naming it"
    (with-redefs [reranker/default-model "someone/a-model-the-harness-never-heard-of"]
      (let [t (try (runner/register-models! "test-key")
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? t) "an undeclared engine default MUST fail the harness startup")
        (is (str/includes? (ex-message t) "someone/a-model-the-harness-never-heard-of")
            "and the message names it")))))

;; =============================================================================
;; RED 7 — the precondition fires BEFORE the harness builds anything. Not
;;         after a confusing result; not after an LLM call.
;; =============================================================================

(deftest guard-fires-before-any-context-is-built
  (testing "start! asserts registrations before create-context — so before any LLM call is possible"
    (let [built? (atom false)]
      (with-redefs [reranker/default-model "someone/a-model-the-harness-never-heard-of"
                    runner/create-context (fn [] (reset! built? true) (throw (ex-info "must not reach" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"UNREGISTERED MODEL"
                              (runner/start!))
            "start! fails with the registration error")
        (is (false? @built?)
            "and it failed BEFORE building a context (hence before any LLM call)")))))
