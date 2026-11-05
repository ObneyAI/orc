(ns ai.obney.orc.orc-service.optional-field-names-test
  "A nested field's name in the type description the provider reads must be its real name.
   Rendering an optional field as `name?:` led a function-calling model to return the key as
   `name?` (and a field already named `x?` as `x??`), so the decoded output silently lost the
   value: measured on one production workload, 25 of 128 returns of one optional nested field and
   every return of a nested field whose own name ends in `?`. Optionality is stated in words,
   outside the name."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(def ^:private flatten-output-schema
  #'ai.obney.orc.orc-service.core.executor/flatten-output-schema)

(def ^:private decision
  [:map
   [:kind [:enum :answer :ask]]
   [:coverage {:optional true} [:vector [:map
                                         [:intent-id :string]
                                         [:explanation {:optional true} :string]]]]
   [:flagged? {:optional true} :boolean]])

(deftest optional-nested-fields-keep-their-exact-names
  (let [desc (executor/malli-schema->description decision)]
    (testing "presence control: every field is described"
      (is (str/includes? desc "kind:"))
      (is (str/includes? desc "coverage"))
      (is (str/includes? desc "explanation"))
      (is (str/includes? desc "flagged?")))
    (testing "no name is altered by a ? suffix"
      (is (not (str/includes? desc "coverage?")) desc)
      (is (not (str/includes? desc "explanation?")) desc)
      (is (not (str/includes? desc "flagged??")) desc))
    (testing "optionality is still stated"
      (is (str/includes? desc "coverage (optional):") desc)
      (is (str/includes? desc "explanation (optional):") desc)
      (is (str/includes? desc "flagged? (optional):") desc)
      (is (not (str/includes? desc "kind (optional)")) desc))))

(deftest a-flattened-output-description-carries-the-exact-nested-names
  (let [[field] (flatten-output-schema :outer [:map [:decision decision]])]
    (is (= :decision (:name field)) "presence control")
    (is (str/includes? (:description field) "coverage (optional):") (:description field))
    (is (not (str/includes? (:description field) "coverage?")) (:description field))))
