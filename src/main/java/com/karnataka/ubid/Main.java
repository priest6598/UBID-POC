package com.karnataka.ubid;

import com.karnataka.ubid.activity.ActivityClassifier;
import com.karnataka.ubid.activity.ActivityEventGenerator;
import com.karnataka.ubid.data.MockDataGenerator;
import com.karnataka.ubid.matching.BlockingEngine;
import com.karnataka.ubid.matching.ProbabilisticMatcher;
import com.karnataka.ubid.model.ActivityEvent;
import com.karnataka.ubid.model.ActivityStatus;
import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ScoredPair;
import com.karnataka.ubid.report.HtmlReportGenerator;
import com.karnataka.ubid.report.JsonWriter;
import com.karnataka.ubid.scrambling.PIIScrambler;
import com.karnataka.ubid.ubid.UBIDRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-to-end pipeline orchestrator.
 *
 * Stages:
 *   1. Ingest mock master data from 4 department systems
 *   2. PII-scramble before any matching reads PII
 *   3. Blocking → candidate pairs
 *   4. Probabilistic matching → scored pairs with evidence
 *   5. UBID assignment via union-find on AUTO_LINK pairs
 *   6. Activity event generation + classification
 *   7. Reports (console summary + HTML + JSON)
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "output");
        Files.createDirectories(outDir);

        section("Stage 1 — Ingestion");
        MockDataGenerator gen = new MockDataGenerator(42L);
        List<BusinessRecord> records = gen.generate();
        System.out.printf("  Loaded %d records from 4 source systems:%n", records.size());
        Map<String, Long> bySys = records.stream()
                .collect(Collectors.groupingBy(BusinessRecord::sourceSystem, Collectors.counting()));
        bySys.forEach((s, c) -> System.out.printf("    %-12s %d records%n", s, c));

        section("Stage 2 — PII Scrambling");
        PIIScrambler scrambler = new PIIScrambler("hackathon-run-2026-05-02");
        records = scrambler.scrambleAll(records);
        BusinessRecord sample = records.get(0);
        System.out.printf("  Sample (record %s):%n", sample.recordId());
        System.out.printf("    raw name : %s%n", sample.businessName());
        System.out.printf("    scrambled: %s%n", sample.scrambledName());
        System.out.printf("    raw PAN  : %s  →  scrambled PAN: %s%n", sample.pan(), sample.scrambledPan());
        System.out.println("  All downstream stages operate on scrambled fields only.");

        section("Stage 3 — Blocking (candidate generation)");
        BlockingEngine blocker = new BlockingEngine();
        List<BlockingEngine.CandidatePair> candidates = blocker.generateCandidates(records);
        long fullCartesian = (long) records.size() * (records.size() - 1) / 2;
        System.out.printf("  Full cartesian space  : %d pairs%n", fullCartesian);
        System.out.printf("  Candidate pairs after blocking: %d  (reduction: %.2f%%)%n",
                candidates.size(), 100.0 * (1.0 - (double) candidates.size() / Math.max(1, fullCartesian)));

        section("Stage 4 — Probabilistic Matching (Fellegi-Sunter)");
        ProbabilisticMatcher matcher = new ProbabilisticMatcher();
        List<ScoredPair> scored = matcher.score(candidates);
        long autoLinks = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.AUTO_LINK).count();
        long reviews   = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.REVIEW).count();
        long noLinks   = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.NO_LINK).count();
        System.out.printf("  AUTO_LINK : %d   REVIEW : %d   NO_LINK : %d%n", autoLinks, reviews, noLinks);

        // Diagnostic: pairs landing near the decision boundary (interesting for demo)
        scored.stream()
                .filter(s -> s.matchProbability() > 0.5 && s.matchProbability() < 0.99)
                .sorted(Comparator.<ScoredPair>comparingDouble(s -> -s.matchProbability()))
                .limit(8)
                .forEach(s -> System.out.printf("    near-boundary p=%.4f  %s ⟷ %s%n",
                        s.matchProbability(), s.left().recordId(), s.right().recordId()));

        section("Stage 5 — UBID Assignment");
        UBIDRegistry registry = new UBIDRegistry(records);
        registry.build(scored);
        List<UBIDRegistry.Cluster> clusters = registry.clusters();
        long multiSystem = clusters.stream().filter(c -> c.members().size() > 1).count();
        long panAnchored = clusters.stream().filter(UBIDRegistry.Cluster::panAnchored).count();
        System.out.printf("  UBIDs issued       : %d%n", clusters.size());
        System.out.printf("  Multi-record UBIDs : %d  (cross-system duplicates resolved)%n", multiSystem);
        System.out.printf("  PAN-anchored UBIDs : %d%n", panAnchored);
        System.out.printf("  Review queue       : %d pair(s)%n", registry.reviewQueue().size());

        // Show the largest 3 multi-record clusters as a demo
        System.out.println();
        System.out.println("  Top resolved entities:");
        clusters.stream()
                .filter(c -> c.members().size() > 1)
                .sorted(Comparator.<UBIDRegistry.Cluster>comparingInt(c -> -c.members().size()))
                .limit(3)
                .forEach(c -> {
                    System.out.printf("    %s  (%d records, %s)%n",
                            c.ubid(), c.members().size(), c.panAnchored() ? "PAN-anchored" : "internal");
                    c.members().forEach(m -> System.out.printf("       └─ [%s] %s%n",
                            m.sourceSystem(), m.businessName()));
                });

        section("Stage 6 — Activity Event Generation & Classification");
        ActivityEventGenerator eventGen = new ActivityEventGenerator(43L, LocalDate.of(2026, 5, 2));
        ActivityEventGenerator.GeneratedEvents ge = eventGen.generate(records);
        System.out.printf("  Generated %d activity events across %d records%n",
                ge.events().size(), records.size());

        ActivityClassifier classifier = new ActivityClassifier(LocalDate.of(2026, 5, 2));
        Map<String, ActivityStatus> activity =
                classifier.classifyAll(registry.assignments(), ge.events());
        Map<ActivityStatus.Status, Long> bySts = activity.values().stream()
                .collect(Collectors.groupingBy(ActivityStatus::status, Collectors.counting()));
        System.out.println("  Status distribution:");
        for (ActivityStatus.Status s : ActivityStatus.Status.values()) {
            System.out.printf("    %-8s %d%n", s, bySts.getOrDefault(s, 0L));
        }

        section("Stage 7 — Writing Reports");
        Path htmlPath  = outDir.resolve("ubid-report.html");
        Path regJson   = outDir.resolve("ubid-registry.json");
        Path actJson   = outDir.resolve("activity-classifications.json");
        Path reviewJson = outDir.resolve("review-queue.json");

        new HtmlReportGenerator().writeReport(htmlPath, records, clusters,
                registry.reviewQueue(), activity);
        Files.writeString(regJson, registryJson(clusters));
        Files.writeString(actJson, activityJson(activity));
        Files.writeString(reviewJson, reviewJson(registry.reviewQueue()));

        System.out.printf("  HTML report   : %s%n", htmlPath.toAbsolutePath());
        System.out.printf("  Registry JSON : %s%n", regJson.toAbsolutePath());
        System.out.printf("  Activity JSON : %s%n", actJson.toAbsolutePath());
        System.out.printf("  Review JSON   : %s%n", reviewJson.toAbsolutePath());

        section("Done");
        System.out.println("  Open the HTML report in a browser to inspect the full demo:");
        System.out.println("    open " + htmlPath.toAbsolutePath());
    }

    /* ----------------------- JSON serialization helpers ----------------------- */

    private static String registryJson(List<UBIDRegistry.Cluster> clusters) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UBIDRegistry.Cluster c : clusters) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ubid", c.ubid());
            entry.put("pan_anchored", c.panAnchored());
            entry.put("member_count", c.members().size());
            List<Map<String, Object>> members = new ArrayList<>();
            for (BusinessRecord r : c.members()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("record_id", r.recordId());
                m.put("source_system", r.sourceSystem());
                m.put("business_name", r.businessName());
                m.put("address", r.address());
                m.put("pin_code", r.pinCode());
                m.put("pan", r.pan());
                m.put("gstin", r.gstin());
                m.put("sector", r.sector());
                m.put("registration_date", r.registrationDate().toString());
                members.add(m);
            }
            entry.put("members", members);
            out.add(entry);
        }
        return JsonWriter.stringify(out);
    }

    private static String activityJson(Map<String, ActivityStatus> activity) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ActivityStatus> e : activity.entrySet()) {
            ActivityStatus s = e.getValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", s.status().name());
            entry.put("reasoning", s.reasoning());
            List<Map<String, Object>> evs = new ArrayList<>();
            for (ActivityEvent ev : s.evidenceEvents()) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("source_system", ev.sourceSystem());
                em.put("source_record_id", ev.sourceRecordId());
                em.put("event_type", ev.eventType().name());
                em.put("event_date", ev.eventDate().toString());
                em.put("metadata", ev.metadata());
                evs.add(em);
            }
            entry.put("evidence_events", evs);
            out.put(e.getKey(), entry);
        }
        return JsonWriter.stringify(out);
    }

    private static String reviewJson(List<ScoredPair> queue) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScoredPair sp : queue) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("left_record_id", sp.left().recordId());
            e.put("left_name", sp.left().businessName());
            e.put("right_record_id", sp.right().recordId());
            e.put("right_name", sp.right().businessName());
            e.put("match_probability", sp.matchProbability());
            e.put("decision", sp.decision().name());
            List<Map<String, Object>> ev = new ArrayList<>();
            sp.evidence().values().forEach(fc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("feature", fc.featureName());
                m.put("observation", fc.observation());
                m.put("delta_log_odds", fc.logOddsContribution());
                ev.add(m);
            });
            e.put("evidence", ev);
            out.add(e);
        }
        return JsonWriter.stringify(out);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("══ " + title + " ══");
    }
}
