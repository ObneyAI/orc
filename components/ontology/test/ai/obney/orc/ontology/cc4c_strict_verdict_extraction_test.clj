(ns ai.obney.orc.ontology.cc4c-strict-verdict-extraction-test
  "CC-4c — the evidence guard's verdict extraction is STRICT, so the layer-2
   verifier's `Fails CLOSED` docstring is true of the code.

   MEASURED, NOT SUSPECTED. CC-4b drove the real `default-explanation-verifier`
   30 times against `qwen/qwen3.5-flash-02-23` over OpenRouter and captured
   every `:grounded-verdict` verbatim (orc-sessions
   `doc/build-timeline/evidence/cc4b/`). Three of those 30 calls returned
   GROUNDED for an explanation the model itself had judged ungrounded:

     * the forced `tool_choice` is dropped on 30/30 requests (dscloj emits snake
       `:tool_choice`; litellm's OpenRouter transform reads kebab
       `:tool-choice`), so the tool is only OFFERED — that key fix is ADR 0025
       and is deliberately NOT this slice's business;
     * on the 8/30 calls where the model did not volunteer the tool call,
       dscloj silently reissued in marker mode and the field came back carrying
       leaked `</think>` CoT and/or a template echo;
     * the old extractor, `(re-find #\"(?i)\\btrue\\b\" ...)`, then matched an
       incidental `true` token in that prose — often the model QUOTING the
       instruction or the adversarial explanation it was judging.

   This slice makes the guard safe EVEN WHEN THAT FALLBACK HAPPENS: the verdict
   is established only by an exact one-word `true`/`false`, and everything else
   is grounding-not-established.

   The corpus in `cc4c_verdict_corpus.edn` is mechanically extracted from the
   CC-4b evidence, verbatim and untruncated. NOTHING here is invented.

   Obligations covered (allium plan ids, specs/ontology.allium):
     rule-success.ExcludeUngroundedDelta
     rule-failure.ExcludeUngroundedDelta.1
     rule-success.RecordNothingWhenAllDeltasExcluded

   CONTRACT — never weaken a test to make it pass; report it as a finding."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.evidence-guard :as guard]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; The real corpus
;; ---------------------------------------------------------------------------

(def ^:private corpus
  "All 30 CC-4b verifier calls, verbatim."
  (-> "cc4c_verdict_corpus.edn" io/resource slurp edn/read-string))

(defn- payload
  "The verbatim `:grounded-verdict` the real workflow produced for one captured
   call, e.g. `(payload :arm1-ungrounded 1)`."
  [arm call]
  (let [hits (filterv #(and (= arm (:arm %)) (= call (:call %))) corpus)]
    (assert (= 1 (count hits)) (str "no unique captured call " arm " " call))
    (:grounded-verdict (first hits))))

;; ---------------------------------------------------------------------------
;; The seam: the REAL verifier, with only the LLM effect faked.
;;
;; `default-explanation-verifier` swallows exceptions and returns false, so a
;; test that merely asserts `false` can pass for entirely the wrong reason (a
;; namespace that failed to load looks identical to a verdict that failed
;; closed). Every call below therefore asserts that the faked workflow WAS
;; executed — that is what makes the false a measurement rather than an
;; artefact.
;; ---------------------------------------------------------------------------

(defn- verify-with
  "Runs the real `default-explanation-verifier` against a captured
   `:grounded-verdict` string. Returns `{:verdict bool :executed n}`."
  [grounded-verdict]
  (let [executed (atom 0)]
    (with-redefs [orc/build-workflow! (fn [_ctx _wf] (random-uuid))
                  orc/execute (fn [_ctx _sheet-id _inputs]
                                (swap! executed inc)
                                {:status :success
                                 :outputs {:grounded-verdict grounded-verdict}})]
      {:verdict (guard/default-explanation-verifier
                  {} {:explanation "an explanation under inspection"})
       :executed @executed})))

(defn- verdict-for [grounded-verdict]
  (let [{:keys [verdict executed]} (verify-with grounded-verdict)]
    (assert (= 1 executed)
            "the faked verifier workflow was never executed — this result is a
             harness artefact, not a verdict")
    verdict))

;; ---------------------------------------------------------------------------
;; CYCLE 1 — the three MEASURED fail-opens
;; ---------------------------------------------------------------------------

(deftest the-three-measured-fail-opens-now-fail-closed
  (testing "arm1 call 01 — the model wrote \"Verdict: `false`\" roughly six
            times; the field is a 5.4K leaked-CoT dump beginning with the
            template echo `{grounded-verdict}`"
    (is (false? (verdict-for (payload :arm1-ungrounded 1)))))

  (testing "arm3 call 03 — the field STARTS with the model's drafted `false`,
            then 2.8K of leaked CoT quoting the instruction and the adversarial
            explanation, `true` x6"
    (is (false? (verdict-for (payload :arm3-adversarial 3)))))

  (testing "arm3 call 06 — the field is `[my answer]` plus leaked CoT quoting
            the adversarial explanation; the model's stated verdict was `false`"
    (is (false? (verdict-for (payload :arm3-adversarial 6))))))

;; ---------------------------------------------------------------------------
;; CYCLE 3 — the guard against OVER-tightening
;;
;; A fix that fails everything closed is not a fix: it starves the learning loop
;; exactly as the whole-body validator did, which is the failure ADR 0021 exists
;; to end. `clean` is decided by raw string equality, NOT by the function under
;; test, so this cannot agree with a broken extractor by construction.
;; ---------------------------------------------------------------------------

(def ^:private clean-one-word-calls
  (filterv #(contains? #{"true" "false"} (:grounded-verdict %)) corpus))

(deftest every-clean-one-word-verdict-still-reads-exactly-as-written
  (testing "the 22 calls where the model volunteered the tool call"
    (is (= 22 (count clean-one-word-calls))
        "CC-4b measured 22/30 on the tool-call path; a different number means
         the fixture corpus is not the corpus that was measured")
    (doseq [{:keys [arm call grounded-verdict]} clean-one-word-calls]
      (is (= (= "true" grounded-verdict) (verdict-for grounded-verdict))
          (str arm " call " call " must still read as " grounded-verdict)))))

;; ---------------------------------------------------------------------------
;; CYCLE 4 — the shapes that carry no verdict at all
;; ---------------------------------------------------------------------------

(deftest a-field-carrying-no-verdict-establishes-nothing
  (testing "the template echo — arm2 call 04, VERBATIM. dscloj's marker parser
            took the FIRST `[[ ## grounded-verdict ## ]]` occurrence, which was
            an echo of the template inside the leaked CoT, so the field is the
            literal placeholder even though the model's final block was a clean
            `true`"
    (is (= "{grounded-verdict}" (payload :arm2-grounded 4))
        "the captured field is the placeholder itself, not a verdict")
    (is (false? (verdict-for (payload :arm2-grounded 4)))))

  (testing "an empty field establishes nothing"
    (is (false? (verdict-for ""))))

  (testing "a nil field establishes nothing — and must not throw, because a
            verifier that throws is a verifier that stops guarding"
    (is (false? (verdict-for nil))))

  (testing "SP-2 — the word the sio pin move leaves behind when a model refers
            back to its own marker. sio #11 recognises a marker after prose and
            the parser takes the LAST match, so
            `[[ ## grounded-verdict ## ]]\\ntrue\\n\\nsee [[ ## grounded-verdict
            ## ]] above` now arrives as the single word `above` instead of the
            whole prose block. The real answer is lost either way; what matters
            is that losing it reads CLOSED, and it does, because only an exact
            one-word true/false establishes anything"
    (is (false? (verdict-for "above")))))

;; ---------------------------------------------------------------------------
;; CYCLE 5 — case, whitespace and quoting are tolerated; prose is not
;;
;; The tolerance is bounded on purpose. Every one of these is still a ONE-WORD
;; answer; the moment a second word appears the model is thinking out loud, and
;; thinking out loud is what CC-4b caught being read as a verdict.
;; ---------------------------------------------------------------------------

(deftest a-one-word-verdict-is-read-through-case-whitespace-and-quoting
  (doseq [written ["true" "TRUE" "True" " true " "\n  true\n" "\"true\"" "'true'" "`true`"]]
    (testing (str "written as " (pr-str written))
      (is (true? (verdict-for written)))))

  (doseq [written ["false" "FALSE" " false " "\"false\""]]
    (testing (str "written as " (pr-str written))
      (is (false? (verdict-for written)))))

  (testing "one more word and it is prose, however affirmative it reads"
    (doseq [written ["true." "the answer is true" "true — the explanation cites
                      reranker_utils.py" "verdict: true" "[[ ## grounded-verdict ## ]]
                      true"]]
      (is (false? (verdict-for written))
          (str (pr-str written) " is not a one-word verdict")))))

;; ---------------------------------------------------------------------------
;; THE REPLAY — the whole CC-4b run, re-scored
;;
;; This is the measurement the slice is accountable for, made durable. Ground
;; truth is the ARM, which is a property of the fixture and not of any
;; extractor: arm 1 and arm 3 are known-UNGROUNDED (correct verdict `false`,
;; so `true` is a fail-OPEN); arm 2 is the known-GROUNDED control (correct
;; verdict `true`, so `false` is a fail-CLOSED).
;; ---------------------------------------------------------------------------

(def ^:private known-ungrounded-arms #{:arm1-ungrounded :arm3-adversarial})

(defn- replay
  "Re-scores all 30 captured payloads with `verdict-fn`, returning the calls
   that came out wrong, split by direction."
  [verdict-fn]
  (let [scored (for [{:keys [arm] :as c} corpus
                     :let [v (verdict-fn (:grounded-verdict c))
                           correct (not (known-ungrounded-arms arm))]]
                 (assoc c :v v :wrong? (not= v correct)))]
    {:fail-open (mapv (juxt :arm :call) (filter #(and (:wrong? %) (:v %)) scored))
     :fail-closed (mapv (juxt :arm :call) (filter #(and (:wrong? %) (not (:v %))) scored))}))

(deftest the-baseline-this-slice-is-measured-against-is-reproducible
  (testing "the OLD extractor, replayed, reproduces the verdict CC-4b recorded
            live for every one of the 30 calls — without this the comparison
            below would be measuring the replay harness, not the fix"
    (doseq [{:keys [arm call grounded-verdict cc4b-loose-verdict]} corpus]
      (is (= cc4b-loose-verdict
             (boolean (re-find #"(?i)\btrue\b" (str grounded-verdict))))
          (str arm " call " call))))

  (testing "and that baseline is 5 fail-open / 2 fail-closed"
    (let [{:keys [fail-open fail-closed]}
          (replay #(boolean (re-find #"(?i)\btrue\b" (str %))))]
      (is (= [[:arm1-ungrounded 1] [:arm1-ungrounded 9] [:arm1-ungrounded 10]
              [:arm3-adversarial 3] [:arm3-adversarial 6]]
             fail-open)
          "3 of these are the MECHANISM failures this slice removes (arm1 c01,
           arm3 c03, arm3 c06 — the model stated `false` and the guard said
           GROUNDED); the arm1 u4-fabricated-file pair is the model's own
           judgement on fabricated-but-concrete references, a MODEL-class scope
           limit of the layer-2 design and deliberately NOT in scope here")
      (is (= [[:arm2-grounded 4] [:arm2-grounded 6]] fail-closed)))))

(deftest strict-extraction-removes-every-mechanism-fail-open
  (let [{:keys [fail-open fail-closed]} (replay verdict-for)]

    (testing "0 mechanism fail-opens — the docstring's fail-CLOSED promise is
              now true of the code"
      (is (= [[:arm1-ungrounded 9] [:arm1-ungrounded 10]] fail-open)
          "the only surviving fail-opens are the two MODEL-class u4 calls,
           where the model itself answered `true` in a clean one-word field;
           widening the verifier's input to chase those is explicitly out of
           scope"))

    (testing "the cost: 2 additional fail-closed, all on the marker-fallback
              path, all in the known-grounded control arm"
      (is (= [[:arm2-grounded 3] [:arm2-grounded 4]
              [:arm2-grounded 5] [:arm2-grounded 6]]
             fail-closed)
          "c03 and c05 were 1.3K and 8.7K prose dumps whose correct verdict
           came only from a lucky `true` token; c04 is dscloj's wrong-occurrence
           placeholder, already failing closed before this change; c06 is the
           model's own strictness on a clean one-word `false`.
           Every mechanism-caused one of these exists ONLY because the forced
           tool call is dropped upstream — ADR 0025's `tool_choice` key fix
           removes the fallback path that produces them.")
      (is (= 3 (count (remove #{[:arm2-grounded 6]} fail-closed)))
          "one of the four is MODEL-class, not extraction"))

    (testing "every marker-fallback call now fails closed, which is the
              contract: 8 of 30 calls produced no one-word verdict at all"
      (is (= 8 (count (remove #(contains? #{"true" "false"} (:grounded-verdict %))
                              corpus))))
      (is (every? #(false? (verdict-for (:grounded-verdict %)))
                  (remove #(contains? #{"true" "false"} (:grounded-verdict %))
                          corpus))))))

;; ===========================================================================
;; The vertical slice: a captured payload, through the real command, to the
;; projection.
;;
;; The unit tests above prove the extractor. These prove it MATTERS — that the
;; string CC-4b captured decides whether a durable claim exists, read back from
;; the read model rather than from a return value.
;;
;; Spec obligations (specs/ontology.allium):
;;   rule-success.ExcludeUngroundedDelta            — the ungrounded delta is
;;                                                    excluded AND recorded
;;   rule-failure.ExcludeUngroundedDelta.1          — `ungrounded.count > 0`
;;                                                    fails, so nothing is
;;                                                    excluded
;;   rule-success.RecordNothingWhenAllDeltasExcluded — no claim, and the claim-set
;;                                                    version does not advance
;; ===========================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc4c-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                        :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- unsettled-occurrence!
  "An occurrence the DETERMINISTIC layer cannot read either way: scored, not
   starved, but with feedback too thin to judge. This is the ONLY shape that
   reaches the layer-2 verifier, so it is the only shape the extraction bug
   could ever have affected."
  [ctx]
  (let [sheet (random-uuid) tick (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet
              :node-id (random-uuid)
              :tick-id tick
              :judge-name "grounding"
              :judge-config {}
              :score 0.4
              :feedback "Thin, but it names the total."
              :dimensions []}))
    [sheet tick]))

(defn- record-one-delta!
  "Consolidates ONE delta citing `occurrence`, with the real layer-2 verifier
   in play and only the LLM effect faked — `verdict-payload` is what the
   verifier workflow returns as its `:grounded-verdict`."
  [ctx target occurrence verdict-payload]
  (let [executed (atom 0)]
    (with-redefs [orc/build-workflow! (fn [_ctx _wf] (random-uuid))
                  orc/execute (fn [_ctx _sheet-id _inputs]
                                (swap! executed inc)
                                {:status :success
                                 :outputs {:grounded-verdict verdict-payload}})]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-claim-deltas
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :granularity :tree-class
                :target-identifier target
                :deltas [{:operation :add :kind :weakness
                          :content "a claim resting on one thin evaluation"
                          :episodes [occurrence] :from-legacy-corpus false}]
                :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))
    @executed))

(deftest a-claim-resting-on-a-leaked-cot-verdict-never-becomes-durable
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          occurrence (unsettled-occurrence! ctx)
          version-before (ontology/get-claim-set-version ctx :tree-class target)
          ;; VERBATIM arm1 call 01: the 5.4K field the loose regex read as
          ;; GROUNDED while the model was writing "Verdict: `false`".
          executed (record-one-delta! ctx target occurrence (payload :arm1-ungrounded 1))]

      (is (= 1 executed)
          "layer 2 must actually have run — if it did not, everything below
           passes for the wrong reason")

      (testing "rule-success.RecordNothingWhenAllDeltasExcluded"
        (is (empty? (ontology/get-claims ctx :tree-class target))
            "the whole point: this exact string, in production, minted a claim")
        (is (= version-before (ontology/get-claim-set-version ctx :tree-class target))
            "a consolidation that produced no trustworthy knowledge must not
             look like one that did"))

      (testing "rule-success.ExcludeUngroundedDelta — recorded, not dropped"
        (let [excluded (ontology/get-excluded-evidence ctx :tree-class target)]
          (is (= 1 (count excluded)))
          (is (= :unverified-explanation (:reason (first excluded))))
          (is (= [occurrence] (vec (:episodes (first excluded))))
              "and it names the occurrence whose grounding could not be
               established, so the drop is diagnosable"))))))

(deftest a-clean-one-word-true-still-mints-the-claim-and-excludes-nothing
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          occurrence (unsettled-occurrence! ctx)
          ;; VERBATIM arm2 call 01: the tool-call path, one word, no CoT.
          executed (record-one-delta! ctx target occurrence (payload :arm2-grounded 1))]

      (is (= 1 executed))
      (is (= "true" (payload :arm2-grounded 1))
          "this fixture is the clean shape, exactly as captured")

      (testing "rule-failure.ExcludeUngroundedDelta.1 — `ungrounded.count > 0`
                is not met, so no exclusion is emitted"
        (is (empty? (ontology/get-excluded-evidence ctx :tree-class target))
            "a strict extractor that also rejected the clean case would be the
             over-rejection failure mode ADR 0021 exists to end"))

      (is (= 1 (count (ontology/get-claims ctx :tree-class target)))
          "the verified delta becomes a durable claim"))))
