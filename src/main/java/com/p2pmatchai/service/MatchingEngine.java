package com.p2pmatchai.service;

import com.p2pmatchai.model.*;

/**
 * The core of "P2P Match AI": performs the automated 3-way match
 * (PO vs GRN vs Invoice), classifies the invoice as Clean Match or
 * Exception, and produces a plain-language explanation + recommended
 * action -- exactly as described in Section 6/7 and Annexure C of the
 * proposal.
 *
 * Design note: this is a deterministic "rules + reasoning" engine
 * (disclosed tolerance thresholds, per Section 12 "Bias and consistency
 * in matching"). The MatchingEngine.explain(...) method is the single
 * seam where a real LLM call (e.g. Claude via API) would be plugged in
 * to turn the structured issue list into a more natural narrative --
 * see ExplanationProvider below. By default a rule-based template is
 * used so the prototype runs with zero external dependencies / API keys.
 */
public class MatchingEngine {

    // Disclosed tolerance thresholds (Section 12: "tolerance thresholds ... are
    // rule-based and disclosed, reducing opaque or arbitrary judgement")
    public static final double QUANTITY_TOLERANCE_PERCENT = 2.0;
    public static final double PRICE_TOLERANCE_PERCENT = 3.0;

    private final DataStore store;
    private final ExplanationProvider explanationProvider;

    public MatchingEngine(DataStore store) {
        this(store, new RuleBasedExplanationProvider());
    }

    public MatchingEngine(DataStore store, ExplanationProvider explanationProvider) {
        this.store = store;
        this.explanationProvider = explanationProvider;
    }

    /** Runs the full 3-way match for one invoice and updates it in place. */
    public Invoice runMatch(Invoice inv) {
        inv.issues.clear();
        inv.poFound = false;
        inv.grnFound = false;
        inv.quantityVariancePercent = null;
        inv.priceVariancePercent = null;
        inv.gstMatch = null;

        // Step 1: low-confidence extraction -> always requires manual verification
        if ("POOR".equalsIgnoreCase(inv.scanQuality)) {
            inv.issues.add("Low-confidence extraction: scan/photo quality is poor. " +
                    "Fields shown may be inaccurate and require manual verification.");
        }

        // Step 2: duplicate check (same vendor + PO reference + amount already processed)
        if (isDuplicate(inv)) {
            inv.issues.add("Possible duplicate submission: another invoice with the same vendor, " +
                    "PO reference, and amount has already been received.");
        }

        // Step 3: PO retrieval
        PurchaseOrder po = store.purchaseOrders.get(inv.poReference);
        if (po == null) {
            inv.poFound = false;
            inv.issues.add("Missing or invalid PO reference: no PO '" + inv.poReference + "' found in records.");
            inv.status = Invoice.STATUS_EXCEPTION;
            inv.recommendation = explanationProvider.recommend(inv);
            logAudit(inv);
            return inv;
        }
        inv.poFound = true;

        // Step 4: GRN retrieval
        GRN grn = store.grns.get(inv.poReference);
        if (grn == null) {
            inv.grnFound = false;
            inv.issues.add("No GRN posted against this PO yet -- goods receipt not confirmed in SAP (MIGO).");
            inv.status = Invoice.STATUS_EXCEPTION;
            inv.recommendation = explanationProvider.recommend(inv);
            logAudit(inv);
            return inv;
        }
        inv.grnFound = true;

        if (grn.partial) {
            inv.issues.add("GRN is only partially posted (" + grn.quantityReceived + " of " + po.quantity +
                    " " + po.uom + " received). Full receipt is pending before this invoice can clear.");
        }

        // Step 5: UOM check -- must match before quantity comparison is meaningful
        boolean uomMismatch = inv.uom != null && po.uom != null && !inv.uom.equalsIgnoreCase(po.uom);
        if (uomMismatch) {
            inv.issues.add("Unit-of-measure mismatch: invoice is in '" + inv.uom +
                    "' but PO/GRN are in '" + po.uom + "'. Variance cannot be safely computed.");
        } else {
            // Step 6: quantity variance (invoice vs GRN received quantity)
            double qtyVariance = percentVariance(inv.quantity, grn.quantityReceived);
            inv.quantityVariancePercent = round2(qtyVariance);
            if (Math.abs(qtyVariance) > QUANTITY_TOLERANCE_PERCENT) {
                inv.issues.add(String.format(
                        "Quantity variance of %.2f%% between invoice (%.2f %s) and GRN (%.2f %s) exceeds the %.1f%% tolerance.",
                        qtyVariance, inv.quantity, inv.uom, grn.quantityReceived, po.uom, QUANTITY_TOLERANCE_PERCENT));
            }
        }

        // Step 7: price variance (invoice unit price vs PO unit price)
        double priceVariance = percentVariance(inv.unitPrice, po.unitPrice);
        inv.priceVariancePercent = round2(priceVariance);
        if (Math.abs(priceVariance) > PRICE_TOLERANCE_PERCENT) {
            inv.issues.add(String.format(
                    "Price variance of %.2f%% between invoice unit price (%.2f) and PO unit price (%.2f) exceeds the %.1f%% tolerance.",
                    priceVariance, inv.unitPrice, po.unitPrice, PRICE_TOLERANCE_PERCENT));
        }

        // Step 8: GST match
        boolean gstOk = po.gstNumber != null && po.gstNumber.equalsIgnoreCase(inv.gstNumber);
        inv.gstMatch = gstOk;
        if (!gstOk) {
            inv.issues.add("GST number mismatch: invoice shows '" + inv.gstNumber +
                    "' but vendor master / PO records '" + po.gstNumber + "'.");
        }

        // Final classification
        if (inv.issues.isEmpty()) {
            inv.status = Invoice.STATUS_CLEAN;
        } else {
            inv.status = Invoice.STATUS_EXCEPTION;
        }
        inv.recommendation = explanationProvider.recommend(inv);

        logAudit(inv);
        return inv;
    }

    private boolean isDuplicate(Invoice candidate) {
        for (Invoice other : store.invoices.values()) {
            if (other.id.equals(candidate.id)) continue;
            if (other.vendor.equalsIgnoreCase(candidate.vendor)
                    && other.poReference.equalsIgnoreCase(candidate.poReference)
                    && Math.abs(other.unitPrice * other.quantity - candidate.unitPrice * candidate.quantity) < 0.01
                    && !"HOLD".equals(other.humanDecision)) {
                return true;
            }
        }
        return false;
    }

    private double percentVariance(double actual, double baseline) {
        if (baseline == 0) return actual == 0 ? 0 : 100;
        return ((actual - baseline) / baseline) * 100.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void logAudit(Invoice inv) {
        store.auditLog.add(new AuditEntry(
                store.now(), inv.id, "AI_CLASSIFIED", inv.status, inv.recommendation, null, null));
    }

    /** Seam for plugging in a real LLM (Claude API) to generate natural-language explanations. */
    public interface ExplanationProvider {
        String recommend(Invoice inv);
    }

    /** Default: deterministic, template-based recommendation text (no external API needed). */
    public static class RuleBasedExplanationProvider implements ExplanationProvider {
        @Override
        public String recommend(Invoice inv) {
            if (inv.issues.isEmpty()) {
                return "Proceed -- all PO, GRN, and invoice fields match within tolerance. " +
                        "AP-executive confirmation required before parking (MIR7/FBV2).";
            }
            StringBuilder sb = new StringBuilder("Hold for review -- ");
            sb.append(inv.issues.size()).append(inv.issues.size() == 1 ? " issue found: " : " issues found: ");
            for (int i = 0; i < inv.issues.size(); i++) {
                sb.append(inv.issues.get(i));
                if (i < inv.issues.size() - 1) sb.append(" ");
            }
            if (!inv.poFound) {
                sb.append(" Recommended action: return to vendor / procurement for a valid PO reference.");
            } else if (!inv.grnFound) {
                sb.append(" Recommended action: escalate to stores/procurement to confirm goods receipt.");
            } else {
                sb.append(" Recommended action: AP executive/team lead to verify and approve override, hold, or escalate to procurement/stores.");
            }
            return sb.toString();
        }
    }
}
