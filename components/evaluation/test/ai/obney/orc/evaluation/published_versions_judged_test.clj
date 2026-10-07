(ns ai.obney.orc.evaluation.published-versions-judged-test
  "S11b: a run of a published workflow version is judged like a draft run. The
   run's node is the SAME node as its draft source for judging and performance;
   versions stay separated by node version. Every test drives the public flow:
   build -> attach -> publish -> execute-version -> assessments / performance."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [com.brunobonacci.mulog.core :as mulog-core]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private a-tree [:sequence [:llm {}] [:final {}]])

(defn produce-tree [_] {:generated-tree-raw a-tree})

(defn- fq [function-name]
  (str "ai.obney.orc.evaluation.published-versions-judged-test/" function-name))

(def ^:private structure {:structure {:type :heuristic-structural}
                          :structure-b {:type :heuristic-structural}})

(defn- host-workflow
  "A one-leaf host named `name` whose leaf carries the judges `judge-names`."
  [name judge-names]
  (sheet/workflow name
    (sheet/blackboard {:generated-tree-raw [:= a-tree]})
    (sheet/judges structure)
    (sheet/code "host" :fn (fq "produce-tree")
      :writes [:generated-tree-raw]
      :judges judge-names)))

(defn- node-id-named [ctx sheet-id node-name]
  (some #(when (= node-name (:name %)) (:id %))
        (sheet/get-nodes-for-sheet ctx sheet-id)))

(defn- requests-for-tick [ctx tick-id]
  (filterv #(= tick-id (:tick-id %))
           (into [] (es/read (:event-store ctx)
                             {:tenant-id (:tenant-id ctx)
                              :types #{:evaluation/assessment-requested}}))))

(defn- publish! [ctx sheet-id description]
  (let [r (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description description))]
    (is (not (h/is-anomaly? r)) (pr-str r))
    r))

(defn- await-requests
  "The requests for `tick-id` once `expected` of them are durable."
  [ctx tick-id expected]
  (is (h/settle-until! #(>= (count (requests-for-tick ctx tick-id)) expected)
                       :timeout-ms 15000)
      (str "expected " expected " assessment requests for the published run"))
  (requests-for-tick ctx tick-id))

(deftest a-published-run-is-judged-by-the-source-nodes-attachment
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (host-workflow "s11b-1" ["structure"]))
          source-id (node-id-named ctx sheet-id "host")
          _ (publish! ctx sheet-id "v1")
          result (sheet/execute ctx sheet-id {} :use-version 1)
          requests (await-requests ctx (:trace-id result) 1)]
      (is (= :success (:status result)))
      (is (= 1 (:executed-version result)))
      (is (= 1 (count requests)) "the leaf's completion is assessed")
      (is (= source-id (:node-id (first requests)))
          "the request names the SOURCE (draft) node")
      (is (some? (:run-node-id (first requests)))
          "the run's own node id is kept for evidence")
      (is (not= source-id (:run-node-id (first requests)))))))

(deftest a-judge-attached-after-publishing-monitors-the-published-version
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (host-workflow "s11b-2" ["structure"]))
          source-id (node-id-named ctx sheet-id "host")
          _ (publish! ctx sheet-id "v1")
          first-run (sheet/execute ctx sheet-id {} :use-version 1)
          _ (await-requests ctx (:trace-id first-run) 1)
          attached (h/run-and-apply!
                    ctx (h/make-set-node-judges-command sheet-id source-id ["structure" "structure-b"]))
          second-run (sheet/execute ctx sheet-id {} :use-version 1)
          requests (await-requests ctx (:trace-id second-run) 2)]
      (is (not (h/is-anomaly? attached)) (pr-str attached))
      (is (= #{"structure" "structure-b"} (set (map :judge-name requests)))
          "both judges assess the same published run")
      (is (= #{source-id} (set (map :node-id requests)))))))

(deftest versions-of-a-published-node-are-separate-with-a-rollup
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (host-workflow "s11b-3" ["structure"]))
          source-id (node-id-named ctx sheet-id "host")
          _ (publish! ctx sheet-id "v1")
          _ (h/run-and-apply! ctx {:command/name :sheet/set-node-instruction
                                   :command/id (random-uuid)
                                   :command/timestamp (ai.obney.grain.time.interface/now)
                                   :sheet-id sheet-id :node-id source-id
                                   :instruction "Answer more carefully."})
          _ (publish! ctx sheet-id "v2")
          v1-run (sheet/execute ctx sheet-id {} :use-version 1)
          v2-run (sheet/execute ctx sheet-id {} :use-version 2)
          draft-run (sheet/execute ctx sheet-id {} :force-draft true)
          _ (await-requests ctx (:trace-id v1-run) 1)
          _ (await-requests ctx (:trace-id v2-run) 1)
          _ (await-requests ctx (:trace-id draft-run) 1)
          perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id source-id})
          by-number (into {} (map (juxt :version-number identity)) (:versions perf))]
      (is (= 3 (count (:versions perf))) "v1, v2 and the draft are separate node versions")
      (is (= 3 (count (set (map :node-version (:versions perf))))) "three different hashes")
      (is (= #{1 2 nil} (set (keys by-number)))
          "each published node version names its sheet version; the draft has none")
      (is (every? #(= 1 (:total (first (:judges %)))) (:versions perf)))
      (is (= 3 (:total (first (:rollup perf)))) "the rollup spans every version"))))


;; -----------------------------------------------------------------------------
;; Legacy snapshots: published before snapshots recorded their draft nodes.
;; -----------------------------------------------------------------------------

(defn- strip-source-ids [tree]
  (cond-> (dissoc tree :source-node-id)
    (:children tree) (update :children #(mapv strip-source-ids %))))

(defn- publish-legacy!
  "Publish `snapshot` as the next version of the sheet, exactly as a publish
   made before source node ids were recorded would have stored it."
  [ctx sheet-id version-number snapshot]
  (let [snapshot-id (random-uuid)
        r (es/append (:event-store ctx)
                     {:tenant-id (:tenant-id ctx)
                      :events [(es/->event
                                {:type :sheet/version-published
                                 :tags #{[:sheet sheet-id] [:version snapshot-id]}
                                 :body {:sheet-id sheet-id
                                        :snapshot-id snapshot-id
                                        :version-number version-number
                                        :snapshot snapshot}})]})]
    (is (not (:cognitect.anomalies/category r)) (pr-str r))))

(defn- legacy-snapshot-of-v1 [ctx sheet-id f]
  (-> (rm/get-version ctx sheet-id 1) :snapshot
      (update :nodes (comp f strip-source-ids))))

(defn- two-leaf-workflow [name]
  (sheet/workflow name
    (sheet/blackboard {:generated-tree-raw [:= a-tree]})
    (sheet/judges structure)
    (sheet/sequence "main"
      (sheet/code "host" :fn (fq "produce-tree")
        :writes [:generated-tree-raw] :judges ["structure"])
      (sheet/code "canary" :fn (fq "produce-tree")
        :writes [:generated-tree-raw] :judges ["structure"]))))

(defmacro ^:private capturing-log [[logs] & body]
  `(let [~logs (atom [])]
     (with-redefs [mulog-core/log* (fn [_# event-name# pairs#]
                                     (swap! ~logs conj {:event event-name#
                                                        :pairs (apply hash-map pairs#)}))]
       ~@body)))

(deftest a-legacy-snapshot-is-judged-where-names-identify-the-draft-node
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (two-leaf-workflow "s11b-4a"))
          host-id (node-id-named ctx sheet-id "host")
          canary-id (node-id-named ctx sheet-id "canary")
          _ (publish! ctx sheet-id "v1")
          _ (publish-legacy! ctx sheet-id 2 (legacy-snapshot-of-v1 ctx sheet-id identity))
          result (sheet/execute ctx sheet-id {} :use-version 2)
          requests (await-requests ctx (:trace-id result) 2)]
      (is (= 2 (:executed-version result)))
      (is (= #{host-id canary-id} (set (map :node-id requests)))
          "each node of the legacy snapshot is recovered by its unique name"))))

(deftest a-legacy-snapshot-with-duplicate-names-is-not-guessed
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (two-leaf-workflow "s11b-4b"))
          canary-id (node-id-named ctx sheet-id "canary")
          _ (publish! ctx sheet-id "v1")
          duplicate-host (fn [main]
                           (let [[host canary] (:children main)]
                             (assoc main :children [host host canary])))
          _ (publish-legacy! ctx sheet-id 2 (legacy-snapshot-of-v1 ctx sheet-id duplicate-host))]
      (capturing-log [logs]
        (let [result (sheet/execute ctx sheet-id {} :use-version 2)
              ;; the canary completes after both duplicates, and the judging
              ;; stage handles completions in order: once it is assessed the
              ;; duplicates' completions have been handled too.
              requests (await-requests ctx (:trace-id result) 1)
              loud (filter #(= :ai.obney.orc.orc-service.core.runtime/published-nodes-cannot-be-judged
                               (:event %))
                           @logs)]
          (is (= :success (:status result)))
          (is (= [canary-id] (mapv :node-id requests))
              "only the unambiguous node is judged; the same-named pair is not attributed to the draft node")
          (is (= 1 (count loud)) "the run says loudly that nodes cannot be judged")
          (is (= ["host" "host"] (:node-names (:pairs (first loud))))))))))

(deftest a-delegate-in-a-published-version-is-judged
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "s11b-5-child"
                      (sheet/blackboard {:generated-tree-raw [:= a-tree]})
                      (sheet/code "child-work" :fn (fq "produce-tree")
                        :writes [:generated-tree-raw])))
          sheet-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "s11b-5"
                      (sheet/blackboard {:generated-tree-raw [:= a-tree]})
                      (sheet/judges structure)
                      (sheet/delegate "child" :target-sheet-id child-id
                        :writes [:generated-tree-raw]
                        :judges ["structure"])))
          delegate-id (node-id-named ctx sheet-id "child")
          draft (sheet/execute ctx sheet-id {} :force-draft true)
          draft-requests (await-requests ctx (:trace-id draft) 1)
          _ (publish! ctx sheet-id "v1")
          result (sheet/execute ctx sheet-id {} :use-version 1)
          requests (await-requests ctx (:trace-id result) 1)]
      (is (= [delegate-id] (mapv :node-id draft-requests)) "a draft run assesses the delegate")
      (is (= :success (:status result)))
      (is (= [delegate-id] (mapv :node-id requests))
          "a published run assesses the delegate as the draft delegate node"))))
