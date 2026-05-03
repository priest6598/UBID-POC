# UBID & Active Business Intelligence Platform — POC

A working prototype of the **Unique Business Identifier (UBID)** and **Active Business Intelligence** platform for the *AI for Bharat Hackathon, Theme 1 — Karnataka Commerce & Industry*.

The platform reconciles fragmented business records across 40+ Karnataka department systems (Shop Establishment, Factories, KSPCB, BWSSB, …) into a single canonical identifier per real-world business, then classifies each business as Active / Dormant / Closed using ongoing transaction events.

This POC implements the full pipeline end-to-end against synthetic data — including a working **active-learning loop** that takes reviewer decisions and updates the matcher's parameters:

> **45 source records → 1 ambiguous pair flagged for review → reviewer marks it non-match → trainer updates 20 tier weights via Bayesian update → re-scoring drops the pair from REVIEW (p=0.75) to NO_LINK (p=0.64).**

---

## Why this matters

Karnataka's industrial data landscape is a classic siloed-systems problem with no join key. The same business appears as completely unrelated records across departments:

| Source system | Record |
|---|---|
| Shop Establishment | `M/s Ramesh Textiles` |
| Factories | `Ramesh Textiles Pvt. Ltd.` |
| KSPCB | `Ramesh Tex` |
| BWSSB | `RAMESH TEXTILES` |

This POC takes those four rows and produces **one** UBID, anchored to the PAN (when available), with a fully explainable evidence trail behind every link decision.

---

## Quick start

Requires only **JDK 17+** (no Maven, no external dependencies).

```bash
cd ~/ubid-poc
./run.sh
open output/ubid-report.html
```

The script compiles all sources to `build/`, runs `com.karnataka.ubid.Main`, and writes outputs to `output/`.

---

## Pipeline

```
┌─────────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│ 1. Ingestion        │ ─▶ │ 2. PII Scramble  │ ─▶ │ 3. Blocking      │
│ 4 mock dept systems │    │ HMAC-SHA256 FPE  │    │ Union of blocks  │
└─────────────────────┘    └──────────────────┘    └──────────────────┘
                                                            │
                                                            ▼
┌─────────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│ 8. HTML/JSON report │ ◀─ │ 7. Activity      │ ◀─ │ 4. Probabilistic │
│ + review queue      │    │ Classification   │    │    Matching      │
│ + active-learning   │    └──────────────────┘    │    (Round 1)     │
│   diff              │                            └──────────────────┘
└─────────────────────┘                                     │
        ▲                                                   ▼
        │                                          ┌──────────────────┐
        │                                          │ 5. UBID Registry │
        │                                          │    (Round 1)     │
        │                                          └──────────────────┘
        │                                                   │
        │                                                   ▼
        │                                          ┌──────────────────┐
        │                                          │ 6. ACTIVE        │
        └─ Round 2 re-score with updated params ◀── │    LEARNING:     │
           (review queue shrinks)                   │ Reviewer →       │
                                                    │ MU update        │
                                                    │ (Bayesian Beta)  │
                                                    └──────────────────┘
```

### 1. Ingestion ([data/MockDataGenerator.java](src/main/java/com/karnataka/ubid/data/MockDataGenerator.java))

Generates 45 records across `SHOP_EST`, `FACTORIES`, `KSPCB`, `BWSSB` from a fixed seed (reproducible). Each canonical business is projected into 1–4 systems with system-specific noise:
- `SHOP_EST`: 40% chance of `M/s ` prefix, sometimes missing GSTIN
- `FACTORIES`: appends `Pvt Ltd` / `Pvt. Ltd.`
- `KSPCB`: occasional abbreviations (`Textiles → Tex`, `Engineering → Engg`)
- `BWSSB`: 60% chance of dropping PAN/GSTIN entirely; sometimes uppercase

Three deliberately calibrated **ambiguous pairs** are added to exercise the human-review path.

### 2. PII Scrambling ([scrambling/PIIScrambler.java](src/main/java/com/karnataka/ubid/scrambling/PIIScrambler.java))

Deterministic, format-preserving transformations applied **before** any matching reads PII:
- **Names / addresses**: per-token consistent substitution via HMAC-SHA256 (same `Ramesh` → same scrambled token within a run, preserving similarity structure)
- **PAN**: format-preserving substitution maintaining `AAAAA9999A` shape
- **GSTIN**: format-preserving, maintains `99AAAAA9999A9Z9` shape

Two records with the same real PAN always produce the same scrambled PAN — exact-match anchoring still works post-scramble. Run secret never leaves the scrambler.

### 3. Blocking ([matching/BlockingEngine.java](src/main/java/com/karnataka/ubid/matching/BlockingEngine.java))

Union-of-blocks — a candidate pair is generated if it agrees on **any** of:
- 6-digit pin code
- GSTIN prefix (first 15 chars)
- Metaphone of first business-name token (phonetic blocking)
- Any 3-gram of first 30 chars of address

Reduces O(n²) ≈ 990 pairs to ~560 candidates on the demo data (44% reduction; reduction ratio improves dramatically with dataset size).

### 4. Probabilistic Matching ([matching/ProbabilisticMatcher.java](src/main/java/com/karnataka/ubid/matching/ProbabilisticMatcher.java))

Fellegi-Sunter style log-odds scoring. Each feature has hand-tuned `m`/`u` probabilities (in production these are re-estimated weekly via EM on reviewer-labelled pairs):

| Feature | Tier | Δ log-odds (approx) |
|---|---|---|
| PAN exact match | agree | +9.20 |
| GSTIN exact match | agree | +9.20 |
| Name (Jaro-Winkler) | ≥0.95 / 0.85 / 0.70 / <0.70 | +3.75 / +2.56 / +0.69 / −1.88 |
| Metaphone first-token | match | +2.22 |
| Pin code | exact / different | +3.86 / −2.97 |
| Address (3-gram Jaccard) | ≥0.6 / 0.3 / <0.3 | +2.99 / +1.39 / −2.99 |
| Sector | match / mismatch | +1.74 / −0.62 |
| Registration date | within 180d / 2y / >2y | +2.08 / +0.69 / −1.50 |

Posterior probability via the logistic function. Decision thresholds:
- **AUTO_LINK** ≥ 0.95
- **REVIEW** ∈ [0.70, 0.95)
- **NO_LINK** < 0.70

Every pair carries the per-feature evidence trail.

### 5. UBID Assignment ([ubid/UBIDRegistry.java](src/main/java/com/karnataka/ubid/ubid/UBIDRegistry.java) + [ubid/UBIDGenerator.java](src/main/java/com/karnataka/ubid/ubid/UBIDGenerator.java))

Union-find clustering over AUTO_LINK pairs only (REVIEW pairs are queued, **never auto-merged** — wrong merges are worse than missed ones). For each cluster:

- If any member has a (scrambled) PAN → **PAN-anchored UBID** = `UUID-v5(PAN_NAMESPACE, pan)`
- Otherwise → **internal-namespace UBID** = `UUID-v5(INTERNAL_NAMESPACE, sortedRecordIds)`

UUID v5 is real SHA-1-based and RFC 4122 compliant (Java's built-in `UUID.nameUUIDFromBytes` is v3/MD5, so v5 is implemented manually). The PAN-anchored property means: when a currently-internal UBID later gains a PAN, it can be **promoted** to the same PAN-anchored ID without re-issuing identifiers downstream.

### 6. Active Learning Loop ([learning/ActiveLearningTrainer.java](src/main/java/com/karnataka/ubid/learning/ActiveLearningTrainer.java) + [learning/MUParams.java](src/main/java/com/karnataka/ubid/learning/MUParams.java))

This is what closes the feedback loop from reviewers back to the model.

**The flow:**

1. **Capture** — Every reviewer decision is stored as a `ReviewerDecision` record with `(left, right, verdict, reviewerId, timestamp, note)`. These are immutable audit records.
2. **Re-estimate (m, u) per tier** — For every (feature, tier) pair (e.g. `name.high`, `pin.disagree`, `metaphone.match`), count how often that tier was hit among confirmed-match pairs and confirmed-non-match pairs. Update via **conjugate-Beta updating**:

   ```
     m_new = (priorStrength · m_current + match_hits)        / (priorStrength + total_matches)
     u_new = (priorStrength · u_current + nonmatch_hits)     / (priorStrength + total_nonmatches)
   ```

   The current `(m, u)` becomes the prior; reviewer-labelled tier hits are the observations. With small labelled sets the prior dominates (protects against overfit); as the labelled set grows the empirical observations take over.

3. **Re-score** — All previously scored pairs are re-evaluated with the updated `MUParams`. Pairs whose probability now falls outside their original decision band are flipped (REVIEW → AUTO_LINK or REVIEW → NO_LINK). The proposal calls this "review-queue shrinkage" — projected ~40% reduction over 3 production months.

**Demo of the loop in action:**

| Stage | Count |
|---|---|
| Round 1 review queue | 1 pair (Ravi Auto Parts vs Ravi Auto Spares, p=0.7462) |
| Reviewer decisions captured | 32 (15 confirmed match, 17 confirmed non-match) |
| Tier weights updated | 20 of 21 tiers received labelled hits |
| Round 2 review queue | **0 pairs** — Ravi pair flipped to NO_LINK (p=0.6445) |

What the trainer learned from this batch:
- `pin.disagree` log-odds fell from −2.97 to −3.71 (more punitive when pin codes differ)
- `pin.agree` log-odds dropped from +3.86 to +1.13 (less generous when pin matches alone)
- `address.moderate` weakened (matched non-matches frequently shared this tier)
- `name_phonetic.match` strengthened (15 confirmed matches all had it; only 1 non-match did)

The `(reviewer-decisions.json, training-result.json)` outputs preserve the full audit trail, and the HTML report renders the per-tier delta table inline.

In production, this loop runs weekly: the entire reviewer-decision log from the past week is replayed through the trainer, the threshold curve is recomputed, and a new `MUParams` snapshot is committed.

### 7. Activity Events & Classification

[activity/ActivityEventGenerator.java](src/main/java/com/karnataka/ubid/activity/ActivityEventGenerator.java) synthesises 12 months of events per record across `RENEWAL`, `INSPECTION`, `CONSUMPTION`, `FILING`, `CLOSURE`, `ADDRESS_CHANGE` based on a hidden ground-truth label.

[activity/ActivityClassifier.java](src/main/java/com/karnataka/ubid/activity/ActivityClassifier.java) joins events to UBIDs via the registry, then applies deterministic rules:

| Status | Trigger |
|---|---|
| **CLOSED** | Explicit closure/deregistration event |
| **ACTIVE** | Renewal in past 365 days **OR** inspection in past 180 days **OR** utility consumption in past 90 days **OR** ≥ 2 filings in past 365 days |
| **DORMANT** | Has historical activity but no recent renewal (>365d) and nothing in last 180d |
| **UNKNOWN** | Insufficient signal |

Every verdict carries the evidence event timeline.

### 8. Reports ([report/HtmlReportGenerator.java](src/main/java/com/karnataka/ubid/report/HtmlReportGenerator.java) + [report/JsonWriter.java](src/main/java/com/karnataka/ubid/report/JsonWriter.java))

- `output/ubid-report.html` — full visual demo (cluster cards, evidence drawers, review queue, active learning section, status badges)
- `output/ubid-registry.json` — final registry with members per UBID
- `output/activity-classifications.json` — UBID → status + reasoning + evidence
- `output/review-queue.json` — pairs awaiting human review with per-feature evidence
- `output/reviewer-decisions.json` — captured reviewer decisions (immutable audit log)
- `output/training-result.json` — per-tier (m, u) before/after with hit counts

---

## Project layout

```
ubid-poc/
├── run.sh                          # compile + run, no external deps
├── README.md                       # this file
├── .gitignore
├── output/                         # generated reports (gitignored)
└── src/main/java/com/karnataka/ubid/
    ├── Main.java                   # pipeline orchestrator
    ├── model/                      # records: BusinessRecord, ActivityEvent, ScoredPair, ActivityStatus, ReviewerDecision
    ├── data/                       # MockDataGenerator
    ├── scrambling/                 # PIIScrambler
    ├── matching/                   # BlockingEngine, ProbabilisticMatcher, StringSimilarity, Metaphone
    ├── learning/                   # MUParams, ActiveLearningTrainer (Bayesian update)
    ├── ubid/                       # UBIDGenerator (UUID v5), UBIDRegistry (union-find)
    ├── activity/                   # ActivityEventGenerator, ActivityClassifier
    └── report/                     # HtmlReportGenerator, JsonWriter
```

---

## Sample output

```
══ Stage 1 — Ingestion ══
  Loaded 45 records from 4 source systems:
    KSPCB        11 records
    FACTORIES    11 records
    BWSSB        10 records
    SHOP_EST     13 records

══ Stage 2 — PII Scrambling ══
  Sample (record FACTORIES-00001):
    raw name : Ramesh Textiles Pvt Ltd
    scrambled: Divhcy Rfnsnf Rhcahl Vnusnl
    raw PAN  : AAACR1234K  →  scrambled PAN: PWKGH6654A
  All downstream stages operate on scrambled fields only.

══ Stage 3 — Blocking (candidate generation) ══
  Full cartesian space  : 990 pairs
  Candidate pairs after blocking: 560  (reduction: 43.43%)

══ Stage 4 — Probabilistic Matching (Round 1, default MU params) ══
  AUTO_LINK : 30   REVIEW : 1   NO_LINK : 529

══ Stage 5 — UBID Assignment (Round 1) ══
  [Round 1] UBIDs=27  multi-record=9  PAN-anchored=21  review-queue=1

══ Stage 6 — Reviewer Decisions & Retraining ══
  32 reviewer decisions captured (15 match, 17 non-match)
  20 of 21 tiers received labelled hits → (m, u) updated

══ Stage 7 — Re-scoring with Updated Params (Round 2) ══
  AUTO_LINK : 30   REVIEW : 0   NO_LINK : 530
  [Round 2] UBIDs=27  multi-record=9  PAN-anchored=21  review-queue=0

  REVIEW-zone pair trajectory (before → after retraining):
    SHOP_EST-00044 ⟷ FACTORIES-00045   p: 0.7462 → 0.6445  (-0.1017)   [REVIEW → NO_LINK]
```

**Two concrete demonstrations:**

1. **Cross-system clustering**: `Vikram Manufacturing` appears in all 4 systems with the BWSSB record missing PAN. All 4 collapse into one **PAN-anchored** UBID. The BWSSB record inherits the PAN-anchored identifier via the union-find link.

2. **Active learning closing the loop**: The single REVIEW-zone pair (`Ravi Auto Parts` vs `Ravi Auto Spares`) lands at p=0.7462 in round 1 — high name similarity and metaphone match, but conflicting pin and divergent addresses. The reviewer marks it as a non-match. The trainer updates 20 of 21 tier (m, u) values via Bayesian Beta updating, and re-scoring drops the pair to p=0.6445 — moved into NO_LINK.

---

## Mapping to the proposal

| Proposal section | POC implementation |
|---|---|
| §3.1 Ingestion & PII scrambling | `MockDataGenerator`, `PIIScrambler` (HMAC-SHA256, FPE) |
| §3.2 Blocking strategy | `BlockingEngine` — pin / GSTIN-prefix / metaphone / 3-gram |
| §3.3 Feature engineering | `ProbabilisticMatcher.scorePair()` — 8 features w/ tiered comparators |
| §3.4 Splink probabilistic matching | `ProbabilisticMatcher` — Fellegi-Sunter log-odds, EM-ready m/u |
| §3.5 Confidence calibration & thresholds | 0.95 / 0.70 thresholds, evidence trail per pair |
| §3.5 UBID generation | `UBIDGenerator` — UUID v5, PAN-anchored namespace |
| §4.1 Reviewer interface | `ReviewerDecision` audit records, evidence cards in HTML |
| §4.2 Active learning feedback loop | `ActiveLearningTrainer` — Bayesian (m, u) update, two-pass demo |
| §5 Activity status classification | `ActivityClassifier` — rule-based with evidence timeline |
| §6 Non-negotiables (no upstream writes, PII contained) | Read-only adapters, scrambled fields only past Stage 2 |

---

## Constraints honoured

- **No upstream writes.** Records are read once, never written back to source systems.
- **PII never leaves the perimeter.** Stage 2 scrambles before any field hits the matcher.
- **No wrong merges.** REVIEW-zone pairs sit in the queue forever until a human decides — they are never silently auto-merged.
- **Explainable.** Every match carries per-feature contribution to the log-odds. Every status carries the evidence event timeline.
- **Reproducible.** Fixed random seeds (`42L` for data, `43L` for events). Same input → same UBIDs always.

---

## Extending to production

The POC is designed so each stage can be swapped without affecting the others:

- **Real data**: swap `MockDataGenerator` for actual department-system adapters
- **Splink**: replace `ProbabilisticMatcher` (the m/u tables map directly to Splink's output)
- **Iceberg / Trino**: replace `UBIDRegistry`'s in-memory store with Iceberg writes; query via Trino
- **Kafka Streams**: Stage 6 is currently batch — replace with a Kafka Streams processor reading from a topic with the same `ActivityEvent` schema
- **Reviewer UI**: the JSON in `output/review-queue.json` is the API contract a UI would consume

---

## Outputs

After `./run.sh`:

```
output/
├── ubid-report.html              # main visual demo
├── ubid-registry.json            # all UBIDs with member records
├── activity-classifications.json # UBID → status + reasoning + evidence
└── review-queue.json             # pairs awaiting human review with per-feature evidence
```

---

*All names, addresses, PAN, and GSTIN values shown anywhere in this POC are mock/synthetic data.*
