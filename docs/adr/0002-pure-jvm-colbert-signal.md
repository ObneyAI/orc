# Pure-JVM ColBERT signal — delete the Python bridge

ORC's last Python dependency was the ColBERT signal: a JSON-RPC subprocess into a `.venv-colbert` running RAGatouille + PyTorch. We replaced it with a pure-JVM implementation inside the same `colbert` component: DJL OnnxRuntime engine + DJL HuggingFace tokenizers encode queries/documents with the `answerai-colbert-small-v1` checkpoint (Apache-2.0, 96-dim, official ONNX incl. int8), and exact brute-force MaxSim replaces the PLAID index. The interface namespace, dynamic resolution from ontology `hybrid-search`, graceful-degradation contract, and all `:colbert/*` event schemas are unchanged; index artifacts remain file-sidecar derived data rebuilt by the existing reindex processor.

The old integration doc argued Python was unavoidable on three grounds, none of which held: (1) the encoder is BERT + linear projection + L2-normalize — standard ONNX ops the JVM runs natively (Vespa's Java `ColBertEmbedder` and Lucene 10.3's `LateInteractionField` are prior art); (2) PLAID is a speed/compression approximation for multi-million-document corpora — at ORC's ontology-descriptions scale (tens to hundreds of documents), exact MaxSim is milliseconds and strictly higher fidelity (SIGIR 2024 reproducibility study: exact reranking beats PLAID at low-latency operating points); (3) fine-tuning genuinely requires autograd, but the entire training surface had zero callers — deleted rather than ported. If domain fine-tuning is ever wanted, the path is offline PyLate → ONNX export → served by this same JVM runtime.

## Considered options

- **Keep the Python bridge** — rejected: an entire virtualenv (~158 pinned packages) as the price of one optional signal, plus subprocess lifecycle fragility.
- **TorchScript on the existing DJL PyTorch engine** — rejected: requires us to own an offline Python trace/export step; checkpoints already ship pre-exported ONNX.
- **`colbert-ir/colbertv2.0` on JVM** — rejected: 3× the inference cost of answerai-small for equal-or-worse BEIR quality; score-scale continuity wasn't worth it since RRF normalization is scale-free and indexes are rebuilt, not migrated.
- **Lucene `LateInteractionField`** — rejected: pulls all of Lucene into a library that has none, and imposes Lucene's index lifecycle on Grain's event-sourced model.
- **Fold into the ontology component** — rejected: keeping `colbert` separate preserves the optional-classpath contract ("no third signal unless you ask for it") and the untouched integration seam.

## Consequences

- DJL artifacts in the `colbert` component are pinned to the same version ontology ships (0.31.1); repo-wide DJL upgrades are a separate concern.
- Model artifacts auto-download from HuggingFace on first use into a local cache; `-Dcolbert.model.path` overrides for air-gapped use. `scripts/setup-colbert.sh` and `.venv-colbert` are gone.
- Legacy `.ragatouille` index artifacts are unreadable orphans: the colbert layer fails loudly on them, ontology degrades to 2-signal per its existing contract, and the reindex processor rebuilds in the new format.
- `normalize-colbert-score`'s fixed ceiling is re-derived for the new checkpoint (unit-normalized tokens bound MaxSim by query token count, 32), verified empirically before the old default is replaced.
- Deletion gate: same-checkpoint numerical parity (Python exact rerank vs JVM on identical corpus/queries), live QA on a real ontology build, and green `clj -M:poly test` — only then does the bridge code leave the tree.
