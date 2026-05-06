package com.ubid.pipeline;

import com.ubid.matching.EntityMatcher;
import com.ubid.model.BusinessRecord;
import com.ubid.model.ModelParameters;
import com.ubid.model.ReviewItem;
import com.ubid.storage.DataStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class PipelineOrchestrator implements Runnable {

    // ── Thread-safe status ──────────────────────────────────────────────────

    public static class PipelineStatus {
        public volatile String state     = "IDLE";
        public volatile String step      = "Waiting for batch files in landing/";
        public volatile String batchId   = "";
        public volatile long   lastUpdated = System.currentTimeMillis();
        public volatile int    totalRecords   = 0;
        public volatile int    autoLinked     = 0;
        public volatile int    pendingReview  = 0;
        public volatile int    registrySize   = 0;

        public synchronized String toJson() {
            return String.format(
                "{\"state\":\"%s\",\"step\":\"%s\",\"batchId\":\"%s\"," +
                "\"lastUpdated\":%d,\"stats\":{\"totalRecords\":%d," +
                "\"autoLinked\":%d,\"pendingReview\":%d,\"registrySize\":%d}}",
                state, esc(step), batchId, lastUpdated,
                totalRecords, autoLinked, pendingReview, registrySize
            );
        }

        private String esc(String s) {
            return s == null ? "" : s.replace("\"", "'");
        }

        public synchronized void update(String state, String step) {
            this.state = state;
            this.step  = step;
            this.lastUpdated = System.currentTimeMillis();
        }
    }

    // ── Union-Find for clustering ────────────────────────────────────────────

    private static class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        public String find(String x) {
            parent.putIfAbsent(x, x);
            if (!parent.get(x).equals(x)) parent.put(x, find(parent.get(x)));
            return parent.get(x);
        }

        public void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (!ra.equals(rb)) parent.put(ra, rb);
        }

        public Map<String, List<String>> groups() {
            Map<String, List<String>> g = new HashMap<>();
            for (String k : parent.keySet()) {
                g.computeIfAbsent(find(k), x -> new ArrayList<>()).add(k);
            }
            return g;
        }
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final DataStore      dataStore;
    private final ModelParameters params;
    private final PipelineStatus  status;

    public PipelineOrchestrator(DataStore dataStore, ModelParameters params, PipelineStatus status) {
        this.dataStore = dataStore;
        this.params    = params;
        this.status    = status;
    }

    public PipelineStatus getStatus() { return status; }

    // ── Main loop ────────────────────────────────────────────────────────────

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                switch (status.state) {
                    case "IDLE"     -> checkForBatch();
                    case "DETECTING"-> detectingPhase();
                    case "INGESTING"-> ingestingPhase();
                    default         -> sleep(3000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                status.update("IDLE", "Error: " + e.getMessage() + ". Retrying...");
                safeLog("Pipeline error: " + e.getMessage());
            }
        }
    }

    // ── Phase: IDLE ──────────────────────────────────────────────────────────

    private void checkForBatch() throws InterruptedException, IOException {
        Path landing = dataStore.getLandingDir();
        List<Path> csvFiles = listFiles(landing, "dept_*.csv");
        boolean hasConfirmation = Files.exists(landing.resolve("confirmation.txt"));

        if (csvFiles.size() >= 2 && hasConfirmation) {
            String batchId = "batch-" + System.currentTimeMillis();
            status.batchId = batchId;
            status.update("DETECTING", "Batch detected: " + csvFiles.size() + " department files found.");
        } else {
            status.update("IDLE", String.format(
                "Waiting for batch files. Found %d dept CSV(s)%s.",
                csvFiles.size(), hasConfirmation ? " + confirmation.txt" : " (no confirmation.txt yet)"));
            sleep(3000);
        }
    }

    // ── Phase: DETECTING ────────────────────────────────────────────────────

    private void detectingPhase() throws InterruptedException, IOException {
        status.update("DETECTING", "Validating batch files and preparing ingestion...");
        sleep(1500);
        status.update("INGESTING", "Reading department CSV files...");
        runBatch();
    }

    // ── Phase: INGESTING → full pipeline ────────────────────────────────────

    private void ingestingPhase() throws InterruptedException {
        // Guard: should not land here normally; detectingPhase transitions directly
        sleep(1000);
    }

    private void runBatch() throws InterruptedException, IOException {
        String batchId = status.batchId;

        // ── INGESTING ──
        status.update("INGESTING", "Parsing department CSV files...");
        sleep(500);

        Path landing = dataStore.getLandingDir();
        List<Path> csvFiles = listFiles(landing, "dept_*.csv");

        Map<String, List<BusinessRecord>> deptRecords = new LinkedHashMap<>();
        int total = 0;
        for (Path csv : csvFiles) {
            String dept = deptName(csv.getFileName().toString());
            List<BusinessRecord> records = parseCsv(csv, dept);
            deptRecords.put(dept, records);
            total += records.size();
            status.update("INGESTING",
                String.format("Loaded %d records from %s", records.size(), csv.getFileName()));
            sleep(400);
        }

        status.totalRecords = total;
        status.update("INGESTING",
            String.format("Ingested %d total records from %d departments.", total, deptRecords.size()));
        sleep(1200);

        // ── MATCHING ──
        status.update("MATCHING", "Computing cross-department similarity scores...");
        sleep(500);

        List<String> depts = new ArrayList<>(deptRecords.keySet());
        // Flat map: recordId → BusinessRecord
        Map<String, BusinessRecord> allRecords = new LinkedHashMap<>();
        for (List<BusinessRecord> recs : deptRecords.values()) {
            for (BusinessRecord r : recs) allRecords.put(r.recordId(), r);
        }

        List<double[]>      scored      = new ArrayList<>(); // [scoreIdx] not needed
        List<ScoredPair>    autoLinks   = new ArrayList<>();
        List<ScoredPair>    reviewPairs = new ArrayList<>();

        int pairsChecked = 0;
        for (int di = 0; di < depts.size(); di++) {
            for (int dj = di + 1; dj < depts.size(); dj++) {
                String dA = depts.get(di), dB = depts.get(dj);
                for (BusinessRecord r1 : deptRecords.get(dA)) {
                    for (BusinessRecord r2 : deptRecords.get(dB)) {
                        pairsChecked++;
                        Map<String, Object> result = EntityMatcher.computeScore(r1, r2, params);
                        double score = (Double) result.get("score");
                        @SuppressWarnings("unchecked")
                        Map<String, Double> features = (Map<String, Double>) result.get("features");
                        String evidence = (String) result.get("evidence");

                        if (score >= params.autoLinkThreshold) {
                            autoLinks.add(new ScoredPair(r1, r2, score, features, evidence));
                        } else if (score >= params.reviewThreshold) {
                            reviewPairs.add(new ScoredPair(r1, r2, score, features, evidence));
                        }
                        // < reviewThreshold → singletons, handled after clustering
                    }
                }
            }
        }

        status.update("MATCHING",
            String.format("Checked %d cross-dept pairs. Auto-linking %d (≥%.0f%%). Sending %d to review.",
                pairsChecked, autoLinks.size(),
                params.autoLinkThreshold * 100, reviewPairs.size()));
        sleep(2000);

        // ── Union-Find clustering on auto-links ──
        UnionFind uf = new UnionFind();
        for (String rid : allRecords.keySet()) uf.find(rid); // register all
        for (ScoredPair pair : autoLinks) {
            uf.union(pair.r1.recordId(), pair.r2.recordId());
        }
        Map<String, List<String>> groups = uf.groups();

        // ── WRITING ──
        status.update("WRITING", "Writing auto-linked records to registry...");
        sleep(500);

        // Determine which records are in multi-record groups (auto-linked)
        Set<String> linkedRecordIds = new HashSet<>();
        for (List<String> members : groups.values()) {
            if (members.size() > 1) linkedRecordIds.addAll(members);
        }

        // Determine which records appear in review pairs
        Set<String> reviewRecordIds = new HashSet<>();
        for (ScoredPair pair : reviewPairs) {
            reviewRecordIds.add(pair.r1.recordId());
            reviewRecordIds.add(pair.r2.recordId());
        }

        // Write auto-linked groups
        int autoCount = 0;
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> members = entry.getValue();
            List<BusinessRecord> recs = members.stream()
                .map(allRecords::get).filter(Objects::nonNull).collect(Collectors.toList());
            if (recs.size() < 2) continue; // singletons handled separately

            String ubid = assignUbid(recs);
            String bestEvidence = autoLinks.stream()
                .filter(p -> members.contains(p.r1.recordId()) || members.contains(p.r2.recordId()))
                .max(Comparator.comparingDouble(p -> p.score))
                .map(p -> p.evidence).orElse("");
            writeRegistryEntry(ubid, recs, bestEvidence, true);
            autoCount += recs.size();
        }
        status.autoLinked = autoCount;

        // Write review pairs to queue
        int reviewCount = 0;
        for (ScoredPair pair : reviewPairs) {
            ReviewItem item = new ReviewItem(
                UUID.randomUUID().toString(),
                pair.r1, pair.r2, pair.score, pair.features, pair.evidence,
                Instant.now().toString()
            );
            dataStore.appendReviewQueue(item.toJson());
            reviewCount++;
        }
        status.pendingReview = reviewCount;

        // Write singletons (not in any link or review)
        int singletonCount = 0;
        for (BusinessRecord rec : allRecords.values()) {
            String rid = rec.recordId();
            boolean inLinked = linkedRecordIds.contains(rid);
            if (!inLinked) {
                // Could be in review pairs — still gets its own UBID in registry for now
                String ubid = assignUbid(List.of(rec));
                writeRegistryEntry(ubid, List.of(rec), "No matching records found", false);
                singletonCount++;
            }
        }

        status.registrySize = dataStore.getRegistryCount();
        status.update("WRITING",
            String.format("Wrote %d auto-linked, %d review items, %d singletons to registry.",
                autoCount, reviewCount, singletonCount));
        sleep(1500);

        // ── Save model ──
        dataStore.saveModelParams(params);

        // ── COMPLETE ──
        status.update("COMPLETE",
            String.format("Batch %s complete. Registry: %d entries, %d awaiting review.",
                batchId, status.registrySize, reviewCount));
        sleep(2000);

        dataStore.archiveLandingFiles(batchId);
        sleep(8000);

        // Reset counters and return to IDLE
        status.totalRecords  = 0;
        status.autoLinked    = 0;
        status.pendingReview = dataStore.getReviewQueueCount();
        status.registrySize  = dataStore.getRegistryCount();
        status.update("IDLE", "Batch complete. Waiting for next batch...");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String assignUbid(List<BusinessRecord> recs) {
        for (BusinessRecord r : recs) {
            if (r.pan() != null && !r.pan().isBlank()) {
                return UUID.nameUUIDFromBytes(
                    ("UBID:" + r.pan().trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)
                ).toString();
            }
        }
        for (BusinessRecord r : recs) {
            if (r.gstin() != null && !r.gstin().isBlank()) {
                return UUID.nameUUIDFromBytes(
                    ("UBID:" + r.gstin().trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)
                ).toString();
            }
        }
        return UUID.randomUUID().toString();
    }

    private void writeRegistryEntry(String ubid, List<BusinessRecord> recs,
                                     String evidence, boolean autoLinked) throws IOException {
        List<String> sources = recs.stream().map(BusinessRecord::source).distinct().collect(Collectors.toList());
        String sourcesJson   = "[" + sources.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
        String recordsJson   = "[" + recs.stream().map(BusinessRecord::toJson).collect(Collectors.joining(",")) + "]";
        String name          = recs.stream().map(BusinessRecord::businessName).filter(s -> s != null && !s.isBlank())
                                    .findFirst().orElse("Unknown");

        String entry = String.format(
            "{\"ubid\":\"%s\",\"businessName\":\"%s\",\"sources\":%s,\"records\":%s," +
            "\"evidence\":\"%s\",\"linkedAt\":\"%s\",\"autoLinked\":%b}",
            ubid, esc(name), sourcesJson, recordsJson, esc(evidence), Instant.now(), autoLinked
        );
        dataStore.appendRegistry(entry);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String deptName(String filename) {
        // dept_shop.csv → shop
        return filename.replaceAll("^dept_", "").replaceAll("\\.csv$", "");
    }

    private List<Path> listFiles(Path dir, String glob) throws IOException {
        if (!Files.exists(dir)) return List.of();
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            ds.forEach(result::add);
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private List<BusinessRecord> parseCsv(Path csvFile, String dept) throws IOException {
        List<String> lines = Files.readAllLines(csvFile, StandardCharsets.UTF_8);
        List<BusinessRecord> records = new ArrayList<>();
        if (lines.isEmpty()) return records;

        // Skip header
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] fields = parseCsvLine(line);
            if (fields.length < 10) {
                // Pad with empty strings
                fields = Arrays.copyOf(fields, 10);
                for (int j = 0; j < 10; j++) if (fields[j] == null) fields[j] = "";
            }
            records.add(new BusinessRecord(
                fields[0].trim(),  // recordId
                dept,
                fields[1].trim(),  // businessName
                fields[2].trim(),  // pan
                fields[3].trim(),  // gstin
                fields[4].trim(),  // address
                fields[5].trim(),  // pinCode
                fields[6].trim(),  // sector
                fields[7].trim(),  // phone
                fields[9].trim()   // registrationDate
            ));
        }
        return records;
    }

    /** Parse a single CSV line handling double-quoted fields with commas. */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void safeLog(String msg) {
        System.err.println("[Pipeline] " + msg);
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    private static class ScoredPair {
        final BusinessRecord      r1, r2;
        final double              score;
        final Map<String, Double> features;
        final String              evidence;

        ScoredPair(BusinessRecord r1, BusinessRecord r2, double score,
                   Map<String, Double> features, String evidence) {
            this.r1 = r1; this.r2 = r2;
            this.score = score; this.features = features; this.evidence = evidence;
        }
    }
}
