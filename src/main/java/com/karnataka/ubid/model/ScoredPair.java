package com.karnataka.ubid.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A candidate pair produced by blocking, scored by the probabilistic matcher.
 * Carries the evidence trail (per-feature contributions to the log-odds) so
 * that every match decision is fully explainable to a reviewer.
 */
public record ScoredPair(
        BusinessRecord left,
        BusinessRecord right,
        double matchProbability,
        Decision decision,
        Map<String, FeatureContribution> evidence
) {
    public enum Decision {
        AUTO_LINK,    // probability >= 0.95
        REVIEW,       // 0.70 <= probability < 0.95
        NO_LINK       // probability < 0.70
    }

    public record FeatureContribution(
            String featureName,
            String observation,
            double logOddsContribution
    ) {}

    public static ScoredPair build(BusinessRecord left, BusinessRecord right,
                                    double prob, Map<String, FeatureContribution> ev) {
        Decision d;
        if (prob >= 0.95) d = Decision.AUTO_LINK;
        else if (prob >= 0.70) d = Decision.REVIEW;
        else d = Decision.NO_LINK;
        return new ScoredPair(left, right, prob, d, new LinkedHashMap<>(ev));
    }
}
