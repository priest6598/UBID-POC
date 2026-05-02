package com.karnataka.ubid.activity;

import com.karnataka.ubid.model.ActivityEvent;
import com.karnataka.ubid.model.BusinessRecord;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Synthesises 12 months of activity events for each source record. Each
 * canonical business is randomly assigned a "ground truth" pattern (active,
 * dormant, or closed), then its events are sampled from that pattern.
 *
 * The classifier in {@link ActivityClassifier} is then evaluated against
 * this ground truth, demonstrating that classification works without access
 * to the latent label.
 */
public final class ActivityEventGenerator {

    public enum GroundTruth { ACTIVE, DORMANT, CLOSED }

    public record GeneratedEvents(
            List<ActivityEvent> events,
            java.util.Map<String, GroundTruth> truthByRecordId
    ) {}

    private final Random rng;
    private final LocalDate today;

    public ActivityEventGenerator(long seed, LocalDate today) {
        this.rng = new Random(seed);
        this.today = today;
    }

    public GeneratedEvents generate(List<BusinessRecord> records) {
        List<ActivityEvent> events = new ArrayList<>();
        java.util.Map<String, GroundTruth> truth = new java.util.LinkedHashMap<>();

        for (BusinessRecord r : records) {
            GroundTruth gt = pickTruth();
            truth.put(r.recordId(), gt);
            switch (gt) {
                case ACTIVE -> emitActive(r, events);
                case DORMANT -> emitDormant(r, events);
                case CLOSED -> emitClosed(r, events);
            }
        }
        return new GeneratedEvents(events, truth);
    }

    private GroundTruth pickTruth() {
        double p = rng.nextDouble();
        if (p < 0.65) return GroundTruth.ACTIVE;
        if (p < 0.90) return GroundTruth.DORMANT;
        return GroundTruth.CLOSED;
    }

    private void emitActive(BusinessRecord r, List<ActivityEvent> out) {
        // Recent renewal, recent inspection, ongoing consumption
        out.add(event(r, ActivityEvent.EventType.RENEWAL, today.minusDays(rng.nextInt(180))));
        if (r.sourceSystem().equals("FACTORIES") || r.sourceSystem().equals("KSPCB")) {
            out.add(event(r, ActivityEvent.EventType.INSPECTION, today.minusDays(rng.nextInt(120))));
        }
        if (r.sourceSystem().equals("BWSSB")) {
            // monthly consumption
            for (int m = 0; m < 12; m++) {
                out.add(event(r, ActivityEvent.EventType.CONSUMPTION,
                        today.minusDays(30 * m + rng.nextInt(5)),
                        "kl=" + (5 + rng.nextInt(50))));
            }
        }
        // Periodic compliance filings
        for (int i = 0; i < 3 + rng.nextInt(4); i++) {
            out.add(event(r, ActivityEvent.EventType.FILING, today.minusDays(rng.nextInt(330))));
        }
    }

    private void emitDormant(BusinessRecord r, List<ActivityEvent> out) {
        // Stale renewal — older than a year — and sparse activity
        out.add(event(r, ActivityEvent.EventType.RENEWAL, today.minusDays(400 + rng.nextInt(400))));
        // No recent inspection or consumption; maybe one old filing
        if (rng.nextDouble() < 0.4) {
            out.add(event(r, ActivityEvent.EventType.FILING, today.minusDays(450 + rng.nextInt(200))));
        }
        if (rng.nextDouble() < 0.3) {
            out.add(event(r, ActivityEvent.EventType.ADDRESS_CHANGE, today.minusDays(300 + rng.nextInt(150))));
        }
    }

    private void emitClosed(BusinessRecord r, List<ActivityEvent> out) {
        out.add(event(r, ActivityEvent.EventType.CLOSURE, today.minusDays(rng.nextInt(400))));
        // Some historical activity before closure
        out.add(event(r, ActivityEvent.EventType.RENEWAL, today.minusDays(500 + rng.nextInt(300))));
    }

    private ActivityEvent event(BusinessRecord r, ActivityEvent.EventType t, LocalDate d) {
        return event(r, t, d, "");
    }

    private ActivityEvent event(BusinessRecord r, ActivityEvent.EventType t, LocalDate d, String meta) {
        return new ActivityEvent(r.sourceSystem(), r.recordId(), t, d, meta);
    }
}
