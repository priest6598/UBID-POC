package com.karnataka.ubid.ubid;

import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ScoredPair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the UBID registry from auto-link decisions.
 *
 * Algorithm:
 *   1. Initialise each record as its own cluster (Union-Find).
 *   2. For every pair the matcher classifies AUTO_LINK, union the two records.
 *      REVIEW pairs are emitted to the review queue (NOT auto-merged — wrong
 *      merges are worse than missed ones, per the proposal).
 *   3. For each cluster, choose a canonical UBID:
 *        - if any record in the cluster has a (scrambled) PAN, anchor on it
 *          (PAN-anchored UBID, portable across departments)
 *        - otherwise, use an internal-namespace UBID seeded by a stable
 *          composite key (sorted record IDs)
 *
 * The PAN-anchored property is important: when a currently-unanchored UBID
 * later gains a PAN (via a future ingest), it can be PROMOTED to the same
 * PAN-anchored UBID without re-issuing identifiers downstream.
 */
public final class UBIDRegistry {

    private final Map<String, Integer> recordIndex = new HashMap<>();
    private final List<BusinessRecord> records;
    private final int[] parent;
    private final int[] rank;

    /** Pairs withheld for human review (probability in [0.70, 0.95)). */
    private final List<ScoredPair> reviewQueue = new ArrayList<>();

    /** UBID assignments: recordId -> UBID. Populated by build(). */
    private final Map<String, String> assignments = new LinkedHashMap<>();

    /** Per-cluster metadata for reporting. */
    private final List<Cluster> clusters = new ArrayList<>();

    public record Cluster(
            String ubid,
            boolean panAnchored,
            List<BusinessRecord> members,
            List<ScoredPair> linkingPairs
    ) {}

    public UBIDRegistry(List<BusinessRecord> records) {
        this.records = records;
        this.parent = new int[records.size()];
        this.rank = new int[records.size()];
        for (int i = 0; i < records.size(); i++) {
            parent[i] = i;
            recordIndex.put(records.get(i).recordId(), i);
        }
    }

    public void build(List<ScoredPair> scoredPairs) {
        // Pre-pass: union exact-PAN-match pairs first regardless of overall score
        // (defensive — the matcher should already auto-link these, but a duplicate
        // PAN with otherwise-noisy fields is still definitionally the same entity).
        for (ScoredPair sp : scoredPairs) {
            String pa = sp.left().scrambledPan(), pb = sp.right().scrambledPan();
            if (pa != null && pa.equals(pb)) {
                union(sp.left().recordId(), sp.right().recordId());
            }
        }

        Map<Integer, List<ScoredPair>> linksByRoot = new HashMap<>();
        for (ScoredPair sp : scoredPairs) {
            switch (sp.decision()) {
                case AUTO_LINK -> {
                    int rootBefore = findIdx(recordIndex.get(sp.left().recordId()));
                    union(sp.left().recordId(), sp.right().recordId());
                    int rootAfter = findIdx(recordIndex.get(sp.left().recordId()));
                    linksByRoot.computeIfAbsent(rootAfter, k -> new ArrayList<>()).add(sp);
                    if (rootBefore != rootAfter) {
                        // root changed — move existing entries
                        List<ScoredPair> moved = linksByRoot.remove(rootBefore);
                        if (moved != null) {
                            linksByRoot.computeIfAbsent(rootAfter, k -> new ArrayList<>()).addAll(moved);
                        }
                    }
                }
                case REVIEW -> reviewQueue.add(sp);
                case NO_LINK -> { /* drop */ }
            }
        }

        // Group records by union-find root and assign UBIDs
        Map<Integer, List<BusinessRecord>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < records.size(); i++) {
            byRoot.computeIfAbsent(findIdx(i), k -> new ArrayList<>()).add(records.get(i));
        }

        for (Map.Entry<Integer, List<BusinessRecord>> e : byRoot.entrySet()) {
            List<BusinessRecord> members = e.getValue();

            // Prefer PAN-anchored UBID when any member has a PAN
            String anchorPan = members.stream()
                    .map(BusinessRecord::scrambledPan)
                    .filter(p -> p != null && !p.isBlank())
                    .sorted()
                    .findFirst().orElse(null);

            String ubid;
            boolean panAnchored;
            if (anchorPan != null) {
                ubid = UBIDGenerator.fromPan(anchorPan);
                panAnchored = true;
            } else {
                String seed = members.stream()
                        .map(BusinessRecord::recordId)
                        .sorted()
                        .reduce((x, y) -> x + "|" + y).orElse("");
                ubid = UBIDGenerator.internal(seed);
                panAnchored = false;
            }

            for (BusinessRecord m : members) assignments.put(m.recordId(), ubid);

            List<ScoredPair> linkPairs = linksByRoot.getOrDefault(e.getKey(), List.of());
            members.sort(Comparator.comparing(BusinessRecord::recordId));
            clusters.add(new Cluster(ubid, panAnchored, members, linkPairs));
        }

        // Stable cluster ordering — biggest first, then by UBID for ties
        clusters.sort(Comparator.<Cluster>comparingInt(c -> -c.members.size())
                .thenComparing(c -> c.ubid));
    }

    public Map<String, String> assignments() { return assignments; }
    public List<Cluster> clusters() { return clusters; }
    public List<ScoredPair> reviewQueue() { return reviewQueue; }

    /* --------------------- union-find primitives --------------------- */

    private int findIdx(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void union(String a, String b) {
        Integer ia = recordIndex.get(a), ib = recordIndex.get(b);
        if (ia == null || ib == null) return;
        int ra = findIdx(ia), rb = findIdx(ib);
        if (ra == rb) return;
        if (rank[ra] < rank[rb]) { int tmp = ra; ra = rb; rb = tmp; }
        parent[rb] = ra;
        if (rank[ra] == rank[rb]) rank[ra]++;
    }
}
