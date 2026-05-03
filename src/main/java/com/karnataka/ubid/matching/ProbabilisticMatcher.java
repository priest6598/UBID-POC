package com.karnataka.ubid.matching;

import com.karnataka.ubid.learning.MUParams;
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
 * Each observation falls into exactly one tier per feature. The log-odds
 * contribution for that tier is log(m/u) where (m, u) come from the
 * supplied {@link MUParams}. Total log-odds → posterior probability via
 * the logistic function.
 *
 * MU values are externalised so the active-learning trainer can update
 * them from reviewer decisions (proposal §4.2).
 */
public final class ProbabilisticMatcher {

    private final MUParams params;

    public ProbabilisticMatcher() { this(MUParams.defaults()); }

    public ProbabilisticMatcher(MUParams params) { this.params = params; }

    public List<ScoredPair> score(List<BlockingEngine.CandidatePair> candidates) {
        List<ScoredPair> out = new ArrayList<>(candidates.size());
        for (BlockingEngine.CandidatePair c : candidates) {
            out.add(scorePair(c.left(), c.right()));
        }
        return out;
    }

    public ScoredPair scorePair(BusinessRecord a, BusinessRecord b) {
        Map<String, ScoredPair.FeatureContribution> ev = new LinkedHashMap<>();
        double logOdds = MUParams.priorLogOdds();

        // -------------------- PAN -------------------- //
        if (a.scrambledPan() != null && b.scrambledPan() != null) {
            String tier = a.scrambledPan().equals(b.scrambledPan()) ? "pan.match" : "pan.disagree";
            String obs  = tier.equals("pan.match") ? "exact match" : "different values";
            logOdds += emit(ev, "pan", tier, obs);
        } else {
            ev.put("pan", new ScoredPair.FeatureContribution(
                    "pan", "pan.missing", "not available in one or both systems", 0.0));
        }

        // -------------------- GSTIN -------------------- //
        if (a.scrambledGstin() != null && b.scrambledGstin() != null) {
            String tier = a.scrambledGstin().equals(b.scrambledGstin()) ? "gstin.match" : "gstin.disagree";
            String obs  = tier.equals("gstin.match") ? "exact match" : "different values";
            logOdds += emit(ev, "gstin", tier, obs);
        }

        // -------------------- Name (Jaro-Winkler) -------------------- //
        double nameJw = StringSimilarity.jaroWinkler(norm(a.scrambledName()), norm(b.scrambledName()));
        String nameTier =
                nameJw >= 0.95 ? "name.near" :
                nameJw >= 0.85 ? "name.high" :
                nameJw >= 0.70 ? "name.moderate" : "name.low";
        logOdds += emit(ev, "name", nameTier, "jw=" + fmt(nameJw));

        // -------------------- Phonetic name -------------------- //
        String mA = Metaphone.encode(firstToken(a.scrambledName()));
        String mB = Metaphone.encode(firstToken(b.scrambledName()));
        String phoneticTier = !mA.isEmpty() && mA.equals(mB) ? "name_phonetic.match" : "name_phonetic.no_match";
        String phoneticObs  = phoneticTier.equals("name_phonetic.match") ? "match (" + mA + ")" : "no match";
        logOdds += emit(ev, "name_phonetic", phoneticTier, phoneticObs);

        // -------------------- Pin code -------------------- //
        String pinTier = a.pinCode() != null && a.pinCode().equals(b.pinCode()) ? "pin.agree" : "pin.disagree";
        String pinObs  = pinTier.equals("pin.agree") ? "exact match (" + a.pinCode() + ")" : "different pin codes";
        logOdds += emit(ev, "pin_code", pinTier, pinObs);

        // -------------------- Address (3-gram Jaccard) -------------------- //
        double addrJacc = StringSimilarity.ngramJaccard(norm(a.scrambledAddress()), norm(b.scrambledAddress()), 3);
        String addrTier =
                addrJacc >= 0.6 ? "address.high" :
                addrJacc >= 0.3 ? "address.moderate" : "address.low";
        logOdds += emit(ev, "address", addrTier, "jacc=" + fmt(addrJacc));

        // -------------------- Sector -------------------- //
        boolean sectorEq = a.sector() != null && a.sector().equalsIgnoreCase(b.sector());
        String sectorTier = sectorEq ? "sector.agree" : "sector.disagree";
        String sectorObs  = sectorEq ? "same sector (" + a.sector() + ")" : "different sectors";
        logOdds += emit(ev, "sector", sectorTier, sectorObs);

        // -------------------- Registration date -------------------- //
        if (a.registrationDate() != null && b.registrationDate() != null) {
            long days = Math.abs(ChronoUnit.DAYS.between(a.registrationDate(), b.registrationDate()));
            String dateTier =
                    days <= 180 ? "reg_date.close" :
                    days <= 730 ? "reg_date.moderate" : "reg_date.far";
            logOdds += emit(ev, "reg_date", dateTier, "Δ=" + days + " days");
        }

        double prob = 1.0 / (1.0 + Math.exp(-logOdds));
        return ScoredPair.build(a, b, prob, ev);
    }

    private double emit(Map<String, ScoredPair.FeatureContribution> ev,
                        String feature, String tierKey, String observation) {
        double c = params.get(tierKey).logOdds();
        ev.put(feature, new ScoredPair.FeatureContribution(feature, tierKey, observation, c));
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

    private static String fmt(double d) { return String.format("%.2f", d); }
}
