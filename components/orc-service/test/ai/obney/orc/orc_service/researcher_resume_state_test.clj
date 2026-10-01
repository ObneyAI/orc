(ns ai.obney.orc.orc-service.researcher-resume-state-test
  "RS7 Postgres stall root cause (2026-09-29): a checkpointed repl-researcher
   campaign silently stops making progress on Grain's Postgres v3 event
   store, never on the in-memory store. Root cause: `researcher-resume-state`
   verifies its durable V3 sandbox snapshot with a SHA-256 hash over a
   `canonical-value` form that used to distinguish Clojure lists from
   vectors by class. `ai.obney.grain.fressian-util.interface/decode`'s
   `deep-clojurize` step converts ANY `java.util.List`-satisfying value that
   is not already a `PersistentVector` into a vector via `(mapv ...)` — this
   fires even on an already-correctly-reconstructed Clojure `PersistentList`,
   because `PersistentList` implements `java.util.List` too. So a sandbox
   value that was a list when the write-time hash was computed comes back a
   vector when the read-time hash is recomputed, on Postgres only (the
   in-memory store returns the original object, no serialization). The old
   `:list`/`:vector` split then threw \"researcher sandbox full snapshot hash
   mismatch\" even though the value was `=`-equal — inside
   `execute-repl-researcher-node`'s future, before the frontier claim is
   attempted, which durable-worker-catch branch emits nothing (by design,
   see the Grain/ORC issue tracker discussion referenced from that catch) —
   so the campaign silently never schedules its next quantum.

   This is a Grain-classpath defect (fressian-util's `deep-clojurize`) that
   ORC cannot fix directly (`components/orc-service` never edits
   `~/.gitlibs/.../grain-core-v2`), so the fix here hardens
   `canonical-value` to not depend on a class distinction the store is free
   to change: lists and vectors both canonicalize under `:sequential`, and
   any `inst?` value canonicalizes to its `inst-ms` instead of a
   print-method-dependent `pr-str`, and every integer width canonicalizes by
   value (the same decode widens java.lang.Integer to java.lang.Long; the
   researcher's own tree-execution counters are Integers in memory).

   The brick test below round-trips through fressian-util itself, the layer
   that changes the classes, so it needs no database. The live proof against
   a real Postgres store lives in development/test
   (resume_state_postgres_roundtrip_test.clj)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.researcher-resume-state :as resume]
            [ai.obney.grain.fressian-util.interface :as fressian-util]))

;; =============================================================================
;; Pure unit coverage of canonical-value / sandbox-hash
;; =============================================================================

(deftest rs7-a-list-and-an-equal-vector-hash-the-same
  (testing "a durable store round trip may legitimately turn a list into a
            vector (see ns docstring); the hash must not depend on that"
    (is (= (resume/sandbox-hash {:a (list 1 2 3)})
           (resume/sandbox-hash {:a [1 2 3]})))
    (is (= (resume/sandbox-hash {:a (list)})
           (resume/sandbox-hash {:a []})))
    (is (= (resume/sandbox-hash {:a {:nested (list 1 (list 2 3))}})
           (resume/sandbox-hash {:a {:nested [1 [2 3]]}})))))

(deftest rs7-a-list-and-a-differently-shaped-vector-still-differ
  (testing "the normalization does not erase genuinely different values"
    (is (not= (resume/sandbox-hash {:a (list 1 2 3)})
              (resume/sandbox-hash {:a [1 2 3 4]})))
    (is (not= (resume/sandbox-hash {:a (list 1 2 3)})
              (resume/sandbox-hash {:a #{1 2 3}})))))

(deftest rs7-an-integer-and-an-equal-long-hash-the-same
  (testing "a durable store round trip normalizes every integral width to
            Long on decode; a Java-interop-produced java.lang.Integer at
            write time (e.g. a collection's .size()) must hash the same as
            the java.lang.Long the store hands back for the same value"
    (is (= (resume/sandbox-hash {:a (int 0)})
           (resume/sandbox-hash {:a (long 0)})))
    (is (= (resume/sandbox-hash {:a (int 42)})
           (resume/sandbox-hash {:a (long 42)})))
    (is (= (resume/sandbox-hash {:a (short 7)})
           (resume/sandbox-hash {:a (long 7)})))
    (is (= (resume/sandbox-hash {:a (bigint 999999999999999999999)})
           (resume/sandbox-hash {:a (biginteger 999999999999999999999)})))
    (is (= (resume/sandbox-hash {:nested {:nodes-failed (int 0)
                                           :nodes-succeeded (int 3)
                                           :nodes-total (int 3)}})
           (resume/sandbox-hash {:nested {:nodes-failed (long 0)
                                           :nodes-succeeded (long 3)
                                           :nodes-total (long 3)}})))))

(deftest rs7-different-integers-still-differ
  (is (not= (resume/sandbox-hash {:a (int 1)})
            (resume/sandbox-hash {:a (int 2)})))
  (testing "an integer and an equal-valued double remain distinct — = is
            false between them in Clojure, and the hash must not collide
            them into the same identity"
    (is (not= (resume/sandbox-hash {:a (int 5)})
              (resume/sandbox-hash {:a (double 5.0)})))))

(deftest rs7-equal-instants-hash-the-same-regardless-of-identity
  (testing "two independently constructed, equal instants must hash
            identically — pr-str on a bare java.time.Instant embeds the
            object's identity hash when nothing has registered a
            print-method for it, which is not a stable basis for a hash"
    (let [epoch-ms 1790684860265
          a (java.time.Instant/ofEpochMilli epoch-ms)
          b (java.time.Instant/ofEpochMilli epoch-ms)]
      (is (not (identical? a b)) "fixture sanity: two distinct objects")
      (is (= a b) "fixture sanity: equal instants")
      (is (= (resume/sandbox-hash {:at a})
             (resume/sandbox-hash {:at b})))))
  (testing "a java.util.Date and an equal java.time.Instant are different
            :inst values represented the same way, so they hash the same"
    (let [epoch-ms 1790684860265
          d (java.util.Date. ^long epoch-ms)
          i (java.time.Instant/ofEpochMilli epoch-ms)]
      (is (= (resume/sandbox-hash {:at d})
             (resume/sandbox-hash {:at i}))))))

(deftest rs7-different-instants-still-differ
  (is (not= (resume/sandbox-hash {:at (java.time.Instant/ofEpochMilli 1)})
            (resume/sandbox-hash {:at (java.time.Instant/ofEpochMilli 2)}))))

;; =============================================================================
;; Round trip through the store's own serialization layer (no database)
;; =============================================================================

(deftest a-sandbox-survives-the-stores-serialization-round-trip
  (let [sandbox {:memo "kept"
                 :iteration-reasonings (list "first reasoning")
                 :emitted-tree (list :sequence [:llm {:writes [:x]}])
                 :tree-stats {:nodes-total (int 3) :nodes-failed (int 0) :nodes-succeeded (int 3)}
                 :at (java.time.Instant/ofEpochMilli 1790682722826)}
        decoded (fressian-util/decode (fressian-util/encode sandbox))]
    (testing "non-vacuous: the round trip really changes the classes the old hash depended on"
      (is (vector? (:iteration-reasonings decoded)) "a list comes back a vector")
      (is (instance? Long (get-in decoded [:tree-stats :nodes-total])) "an Integer comes back a Long"))
    (is (= sandbox decoded) "the value is unchanged under =")
    (is (= (resume/sandbox-hash sandbox) (resume/sandbox-hash decoded))
        "the hash is computed over the value, so it survives the round trip")))

(deftest a-fact-saved-under-the-old-hash-still-hydrates
  (testing "upgrade safety: a full-snapshot fact whose hash was computed with the
            pre-upgrade preimage verifies; a hash matching neither preimage fails"
    (let [sandbox {:memo "kept" :count 3 :items [1 2]}
          legacy-hash @(resolve 'ai.obney.orc.orc-service.core.researcher-resume-state/legacy-sandbox-hash)
          fact (assoc (resume/encode-full {:version 2 :revision 1 :sandbox-vars sandbox})
                      :resulting-state-hash (legacy-hash sandbox))]
      (is (not= (legacy-hash sandbox) (resume/sandbox-hash sandbox))
          "non-vacuous: the two preimages differ for this sandbox")
      (is (= sandbox (:sandbox-vars (resume/hydrate nil fact))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"full snapshot hash mismatch"
            (resume/hydrate nil (assoc fact :resulting-state-hash "sha256:0000")))))))
