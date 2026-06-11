# Task 05 — Legal Issue Detection (small doc)

**Run file:** `generalization-results/legal-issue-detection_2026-05-19_132452.edn`
**Source:** `development/bench/legal_issue_detection.clj` (ns `legal-issue-detection`, var `task`)

## What the task is

Review a small employment agreement (~7K chars) from the EMPLOYEE'S perspective and identify potential issues, ambiguities, missing protections, and negotiation recommendations.

- **Document:** Employment agreement (`employment_agreement.txt`, 7,331 chars)
- **Outputs:** `:issues`, `:ambiguities`, `:missing`, `:recommendations`

## What the model did

**The model designed NO tree.** It bypassed `emit-tree!` entirely and answered directly in a single LLM call.

This is the model's adaptive decision-making at work — the 7K document fits comfortably in one prompt, and a decomposition tree would only add overhead.

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **8.8 s** |
| Total tokens | **5,706** |
| Generated tree | None (direct answer) |

## Output Quality (verified)

| Model Claim | Source Verification |
|---|---|
| Schedule E, Section 2 non-compete with 12-month + geographic restriction | ✓ Real clause |
| Schedule E, Section 3 non-solicitation 18 months | ✓ Real clause |
| Sensitive data fields (SIN, passport #, health card #) | ✓ Present in document |
| 20% performance bonus mentioned | ✓ EXACT: "Performance Bonus: Up to 20% of base salary" |
| Senior Financial Analyst, Wealth Management Division | ✓ EXACT role match |
| BC non-compete enforceability context | ✓ Plausible jurisdiction-aware observation |

**Zero hallucinations.** Every cited clause reference matches the source.

## Why this run matters

This is the cleanest demonstration that ORC RLM is genuinely adaptive: when decomposition isn't beneficial, the model SKIPS it. The engine doesn't force `emit-tree!` — it's a tool the model chooses when appropriate.

Compare to the 280K tasks where the same model designed elaborate chunked-parallel trees. Same engine, same model, completely different decomposition strategy — driven only by input size.

## Full Model Output

### `:issues`

> 1. **Restrictive Non-Compete (Schedule E, Sec 2):** A 12-month geographic restriction covering both Metro Vancouver and Victoria is broad for a Senior Financial Analyst role. In BC, non-competes are increasingly difficult to enforce unless they protect a very specific 'proprietary interest' and are reasonable in scope.
> 2. **Long Non-Solicitation Period (Schedule E, Sec 3):** 18 months is significantly longer than the industry standard (usually 6–12 months) and may hinder the employee's ability to maintain professional networks.
> 3. **Lack of Intellectual Property (IP) Clarity:** While Schedule E covers non-disclosure, it does not explicitly state that the Employee retains rights to 'work product' created prior to employment or general industry expertise.
> 4. **Privacy/Data Sensitivity:** The document contains highly sensitive personal data (SIN, Passport #, Health Card numbers, Children's SINs) on multiple pages. There is no clause regarding how the Employer will protect or eventually destroy this sensitive data.

### `:ambiguities`

> 1. **"At Employer discretion" (Schedule A, Sec 3):** The bonus criteria are not defined, making the potential $28,500 CAD (20%) entirely subjective and unpredictable.
> 2. **"Competing financial services firm" (Schedule E, Sec 2):** This term is overly broad in the non-compete clause; it doesn't specify if it applies to the specific niche of Wealth Management or any bank/insurance/brokerage firm regardless of the role.
> 3. **"Proprietary models" (Schedule E, Sec 1):** It is unclear if this includes general financial models developed by the employee using standard industry knowledge or only those developed using Employer-specific data.

### `:missing`

> 1. **Termination/Severance Clause:** The agreement is silent on notice periods or severance pay. Without this, the employee is only protected by the BC Employment Standards Act minimums, rather than common law reasonable notice.
> 2. **Change of Control Protection:** There is no 'double-trigger' or protection if Pacific Northwest Financial Services is acquired, which is a risk in the financial sector.
> 3. **'For Cause' Definition:** The agreement lacks a clear definition of what constitutes 'Cause' for termination without notice.
> 4. **Professional Fees/Dues:** No mention of reimbursement for CFA/professional designations or continuing education required for a Senior Analyst.
> 5. **Vacation/Sick Leave:** Schedule A lists compensation but fails to mention the annual vacation entitlement or paid sick days.

### `:recommendations`

> 1. **Negotiate the Non-Compete:** Request the removal of the Non-Compete (Schedule E, Sec 2) in favor of a robust Non-Solicitation clause, or at least narrow the geographic scope to 'Wealth Management' services specifically.
> 2. **Define Bonus Metrics:** Ask for a 'Side Letter' or inclusion in Schedule A that outlines the KPIs (Key Performance Indicators) required to achieve the 20% bonus.
> 3. **Insert a Severance Provision:** Propose a termination clause that provides for at least 1 month of pay per year of service, or a fixed minimum (e.g., 3 months), given the seniority of the role.
> 4. **Request Professional Reimbursement:** Ensure the Employer agrees to pay for annual professional licensing fees and relevant memberships.
> 5. **Clarify Vacation:** Ensure the 'Annual Salary' (Schedule A) is supplemented by a specific number of weeks of paid vacation (typically 3-4 weeks for this salary bracket in BC).
