package com.karnataka.ubid.report;

import com.karnataka.ubid.model.ActivityStatus;
import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ScoredPair;
import com.karnataka.ubid.ubid.UBIDRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Produces a self-contained HTML demo report. */
public final class HtmlReportGenerator {

    public void writeReport(Path out,
                            List<BusinessRecord> records,
                            List<UBIDRegistry.Cluster> clusters,
                            List<ScoredPair> reviewQueue,
                            Map<String, ActivityStatus> activity) throws IOException {
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

        sb.append("<p style='color:#999;font-size:11px;margin-top:48px'>")
          .append("Generated by the UBID POC. All names/addresses/PAN/GSTIN values shown are mock data.")
          .append("</p>");
        sb.append("</body></html>");

        Files.writeString(out, sb.toString());
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
