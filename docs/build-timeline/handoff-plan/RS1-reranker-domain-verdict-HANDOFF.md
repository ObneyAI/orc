# RS-1 handoff — The reranker gives a domain verdict beside fitness

Issue: `docs/issues/r-inject-specialisation/RS-1-the-reranker-gives-a-domain-verdict-beside-fitness.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` (branch `feature/r-inject-specialisation`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`; a copy of the runner is at `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc/.rr-durable-notes/run-focused.sh`
— note the runner `cd`s into the ARC worktree; for this branch run the same command with
`cd /Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` — see "Runner" below). ONE JVM at a time; never kill a
JVM whose working directory is not this worktree (another team's benchmark runs on this machine); confirm 0 orphan JVMs
after every run. Do not run `poly test` or any whole-brick build (the orchestrator runs the gates). Do not commit, push
or stash. Never edit `specs/*.allium`.

## Runner

```
cd /Users/darylroberts/Desktop/Code/orc-r-inject-specialisation && clojure -J-Djava.awt.headless=true -J-Xmx1600m -M:dev:test -e "(require 'clojure.test) (require '<ns1> '<ns2>) (let [r (clojure.test/run-tests '<ns1> '<ns2>)] (println \"RESULT\" r) (shutdown-agents) (System/exit (if (clojure.test/successful? r) 0 1)))" > <log> 2>&1; echo exit=$?
```

## Goal

Every per-candidate result of the rerank call carries, beside `:fitness-score` and `:reasoning`, a discrete domain
verdict and a canonical label, with the reasoning written before the verdict. The parse step canonicalises the new keys
exactly as it does the existing ones and reads a missing or malformed verdict as `unknown`, never coercing it. The
instruction gains the domain section the two prototypes proved (below, verbatim), and can render a candidate's existing
domain children for label reuse. Fitness keeps its meaning; every existing consumer of the reranked result is unchanged.

## Read first

1. Spec excerpt, verbatim (`specs/ontology.allium`):

       enum DomainCoverage { covered | partial | uncovered | unknown }

       value DomainVerdict {
           coverage: DomainCoverage
           domain_label: String
           reasoning: String
       }

       contract TaskClassification {
           classify: (task_signature: String) -> ClassificationOutcome
           judge_domain_coverage: (task_signature: String, candidate: Concept) -> DomainVerdict
           ...
           @invariant DomainCoverageIsJudgedNotInferred
               -- Whether a matched class covers the task's DOMAIN is a discrete verdict
               -- the reranker gives alongside fitness, with its reasoning naming the
               -- representative use or guard it matched or the gap it found — never a
               -- threshold over a similarity score, and never read off the fitness
               -- number, which keeps meaning shape-and-intent fit. An unknown, missing
               -- or malformed verdict defers on the domain axis ...
       }

2. `components/ontology/src/ai/obney/orc/ontology/core/reranker.clj`:
   - `reranker-instruction` (line ~24): the shipped instruction. Its output contract paragraph ("PRODUCE a JSON string
     of a vector … EXACTLY these three keys … Example shape …") is what the prototypes REPLACED; everything else stays
     byte-identical.
   - `candidate-schema` (~line 167): the per-candidate input; add an OPTIONAL `:existing-domain-children
     [:vector :string]` (RS-2/RS-3 will populate it; here it is accepted and rendered into the candidates JSON).
   - `parse-reranked-json` (~line 320): canonicalises `document_id`/`fitness_score` and then `select-keys` to the three
     canonical keys — that `select-keys` is what drops the new fields today; canonicalise `domain_coverage` →
     `:domain-coverage` (string → keyword, one of the four, else `:unknown`), `domain_label` → `:domain-label` (string,
     else nil), `domain_reasoning` → `:domain-reasoning` (string, else nil), and keep them.
   - `ontology-schemas/reranked-result` (`interface/schemas.clj`): the validated entry shape; the three new keys are
     OPTIONAL so pre-RS-1 payloads still validate.
3. The two prototypes and their findings — the calibration is the evidence, not opinion:
   `development/src/rs_p1_coverage_probe.clj` (throwaway; the `new-contract` string is the wording that worked),
   `development/bench/ood-stress-results/rs-p1-coverage-probe/FINDINGS.md`,
   `development/bench/ood-stress-results/rs-p1b2-sibling-reuse-separated-probe/FINDINGS.md`.
4. Existing tests to mirror: `components/ontology/test/ai/obney/orc/ontology/reranker_test.clj`,
   `cc15_reranker_enrichment_contract_test.clj`, `rerank_failure_surfacing_test.clj`.
5. Grill decisions D4, D7, D7b in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`.

## The exact instruction text (proven by RS-P1b run 2 — use it verbatim, replacing the shipped output-contract paragraph)

```
DOMAIN COVERAGE — A SEPARATE VERDICT, NOT A NUMBER.
fitness_score means how well the candidate's SHAPE and intent fit the task.
Separately, for each candidate, judge whether the candidate's DECLARED DOMAIN
— its representative uses and its avoid-when guards — covers the DOMAIN of the
task (what the task is about: the subject matter, the material, the kind of
output). Give a discrete verdict:
  covered    — a representative use names this task's SUBJECT MATTER, its
               MATERIAL (what is read) and its OUTPUT KIND. A shared kind of
               processing ('a pipeline', 'a sequence of passes', 'draft then
               revise') is NOT a domain and never makes a candidate covered.
  partial    — a representative use shares the subject matter but not the
               material or the output kind, or the reverse
  uncovered  — nothing the candidate declares names this task's subject
               matter, material or output kind; the fit, if any, is shape only
  unknown    — you cannot tell from what the candidate declares
A candidate may carry existing_domain_children: labels of domain children
already minted under it. They serve ONE purpose — label reuse. If one of them
names THIS task's domain, you MUST reuse that label verbatim as domain_label
(do not coin a variant); coin a new label only when none of the existing ones
fits. existing_domain_children MUST NOT influence domain_coverage: coverage is
judged solely against the candidate's OWN representative uses and content. A
child naming this task's domain does not make its parent covered — a parent
with a matching child is exactly the case where the task belongs to the child,
not to the parent.
Write domain_reasoning BEFORE choosing the verdict: name the representative use
or guard you matched, or state the gap. Also give domain_label: a 2-4 word
kebab-case label of the TASK's own domain (the same label for every candidate
of this task), e.g. "marathon-training-plan", "recipe-scaling",
"security-findings-haiku".

PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these six keys:
  {"document_id":     "<echo the candidate's document-id verbatim>",
   "reasoning":       "<concrete, actionable; references specific content>",
   "fitness_score":   <number in [0.0, 1.0]>,
   "domain_reasoning": "<the representative use / guard matched, or the gap>",
   "domain_coverage": "<covered|partial|uncovered|unknown>",
   "domain_label":    "<2-4 word kebab-case label of the task's domain>"}

Example shape:
  [{"document_id":"a","reasoning":"...","fitness_score":0.91,
    "domain_reasoning":"...","domain_coverage":"covered","domain_label":"contract-comparison"},
   {"document_id":"b","reasoning":"...","fitness_score":0.42,
    "domain_reasoning":"...","domain_coverage":"uncovered","domain_label":"contract-comparison"}]
```

## TDD cycle list

1. **RED** `rs1_domain_verdict_test.clj` (ontology test dir): `parse-reranked-json` on a constructed result whose JSON
   carries the six keys returns entries with `:domain-coverage :partial`, `:domain-label "marathon-training-plan"`,
   `:domain-reasoning "…"` beside the three canonical keys, and each entry validates against
   `ontology-schemas/reranked-result`. RED today (the keys are dropped by `select-keys`).
2. GREEN: canonicalise and keep the keys; make them optional in the schema.
3. **RED**: a payload with the three keys MISSING (a pre-RS-1 model reply) parses to entries with `:domain-coverage
   :unknown` and nil label/reasoning — and still validates; a payload with `"domain_coverage":"maybe"` (malformed) also
   reads `:unknown`. RED today.
4. GREEN.
5. **RED**: the reranker instruction contains the domain section verbatim (assert on the constant you added — it is
   OUR text, not model prose) and no longer says "EXACTLY these three keys"; the candidates JSON rendered for a
   candidate carrying `:existing-domain-children` includes them and `candidate-schema` accepts the key. RED today.
6. GREEN.
7. **Guard**: an entry without the new keys and every existing reranker suite behave exactly as before — run
   `reranker-test`, `cc15-reranker-enrichment-contract-test`, `rerank-failure-surfacing-test`,
   `rr1-reranker-timeout-resilience-test`, `rr-cfg-reranker-model-config-test`, `el3-three-state-outcome-test`,
   `walk-down-classifier-test`, `r05b-classify-behaviors-test` unchanged.

Propagate note (orchestrator): `allium plan specs/ontology.allium` lists `contract-signature.TaskClassification.judge_domain_coverage`,
`entity-fields.DomainVerdict`, `value-equality.DomainVerdict`, `enum-comparable.DomainCoverage` — all UNCOVERED today
(RED by absence). Cycles 1–4 cover them: the parsed entry IS the realisation of `DomainVerdict`; the enum comparability is
the four-value keyword set with `unknown` as the fallback. Expected coverage line: `4 obligations, 4 covered, 0 uncovered`.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.

## Do NOT touch

- `specs/*.allium`; the classifier (`task_classifier.clj`), the wedge, the prepend renderer, harvest, the consolidator;
  the reranker's stance, score definition, reasoning discipline, timeout/retry logic and model resolution; the
  `search-descriptions` join (`apply-rerank`) — it passes the reranked entry through by key and needs no change (verify,
  do not edit).

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; one parsed entry showing the six keys and one
showing the `unknown` fallback; the coverage line; what you could NOT verify; the orphan-JVM check.
