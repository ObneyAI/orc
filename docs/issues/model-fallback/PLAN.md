# Standard-node model fallback revision

This local revision supersedes the wrapper-only implementation at dcf5fbe7. No push, public PR update or merge without renewed user approval.

## Accepted contract

Existing llm, llm-condition, llm-decision and repl-researcher nodes accept :provider, :fallback-models (ordered maps requiring :model with optional :provider and generation :options), and :fallback-policy. Primary remains :model. Candidate provider omission resolves against the effective primary provider. Default policy temporary-transport; explicit all-transport remains available. Empty backup list disables inherited fallbacks. Remove the unmerged llm-fallback helper, preserving historical events.

Researchers keep prime settings on the node and specialist defaults in :rlm via :sub-provider, :sub-model, :sub-options, :sub-fallback-models and :sub-fallback-policy. Inline and emitted calls inherit these. Primary-only overrides retain backups. Sticky successful selection persists per run, role and effective configuration; concurrent older completions cannot move selection backwards. New runs start with primary.

One physical request per candidate by default; explicit same-model retries precede switching. Outer retries must not restart an exhausted chain. Model switching remains inside the failed logical request, preserves contracts and progress, and consumes original budget/deadline. No completed tools are replayed. Interrupted unknown model requests may need a counted retry.

Compatible registered providers may differ in protocol when existing adapters preserve the effective task/decision contract. Invalid configurations fail before dispatch; no strict downgrade or invented confidence. Temporary evidence includes noncontradictory 5xx/408, typed timeouts/connections/rate limits; bare429 remains ambiguous. Semantic negative, abstain, tie, malformed outputs and limits never trigger switching.

Streaming switches before visible output only. First-output state prevents replacement after partial output, including recovery. Repair asynchronous client error evidence/sanitization at the producer boundary; do not assume a synchronous catch covers it.

## Work and acceptance

Root owns specs/checklist and independent acceptance. Configuration, shared runner and researcher integration are delegated with disjoint file sections. Streaming follows the produced shared API. NODE-FALLBACK-1 through 6 track focused real Grain/datastore/local HTTP/native/SSE proofs, including checkpoint recovery, ownership, limits and option propagation. No broad test sweeps after every edit. Compare Allium diagnostics with baseline. Update skills only after proof and have a fresh agent exercise the documented API. Prepare reviewable local changes and ask before publication.

## Local acceptance complete

Independent integrated run: 20 tests/371 assertions, zero failures/errors. Fresh-agent ordinary/refusal/researcher inheritance recipe: seven controlled HTTP requests, observed outcomes and public diagnostics agree. Allium zero errors with baseline39 warnings/114 informational diagnostics and zero analysis findings. Source/client hashes match the tested files. Local skills and review package carry the support boundary. Publication awaits explicit user approval, including the companion client repair and dependency-pin step; no commit/push/PR update was performed.
