# colbert_index_golden — artifact-format byte-identity golden

This directory IS an `orc-colbert-index` (format-version 1) artifact:
`embeddings.bin` + `index-meta.json`, exactly as written by the PRE-SCALE-2
single-buffer `write-index!` implementation (one contiguous `ByteBuffer` for
the whole bin, `Files/readAllBytes` on read).

- **Input**: `ai.obney.orc.colbert.index-store-test/golden-fixture` — a
  deterministic 4-passage index (dim 8; row counts 3 / 0 / 5 / 1, including
  the zero-row edge where two passages share a byte offset), floats from a
  seeded `java.util.Random` (algorithm specified by the JDK, reproducible on
  any JVM).
- **Capture**: written twice with the old implementation and compared for
  byte equality (deterministic), then copied here verbatim.
- **embeddings.bin SHA-256**:
  `525018090cc600cf83b81c38ba4780ef57ed017eda911759e787a9688db1835c`
  (288 bytes = 9 rows x 8 dims x 4 bytes), pinned as
  `golden-embeddings-sha256` in `index_store_test.clj`.

The byte-identity tests in `index_store_test.clj` use it as the compat
contract for the SCALE-2 chunked/streamed IO rewrite: the streamed writer
must reproduce these exact bytes + this exact meta string, and the streamed
reader must read THIS old-code-written artifact back exactly. No format
change, no version bump — old artifacts stay readable, new artifacts stay
readable by old code.

Do NOT regenerate casually: these bytes are the contract. If the format ever
version-bumps deliberately, capture a NEW golden alongside (v2), keep this
one for v1 compat.
