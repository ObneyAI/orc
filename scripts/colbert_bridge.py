#!/usr/bin/env python3
"""
ColBERT Bridge - JSON-RPC subprocess for Clojure interop.

Protocol: JSON lines over stdin/stdout
Request:  {"id": 1, "method": "search", "params": {...}}
Response: {"id": 1, "result": {...}} or {"id": 1, "error": {...}}

This bridge provides access to RAGatouille/ColBERT functionality from Clojure.
It handles model loading, index creation, search, reranking, and training.

Why Python is Required:
- ColBERT is a 110M parameter PyTorch neural network
- PLAID indexing is a specialized algorithm (2000+ lines)
- Training requires PyTorch autograd for gradient computation
"""

import json
import sys
import traceback
import os
import contextlib
from pathlib import Path

# Ensure venv bin is in PATH for ninja and other tools
# This is needed because ColBERT/PyTorch needs ninja for JIT compilation
script_dir = Path(__file__).parent.parent.resolve()
venv_bin = script_dir / ".venv-colbert" / "bin"
if venv_bin.exists():
    current_path = os.environ.get("PATH", "")
    if str(venv_bin) not in current_path:
        os.environ["PATH"] = f"{venv_bin}:{current_path}"

# Lazy imports to speed up startup - only import when needed
_rag_model = None
_rag_trainer = None


@contextlib.contextmanager
def suppress_stdout():
    """Context manager to suppress stdout during ColBERT operations.

    ColBERT/transformers/tqdm print progress messages to stdout which
    interferes with our JSON-RPC framing. This redirects BOTH sys.stdout
    (Python-level prints) AND file descriptor 1 (C-extension prints like
    torch printf) to devnull during operations.
    """
    devnull = os.open(os.devnull, os.O_WRONLY)
    old_stdout_fd = os.dup(1)
    old_stdout = sys.stdout
    try:
        sys.stdout.flush()
        os.dup2(devnull, 1)
        sys.stdout = open(os.devnull, 'w')
        yield
    finally:
        sys.stdout.flush()
        sys.stdout.close()
        sys.stdout = old_stdout
        os.dup2(old_stdout_fd, 1)
        os.close(old_stdout_fd)
        os.close(devnull)


def get_ragatouille():
    """Lazy load RAGatouille to reduce startup time.

    The import itself prints a deprecation banner to stdout (PyLate
    migration notice). That breaks our JSON-RPC framing — the Clojure
    bridge reads the first stdout line as JSON. So we suppress stdout
    during the import."""
    global _rag_model, _rag_trainer
    if _rag_model is None:
        with suppress_stdout():
            from ragatouille import RAGPretrainedModel, RAGTrainer
        _rag_model = RAGPretrainedModel
        _rag_trainer = RAGTrainer
    return _rag_model, _rag_trainer


class ColBERTBridge:
    """
    Bridge class managing ColBERT models and trainers.

    Models are stored by alias to support multiple concurrent indexes.
    """

    def __init__(self, index_root=".ragatouille"):
        self.models = {}      # alias -> RAGPretrainedModel
        self.trainers = {}    # alias -> RAGTrainer
        self.index_root = Path(index_root)

    def handle(self, request):
        """Handle a JSON-RPC request and return response."""
        method = request.get("method")
        params = request.get("params", {})
        req_id = request.get("id")

        try:
            handler = getattr(self, f"do_{method}", None)
            if not handler:
                return {
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Unknown method: {method}"
                    }
                }
            result = handler(**params)
            return {"id": req_id, "result": result}
        except Exception as e:
            return {
                "id": req_id,
                "error": {
                    "code": -32000,
                    "message": str(e),
                    "traceback": traceback.format_exc()
                }
            }

    # =========================================================================
    # Model Management
    # =========================================================================

    def do_load_model(self, alias, model_name=None, index_path=None):
        """
        Load a ColBERT model from pretrained or existing index.

        Args:
            alias: Unique identifier for this model instance
            model_name: HuggingFace model name (e.g., "colbert-ir/colbertv2.0")
            index_path: Path to existing index (loads from index instead)

        Returns:
            {"status": "loaded", "alias": str}
        """
        # Idempotent: the alias is the unique index-id, so if it's already loaded the
        # right model/index is already in memory. Skip the (expensive) reload — callers
        # invoke load_model before every search, and reloading the index from disk per
        # search makes per-line retrieval (e.g. cleaning a transcript) pathologically
        # slow (one full index load per query).
        if alias in self.models:
            return {"status": "cached", "alias": alias}

        RAGPretrainedModel, _ = get_ragatouille()

        with suppress_stdout():
            if index_path:
                self.models[alias] = RAGPretrainedModel.from_index(index_path)
            else:
                self.models[alias] = RAGPretrainedModel.from_pretrained(
                    model_name or "colbert-ir/colbertv2.0",
                    index_root=str(self.index_root)
                )
        return {"status": "loaded", "alias": alias}

    def do_unload_model(self, alias):
        """
        Unload a model to free memory.

        Args:
            alias: Model alias to unload

        Returns:
            {"status": "unloaded", "alias": str}
        """
        if alias in self.models:
            del self.models[alias]
        return {"status": "unloaded", "alias": alias}

    def do_list_models(self):
        """
        List all loaded model aliases.

        Returns:
            {"models": [str]}
        """
        return {"models": list(self.models.keys())}

    # =========================================================================
    # Index Operations
    # =========================================================================

    def do_create_index(self, alias, collection, index_name, document_ids=None,
                        document_metadatas=None, split_documents=True,
                        max_document_length=256, use_faiss=False):
        """
        Create a PLAID index from documents.

        Args:
            alias: Model alias to use
            collection: List of document strings
            index_name: Name for the index
            document_ids: Optional list of document IDs
            document_metadatas: Optional list of metadata dicts
            split_documents: Whether to auto-split long documents
            max_document_length: Max tokens per passage (chunk size)
            use_faiss: Use FAISS instead of PLAID (not recommended)

        Returns:
            {"index_path": str, "index_name": str, "num_passages": int}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded. Call load_model first.")

        with suppress_stdout():
            path = model.index(
                collection=collection,
                document_ids=document_ids,
                document_metadatas=document_metadatas,
                index_name=index_name,
                split_documents=split_documents,
                max_document_length=max_document_length,
                use_faiss=use_faiss
            )

        # Get passage count - try multiple attributes as API varies by version
        num_passages = len(collection)  # Default
        try:
            if hasattr(model, 'model') and hasattr(model.model, 'docid_to_doc'):
                num_passages = len(model.model.docid_to_doc)
            elif hasattr(model, 'model') and hasattr(model.model, 'index_path'):
                # Try to read from index metadata
                pass
        except Exception:
            pass

        return {
            "index_path": str(path),
            "index_name": index_name,
            "num_passages": num_passages
        }

    def do_add_to_index(self, alias, new_collection, new_document_ids=None,
                        new_document_metadatas=None, index_name=None):
        """
        Add documents to an existing index.

        Args:
            alias: Model alias to use
            new_collection: List of new document strings
            new_document_ids: Optional list of document IDs
            new_document_metadatas: Optional list of metadata dicts
            index_name: Index name to update

        Returns:
            {"status": "updated", "documents_added": int}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        with suppress_stdout():
            model.add_to_index(
                new_collection=new_collection,
                new_document_ids=new_document_ids,
                new_document_metadatas=new_document_metadatas,
                index_name=index_name
            )

        return {
            "status": "updated",
            "documents_added": len(new_collection)
        }

    def do_delete_from_index(self, alias, document_ids, index_name=None):
        """
        Delete documents from an existing index by ID.

        Args:
            alias: Model alias to use
            document_ids: List of document IDs to remove
            index_name: Index name to update

        Returns:
            {"status": "updated", "documents_removed": int}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        with suppress_stdout():
            model.delete_from_index(
                document_ids=document_ids,
                index_name=index_name
            )

        return {
            "status": "updated",
            "documents_removed": len(document_ids)
        }

    # =========================================================================
    # Search Operations
    # =========================================================================

    def do_search(self, alias, query, k=10, index_name=None, doc_ids=None):
        """
        Search the indexed corpus using ColBERT late-interaction.

        Args:
            alias: Model alias to use
            query: Search query string
            k: Number of results to return
            index_name: Optional specific index to search
            doc_ids: Optional list of doc IDs to filter results

        Returns:
            {"results": [{"content": str, "score": float, "rank": int,
                         "document_id": str, "document_metadata": dict}]}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        with suppress_stdout():
            results = model.search(
                query=query,
                k=k,
                index_name=index_name,
                doc_ids=doc_ids
            )

        # Format results with consistent structure
        formatted = []
        for i, r in enumerate(results):
            formatted.append({
                "content": r.get("content", ""),
                "score": float(r.get("score", 0)),
                "rank": i + 1,  # 1-indexed rank
                "document_id": r.get("document_id", ""),
                "document_metadata": r.get("document_metadata", {})
            })

        return {"results": formatted}

    def do_search_batch(self, alias, queries, k=10, index_name=None):
        """
        Batch search for multiple queries.

        Args:
            alias: Model alias to use
            queries: List of query strings
            k: Number of results per query
            index_name: Optional specific index to search

        Returns:
            {"results": [[{...}, ...], ...]}  # List of result lists
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        all_results = []
        with suppress_stdout():
            for query in queries:
                results = model.search(query=query, k=k, index_name=index_name)
                formatted = []
                for i, r in enumerate(results):
                    formatted.append({
                        "content": r.get("content", ""),
                        "score": float(r.get("score", 0)),
                        "rank": i + 1,
                        "document_id": r.get("document_id", ""),
                        "document_metadata": r.get("document_metadata", {})
                    })
                all_results.append(formatted)

        return {"results": all_results}

    def do_rerank(self, alias, query, documents, k=None):
        """
        Rerank documents in-memory (no index required).

        This encodes query and documents on-the-fly and scores them.
        Useful for reranking candidates from other retrieval methods.

        Args:
            alias: Model alias to use
            query: Query string
            documents: List of document strings to rerank
            k: Number of results to return (default: all)

        Returns:
            {"results": [{"content": str, "score": float, "rank": int}]}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        k = k or len(documents)
        with suppress_stdout():
            results = model.rerank(query=query, documents=documents, k=k)

        formatted = []
        for i, r in enumerate(results):
            formatted.append({
                "content": r.get("content", ""),
                "score": float(r.get("score", 0)),
                "rank": i + 1
            })

        return {"results": formatted}

    # =========================================================================
    # Training Operations
    # =========================================================================

    def do_create_trainer(self, alias, model_name, pretrained_model_name=None,
                          language_code="en"):
        """
        Create a trainer instance for fine-tuning.

        Args:
            alias: Unique identifier for this trainer
            model_name: Name for the new model being trained
            pretrained_model_name: Base model to fine-tune from
            language_code: Language code for the model

        Returns:
            {"status": "created", "alias": str}
        """
        _, RAGTrainer = get_ragatouille()

        with suppress_stdout():
            self.trainers[alias] = RAGTrainer(
                model_name=model_name,
                pretrained_model_name=pretrained_model_name or "colbert-ir/colbertv2.0",
                language_code=language_code
            )

        return {"status": "created", "alias": alias}

    def do_prepare_training_data(self, alias, raw_data, data_out_path,
                                  all_documents=None, mine_hard_negatives=True,
                                  num_new_negatives=10,
                                  hard_negative_minimum_rank=10):
        """
        Prepare training data with optional hard negative mining.

        Raw data formats supported:
        - pairs: [[query, positive], ...]
        - labeled_pairs: [[query, passage, label], ...] where label is 0/1
        - triplets: [[query, positive, negative], ...]

        Args:
            alias: Trainer alias
            raw_data: Training data in one of the supported formats
            data_out_path: Path to write processed training data
            all_documents: Optional corpus for hard negative mining
            mine_hard_negatives: Whether to mine hard negatives
            num_new_negatives: Negatives to mine per positive
            hard_negative_minimum_rank: Skip top-N results as too easy

        Returns:
            {"data_path": str, "num_triplets": int}
        """
        trainer = self.trainers.get(alias)
        if not trainer:
            raise ValueError(f"Trainer '{alias}' not created. Call create_trainer first.")

        with suppress_stdout():
            path = trainer.prepare_training_data(
                raw_data=raw_data,
                all_documents=all_documents,
                data_out_path=data_out_path,
                mine_hard_negatives=mine_hard_negatives,
                num_new_negatives=num_new_negatives,
                hard_negative_minimum_rank=hard_negative_minimum_rank
            )

        # Count triplets in output
        triplets_path = Path(path) / "triples.train.colbert.jsonl"
        num_triplets = 0
        if triplets_path.exists():
            with open(triplets_path) as f:
                num_triplets = sum(1 for _ in f)

        return {
            "data_path": str(path),
            "num_triplets": num_triplets
        }

    def do_train(self, alias, batch_size=32, nbits=2, maxsteps=500000,
                 learning_rate=5e-6, dim=128, doc_maxlen=256, use_ib_negatives=True,
                 warmup_steps=0, accumsteps=1):
        """
        Train/fine-tune a ColBERT model.

        Training happens on the prepared data from prepare_training_data.

        Args:
            alias: Trainer alias
            batch_size: Training batch size
            nbits: Compression bits for PLAID (2 recommended)
            maxsteps: Maximum training steps
            learning_rate: Learning rate
            dim: Embedding dimension
            doc_maxlen: Maximum document length
            use_ib_negatives: Use in-batch negatives
            warmup_steps: Learning rate warmup steps
            accumsteps: Gradient accumulation steps

        Returns:
            {"checkpoint_path": str, "final_step": int}
        """
        trainer = self.trainers.get(alias)
        if not trainer:
            raise ValueError(f"Trainer '{alias}' not created")

        with suppress_stdout():
            checkpoint = trainer.train(
                batch_size=batch_size,
                nbits=nbits,
                maxsteps=maxsteps,
                learning_rate=learning_rate,
                dim=dim,
                doc_maxlen=doc_maxlen,
                use_ib_negatives=use_ib_negatives,
                warmup_steps=warmup_steps,
                accumsteps=accumsteps
            )

        return {
            "checkpoint_path": str(checkpoint),
            "final_step": maxsteps
        }

    # =========================================================================
    # Utility Operations
    # =========================================================================

    def do_ping(self):
        """Health check."""
        return {"status": "ok"}

    def do_get_index_info(self, alias):
        """
        Get information about the loaded index.

        Args:
            alias: Model alias

        Returns:
            {"index_path": str, "num_documents": int, "model_name": str}
        """
        model = self.models.get(alias)
        if not model:
            raise ValueError(f"Model '{alias}' not loaded")

        info = {
            "index_path": str(model.model.index_path) if hasattr(model, 'model') else None,
            "model_name": model.model.model_name if hasattr(model, 'model') else None
        }

        return info

    def do_shutdown(self):
        """Graceful shutdown."""
        return {"status": "shutting_down"}


def main():
    """Main entry point - read JSON lines from stdin, write responses to stdout."""
    bridge = ColBERTBridge()

    # Use unbuffered output
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            response = {
                "error": {
                    "code": -32700,
                    "message": f"Parse error: {e}"
                }
            }
            print(json.dumps(response), flush=True)
            continue

        response = bridge.handle(request)
        print(json.dumps(response), flush=True)

        # Handle shutdown request
        if request.get("method") == "shutdown":
            break


if __name__ == "__main__":
    main()
