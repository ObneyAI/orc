# First-use contract check

Read this when starting an application from an ordinary business request. The human should not have to request schemas, isolated delegates or projection evidence by name. Choose the smallest useful design, and apply the relevant contracts yourself.

## Before choosing the first fields

Name each boundary: user text, authoritative source records, saved application state, delegate inputs/results, model inputs/results, and domain commands/events. Give known records concrete described fields. Use a typed dictionary for genuinely variable keys and explicit optional or nullable shapes for missing facts. Keep natural-language messages as text.

Do not wrap a known record in `pr-str`, `json/generate-string` or another serializer merely to declare its blackboard field `:string`. That loses nested validation and model-visible field meanings. Likewise, a domain event carrying a whole state snapshot as a string validates only the wrapper, not its business fields. Let the ORC/SIO and datastore adapters perform their own wire serialization. A real external text protocol can require an explicit codec; explain that boundary and restore native records after decoding it.

For example, a source observation should travel as a described map of its source identity, observed facts and missing-information state. The next delegate should receive that map as a map. Its typed assessment should return through its declared write. Reserve a separate text field for the explanation or final user-facing response. There is no required specialist count or fixed stage sequence.

## Check the delivered boundary

Resolve the actual native read/write values from durable node events, including delegate references. Compare the application packet with the original source facts and the exact serialized model request. Confirm descriptions of supplied parent and nested fields appear in the chosen transport. A strict output schema establishes only that output boundary; it does not prove structured inputs, domain events or another node family were exercised.

Check the behavior separately: current facts, user authority, returned effects and reopened projections. Record successful user behavior and contract-authoring gaps independently. A technically incomplete contract need not be invented as a business failure.

## Evidence and limits

The natural first-use experiment (historical local experiment; private archive not bundled) gave a fresh builder only a shop-owner request, ordinary skills and local services. The demo passed independent quote, revised agreement, price-cap, customer-scope and cold read-back cases. It used a typed strict intent result but serialized saved state, source facts and intermediate results as strings. The builder did not load the blackboard-contract skill and reported unrecovered truncated skill/reference reads. This motivated the earlier reading route and boundary check above.

The observation proves that a useful first application can still miss existing contract guidance. It does not prove the guidance change improves future builders: that requires another fresh attempt. It also does not establish load capacity, automatic interrupted-tick recovery, nested input-description propagation, fallback models, researchers or judges; those mechanisms were not exercised by this application.
