# P2P Match AI — Working Prototype

An AI-assisted invoice verification & exception-triage prototype for a SAP-based
Procure-to-Pay cycle, built to accompany the project proposal *"P2P Match AI: An
AI-Powered Invoice Verification & Exception-Triage Agent for SAP-Based
Procure-to-Pay at a Manufacturing Unit."*

This is a **complete, runnable full-stack app**: Java backend (REST API) +
HTML/CSS/JS frontend (review dashboard). It uses **only synthetic data** — no
real organizational data — consistent with the proposal's data-restriction
section.

## Why plain Java (no Spring/Maven)?

To guarantee it runs on any machine with just a JDK installed — no internet
access or dependency downloads required, which matters a lot for a quick
classroom/viva demo. The backend uses `com.sun.net.httpserver.HttpServer`
(built into the JDK) and a small hand-written JSON reader/writer
(`util/Json.java`). If you later want Spring Boot for a resume-style version,
the `service/` and `model/` classes can be dropped into a Spring project
almost unchanged — only `server/Server.java` (the routing layer) would need
to be rewritten as `@RestController`s.

## Requirements

- JDK 11 or later (`java -version` to check)
- A web browser
- No external libraries, no Maven, no internet connection needed to run it

## How to run

```bash
# 1. Build
./build.sh

# 2. Run
./run.sh
# (or specify a port: ./run.sh 9090)
```

Then open **http://localhost:8080** in your browser.

If you're on Windows / prefer manual commands:

```bash
javac -d out $(find src -name "*.java")
java -cp out com.p2pmatchai.server.Server 8080
```

## What the prototype does

1. **Seeds synthetic data** on startup: 6 Purchase Orders, 5 GRNs, and 6 vendor
   invoices (mirroring the test cases in Annexure E of the proposal — clean
   match, price variance, UOM mismatch, partial GRN, GST mismatch, missing
   GRN + poor scan quality).
2. **Runs the AI three-way match automatically** the moment an invoice is
   submitted (via the seeded data or the "New Invoice" form): retrieves the
   matching PO and GRN, checks quantity/price variance against disclosed
   tolerances (2% quantity, 3% price), checks GST number, checks UOM, checks
   for duplicates and partial GRNs, and checks scan-quality confidence.
3. **Classifies each invoice** as `CLEAN_MATCH` or `EXCEPTION` and generates
   a plain-language explanation + recommended action.
4. **Never posts anything automatically.** Every invoice — clean or
   exception — needs an explicit human decision (`Confirm`, `Approve
   override`, `Hold`, or `Escalate`) before it's marked `RESOLVED`.
5. **Logs everything** to an in-memory audit trail: every AI classification
   and every human decision, with timestamps and reasons — visible under the
   "Audit Trail" tab.

## Project structure

```
p2pmatchai/
├── src/main/java/com/p2pmatchai/
│   ├── model/          PurchaseOrder, GRN, Invoice, AuditEntry
│   ├── service/         DataStore (seed data), MatchingEngine (the AI logic)
│   ├── util/            Json.java (dependency-free JSON read/write)
│   └── server/          Server.java (HTTP routing + REST API + static files)
├── webapp/               index.html, style.css, app.js (frontend dashboard)
├── build.sh / run.sh
└── README.md (this file)
```

## REST API reference

| Method | Endpoint                          | Description                                  |
|--------|------------------------------------|-----------------------------------------------|
| GET    | `/api/invoices`                   | List all invoices with current match status   |
| POST   | `/api/invoices`                   | Submit a new invoice (runs the AI match)       |
| GET    | `/api/invoices/{id}`              | Get one invoice's full detail                 |
| POST   | `/api/invoices/{id}/decision`     | Record a human decision (see body below)       |
| POST   | `/api/invoices/{id}/rematch`      | Re-run the AI match (e.g. after correction)    |
| GET    | `/api/pos`                        | List seeded Purchase Orders                    |
| GET    | `/api/grns`                       | List seeded GRNs                               |
| GET    | `/api/audit`                      | Full audit trail, most recent first            |

`POST /api/invoices` body example:
```json
{
  "vendor": "ABC Textiles Pvt Ltd",
  "poReference": "4500123456",
  "quantity": 1000,
  "uom": "KG",
  "unitPrice": 145.00,
  "gstNumber": "09ABCPT1234A1Z5",
  "currency": "INR",
  "scanQuality": "GOOD"
}
```

`POST /api/invoices/{id}/decision` body:
```json
{ "decision": "APPROVE_OVERRIDE", "reason": "Confirmed with vendor, rounding difference" }
```
`decision` must be one of `CONFIRMED`, `APPROVE_OVERRIDE`, `HOLD`, `ESCALATE`.

## Where a real LLM would plug in

`MatchingEngine` is deliberately split so the matching **rules** (deterministic,
disclosed tolerances) are separate from the **explanation text**. The
`MatchingEngine.ExplanationProvider` interface is the seam: swap
`RuleBasedExplanationProvider` for an implementation that calls the Claude API
(or any LLM) to turn the structured issue list into a more natural narrative,
without touching the matching logic itself. For a classroom prototype, the
rule-based version keeps things running with zero API keys / external calls.

Similarly, real invoice **extraction from an image/PDF** (OCR + LLM, per the
proposal's Technology Plan) is simulated here via the "scanQuality" field and
a manual-entry form — you can wire in an actual PDF/image upload + Claude
Vision API call at the point where `handleCreateInvoice()` builds the
`Invoice` object in `Server.java`, without changing anything downstream.

## Demo script for viva / presentation

1. Open the dashboard — queue shows 6 seeded invoices, several already
   flagged as exceptions.
2. Click an `EXCEPTION` invoice (e.g. the GST-mismatch or price-variance one)
   — show the side-by-side PO / GRN / Invoice comparison and the AI's
   plain-language explanation.
3. Click **Hold** or **Escalate**, add a reason, and show it move to the
   audit trail.
4. Go to **New Invoice**, submit a fresh invoice against an existing PO with
   a deliberately wrong GST number or quantity — show it get classified live.
5. Open **Audit Trail** — show every AI recommendation and human decision
   logged with timestamps.

This matches the demonstration script in Annexure F of the proposal.
