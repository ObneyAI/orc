# R-Default: Make recursive the always-on default RLM mode

## Parent

`docs/prd/rlm-recursive-emit-tree.md`. The original framing of recursive mode (R-1) was "opt-in via `:rlm {:recursive? true}`" so terminal-mode benchmarks didn't regress. After R-3 + R-4 + R-5 + the R-Bench verification sweep, recursive is verified non-regressive and frequently CHEAPER than terminal on the same tasks. Time to flip the default.

## What to build

End-to-end behavior:
- When a node is configured with `:rlm true` (the legacy shorthand for terminal mode), the framework treats it as `:rlm {:recursive? true}` going forward.
- Explicit `:rlm {:recursive? false}` continues to work for the rare case a consumer genuinely wants terminal mode.
- The bench runner's task-level `:recursive?` override stays usable (it's still useful for backward-compat with the experiment scripts).
- RLM-GUIDE.md updates to describe recursive as the default + opt-out for terminal.

End-state from the user's perspective:
> ```clojure
> (orc/repl-researcher "researcher"
>   :model "..."
>   :instruction "..."
>   :writes [:answer]
>   :max-iterations 5
>   :rlm true)  ;; Now means recursive
> ```

## Acceptance criteria

- [ ] Unit test: a node with `:rlm true` produces an rlm-config interpreted as `:recursive? true` when execute-repl-researcher-rlm reaches its recursive dispatch.
- [ ] Unit test: `:rlm {:recursive? false}` still routes through terminal-mode dispatch (preserved escape hatch).
- [ ] Regression sweep: all 5 brick + framework test suites GREEN (no regressions across the 126 RLM tests).
- [ ] Live verify: a benchmark using only `:rlm true` (no explicit `:recursive?`) on the runner config produces the same outputs as before with the recursive flow (i.e. multiple iterations possible, prior-tree outputs available via `get-var`, etc.).
- [ ] RLM-GUIDE.md updated:
  - Top of doc: "Recursive mode is the default."
  - Migration note: explicit terminal-only consumers can set `:rlm {:recursive? false}`.

## Blocked by

- R-3 + R-4 + R-5 ✅ all shipped to this branch
- R-Bench ✅ 5/5 success verified

## Implementation sketch

The flip lives in one place — wherever `execute-repl-researcher-rlm` reads `:recursive?` from rlm-config. Currently:

```clojure
recursive-mode? (boolean (get-in node [:rlm :recursive?]))
```

Becomes:

```clojure
recursive-mode? (not= false (get-in node [:rlm :recursive?]))
```

i.e. default-to-true. Explicit `false` still works as the escape hatch.

(Also need to handle the case where `:rlm` itself is `true` vs a map — `(:rlm node)` `= true` means recursive now.)

## Notes

- This is a SEMANTIC CHANGE — any consumer relying on "first emit-tree! returns immediately" behavior breaks. But: that behavior was already discouraged by the prompt-as-default-mode framing, and the recursive merge gracefully handles single-tree-then-final! flows (the model can call `final!` on iteration 2 after one tree, which is the new equivalent of terminal-mode).
- The companion R-6 (syntax-error retry optimization) is a nice-to-have but doesn't block this flip.
