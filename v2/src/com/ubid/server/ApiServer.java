package com.ubid.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ubid.model.ModelParameters;
import com.ubid.model.ReviewItem;
import com.ubid.pipeline.PipelineOrchestrator.PipelineStatus;
import com.ubid.storage.DataStore;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ApiServer {

    private final HttpServer       server;
    private final DataStore        dataStore;
    private final ModelParameters  params;
    private final PipelineStatus   pipelineStatus;
    private final Path             webDir;

    public ApiServer(int port, DataStore dataStore, ModelParameters params,
                     PipelineStatus pipelineStatus, Path webDir) throws IOException {
        this.dataStore      = dataStore;
        this.params         = params;
        this.pipelineStatus = pipelineStatus;
        this.webDir         = webDir;

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/api/status",   this::handleStatus);
        server.createContext("/api/queue",    this::handleQueue);
        server.createContext("/api/registry", this::handleRegistry);
        server.createContext("/api/model",    this::handleModel);
        server.createContext("/api/review",   this::handleReview);
        server.createContext("/reviewer",     this::handleReviewerPage);
        server.createContext("/web/",         this::handleStaticFile);
        server.createContext("/",             this::handleIndex);
    }

    public void start() {
        server.start();
    }

    // ── Route handlers ───────────────────────────────────────────────────────

    private void handleIndex(HttpExchange ex) throws IOException {
        serveFile(ex, webDir.resolve("index.html"), "text/html");
    }

    private void handleReviewerPage(HttpExchange ex) throws IOException {
        serveFile(ex, webDir.resolve("reviewer.html"), "text/html");
    }

    private void handleStaticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(); // /web/something
        String relative = path.startsWith("/web/") ? path.substring(5) : path;
        Path file = webDir.resolve(relative);
        serveFile(ex, file, detectContentType(path));
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        respondJson(ex, pipelineStatus.toJson());
    }

    private void handleQueue(HttpExchange ex) throws IOException {
        List<String> lines = dataStore.loadAllReviewItems();
        String json = "[" + String.join(",", lines) + "]";
        respondJson(ex, json);
    }

    private void handleRegistry(HttpExchange ex) throws IOException {
        Path reg = dataStore.getRegistryFile();
        if (!Files.exists(reg)) {
            respondJson(ex, "[]");
            return;
        }
        List<String> lines = Files.readAllLines(reg, StandardCharsets.UTF_8)
            .stream().filter(l -> !l.isBlank()).collect(Collectors.toList());
        int from = Math.max(0, lines.size() - 50);
        String json = "[" + String.join(",", lines.subList(from, lines.size())) + "]";
        respondJson(ex, json);
    }

    private void handleModel(HttpExchange ex) throws IOException {
        respondJson(ex, params.toJson());
    }

    private void handleReview(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json", "{\"error\":\"Method not allowed\"}");
            return;
        }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String id       = extractStrField(body, "id");
        String decision = extractStrField(body, "decision");
        String note     = extractStrField(body, "note");

        if (id == null || decision == null) {
            respond(ex, 400, "application/json", "{\"error\":\"Missing id or decision\"}");
            return;
        }

        // Load all review items, find the matching one for feedback
        List<String> items = dataStore.loadAllReviewItems();
        String matchedLine = null;
        for (String line : items) {
            String lineId = DataStore.extractJsonString(line, "id");
            if (id.equals(lineId)) { matchedLine = line; break; }
        }

        if (matchedLine != null) {
            Map<String, Double> features = DataStore.extractFeatures(matchedLine);
            boolean isMatch = "MATCH".equalsIgnoreCase(decision);

            // Update model weights from feedback
            synchronized (params) {
                params.updateFromFeedback(features, isMatch);
            }
            try { dataStore.saveModelParams(params); } catch (IOException ignored) {}

            // Write result to registry
            if (isMatch) {
                writeMatchedPairToRegistry(matchedLine);
            } else {
                writeUnmatchedPairToRegistry(matchedLine);
            }
        }

        // Update review item status
        dataStore.updateReviewItem(id, decision.toUpperCase(),
            note == null ? "" : note);

        // Update live pending count
        pipelineStatus.pendingReview = dataStore.getReviewQueueCount();
        pipelineStatus.registrySize  = dataStore.getRegistryCount();

        respondJson(ex, "{\"status\":\"ok\"}");
    }

    // ── Registry write helpers ───────────────────────────────────────────────

    private void writeMatchedPairToRegistry(String reviewLine) throws IOException {
        String r1Json = extractObjectField(reviewLine, "record1");
        String r2Json = extractObjectField(reviewLine, "record2");
        double score  = DataStore.extractJsonDouble(reviewLine, "score");
        String ev     = DataStore.extractJsonString(reviewLine, "evidence");

        String pan1   = DataStore.extractJsonString(r1Json == null ? "" : r1Json, "pan");
        String pan2   = DataStore.extractJsonString(r2Json == null ? "" : r2Json, "pan");
        String pan    = (pan1 != null && !pan1.isBlank()) ? pan1 : (pan2 != null ? pan2 : "");
        String ubid   = pan.isBlank()
            ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes(("UBID:" + pan.trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)).toString();

        String name1  = DataStore.extractJsonString(r1Json == null ? "" : r1Json, "businessName");
        String src1   = DataStore.extractJsonString(r1Json == null ? "" : r1Json, "source");
        String src2   = DataStore.extractJsonString(r2Json == null ? "" : r2Json, "source");

        String entry = String.format(
            "{\"ubid\":\"%s\",\"businessName\":\"%s\",\"sources\":[\"%s\",\"%s\"]," +
            "\"records\":[%s,%s],\"evidence\":\"%s\",\"linkedAt\":\"%s\"," +
            "\"autoLinked\":false,\"humanVerified\":true,\"score\":%.4f}",
            ubid, esc(name1 == null ? "" : name1), esc(src1), esc(src2),
            r1Json, r2Json, esc(ev == null ? "" : ev), Instant.now(), score
        );
        dataStore.appendRegistry(entry);
    }

    private void writeUnmatchedPairToRegistry(String reviewLine) throws IOException {
        // Each record gets its own UBID
        for (String field : new String[]{"record1", "record2"}) {
            String rJson = extractObjectField(reviewLine, field);
            if (rJson == null) continue;
            String pan  = DataStore.extractJsonString(rJson, "pan");
            String ubid = (pan != null && !pan.isBlank())
                ? UUID.nameUUIDFromBytes(("UBID:" + pan.trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)).toString()
                : UUID.randomUUID().toString();
            String name = DataStore.extractJsonString(rJson, "businessName");
            String src  = DataStore.extractJsonString(rJson, "source");
            String entry = String.format(
                "{\"ubid\":\"%s\",\"businessName\":\"%s\",\"sources\":[\"%s\"]," +
                "\"records\":[%s],\"evidence\":\"Human reviewer: not same business\"," +
                "\"linkedAt\":\"%s\",\"autoLinked\":false,\"humanVerified\":true}",
                ubid, esc(name == null ? "" : name), esc(src == null ? "" : src), rJson, Instant.now()
            );
            dataStore.appendRegistry(entry);
        }
    }

    // ── Static file serving ──────────────────────────────────────────────────

    private void serveFile(HttpExchange ex, Path file, String contentType) throws IOException {
        if (!Files.exists(file)) {
            respond(ex, 404, "text/plain", "Not found: " + file.getFileName());
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        addCorsHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private String detectContentType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ── Response helpers ─────────────────────────────────────────────────────

    private void respondJson(HttpExchange ex, String json) throws IOException {
        respond(ex, 200, "application/json", json);
    }

    private void respond(HttpExchange ex, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    // ── Simple JSON field extractors ─────────────────────────────────────────

    /** Extract a top-level string field from flat JSON. */
    private String extractStrField(String json, String key) {
        return DataStore.extractJsonString(json, key);
    }

    /**
     * Extract a nested JSON object value by key.
     * Finds the first '{' after "key": and matches braces.
     */
    private String extractObjectField(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = json.indexOf('{', idx + search.length());
        if (start < 0) return null;
        int depth = 0, i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return json.substring(start, i + 1); }
            else if (c == '"') { i++; while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++; i++; } }
            i++;
        }
        return null;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
