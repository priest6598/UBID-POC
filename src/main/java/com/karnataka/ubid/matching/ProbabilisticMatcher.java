package com.karnataka.ubid.matching;

import com.karnataka.ubid.model.BusinessRecord;
import com.karnataka.ubid.model.ScoredPair;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fellegi-Sunter style probabilistic matcher.
 *
 * Each feature has m and u probabilities (likelihood of agreement under
 * match / non-match). Per-pair we sum log(m/u) for agreed features and
 * log((1-m)/(1-u)) for disagreed features. The total log-odds is converted
 * to a posterior match probability via the logistic function.
 *
 * The m/u values here are hand-tuned plausible defaults that approximate
 * what Splink's EM step would estimate on Karnataka-shaped data. In a
 * production deployment these would be re-estimated weekly from reviewer
 * decisions (active-learning loop, see proposal section 4.2).
 */
public final class ProbabilisticMatcher {

    // Prior log-odds — most pairs are non-matches even after blocking
    private static final double PRIOR_LOG_ODDS = Math.log(0.05 / 0.95);

    public List<ScoredPair> score(List<BlockingEngine.CandidatePair> candidates) {
        List<ScoredPair> out = new ArrayList<>(candidates.size());
        for (BlockingEngine.CandidatePair c : candidates) {
            out.add(scorePair(c.left(), c.right()));
        }
        return out;
    }

    public ScoredPair scorePair(BusinessRecord a, BusinessRecord b) {
        Map<String, ScoredPair.FeatureContribution> evidence = new LinkedHashMap<>();
        double logOdds = PRIOR_LOG_ODDS;

        // -------------------- PAN (strongest signal) -------------------- //
        if (a.scrambledPan() != null && b.scrambledPan() != null) {
            if (a.scrambledPan().equals(b.scrambledPan())) {
                logOdds += contrib(evidence, "pan", "exact match", 0.99, 0.0001);
            } else {
                logOdds += contribDisagree(evidence, "pan", "different values", 0.99, 0.0001);
            }
        } else {
            evidence.put("pan", new ScoredPair.FeatureContribution(
                    "pan", "not available in one or both systems", 0.0));
        }

        // -------------------- GSTIN -------------------- //
        if (a.scrambledGstin() != null && b.scrambledGstin() != null) {
            if (a.scrambledGstin().equals(b.scrambledGstin())) {
                logOdds += contrib(evidence, "gstin", "exact match", 0.99, 0.0001);
            } else {
                logOdds += contribDisagree(evidence, "gstin", "different values", 0.99, 0.0001);
            }
        }

        // -------------------- Name (Jaro-Winkler tiers) -------------------- //
        double nameJw = StringSimilarity.jaroWinkler(
                norm(a.scrambledName()), norm(b.scrambledName()));
        if (nameJw >= 0.95) {
            logOdds += contrib(evidence, "name", "near-identical (jw=" + fmt(nameJw) + ")", 0.85, 0.02);
        } else if (nameJw >= 0.85) {
            logOdds += contrib(evidence, "name", "high similarity (jw=" + fmt(nameJw) + ")", 0.65, 0.05);
        } else if (nameJw >= 0.70) {
            logOdds += contrib(evidence, "name", "moderate similarity (jw=" + fmt(nameJw) + ")", 0.30, 0.15);
        } else {
            logOdds += contribDisagree(evidence, "name", "low similarity (jw=" + fmt(nameJw) + ")", 0.85, 0.02);
        }

        // -------------------- Phonetic name (Metaphone) -------------------- //
        String mA = Metaphone.encode(firstToken(a.scrambledName()));
        String mB = Metaphone.encode(firstToken(b.scrambledName()));
        if (!mA.isEmpty() && mA.equals(mB)) {
            logOdds += contrib(evidence, "name_phonetic", "metaphone match (" + mA + ")", 0.92, 0.10);
        }

        // -------------------- Pin code -------------------- //
        if (a.pinCode() != null && a.pinCode().equals(b.pinCode())) {
            logOdds += contrib(evidence, "pin_code", "exact match (" + a.pinCode() + ")", 0.95, 0.02);
        } else {
            logOdds += contribDisagree(evidence, "pin_code", "different pin codes", 0.95, 0.02);
        }

        // -------------------- Address (n-gram Jaccard) -------------------- //
        double addrJacc = StringSimilarity.ngramJaccard(
                norm(a.scrambledAddress()), norm(b.scrambledAddress()), 3);
        if (addrJacc >= 0.6) {
            logOdds += contrib(evidence, "address", "high overlap (jacc=" + fmt(addrJacc) + ")", 0.80, 0.04);
        } else if (addrJacc >= 0.3) {
            logOdds += contrib(evidence, "address", "moderate overlap (jacc=" + fmt(addrJacc) + ")", 0.40, 0.10);
        } else {
            logOdds += contribDisagree(evidence, "address", "low overlap (jacc=" + fmt(addrJacc) + ")", 0.80, 0.04);
        }

        // -------------------- Sector -------------------- //
        if (a.sector() != null && a.sector().equalsIgnoreCase(b.sector())) {
            logOdds += contrib(evidence, "sector", "same sector (" + a.sector() + ")", 0.85, 0.15);
        } else {
            logOdds += contribDisagree(evidence, "sector", "different sectors", 0.85, 0.15);
        }

        // -------------------- Registration date (within window) -------------------- //
        if (a.registrationDate() != null && b.registrationDate() != null) {
            long days = Math.abs(ChronoUnit.DAYS.between(a.registrationDate(), b.registrationDate()));
            if (days <= 180) {
                logOdds += contrib(evidence, "reg_date", "within 180 days (Δ=" + days + ")", 0.80, 0.10);
            } else if (days <= 730) {
                logOdds += contrib(evidence, "reg_date", "within 2 years (Δ=" + days + ")", 0.40, 0.20);
            } else {
                logOdds += contribDisagree(evidence, "reg_date", "Δ=" + days + " days", 0.80, 0.10);
            }
        }

        double prob = 1.0 / (1.0 + Math.exp(-logOdds));
        return ScoredPair.build(a, b, prob, evidence);
    }

    /* ----------------------- helpers ----------------------- */

    private double contrib(Map<String, ScoredPair.FeatureContribution> ev,
                           String name, String obs, double m, double u) {
        double c = Math.log(m / u);
        ev.put(name, new ScoredPair.FeatureContribution(name, obs, c));
        return c;
    }

    private double contribDisagree(Map<String, ScoredPair.FeatureContribution> ev,
                                   String name, String obs, double m, double u) {
        double c = Math.log((1 - m) / (1 - u));
        ev.put(name, new ScoredPair.FeatureContribution(name, obs, c));
        return c;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static String firstToken(String s) {
        if (s == null) return "";
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }
}
