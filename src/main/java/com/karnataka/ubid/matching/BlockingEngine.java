package com.karnataka.ubid.matching;

import com.karnataka.ubid.model.BusinessRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reduces O(n^2) candidate pairs to O(n*k) via union-of-blocks. A pair
 * is a candidate if it agrees on ANY of the blocking keys:
 *   - pin code (6 digits)
 *   - GSTIN prefix (first 15 chars — state + PAN component)
 *   - first-token Metaphone of business name
 *   - 3-gram address overlap (>= 1 shared 3-gram in first 30 chars)
 *
 * Operates on scrambled fields — no real PII is read here.
 */
public final class BlockingEngine {

    public record CandidatePair(BusinessRecord left, BusinessRecord right, Set<String> blocksHit) {}

    public List<CandidatePair> generateCandidates(List<BusinessRecord> records) {
        Map<String, List<Integer>> byPin = new HashMap<>();
        Map<String, List<Integer>> byGstinPrefix = new HashMap<>();
        Map<String, List<Integer>> byNameMetaphone = new HashMap<>();
        Map<String, List<Integer>> byAddressNgram = new HashMap<>();

        for (int i = 0; i < records.size(); i++) {
            BusinessRecord r = records.get(i);
            byPin.computeIfAbsent(r.pinCode(), k -> new ArrayList<>()).add(i);

            if (r.scrambledGstin() != null && r.scrambledGstin().length() >= 15) {
                byGstinPrefix.computeIfAbsent(r.scrambledGstin().substring(0, 15),
                        k -> new ArrayList<>()).add(i);
            }

            String firstToken = firstToken(r.scrambledName());
            if (!firstToken.isEmpty()) {
                String code = Metaphone.encode(firstToken);
                if (!code.isEmpty()) {
                    byNameMetaphone.computeIfAbsent(code, k -> new ArrayList<>()).add(i);
                }
            }

            for (String gram : addressNgrams(r.scrambledAddress())) {
                byAddressNgram.computeIfAbsent(gram, k -> new ArrayList<>()).add(i);
            }
        }

        // Use a map keyed by (i,j) to dedupe pairs while accumulating blocking-key hits
        Map<Long, Set<String>> pairBlocks = new HashMap<>();
        accumulate(byPin, "pin_code", pairBlocks);
        accumulate(byGstinPrefix, "gstin_prefix", pairBlocks);
        accumulate(byNameMetaphone, "name_phonetic", pairBlocks);
        accumulate(byAddressNgram, "address_ngram", pairBlocks);

        List<CandidatePair> out = new ArrayList<>(pairBlocks.size());
        for (Map.Entry<Long, Set<String>> e : pairBlocks.entrySet()) {
            int i = (int) (e.getKey() >>> 32);
            int j = e.getKey().intValue();
            BusinessRecord a = records.get(i), b = records.get(j);
            // Don't pair a record with itself within the same source system if record IDs differ —
            // we DO want to pair two different shop_est records (true duplicates within a system
            // happen). The recordId check below is enough.
            if (a.recordId().equals(b.recordId())) continue;
            out.add(new CandidatePair(a, b, e.getValue()));
        }
        return out;
    }

    private void accumulate(Map<String, List<Integer>> blocks, String label,
                            Map<Long, Set<String>> pairBlocks) {
        for (List<Integer> bucket : blocks.values()) {
            if (bucket.size() < 2) continue;
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    int a = Math.min(bucket.get(i), bucket.get(j));
                    int b = Math.max(bucket.get(i), bucket.get(j));
                    long key = ((long) a << 32) | (b & 0xFFFFFFFFL);
                    pairBlocks.computeIfAbsent(key, k -> new HashSet<>()).add(label);
                }
            }
        }
    }

    private static String firstToken(String s) {
        if (s == null) return "";
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static Set<String> addressNgrams(String addr) {
        Set<String> out = new HashSet<>();
        if (addr == null) return out;
        String norm = addr.toLowerCase().replaceAll("[^a-z0-9 ]", "");
        if (norm.length() > 30) norm = norm.substring(0, 30);
        if (norm.length() < 3) return out;
        for (int i = 0; i <= norm.length() - 3; i++) {
            out.add(norm.substring(i, i + 3));
        }
        return out;
    }
}
