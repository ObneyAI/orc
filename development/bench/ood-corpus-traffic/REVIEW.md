# Traffic corpus review

The RS-7 realistic-traffic corpus: 40 hand-written briefs (4 in-domain, 2 near-domain confounders, 34 off-domain), six
generated variants each, 240 task slots. Ground truth is always the brief (its group, whether it is in-domain, and
for an in-domain brief the seed it must match). The generator never sees the group, the brief id or any label.

## How each task was made

One model call drafts the task from the brief's subject matter, material, output kind, instance space and
must-not-be line, in one of six styles (ticket, long email, bullet spec with a schema, conversational, numbered list,
prose with an inline data excerpt), with the brief's earlier variants passed as "do not reuse". A second call polishes
the draft toward the style's length. Provenance (prompt hashes, token usage, model) is recorded per slot under
`entries/`.

## Defects found in review, and what was done

1. **First run discarded.** Under function calling the provider returns the one-string answer as a list or a map.
   The extractor accepted only a string, so every body kept its list wrapper, and drafts in other shapes reached the
   polish call empty. The polish then echoed its own instruction or invented an unrelated task. Of the 240 first-run
   tasks, at least 13 were meta-instructions, JSON schemas or off-brief, and the rest could not be trusted, so the
   whole run was regenerated. The first run is kept outside version control for reference.
2. **Echoes from echoed input.** The regenerated corpus still held echoed prompts: when the declared output field was
   missing, a fallback read any string in the outputs map and picked up the echoed input. The fallback was removed;
   a missing field is a failed call, retried, then thrown.
3. **Truncated multi-part answers.** Two tasks were reduced to one line ("Payload (Map)", one release-note line)
   because only the first of several returned strings was kept. All parts are now joined.
4. **The neighbour flag carried no information.** It compared a ceiling-normalized ColBERT score with 0.75 and fired
   on 240 of 240. It is now rank-based: a task is flagged when its single nearest neighbour across the whole corpus
   belongs to another group.

## Brief-faithfulness judge

Every task is judged by one structured model call against its brief (reasoning first, then on-brief or off-brief).
The verdict and reasoning are stored per entry. Spot checks agreed with the judge on every off-brief call sampled
(echoed prompts, a single-contract review under the comparison brief, a data analysis under the experiment-plan
brief, a logistics reformat under meeting actions). The judge passed one broken task (a one-line release note),
which was rejected by hand.

| Round | Off-brief | Action |
|---|---:|---|
| Full regeneration | 15 (+1 rejected by hand) | removed and regenerated |
| Remediation 1 | 3 | removed and regenerated |
| Remediation 2 | 2 | rejected (meeting-actions v06, planting-schedule v06, both inline-data variants that drifted off-brief) |

## Review policy

- Ground truth never comes from a flag or the judge; they decide only what a human rejects.
- Neighbour flags in the legal cluster are expected: in-domain tasks sit next to each other and next to their
  confounders by design. They are kept.
- A task still off-brief after two remediation rounds is marked rejected and excluded; the manifest's
  `:corpus-sha256` covers accepted entries only.

## Final corpus

| | Count |
|---|---:|
| Accepted tasks | 238 |
| In-domain | 24 |
| Off-domain (incl. confounders) | 214 |
| Rejected | 2 |
| Neighbour flags (rank-based) | 21 |

Frozen `corpus-sha256`: `7f90ac6a4e4f818d3e141e3f9c39dee6df42fc02cbca039d203f20a452d4f18b`. Every sweep asserts this value.
