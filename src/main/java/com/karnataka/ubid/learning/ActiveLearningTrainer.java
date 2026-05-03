package com.karnataka.ubid.learning;

import com.karnataka.ubid.model.ReviewerDecision;
import com.karnataka.ubid.model.ScoredPair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closes the active-learning loop. Reviewer decisions become labelled
 * training pairs; (m, u) per tier are re-estimated and rolled back into
 * the model.
 *
 * Update rule (per tier T, per parameter):
 *
 *   m_T_new = (alpha_m + match_hits_T)     / (alpha_m + beta_m + total_matches)
 *   u_T_new = (alpha_u + nonmatch_hits_T)  / (alpha_u + beta_u + total_nonmatches)
 *
 * where alpha and beta are pseudo-counts derived from the current (prior)
 * (m_T, u_T) and a strength parameter:
 *
 *   alpha_m = priorStrength * m_T_current     beta_m = priorStrength * (1 - m_T_current)
 *   alpha_u = priorStrength * u_T_current     beta_u = priorStrength * (1 - u_T_current)
 *
 * This is conjugate-Beta updating with a Beta prior. With a small labelled
 * set the prior dominates (good — protects against overfit); as the
 * labelled set grows the empirical observations take over.
 */
public final class ActiveLearningTrainer {

    /** Strength of the Beta prior — equivalent to "we trust the current (m, u)
     *  as if it were estimated from this many synthetic prior observations". */
    private final double priorStrength;

    public ActiveLearningTrainer() { this(40.0); }

    public ActiveLearningTrainer(double priorStrength) { this.priorStrength = priorStrength; }

    /**
     * @param current   the prior parameters (the model state before retraining)
     * @param scored    every previously scored pair (so we can find tier hits per labelled decision)
     * @param decisions reviewer decisions — only CONFIRMED_MATCH and CONFIRMED_NON_MATCH contribute
     * @return  updated parameters
     */
    public Result retrain(MUParams current, List<ScoredPair> scored, List<ReviewerDecision> decisions) {
        // Build pair index for fast lookup
        Map<String, ScoredPair> pairIndex = new HashMap<>();
        for (ScoredPair sp : scored) pairIndex.put(pairKey(sp.left().recordId(), sp.right().recordId()), sp);

        Set<String> matchPairs = new HashSet<>();
        Set<String> nonMatchPairs = new HashSet<>();
        for (ReviewerDecision d : decisions) {
            String key = pairKey(d.leftRecordId(), d.rightRecordId());
            switch (d.verdict()) {
                case CONFIRMED_MATCH     -> matchPairs.add(key);
                case CONFIRMED_NON_MATCH -> nonMatchPairs.add(key);
                case ESCALATED           -> { /* skip */ }
            }
        }

        // Tally tier hits separately under each label
        Map<String, Integer> matchHits = new HashMap<>();
        Map<String, Integer> nonMatchHits = new HashMap<>();
        int totalMatches = 0, totalNonMatches = 0;

        for (String key : matchPairs) {
            ScoredPair sp = pairIndex.get(key);
            if (sp == null) continue;
            totalMatches++;
            for (var fc : sp.evidence().values()) {
                if (fc.tierKey() != null && !"pan.missing".equals(fc.tierKey())) {
                    matchHits.merge(fc.tierKey(), 1, Integer::sum);
                }
            }
        }
        for (String key : nonMatchPairs) {
            ScoredPair sp = pairIndex.get(key);
            if (sp == null) continue;
            totalNonMatches++;
            for (var fc : sp.evidence().values()) {
                if (fc.tierKey() != null && !"pan.missing".equals(fc.tierKey())) {
                    nonMatchHits.merge(fc.tierKey(), 1, Integer::sum);
                }
            }
        }

        MUParams updated = current.copy();
        Map<String, Adjustment> diffs = new LinkedHashMap<>();

        for (Map.Entry<String, MUParams.MU> e : current.snapshot().entrySet()) {
            String tier = e.getKey();
            MUParams.MU prior = e.getValue();

            int mh = matchHits.getOrDefault(tier, 0);
            int nh = nonMatchHits.getOrDefault(tier, 0);

            // Bayesian Beta update
            double alphaM = priorStrength * prior.m();
            double betaM  = priorStrength * (1 - prior.m());
            double alphaU = priorStrength * prior.u();
            double betaU  = priorStrength * (1 - prior.u());

            double newM = totalMatches == 0
                    ? prior.m()
                    : (alphaM + mh) / (alphaM + betaM + totalMatches);
            double newU = totalNonMatches == 0
                    ? prior.u()
                    : (alphaU + nh) / (alphaU + betaU + totalNonMatches);

            // Floor very small values to avoid log(0) / numerical instability
            newM = Math.max(newM, 1e-4);
            newU = Math.max(newU, 1e-4);

            updated.put(tier, newM, newU);

            if (mh > 0 || nh > 0) {
                diffs.put(tier, new Adjustment(prior.m(), prior.u(), newM, newU, mh, nh));
            }
        }

        return new Result(updated, diffs, totalMatches, totalNonMatches);
    }

    public record Adjustment(
            double oldM, double oldU,
            double newM, double newU,
            int matchHits, int nonMatchHits
    ) {
        public double oldLogOdds() { return Math.log(oldM / oldU); }
        public double newLogOdds() { return Math.log(newM / newU); }
        public double delta() { return newLogOdds() - oldLogOdds(); }
    }

    public record Result(
            MUParams updatedParams,
            Map<String, Adjustment> adjustments,
            int totalMatches,
            int totalNonMatches
    ) {}

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }
}
