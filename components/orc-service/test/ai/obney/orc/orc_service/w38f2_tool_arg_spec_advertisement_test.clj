(ns ai.obney.orc.orc-service.w38f2-tool-arg-spec-advertisement-test
  "W38-F2 durable tests — the Phase-1 tool advertisement names each tool's
   REAL argument keys instead of teaching a placeholder.

   Root cause (W38 forensic, store-proven): the RLM code-gen module advertised
   every bound tool with the single generic example
   (example-tool {\"arg\" \"value\"}), and NO surface anywhere named a tool's
   actual parameter keys. Models copy the taught shape byte-for-byte — the
   store holds real verification commands sent under {\"arg\" ...} and
   {\"command\" ...}, which the consumer's intent layer reads as :cmd nil and
   refuses. Fix: an ADDITIVE per-tool arg spec on the node's :options channel
   (:tool-arg-specs {\"tool-name\" [\"arg-key\" ...]}); a spec'd tool renders
   its real keys verbatim, an un-spec'd tool keeps the generic example
   (backward-compatible, nil-safe).

   All assertions go through the REAL builder fn (no string copies of the
   prompt); key renderings are asserted at byte level."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(def ^:private build-fn #'executor/build-rlm-code-generation-module)

(def ^:private coding-tools
  ["shell/exec" "fs/write" "fs/read"])

(def ^:private coding-specs
  {"shell/exec" ["cmd" "timeout-ms"]
   "fs/write"   ["path" "content"]
   "fs/read"    ["path"]})

(defn- build-module
  "Build the RLM code-gen module the way the executor call site does:
   node carries :mcp-tools (+ optional :options :tool-arg-specs)."
  [node]
  (build-fn node "" [] {} {} {}))

(defn- instructions-for [node]
  (:instructions (build-module node)))

(deftest specd-tool-renders-its-real-keys-verbatim
  (testing "a bound tool WITH a declared arg spec renders its real keys, byte-exact"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools
                :options {:tool-arg-specs coding-specs}}
          instructions (instructions-for node)]
      (is (str/includes? instructions
                         "(shell/exec {\"cmd\" \"...\" \"timeout-ms\" \"...\"})  ;; => result map\n")
          "shell/exec renders its declared keys in declared order")
      (is (str/includes? instructions
                         "(fs/write {\"path\" \"...\" \"content\" \"...\"})  ;; => result map\n")
          "fs/write renders its declared keys")
      (is (str/includes? instructions
                         "(fs/read {\"path\" \"...\"})  ;; => result map\n")
          "fs/read renders its single declared key")
      (is (str/includes? instructions
                         "The argument keys shown above are each tool's REAL parameter names")
          "the advertisement states the keys are the real parameter names"))))

(deftest unspecd-tool-keeps-the-generic-example
  (testing "no :tool-arg-specs at all -> the pre-W38 generic example, unchanged"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools}
          instructions (instructions-for node)]
      (is (str/includes? instructions
                         "(shell/exec {\"arg\" \"value\"})  ;; => result map\n")
          "the generic example survives, built from the first bound tool")
      (is (not (str/includes? instructions "REAL parameter names"))
          "no real-keys claim is made when nothing is declared")))
  (testing "a PARTIAL spec map: spec'd tools render keys, the rest share one generic line"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools
                :options {:tool-arg-specs {"shell/exec" ["cmd"]}}}
          instructions (instructions-for node)]
      (is (str/includes? instructions
                         "(shell/exec {\"cmd\" \"...\"})  ;; => result map\n")
          "the spec'd tool renders its real key")
      (is (str/includes? instructions
                         "(fs/write {\"arg\" \"value\"})  ;; => result map\n")
          "the first UN-spec'd tool carries the generic example")))
  (testing "an EMPTY spec entry is nil-safe: treated as un-spec'd"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools ["shell/exec"]
                :options {:tool-arg-specs {"shell/exec" []}}}
          instructions (instructions-for node)]
      (is (str/includes? instructions
                         "(shell/exec {\"arg\" \"value\"})  ;; => result map\n")))))

(deftest fully-specd-advertisement-contains-no-placeholder
  (testing "when EVERY bound tool declares its keys, the {\"arg\" \"value\"} placeholder is gone"
    (let [node {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools
                :options {:tool-arg-specs coding-specs}}
          instructions (instructions-for node)]
      (is (not (str/includes? instructions "{\"arg\" \"value\"}"))
          "no placeholder arg shape anywhere in the prompt")
      (is (not (str/includes? instructions "\"arg\""))
          "the literal placeholder key never appears"))))

(deftest no-spec-module-is-byte-identical-to-pre-w38
  (testing "a node without :tool-arg-specs builds the exact same module as one
            whose :options omit the key (backward compatibility of the seam)"
    (let [base {:type :repl-researcher
                :rlm {:recursive? true}
                :writes [:answer]
                :instruction "Implement the fix."
                :mcp-tools coding-tools}]
      (is (= (build-module base)
             (build-module (assoc base :options {})))
          "empty :options and absent :options produce identical modules"))))
