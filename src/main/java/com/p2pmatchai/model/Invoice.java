package com.p2pmatchai.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a vendor invoice moving through the P2P Match AI pipeline.
 * Holds both the extracted invoice data AND the outcome of the AI
 * three-way match, so the whole lifecycle of one invoice lives in one object
 * (simple, transparent, and easy to inspect for a prototype/demo).
 */
public class Invoice {

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_CLEAN     = "CLEAN_MATCH";
    public static final String STATUS_EXCEPTION = "EXCEPTION";
    public static final String STATUS_RESOLVED  = "RESOLVED";

    public String id;
    public String vendor;
    public String poReference;
    public double quantity;
    public String uom;
    public double unitPrice;
    public String gstNumber;
    public String currency;
    public String scanQuality;   // "GOOD" or "POOR" -- simulates OCR confidence
    public String submittedAt;

    // ---- AI matching outcome ----
    public String status = STATUS_PENDING;      // PENDING / CLEAN_MATCH / EXCEPTION / RESOLVED
    public List<String> issues = new ArrayList<>();
    public String recommendation = "";
    public Double quantityVariancePercent;
    public Double priceVariancePercent;
    public Boolean gstMatch;
    public boolean poFound;
    public boolean grnFound;

    // ---- Human-in-the-loop decision ----
    public String humanDecision;   // CONFIRMED / APPROVE_OVERRIDE / HOLD / ESCALATE
    public String decisionReason;
    public String decidedAt;

    public Invoice() {}

    public Invoice(String id, String vendor, String poReference, double quantity, String uom,
                    double unitPrice, String gstNumber, String currency, String scanQuality,
                    String submittedAt) {
        this.id = id;
        this.vendor = vendor;
        this.poReference = poReference;
        this.quantity = quantity;
        this.uom = uom;
        this.unitPrice = unitPrice;
        this.gstNumber = gstNumber;
        this.currency = currency;
        this.scanQuality = scanQuality;
        this.submittedAt = submittedAt;
    }
}
