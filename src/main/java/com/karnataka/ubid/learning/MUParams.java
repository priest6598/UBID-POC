package com.karnataka.ubid.learning;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-tier (m, u) probability table for the Fellegi-Sunter matcher.
 *
 *   m = P(observation falls into this tier | the pair is a true match)
 *   u = P(observation falls into this tier | the pair is a true non-match)
 *
 * The matcher converts (m, u) to a log-odds contribution per tier hit
 * via log(m/u). The trainer ({@link ActiveLearningTrainer}) updates m and
 * u in place using Bayesian smoothing — current values become the prior,
 * reviewer-labelled tier hits become observations.
 *
 * Tier keys mirror the tiers used in {@link com.karnataka.ubid.matching.ProbabilisticMatcher}.
 */
public final class MUParams {

    public record MU(double m, double u) {
        public double logOdds() { return Math.log(m / u); }
    }

    private final Map<String, MU> table;

    private MUParams(Map<String, MU> t) { this.table = new LinkedHashMap<>(t); }

    public MU get(String tierKey) {
        MU mu = table.get(tierKey);
        if (mu == null) throw new IllegalArgumentException("Unknown tier key: " + tierKey);
        return mu;
    }

    public void put(String tierKey, double m, double u) { table.put(tierKey, new MU(m, u)); }

    public Map<String, MU> snapshot() { return new LinkedHashMap<>(table); }

    public MUParams copy() { return new MUParams(table); }

    /** Production-baseline values approximating what Splink's EM would estimate
     *  on Karnataka-shaped data before any labelled feedback exists. */
    public static MUParams defaults() {
        Map<String, MU> t = new LinkedHashMap<>();

        // PAN — strongest identifier
        t.put("pan.match",        new MU(0.99,  0.0001));
        t.put("pan.disagree",     new MU(0.005, 0.99));
        // GSTIN
        t.put("gstin.match",      new MU(0.99,  0.0001));
        t.put("gstin.disagree",   new MU(0.005, 0.99));

        // Name (Jaro-Winkler tiers — partition the JW range)
        t.put("name.near",        new MU(0.55,  0.005));   // jw ≥ 0.95
        t.put("name.high",        new MU(0.30,  0.020));   // 0.85 ≤ jw < 0.95
        t.put("name.moderate",    new MU(0.10,  0.075));   // 0.70 ≤ jw < 0.85
        t.put("name.low",         new MU(0.05,  0.900));   // jw < 0.70

        // Phonetic
        t.put("name_phonetic.match",     new MU(0.92, 0.10));
        t.put("name_phonetic.no_match",  new MU(0.08, 0.90));

        // Pin
        t.put("pin.agree",        new MU(0.95, 0.02));
        t.put("pin.disagree",     new MU(0.05, 0.98));

        // Address (3-gram Jaccard) — u for "low" is ≈0.5 because pairs blocked
        // by pin code or shared name token still often share *some* address
        // 3-grams (street suffixes, "Bangalore", etc.), so half of non-match
        // candidate pairs end up here rather than in the high/moderate tiers.
        t.put("address.high",     new MU(0.70, 0.04));
        t.put("address.moderate", new MU(0.20, 0.10));
        t.put("address.low",      new MU(0.10, 0.50));

        // Sector
        t.put("sector.agree",     new MU(0.85, 0.15));
        t.put("sector.disagree",  new MU(0.15, 0.85));

        // Registration date
        t.put("reg_date.close",     new MU(0.70, 0.10));
        t.put("reg_date.moderate",  new MU(0.20, 0.20));
        t.put("reg_date.far",       new MU(0.10, 0.70));

        return new MUParams(t);
    }

    /** Prior log-odds — most pairs are non-matches even after blocking. */
    public static double priorLogOdds() { return Math.log(0.05 / 0.95); }
}
