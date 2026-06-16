(ns ai.obney.orc.orc-service.v19-text-source-tools-test
  "V19 — TEXT-file source-access tools (the text leg of the per-format family).

   These give a text source the SAME bounded-read affordances + calling
   convention the csv/sql/excel specialists have, so a builder fluent in one
   isn't tripped switching mediums:

   - peek-text     — first units + shape profile, never the whole file.
   - sample-units  — at most N units (lines), :offset windows; a wrong-shape arg
                     is a TEACHING error, not an arity crash.
   - count-units   — total unit (line) count WITHOUT loading the file.
   - stream-all    — iterate the FULL unit set in bounded windows, covering every
                     unit exactly once (the substrate V20 runs a transform over).

   Each deftest maps to a V19 acceptance criterion. Read-side only; no events."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools-text :as text]))

(defn- write-tmp-text!
  "Write `content` to a fresh temp .txt file; return its absolute path."
  [content]
  (let [f (java.io.File/createTempFile "v19-text-fixture" ".txt")]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

(defn- big-text
  "N lines whose content is the 0-based line index, so coverage can be checked
   exactly."
  [n]
  (apply str (map (fn [i] (str i "\n")) (range n))))

(defn- tools-for [path] (text/text-source-tools {:text-path path}))
(defn- tool [path sym] (get (tools-for path) sym))

;; =============================================================================
;; AC — peek-text shows shape without loading the file
;; =============================================================================

(deftest peek-text-returns-units-and-shape
  (testing "peek-text returns the first units + a shape profile, bounded."
    (let [path (write-tmp-text! "alpha\n\nbeta\ngamma\n")
          r ((tool path 'peek-text))]
      (is (= ["alpha" "" "beta" "gamma"] (:units r)))
      (is (= 4 (:unit-count-sampled r)))
      (is (= 0.25 (double (:blank-ratio r))) "one of four sampled units is blank")
      (is (false? (:more? r)) "all units fit in the peek window"))))

;; =============================================================================
;; AC — sample-units bounded + :offset windows
;; =============================================================================

(deftest sample-units-is-bounded-and-offsettable
  (testing "sample-units returns at most N units; :offset pages a deeper window."
    (let [path (write-tmp-text! (big-text 1000))
          sample (tool path 'sample-units)]
      (let [r (sample 3)]
        (is (= ["0" "1" "2"] (:units r)))
        (is (= 3 (:returned r)))
        (is (true? (:capped? r)) "more than 3 units exist"))
      (let [r (sample {:limit 3 :offset 500})]
        (is (= ["500" "501" "502"] (:units r)) "offset reaches a deeper window")
        (is (= 500 (:offset r)))))))

(deftest sample-units-hard-caps-an-absurd-n
  (testing "an absurd N is clamped to the hard cap — the file is never dumped."
    (let [path (write-tmp-text! (big-text 2000))
          r ((tool path 'sample-units) 1000000)]
      (is (<= (:returned r) text/max-sample-units))
      (is (true? (:capped? r))))))

;; =============================================================================
;; AC — wrong-shape sampling arg is a TEACHING error, not an arity crash
;; =============================================================================

(deftest sample-units-wrong-shape-arg-is-a-teaching-error
  (testing "a wrong-shape arg (not an integer N nor an opts map) yields a clear
            teaching error naming the correct call form."
    (let [path (write-tmp-text! "a\nb\n")
          sample (tool path 'sample-units)]
      (is (thrown-with-msg? Exception #"(?i):limit|:offset|opts map"
                            (sample [1 2 3])))
      (is (thrown-with-msg? Exception #"(?i)sample-units"
                            (sample [1 2 3])))))
  (testing "an extra positional arg (the Excel-style mistake) teaches putting
            :offset in the opts map — consistent across specialists."
    (let [path (write-tmp-text! "a\nb\n")
          sample (tool path 'sample-units)]
      (is (thrown-with-msg? Exception #"(?i):limit|:offset|opts map|positional"
                            (sample 10 {:offset 0}))))))

;; =============================================================================
;; AC — count affordance: total unit count WITHOUT a full load
;; =============================================================================

(deftest count-units-returns-unit-count
  (testing "count-units returns the exact unit (line) count without loading the
            file — on a 5000-line file it returns 5000, above the sample cap."
    (let [path (write-tmp-text! (big-text 5000))
          c ((tool path 'count-units))]
      (is (= 5000 (:unit-count c)))
      (is (false? (:capped? c)) "count is exact, not a capped estimate")))
  (testing "empty file -> 0 units"
    (is (= 0 (:unit-count ((tool (write-tmp-text! "") 'count-units)))))))

;; =============================================================================
;; AC — stream-all covers every unit exactly once in bounded windows
;; =============================================================================

(deftest stream-all-covers-every-unit-exactly-once
  (testing "stream-all pages the FULL unit set in bounded windows, covering every
            unit exactly once while honoring the per-call cap. Each line is its
            index, so the concatenation must be exactly 0..4999."
    (let [path (write-tmp-text! (big-text 5000))
          windows ((tool path 'stream-all) {:window 100})
          idxs (mapcat (fn [w] (map #(Long/parseLong %) (:units w))) windows)]
      (is (> (count windows) 1) "more than one window — file did not fit in one")
      (is (every? #(<= (:returned %) text/max-sample-units) windows)
          "every window respects the per-call cap")
      (is (= (range 0 5000) idxs) "every unit covered, in order, exactly once")
      (is (= 5000 (count (set idxs))) "no duplicates"))))

(deftest stream-all-clamps-window-to-hard-cap
  (testing "a :window above the hard cap is clamped — stream-all never pulls more
            than the per-call ceiling in one window, but still covers the file."
    (let [path (write-tmp-text! (big-text 1200))
          windows ((tool path 'stream-all) {:window 999999})
          idxs (mapcat (fn [w] (map #(Long/parseLong %) (:units w))) windows)]
      (is (every? #(<= (:returned %) text/max-sample-units) windows)
          "window clamped to the hard cap")
      (is (= 1200 (count idxs)) "all 1200 units still covered across windows"))))

;; =============================================================================
;; AC — docstring quality (self-contained) + adversarial twin
;; =============================================================================

(deftest each-tool-docstring-is-self-contained
  (testing "every text tool doc has PURPOSE, EXAMPLE, RETURNS with a concrete
            call form (no <placeholder> tokens)."
    (let [docs text/text-source-tool-docs
          required ["PURPOSE" "EXAMPLE" "RETURNS"]]
      (is (= #{'peek-text 'sample-units 'count-units 'stream-all}
             (set (keys docs)))
          "all four text tools are documented")
      (doseq [[sym doc] docs]
        (testing (str sym " docstring structure")
          (is (string? doc))
          (doseq [el required]
            (is (str/includes? doc el) (str sym " missing " el)))
          (let [after (second (str/split doc #"EXAMPLE"))]
            (is (some? after) (str sym " has content after EXAMPLE"))
            (is (str/includes? after "(") (str sym " EXAMPLE has a code form"))
            (let [code-only (first (str/split after #"RETURNS"))]
              (is (not (re-find #"<arg\d?>|<placeholder>" code-only))
                  (str sym " EXAMPLE uses concrete values, not placeholders")))))))))

(deftest adversarial-stripping-a-section-fails-docstring-quality
  (testing "proof the doc-quality check is not trivially passing."
    (let [bad "PURPOSE — reads text. RETURNS — a map."
          required ["PURPOSE" "EXAMPLE" "RETURNS"]
          results (mapv #(str/includes? bad %) required)]
      (is (= [true false true] results))
      (is (not (every? identity results))))))

;; =============================================================================
;; AC — fns carry the docstring on metadata + no-grant -> nil
;; =============================================================================

(deftest tools-carry-docstring-metadata
  (testing "each tool fn carries its self-contained docstring on :doc metadata."
    (let [ts (tools-for (write-tmp-text! "a\n"))]
      (doseq [[sym f] ts]
        (is (string? (:doc (meta f))) (str sym " fn has :doc metadata"))
        (is (str/includes? (:doc (meta f)) "PURPOSE")
            (str sym " :doc is the self-contained docstring"))))))

(deftest no-text-path-means-no-tools
  (testing "without a :text-path the builder returns nil — the sandbox must not
            expose unscoped source tools."
    (is (nil? (text/text-source-tools {})))
    (is (nil? (text/text-source-tools {:text-path nil})))
    (is (nil? (text/text-source-tools {:text-path ""})))))

;; =============================================================================
;; AC — missing file surfaces as data, not a crash (read-side honesty)
;; =============================================================================

(deftest missing-file-is-honest
  (testing "a missing file surfaces an :error marker rather than throwing."
    (let [ts (tools-for "/no/such/file/anywhere.txt")]
      (is (some? (:error ((get ts 'peek-text)))))
      (is (= 0 (:unit-count ((get ts 'count-units))))))))
