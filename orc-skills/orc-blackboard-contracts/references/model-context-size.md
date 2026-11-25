# Model switching and accumulated context

Use the normal ORC/SIO model setting and concrete described contracts. Switching models does not call for an application parser, response-key rewriting, JSON unwrapping or a model-name branch. Token allowance may need adjustment for reasoning and the required answer.

When a complex journey fails, identify the exact boundary before changing its contracts. Distinguish a locally refused request, provider rejection, incomplete generation, paired decoding failure, canonical write mismatch, and a semantically incorrect but correctly typed decision. A nil root output after an upstream failure does not establish an empty provider response. A local conservative byte/token admission estimate is not the provider's measured context window.

Keep authoritative progress and model-facing context separate when repeated evidence makes requests large. Ordinary code leaves can assemble a typed context packet; ORC/SIO still handles its normal serialization and generated outputs. Choose the packet for the task, with the same representation across models. This is tree design, not required model adaptation.

For repeated immutable records, one lossless option is a catalog of complete original records plus typed identity references at their former locations. Preserve currentness and chronology on each containing observation. Describe how to resolve references, retain all unique facts and reject conflicting full records under one supposedly immutable identity. Verify rehydration against the original canonical state. This pattern assumes immutable record identities; a host whose records legitimately evolve needs an explicit versioned contract instead. It is optional, not a universal wrapper for ORC nodes.

Validate the actual request messages and strict output schema, including nested descriptions, the paired decoded types and receiving canonical writes. Inspect the final writer's actual input and the independently observed current result. Do not infer end-to-end model parity from a schema preflight or a single successful trajectory.

When investigating exact value fidelity in JSON-schema mode, independently compare standard JSON-decoded provider fields with canonical native writes through the declared output-group mapping. This catches missing/extra keys, changed text, altered collection order or boolean/integer confusion that a type-only check may miss. Comparing two uses of the same production decoder alone cannot rule out silent value changes. This is an evidence audit, not an application decoder or a reason to add per-model parsing.

Lossless duplicate factoring does not bound arbitrary history or prove relevance selection. Continue to design focused context around the active obligation and preserve indispensable qualifications, prior drafts and feedback. Do not use arbitrary truncation to hide growth.
