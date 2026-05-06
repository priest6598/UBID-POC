# UBID Platform — PoC Demo

**Unified Business ID (UBID) Assignment with Human-in-the-Loop Review**
AI for Bharat Hackathon · Theme 1 · Karnataka Commerce & Industry

---

## What This PoC Demonstrates

Karnataka's industrial data is siloed across 40+ department systems — the same business appears as a completely unrelated record in Shop Establishment, Factories, KSPCB, and BWSSB because each department uses its own schema and identifier.

This PoC shows a **continuously running pipeline** that:
1. Watches a landing area for department data files
2. Runs probabilistic entity resolution to detect the same business across departments
3. Assigns a **single UBID** (Unified Business ID) to all records of the same business
4. Routes ambiguous cases to a **human reviewer** — never auto-commits uncertain merges
5. **Learns from reviewer feedback** — improves matching weights over time

---

## Architecture

```
landing/                     data/
  dept_shop.csv    ──┐         registry.jsonl      (resolved UBIDs)
  dept_factory.csv   │         review_queue.jsonl  (pending human review)
  dept_kspcb.csv     ├──► Pipeline ──► model.properties   (learnable weights)
  dept_bwssb.csv     │         archive/            (processed batches)
  confirmation.txt ──┘

Pipeline states: IDLE → DETECTING → INGESTING → MATCHING → WRITING → COMPLETE → IDLE

                            ┌─────────────────┐
                            │  Pipeline UI    │  http://localhost:8080
                            │  (live status)  │
                            └─────────────────┘
                            ┌─────────────────┐
                            │  Reviewer UI    │  http://localhost:8080/reviewer
                            │  (human review) │
                            └─────────────────┘
```

### Matching Model

Scoring uses a **weighted feature vector** — inspired by the Fellegi-Sunter probabilistic record linkage model:

| Feature | Weight | Algorithm |
|---|---|---|
| PAN / GSTIN (identifier) | 0.35 | Exact match — strong anchor |
| Business name | 0.30 | Jaro-Winkler on normalised tokens |
| Address | 0.15 | Token Jaccard similarity |
| Pin code | 0.10 | Exact match |
| Sector | 0.05 | Exact match |
| Phone | 0.05 | Levenshtein ≤ 1 |

**Decision zones:**
- Score ≥ 0.85 → **Auto-link** (shared UBID written to registry)
- Score 0.50–0.84 → **Human review queue** (no merge until reviewer decides)
- Score < 0.50 → **Separate entities** (each gets its own UBID)

Weights are updated after every reviewer decision, so the model improves with use.

---

## Prerequisites

- Java 17+

That's it. No external dependencies — pure Java stdlib.

---

## How to Run

### 1. Compile (one-time)

```bash
cd v2/
./compile.sh
```

### 2. Start the server

```bash
./run.sh
```

Open in browser:
- **Pipeline Dashboard** → http://localhost:8080
- **Reviewer UI** → http://localhost:8080/reviewer

### 3. Trigger the pipeline

In a second terminal, drop the sample data files into the landing area:

```bash
cp sample-data/* landing/
```

Watch the dashboard — the pipeline detects the files and progresses through each stage automatically. The page auto-refreshes every 2 seconds.

### 4. Review ambiguous pairs

After the pipeline completes, switch to the Reviewer UI. Ambiguous business pairs appear as cards in the left sidebar. Click a pair to see:
- Side-by-side field comparison
- Per-feature score bars (green = strong agreement, red = disagreement)
- Evidence text explaining the score

Click **"Same Business"** or **"Different Business"**, optionally add a note, and submit. The decision writes to the registry and updates the model weights.

### 5. Run another batch

To demo a second batch (with the improved model), clean the landing area and repeat:

```bash
rm landing/*
cp sample-data/* landing/
```

---

## Sample Data

Four synthetic department files model real Karnataka business data across:

| File | Department | Records |
|---|---|---|
| `dept_shop.csv` | Shop Establishment | 6 |
| `dept_factory.csv` | Factories Dept | 5 |
| `dept_kspcb.csv` | Pollution Control Board | 5 |
| `dept_bwssb.csv` | Water Supply Board | 5 |

The data includes deliberate variations to showcase matching:

| Business | Departments | Match type |
|---|---|---|
| Infosys Limited | All 4 | Auto-link (same PAN + GSTIN) |
| Ramesh Textiles | Shop + Factory + BWSSB | Auto-link (same PAN, name variation) |
| Kumar Electronics | Shop + KSPCB + BWSSB | Goes to review ("Kumar Elec" abbreviation) |
| Bangalore Steel Works | Factory + KSPCB + BWSSB | Goes to review (name variant, no PAN) |
| City Bakery | Shop + BWSSB | Goes to review (name variation, no PAN) |
| ABC Chemicals | Factory + KSPCB | Auto-link (same GSTIN) |

---

## Project Structure

```
v2/
├── src/com/ubid/
│   ├── Main.java                        entry point
│   ├── model/
│   │   ├── BusinessRecord.java          data record (one row from a dept CSV)
│   │   ├── ReviewItem.java              ambiguous pair awaiting human decision
│   │   └── ModelParameters.java        learnable weights with feedback update
│   ├── matching/
│   │   ├── SimilarityUtils.java        Jaro-Winkler, Jaccard, Levenshtein
│   │   └── EntityMatcher.java          weighted scoring + PAN override logic
│   ├── pipeline/
│   │   └── PipelineOrchestrator.java   state machine + Union-Find clustering
│   ├── storage/
│   │   └── DataStore.java              JSONL file persistence, atomic writes
│   └── server/
│       └── ApiServer.java              HTTP server (stdlib only), REST API
├── web/
│   ├── index.html                       pipeline status dashboard
│   └── reviewer.html                    human review interface
├── sample-data/                         drop these into landing/ to trigger pipeline
│   ├── dept_shop.csv
│   ├── dept_factory.csv
│   ├── dept_kspcb.csv
│   ├── dept_bwssb.csv
│   └── confirmation.txt
├── compile.sh
└── run.sh
```

---

## API Reference

| Endpoint | Method | Description |
|---|---|---|
| `/api/status` | GET | Current pipeline state and stats |
| `/api/queue` | GET | All review queue items |
| `/api/registry` | GET | Last 50 resolved UBID entries |
| `/api/model` | GET | Current model weights and feedback count |
| `/api/review` | POST | Submit a reviewer decision |

---

## Clean Reset 

```bash
rm -f data/registry.jsonl data/review_queue.jsonl data/model.properties
rm -rf data/archive/
rm -f landing/*
```
