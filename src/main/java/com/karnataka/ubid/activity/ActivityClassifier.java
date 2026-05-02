package com.karnataka.ubid.activity;

import com.karnataka.ubid.model.ActivityEvent;
import com.karnataka.ubid.model.ActivityStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based activity classifier.
 *
 * Events for every source record under a given UBID are aggregated, then
 * a deterministic decision tree assigns ACTIVE / DORMANT / CLOSED / UNKNOWN
 * with a written reasoning string. Every verdict carries the evidence
 * timeline (the events the rule fired on) for reviewer auditability.
 *
 * Deliberately simple — rules first, ML override would slot in here as a
 * scoring pass over the same feature vector.
 */
public final class ActivityClassifier {

    private final LocalDate today;

    public ActivityClassifier(LocalDate today) { this.today = today; }

    /**
     * @param recordToUbid  recordId -> UBID
     * @param events        all events across all source systems
     * @return  ubid -> ActivityStatus
     */
    public Map<String, ActivityStatus> classifyAll(
            Map<String, String> recordToUbid,
            List<ActivityEvent> events) {

        // Group events under their UBID via the recordId mapping
        Map<String, List<ActivityEvent>> eventsByUbid = new HashMap<>();
        for (ActivityEvent e : events) {
            String ubid = recordToUbid.get(e.sourceRecordId());
            if (ubid == null) continue; // unresolved — would go to unresolved_events topic
            eventsByUbid.computeIfAbsent(ubid, k -> new ArrayList<>()).add(e);
        }

        Map<String, ActivityStatus> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ActivityEvent>> e : eventsByUbid.entrySet()) {
            out.put(e.getKey(), classify(e.getKey(), e.getValue()));
        }
        // UBIDs with zero events
        for (String ubid : recordToUbid.values()) {
            out.computeIfAbsent(ubid, k -> new ActivityStatus(
                    k, ActivityStatus.Status.UNKNOWN,
                    "No events received from any department system in the observation window.",
                    List.of()));
        }
        return out;
    }

    private ActivityStatus classify(String ubid, List<ActivityEvent> events) {
        events.sort(Comparator.comparing(ActivityEvent::eventDate).reversed());

        boolean hasClosure = events.stream()
                .anyMatch(e -> e.eventType() == ActivityEvent.EventType.CLOSURE);
        if (hasClosure) {
            ActivityEvent c = events.stream()
                    .filter(e -> e.eventType() == ActivityEvent.EventType.CLOSURE)
                    .findFirst().orElseThrow();
            return new ActivityStatus(ubid, ActivityStatus.Status.CLOSED,
                    "Explicit closure/deregistration event recorded by " + c.sourceSystem()
                            + " on " + c.eventDate() + ".",
                    List.of(c));
        }

        long daysSinceRenewal = daysSince(events, ActivityEvent.EventType.RENEWAL);
        long daysSinceInspection = daysSince(events, ActivityEvent.EventType.INSPECTION);
        long daysSinceFiling = daysSince(events, ActivityEvent.EventType.FILING);
        boolean hasRecentConsumption = events.stream()
                .anyMatch(e -> e.eventType() == ActivityEvent.EventType.CONSUMPTION
                        && ChronoUnit.DAYS.between(e.eventDate(), today) <= 90);

        // ACTIVE if any of: renewal in past year, recent inspection, recent consumption,
        // OR multiple recent filings.
        long recentFilings = events.stream()
                .filter(e -> e.eventType() == ActivityEvent.EventType.FILING
                        && ChronoUnit.DAYS.between(e.eventDate(), today) <= 365)
                .count();

        if (daysSinceRenewal <= 365 || daysSinceInspection <= 180
                || hasRecentConsumption || recentFilings >= 2) {
            String reason = buildActiveReason(daysSinceRenewal, daysSinceInspection,
                    hasRecentConsumption, recentFilings);
            return new ActivityStatus(ubid, ActivityStatus.Status.ACTIVE, reason,
                    pickRecent(events, 5));
        }

        // DORMANT — has historical activity but nothing recent
        if (!events.isEmpty() && daysSinceRenewal > 365) {
            return new ActivityStatus(ubid, ActivityStatus.Status.DORMANT,
                    "Most recent renewal was " + daysSinceRenewal + " days ago; "
                            + "no inspection in last 180 days and no recent consumption. "
                            + "Likely dormant — recommend triage.",
                    pickRecent(events, 5));
        }

        return new ActivityStatus(ubid, ActivityStatus.Status.UNKNOWN,
                "Insufficient signal across observed event windows.", events);
    }

    private long daysSince(List<ActivityEvent> events, ActivityEvent.EventType t) {
        return events.stream()
                .filter(e -> e.eventType() == t)
                .mapToLong(e -> ChronoUnit.DAYS.between(e.eventDate(), today))
                .min().orElse(Long.MAX_VALUE);
    }

    private List<ActivityEvent> pickRecent(List<ActivityEvent> events, int n) {
        return events.stream().limit(n).toList();
    }

    private String buildActiveReason(long renewal, long inspection,
                                     boolean consumption, long recentFilings) {
        List<String> reasons = new ArrayList<>();
        if (renewal <= 365) reasons.add("renewal " + renewal + " days ago");
        if (inspection <= 180) reasons.add("inspection " + inspection + " days ago");
        if (consumption) reasons.add("active utility consumption in last 90 days");
        if (recentFilings >= 2) reasons.add(recentFilings + " compliance filings in last 365 days");
        return "Active — " + String.join(", ", reasons) + ".";
    }
}
