(ns ai.obney.orc.orc-service.real-llm-tenant-isolation-e2e-test
  "Gated real-model cross-tenant journey for DET-E2E-120. Both tenants share
  one store and processor set and deliberately collide on human names."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.read-models]
            [ai.obney.orc.gepa.core.todo-processors]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- command! [ctx command]
  (cp/process-command
   (assoc ctx :command (merge {:command/id (random-uuid)
                               :command/timestamp (time/now)} command))))

(defn- colliding-workflow [sentinel]
  (sheet/workflow "det-e2e-120-colliding-workflow"
    (sheet/blackboard {:question :string :answer :string})
    (sheet/llm "colliding-leaf"
      :model live/openrouter-model
      :instruction (str "Answer with the exact private tenant sentinel " sentinel)
      :reads [:question] :writes [:answer])))

(defn- drain! [ch]
  (let [deadline (+ (System/currentTimeMillis) 180000)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[value _] (async/alts!! [ch (async/timeout remaining)])]
            (if (nil? value) acc (recur (conj acc value))))
          acc)))))

(defn- metric [example outputs]
  (if (and (string? (:answer outputs))
           (str/includes? (:answer outputs) (get example "sentinel")))
    1.0 0.0))

(defn- optimize-one! [ctx sheet-id sentinel]
  (gepa/optimize!
   ctx {:sheet-id sheet-id
        :trainset [{"question" "Return the private sentinel"
                    "sentinel" sentinel}]
        :valset [{"question" "Return the private sentinel"
                  "sentinel" sentinel}]
        :metric-fn metric
        :config {:max-metric-calls 1
                 :reflection-minibatch-size 1
                 :reflection-lm live/openrouter-model
                 :skip-perfect-score true}
        :inherit-from-previous false :block? true :timeout-ms 300000}))

(defn- mint-derived! [ctx sheet-id tick-id sentinel answer]
  (let [result (command!
                ctx {:command/name :ontology/mint-behavioral-subtree
                     :name "det-e2e-120-colliding-behavior"
                     :body {:capabilities ["Retain tenant-private evidence"]
                            :strengths [] :weaknesses []
                            :representative-uses [sentinel]
                            :avoid-when ["tenant identity is absent"]
                            :summary (str sentinel " derived " answer)
                            :version 1 :consolidated-from-event-count 1}
                     :provenance :agent-minted
                     :minted-by-sheet-id sheet-id
                     :minted-by-tick-id tick-id})]
    (->> (:command-result/events result)
         (some #(when (= :ontology/behavioral-subtree-minted (:event/type %))
                  (:target-id %))))))

(deftest det-e2e-120-real-llm-tenant-isolation-across-adaptive-stack
  (testing "colliding real-model artifacts stay inside their initiating tenant"
    (live/with-real-openrouter
      (live/register-openrouter!)
      (h/with-async-test-context [base]
        (let [tenant-a (random-uuid)
              tenant-b (random-uuid)
              ctx-a (assoc base :tenant-id tenant-a :llm-provider :openrouter)
              ctx-b (assoc base :tenant-id tenant-b :llm-provider :openrouter)
              sentinel-a "TENANT-A-PRIVATE-120"
              sentinel-b "TENANT-B-PRIVATE-120"
              sheet-a (sheet/build-workflow! ctx-a (colliding-workflow sentinel-a))
              sheet-b (sheet/build-workflow! ctx-b (colliding-workflow sentinel-b))
              stream-a (sheet/execute-stream ctx-a sheet-a {:question "private"}
                                             :timeout-ms 180000)
              stream-b (sheet/execute-stream ctx-b sheet-b {:question "private"}
                                             :timeout-ms 180000)
              result-a @(:result stream-a)
              result-b @(:result stream-b)
              envelopes-a (drain! (:events-ch stream-a))
              envelopes-b (drain! (:events-ch stream-b))]
          (is (= :success (:status result-a)) (pr-str result-a))
          (is (= :success (:status result-b)) (pr-str result-b))
          (is (str/includes? (get-in result-a [:outputs :answer]) sentinel-a))
          (is (str/includes? (get-in result-b [:outputs :answer]) sentinel-b))
          (is (not (str/includes? (pr-str result-a) sentinel-b)))
          (is (not (str/includes? (pr-str result-b) sentinel-a)))
          (is (not (str/includes? (pr-str envelopes-a) sentinel-b)))
          (is (not (str/includes? (pr-str envelopes-b) sentinel-a)))
          (doseq [call (live/assert-live-provenance! ctx-a (:trace-id result-a))]
            (is (= live/openrouter-model (:model call))))
          (doseq [call (live/assert-live-provenance! ctx-b (:trace-id result-b))]
            (is (= live/openrouter-model (:model call))))
          (let [behavior-a (mint-derived! ctx-a sheet-a (:trace-id result-a)
                                          sentinel-a (get-in result-a [:outputs :answer]))
                behavior-b (mint-derived! ctx-b sheet-b (:trace-id result-b)
                                          sentinel-b (get-in result-b [:outputs :answer]))
                optimization-a (optimize-one! ctx-a sheet-a sentinel-a)
                optimization-b (optimize-one! ctx-b sheet-b sentinel-b)
                opt-a (:optimization-id optimization-a)
                opt-b (:optimization-id optimization-b)
                tenant-a-sheet (sheet/get-sheet ctx-a sheet-a)
                tenant-b-sheet (sheet/get-sheet ctx-b sheet-b)
                export-a (sheet/export-sheet ctx-a sheet-a)
                export-b (sheet/export-sheet ctx-b sheet-b)]
            (is (= :completed (:status optimization-a)) (pr-str optimization-a))
            (is (= :completed (:status optimization-b)) (pr-str optimization-b))
            (is (= sheet-a sheet-b)
                "identical definitions deliberately collide on deterministic identity")
            (is (not= (:content-hash tenant-a-sheet)
                      (:content-hash tenant-b-sheet))
                "the shared identity still resolves to distinct tenant-local content")
            (is (= tenant-a-sheet (sheet/get-sheet ctx-a sheet-b)))
            (is (= tenant-b-sheet (sheet/get-sheet ctx-b sheet-a)))
            (is (empty? (gepa/get-optimization-summary ctx-a opt-b)))
            (is (empty? (gepa/get-optimization-summary ctx-b opt-a)))
            (is (some? (gepa/get-population-state ctx-a opt-a)))
            (is (some? (gepa/get-population-state ctx-b opt-b)))
            (is (str/includes? (:summary (ontology/get-description
                                          ctx-a :tree-fingerprint behavior-a))
                               sentinel-a))
            (is (str/includes? (:summary (ontology/get-description
                                          ctx-b :tree-fingerprint behavior-b))
                               sentinel-b))
            (is (not (str/includes? (pr-str export-a) sentinel-b)))
            (is (not (str/includes? (pr-str export-b) sentinel-a)))
            (is (not (str/includes? (pr-str (live/events ctx-a)) sentinel-b)))
            (is (not (str/includes? (pr-str (live/events ctx-b)) sentinel-a)))
            (is (= (count (live/events ctx-a))
                   (count (into [] (es/read (:event-store base)
                                           {:tenant-id tenant-a}))))
                "tenant projection count equals the independent tenant partition")
            (is (= (count (live/events ctx-b))
                   (count (into [] (es/read (:event-store base)
                                           {:tenant-id tenant-b})))))))))))
