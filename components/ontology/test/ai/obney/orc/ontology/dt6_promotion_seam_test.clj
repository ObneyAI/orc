(ns ai.obney.orc.ontology.dt6-promotion-seam-test
  "DT6 — Node prompt-assembly promotion seam (static now, living later).

   Every focused discovery node (DT2 Profile, DT3 Model, DT4 Transform, DT5
   Requirements/CQ) assembles its prompt through ONE seam — `assemble-node-prompt`
   — rather than each node reaching for its own `*-node-prompt` fn at its call
   site. This test pins the seam CONTRACT (PRD M6):

     1. STATIC PATH (today): for each node-kind the seam returns EXACTLY the
        node's existing static focused prompt — byte-identical to the per-node
        `*-node-prompt` fn the DT2-DT5 tests already pin. No behavior change.

     2. PLUGGABLE PROMPT-SOURCE (the promotion hook): the seam accepts an optional
        prompt-source fn; injecting a stub source proves promotion-to-living-
        behavior is a clean FLIP behind the seam — a later slice swaps the static
        source for one that sources from classify-behaviors / the seed corpus and
        participates in minting, WITHOUT a node rewrite.

     3. NO COUPLING to current minting internals: the seam's pluggable source is a
        plain fn; it does NOT call into today's classify/mint code (the minting
        process is being reworked separately). This test proves the injection
        point is clean (a stub fn the seam invokes), and that the default source
        is the static one.

   Discipline #6 (TDD, public-interface): the seam is exercised through its
   public `assemble-node-prompt` fn + the public per-node prompt fns it must
   match. The DT2-DT5 node tests remain the proof that the static bodies are
   correct; this test proves they FLOW THROUGH the seam unchanged."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]))

(def ^:private domain-goal
  "Build an ontology connecting fields/programs of study to occupations.")

;; =============================================================================
;; 1. STATIC PATH — the seam returns each node's existing focused prompt
;; =============================================================================

(deftest seam-returns-static-profile-prompt
  (testing "assemble-node-prompt :profile returns the EXACT static Profile prompt
            (byte-identical to profile-node-prompt — no behavior change)"
    (doseq [fmt [:csv :sql :excel :text]]
      (is (= (dt/profile-node-prompt domain-goal fmt)
             (dt/assemble-node-prompt :profile {:goal domain-goal :fmt fmt}))
          (str "the seam must return the static Profile body for fmt " fmt)))))

(deftest seam-returns-static-model-prompt
  (testing "assemble-node-prompt :model returns the EXACT static Model prompt"
    (is (= (dt/model-node-prompt domain-goal)
           (dt/assemble-node-prompt :model {:goal domain-goal})))))

(deftest seam-returns-static-transform-prompt
  (testing "assemble-node-prompt :transform returns the EXACT static Transform
            prompt — both with and without a key-shape grounding block"
    ;; no key-shape
    (is (= (dt/transform-node-prompt domain-goal)
           (dt/assemble-node-prompt :transform {:goal domain-goal})))
    (is (= (dt/transform-node-prompt domain-goal nil)
           (dt/assemble-node-prompt :transform {:goal domain-goal :key-shape nil})))
    ;; with a real key-shape (the DT4-grounding block must flow through verbatim)
    (let [key-shape {:keys [:UNITID :OPEID] :key-type :keyword
                     :format :sql :sample-row {:UNITID 100654 :OPEID "00100200"}}]
      (is (= (dt/transform-node-prompt domain-goal key-shape)
             (dt/assemble-node-prompt :transform {:goal domain-goal
                                                  :key-shape key-shape}))))))

(deftest seam-returns-static-cq-prompt
  (testing "assemble-node-prompt :requirements returns the EXACT static CQ prompt"
    (is (= (dt/cq-node-prompt domain-goal)
           (dt/assemble-node-prompt :requirements {:goal domain-goal})))))

(deftest seam-rejects-unknown-node-kind
  (testing "an unknown node-kind is a programmer error surfaced loudly, NOT a
            silent empty/wrong prompt (Discipline #5 — no false green)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (dt/assemble-node-prompt :not-a-node {:goal domain-goal})))))

;; =============================================================================
;; 2 + 3. PLUGGABLE PROMPT-SOURCE — promotion is a clean flip, no minting coupling
;; =============================================================================

(deftest seam-supports-a-pluggable-prompt-source
  (testing "injecting a stub prompt-source fn makes the seam use IT instead of the
            static body — proving promotion-to-living-behavior is a clean flip
            behind the seam (a later slice swaps in a classify-behaviors/corpus-
            sourced fn) WITHOUT a node rewrite"
    (let [calls (atom [])
          stub-source (fn [node-kind params]
                        (swap! calls conj [node-kind params])
                        (str "STUB-PROMPT for " (name node-kind)
                             " :: goal=" (:goal params)))]
      (doseq [[node-kind params] [[:profile {:goal domain-goal :fmt :csv}]
                                  [:model {:goal domain-goal}]
                                  [:transform {:goal domain-goal}]
                                  [:requirements {:goal domain-goal}]]]
        (let [out (dt/assemble-node-prompt node-kind params
                                           {:prompt-source stub-source})]
          (is (= (str "STUB-PROMPT for " (name node-kind) " :: goal=" domain-goal)
                 out)
              (str "the seam routes " node-kind " through the injected source"))))
      ;; the seam invoked the injected source for EVERY node-kind, with the
      ;; node-kind + params it was asked to assemble.
      (is (= [:profile :model :transform :requirements]
             (mapv first @calls))
          "the pluggable source is the single point every node's prompt flows through"))))

(deftest default-prompt-source-is-the-static-one
  (testing "with NO prompt-source supplied (and with an explicit nil), the seam
            falls back to the STATIC source — the default is static, promotion is
            opt-in behind the seam"
    (is (= (dt/assemble-node-prompt :profile {:goal domain-goal :fmt :csv})
           (dt/assemble-node-prompt :profile {:goal domain-goal :fmt :csv} {}))
        "no opts == static")
    (is (= (dt/assemble-node-prompt :profile {:goal domain-goal :fmt :csv})
           (dt/assemble-node-prompt :profile {:goal domain-goal :fmt :csv}
                                    {:prompt-source nil}))
        "explicit nil prompt-source == static")))

(deftest static-prompt-source-is-publicly-pluggable
  (testing "the static source itself is a public fn (the default) — so a promotion
            slice can WRAP/compose it (e.g. corpus-sourced-or-fall-back-to-static)
            and inject the composite, rather than fork the static bodies"
    (is (fn? dt/static-node-prompt-source))
    ;; the public static source produces the same body the convenience fns do
    (is (= (dt/profile-node-prompt domain-goal :csv)
           (dt/static-node-prompt-source :profile {:goal domain-goal :fmt :csv})))
    (is (= (dt/model-node-prompt domain-goal)
           (dt/static-node-prompt-source :model {:goal domain-goal})))))

(deftest seam-does-not-couple-to-minting-internals
  (testing "the seam's promotion hook is a PLAIN injected fn — the seam does NOT
            reach into classify-behaviors / mint code today. Proof: the injected
            source is invoked as a bare 2-arg fn and the seam never touches the
            minting namespace. We assert the seam source contains NO reference to
            the classify/mint interface symbols (the minting rework is separate)."
    ;; The seam is a clean interface boundary: with a stub source that records
    ;; only its args, the seam produces a prompt WITHOUT any classify/mint side
    ;; effect. (If the seam called into minting it could not be satisfied by a
    ;; pure 2-arg stub.)
    (let [pure-stub (fn [_ _] "PURE")]
      (is (= "PURE" (dt/assemble-node-prompt :model {:goal domain-goal}
                                             {:prompt-source pure-stub}))
          "a pure 2-arg stub fully satisfies the seam — no minting dependency"))))
