(ns ai.obney.orc.orc-service.recursive-schema-snapshot-test
  "A built workflow is a snapshot of its blackboard schemas. The snapshot must
   stay a valid, reconstructable schema with a stable hash, including when a
   schema is recursive through a local registry, and must still resolve
   references to the global registry."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]))

(defschemas snapshot-test-schemas
  {::global-leaf [:map [:id :int] [:label :string]]})

(def ^:private nested-schema
  [:schema
   {:registry
    {::tree [:or :nil :string :int
             [:vector [:ref ::tree]]
             [:map-of :keyword [:ref ::tree]]]}}
   [:ref ::tree]])

(def deep-value {:a [1 "x" {:b [nil {:c [[[2]]]}]}]})

(defn write-deep [_] {:payload deep-value})
(defn write-invalid [_] {:payload {:a [1 (atom :not-allowed)]}})

(defn- workflow [name fn-sym]
  (sheet/workflow name
    (sheet/blackboard {:payload nested-schema})
    (sheet/code "writer" :fn (str "ai.obney.orc.orc-service.recursive-schema-snapshot-test/" fn-sym)
      :reads [] :writes [:payload])))

(defn- persisted-form [ctx sheet-id k]
  (:schema (get (sheet/get-blackboard-by-key ctx sheet-id) k)))

(deftest recursive-local-registry-builds-and-executes
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow! ctx (workflow "rss-exec" "write-deep"))
          r (sheet/execute ctx sid {} :timeout-ms 15000)]
      (is (= :success (:status r)) (pr-str r))
      (is (= deep-value (get-in r [:outputs :payload])) (pr-str r)))))

(deftest recursive-schema-violation-is-rejected
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow! ctx (workflow "rss-invalid" "write-invalid"))
          r (sheet/execute ctx sid {} :timeout-ms 15000)]
      (is (not= :success (:status r)) (pr-str r)))))

(deftest rebuilding-the-same-workflow-keeps-the-content-hash
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow! ctx (workflow "rss-hash" "write-deep"))
          h1 (:content-hash (sheet/get-sheet ctx sid))
          _ (sheet/build-workflow! ctx (workflow "rss-hash" "write-deep"))
          h2 (:content-hash (sheet/get-sheet ctx sid))]
      (is (string? h1))
      (is (= h1 h2)))))

(deftest the-hash-is-stable-across-independent-contexts
  (let [hash-in-fresh-ctx
        (fn []
          (h/with-async-test-context [ctx]
            (let [sid (sheet/build-workflow! ctx (workflow "rss-hash-x" "write-deep"))]
              (:content-hash (sheet/get-sheet ctx sid)))))]
    (is (= (hash-in-fresh-ctx) (hash-in-fresh-ctx)))))

(deftest the-persisted-form-is-a-valid-self-contained-schema
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow! ctx (workflow "rss-form" "write-deep"))
          form (persisted-form ctx sid :payload)
          rebuilt (m/schema form)]
      (is (some? form))
      (is (m/validate rebuilt deep-value))
      (is (not (m/validate rebuilt {:a [(atom 1)]}))))))

(deftest global-registry-references-are-snapshotted-resolved
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow!
               ctx
               (sheet/workflow "rss-global"
                 (sheet/blackboard {:leaf ::global-leaf})
                 (sheet/code "writer" :fn "clojure.core/identity"
                   :reads [] :writes [:leaf])))
          form (persisted-form ctx sid :leaf)]
      (is (= [:map [:id :int] [:label :string]] form)
          "the global reference is inlined, not left as a dangling keyword"))))

;; ---------------------------------------------------------------------------
;; The snapshot must accept and reject exactly what the original schema does.
;; ---------------------------------------------------------------------------

(defn- snapshot-agrees?
  "Build a one-key workflow over `schema`, read the persisted form back, and
   check it classifies every value exactly as the original schema does.
   Returns the list of disagreeing values."
  [wf-name schema values]
  (h/with-async-test-context [ctx]
    (let [sid (sheet/build-workflow!
               ctx
               (sheet/workflow wf-name
                 (sheet/blackboard {:payload schema})
                 (sheet/code "writer" :fn "clojure.core/identity"
                   :reads [] :writes [:payload])))
          rebuilt (m/schema (persisted-form ctx sid :payload))
          original (m/schema schema)]
      (vec (remove #(= (m/validate original %) (m/validate rebuilt %)) values)))))

(defn- local-rec [id leaf]
  [:schema {:registry {id [:or leaf [:vector [:ref id]]]}} [:ref id]])

(deftest same-id-in-two-local-registries-keeps-each-definition
  (let [schema [:map [:a (local-rec ::n :int)] [:b (local-rec ::n :string)]]
        values [{:a [1] :b ["x"]} {:a [[1]] :b [["x"]]}
                {:a ["x"] :b ["x"]} {:a [1] :b [1]} {:a 1 :b "x"}
                {:a [1 [2 [3]]] :b ["x" ["y"]]} {:a [1 ["x"]] :b ["x"]}]]
    (is (m/validate schema (first values)) "fixture: the original accepts mixed")
    (is (= [] (snapshot-agrees? "rss-shadow" schema values)))))

(deftest keyword-and-string-registry-ids-coexist
  (let [schema [:map [:a (local-rec ::t :int)] [:b (local-rec "s" :string)]]
        values [{:a [1] :b ["x"]} {:a ["x"] :b ["x"]} {:a [1] :b [1]}
                {:a [[1]] :b [["x"]]}]]
    (is (= [] (snapshot-agrees? "rss-mixed-ids" schema values)))))

(deftest nested-registry-inside-a-definition-is-kept
  (let [schema [:schema
                {:registry
                 {::outer [:or :int
                           [:vector [:ref ::outer]]
                           [:schema {:registry {::inner [:or :string [:vector [:ref ::inner]]]}}
                            [:ref ::inner]]]}}
                [:ref ::outer]]
        values [1 [1 [2]] "x" ["x" ["y"]] [1 "x"] :k [[1] ["x"]] nil]]
    (is (= [] (snapshot-agrees? "rss-nested" schema values)))))

(deftest rebuilding-shadowed-and-mixed-workflows-keeps-the-content-hash
  (let [schema [:map [:a (local-rec ::n :int)] [:b (local-rec ::n :string)]
                [:c (local-rec "s" :string)]]
        hash-of (fn []
                  (h/with-async-test-context [ctx]
                    (let [sid (sheet/build-workflow!
                               ctx
                               (sheet/workflow "rss-shadow-hash"
                                 (sheet/blackboard {:payload schema})
                                 (sheet/code "writer" :fn "clojure.core/identity"
                                   :reads [] :writes [:payload])))]
                      (:content-hash (sheet/get-sheet ctx sid)))))]
    (is (= (hash-of) (hash-of)))))
