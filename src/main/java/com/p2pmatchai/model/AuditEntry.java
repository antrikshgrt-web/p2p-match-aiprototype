package com.p2pmatchai.model;

/**
 * One row of the audit trail: an AI recommendation paired with (eventually)
 * a human decision. Per proposal Section 7 & 12, every AI output and every
 * human decision must be logged -- nothing is ever auto-posted.
 */
public class AuditEntry {
    public String timestamp;
    public String invoiceId;
    public String event;          // e.g. "AI_CLASSIFIED", "HUMAN_DECISION"
    public String aiStatus;       // CLEAN_MATCH / EXCEPTION at time of event
    public String aiRecommendation;
    public String humanDecision;  // null until a human acts
    public String reason;

    public AuditEntry(String timestamp, String invoiceId, String event, String aiStatus,
                       String aiRecommendation, String humanDecision, String reason) {
        this.timestamp = timestamp;
        this.invoiceId = invoiceId;
        this.event = event;
        this.aiStatus = aiStatus;
        this.aiRecommendation = aiRecommendation;
        this.humanDecision = humanDecision;
        this.reason = reason;
    }
}
