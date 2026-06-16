(ns ai.obney.orc.ontology.dt1-discovery-tree-test
  "DT1 — discovery-tree scaffold + orchestration tests.

   Verifies the ORCHESTRATION through public interfaces (NOT node internals,
   NOT prompt string contents):

     - The frozen inter-node contract (PRD M2) is exposed + stable.
     - The fixed-core sequence Profile -> Model -> Transform runs in order, and
       each node reads its predecessor's output via the blackboard (node-output).
     - A node failure surfaces honestly as :failed-at-<node> (no false green,
       no fabricated downstream steps).
     - build! is invoked UNCHANGED and its CQ verdict surfaces on the result.
     - The four branch points are present as NAMED stubs.
     - The discovery tree is composable as a behavior-tree node.

   Discipline: the LLM nodes are stubbed (via run-node-session! redef) so the
   orchestration is tested deterministically — the REAL-LLM proof is the
   DT1-scaffold live verify (Discipline #4). The V20 apply-step, compile path,
   and build! run REAL over a REAL temp CSV + REAL Grain in-memory event store.
   Domain-agnostic fixtures — no education/CIP/SOC specifics."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context (mirrors v20 / s17 pattern)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dt1-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

(defn- record-spec! [ctx ontology-id body]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body body})))

(defn- always-pass-judge [_]
  {:verdict :pass :reasoning "test judge" :evidence-uris [] :gaps []})

;; =============================================================================
;; Domain-agnostic CSV fixture: rows describe attribute-bearing entities in a
;; category — a generic shape, no domain baked in.
;; =============================================================================

(defn- write-fixture-csv! [n]
  (let [f (java.io.File/createTempFile "dt1-fixture" ".csv")
        path (.getAbsolutePath f)]
    (with-open [w (io/writer f)]
      (.write w "entity_id,category_code,score\n")
      (doseq [i (range 1 (inc n))]
        (.write w (str "E" i "," "C" (mod i 5) "," (* i 2) "\n"))))
    (.deleteOnExit f)
    path))

;; A real, sample-validated-shaped transform: one entity NODE per row carrying
;; its score as an attribute, one category NODE, and an edge connecting them.
(def ^:private fixture-transform-source
  (str "(fn [row]"
       "  (let [eid (get row \"entity_id\")"
       "        cat (get row \"category_code\")"
       "        score (get row \"score\")]"
       "    {:concept-drafts [{:uri (str \"entity:\" eid) :label eid"
       "                       :attributes {:score score}"
       "                       :evidence [{:source \"entity_id\" :quote eid}]}"
       "                      {:uri (str \"category:\" cat) :label cat"
       "                       :evidence [{:source \"category_code\" :quote cat}]}]"
       "     :relationship-drafts [{:source-uri (str \"entity:\" eid)"
       "                            :target-uri (str \"category:\" cat)"
       "                            :predicate \"belongsTo\" :confidence-class \"extracted\""
       "                            :evidence [{:source \"row\" :quote eid}]}]}))"))

;; Stub node outputs (the frozen PRD M2 contract shapes) — what the THIN nodes
;; would emit. The orchestration test feeds these via a run-node-session! redef
;; so the sequence + contract flow is tested deterministically.
(def ^:private stub-profile-output
  {:entity-candidates ["entity" "category"]
   :identifying-keys {"entity" ["entity_id"] "category" ["category_code"]}
   :scope-fields []
   :linking-keys ["category_code"]
   :grain-signals []
   :sample [{:entity_id "E1" :category_code "C1" :score 2}]})

(def ^:private stub-model-output
  {:entity-types [{:type "entity" :uri-keying-fields ["entity_id"]
                   :grain-strategy :canonical-row-filter}
                  {:type "category" :uri-keying-fields ["category_code"]
                   :grain-strategy :canonical-row-filter}]
   :scope-filter "all rows in scope"
   :edges [{:source-type "entity" :target-type "category" :predicate "belongsTo"}]})

(defn- stub-node-session
  "A run-node-session! stand-in that returns the right stubbed contract for each
   node-key, and records the inter-node inputs it received so a test can assert a
   node read its predecessor's output."
  [received-inputs]
  (fn [_ctx {:keys [node-name extra-inputs]}]
    (swap! received-inputs assoc node-name extra-inputs)
    (case node-name
      :profile   {:status :ok :output stub-profile-output}
      :model     {:status :ok :output stub-model-output}
      :transform {:status :ok :output {:transform-source fixture-transform-source
                                       :selector nil}}
      {:status :failed :error (str "unexpected node " node-name)})))

;; =============================================================================
;; The frozen contract (PRD M2) — exposed + stable
;; =============================================================================

(deftest frozen-contract-shapes-are-exposed
  (testing "the frozen PRD-M2 contract keys + grain-strategy enum are public
            and carry exactly the agreed shape (so DT2/DT3/DT4 build on them)"
    (is (= [:entity-candidates :identifying-keys :scope-fields
            :linking-keys :grain-signals :sample]
           dt/profile-contract-keys))
    (is (= [:entity-types :scope-filter :edges]
           dt/model-contract-keys))
    (is (= [:transform-source :selector]
           dt/transform-contract-keys))
    (is (= #{:canonical-row-filter :breakdown-as-entity}
           dt/valid-grain-strategies))))

;; =============================================================================
;; node-output — the inter-node contract read mechanism
;; =============================================================================

(deftest node-output-reads-a-nodes-emitted-contract-off-the-blackboard
  (testing "node-output returns a named node's emitted contract; nil when absent"
    (let [bb {:profile {:output stub-profile-output}}]
      (is (= stub-profile-output (dt/node-output bb :profile)))
      (is (nil? (dt/node-output bb :model))))))

;; =============================================================================
;; Branch points — present as NAMED stubs (DT8/DT9 fill them)
;; =============================================================================

(deftest branch-points-exist-as-named-no-op-stubs
  (testing "all four branch points are present, named, and no-op (taken? false)
            so DT8/DT9 fill them without restructuring"
    (let [ctx {}]
      (is (= :recovery (:branch (dt/recovery-branch-stub ctx {}))))
      (is (= :cq-reextract (:branch (dt/cq-reextract-branch-stub ctx {}))))
      (is (= :greenfield-vs-maintain
             (:branch (dt/greenfield-vs-maintain-branch-stub ctx {}))))
      (is (= :full-extract-vs-inline
             (:branch (dt/full-extract-vs-inline-branch-stub ctx {}))))
      (doseq [s [(dt/recovery-branch-stub ctx {})
                 (dt/cq-reextract-branch-stub ctx {})
                 (dt/greenfield-vs-maintain-branch-stub ctx {})
                 (dt/full-extract-vs-inline-branch-stub ctx {})]]
        (is (false? (:taken? s)) "a DT1 branch stub is a no-op")
        (is (= :stub-not-yet-implemented (:reason s)))))))

;; =============================================================================
;; The fixed-core sequence runs in order + honors the inter-node contract
;; =============================================================================

(deftest tree-runs-profile-model-transform-extract-build-and-surfaces-cq-verdict
  (testing "the fixed-core sequence runs, the contract flows node->node via the
            blackboard, V20 apply-step + build! run, and the CQ verdict surfaces"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            received (atom {})
            path (write-fixture-csv! 120)  ; > 100 so V20 spans >1 window
            source {:name :fx :type :csv :path path}]
        (record-spec! ctx oid {:purpose "dt1 test"
                               :competency-questions
                               ["Are there entity concepts?"
                                "Are entities connected to categories?"]})
        (Thread/sleep 100)
        (with-redefs [rlm-discovery/run-node-session! (stub-node-session received)]
          (let [result (dt/run-discovery-tree!
                        ctx {:ontology-id oid
                             :source source
                             :goal "Build an ontology of the entities in this source."
                             :judge-fn always-pass-judge
                             :exit-criterion {:pass-rate-min 0.5 :unknown-rate-max 0.6}})]
            ;; --- the fixed-core sequence ran in order ---
            (is (= [:profile :model :transform] (:nodes-run result))
                "fixed-core sequence runs Profile->Model->Transform in order")

            ;; --- inter-node contract flowed via the blackboard ---
            (is (= stub-profile-output (dt/node-output (:blackboard result) :profile)))
            (is (= stub-model-output (dt/node-output (:blackboard result) :model)))
            (is (= fixture-transform-source
                   (:transform-source (dt/node-output (:blackboard result) :transform))))

            ;; --- each node READ its predecessor's contract output ---
            (is (= stub-profile-output (get-in @received [:model :profile]))
                "the Model node received the Profile's output as :profile")
            (is (= stub-model-output (get-in @received [:transform :model-spec]))
                "the Transform node received the Model's output as :model-spec")

            ;; --- V20 apply-step ran over the FULL source (coverage) ---
            (is (= 120 (:rows-streamed (:full-extraction result)))
                "V20 apply-step streamed every row of the source")
            (is (zero? (:rows-errored (:full-extraction result))))

            ;; --- build! was invoked UNCHANGED and its CQ verdict surfaced ---
            (is (contains? #{:complete :failed-cq} (:build-status result))
                "build! ran to its CQ gate")
            (is (= (:build-status result) (:status result))
                "the tree result status IS build!'s status (PRD M7)")
            (is (some? (:graph-health result))
                "build!'s graph-health (the CQ verdict) surfaces on the tree result")

            ;; --- the graph was actually built (entity NODES + edges) ---
            (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
                  rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
              (is (pos? (count concepts)) "concept NODES landed in the projection")
              (is (pos? (count rels)) "relationship edges landed in the projection"))

            ;; --- branch points present as named stubs on the result ---
            (is (= #{:greenfield-vs-maintain :full-extract-vs-inline
                     :cq-reextract :recovery}
                   (set (keys (:branch-points result))))
                "all branch points present as named stubs on a completed run")))))))

;; =============================================================================
;; A node failure surfaces honestly (no false green)
;; =============================================================================

(deftest a-node-failure-surfaces-honestly-without-running-downstream
  (testing "when the Model node fails, the tree returns :failed-at-model with the
            error and does NOT run Transform/extract/build! (no false green)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            path (write-fixture-csv! 10)
            source {:name :fx :type :csv :path path}]
        (with-redefs [rlm-discovery/run-node-session!
                      (fn [_ctx {:keys [node-name]}]
                        (case node-name
                          :profile {:status :ok :output stub-profile-output}
                          :model   {:status :failed :error "model node blew up"}
                          (throw (ex-info "Transform should NOT run after model failure"
                                          {:node node-name}))))]
          (let [result (dt/run-discovery-tree!
                        ctx {:ontology-id oid :source source
                             :goal "Build an ontology."})]
            (is (= :failed-at-model (:status result)))
            (is (= "model node blew up" (:error result)))
            (is (= [:profile :model] (:nodes-run result))
                "Transform did not run after the Model failure")
            (is (contains? (:branch-points result) :recovery)
                "the recovery branch stub is surfaced on a node failure")))))))

;; =============================================================================
;; The discovery tree is composable as a behavior-tree node
;; =============================================================================

(deftest discovery-tree-is-a-composable-behavior-tree-node
  (testing "discovery-tree-node returns a :code leaf descriptor whose :fn runs
            the tree — so a larger behavior tree can sequence it (PRD US-24)"
    (with-ctx [ctx]
      (let [oid (random-uuid)
            path (write-fixture-csv! 10)
            source {:name :fx :type :csv :path path}
            node (dt/discovery-tree-node
                  {:source source
                   :goal "Build an ontology of the entities."
                   :write-key :discovery-result})]
        (is (= :code (:node-type node)) "it is a composable :code leaf")
        (is (= [:discovery-result] (:writes node)))
        (is (fn? (:fn node)))
        (record-spec! ctx oid {:purpose "dt1 compose test"
                               :competency-questions ["Are there entity concepts?"]})
        (Thread/sleep 100)
        (with-redefs [rlm-discovery/run-node-session! (stub-node-session (atom {}))]
          ;; Tick the node the way a parent tree's :code executor would: pass
          ;; {:inputs {:ontology-id oid} :ctx ctx} and read the declared write.
          (let [out ((:fn node) {:inputs {:ontology-id oid} :ctx ctx})
                tree-result (:discovery-result out)]
            (is (= [:profile :model :transform] (:nodes-run tree-result))
                "the composed node ran the full discovery sequence")
            (is (some? (:build-status tree-result))
                "the composed node surfaced build!'s status")))))))
