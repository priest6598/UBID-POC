package com.ubid.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class ModelParameters {

    private Map<String, Double> weights;
    public double autoLinkThreshold;
    public double reviewThreshold;
    public int feedbackCount;

    public ModelParameters() {
        weights = new LinkedHashMap<>();
        weights.put("pan",     0.35);
        weights.put("name",    0.30);
        weights.put("address", 0.15);
        weights.put("pinCode", 0.10);
        weights.put("sector",  0.05);
        weights.put("phone",   0.05);
        autoLinkThreshold = 0.85;
        reviewThreshold   = 0.50;
        feedbackCount     = 0;
    }

    public Map<String, Double> getWeights() {
        return weights;
    }

    public void load(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Properties props = new Properties();
        props.load(Files.newInputStream(path));
        for (String key : weights.keySet()) {
            String val = props.getProperty("weight." + key);
            if (val != null) weights.put(key, Double.parseDouble(val));
        }
        if (props.containsKey("autoLinkThreshold"))
            autoLinkThreshold = Double.parseDouble(props.getProperty("autoLinkThreshold"));
        if (props.containsKey("reviewThreshold"))
            reviewThreshold = Double.parseDouble(props.getProperty("reviewThreshold"));
        if (props.containsKey("feedbackCount"))
            feedbackCount = Integer.parseInt(props.getProperty("feedbackCount"));
    }

    public void save(Path path) throws IOException {
        Properties props = new Properties();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            props.setProperty("weight." + e.getKey(), String.valueOf(e.getValue()));
        }
        props.setProperty("autoLinkThreshold", String.valueOf(autoLinkThreshold));
        props.setProperty("reviewThreshold",   String.valueOf(reviewThreshold));
        props.setProperty("feedbackCount",     String.valueOf(feedbackCount));
        props.store(Files.newOutputStream(path), "UBID Model Parameters");
    }

    /**
     * Simple gradient update: push weights toward features that correctly
     * distinguished matches vs non-matches.
     */
    public synchronized void updateFromFeedback(Map<String, Double> featureScores, boolean isMatch) {
        double learningRate = 0.02;

        for (String feature : weights.keySet()) {
            Double featureScore = featureScores.get(feature);
            if (featureScore == null) continue;

            // For a match: increase weight of high-scoring features
            // For a non-match: increase weight of low-scoring features (they correctly signaled difference)
            double agreement = isMatch ? featureScore : (1.0 - featureScore);
            double delta = learningRate * (agreement - 0.5); // pushes toward 0 if neutral

            double newWeight = weights.get(feature) + delta;
            newWeight = Math.max(0.01, Math.min(0.60, newWeight));
            weights.put(feature, newWeight);
        }

        renormalize();
        feedbackCount++;
    }

    private void renormalize() {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) return;
        weights.replaceAll((k, v) -> v / sum);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"weights\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(String.format("%.4f", e.getValue()));
            first = false;
        }
        sb.append("},\"autoLinkThreshold\":").append(autoLinkThreshold)
          .append(",\"reviewThreshold\":").append(reviewThreshold)
          .append(",\"feedbackCount\":").append(feedbackCount)
          .append("}");
        return sb.toString();
    }
}
