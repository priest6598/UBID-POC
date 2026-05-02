package com.karnataka.ubid.matching;

/**
 * Pure-Java implementation of Jaro-Winkler similarity. Returns a score in
 * [0.0, 1.0] where 1.0 is a perfect match.
 */
public final class StringSimilarity {

    private StringSimilarity() {}

    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        double jaro = jaro(s1, s2);
        if (jaro < 0.7) return jaro;

        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(s1.length(), s2.length()));
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + 0.1 * prefix * (1.0 - jaro);
    }

    private static double jaro(String s1, String s2) {
        int len1 = s1.length(), len2 = s2.length();
        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j]) continue;
                if (s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        int t = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) t++;
            k++;
        }
        double transpositions = t / 2.0;
        return (matches / (double) len1
              + matches / (double) len2
              + (matches - transpositions) / matches) / 3.0;
    }

    /**
     * Character n-gram Jaccard similarity — useful for address comparison
     * where word order may shift but substrings overlap.
     */
    public static double ngramJaccard(String a, String b, int n) {
        if (a == null || b == null) return 0.0;
        java.util.Set<String> ga = ngrams(a, n);
        java.util.Set<String> gb = ngrams(b, n);
        if (ga.isEmpty() && gb.isEmpty()) return 1.0;
        java.util.Set<String> intersection = new java.util.HashSet<>(ga);
        intersection.retainAll(gb);
        java.util.Set<String> union = new java.util.HashSet<>(ga);
        union.addAll(gb);
        return (double) intersection.size() / union.size();
    }

    private static java.util.Set<String> ngrams(String s, int n) {
        java.util.Set<String> out = new java.util.HashSet<>();
        String norm = s.toLowerCase().replaceAll("\\s+", " ").trim();
        if (norm.length() < n) {
            out.add(norm);
            return out;
        }
        for (int i = 0; i <= norm.length() - n; i++) {
            out.add(norm.substring(i, i + n));
        }
        return out;
    }
}
