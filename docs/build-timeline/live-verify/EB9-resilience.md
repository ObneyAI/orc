# EB9 — Subbehavior-internal resilience — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM troubleshoot, real async todo processors, real child tick, REAL source file. The FAILURE is INDUCED DETERMINISTICALLY (the primary apply node is forced to yield 0 drafts — model-proof, #1: an earlier mis-scope injection was non-deterministic because a capable LLM sometimes ignored the nonsense scope and extracted anyway). The failure is injected deterministically; the RESILIENCE RESPONSE under test (the robust author on the recoverable path; the troubleshoot on the unrecoverable path) is the REAL LLM.

Proves the reusable `with-resilience` sub-tree composed into Extract: a `:fallback`[primary→robust] guarded by a sanity `:condition`, with a troubleshoot `:llm` node (Investigation root-cause + Validation check, `:reasoning` FIRST #13) that lands a STRUCTURED `:diagnosis` + a CLEAN `:failure` on unrecoverable failure — NEVER a poisoned empty success (#4/#5). Read back from the PARENT tick blackboard via the projection (discipline 7), NOT from the execute return value.

## RECOVERABLE — primary forced-empty → gate rejects → REAL-LLM robust recovers

The primary apply was forced to 0 drafts → the sanity gate (`{:key :concept-count :op :gt :value 0}`) REJECTED it → the `:fallback` ran the REAL-LLM ROBUST author→apply → a sane scoped draft set. Downstream saw a GOOD result:

- status: **:success** (5536ms)
- concept-count (recovered by the REAL LLM robust path, scoped, non-empty): **572**
- diagnosis present? **false** (nil — recovered via fallback, troubleshoot never ran)

Sample recovered drafts (verbatim):

```clojure
[{:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1011",
    "SOC_Title" "Animal Scientists"}],
  :attributes {:cip_code "01.0000"}}
 {:uri "occupation/19-1011",
  :label "Animal Scientists",
  :type "Occupation",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1011",
    "SOC_Title" "Animal Scientists"}],
  :attributes {:soc_code "19-1011"}}
 {:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1012",
    "SOC_Title" "Food Scientists and Technologists"}],
  :attributes {:cip_code "01.0000"}}
 {:uri "occupation/19-1012",
  :label "Food Scientists and Technologists",
  :type "Occupation",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1012",
    "SOC_Title" "Food Scientists and Technologists"}],
  :attributes {:soc_code "19-1012"}}
 {:uri "program-of-study/01.0000",
  :label "Agriculture, General.",
  :type "Program of Study",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1013",
    "SOC_Title" "Soil and Plant Scientists"}],
  :attributes {:cip_code "01.0000"}}
 {:uri "occupation/19-1013",
  :label "Soil and Plant Scientists",
  :type "Occupation",
  :evidence
  [{"CIP_Code" "01.0000",
    "CIP_Title" "Agriculture, General.",
    "SOC_Code" "19-1013",
    "SOC_Title" "Soil and Plant Scientists"}],
  :attributes {:soc_code "19-1013"}}]
```

## UNRECOVERABLE — primary+robust forced-empty → real-LLM troubleshoot + clean failure

BOTH the primary and robust apply nodes were forced to 0 drafts → BOTH gates rejected → the troubleshoot `:llm` node (REAL OpenRouter) investigated WHY and landed a structured `:diagnosis`, then the always-fail `:condition` forced a CLEAN `:failure`:

- status: **:failure** (should be `:failure`; 4417ms)
- concept-drafts read back from the parent tick (downstream POISON check): **[]** (empty → downstream NOT poisoned with a fake success)
- diagnosis present? **true**

REAL-LLM structured diagnosis (Investigation root-cause + Validation check; verbatim, NOT truncated — #11):

```clojure
{:symptom
 "The extraction yielded zero concepts and zero processed rows despite valid input data.",
 :root-cause
 "The extraction process was bypassed by a deterministic failure injection (\"FORCED-EMPTY (EB9 deterministic failure injection)\").",
 :ruled-out
 "- [:scope-mismatch \"Ruled out because sample-rows contains CIP_Code '01.0000' which satisfies the '01' scope-filter.\"]\n- [:empty-input \"Ruled out because sample-rows contains 5 valid records.\"]\n- [:transformation-error \"Ruled out because rows-errored is 0; the system didn't even attempt to process the rows.\"]",
 :check-failed
 "A NON-EMPTY, scoped set of concept drafts was expected.",
 :issues
 "[{:rule :non-empty-extraction :reason \"The extraction report indicates zero rows were streamed or processed.\"}]",
 :recommended-fix
 "Disable the 'EB9 deterministic failure injection' in the environment or deployment configuration to allow the extraction logic to execute against the provided stream.",
 :recoverable? nil}
```

## Verdict

The reusable `with-resilience` sub-tree, composed into Extract, makes the subbehavior SELF-CORRECT (recoverable → the `:fallback` robust path recovers, downstream sees a good result) OR FAIL CLEANLY WITH A DIAGNOSIS (unrecoverable → a real-LLM troubleshoot lands a structured diagnosis + the step returns a clean `:failure`, downstream NOT poisoned). No fake success on the troubleshoot path (#4/#5); the gate is a structural `:condition` (no hardcoded phrase matching, #7/#12); the troubleshoot writes `:reasoning` first (#13). Reuses the dsl `:fallback`/`:condition` + the Investigation/Validation pattern (no fork). Read back from the projection (discipline 7).
