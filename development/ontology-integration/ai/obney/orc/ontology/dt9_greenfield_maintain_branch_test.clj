(ns ai.obney.orc.ontology.dt9-greenfield-maintain-branch-test
  "DT9 — greenfield-vs-maintain front-of-tree branch point.

   Verifies (through the PUBLIC interface, over a REAL Grain in-memory event
   store) the front-of-tree condition that selects:

     - GREENFIELD (no graph yet exists for the ontology-id) -> run the full
       discovery tree EXACTLY as DT1-DT8 verified (no regression).
     - MAINTAIN (a graph ALREADY exists for the ontology-id) -> an EXPLICIT,
       NAMED deferred stub: a clear `:maintain :deferred` surface that defers to
       the maintain handoff. NOT a silent gap; NOT a partial build.

   The branch is structured so the deferred maintain build (per the handoff) is
   ADDITIVE later: a thin condition flip + reusing DT7's `reconcile-graph!`
   (which already reconciles against current graph state). Existence is detected
   via the SAME current-graph projection read DT7 uses — no forked notion.

   Discipline #4: the LLM nodes are stubbed (run-node-session! redef) so the
   ROUTING is tested deterministically; the existence read, build!, V20, and
   compile run REAL over a REAL temp CSV + REAL Grain store. Domain-agnostic
   fixtures — no education/CIP/SOC specifics."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.interface.schemas]
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
;; Harness (mirrors dt1 / dt7)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dt9-test-" (random-uuid))
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

(defn- mint! [ctx oid {:keys [uri label description]}]
  (cp/process-command
   (assoc ctx :command {:command/name :ontology/create-concept
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :ontology-id oid :uri uri :label label
                        :description description :scope :custom
                        :broader [] :indicators []})))

(defn- always-pass-judge [_]
  {:verdict :pass :reasoning "test judge" :evidence-uris [] :gaps []})

(defn- write-fixture-csv! [n]
  (let [f (java.io.File/createTempFile "dt9-fixture" ".csv")
        path (.getAbsolutePath f)]
    (with-open [w (io/writer f)]
      (.write w "entity_id,category_code,score\n")
      (doseq [i (range 1 (inc n))]
        (.write w (str "E" i "," "C" (mod i 5) "," (* i 2) "\n"))))
    (.deleteOnExit f)
    path))

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

(defn- stub-node-session [received-inputs]
  (fn [_ctx {:keys [node-name extra-inputs]}]
    (swap! received-inputs assoc node-name extra-inputs)
    (case node-name
      :profile   {:status :ok :output stub-profile-output}
      :model     {:status :ok :output stub-model-output}
      :transform {:status :ok :output {:transform-source fixture-transform-source
                                       :selector nil}}
      {:status :failed :error (str "unexpected node " node-name)})))

;; =============================================================================
;; 1. graph-exists? — the existence read (reuses DT7's current-graph projection)
;; =============================================================================

(deftest graph-exists?-false-on-empty-true-on-populated
  (testing "graph-exists? reads the CURRENT graph projection (the DT7 read): an
            ontology-id with no concepts does NOT exist; one with concepts does."
    (with-ctx [ctx]
      (let [oid (random-uuid)]
        (is (false? (dt/graph-exists? ctx oid))
            "an empty graph does not exist yet (greenfield)")
        (mint! ctx oid {:uri "entity:E1" :label "E1" :description "a prior concept"})
        (is (true? (dt/graph-exists? ctx oid))
            "once a concept is in the projection the graph exists (maintain)")))))

;; =============================================================================
;; 2. The branch decider — greenfield vs maintain off real graph state
;; =============================================================================

(deftest branch-selects-greenfield-on-empty-graph
  (testing "An empty graph routes to GREENFIELD (taken? false — the full tree
            runs the default arm); the decider reads real graph state."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            b (dt/greenfield-vs-maintain-branch-stub ctx {:ontology-id oid})]
        (is (= :greenfield-vs-maintain (:branch b)))
        (is (= :greenfield (:selected b)))
        (is (false? (:taken? b)) "greenfield is the default arm — branch not taken")))))

(deftest branch-selects-maintain-on-populated-graph
  (testing "A pre-populated graph routes to MAINTAIN: the branch is TAKEN and the
            selection is :maintain with an explicit :deferred surface that names
            the reconcile path it will reuse (additive later, not a restructure)."
    (with-ctx [ctx]
      (let [oid (random-uuid)]
        (mint! ctx oid {:uri "entity:E1" :label "E1" :description "from an earlier build"})
        (let [b (dt/greenfield-vs-maintain-branch-stub ctx {:ontology-id oid})]
          (is (= :greenfield-vs-maintain (:branch b)))
          (is (= :maintain (:selected b)))
          (is (true? (:taken? b)) "maintain is taken when a graph already exists")
          (is (= :deferred (:maintain b))
              "maintain is an EXPLICIT deferred surface, not a silent gap")
          (is (= 'reconcile-graph! (:reuses b))
              "the deferred stub names the DT7 reconcile path the maintain build reuses"))))))

;; =============================================================================
;; 3. maintain-deferred-stub — the explicit, named deferred surface
;; =============================================================================

(deftest maintain-deferred-stub-is-explicit-and-named
  (testing "The maintain arm is an EXPLICIT named deferred stub — a clear
            `:status :maintain-deferred` surface a caller cannot mistake for a
            completed build or a silent no-op; it carries the ontology-id, the
            handoff reference, and the reconcile path it will reuse."
    (let [s (dt/maintain-deferred-stub {} {:ontology-id #uuid "00000000-0000-0000-0000-000000000001"
                                           :goal "g" :source {:type :csv :path "/x"}})]
      (is (= :maintain-deferred (:status s)))
      (is (= :maintain (get-in s [:branch-points :greenfield-vs-maintain :selected])))
      (is (= 'reconcile-graph! (:reuses s)) "names the reconcile path it reuses")
      (is (some? (:deferred s)) "carries a human-readable deferred note")
      (is (false? (boolean (:build-result s)))
          "no build was run — maintain is intentionally deferred, not partially built"))))

;; =============================================================================
;; 4. run-discovery-tree! routing — greenfield runs; maintain defers (no build)
;; =============================================================================

(deftest run-discovery-tree-greenfield-runs-the-full-tree-unchanged
  (testing "On an EMPTY (greenfield) graph the front-of-tree condition selects
            greenfield and the full DT1-DT8 sequence runs exactly as before —
            Profile->Model->Transform, V20 apply, build!, CQ verdict (no regression)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            received (atom {})
            path (write-fixture-csv! 120)
            source {:name :fx :type :csv :path path}]
        (record-spec! ctx oid {:purpose "dt9 greenfield test"
                               :competency-questions
                               ["Are there entity concepts?"
                                "Are entities connected to categories?"]})
        (Thread/sleep 100)
        (with-redefs [rlm-discovery/run-node-session! (stub-node-session received)]
          (let [result (dt/run-discovery-tree!
                        ctx {:ontology-id oid :source source
                             :goal "Build an ontology of the entities in this source."
                             :judge-fn always-pass-judge
                             :exit-criterion {:pass-rate-min 0.5 :unknown-rate-max 0.6}})]
            (is (not= :maintain-deferred (:status result))
                "greenfield did NOT route to the deferred stub")
            (is (= [:profile :model :transform] (:nodes-run result))
                "the full fixed-core sequence ran (greenfield unchanged)")
            (is (= :greenfield
                   (get-in result [:branch-points :greenfield-vs-maintain :selected]))
                "the front-of-tree branch recorded greenfield")
            (is (some? (:build-result result)) "build! ran on the greenfield path")
            (is (pos? (count (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))))
                "concepts landed (the greenfield graph was built)")))))))

(deftest run-discovery-tree-maintain-routes-to-deferred-stub-no-build
  (testing "On a PRE-POPULATED graph the front-of-tree condition selects maintain
            and returns the EXPLICIT deferred stub WITHOUT running any node or
            building — no silent no-op, no partial build (the maintain arm is
            intentionally deferred per the handoff)."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            path (write-fixture-csv! 10)
            source {:name :fx :type :csv :path path}]
        ;; A graph already exists for this ontology-id (an earlier build).
        (mint! ctx oid {:uri "entity:PRIOR" :label "Prior" :description "from an earlier build"})
        (let [before (count (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {})))]
          (with-redefs [rlm-discovery/run-node-session!
                        (fn [_ctx {:keys [node-name]}]
                          (throw (ex-info "NO node may run on the deferred maintain arm"
                                          {:node node-name})))]
            (let [result (dt/run-discovery-tree!
                          ctx {:ontology-id oid :source source
                               :goal "Maintain the ontology."})]
              (is (= :maintain-deferred (:status result))
                  "a pre-existing graph routes to the explicit deferred maintain stub")
              (is (= :maintain
                     (get-in result [:branch-points :greenfield-vs-maintain :selected]))
                  "the branch recorded the maintain selection")
              (is (nil? (:nodes-run result))
                  "NO discovery node ran on the deferred arm (no partial build)")
              (is (= 'reconcile-graph! (:reuses result))
                  "the deferred stub names the reconcile path the maintain build reuses")))
          (is (= before (count (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))))
              "the deferred arm built NOTHING — graph state unchanged"))))))
