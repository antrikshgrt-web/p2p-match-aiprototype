package com.p2pmatchai.model;

/**
 * Represents a Goods Receipt Note as posted via SAP T-code MIGO.
 */
public class GRN {
    public String grnNumber;
    public String poNumber;
    public double quantityReceived;
    public String dateReceived;
    public boolean partial; // true if this is a partial delivery against the PO

    public GRN(String grnNumber, String poNumber, double quantityReceived,
               String dateReceived, boolean partial) {
        this.grnNumber = grnNumber;
        this.poNumber = poNumber;
        this.quantityReceived = quantityReceived;
        this.dateReceived = dateReceived;
        this.partial = partial;
    }
}
