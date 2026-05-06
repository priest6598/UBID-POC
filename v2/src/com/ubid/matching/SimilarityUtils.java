package com.ubid.matching;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class SimilarityUtils {

    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
        "\\b(pvt ltd|private limited|m/s|ltd|limited|llp|and|co|&)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NON_ALPHA = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern TOKENIZE  = Pattern.compile("[\\s\\p{Punct}]+");

    private SimilarityUtils() {}

    public static String normalizeBusinessName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase();
        s = SUFFIX_PATTERN.matcher(s).replaceAll(" ");
        s = NON_ALPHA.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int matchDist = Math.max(Math.max(s1.length(), s2.length()) / 2 - 1, 0);
        boolean[] s1Matched = new boolean[s1.length()];
        boolean[] s2Matched = new boolean[s2.length()];

        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int lo = Math.max(0, i - matchDist);
            int hi = Math.min(i + matchDist + 1, s2.length());
            for (int j = lo; j < hi; j++) {
                if (!s2Matched[j] && s1.charAt(i) == s2.charAt(j)) {
                    s1Matched[i] = true;
                    s2Matched[j] = true;
                    matches++;
                    break;
                }
            }
        }

        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!s1Matched[i]) continue;
            while (!s2Matched[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double jaro = (
            (double) matches / s1.length() +
            (double) matches / s2.length() +
            (double) (matches - transpositions / 2.0) / matches
        ) / 3.0;

        // Winkler prefix bonus (up to 4 chars)
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    public static double tokenJaccard(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";
        s1 = s1.trim();
        s2 = s2.trim();
        if (s1.isEmpty() && s2.isEmpty()) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        Set<String> set1 = new HashSet<>(Arrays.asList(TOKENIZE.split(s1.toLowerCase())));
        Set<String> set2 = new HashSet<>(Arrays.asList(TOKENIZE.split(s2.toLowerCase())));
        set1.remove("");
        set2.remove("");

        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    public static int levenshtein(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";
        int m = s1.length(), n = s2.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1], Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[m][n];
    }
}
