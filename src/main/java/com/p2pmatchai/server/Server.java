package com.p2pmatchai.server;

import com.p2pmatchai.model.*;
import com.p2pmatchai.service.DataStore;
import com.p2pmatchai.service.MatchingEngine;
import com.p2pmatchai.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * P2P Match AI -- prototype backend.
 * Pure JDK HTTP server (com.sun.net.httpserver) -- no Maven/Spring needed,
 * so this compiles and runs with just `javac` + `java` on any machine with
 * a JDK 11+ installed. Serves both the REST API and the static frontend.
 */
public class Server {

    private final DataStore store = new DataStore();
    private final MatchingEngine engine = new MatchingEngine(store);
    private final Path webRoot;

    public Server(Path webRoot) {
        this.webRoot = webRoot;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path webRoot = Paths.get("webapp");
        Server server = new Server(webRoot);
        server.start(port);
    }

    public void start(int port) throws IOException {
        store.seed();
        // Run initial AI classification on all seeded invoices so the dashboard
        // has data the moment it loads (mirrors the AI running as invoices arrive).
        for (Invoice inv : store.invoices.values()) {
            engine.runMatch(inv);
        }

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/api/invoices", new InvoicesHandler());
        http.createContext("/api/pos", exchange -> respondJson(exchange, 200, posAsList()));
        http.createContext("/api/grns", exchange -> respondJson(exchange, 200, grnsAsList()));
        http.createContext("/api/audit", exchange -> respondJson(exchange, 200, auditAsList()));
        http.createContext("/", new StaticHandler());
        http.setExecutor(null);
        http.start();
        System.out.println("=========================================================");
        System.out.println(" P2P Match AI prototype running at: http://localhost:" + port);
        System.out.println(" REST API base:                     http://localhost:" + port + "/api");
        System.out.println("=========================================================");
    }

    // ---------------------------------------------------------------
    // /api/invoices              GET  -> list all invoices
    // /api/invoices              POST -> submit a new invoice (runs AI match)
    // /api/invoices/{id}         GET  -> single invoice detail
    // /api/invoices/{id}/decision POST -> record human decision
    // /api/invoices/{id}/rematch POST -> re-run the AI match (e.g. after correction)
    // ---------------------------------------------------------------
    class InvoicesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.replaceFirst("^/api/invoices/?", "").split("/");
            // parts[0] = "" (list) or invoiceId; parts[1] = "decision" | "rematch" if present

            try {
                if (parts.length == 1 && parts[0].isEmpty()) {
                    if ("GET".equals(method)) {
                        List<Object> all = new ArrayList<>();
                        for (Invoice i : store.invoices.values()) all.add(toMap(i));
                        respondJson(exchange, 200, all);
                    } else if ("POST".equals(method)) {
                        handleCreateInvoice(exchange);
                    } else {
                        respondJson(exchange, 405, err("Method not allowed"));
                    }
                    return;
                }

                String invoiceId = parts[0];
                Invoice inv = store.invoices.get(invoiceId);
                if (inv == null) {
                    respondJson(exchange, 404, err("Invoice not found: " + invoiceId));
                    return;
                }

                if (parts.length == 1) {
                    respondJson(exchange, 200, toMap(inv));
                } else if (parts.length == 2 && "decision".equals(parts[1]) && "POST".equals(method)) {
                    handleDecision(exchange, inv);
                } else if (parts.length == 2 && "rematch".equals(parts[1]) && "POST".equals(method)) {
                    engine.runMatch(inv);
                    respondJson(exchange, 200, toMap(inv));
                } else {
                    respondJson(exchange, 404, err("Not found"));
                }
            } catch (Exception e) {
                respondJson(exchange, 500, err("Server error: " + e.getMessage()));
            }
        }
    }

    private void handleCreateInvoice(HttpExchange exchange) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String id = store.nextInvoiceId();
        Invoice inv = new Invoice(
                id,
                str(body, "vendor", ""),
                str(body, "poReference", ""),
                num(body, "quantity", 0),
                str(body, "uom", "PCS"),
                num(body, "unitPrice", 0),
                str(body, "gstNumber", ""),
                str(body, "currency", "INR"),
                str(body, "scanQuality", "GOOD"),
                store.now()
        );
        store.invoices.put(id, inv);
        engine.runMatch(inv);
        respondJson(exchange, 201, toMap(inv));
    }

    private void handleDecision(HttpExchange exchange, Invoice inv) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String decision = str(body, "decision", "");
        String reason = str(body, "reason", "");

        Set<String> valid = new HashSet<>(Arrays.asList("CONFIRMED", "APPROVE_OVERRIDE", "HOLD", "ESCALATE"));
        if (!valid.contains(decision)) {
            respondJson(exchange, 400, err("decision must be one of " + valid));
            return;
        }

        inv.humanDecision = decision;
        inv.decisionReason = reason;
        inv.decidedAt = store.now();
        // CONFIRMED (clean match) or APPROVE_OVERRIDE (exception) move the invoice to RESOLVED
        // (i.e. it proceeds to SAP parking/posting -- MIR7/FBV2, outside this prototype's scope).
        if ("CONFIRMED".equals(decision) || "APPROVE_OVERRIDE".equals(decision)) {
            inv.status = Invoice.STATUS_RESOLVED;
        }
        // HOLD / ESCALATE: invoice remains visible as an exception awaiting resolution upstream.

        store.auditLog.add(new AuditEntry(
                store.now(), inv.id, "HUMAN_DECISION", inv.status, inv.recommendation, decision, reason));

        respondJson(exchange, 200, toMap(inv));
    }

    // ---------------------------------------------------------------
    // Static file serving for the frontend (webapp/ directory)
    // ---------------------------------------------------------------
    class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = webRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.exists(file) || Files.isDirectory(file)) {
                byte[] notFound = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(notFound); }
                return;
            }
            String contentType = guessContentType(file.toString());
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    private static String guessContentType(String filename) {
        if (filename.endsWith(".html")) return "text/html; charset=utf-8";
        if (filename.endsWith(".css")) return "text/css; charset=utf-8";
        if (filename.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (filename.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------
    // Helpers: request/response plumbing + Invoice <-> Map conversion
    // ---------------------------------------------------------------
    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void respondJson(HttpExchange exchange, int status, Object payload) throws IOException {
        String json = Json.write(payload);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    private Map<String, Object> toMap(Invoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.id);
        m.put("vendor", inv.vendor);
        m.put("poReference", inv.poReference);
        m.put("quantity", inv.quantity);
        m.put("uom", inv.uom);
        m.put("unitPrice", inv.unitPrice);
        m.put("gstNumber", inv.gstNumber);
        m.put("currency", inv.currency);
        m.put("scanQuality", inv.scanQuality);
        m.put("submittedAt", inv.submittedAt);
        m.put("status", inv.status);
        m.put("issues", inv.issues);
        m.put("recommendation", inv.recommendation);
        m.put("quantityVariancePercent", inv.quantityVariancePercent);
        m.put("priceVariancePercent", inv.priceVariancePercent);
        m.put("gstMatch", inv.gstMatch);
        m.put("poFound", inv.poFound);
        m.put("grnFound", inv.grnFound);
        m.put("humanDecision", inv.humanDecision);
        m.put("decisionReason", inv.decisionReason);
        m.put("decidedAt", inv.decidedAt);

        // Attach PO / GRN reference data for side-by-side display in the UI
        PurchaseOrder po = store.purchaseOrders.get(inv.poReference);
        if (po != null) {
            Map<String, Object> poMap = new LinkedHashMap<>();
            poMap.put("poNumber", po.poNumber);
            poMap.put("vendor", po.vendor);
            poMap.put("material", po.material);
            poMap.put("quantity", po.quantity);
            poMap.put("uom", po.uom);
            poMap.put("unitPrice", po.unitPrice);
            poMap.put("gstNumber", po.gstNumber);
            m.put("po", poMap);
        } else {
            m.put("po", null);
        }
        GRN grn = store.grns.get(inv.poReference);
        if (grn != null) {
            Map<String, Object> grnMap = new LinkedHashMap<>();
            grnMap.put("grnNumber", grn.grnNumber);
            grnMap.put("quantityReceived", grn.quantityReceived);
            grnMap.put("dateReceived", grn.dateReceived);
            grnMap.put("partial", grn.partial);
            m.put("grn", grnMap);
        } else {
            m.put("grn", null);
        }
        return m;
    }

    private List<Object> posAsList() {
        List<Object> result = new ArrayList<>();
        for (PurchaseOrder po : store.purchaseOrders.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("poNumber", po.poNumber);
            m.put("vendor", po.vendor);
            m.put("material", po.material);
            m.put("quantity", po.quantity);
            m.put("uom", po.uom);
            m.put("unitPrice", po.unitPrice);
            m.put("gstNumber", po.gstNumber);
            m.put("currency", po.currency);
            result.add(m);
        }
        return result;
    }

    private List<Object> grnsAsList() {
        List<Object> result = new ArrayList<>();
        for (GRN grn : store.grns.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("grnNumber", grn.grnNumber);
            m.put("poNumber", grn.poNumber);
            m.put("quantityReceived", grn.quantityReceived);
            m.put("dateReceived", grn.dateReceived);
            m.put("partial", grn.partial);
            result.add(m);
        }
        return result;
    }

    private List<Object> auditAsList() {
        List<Object> result = new ArrayList<>();
        List<AuditEntry> reversed = new ArrayList<>(store.auditLog);
        Collections.reverse(reversed); // most recent first
        for (AuditEntry a : reversed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", a.timestamp);
            m.put("invoiceId", a.invoiceId);
            m.put("event", a.event);
            m.put("aiStatus", a.aiStatus);
            m.put("aiRecommendation", a.aiRecommendation);
            m.put("humanDecision", a.humanDecision);
            m.put("reason", a.reason);
            result.add(m);
        }
        return result;
    }
}
