package com.p2pmatchai.model;

/**
 * Represents a Purchase Order as it would be viewed via SAP T-code ME23N.
 * In this prototype it is held in-memory / seeded with synthetic data —
 * no live SAP connection is made (per proposal Section 8 & 12).
 */
public class PurchaseOrder {
    public String poNumber;
    public String vendor;
    public String material;
    public double quantity;
    public String uom;          // unit of measure, e.g. "PCS", "BOX"
    public double unitPrice;
    public String gstNumber;    // GST number on vendor master, per PO
    public String currency;

    public PurchaseOrder(String poNumber, String vendor, String material, double quantity,
                          String uom, double unitPrice, String gstNumber, String currency) {
        this.poNumber = poNumber;
        this.vendor = vendor;
        this.material = material;
        this.quantity = quantity;
        this.uom = uom;
        this.unitPrice = unitPrice;
        this.gstNumber = gstNumber;
        this.currency = currency;
    }
}
