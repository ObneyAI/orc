(ns ai.obney.orc.predict-rlm-redaction-tools.interface-test
  "Tests for predict-rlm-redaction-tools/apply-redactions.

   apply-redactions is the deterministic transform that takes per-page
   text + per-page redaction targets and produces redacted text + a
   structured RedactionResult-equivalent. The behavior tree the model
   emits will identify targets via vision LLM (per page), then invoke
   this code-node to apply them."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.predict-rlm-redaction-tools.interface :as redact]))

;; =============================================================================
;; Tracer bullet — one target on one page
;; =============================================================================

(deftest applies-one-target-on-one-page
  (testing "single target text is replaced with redaction block + total-redactions = 1"
    (let [page-texts ["John Smith works at Acme Corp."]
          targets [{:page 0 :text "John Smith" :category "person_name"
                    :reason "Full name of an individual"}]
          result (redact/apply-redactions {:inputs {:page-texts page-texts
                                                    :targets targets}})]
      (is (= 1 (:total-redactions result)))
      (is (= 1 (count (:redacted-text-per-page result))))
      (is (not (clojure.string/includes? (first (:redacted-text-per-page result))
                                          "John Smith"))
          "redacted page text must not contain the target string"))))

(deftest empty-targets-yields-untouched-text-zero-count
  (testing "empty :targets vector returns input text unchanged and zero count"
    (let [page-texts ["Hello world." "Another page."]
          result (redact/apply-redactions {:inputs {:page-texts page-texts
                                                    :targets []}})]
      (is (= 0 (:total-redactions result)))
      (is (= page-texts (:redacted-text-per-page result))
          "text vector is byte-identical when no targets are supplied")
      (is (= [] (:targets-applied result)))
      (is (= [] (:targets-missing result))))))

(deftest is-idempotent-on-second-pass
  (testing "running apply-redactions on already-redacted text doesn't double-redact"
    (let [page-texts ["John Smith works at Acme."]
          targets [{:page 0 :text "John Smith" :category "person_name" :reason "name"}]
          first-pass (redact/apply-redactions {:inputs {:page-texts page-texts
                                                        :targets targets}})
          ;; Feed the already-redacted text BACK as page-texts and try again
          second-pass (redact/apply-redactions
                        {:inputs {:page-texts (:redacted-text-per-page first-pass)
                                  :targets targets}})]
      (is (= 1 (:total-redactions first-pass))
          "first pass redacts the target")
      (is (= 0 (:total-redactions second-pass))
          "second pass over already-redacted text finds nothing")
      (is (= 1 (count (:targets-missing second-pass)))
          "the target text is no longer in the page; classified as missing")
      (is (= (:redacted-text-per-page first-pass)
             (:redacted-text-per-page second-pass))
          "text is byte-identical between passes"))))

(deftest page-summaries-have-counts-and-deduped-categories
  (testing "per-page summary shows correct count + deduped categories per page"
    (let [page-texts ["John Smith met Jane Doe at Acme on May 1, 2025."
                      "Phone: 555-1234. Fax: 555-9999. Email: x@y.com"
                      "No PII here."]
          targets [{:page 0 :text "John Smith" :category "person_name" :reason "name"}
                   {:page 0 :text "Jane Doe" :category "person_name" :reason "name"}
                   {:page 0 :text "May 1, 2025" :category "date" :reason "date"}
                   {:page 1 :text "555-1234" :category "phone_number" :reason "phone"}
                   {:page 1 :text "555-9999" :category "phone_number" :reason "fax-as-phone"}
                   {:page 1 :text "x@y.com" :category "email" :reason "email"}]
          result (redact/apply-redactions {:inputs {:page-texts page-texts
                                                    :targets targets}})
          [p0 p1 p2] (:page-summaries result)]
      (is (= 0 (:page p0)))
      (is (= 3 (:redaction_count p0)))
      (is (= ["date" "person_name"] (vec (:categories p0)))
          "page 0 categories deduped + sorted (person_name appears 2× in targets)")

      (is (= 1 (:page p1)))
      (is (= 3 (:redaction_count p1)))
      (is (= ["email" "phone_number"] (vec (:categories p1)))
          "page 1 categories deduped + sorted (phone_number appears 2× in targets)")

      (is (= 2 (:page p2)))
      (is (= 0 (:redaction_count p2)))
      (is (= [] (vec (:categories p2)))
          "page 2 has no redactions → empty categories vector"))))

(deftest missing-target-is-graceful-not-counted
  (testing "target text not found in page → tracked under :targets-missing, NOT counted"
    (let [page-texts ["Hello world"]
          targets [{:page 0 :text "John Smith" :category "person_name" :reason "name"}
                   {:page 0 :text "Hello" :category "custom" :reason "greeting"}]
          result (redact/apply-redactions {:inputs {:page-texts page-texts
                                                    :targets targets}})]
      (is (= 1 (:total-redactions result))
          "only the found target counts toward total-redactions")
      (is (= 1 (count (:targets-applied result))))
      (is (= 1 (count (:targets-missing result))))
      (is (= "John Smith" (-> result :targets-missing first :text))))))

(deftest applies-multiple-targets-across-multiple-pages
  (testing "multiple targets across pages → all replaced + count = sum"
    (let [page-texts ["John Smith works at Acme Corp."
                      "Contact: jane@example.com or 555-1234."
                      "DOB: March 15, 1985. SSN: 123-45-6789."]
          targets [{:page 0 :text "John Smith" :category "person_name" :reason "name"}
                   {:page 1 :text "jane@example.com" :category "email" :reason "email"}
                   {:page 1 :text "555-1234" :category "phone_number" :reason "phone"}
                   {:page 2 :text "March 15, 1985" :category "date" :reason "birthdate"}
                   {:page 2 :text "123-45-6789" :category "government_id" :reason "SSN"}]
          result (redact/apply-redactions {:inputs {:page-texts page-texts
                                                    :targets targets}})
          redacted (:redacted-text-per-page result)]
      (is (= 5 (:total-redactions result)))
      (is (= 3 (count redacted)))
      (doseq [{:keys [text]} targets]
        (let [in-any-redacted? (some #(clojure.string/includes? % text) redacted)]
          (is (not in-any-redacted?)
              (str "target " (pr-str text) " should NOT appear in any redacted page")))))))
