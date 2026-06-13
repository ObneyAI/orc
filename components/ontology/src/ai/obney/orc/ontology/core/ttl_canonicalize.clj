(ns ai.obney.orc.ontology.core.ttl-canonicalize
  "S09 — canonical triple-set diff for the G1 round-trip gate.

   Two TTL strings are semantically equivalent iff their RDF
   triple-sets are identical AFTER:
     - prefix expansion (rdflib does this)
     - literal lexicalization (rdflib normalizes datatypes; e.g.,
       `\"42\"^^xsd:integer` and bare `42` collapse; `\"2026-01-01T00:00:00Z\"`
       and `\"2026-01-01T00:00:00+00:00\"` collapse on xsd:dateTime)
     - blank-node canonical labelling (URDNA2015 via rdflib)
     - whitespace / triple ordering (sort the N-Triples lines)

   Approach (prototype-verified): shell out to python3+rdflib for parsing
   and URDNA2015 canonicalization — the most battle-tested implementation
   reachable on the dev classpath; rdflib 7.5.0 is the same parser the
   S06 grill verified the reified-on-demand format against. The output is
   sorted N-Triples (one triple per line), which is a stable byte-form
   for the same triple-set.

   The diff over canonical N-Triples is grouped to be ROOT-CAUSE-READY:
     - Named-node triples render as-is.
     - Blank-node-rooted triples group by structural signature (the
       sorted (predicate, object-or-BN-marker) tuple), so when ONE field
       of a reified rdf:Statement changes the diff shows the WHOLE record
       on each side instead of N independent bnode-relabelled lines.
     - Named-node lexical drift (same (s, p), different o) extracts into
       its own LEXICAL MISMATCHES section.

   PUBLIC API:
     (canonicalize-ttl ttl-string)            ; → sorted N-Triples string
                                              ; or {::anom/category ::anom/incorrect :anomaly/message ...}
     (semantic-diff ttl-a ttl-b)              ; → {:equivalent? bool :diff string}
                                              ; the :diff key is the
                                              ; root-cause-ready report
                                              ; printed by failed
                                              ; G1 assertions."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [cognitect.anomalies :as anom])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Canonicalizer python script — emitted to a temp file at load time so
;; the Clojure namespace ships standalone.
;; =============================================================================

(def ^:private canonicalize-script
  "Python script that parses TTL via rdflib and emits canonical sorted
   N-Triples on stdout. Exits 3 on parse error (with cause+position on
   stderr); exit 2 on usage error. Embedded as a string so test harness
   does not depend on a separate file shipped alongside this namespace."
  (str
   "import sys\n"
   "import rdflib\n"
   "from rdflib import Graph\n"
   "from rdflib.compare import to_canonical_graph\n"
   "if len(sys.argv) != 2:\n"
   "    print('usage: <script> <input.ttl>', file=sys.stderr); sys.exit(2)\n"
   "g = Graph()\n"
   "try:\n"
   "    g.parse(sys.argv[1], format='turtle')\n"
   "except Exception as e:\n"
   "    print('PARSE-ERROR: ' + type(e).__name__ + ': ' + str(e), file=sys.stderr)\n"
   "    sys.exit(3)\n"
   "canonical = to_canonical_graph(g)\n"
   "lines = []\n"
   "for s, p, o in canonical:\n"
   "    lines.append(s.n3() + ' ' + p.n3() + ' ' + o.n3() + ' .')\n"
   "lines.sort()\n"
   "sys.stdout.write('\\n'.join(lines))\n"
   "sys.stdout.write('\\n')\n"))

(def ^:private ^:const python3-cmd "python3")

(defn- write-temp! [^String suffix ^String content]
  (let [path (Files/createTempFile "orc-s09-" suffix (make-array FileAttribute 0))
        f (.toFile path)]
    (.deleteOnExit f)
    (Files/write path (.getBytes content StandardCharsets/UTF_8)
                 (make-array java.nio.file.OpenOption 0))
    f))

(defn canonicalize-ttl
  "Parse `ttl-string` with rdflib, canonicalize blank nodes (URDNA2015),
   and emit sorted N-Triples (one triple per line). Returns the
   canonical-string on success, or an anomaly map on parse failure.

   The anomaly carries `:anomaly/message` (the parser's error text with
   line/col position when rdflib provides one), suitable for the
   adversarial 'malformed TTL fails loudly with a position-bearing
   error' acceptance test."
  [ttl-string]
  (let [script-file (write-temp! ".py" canonicalize-script)
        ttl-file (write-temp! ".ttl" (or ttl-string ""))
        {:keys [exit out err]} (sh/sh python3-cmd
                                      (.getAbsolutePath script-file)
                                      (.getAbsolutePath ttl-file))]
    (cond
      (zero? exit) out

      (= 3 exit)
      ;; rdflib parse error — preserve the full message verbatim
      ;; (includes the line/col when present).
      {::anom/category ::anom/incorrect
       :anomaly/message (str/trim (or err ""))
       :anomaly/kind :ttl/parse-error}

      :else
      {::anom/category ::anom/fault
       :anomaly/message (str "canonicalize-ttl: unexpected exit " exit
                             " stderr=" (str/trim (or err "")))})))

;; =============================================================================
;; Diff grouping — pure Clojure.
;; =============================================================================

(def ^:private triple-re
  ;; Match `<subject> <predicate> <object> .` where subject can be a bnode
  ;; (`_:...`) or a URI (`<...>`), predicate is always a URI, and object
  ;; can be a URI, a bnode, or a literal (which may contain spaces inside
  ;; quotes followed by an @lang or ^^datatype suffix).
  ;; The N-Triples form rdflib emits via term.n3() is well-formed,
  ;; so a simple split-on-first/second/last-space suffices.
  nil)

(defn- parse-canonical-line
  "Parse one canonical N-Triples line `'<s> <p> <o> .'` into [s p o].
   Returns nil for blank lines."
  [^String line]
  (let [trimmed (str/trim line)]
    (when-not (str/blank? trimmed)
      ;; The line ends with ' .' — strip that, then split into
      ;; subject / predicate / object. Subject and predicate cannot
      ;; contain unescaped spaces (URIs are bracketed, bnodes are
      ;; `_:label`). The object MAY contain spaces inside quoted
      ;; literals, so we walk from the front.
      (let [stripped (if (str/ends-with? trimmed " .")
                       (subs trimmed 0 (- (count trimmed) 2))
                       trimmed)
            sp1 (.indexOf stripped (int \space))
            subj (subs stripped 0 sp1)
            rest1 (subs stripped (inc sp1))
            sp2 (.indexOf rest1 (int \space))
            pred (subs rest1 0 sp2)
            obj (subs rest1 (inc sp2))]
        [subj pred obj]))))

(defn parse-canonical-ntriples
  "Parse a canonical N-Triples string (the output of `canonicalize-ttl`)
   into a vector of [subject predicate object] tuples."
  [nt-string]
  (->> (str/split-lines nt-string)
       (keep parse-canonical-line)
       vec))

(defn- bnode? [^String term] (str/starts-with? term "_:"))

(defn- shape-of
  "Replace bnode objects with a `_:BN` marker so two bnode records that
   differ ONLY in their relabelled outgoing-bnode object still compare
   equal via structural signature."
  [pred obj]
  (if (bnode? obj) [pred "_:BN"] [pred obj]))

(defn- group-bnode-records
  "Split triples into `[named-triples bnode-records]`.

   `named-triples` is `[[s p o] ...]` for triples whose subject is a
   named node. `bnode-records` is `[sig ...]` where each `sig` is the
   sorted vector of `[predicate object-or-BN-marker]` pairs for one
   bnode's outgoing triples. Comparing the COLLECTION of `sig`s on each
   side answers 'do both sides have the same bnode records modulo
   relabelling'."
  [triples]
  (let [by-subj (reduce (fn [acc [s p o]] (update acc s (fnil conj []) [p o]))
                        {}
                        triples)
        named   (for [[s pos] by-subj
                      :when (not (bnode? s))
                      [p o]   pos]
                  [s p o])
        records (for [[s pos] by-subj
                      :when (bnode? s)]
                  (->> pos
                       (map (fn [[p o]] (shape-of p o)))
                       sort
                       vec))]
    [(vec named) (vec records)]))

(defn- multiset-diff
  "Return [missing extra] — multiset-style differences of two collections."
  [left right]
  (let [lc (frequencies left)
        rc (frequencies right)
        missing (for [[v c] lc
                      :let [diff (- c (get rc v 0))]
                      :when (pos? diff)
                      _ (range diff)]
                  v)
        extra (for [[v c] rc
                    :let [diff (- c (get lc v 0))]
                    :when (pos? diff)
                    _ (range diff)]
                v)]
    [(vec missing) (vec extra)]))

(defn- extract-lexical-mismatches
  "Pull (s, p, l-o, r-o) entries out of the named missing/extra lists
   where the (s, p) pair appears exactly once on each side with a
   different object. These are 'lexical mismatches' (e.g., `kg` vs
   `Kg`); presenting them separately is more readable than
   missing+extra pairs."
  [missing extra]
  (let [lmap (group-by (fn [[s p _]] [s p]) missing)
        rmap (group-by (fn [[s p _]] [s p]) extra)
        candidate-keys (filter (fn [k]
                                 (and (= 1 (count (get lmap k)))
                                      (= 1 (count (get rmap k)))))
                               (keys lmap))
        mismatches (mapv (fn [k]
                           (let [[s p lo] (first (get lmap k))
                                 [_ _  ro] (first (get rmap k))]
                             [s p lo ro]))
                         candidate-keys)
        handled-keys (set candidate-keys)
        ;; Drop the handled (s,p) entries from each list (only one
        ;; entry per side, so we can just remove by key match).
        purged-missing (remove (fn [[s p _]] (contains? handled-keys [s p])) missing)
        purged-extra   (remove (fn [[s p _]] (contains? handled-keys [s p])) extra)]
    [mismatches (vec purged-missing) (vec purged-extra)]))

(defn- render-named-triple [[s p o]]
  (str "  " s " " p " " o " ."))

(defn- render-lexical-mismatch [[s p lo ro]]
  (str "  " s " " p "\n"
       "    left : " lo "\n"
       "    right: " ro))

(defn- render-bnode-record [sig]
  (str/join "\n"
            (cons "  bnode record:"
                  (concat (for [[p o] sig]
                            (str "    " p "  " o))
                          ["  ---"]))))

(defn diff-canonical
  "Diff two canonical-NT strings. Returns
     {:equivalent? bool :report string}.

   When `:equivalent?` is true, `:report` is `\"EQUIVALENT\"`. When
   false, `:report` is a grouped human-readable diff suitable for
   embedding in a test failure message. The grouping makes the diff
   ROOT-CAUSE-READY for the three failure modes verified in the S09
   prototype:
     - lexical mismatch       → LEXICAL MISMATCHES section
     - missing named triple   → MISSING TRIPLES section
     - missing reified record → MISSING BNODE RECORDS section"
  [left-nt right-nt]
  (let [L (parse-canonical-ntriples left-nt)
        R (parse-canonical-ntriples right-nt)
        [Ln Lr] (group-bnode-records L)
        [Rn Rr] (group-bnode-records R)
        [n-miss n-extra] (multiset-diff Ln Rn)
        [b-miss b-extra] (multiset-diff Lr Rr)
        [lex pure-miss pure-extra] (extract-lexical-mismatches n-miss n-extra)
        equivalent? (and (empty? pure-miss) (empty? pure-extra)
                         (empty? lex)
                         (empty? b-miss) (empty? b-extra))
        sections (cond-> []
                   (seq lex)
                   (conj (str "LEXICAL MISMATCHES (same subject+predicate, object differs):\n"
                              (str/join "\n" (map render-lexical-mismatch (sort lex)))))
                   (seq pure-miss)
                   (conj (str "MISSING TRIPLES (in left but not right):\n"
                              (str/join "\n" (map render-named-triple (sort pure-miss)))))
                   (seq pure-extra)
                   (conj (str "EXTRA TRIPLES (in right but not left):\n"
                              (str/join "\n" (map render-named-triple (sort pure-extra)))))
                   (seq b-miss)
                   (conj (str "MISSING BNODE RECORDS (in left but not right):\n"
                              (str/join "\n" (map render-bnode-record b-miss))))
                   (seq b-extra)
                   (conj (str "EXTRA BNODE RECORDS (in right but not left):\n"
                              (str/join "\n" (map render-bnode-record b-extra)))))]
    {:equivalent? equivalent?
     :report (if equivalent? "EQUIVALENT" (str/join "\n\n" sections))}))

(defn semantic-diff
  "G1's primary entry point: compare two TTL strings for semantic
   equivalence. Returns
     {:equivalent? bool :report string ::left-canonical s ::right-canonical s}
   or, when EITHER input fails to parse,
     {:equivalent? false :report 'PARSE-ERROR ...'
      :anomaly/* fields from the parser}.

   A passing G1 assertion can simply check `:equivalent?`; a failing one
   prints `:report` so the diff is root-cause-ready in the test output."
  [ttl-a ttl-b]
  (let [a (canonicalize-ttl ttl-a)
        b (canonicalize-ttl ttl-b)]
    (cond
      (map? a)
      (assoc a :equivalent? false
               :report (str "PARSE-ERROR (left): " (:anomaly/message a)))
      (map? b)
      (assoc b :equivalent? false
               :report (str "PARSE-ERROR (right): " (:anomaly/message b)))
      :else
      (let [d (diff-canonical a b)]
        (assoc d
               ::left-canonical a
               ::right-canonical b)))))
