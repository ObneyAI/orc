#!/usr/bin/env python3
"""Compare predict-rlm vs orc Style A vs orc Style B outputs side by side.

Reads run.json files from:
  /Users/justinobney/dev/predict-rlm/bench/runs/<task>/<run_id>/run.json
  /Users/justinobney/dev/orc/bench/runs/<task>/<run_id>/run.json

Stacks are identified by the `stack` field inside each run.json. Per-task
comparison fns extract a normalized view of the actual output (letter
counts, invoice records, redaction counts, etc.) so we can tell whether
two cheap orc runs and one expensive predict-rlm run actually produced
equivalent answers — or whether the cheap runs shortcut the work.

Usage:
    python3 bench/compare.py                       # all tasks, all stacks
    python3 bench/compare.py image_analysis        # one task
    python3 bench/compare.py --since 20260426-1034 # only runs at/after timestamp
                                                   # (run_id starts with this prefix)
    python3 bench/compare.py --since 20260426-1034 image_analysis
"""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable

PREDICT_RLM_RUNS = Path("/Users/justinobney/dev/predict-rlm/bench/runs")
ORC_RUNS = Path("/Users/justinobney/dev/orc/bench/runs")
TASKS = ["image_analysis", "invoice_processing", "document_redaction",
         "contract_comparison", "document_analysis"]


# ---------------------------------------------------------------------------
# Loading
# ---------------------------------------------------------------------------

def _load_runs(task: str, since: str | None = None,
               since_orc_only: bool = True) -> list[dict[str, Any]]:
    """Load run.json files. `since` filters by run_id timestamp prefix at
    the directory-listing level (avoids reading files we'll discard). The
    orc/predict-rlm split lets us apply a cutoff to one stack only."""
    out: list[dict[str, Any]] = []
    for root, is_orc in ((PREDICT_RLM_RUNS, False), (ORC_RUNS, True)):
        task_dir = root / task
        if not task_dir.is_dir():
            continue
        cutoff = since if (since and (not since_orc_only or is_orc)) else None
        for run_dir in sorted(task_dir.iterdir()):
            if cutoff and run_dir.name < cutoff:
                continue
            f = run_dir / "run.json"
            if not f.is_file():
                continue
            try:
                d = json.loads(f.read_text())
                d["_dir"] = str(run_dir)
                out.append(d)
            except Exception as e:
                print(f"WARN: failed to load {f}: {e}", file=sys.stderr)
    return out


def _by_stack(runs: list[dict]) -> dict[str, list[dict]]:
    by: dict[str, list[dict]] = defaultdict(list)
    for r in runs:
        by[r.get("stack", "predict-rlm")].append(r)
    return by


# ---------------------------------------------------------------------------
# Per-task extractors — pull a normalized "what the run actually produced"
# from each stack's structured field.
# ---------------------------------------------------------------------------

_TRACE_FIELDS = frozenset({"trace", "trajectory", "final_reasoning"})


def _result_scope(structured: Any) -> Any:
    """Narrow `structured` to just the actual workflow output, skipping
    DSPy trajectory/trace fields that would otherwise let our walkers
    double-count (predict-rlm embeds invoice records in BOTH `result.invoices`
    AND `trace.steps.predict_calls.calls.output`, etc.)."""
    if not isinstance(structured, dict):
        return structured
    if "result" in structured:
        return structured["result"]
    return {k: v for k, v in structured.items() if k not in _TRACE_FIELDS}


def _find_values(obj: Any, target_keys: tuple[str, ...]):
    """Depth-first yield of every value in `obj` whose parent key is in
    `target_keys`."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in target_keys:
                yield v
            yield from _find_values(v, target_keys)
    elif isinstance(obj, list):
        for it in obj:
            yield from _find_values(it, target_keys)

def _extract_image_analysis(r: dict) -> dict[str, Any]:
    """Letter counts (A-Z) and indicators that the rigor (self-consistency,
    counting in code) was actually performed."""
    s = r.get("structured") or {}
    answer = s.get("answer", "") if isinstance(s, dict) else ""
    if not isinstance(answer, str):
        answer = json.dumps(answer)

    # Try to parse "A: N" or "A:N" pairs
    counts: dict[str, int] = {}
    for ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        m = re.search(rf"\b{ch}\s*:\s*(\d+)\b", answer)
        if m:
            counts[ch] = int(m.group(1))

    return {
        "answer_chars": len(answer),
        "letter_counts": counts if len(counts) >= 20 else {},
        "all_26_letters": len(counts) == 26,
        "mentions_consistency": bool(re.search(r"consisten|attempts|extract.*\d.*time", answer, re.I)),
        "mentions_letter_count": bool(re.search(r"letter\s*count|count.*letter", answer, re.I)),
        "answer_preview": answer[:200].replace("\n", " ⏎ "),
    }


def _walk_for_invoices(obj: Any) -> list[dict]:
    """Maps containing both a vendor-name and invoice-number (kebab or snake)."""
    def is_invoice(x):
        if not isinstance(x, dict):
            return False
        ks = set(x.keys())
        return ({"vendor-name", "invoice-number"}.issubset(ks)
                or {"vendor_name", "invoice_number"}.issubset(ks))
    def walk(x):
        if is_invoice(x):
            yield x
        if isinstance(x, dict):
            for v in x.values():
                yield from walk(v)
        elif isinstance(x, list):
            for it in x:
                yield from walk(it)
    return list(walk(obj))


def _extract_invoice_processing(r: dict) -> dict[str, Any]:
    scope = _result_scope(r.get("structured"))
    if not isinstance(scope, dict):
        return {"error": "no structured result"}
    invoices = _walk_for_invoices(scope)
    totals = [float(v) for v in _find_values(scope, ("total-amount", "total_amount", "total"))
              if isinstance(v, (int, float))]
    return {
        "invoices_found": len(invoices),
        "vendor_names": sorted({i.get("vendor-name") or i.get("vendor_name", "?") for i in invoices}),
        "invoice_numbers": sorted({i.get("invoice-number") or i.get("invoice_number", "?") for i in invoices}),
        "line_item_counts": [
            len(i.get("line-items") or i.get("line_items") or [])
            for i in invoices
        ],
        "totals_seen": sorted(set(totals)),
        "produced_xlsx": any(o.get("path", "").endswith(".xlsx") for o in r.get("outputs", [])),
    }


def _extract_document_redaction(r: dict) -> dict[str, Any]:
    scope = _result_scope(r.get("structured"))
    if not isinstance(scope, dict):
        return {"error": "no structured result"}
    # predict-rlm: result.targets [{page, text, category, reason}]; result.total_redactions
    # orc:        all-targets / doc-targets / doc-redactions (similar shape)
    items = [it for lst in _find_values(scope, ("targets", "all-targets", "doc-targets",
                                                 "redactions", "redacted_items"))
             if isinstance(lst, list)
             for it in lst if isinstance(it, dict)]
    declared_total = next(iter(_find_values(scope, ("total_redactions", "total-redactions"))), None)
    by_cat: dict[str, int] = defaultdict(int)
    for it in items:
        by_cat[str(it.get("category") or it.get("type") or it.get("kind") or "?")] += 1
    return {
        "redactions_total": len(items),
        "declared_total": declared_total,
        "redactions_by_category": dict(by_cat),
        "produced_redacted_pdfs": sum(1 for o in r.get("outputs", []) if o.get("path", "").endswith(".pdf")),
    }


def _longest_str(d: dict, keys: tuple[str, ...]) -> str:
    """Pick the longest string value among `keys` (or .report nested)."""
    best = ""
    for k in keys:
        v = d.get(k)
        if isinstance(v, str) and len(v) > len(best):
            best = v
        elif isinstance(v, dict) and isinstance(v.get("report"), str) and len(v["report"]) > len(best):
            best = v["report"]
    return best


def _extract_contract_comparison(r: dict) -> dict[str, Any]:
    scope = _result_scope(r.get("structured"))
    if not isinstance(scope, dict):
        return {"error": "no structured result (predict-rlm wrapper bug for tasks returning bare Pydantic)"}
    diffs = [it for lst in _find_values(scope, ("section_diffs", "section-diffs", "diffs",
                                                 "key_differences", "key-differences"))
             if isinstance(lst, list) for it in lst]
    sigs: dict[str, int] = defaultdict(int)
    for d in diffs:
        if isinstance(d, dict):
            sigs[str(d.get("significance") or d.get("impact") or "?")] += 1
    report = _longest_str(scope, ("report", "comparison-report", "answer"))
    return {
        "diff_count": len(diffs),
        "by_significance": dict(sigs),
        "report_chars": len(report),
        "report_preview": report[:200].replace("\n", " ⏎ "),
    }


def _extract_document_analysis(r: dict) -> dict[str, Any]:
    scope = _result_scope(r.get("structured"))
    if not isinstance(scope, dict):
        return {"error": "no structured result"}
    key_dates = [it for lst in _find_values(scope, ("key_dates", "key-dates"))
                 if isinstance(lst, list) for it in lst]
    key_entities = [it for lst in _find_values(scope, ("key_entities", "key-entities"))
                    if isinstance(lst, list) for it in lst]
    report = _longest_str(scope, ("report", "analysis", "answer"))
    return {
        "key_dates_count": len(key_dates),
        "key_entities_count": len(key_entities),
        "report_chars": len(report),
        "report_preview": report[:200].replace("\n", " ⏎ "),
        "produced_docx": any(o.get("path", "").endswith(".docx") for o in r.get("outputs", [])),
    }


EXTRACTORS: dict[str, Callable[[dict], dict[str, Any]]] = {
    "image_analysis": _extract_image_analysis,
    "invoice_processing": _extract_invoice_processing,
    "document_redaction": _extract_document_redaction,
    "contract_comparison": _extract_contract_comparison,
    "document_analysis": _extract_document_analysis,
}


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def _total_tokens(r: dict) -> int:
    t = r.get("tokens") or {}
    def s(side):
        x = t.get(side) or {}
        return (x.get("input") or 0) + (x.get("output") or 0)
    return s("root") + s("sub")


def _summarize(runs: list[dict]) -> dict[str, Any]:
    ok = [r for r in runs if not r.get("error")]
    errs = [r for r in runs if r.get("error")]
    costs = sorted(r["cost"]["total"] for r in ok)
    durs  = sorted(r["duration_seconds"] for r in ok)
    toks  = sorted(_total_tokens(r) for r in ok)
    med = lambda xs: xs[len(xs)//2] if xs else None
    return {
        "n": len(runs),
        "n_ok": len(ok),
        "n_err": len(errs),
        "cost_med": med(costs),
        "cost_min": costs[0] if costs else None,
        "cost_max": costs[-1] if costs else None,
        "dur_med_s": med(durs),
        "tokens_med": med(toks),
        "tokens_min": toks[0] if toks else None,
        "tokens_max": toks[-1] if toks else None,
    }


def _report_task(task: str, only_recent: bool = True,
                  since: str | None = None) -> None:
    """`since` filters orc-* stacks only — the input-key bug we fixed at
    that timestamp was orc-only, so predict-rlm runs are always valid."""
    runs = _load_runs(task, since=since, since_orc_only=True)
    if not runs:
        print(f"\n## {task}\n  (no runs)\n")
        return
    by_stack = _by_stack(runs)
    extractor = EXTRACTORS[task]

    suffix = f" — orc runs since {since}" if since else ""
    print(f"\n## {task}{suffix}\n")
    for stack in ("predict-rlm", "orc-style-legacy", "orc-style-a", "orc-style-b"):
        rs = sorted(by_stack.get(stack, []), key=lambda r: r.get("run_id", ""))
        if only_recent and rs and not (since and stack.startswith("orc")):
            # Recent-3 truncation only applies when no since-cutoff is in
            # play for this stack (otherwise we want all post-cutoff runs).
            rs = rs[-3:]
        if not rs:
            print(f"### {stack}\n  (no runs)\n")
            continue
        s = _summarize(rs)
        print(f"### {stack}  —  n={s['n']} (ok={s['n_ok']}, err={s['n_err']})")
        if s["cost_med"] is not None:
            print(f"  cost   ${s['cost_min']:.4f}–${s['cost_max']:.4f} (med ${s['cost_med']:.4f})  "
                  f"dur med {s['dur_med_s']:.1f}s")
            print(f"  tokens {s['tokens_min']:,}–{s['tokens_max']:,} (med {s['tokens_med']:,})")
        for r in rs:
            ext = extractor(r)
            err = " ERR" if r.get("error") else ""
            cost = r["cost"]["total"]
            calls = r["calls"]
            tok = r.get("tokens") or {}
            rin  = (tok.get("root") or {}).get("input")  or 0
            rout = (tok.get("root") or {}).get("output") or 0
            sin  = (tok.get("sub")  or {}).get("input")  or 0
            sout = (tok.get("sub")  or {}).get("output") or 0
            print(f"  • {r['run_id'][:13]} ${cost:7.4f} "
                  f"root[{calls['root']:>2}] {rin:>6,}/{rout:>5,}  "
                  f"sub[{calls['sub']:>3}] {sin:>6,}/{sout:>5,}{err}")
            for k, v in ext.items():
                print(f"      {k}: {v}")
        print()


def main() -> None:
    args = sys.argv[1:]
    since: str | None = None
    if "--since" in args:
        i = args.index("--since")
        since = args[i + 1]
        args = args[:i] + args[i + 2:]
    tasks = args or TASKS
    header = "# Bench comparison report"
    if since: header += f" (runs at/after {since})"
    print(header + "\n")
    for t in tasks:
        if t not in EXTRACTORS:
            print(f"WARN: unknown task {t}")
            continue
        _report_task(t, since=since)


if __name__ == "__main__":
    main()
