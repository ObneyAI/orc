# RR-P1 finding — generated code source capture

## Question

Can model-authored code inside `emit-tree!` reach the emit boundary as durable
source without changing code nodes to a string-authored DSL?

## Finding

Yes. A **quoted emitted tree** preserves the inline `fn` as an ordinary Clojure
form. The emit boundary can record that form before compiling only its `:fn`
values with `sci.core/eval-form` in the same SCI context. This produces two
representations of one authored tree:

- a source tree that round-trips through `pr-str` / `read-string`; and
- an executable tree whose code-node functions are SCI closures.

No string-authoring fallback is required, so RR-P1 does not falsify the
ratified generated-source contract and does not require a new grill.

## Falsification sequence

The mechanisms were tested in the issue's required order:

1. **Already-evaluated closure — rejected as a source mechanism.** The current
   `emit-tree!` receives `sci.impl.fns$fun...`; `fn?` is true, metadata is nil,
   and `pr-str` contains only an object identity. This agrees with the current
   sanitization path, which replaces such values with `"<inline-fn>"` in
   [`rlm_tree_executor.clj`](../../../components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj).
2. **Quoted form — accepted.** The same authored function reached the host as
   `(fn [{:keys [inputs]}] ...)`, round-tripped exactly as data, compiled with
   `sci/eval-form`, and returned `{:count 3}` for three inputs.
3. **String convention — not attempted.** The earlier mechanism succeeded, so
   the DSL-changing fallback was neither necessary nor authorized.

The existing production transformer currently rejects quoted code-node
functions because it accepts only strings or compiled functions in
[`rlm_dsl.clj`](../../../components/orc-service/src/ai/obney/orc/orc_service/core/rlm_dsl.clj).
That is the implementation seam RR-6 must change; it is not evidence against
the prototype mechanism.

## Runnable demonstration and observed result

The throwaway demonstration was run from the repository root with:

```bash
clojure -M:dev:test /private/tmp/rr_p1_source_capture.clj
```

Its source-capture core was:

```clojure
(let [ctx* (atom nil)
      captured (atom nil)
      executable (atom nil)
      emit! (fn [quoted-tree]
              (reset! captured quoted-tree)
              (reset! executable
                      (clojure.walk/postwalk
                       (fn [node]
                         (if (and (map? node)
                                  (seq? (:fn node))
                                  (= 'fn (first (:fn node))))
                           (update node :fn #(sci.core/eval-form @ctx* %))
                           node))
                       quoted-tree)))
      ctx (sci.core/init {:bindings {'emit-tree! emit!}})]
  (reset! ctx* ctx)
  (sci.core/eval-string*
   ctx
   "(emit-tree! (quote [:code {:reads [:xs]
                               :writes [:count]
                               :fn (fn [{:keys [inputs]}]
                                     {:count (count (:xs inputs))})}]))")
  {:durable-source (pr-str @captured)
   :source-round-trips? (= @captured (read-string (pr-str @captured)))
   :compiled-function? (fn? (get-in @executable [1 :fn]))
   :execution-result ((get-in @executable [1 :fn])
                      {:inputs {:xs [1 2 3]}})})
```

Observed:

```clojure
{:source-round-trips? true,
 :compiled-function? true,
 :execution-result {:count 3}}
```

The temporary script was deleted after this finding was captured. No
prototype code entered a production or test source path.

## Decision handed to RR-6

RR-6 must capture a quoted source tree at `emit-tree!`, persist that exact
source representation, and compile code-node forms only for execution. Restart
must reconstruct executable functions from the durable source. It must not
attempt to recover source from SCI closure metadata and must not teach the model
a string-authored code-node convention.

Coverage: `0 obligations, 0 covered, 0 uncovered`. This HITL prototype resolved
a design uncertainty; it did not implement a behavioral obligation.
