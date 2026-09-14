(ns ai.obney.orc.orc-service.rr20-occurrence-class-resolution-test
  "RR-20 exact behavioral change 3 (my own RED test, per the handoff's Cycle
   4 instruction): the post-emit enrichment writer resolves the tree-class
   by the OCCURRENCE pair `[source-sheet-id source-tick-id]`
   (`read-models/get-tree-class-for-occurrence`, SJ-1's `:occurrence->class`
   join), never by the bare, possibly-shared, sheet-id
   (`get-tree-class-for-sheet`).

   A static task-shape's sheet-id is shared across every turn that runs it.
   Before this slice, `enrich-tree-class-with-emitted-dsl!` resolved the
   class via `get-tree-class-for-sheet`, whose `:sheet->class` map holds only
   the MOST RECENT classification for a given sheet-id — so a bookend from
   an EARLIER turn on that static sheet would, once a LATER turn reclassified
   the same sheet to a sibling class, land its shape on the wrong class
   entirely. This test proves the fix directly: two turns share one static
   host sheet, are classified to two DIFFERENT classes, and each turn's
   bookend must enrich only its OWN class."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- stub-predict-fixture [f]
  (with-redefs [llm/predict (fn [_provider _module _inputs _options]
                              {:outputs {:operations []}
                               :usage {:total-tokens 1}
                               :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr20-occ-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc n {:keys [handler-fn topics]}]
                      (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                              :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- settle-until! [pred & {:keys [timeout-ms] :or {timeout-ms 4000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(def ^:private tree-a
  [:sequence [:llm {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private source-a "tree-a-source")
(def ^:private tree-b
  [:sequence [:llm {:reads [:doc] :writes [:notes]}]
   [:llm {:reads [:notes] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private source-b "tree-b-source")

(defn- classify! [ctx source-sheet-id source-tick-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id
           :source-node-id (random-uuid)
           :assigned-tree-id class-id
           :confidence 0.95
           :top-candidates []
           :reasoning "rr20-occurrence-resolution"
           :was-fresh-mint? true})))

(defn- capture-floor! [ctx class-id signature]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :granularity :tree-class
           :target-identifier class-id
           :deltas [{:operation :add :kind :representative-use
                     :content signature :episodes []
                     :from-legacy-corpus false
                     :evidence-basis :classification-signature}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- emit! [ctx source-sheet-id source-tick-id tree source]
  (cp/process-command
   (assoc ctx :command
          {:command/name :sheet/record-rlm-tree-execution-completion
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id (random-uuid)
           :tick-id (random-uuid)
           :trajectory []
           :total-usage {:total-tokens 0}
           :status :success
           :duration-ms 1
           :tree-fingerprint source
           :generated-tree tree
           :generated-tree-source source
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id})))

(defn- recommended-patterns [ctx class-id]
  (into #{}
        (comp (filter :recommended-pattern) (map :recommended-pattern))
        (:strengths (ontology/get-description ctx :tree-class class-id))))

(deftest two-turns-on-one-static-host-sheet-each-enrich-their-own-class
  (testing "the same (static) source-sheet-id classified to two different
            classes on two different turns: each turn's bookend enriches
            ONLY the class its own occurrence was classified to — never the
            sibling class the shared sheet was more recently reclassified to"
    (with-test-ctx [ctx]
      (let [static-sheet-id (random-uuid)
            tick-1 (random-uuid)
            tick-2 (random-uuid)
            class-a (random-uuid)
            class-b (random-uuid)]
        ;; Turn 1: static sheet classified to class-a.
        (classify! ctx static-sheet-id tick-1 class-a)
        (capture-floor! ctx class-a "turn 1 signature")
        (Thread/sleep 150)
        ;; Turn 2: the SAME static sheet, a DIFFERENT turn (tick-2),
        ;; classified to a DIFFERENT class (class-b). Under the pre-RR-20
        ;; sheet-only join, :sheet->class[static-sheet-id] now points at
        ;; class-b — turn 1's bookend, if it arrived after this, would
        ;; misattribute onto class-b under that join.
        (classify! ctx static-sheet-id tick-2 class-b)
        (capture-floor! ctx class-b "turn 2 signature")
        (Thread/sleep 150)
        ;; Turn 1's bookend arrives AFTER the sheet has been reclassified to
        ;; class-b — the adversarial ordering the sheet-only join gets wrong.
        (emit! ctx static-sheet-id tick-1 tree-a source-a)
        ;; Turn 2's bookend.
        (emit! ctx static-sheet-id tick-2 tree-b source-b)
        (is (settle-until! #(and (= #{source-a} (recommended-patterns ctx class-a))
                                 (= #{source-b} (recommended-patterns ctx class-b))))
            "each turn's shape landed on its OWN class")
        (is (= #{source-a} (recommended-patterns ctx class-a))
            "class-a offers only turn 1's shape")
        (is (= #{source-b} (recommended-patterns ctx class-b))
            "class-b offers only turn 2's shape — never turn 1's, despite sharing a host sheet")))))
