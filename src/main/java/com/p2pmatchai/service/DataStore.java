package com.p2pmatchai.service;

import com.p2pmatchai.model.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All data lives in memory for this prototype (per proposal Section 9:
 * "No actual DCM Shriram Rayons data ... will be uploaded"; everything
 * here is synthetic/fictional). Restarting the server resets the demo
 * to a clean, known state.
 */
public class DataStore {

    public final Map<String, PurchaseOrder> purchaseOrders = new ConcurrentHashMap<>();
    public final Map<String, GRN> grns = new ConcurrentHashMap<>();          // keyed by poNumber
    public final Map<String, Invoice> invoices = new ConcurrentHashMap<>();
    public final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger invoiceSeq = new AtomicInteger(1000);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String now() {
        return LocalDateTime.now().format(TS);
    }

    public String nextInvoiceId() {
        return "INV-" + invoiceSeq.incrementAndGet();
    }

    public void seed() {
        // ---- Synthetic Purchase Orders (as if viewed via ME23N) ----
        addPO("4500123456", "ABC Textiles Pvt Ltd", "Viscose Fibre Grade A", 1000, "KG", 145.00, "09ABCPT1234A1Z5", "INR");
        addPO("4500123457", "Kota Chem Supplies",   "Sulphuric Acid Tech Grade", 500, "KG", 42.50, "08KOTCS5678B2Y4", "INR");
        addPO("4500123458", "Rajputana Packaging",  "Corrugated Cartons 5-ply", 2000, "PCS", 18.75, "08RAJPK9012C3X3", "INR");
        addPO("4500123459", "Shree Engineering Works", "Spindle Bearings SKF-206", 300, "PCS", 610.00, "08SHREN3456D4W2", "INR");
        addPO("4500123460", "Vasundhara Dyes & Chemicals", "Reactive Dye Blue-BR", 150, "KG", 890.00, "08VASDY7890E5V1", "INR");
        addPO("4500123461", "ABC Textiles Pvt Ltd", "Viscose Fibre Grade A", 800, "KG", 145.00, "09ABCPT1234A1Z5", "INR");

        // ---- Synthetic GRNs (as if posted via MIGO) ----
        addGRN("GRN-9001", "4500123456", 1000, "2026-08-20", false); // exact match
        addGRN("GRN-9002", "4500123457", 500, "2026-08-21", false);  // exact match
        addGRN("GRN-9003", "4500123458", 2000, "2026-08-21", false); // exact match
        addGRN("GRN-9004", "4500123459", 220, "2026-08-22", true);   // partial delivery
        addGRN("GRN-9005", "4500123460", 150, "2026-08-22", false);  // exact match
        // Note: PO 4500123461 deliberately has NO GRN posted yet (tests "no GRN" case)

        // ---- Synthetic vendor invoices (mirrors Annexure E test cases) ----
        addInvoice("ABC Textiles Pvt Ltd", "4500123456", 1000, "KG", 145.00, "09ABCPT1234A1Z5", "INR", "GOOD"); // #1 clean match
        addInvoice("Kota Chem Supplies", "4500123457", 500, "KG", 43.80, "08KOTCS5678B2Y4", "INR", "GOOD");     // small price variance within tolerance
        addInvoice("Rajputana Packaging", "4500123458", 2000, "BOX", 18.75, "08RAJPK9012C3X3", "INR", "GOOD"); // UOM mismatch (PCS vs BOX)
        addInvoice("Shree Engineering Works", "4500123459", 300, "PCS", 610.00, "08SHREN3456D4W2", "INR", "GOOD"); // partial GRN
        addInvoice("Vasundhara Dyes & Chemicals", "4500123460", 150, "KG", 890.00, "08VASDY0000X9Z1", "INR", "GOOD"); // GST mismatch
        addInvoice("ABC Textiles Pvt Ltd", "4500123461", 800, "KG", 145.00, "09ABCPT1234A1Z5", "INR", "POOR");  // no GRN + poor scan
    }

    private void addPO(String poNumber, String vendor, String material, double qty, String uom,
                        double price, String gst, String currency) {
        purchaseOrders.put(poNumber, new PurchaseOrder(poNumber, vendor, material, qty, uom, price, gst, currency));
    }

    private void addGRN(String grnNumber, String poNumber, double qtyReceived, String date, boolean partial) {
        grns.put(poNumber, new GRN(grnNumber, poNumber, qtyReceived, date, partial));
    }

    private void addInvoice(String vendor, String poRef, double qty, String uom, double price,
                             String gst, String currency, String scanQuality) {
        String id = nextInvoiceId();
        Invoice inv = new Invoice(id, vendor, poRef, qty, uom, price, gst, currency, scanQuality, now());
        invoices.put(id, inv);
    }
}
