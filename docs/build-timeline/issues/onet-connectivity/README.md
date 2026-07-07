# O*NET Connectivity — issue index (LOCAL, never committed)

Root cause (proven, `project_graph_b_disconnection_root_cause`): O*NET (excel-dir) graphs are DISCONNECTED — occupations carry 0 cross-sheet edges — because the **excel container-contract's `:relations` op is nil** (SQL has FK relations, CSV has relationship-hints, excel has none), so MC-6's deterministic cross-sheet edge-derivation never fires. Secondary: the grain vocabulary has no junction/edge option, so SOC→element junction sheets aggregate to occupation attributes (no element nodes for MC-6 to join). CONNECT-1 (selection-ranking key-mangling) already landed (`8d025f16`) — necessary but upstream-blocked by this.

Ordering (story): CONNECT-2 (the missing capability) → CONNECT-3 (grain so entities exist to edge) → CONNECT-4 (connectivity acceptance). CONNECT-3's handoff is crafted AFTER CONNECT-2 lands + its real API / MC-6 behavior is inspected (dependency rule).

- [CONNECT-2](CONNECT-2-excel-relations-op.md) — excel `:relations` op (heuristic shared-key cross-sheet relations). AFK. Blocked-by: none.
- [CONNECT-3](CONNECT-3-junction-grain-element-nodes.md) — grain: junction sheets mint shared element nodes. Blocked-by: CONNECT-2 (craft after it lands).
- [CONNECT-4](CONNECT-4-connectivity-acceptance.md) — bounded O*NET build yields occupation↔skill/task cross-sheet edges. Blocked-by: CONNECT-2 + CONNECT-3.
