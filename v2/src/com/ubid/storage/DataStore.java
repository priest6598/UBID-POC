package com.ubid.storage;

import com.ubid.model.ModelParameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DataStore {

    private final Path base;
    private final Path registryFile;
    private final Path reviewQueueFile;
    private final Path modelFile;
    private final Path landingDir;
    private final Path archiveDir;

    public DataStore(Path base) {
        this.base           = base;
        this.registryFile   = base.resolve("data/registry.jsonl");
        this.reviewQueueFile = base.resolve("data/review_queue.jsonl");
        this.modelFile      = base.resolve("data/model.properties");
        this.landingDir     = base.resolve("landing");
        this.archiveDir     = base.resolve("data/archive");
    }

    public void ensureDirectories() throws IOException {
        Files.createDirectories(base.resolve("data"));
        Files.createDirectories(landingDir);
        Files.createDirectories(archiveDir);
        Files.createDirectories(base.resolve("web"));
    }

    public synchronized void appendRegistry(String jsonLine) throws IOException {
        Files.writeString(registryFile, jsonLine + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized void appendReviewQueue(String jsonLine) throws IOException {
        Files.writeString(reviewQueueFile, jsonLine + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<String> loadAllReviewItems() throws IOException {
        if (!Files.exists(reviewQueueFile)) return new ArrayList<>();
        return Files.readAllLines(reviewQueueFile, StandardCharsets.UTF_8)
                    .stream()
                    .filter(l -> !l.isBlank())
                    .collect(Collectors.toList());
    }

    public synchronized void updateReviewItem(String id, String decision, String note)
            throws IOException {
        List<String> lines = loadAllReviewItems();
        List<String> updated = new ArrayList<>();
        for (String line : lines) {
            if (line.contains("\"id\":\"" + id + "\"")) {
                line = line
                    .replaceAll("\"status\":\"[^\"]*\"", "\"status\":\"REVIEWED\"")
                    .replaceAll("\"decision\":(null|\"[^\"]*\")",
                                "\"decision\":\"" + decision + "\"")
                    .replaceAll("\"reviewNote\":(null|\"[^\"]*\")",
                                "\"reviewNote\":\"" + escJson(note) + "\"");
            }
            updated.add(line);
        }
        Path tmp = reviewQueueFile.resolveSibling("review_queue.tmp");
        Files.writeString(tmp, String.join("\n", updated) + (updated.isEmpty() ? "" : "\n"),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, reviewQueueFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public int getRegistryCount() throws IOException {
        if (!Files.exists(registryFile)) return 0;
        return (int) Files.lines(registryFile).filter(l -> !l.isBlank()).count();
    }

    public int getReviewQueueCount() throws IOException {
        List<String> items = loadAllReviewItems();
        return (int) items.stream().filter(l -> l.contains("\"status\":\"PENDING\"")).count();
    }

    public void archiveLandingFiles(String batchId) throws IOException {
        Path dest = archiveDir.resolve(batchId);
        Files.createDirectories(dest);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(landingDir)) {
            for (Path file : stream) {
                Files.move(file, dest.resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public Path getLandingDir()     { return landingDir; }
    public Path getRegistryFile()   { return registryFile; }
    public Path getModelFile()      { return modelFile; }

    public void saveModelParams(ModelParameters params) throws IOException {
        params.save(modelFile);
    }

    public ModelParameters loadModelParams() throws IOException {
        ModelParameters params = new ModelParameters();
        params.load(modelFile);
        return params;
    }

    /** Extract a JSON string value by key from a raw JSON line. */
    public static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        }
        if (json.startsWith("null", start)) return null;
        return null;
    }

    /** Extract a JSON number value by key from a raw JSON line. */
    public static double extractJsonDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return 0.0;
        int start = idx + search.length();
        int end = start;
        while (end < json.length() && "0123456789.-".indexOf(json.charAt(end)) >= 0) end++;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Extract the features map from a review item JSON line.
     * Expected format: "features":{"name":0.8500,"identifier":0.0000,...}
     */
    public static java.util.Map<String, Double> extractFeatures(String json) {
        java.util.Map<String, Double> map = new java.util.LinkedHashMap<>();
        int idx = json.indexOf("\"features\":{");
        if (idx < 0) return map;
        int start = json.indexOf('{', idx + 10);
        int end = json.indexOf('}', start);
        if (start < 0 || end < 0) return map;
        String inner = json.substring(start + 1, end);
        for (String part : inner.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) {
                String k = kv[0].trim().replace("\"", "");
                try {
                    map.put(k, Double.parseDouble(kv[1].trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
