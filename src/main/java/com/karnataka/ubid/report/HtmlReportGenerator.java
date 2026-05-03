package com.karnataka.ubid.report;

import com.karnataka.ubid.learning.ActiveLearningTrainer;
import com.karnataka.ubid.model.ActivityStatus;
import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ReviewerDecision;
import com.karnataka.ubid.model.ScoredPair;
import com.karnataka.ubid.ubid.UBIDRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Produces a self-contained HTML demo report. */
public final class HtmlReportGenerator {

    /** Bundle of artefacts to render the active-learning section. */
    public record ActiveLearningSummary(
            List<ReviewerDecision> decisions,
            ActiveLearningTrainer.Result trainingResult,
            List<ScoredPair> scoredV1,
            List<ScoredPair> scoredV2
    ) {}

    public void writeReport(Path out,
                            List<BusinessRecord> records,
                            List<UBIDRegistry.Cluster> clusters,
                            List<ScoredPair> reviewQueue,
                            Map<String, ActivityStatus> activity,
                            ActiveLearningSummary learning) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html><head><meta charset="utf-8">
                <title>UBID Platform — POC Report</title>
                <style>
                  body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                         max-width: 1200px; margin: 24px auto; padding: 0 16px; color: #222; }
                  h1 { border-bottom: 2px solid #333; padding-bottom: 8px; }
                  h2 { margin-top: 36px; color: #1a4d8c; }
                  .summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin: 16px 0; }
                  .stat { background: #f4f7fb; padding: 16px; border-radius: 8px; border-left: 4px solid #1a4d8c; }
                  .stat .num { font-size: 28px; font-weight: 600; color: #1a4d8c; }
                  .stat .label { font-size: 12px; text-transform: uppercase; color: #666; }
                  .cluster { border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin: 16px 0; background: #fff; }
                  .cluster.linked { border-left: 4px solid #2a9d3a; }
                  .cluster.solo { border-left: 4px solid #aaa; }
                  .ubid { font-family: monospace; background: #eef; padding: 4px 8px; border-radius: 4px;
                          font-size: 13px; }
                  .badge { display: inline-block; padding: 2px 10px; border-radius: 12px;
                           font-size: 11px; font-weight: 600; text-transform: uppercase; }
                  .badge.active { background: #d4edda; color: #155724; }
                  .badge.dormant { background: #fff3cd; color: #856404; }
                  .badge.closed  { background: #f8d7da; color: #721c24; }
                  .badge.unknown { background: #e2e3e5; color: #383d41; }
                  .badge.pan { background: #cfe2ff; color: #052c65; }
                  .badge.internal { background: #f0f0f0; color: #555; }
                  table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 13px; }
                  th, td { padding: 6px 10px; text-align: left; border-bottom: 1px solid #eee; vertical-align: top; }
                  th { background: #fafafa; font-weight: 600; }
                  .review-pair { background: #fffbe6; border: 1px solid #ffe7a0; padding: 12px;
                                 border-radius: 6px; margin: 12px 0; }
                  .evidence { font-family: monospace; font-size: 12px; background: #f7f7f7; padding: 8px;
                              border-radius: 4px; margin-top: 8px; }
                  .evidence .pos { color: #155724; }
                  .evidence .neg { color: #721c24; }
                  .gauge { display: inline-block; width: 80px; height: 8px; background: #eee;
                           border-radius: 4px; vertical-align: middle; margin-right: 6px; overflow: hidden; }
                  .gauge .fill { height: 100%; background: linear-gradient(90deg, #f5c542, #2a9d3a); }
                  .al-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 12px 0; }
                  .al-card { background: #fafbfd; border: 1px solid #dde4ec; border-radius: 8px; padding: 14px; }
                  .al-card h3 { margin: 0 0 8px 0; font-size: 14px; color: #1a4d8c; }
                  .flip-row { padding: 8px 12px; margin: 6px 0; border-radius: 6px; font-family: monospace;
                              font-size: 12px; background: #f4f7fb; border-left: 3px solid #1a4d8c; }
                  .flip-row .arrow { color: #1a4d8c; font-weight: 600; }
                  .delta-pos { color: #155724; font-weight: 600; }
                  .delta-neg { color: #721c24; font-weight: 600; }
                  .tier-table th { font-size: 11px; }
                  .tier-table td { font-family: monospace; font-size: 12px; }
                </style></head><body>
                """);

        sb.append("<h1>UBID &amp; Active Business Intelligence Platform — POC Report</h1>");

        long linkedClusters = clusters.stream().filter(c -> c.members().size() > 1).count();
        long panAnchored   = clusters.stream().filter(UBIDRegistry.Cluster::panAnchored).count();
        long deduplicated  = records.size() - clusters.size();

        sb.append("<div class='summary'>");
        sb.append(stat(records.size(), "Source Records"));
        sb.append(stat(clusters.size(), "UBIDs Issued"));
        sb.append(stat(deduplicated,    "Cross-System Duplicates Resolved"));
        sb.append(stat(reviewQueue.size(), "Pairs in Review Queue"));
        sb.append("</div>");

        sb.append("<div class='summary'>");
        sb.append(stat(linkedClusters, "Multi-System Linked Clusters"));
        sb.append(stat(panAnchored, "PAN-Anchored UBIDs"));
        long active  = activity.values().stream().filter(a -> a.status() == ActivityStatus.Status.ACTIVE).count();
        long dormant = activity.values().stream().filter(a -> a.status() == ActivityStatus.Status.DORMANT).count();
        long closed  = activity.values().stream().filter(a -> a.status() == ActivityStatus.Status.CLOSED).count();
        sb.append(stat(active + " / " + dormant + " / " + closed, "Active / Dormant / Closed"));
        sb.append(stat(activity.size(), "Status Verdicts"));
        sb.append("</div>");

        /* ---------------------- UBID Registry ---------------------- */
        sb.append("<h2>UBID Registry</h2>");
        sb.append("<p>Each cluster below is one canonical business. Records inside a cluster ")
          .append("appeared as separate rows in the source systems but resolve to a single UBID.</p>");

        for (UBIDRegistry.Cluster c : clusters) {
            String css = c.members().size() > 1 ? "linked" : "solo";
            sb.append("<div class='cluster ").append(css).append("'>");
            sb.append("<div><span class='ubid'>").append(esc(c.ubid())).append("</span> ");
            sb.append("<span class='badge ").append(c.panAnchored() ? "pan'>PAN-anchored" : "internal'>internal-namespace");
            sb.append("</span> ");
            ActivityStatus st = activity.get(c.ubid());
            if (st != null) {
                sb.append("<span class='badge ").append(st.status().name().toLowerCase()).append("'>");
                sb.append(st.status()).append("</span>");
            }
            sb.append(" &nbsp;<small>").append(c.members().size()).append(" record(s)</small></div>");

            sb.append("<table><thead><tr>")
              .append("<th>Record ID</th><th>Source</th><th>Business Name</th>")
              .append("<th>Address</th><th>Pin</th><th>PAN</th><th>GSTIN</th><th>Sector</th>")
              .append("</tr></thead><tbody>");
            for (BusinessRecord r : c.members()) {
                sb.append("<tr>")
                  .append(td(r.recordId()))
                  .append(td(r.sourceSystem()))
                  .append(td(r.businessName()))
                  .append(td(r.address()))
                  .append(td(r.pinCode()))
                  .append(td(nv(r.pan())))
                  .append(td(nv(r.gstin())))
                  .append(td(r.sector()))
                  .append("</tr>");
            }
            sb.append("</tbody></table>");

            if (st != null) {
                sb.append("<div><b>Status reasoning:</b> ").append(esc(st.reasoning())).append("</div>");
            }

            if (!c.linkingPairs().isEmpty()) {
                sb.append("<details><summary>Show linking evidence (")
                  .append(c.linkingPairs().size()).append(" pair(s))</summary>");
                for (ScoredPair sp : c.linkingPairs()) {
                    sb.append(renderEvidence(sp));
                }
                sb.append("</details>");
            }
            sb.append("</div>");
        }

        /* ---------------------- Review Queue ---------------------- */
        sb.append("<h2>Human Review Queue</h2>");
        if (reviewQueue.isEmpty()) {
            sb.append("<p><i>Empty — no ambiguous pairs at current thresholds.</i></p>");
        } else {
            sb.append("<p>Pairs with match probability in [0.70, 0.95). A reviewer would ")
              .append("confirm or reject these — decisions feed back into the active learning loop.</p>");
            for (ScoredPair sp : reviewQueue) {
                sb.append("<div class='review-pair'>");
                sb.append("<div><b>Record A:</b> ").append(esc(sp.left().recordId()))
                  .append(" — ").append(esc(sp.left().businessName())).append("</div>");
                sb.append("<div><b>Record B:</b> ").append(esc(sp.right().recordId()))
                  .append(" — ").append(esc(sp.right().businessName())).append("</div>");
                sb.append("<div><b>Probability:</b> ");
                sb.append(gauge(sp.matchProbability()));
                sb.append(String.format("%.4f", sp.matchProbability())).append("</div>");
                sb.append(renderEvidence(sp));
                sb.append("</div>");
            }
        }

        /* ---------------------- Active Learning Loop ---------------------- */
        sb.append("<h2>Active Learning — Closing the Loop</h2>");
        sb.append(renderActiveLearning(learning));

        sb.append("<p style='color:#999;font-size:11px;margin-top:48px'>")
          .append("Generated by the UBID POC. All names/addresses/PAN/GSTIN values shown are mock data.")
          .append("</p>");
        sb.append("</body></html>");

        Files.writeString(out, sb.toString());
    }

    /* ---------------------- active learning section ---------------------- */

    private static String renderActiveLearning(ActiveLearningSummary al) {
        if (al == null) return "";
        StringBuilder sb = new StringBuilder();

        long matches = al.decisions().stream()
                .filter(d -> d.verdict() == ReviewerDecision.Verdict.CONFIRMED_MATCH).count();
        long nonMatches = al.decisions().stream()
                .filter(d -> d.verdict() == ReviewerDecision.Verdict.CONFIRMED_NON_MATCH).count();

        sb.append("<p>Reviewer decisions are captured as immutable audit records, then fed back to the ")
          .append("Fellegi-Sunter matcher as labelled training data. Per-tier <code>(m, u)</code> values ")
          .append("are re-estimated via Bayesian Beta updating: the current parameters become the prior, ")
          .append("the labelled tier hits become observations. Re-scoring with updated parameters can ")
          .append("flip ambiguous pairs into the auto-link or no-link zones without touching the data.</p>");

        sb.append("<div class='al-grid'>");

        // Decisions captured
        sb.append("<div class='al-card'><h3>Reviewer Decisions Captured</h3>");
        sb.append("<div>Total: <b>").append(al.decisions().size()).append("</b> ");
        sb.append("(").append(matches).append(" confirmed match, ")
          .append(nonMatches).append(" confirmed non-match)</div>");
        sb.append("<details style='margin-top:8px'><summary>Show decisions</summary>");
        sb.append("<table><thead><tr><th>Pair</th><th>Verdict</th><th>Reviewer</th><th>Note</th></tr></thead><tbody>");
        for (ReviewerDecision d : al.decisions()) {
            sb.append("<tr>");
            sb.append("<td>").append(esc(d.leftRecordId())).append(" ⟷ ").append(esc(d.rightRecordId())).append("</td>");
            String badge = switch (d.verdict()) {
                case CONFIRMED_MATCH     -> "<span class='badge active'>match</span>";
                case CONFIRMED_NON_MATCH -> "<span class='badge closed'>non-match</span>";
                case ESCALATED           -> "<span class='badge unknown'>escalated</span>";
            };
            sb.append("<td>").append(badge).append("</td>");
            sb.append("<td>").append(esc(d.reviewerId())).append("</td>");
            sb.append("<td>").append(esc(d.note())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table></details></div>");

        // Tier adjustments
        sb.append("<div class='al-card'><h3>Per-Tier MU Adjustments</h3>");
        sb.append("<div>").append(al.trainingResult().totalMatches()).append(" labelled match pair(s), ")
          .append(al.trainingResult().totalNonMatches()).append(" labelled non-match pair(s)</div>");
        sb.append("<details open style='margin-top:8px'><summary>Show parameter changes</summary>");
        sb.append("<table class='tier-table'><thead><tr>")
          .append("<th>Tier</th><th>m before</th><th>m after</th>")
          .append("<th>u before</th><th>u after</th><th>Δ log-odds</th>")
          .append("<th>match hits</th><th>non-match hits</th></tr></thead><tbody>");
        al.trainingResult().adjustments().forEach((tier, adj) -> {
            sb.append("<tr>");
            sb.append("<td>").append(esc(tier)).append("</td>");
            sb.append(td(String.format("%.4f", adj.oldM())));
            sb.append(td(String.format("%.4f", adj.newM())));
            sb.append(td(String.format("%.4f", adj.oldU())));
            sb.append(td(String.format("%.4f", adj.newU())));
            String cls = adj.delta() > 0 ? "delta-pos" : "delta-neg";
            sb.append("<td class='").append(cls).append("'>")
              .append(String.format("%+.3f", adj.delta())).append("</td>");
            sb.append(td(String.valueOf(adj.matchHits())));
            sb.append(td(String.valueOf(adj.nonMatchHits())));
            sb.append("</tr>");
        });
        sb.append("</tbody></table></details></div>");
        sb.append("</div>");

        // Pair-level flips
        Map<String, ScoredPair> v1 = al.scoredV1().stream().collect(Collectors.toMap(
                HtmlReportGenerator::pairKey, p -> p, (a, b) -> a, HashMap::new));
        java.util.List<Object[]> flips = new java.util.ArrayList<>();
        for (ScoredPair sp : al.scoredV2()) {
            ScoredPair old = v1.get(pairKey(sp));
            if (old != null && old.decision() != sp.decision()) {
                flips.add(new Object[]{old, sp});
            }
        }

        sb.append("<h3 style='margin-top:24px'>Decision Changes After Retraining</h3>");
        if (flips.isEmpty()) {
            sb.append("<p><i>No decision-level flips after retraining (the prior was well-calibrated " +
                      "for this batch).</i></p>");
        } else {
            sb.append("<p>").append(flips.size())
              .append(" pair(s) crossed a decision boundary. Each flip reflects updated tier weights, ")
              .append("not changed input data.</p>");
            for (Object[] pair : flips) {
                ScoredPair before = (ScoredPair) pair[0];
                ScoredPair after = (ScoredPair) pair[1];
                sb.append("<div class='flip-row'>");
                sb.append(esc(before.left().recordId())).append(" ⟷ ").append(esc(before.right().recordId()))
                  .append("<br>");
                sb.append(before.decision().name()).append(" (p=").append(String.format("%.4f", before.matchProbability())).append(")");
                sb.append(" <span class='arrow'>→</span> ");
                sb.append(after.decision().name()).append(" (p=").append(String.format("%.4f", after.matchProbability())).append(")");
                sb.append("</div>");
            }
        }
        return sb.toString();
    }

    private static String pairKey(ScoredPair sp) {
        String a = sp.left().recordId(), b = sp.right().recordId();
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /* ---------------------- helpers ---------------------- */

    private static String stat(Object value, String label) {
        return "<div class='stat'><div class='num'>" + esc(String.valueOf(value)) +
               "</div><div class='label'>" + esc(label) + "</div></div>";
    }

    private static String td(String s) {
        return "<td>" + esc(s) + "</td>";
    }

    private static String nv(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String renderEvidence(ScoredPair sp) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='evidence'>");
        sb.append("p(match) = ").append(String.format("%.4f", sp.matchProbability()))
          .append(" → <b>").append(sp.decision()).append("</b><br>");
        for (var fc : sp.evidence().values()) {
            String cssCls = fc.logOddsContribution() > 0 ? "pos" : "neg";
            sb.append("<span class='").append(cssCls).append("'>")
              .append(esc(fc.featureName())).append(": ")
              .append(esc(fc.observation()))
              .append(" (Δ logodds = ").append(String.format("%+.2f", fc.logOddsContribution())).append(")")
              .append("</span><br>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String gauge(double prob) {
        int pct = (int) Math.round(prob * 100);
        return "<span class='gauge'><span class='fill' style='width:" + pct + "%'></span></span>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
