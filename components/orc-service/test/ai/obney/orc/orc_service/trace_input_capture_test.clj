(ns ai.obney.orc.orc-service.trace-input-capture-test
  "Phase 2 (honest evaluation signal): the repl-researcher / non-leaf
   synthesis node's real read values must flow into the eval trace so
   grounding judges don't score grounded answers as hallucinations.

   These exercise the pure capture helper extract-read-inputs that
   execute-repl-researcher-node uses to populate the completion event's
   :inputs (which assemble-execution-trace then prefers over the empty
   started inputs).

   execute-delegate-node uses the same helper for the same reason: a
   delegate is a non-leaf child, so the control node that starts it drops
   its reads — verified live in orc-sessions, where the synthesis path
   routes through a :delegate (\"intent\") node whose Source Grounding went
   from 0.0/\"empty context — hallucination\" to 0.7 once its real reads
   (:session :turns :user-message …) reached the judge."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]))

(def ^:private extract-read-inputs #'tp/extract-read-inputs)

(deftest extract-read-inputs-restricts-to-declared-reads
  (testing "only declared :reads keys are captured; others ignored"
    (is (= {:workspace-research "found 3 read models"
            :evidence-bundle ["a" "b"]}
           (extract-read-inputs
            [:workspace-research :evidence-bundle]
            {:workspace-research {:value "found 3 read models"}
             :evidence-bundle {:value ["a" "b"]}
             :unrelated {:value "should not appear"}})))))

(deftest extract-read-inputs-skips-missing-and-nil
  (testing "missing keys and nil values are dropped, not emitted as {} entries"
    (is (= {:present "x"}
           (extract-read-inputs
            [:present :absent :nilval]
            {:present {:value "x"}
             :nilval {:value nil}})))))

(deftest extract-read-inputs-empty-reads
  (testing "no reads -> empty map (control nodes stay {})"
    (is (= {} (extract-read-inputs [] {:a {:value "x"}})))
    (is (= {} (extract-read-inputs nil {:a {:value "x"}})))))

(deftest extract-read-inputs-truncates-large-strings
  (testing "large string reads are truncated with a marker; small values pass through"
    (let [big (apply str (repeat 25000 \x))
          out (extract-read-inputs [:doc :small] {:doc {:value big}
                                                  :small {:value "tiny"}})]
      (is (= "tiny" (:small out)))
      (is (< (count (:doc out)) (count big))
          "large value is shortened")
      (is (clojure.string/includes? (:doc out) "truncated")
          "truncation is signposted so a judge knows context was cut")
      (is (clojure.string/starts-with? (:doc out) (subs big 0 100))
          "the head of the evidence is preserved for grounding"))))
