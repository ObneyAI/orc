# RS-P1b — does a judged choice converge labels, and does the verdict survive siblings? Findings

Two runs of `development/src/rs_p1_coverage_probe.clj` (extended): pass one mints labels per top candidate under
the tightened `covered` definition (subject matter, material, output kind; "a kind of processing is not a domain");
pass two hands each candidate the labels minted under it in pass one as `existing_domain_children` and requires
reuse when one fits. Run 1 (`../rs-p1b-sibling-reuse-probe/`): sibling list only. Run 2 (this directory): the
instruction additionally states that the sibling list must not influence the coverage verdict.

## Held

- **Label reuse converges.** Run 1: 13 of 17 eligible tasks reused a sibling label verbatim, and the 4 refusals were
  correct (no sibling named the task's domain — an ETL design offered marathon/recipe siblings, a metered recipe offered
  a pi-melody sibling). Run 2: 16 of 17. Label agreement between passes rose from RS-P1's 5/21 to 15/21 and 19/21.
- **The tightened `covered` definition calibrates.** Run 2 pass one: partial 13 / uncovered 5 / covered 3 — and the
  three covered are the two sanity checks plus the change-data-capture ETL design, which genuinely is in the ETL
  pipeline's declared domain. RS-P1's 12 covered / 2 uncovered is gone.

## Did not hold

- **Once a candidate carries children, the model calls it `covered`** — 20 of 21 in both runs, and the explicit
  "siblings must not influence coverage" sentence changed nothing. It reads "a child under me names your domain" as
  "I cover your domain", and no wording moves it. (The lesson RS-P1 already taught: a live model is not calibrated by
  prose.)

## Verdict and the design consequence (D7b, posed)

A judged label choice is a sound canonicaliser: reuse when a sibling fits, coin otherwise, 16/17. The coverage verdict
is sound only for a class that has no domain children yet. Therefore the verdict must not be consulted once a class
has children; the label decides: a sibling label lands the task on that child, a new label mints a new child. Coverage
(partial/uncovered → mint, covered → leaf) applies only to the first child of a class. Over-minting under a class that
already specialises is cheap by D7 and is the waterfall forming, not noise (a "data-warehouse-etl-design" child under
ETL pipeline is a correct specialisation). This makes convergence fully independent of the verdict repeating, which is
what `DomainChildrenAreAlwaysConsidered` already states.
