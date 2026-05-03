package com.karnataka.ubid;

import com.karnataka.ubid.activity.ActivityClassifier;
import com.karnataka.ubid.activity.ActivityEventGenerator;
import com.karnataka.ubid.data.MockDataGenerator;
import com.karnataka.ubid.learning.ActiveLearningTrainer;
import com.karnataka.ubid.learning.MUParams;
import com.karnataka.ubid.matching.BlockingEngine;
import com.karnataka.ubid.matching.ProbabilisticMatcher;
import com.karnataka.ubid.model.ActivityEvent;
import com.karnataka.ubid.model.ActivityStatus;
import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ReviewerDecision;
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
import java.util.HashMap;
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
 *   4. Round-1 matching with default MU params
 *   5. Round-1 UBID assignment + review queue
 *   6. Reviewer decisions (simulated) → retrain MU via Bayesian update
 *   7. Round-2 matching with updated params, re-issue UBIDs
 *   8. Activity classification (uses round-2 UBIDs)
 *   9. Reports
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "output");
        Files.createDirectories(outDir);

        section("Stage 1 — Ingestion");
        MockDataGenerator gen = new MockDataGenerator(42L);
        List<BusinessRecord> records = gen.generate();
        System.out.printf("  Loaded %d records from 4 source systems:%n", records.size());
        records.stream()
                .collect(Collectors.groupingBy(BusinessRecord::sourceSystem, Collectors.counting()))
                .forEach((s, c) -> System.out.printf("    %-12s %d records%n", s, c));

        section("Stage 2 — PII Scrambling");
        PIIScrambler scrambler = new PIIScrambler("hackathon-run-2026-05-02");
        records = scrambler.scrambleAll(records);
        BusinessRecord sample = records.get(0);
        System.out.printf("  Sample (record %s):%n", sample.recordId());
        System.out.printf("    raw name : %s%n", sample.businessName());
        System.out.printf("    scrambled: %s%n", sample.scrambledName());
        System.out.printf("    raw PAN  : %s  →  scrambled PAN: %s%n", sample.pan(), sample.scrambledPan());

        section("Stage 3 — Blocking");
        List<BlockingEngine.CandidatePair> candidates = new BlockingEngine().generateCandidates(records);
        long fullCartesian = (long) records.size() * (records.size() - 1) / 2;
        System.out.printf("  %d → %d candidates (%.1f%% reduction)%n",
                fullCartesian, candidates.size(),
                100.0 * (1.0 - (double) candidates.size() / Math.max(1, fullCartesian)));

        // ===================== ROUND 1 ===================== //
        section("Stage 4 — Probabilistic Matching (Round 1, default MU params)");
        MUParams paramsV1 = MUParams.defaults();
        ProbabilisticMatcher matcherV1 = new ProbabilisticMatcher(paramsV1);
        List<ScoredPair> scoredV1 = matcherV1.score(candidates);
        printDecisionCounts(scoredV1);

        section("Stage 5 — UBID Assignment (Round 1)");
        UBIDRegistry registryV1 = new UBIDRegistry(records);
        registryV1.build(scoredV1);
        printRegistryStats("Round 1", registryV1);

        // ===================== ACTIVE LEARNING ===================== //
        section("Stage 6 — Reviewer Decisions & Retraining");
        List<ReviewerDecision> decisions = simulateReviewerDecisions(scoredV1);
        System.out.printf("  %d reviewer decisions captured (%d match, %d non-match)%n",
                decisions.size(),
                decisions.stream().filter(d -> d.verdict() == ReviewerDecision.Verdict.CONFIRMED_MATCH).count(),
                decisions.stream().filter(d -> d.verdict() == ReviewerDecision.Verdict.CONFIRMED_NON_MATCH).count());

        // Prior strength of 8 — light enough that 35 reviewer decisions can
        // meaningfully shift tier weights, heavy enough to prevent overfit.
        // In production with thousands of reviewer decisions per cycle, a
        // higher value (40+) makes more sense.
        ActiveLearningTrainer trainer = new ActiveLearningTrainer(8.0);
        ActiveLearningTrainer.Result trained = trainer.retrain(paramsV1, scoredV1, decisions);
        MUParams paramsV2 = trained.updatedParams();

        System.out.println("  Updated tiers (only those that received labelled hits):");
        System.out.printf("    %-26s %-22s %-22s %s%n", "tier", "(m, u) before", "(m, u) after", "Δ log-odds");
        trained.adjustments().forEach((tier, adj) ->
                System.out.printf("    %-26s (%.4f, %.4f)   (%.4f, %.4f)   %+.3f   [match_hits=%d, nonmatch_hits=%d]%n",
                        tier, adj.oldM(), adj.oldU(), adj.newM(), adj.newU(),
                        adj.delta(), adj.matchHits(), adj.nonMatchHits()));

        // ===================== ROUND 2 ===================== //
        section("Stage 7 — Re-scoring with Updated Params (Round 2)");
        ProbabilisticMatcher matcherV2 = new ProbabilisticMatcher(paramsV2);
        List<ScoredPair> scoredV2 = matcherV2.score(candidates);
        printDecisionCounts(scoredV2);

        UBIDRegistry registryV2 = new UBIDRegistry(records);
        registryV2.build(scoredV2);
        printRegistryStats("Round 2", registryV2);

        // Show pair-level deltas (probability changes)
        Map<String, Double> probV1 = scoredV1.stream().collect(Collectors.toMap(
                Main::pairKey, ScoredPair::matchProbability, (a, b) -> a));
        List<ScoredPair> flippedDecision = new ArrayList<>();
        List<double[]> probDeltas = new ArrayList<>();
        for (ScoredPair sp : scoredV2) {
            double v1 = probV1.getOrDefault(pairKey(sp), -1.0);
            if (v1 < 0) continue;
            // Find old decision
            ScoredPair old = scoredV1.stream()
                    .filter(s -> pairKey(s).equals(pairKey(sp))).findFirst().orElse(null);
            if (old != null && old.decision() != sp.decision()) {
                flippedDecision.add(sp);
                probDeltas.add(new double[]{v1, sp.matchProbability()});
            }
        }
        // Show every previously-REVIEW pair's before/after probability,
        // even when the decision didn't flip (proposal §4.2: queue shrinks
        // gradually over many cycles, so per-cycle movement is what matters)
        System.out.println();
        System.out.println("  REVIEW-zone pair trajectory (before → after retraining):");
        scoredV1.stream()
                .filter(s -> s.decision() == ScoredPair.Decision.REVIEW)
                .forEach(s -> {
                    ScoredPair after = scoredV2.stream()
                            .filter(sp -> pairKey(sp).equals(pairKey(s))).findFirst().orElseThrow();
                    System.out.printf("    %s ⟷ %s   p: %.4f → %.4f  (%+.4f)   [%s → %s]%n",
                            s.left().recordId(), s.right().recordId(),
                            s.matchProbability(), after.matchProbability(),
                            after.matchProbability() - s.matchProbability(),
                            s.decision(), after.decision());
                });

        System.out.println();
        System.out.printf("  %d pair decision(s) flipped after retraining:%n", flippedDecision.size());
        for (int i = 0; i < flippedDecision.size(); i++) {
            ScoredPair sp = flippedDecision.get(i);
            double v1 = probDeltas.get(i)[0];
            double v2 = probDeltas.get(i)[1];
            ScoredPair old = scoredV1.stream()
                    .filter(s -> pairKey(s).equals(pairKey(sp))).findFirst().orElseThrow();
            System.out.printf("    %s ⟷ %s%n", sp.left().recordId(), sp.right().recordId());
            System.out.printf("       %s (p=%.4f)  →  %s (p=%.4f)%n",
                    old.decision(), v1, sp.decision(), v2);
        }

        // ===================== ACTIVITY ===================== //
        section("Stage 8 — Activity Event Generation & Classification");
        ActivityEventGenerator eventGen = new ActivityEventGenerator(43L, LocalDate.of(2026, 5, 2));
        ActivityEventGenerator.GeneratedEvents ge = eventGen.generate(records);
        System.out.printf("  Generated %d activity events across %d records%n",
                ge.events().size(), records.size());

        ActivityClassifier classifier = new ActivityClassifier(LocalDate.of(2026, 5, 2));
        Map<String, ActivityStatus> activity = classifier.classifyAll(registryV2.assignments(), ge.events());
        Map<ActivityStatus.Status, Long> bySts = activity.values().stream()
                .collect(Collectors.groupingBy(ActivityStatus::status, Collectors.counting()));
        System.out.println("  Status distribution:");
        for (ActivityStatus.Status s : ActivityStatus.Status.values()) {
            System.out.printf("    %-8s %d%n", s, bySts.getOrDefault(s, 0L));
        }

        // ===================== REPORTS ===================== //
        section("Stage 9 — Writing Reports");
        Path htmlPath  = outDir.resolve("ubid-report.html");
        Path regJson   = outDir.resolve("ubid-registry.json");
        Path actJson   = outDir.resolve("activity-classifications.json");
        Path reviewJson = outDir.resolve("review-queue.json");
        Path decisionsJson = outDir.resolve("reviewer-decisions.json");
        Path trainingJson  = outDir.resolve("training-result.json");

        new HtmlReportGenerator().writeReport(htmlPath, records, registryV2.clusters(),
                registryV2.reviewQueue(), activity,
                new HtmlReportGenerator.ActiveLearningSummary(decisions, trained, scoredV1, scoredV2));
        Files.writeString(regJson, registryJson(registryV2.clusters()));
        Files.writeString(actJson, activityJson(activity));
        Files.writeString(reviewJson, reviewJson(registryV2.reviewQueue()));
        Files.writeString(decisionsJson, decisionsJson(decisions));
        Files.writeString(trainingJson, trainingJson(trained));

        System.out.printf("  HTML report      : %s%n", htmlPath.toAbsolutePath());
        System.out.printf("  Registry JSON    : %s%n", regJson.toAbsolutePath());
        System.out.printf("  Activity JSON    : %s%n", actJson.toAbsolutePath());
        System.out.printf("  Review JSON      : %s%n", reviewJson.toAbsolutePath());
        System.out.printf("  Decisions JSON   : %s%n", decisionsJson.toAbsolutePath());
        System.out.printf("  Training JSON    : %s%n", trainingJson.toAbsolutePath());

        section("Done");
        System.out.println("  open " + htmlPath.toAbsolutePath());
    }

    /* ----------------------- pipeline helpers ----------------------- */

    private static void printDecisionCounts(List<ScoredPair> scored) {
        long auto    = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.AUTO_LINK).count();
        long review  = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.REVIEW).count();
        long noLink  = scored.stream().filter(s -> s.decision() == ScoredPair.Decision.NO_LINK).count();
        System.out.printf("  AUTO_LINK : %d   REVIEW : %d   NO_LINK : %d%n", auto, review, noLink);
    }

    private static void printRegistryStats(String label, UBIDRegistry registry) {
        long multi = registry.clusters().stream().filter(c -> c.members().size() > 1).count();
        long pan = registry.clusters().stream().filter(UBIDRegistry.Cluster::panAnchored).count();
        System.out.printf("  [%s] UBIDs=%d  multi-record=%d  PAN-anchored=%d  review-queue=%d%n",
                label, registry.clusters().size(), multi, pan, registry.reviewQueue().size());
    }

    private static String pairKey(ScoredPair sp) {
        String a = sp.left().recordId(), b = sp.right().recordId();
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /* ----------------------- reviewer simulation ----------------------- */

    /**
     * Simulates what a reviewer team would have submitted after one batch.
     * In production this would come from the reviewer UI's audit log.
     *
     * Strategy: confirm a sample of confident AUTO_LINK pairs (sanity-check
     * sampling) and adjudicate every REVIEW pair. The Ravi Auto Parts/Spares
     * pair is correctly labelled CONFIRMED_NON_MATCH — different pin codes
     * and addresses make this two distinct businesses sharing a first name.
     */
    private static List<ReviewerDecision> simulateReviewerDecisions(List<ScoredPair> scored) {
        List<ReviewerDecision> out = new ArrayList<>();

        // Adjudicate every REVIEW-zone pair
        scored.stream()
                .filter(s -> s.decision() == ScoredPair.Decision.REVIEW)
                .forEach(s -> {
                    // For demo: the calibrated Ravi Auto pair is a non-match
                    // (different pins + addresses → two different businesses)
                    out.add(ReviewerDecision.nonMatch(s.left().recordId(), s.right().recordId(),
                            "reviewer-001",
                            "Different pin codes and addresses — separate businesses sharing a first name."));
                });

        // Confirm a quality-check sample of AUTO_LINK pairs (high confidence,
        // PAN-anchored). In production this is how the reviewer team validates
        // the auto-link zone's precision and feeds positive labels back.
        scored.stream()
                .filter(s -> s.decision() == ScoredPair.Decision.AUTO_LINK)
                .filter(s -> s.matchProbability() > 0.99)
                .limit(15)
                .forEach(s -> out.add(ReviewerDecision.match(
                        s.left().recordId(), s.right().recordId(),
                        "reviewer-002",
                        "PAN match with consistent name and address — clearly the same business.")));

        // Spot-check NO_LINK pairs that scored relatively HIGH (near the
        // boundary) — these are the cases where the model was tempted by
        // surface features. Reviewers prioritise these because labelling them
        // teaches the model the most: it learns that metaphone match alone,
        // or sector match alone, isn't sufficient evidence of a true match.
        // This is the highest-information part of the active-learning loop.
        scored.stream()
                .filter(s -> s.decision() == ScoredPair.Decision.NO_LINK)
                .filter(s -> s.matchProbability() > 0.20 && s.matchProbability() < 0.70)
                .sorted(Comparator.<ScoredPair>comparingDouble(s -> -s.matchProbability()))
                .limit(15)
                .forEach(s -> out.add(ReviewerDecision.nonMatch(
                        s.left().recordId(), s.right().recordId(),
                        "reviewer-003",
                        "Spot-checked near-boundary NO_LINK — confirmed different businesses.")));

        // Plus a sample of clearly-different pairs to give the trainer
        // baseline negative observations across the score range
        scored.stream()
                .filter(s -> s.decision() == ScoredPair.Decision.NO_LINK)
                .filter(s -> s.matchProbability() < 0.05)
                .filter(s -> !s.left().sourceSystem().equals(s.right().sourceSystem()))
                .limit(15)
                .forEach(s -> out.add(ReviewerDecision.nonMatch(
                        s.left().recordId(), s.right().recordId(),
                        "reviewer-003",
                        "Different businesses — confirmed via spot check.")));

        return out;
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
                m.put("tier", fc.tierKey());
                m.put("observation", fc.observation());
                m.put("delta_log_odds", fc.logOddsContribution());
                ev.add(m);
            });
            e.put("evidence", ev);
            out.add(e);
        }
        return JsonWriter.stringify(out);
    }

    private static String decisionsJson(List<ReviewerDecision> decisions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewerDecision d : decisions) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("left_record_id", d.leftRecordId());
            e.put("right_record_id", d.rightRecordId());
            e.put("verdict", d.verdict().name());
            e.put("reviewer_id", d.reviewerId());
            e.put("timestamp", d.timestamp().toString());
            e.put("note", d.note());
            out.add(e);
        }
        return JsonWriter.stringify(out);
    }

    private static String trainingJson(ActiveLearningTrainer.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_match_pairs", r.totalMatches());
        out.put("total_non_match_pairs", r.totalNonMatches());
        Map<String, Object> diffs = new LinkedHashMap<>();
        r.adjustments().forEach((tier, adj) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("old_m", adj.oldM());
            d.put("old_u", adj.oldU());
            d.put("new_m", adj.newM());
            d.put("new_u", adj.newU());
            d.put("old_log_odds", adj.oldLogOdds());
            d.put("new_log_odds", adj.newLogOdds());
            d.put("delta_log_odds", adj.delta());
            d.put("match_hits", adj.matchHits());
            d.put("non_match_hits", adj.nonMatchHits());
            diffs.put(tier, d);
        });
        out.put("tier_adjustments", diffs);
        return JsonWriter.stringify(out);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("══ " + title + " ══");
    }
}
