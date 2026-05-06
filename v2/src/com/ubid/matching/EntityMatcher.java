package com.ubid.matching;

import com.ubid.model.BusinessRecord;
import com.ubid.model.ModelParameters;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class EntityMatcher {

    private EntityMatcher() {}

    public static Map<String, Object> computeScore(
            BusinessRecord r1, BusinessRecord r2, ModelParameters params) {

        Map<String, Double> features = new LinkedHashMap<>();

        double panScore   = exactMatchScore(r1.pan(),   r2.pan());
        double gstinScore = exactMatchScore(r1.gstin(), r2.gstin());

        // Expose the stronger identifier signal
        features.put("identifier", Math.max(panScore, gstinScore));

        double nameScore = SimilarityUtils.jaroWinkler(
            SimilarityUtils.normalizeBusinessName(r1.businessName()),
            SimilarityUtils.normalizeBusinessName(r2.businessName())
        );
        features.put("name", nameScore);

        double addressScore = SimilarityUtils.tokenJaccard(
            r1.address().toLowerCase(),
            r2.address().toLowerCase()
        );
        features.put("address", addressScore);

        features.put("pinCode", exactMatchScore(r1.pinCode(), r2.pinCode()));
        features.put("sector",  exactMatchScore(r1.sector(),  r2.sector()));
        features.put("phone",   phoneMatchScore(r1.phone(),   r2.phone()));

        Map<String, Double> weights = params.getWeights();
        // Map our features to model weights; "identifier" uses pan+gstin combined weight
        double identifierWeight = weights.getOrDefault("pan", 0.35);
        double weightedScore =
            identifierWeight                            * features.get("identifier") +
            weights.getOrDefault("name",    0.30)      * features.get("name") +
            weights.getOrDefault("address", 0.15)      * features.get("address") +
            weights.getOrDefault("pinCode", 0.10)      * features.get("pinCode") +
            weights.getOrDefault("sector",  0.05)      * features.get("sector") +
            weights.getOrDefault("phone",   0.05)      * features.get("phone");

        // PAN or GSTIN exact match is a very strong signal
        double finalScore = (panScore == 1.0 || gstinScore == 1.0)
            ? Math.max(0.92, weightedScore)
            : weightedScore;

        String evidence = buildEvidence(features, panScore, gstinScore);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score",    finalScore);
        result.put("features", features);
        result.put("evidence", evidence);
        return result;
    }

    private static double exactMatchScore(String a, String b) {
        if (a == null || b == null) return 0.0;
        a = a.trim(); b = b.trim();
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        return a.equalsIgnoreCase(b) ? 1.0 : 0.0;
    }

    private static double phoneMatchScore(String a, String b) {
        if (a == null || b == null) return 0.0;
        a = a.trim(); b = b.trim();
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        return SimilarityUtils.levenshtein(a, b) <= 1 ? 1.0 : 0.0;
    }

    private static String buildEvidence(
            Map<String, Double> features, double panScore, double gstinScore) {
        StringJoiner sj = new StringJoiner(", ");
        sj.add(String.format("Name: %.2f", features.get("name")));
        if (panScore == 1.0)   sj.add("PAN: match");
        if (gstinScore == 1.0) sj.add("GSTIN: match");
        if (features.get("identifier") > 0 && panScore < 1.0 && gstinScore < 1.0)
            sj.add(String.format("Identifier: %.2f", features.get("identifier")));
        sj.add(String.format("Address: %.2f", features.get("address")));
        if (features.get("pinCode") == 1.0) sj.add("PinCode: match");
        if (features.get("sector")  == 1.0) sj.add("Sector: match");
        if (features.get("phone")   == 1.0) sj.add("Phone: match");
        return sj.toString();
    }
}
