# Scoped model alternatives

For merged standard-node fallback configuration, use [configured model fallback](configured-model-fallback.md). The evidence-only revision and hand-assembled recipe below remain historical references.

Use this when designing an ordered model chain for one typed task. A BT fallback selects behavior; provider failover repeats the same task with another compatible model. A node failure alone does not identify an outage.

## Historical source and support boundary

The following observations retain their original experiment pins and local/unmerged labels. Configured fallback and its supporting repairs are now integrated in merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e`; use [current integration status](model-portability.md#current-authoring-and-source-checkpoint) for adoption, including the separate unmerged PR #50 correction. This does not rerun the historical proofs or enlarge their scope.

Merged ORC `250de6e6` already includes broad `failure-kind` and provider evidence. Its ordinary catch still loses the pinned client's category/status and retains unsafe exception text/cause. [ORC PR #48](https://github.com/ObneyAI/orc/pull/48), commit `393c600e94de99862514128a0852008dd49854ea`, proposes safe typed ordinary transport evidence and inherited deadline caps. **The PR was open and unmerged at that historical checkpoint**; see repair and patch (historical local experiment; private archive not bundled) and coverage/limits (historical local experiment; private archive not bundled). Verify the consuming code/schema rather than assuming this exists on main.

The local repair adds optional closed, nonempty `provider-evidence.transport`: recognized client `:type`, integer `:http-status`100–599, or both. Unknown fields are omitted. The public summary uses only recognized values and has no original cause. Provider-not-configured remains distinct; invalid output is not reclassified. A separate local native repair (historical local experiment; private archive not bundled), also unmerged, retains direct top-level integer `:status`100–599 as `:transport {:http-status ...}` with no invented category, unsafe message or cause. Independently observed real HTTP429 reaches native completion/trace and the one-response wrapper’s single error then close (2 tests/51 assertions,3 local requests). A status-only429 is not evidence of a temporary rate limit. Custom native classification, chat producer errors and strict custom adapters remain open.

## Consumer design

Keep already completed tools/siblings outside the candidate boundary. A useful shape is `completed work → delegate same task → fallback(primary, sequence(read-own-failure, eligible?, alternative))`. Each candidate uses the same semantic instruction, typed input/output contract and compatible options, with its configured model identity separate. Give all candidates the same root call-budget identity and existing task deadline; changing model is not a new authorization or a new task.

The admission gate must load the exact predecessor from the owning tenant's canonical events. Match sheet/tick, node, execution context including mapped item, canonical task input values, and completion before the gate's own persisted start. Deny missing or ambiguous ownership instead of guessing a nearest failure. The tested example has one primary attempt; repeated attempts require an explicit attempt selector, not copying that single-completion assumption.

Choose and document a policy independently of the tree shape. The example allows typed rate-limit with absent/429 status, timeout with absent/408/504, connection-error with absent status, and server/provider-error with5xx. It denies unknown, contradictory, quota/auth/config and schema/business outcomes. A429 alone cannot distinguish quota from a temporary rate limit. An application's authorized all-transport compatibility policy is a different policy and must not be silently changed during a runtime pin update.

Keep model-specific capabilities and generation limits compatible with the same output contract. Preserve requested and served identity separately. Count physical requests, including client/node retries, and let existing family call-budget admission stop an alternative even when the failure classifier permits it. Do not infer retry permission from unused budget alone.

## Verify the intended scope

The local example independently passed1 test/108 assertions through ordinary blocking HTTP, real Grain with an in-memory store and canonical event readback. Two concurrent roots use one delegated sheet; A's502 switches once, B's valid false uses one request. Each completed fixture tool runs once. Real quota429, auth401, invalid boolean and family budget1 stop further requests; total7. A's serialized bodies are identical except model and its canonical inputs match. This proves controlled routing and contracts, not real-model semantic quality or production scale.

A separate paired HTTP429 observation (historical local experiment; private archive not bundled) independently passed1 test/57 assertions with3 local requests: typed rate-limit429 selects one same-task alternative; quota-exceeded429 selects none. Exact ownership, candidate inputs/options, budgets/deadlines and one fixture tool per task are retained. The pinned client derives this distinction from lowercase `quota` in the stringified response body. Treat that as a source-specific heuristic, not authoritative classification of every provider’s429 response; status-only native429 still supplies no temporary-failure category.

Negative controls must fail for the expected reason, not merely return non-success. Unknown/contradictory/mapped-owner controls here are predicate observations, not corresponding HTTP or mapped-parallel runs. The separate native status proof does not establish native model-chain admission, partial-stream safety or restart effects.

Measure deadlines on a surface that retains them: durable node traces do not project the execution deadline at this pin. The original example captured frozen tick options but did not test expiry. A separate local, unmerged deadline repair (historical local experiment; private archive not bundled) now passes the parent’s absolute cap into public `execute`, which stores the lesser of that cap and its local deadline. Controlled delegate dispatch delay cannot renew it; a tighter local allowance still applies and a spent cap stays spent. An independent actual-expiry case uses the authenticated tick command without the blocking caller timer: the alternative records the exact admission timeout before another provider request. This was3 tests/34 assertions with one local HTTP502. The cap governs provider admission; it does not shorten blocking caller wait or interrupt arbitrary code/tools. Streaming and other child constructors remain unverified. Verify the consuming source before relying on this unmerged behavior, and do not present unchanged options alone as an exhaustion test.

## Adopt the repair in a consumer

A dependency pin alone does not prove adoption. A consumer can place patched source directories ahead of dependencies on its classpath. Inspect the actual loaded resource URLs and hashes for the provider adapter, execution code, evidence schema and any compatibility overrides. Rebuild existing overrides against the intended repair while preserving their necessary behavior; do not delete them merely to expose the new dependency.

A verified blocking-call consumer preserved seven compatibility overrides, including successful-response evidence, provider identity and scoped reads. Its actual compiler, runtime and persisted-predecessor admission path passed one focused checkpoint: two local HTTP calls, one completed fixture effect, typed authentication failure followed by a valid false result, and full owned cleanup. That consumer explicitly admitted all transport failures. This proves compatibility with that policy, not that authentication failures should be eligible under every application's policy. See the adoption evidence (historical local experiment; private archive not bundled).

For an upgrade, align the real published pin, source overlays and their preparation manifests, then repeat the same-task observation through the consumer's public path. Preserve the original task inputs, output contract, effect identity, family budget and absolute deadline. Check typed failure evidence and successful output in the consumer's projections. A successful library example alone does not establish which source the application loads. Local HTTP fixtures prove contracts and routing, not another model's semantic suitability or streaming safety.

## Assign primary and backup models

ORC already supports an authored model chain through `llm`, `fallback`, `sequence`, `code` and `condition`. The original PR #48 revision `393c600e` preserves evidence and deadlines needed by its admission gate. It does **not** add a `:fallback-models` option or automatic failover merely because several providers are registered. The provider's configured default model is used when a node has no explicit model; that default is not an outage alternative.

Assign each candidate's model explicitly. This outline follows the [executable same-task example at the proposed revision](https://github.com/ObneyAI/orc/blob/393c600e94de99862514128a0852008dd49854ea/components/orc-service/test/ai/obney/orc/orc_service/scoped_model_alternative_e2e_test.clj). It assumes the described blackboard, common instruction/reads/writes/options and application admission function are defined; it is not a new convenience API.

```clojure
(let [candidate (fn [name model]
                  (orc/llm name :model model :instruction instruction
                    :reads task-reads :writes task-writes
                    :options generation-options
                    :retry {:max-attempts 1 :backoff-ms []}))]
  (orc/fallback "configured-model-candidates"
    (candidate "primary" primary-model)
    (orc/sequence "gated-alternative"
      (orc/code "read-own-primary-failure"
        :fn "your.app.fallback/read-exact-failure"
        :reads task-reads
        :writes [:alternative-admission :alternative-allowed?])
      (orc/condition "eligible-recorded-failure"
        :check {:key :alternative-allowed? :op :equals :value true})
      (candidate "alternative" backup-model))))
```

`primary-model` and `backup-model` are actual model IDs from application configuration. Both must work through the configured provider and support the same task contract. The gate is application code: it reads the exact prior execution's recorded failure and applies the declared policy. Do not implement it as “any failure means try the next model,” unless that is explicitly the intended policy. Keep preceding effects outside this fallback subtree, and retain the original family budget and absolute deadline. More candidates require exact predecessor/attempt selection for each transition; the verified two-candidate example does not provide a general list compiler.

The example's real local HTTP observations verify assignment, routing, canonical task inputs and valid negative results. They do not prove every alternative model's semantic quality. The subsequent revision, now integrated at the merged pin above, adds configuration to existing model nodes; the historical unmerged `llm-fallback` helper is superseded. See the configured recipe above.
