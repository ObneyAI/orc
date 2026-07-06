# Handoff — MT-10: coerce malformed-string `:entity-types` (the real empty-vocab root fix)

> **REVISION (live-verify finding).** The first MT-10 fix (EDN → `json/read-str` → []) was landed + gate-green, but the LIVE bounded acceptance STILL failed empty-vocab on BOTH runs. A `[COERCE-PROBE]` diagnostic captured the ACTUAL failing shape — it is NOT clean JSON: it is a **JSON/EDN HYBRID** — JSON object syntax (string keys `"type":`) with a **bare EDN keyword VALUE** (`"grain-strategy": :canonical-row-filter` — unquoted). BOTH parsers reject it: `edn/read-string` on the `"key":` colon; `json/read-str` on the bare `:keyword` value. THE FIX below is revised to add a hybrid-recovery path. Everything else in this handoff still holds.
>
> **Revised recovery order for a STRING:** (1) EDN; (2) clean JSON (`json/read-str :key-fn keyword`); (3) **HYBRID recovery** — deterministically DROP the JSON field-separator colon after each quoted key (`"key":` → `"key" `), turning the hybrid into valid EDN (`{"type" "Occupation", "grain-strategy" :canonical-row-filter}` — EDN allows string keys + keyword values), then `edn/read-string`; (4) `[]`. Then keep `map?` entries + keywordize keys (existing). The colon-drop regex must match a JSON string key (handling escaped quotes: `"(?:[^"\\]|\\.)*"`) followed by `:`, and must NOT touch a colon INSIDE a quoted value (a value string is followed by `,`/`}`/`]`, never `:`). TEST that a `"a: b"` value survives. This single transform also subsumes clean JSON, but keep step 2 (json/read-str) too for standard cases.
>
> **New TDD tracer (add):** the hybrid string `"[{\"type\": \"Occupation\", \"uri-keying-fields\": [\"O*NET-SOC Code\"], \"grain-strategy\": :canonical-row-filter}]"` → `coerce-entity-types` yields `[{:type "Occupation" :uri-keying-fields ["O*NET-SOC Code"] :grain-strategy :canonical-row-filter}]`; `canonical-types` yields `["Occupation"]`; `empty-vocabulary?` false. Plus: a hybrid entry whose VALUE contains a colon (`"description": "role: important"`) parses with the value intact (the colon-drop must not corrupt it).

---
_Original handoff (the clean-JSON half — still valid, keep it):_

# Handoff — MT-10: coerce JSON-string `:entity-types` (the real empty-vocab root fix)

**Parent:** the MT-7c acceptance reliability (the TRUE root cause behind the empty-vocab model-extract failures). **Blocked-by:** none.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (diagnostic-PROVEN — don't re-derive)
The comprehensive/bounded build intermittently dies at `:failed-at-model-extract` ("NO usable entity-type vocabulary"). A 5-call diagnostic (`development/src/mt9_retry_diagnostic.clj`, per-attempt raw-hash probe) proved the cause: the Model's `:entity-types` intermittently crosses the `:delegate` as a **JSON STRING with STRING keys**, e.g.
```
:entity-types "[\n  {\n    \"type\": \"Occupation\",\n    \"uri-keying-fields\": [\"O*NET-SOC Code\"],\n    \"grain-strategy\": \":canonical-row-filter\"}, …]"
```
`coerce-entity-types` (in `vocabulary_binding.clj`) only tries `edn/read-string`, which THROWS on JSON's `"key":` colon → falls to `[]` → empty vocabulary → hard stop. When the same field arrives as parsed EDN (`[{:type "Occupation" :uri-keying-fields […]}]`, keyword keys) it succeeds. So the data is NEVER lost and it is NOT caching (diagnostic showed differing raw-hashes + a recovery) — it is **unparsed JSON**. Retries (MT-7d/MT-9) only help when a re-run happens to return EDN (~50%), so the acceptance stays flaky. Parsing the JSON is the cure — it converts these failures directly into successes, no retry needed.

## The change (`coerce-entity-types` in `components/ontology/src/ai/obney/orc/ontology/core/vocabulary_binding.clj`)
When `:entity-types` is a STRING, try EDN first (backward-compat), then **JSON**, then `[]`:
1. **EDN attempt** (as today): `edn/read-string`; if it yields a sequential, use it; on throw/non-sequential, fall through (do NOT return `[]` yet).
2. **JSON attempt:** parse with `clojure.data.json` (already an ontology dep — `[clojure.data.json :as json]`) via `(json/read-str s :key-fn keyword)` so object keys become KEYWORDS (`"type"` → `:type`, `"uri-keying-fields"` → `:uri-keying-fields`). If it yields a sequential of maps, use it. Wrap in try/catch → on failure fall through.
3. **Neither parses → `[]`** (honest, as today).
4. **Keyword-key safety:** whatever the path, ensure each kept entry MAP has keyword keys (defensively keywordize any residual string keys at the entry's top level), because downstream (`canonical-types`, GC-1) reads `:type` / `:uri-keying-fields` as keywords. Keep only `map?` entries (as today). Pure + total — never throws (all parses guarded).

Update the docstring: the string form may be EDN **or JSON** (the diagnostic-proven crossing shape); both are recovered; only genuinely-unparseable → `[]`.

DELIBERATELY NOT: no change to the retry (it stays as the safety net for genuinely-empty outputs), the hard stop, the binding/proposal logic, or the grain. Domain-agnostic — parses whatever JSON/EDN the model emits; names no field. Add the `clojure.data.json` require to `vocabulary_binding.clj`.

## /prototype
Not needed — the failing + passing raw shapes are diagnostic-captured; the fix is a guarded parse fallback with a clear red test.

## TDD cycle (tests FIRST, red→green, PUBLIC `coerce-entity-types` + `canonical-types`)
1. **JSON-string with string keys → parsed to keyword-keyed maps (RED first):** input `"[{\"type\": \"Occupation\", \"uri-keying-fields\": [\"O*NET-SOC Code\"]}]"` → `coerce-entity-types` yields `[{:type "Occupation" :uri-keying-fields ["O*NET-SOC Code"]}]`; `canonical-types` on a model-spec carrying that string yields one usable type (`empty-vocabulary?` = false). Currently RED (EDN-only → `[]` → empty).
2. **EDN-string still works** (backward-compat — the existing behavior): `"[{:type \"Occupation\" :uri-keying-fields [\"id\"]}]"` → keyword-keyed maps (cite/keep the existing EDN test green).
3. **Already-parsed vector passthrough** (the common success path) unchanged.
4. **Genuinely-unparseable string → `[]`** (honest), and a JSON scalar / non-sequential → `[]` (not a vocabulary).

## Live-QA (the reviewer's `/inspect-orc`)
Re-run the MT-7c bounded acceptance (`clj -J-Xmx6g -M:dev:test -m mt7c-acceptance`). The JSON-string entity-types now parse at coercion → the empty-vocab hard stop stops firing on them → BOTH runs reliably clear model-extract → the acceptance goes GREEN (honest-terminal + zero fragmentation + cq-gate-answered + not-hollow on both). If a run still fails empty, capture the raw (re-add the mt9 probe) — a NEW unparsed shape, not JSON/EDN.

## Do NOT touch
The retry/hard stop; the binding/proposal seams; MT-8 CQ evidence; the grain. No error-string matching; no domain names.

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "flaky" — the JSON-string cause is diagnostic-PROVEN (per-attempt raw capture). 2. Verify QUALITY not completion — a parse that yields string-keyed maps reads as "fixed" but still fails downstream (canonical-types needs keyword keys); test `empty-vocabulary?` = false + a real `canonical-types` type, not just "non-[]". 3. Instrument to root cause (done — the diagnostic). 4. Live REAL everything; the re-acceptance going green is the proof; no false green. 5. No silent fallback — a genuinely-unparseable string still honestly → `[]` → the loud hard stop stands. 6. TDD, tests first, public fns. 7. NO error-string / phrase matching; a guarded structural JSON/EDN parse only. 8. Re-orchestrate — extend the ONE shared coercion (normalize-model-spec delegates to it, so extract + vocabulary both benefit); do NOT fork. 9. Adversarial verdict — hunt a string-keyed-map "pass" that canonical-types can't read. 10. Deterministic coercion around the LLM output — verify both parse paths. 11. Key = env var; never truncate model output; JVM hygiene (one build at a time, 0 orphans, `pgrep -f`). 12. Domain-agnostic — parse whatever the model emits; name no O\*NET field. 13. `:reasoning` first on `:llm` nodes (none new here).
