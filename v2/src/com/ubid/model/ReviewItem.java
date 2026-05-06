package com.ubid.model;

import java.util.Map;

public class ReviewItem {
    public String id;
    public BusinessRecord record1;
    public BusinessRecord record2;
    public double score;
    public Map<String, Double> features;
    public String evidence;
    public String status;      // PENDING, REVIEWED
    public String decision;    // MATCH, NO_MATCH, null
    public String reviewNote;
    public String createdAt;

    public ReviewItem(String id, BusinessRecord r1, BusinessRecord r2,
                      double score, Map<String, Double> features,
                      String evidence, String createdAt) {
        this.id = id;
        this.record1 = r1;
        this.record2 = r2;
        this.score = score;
        this.features = features;
        this.evidence = evidence;
        this.status = "PENDING";
        this.decision = null;
        this.reviewNote = null;
        this.createdAt = createdAt;
    }

    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"record1\":%s,\"record2\":%s,\"score\":%.4f," +
            "\"features\":%s,\"evidence\":\"%s\",\"status\":\"%s\"," +
            "\"decision\":%s,\"reviewNote\":%s,\"createdAt\":\"%s\"}",
            esc(id),
            record1.toJson(),
            record2.toJson(),
            score,
            featuresToJson(),
            esc(evidence),
            esc(status),
            decision == null ? "null" : "\"" + esc(decision) + "\"",
            reviewNote == null ? "null" : "\"" + esc(reviewNote) + "\"",
            esc(createdAt)
        );
    }

    private String featuresToJson() {
        if (features == null || features.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : features.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(String.format("%.4f", e.getValue()));
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
