# RR-5 iteration-record size

## Question

How large are representative durable iteration records, and does record size
remain independent of the raw data payload the researcher reads or returns?

## Method

Four production-created records were read back from public checkpointed
researcher executions: a direct success, a tree-emitting success, a sandbox
failure at both configured text caps, and a provider timeout. Each record was
measured in two ways:

1. UTF-8 bytes of the record's EDN representation, isolating the digest.
2. Grain's Fressian serialization of the complete
   `:rlm/researcher-iteration-recorded` event, including identity, tags and
   envelope. This uses the same handlers as the SQLite event store, modulo row
   and index overhead and backend compression.

The fixtures are deliberately representative rather than equal-sized. Code
and emitted-tree structure are authored evidence and remain untruncated, so
their size is allowed to affect the record.

| Outcome fixture | Record EDN bytes | Complete event Fressian bytes |
|---|---:|---:|
| provider timeout | 372 | 778 |
| tree-emitting success | 630 | 1,039 |
| direct success | 714 | 1,132 |
| capped sandbox failure | 1,406 | 1,819 |

The observed record range was **372–1,406 bytes** and the complete persisted
event range was **778–1,819 bytes**. The failure fixture is largest because it
contains 438 characters of reasoning, a 175-character error excerpt, and the
full authored failing code. Those are contractually retained evidence, not raw
result payload.

## Structural bounds

A durable public test executes the same generated code twice with a one-byte
string and a 65,536-byte string. After normalizing the reported
`result-profile.length`, the two records are equal. Their serialized EDN size
differs only by the additional decimal digits needed to represent that length;
the 64 KiB value is absent from the record. This asserts payload-size
independence without inventing a total-byte ceiling.

Reasoning and error size remain enforced separately by the measured public
limits in `RR5-iteration-evidence-bounds.md`, including below-, at-, and
above-limit tests. Generated code and emitted trees are never truncated, so no
fixed total-record cap is claimed.

## Limitations

This is a four-shape deterministic sample, not a production percentile claim.
RR-26 must repeat the measurement across completed real campaigns and report
the distribution of code and tree sizes. Postgres row/index overhead and TOAST
compression are also outside this measurement.
