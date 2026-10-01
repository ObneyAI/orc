(ns ai.obney.orc.orc-service.closure-rejection-hint-test
  "CV-B (C7) — the closure-rejection repair hint, preventive + reactive.

   Usefulness report 06 / the marathon full-bench run: a checkpointed
   researcher wrote an emit-tree! :code node's :fn as an ALREADY-EVALUATED
   closure (unquoted `(fn ...)`) instead of quoted source. rlm_sandbox's
   emit-tree!-fn rejects that tree (durable-source-required? +
   live-inline-closure?) with ex-data {:requirement
   :quoted-inline-function-source} — but nothing downstream told the model
   WHY or HOW to fix it, so the marathon run burned five iterations and
   139k tokens rediscovering the same rejection.

   This bundle:
   1. Exports rlm_sandbox's rejection message as a constant and carries
      :error-data (ex-data e) alongside :error out of execute-rlm-code's
      catch, so the requirement keyword survives the exception boundary.
   2. Adds a reactive arm to executor's diagnose-parse-error, keyed on the
      ex-data keyword (falling back to a literal — never regex — substring
      check against the exported constant when only the message text
      survives), surfaced through build-iteration-history's next-iteration
      prompt.
   3. Adds a preventive bullet to the Common-pitfalls block, scoped to
      checkpointed campaigns (the only campaigns durable-source-required?
      ever gates).

   Assertions are on STRUCTURED data (the ex-data keyword) and on OUR OWN
   template text (the exported constant, the pitfalls bullet) — never a
   regex or phrase match over model-authored prose."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.iteration-evidence :as iteration-evidence]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(def ^:private build-iteration-history
  (requiring-resolve 'ai.obney.orc.orc-service.core.executor/build-iteration-history))

(def ^:private build-rlm-code-generation-module
  (requiring-resolve 'ai.obney.orc.orc-service.core.executor/build-rlm-code-generation-module))

;; =============================================================================
;; 1. execute-rlm-code's catch carries :error-data through the exception
;;    boundary — the sandbox's OWN throw, exercised through its public
;;    execute-rlm-code entry point (not a hand-built ex-info standing in
;;    for it).
;; =============================================================================

(deftest execute-rlm-code-catch-carries-the-ex-data-keyword
  (testing "a checkpointed campaign's already-evaluated :fn closure is rejected
            by emit-tree!-fn, and execute-rlm-code's catch carries the
            sandbox's own ex-data :requirement keyword as :error-data
            alongside the :error message"
    (let [sandbox (rlm-sandbox/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :inputs {}
                    :durable-source-required? true})
          ;; Unquoted (fn ...) — SCI evaluates it to a live closure before
          ;; emit-tree!-fn runs, tripping live-inline-closure?. Mirrors
          ;; checkpointed_researcher_test's checkpointed-inline-closure-is-
          ;; rejected-before-tree-append stub code.
          code (str "(emit-tree! [:sequence "
                    "[:code {:reads [:n] :writes [:doubled] "
                    ":fn (fn [{:keys [inputs]}] {:doubled (* 2 (:n inputs))})}] "
                    "[:final {:keys [:doubled]}]])")
          result (rlm-sandbox/execute-rlm-code sandbox code)]
      (is (some? (:error result)) (pr-str result))
      (is (= :quoted-inline-function-source (:requirement (:error-data result)))
          "the ex-data :requirement keyword survives the catch as :error-data")
      (is (= rlm-sandbox/quoted-inline-function-source-message (:error result))
          "the :error message is the exported constant, byte-identical"))))

(deftest execute-rlm-code-catch-does-not-false-positive-on-an-unrelated-error
  (testing "an ordinary SCI error (SCI's own sci/error ex-data, no
            :requirement key) never carries the closure-rejection keyword —
            proves diagnose-parse-error's ex-data check can't false-positive"
    (let [sandbox (rlm-sandbox/build-rlm-context
                   {:provider :openrouter :blackboard {} :inputs {}})
          result (rlm-sandbox/execute-rlm-code sandbox "(no-such-fn 1 2)")]
      (is (some? (:error result)) (pr-str result))
      (is (not= :quoted-inline-function-source (:requirement (:error-data result)))))))

;; =============================================================================
;; 2. diagnose-parse-error's reactive hint, surfaced through
;;    build-iteration-history — the actual next-iteration prompt renderer.
;; =============================================================================

(def ^:private hint-marker
  "Diagnostic hint" )

(def ^:private hint-fix-marker
  "the substring OUR OWN production hint text carries, asserted verbatim
   (not a regex) — proves the hint text, not merely SOME 'Diagnostic hint'
   string, fired."
  "literal quoted `(fn ...)`")

(deftest build-iteration-history-surfaces-the-hint-via-error-data-keyword
  (testing "an entry carrying :error-data {:requirement
            :quoted-inline-function-source} gets the reactive hint in the
            rendered history, keyed on the ex-data keyword"
    (let [history [{:code "(emit-tree! ...)"
                    :error rlm-sandbox/quoted-inline-function-source-message
                    :error-data {:requirement :quoted-inline-function-source}
                    :result nil
                    :vars-created []}]
          out (build-iteration-history history)]
      (is (str/includes? out hint-marker))
      (is (str/includes? out hint-fix-marker)))))

(deftest build-iteration-history-surfaces-the-hint-via-message-fallback
  (testing "when only the message text survives (:error-data absent/nil), the
            hint still fires via a literal substring check against the
            exported constant — never a regex over prose"
    (let [history [{:code "(emit-tree! ...)"
                    :error rlm-sandbox/quoted-inline-function-source-message
                    :result nil
                    :vars-created []}]
          out (build-iteration-history history)]
      (is (str/includes? out hint-marker))
      (is (str/includes? out hint-fix-marker)))))

;; Orchestrator inspection addition: after a checkpoint resume the history is
;; rebuilt from durable iteration records, which keep ONLY a bounded
;; :error-excerpt of the ENHANCED error (no :error-data — the record is an
;; allowlist). The hint must still fire from that excerpt, composed through the
;; real enhancer and the real durable bound, with available vars present (the
;; enhancer appends them after the message).
(deftest build-iteration-history-surfaces-the-hint-from-a-durable-error-excerpt
  (let [enhanced (executor/format-error-with-suggestions
                  rlm-sandbox/quoted-inline-function-source-message
                  [:task :documents :findings :draft-report])
        excerpt (iteration-evidence/bound-text
                 enhanced iteration-evidence/error-excerpt-max-chars)
        out (build-iteration-history
             ;; the immutable iteration-record shape (executor iteration-record allowlist)
             [{:iteration-index 0
               :status :failure
               :code "(emit-tree! ...)"
               :error-class "clojure.lang.ExceptionInfo"
               :error-excerpt excerpt
               :variable-delta {:created-keys [] :updated-keys [] :removed-keys []}}])]
    (is (str/includes? excerpt rlm-sandbox/quoted-inline-function-source-message)
        "the whole constant fits inside the durable excerpt bound")
    (is (str/includes? out hint-fix-marker))))

(deftest build-iteration-history-leaves-unrelated-errors-without-the-closure-hint
  (testing "an unrelated error does not spuriously get the closure-rejection hint"
    (let [history [{:code "(final! {})"
                    :error "final! called with all empty values"
                    :result ""
                    :vars-created []}]
          out (build-iteration-history history)]
      (is (not (str/includes? out hint-fix-marker))))))

;; =============================================================================
;; 3. Preventive pitfalls bullet — scoped to checkpointed campaigns.
;; =============================================================================

(def ^:private pitfalls-bullet-marker
  "the exact substring of OUR OWN production pitfalls bullet, asserted
   verbatim — our own template text, not model-authored prose."
  "write every `:code` node's `:fn` as a literal quoted `(fn ...)` inside the `emit-tree!` call")

(deftest checkpointed-node-module-includes-the-preventive-pitfalls-bullet
  (testing "a checkpointed repl-researcher's generated module includes the
            closure-rejection preventive bullet in Common pitfalls"
    (let [node {:instruction "Do the task."
                :writes []
                :rlm {:checkpointed? true}}
          module (build-rlm-code-generation-module node {} [] {} {} {})]
      (is (str/includes? (:instructions module) pitfalls-bullet-marker)))))

(deftest non-checkpointed-node-module-omits-the-preventive-pitfalls-bullet
  (testing "a non-checkpointed repl-researcher's module does NOT carry the
            checkpointed-only bullet — durable-source-required? never gates
            a non-checkpointed campaign"
    (let [node {:instruction "Do the task."
                :writes []
                :rlm {:checkpointed? false}}
          module (build-rlm-code-generation-module node {} [] {} {} {})]
      (is (not (str/includes? (:instructions module) pitfalls-bullet-marker))))))
