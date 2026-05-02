package com.karnataka.ubid.scrambling;

import com.karnataka.ubid.model.BusinessRecord;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic, format-preserving PII scrambling sidecar.
 *
 * Two records that share a real PAN will share the same scrambled PAN
 * (so exact-match anchoring still works after scramble). Token similarity
 * is preserved within a name (consistent token substitution table) so
 * fuzzy matching is unaffected.
 *
 * Built on HMAC-SHA256 with a per-run secret. The secret never leaves the
 * scrambler; downstream stages only see the scrambled output.
 */
public final class PIIScrambler {

    private final SecretKeySpec key;
    private final Map<String, String> tokenCache = new HashMap<>();
    private final Map<String, String> panCache = new HashMap<>();
    private final Map<String, String> gstinCache = new HashMap<>();
    private final Map<String, String> addressTokenCache = new HashMap<>();

    public PIIScrambler(String runSecret) {
        this.key = new SecretKeySpec(runSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public List<BusinessRecord> scrambleAll(List<BusinessRecord> records) {
        return records.stream().map(this::scramble).collect(Collectors.toList());
    }

    public BusinessRecord scramble(BusinessRecord r) {
        String sName = scrambleName(r.businessName());
        String sAddr = scrambleAddress(r.address());
        String sPan = r.pan() == null ? null : scramblePan(r.pan());
        String sGstin = r.gstin() == null ? null : scrambleGstin(r.gstin());
        return r.withScrambled(sName, sAddr, sPan, sGstin);
    }

    /* ------------------------------------------------------------------ */
    /*  Name scrambling: per-token consistent substitution                 */
    /* ------------------------------------------------------------------ */

    private String scrambleName(String name) {
        if (name == null) return null;
        String[] tokens = name.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(scrambleToken(tokens[i].toLowerCase(), tokenCache, 6));
        }
        return sb.toString();
    }

    private String scrambleAddress(String address) {
        if (address == null) return null;
        // Preserve commas and digit groups (pin codes) where possible
        String[] parts = address.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            String[] tokens = parts[i].trim().split("\\s+");
            for (int j = 0; j < tokens.length; j++) {
                if (j > 0) sb.append(' ');
                String t = tokens[j];
                if (t.matches("\\d+")) {
                    sb.append(t); // keep numbers as-is (plot/door numbers are stable identifiers)
                } else {
                    sb.append(scrambleToken(t.toLowerCase(), addressTokenCache, 5));
                }
            }
        }
        return sb.toString();
    }

    private String scrambleToken(String token, Map<String, String> cache, int len) {
        return cache.computeIfAbsent(token, k -> {
            byte[] mac = hmac(k);
            // Build a 'pronounceable' alphabetic token from the MAC
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int b = mac[i] & 0xFF;
                sb.append((char) ('a' + (b % 26)));
            }
            // Capitalise to match typical token shape
            sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
            return sb.toString();
        });
    }

    /* ------------------------------------------------------------------ */
    /*  PAN / GSTIN: deterministic FPE-style — same input -> same output   */
    /* ------------------------------------------------------------------ */

    private String scramblePan(String pan) {
        return panCache.computeIfAbsent(pan, p -> {
            // PAN format: AAAAA9999A — preserve structure
            byte[] mac = hmac("PAN:" + p);
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 5; i++) {
                sb.append((char) ('A' + ((mac[i] & 0xFF) % 26)));
            }
            for (int i = 5; i < 9; i++) {
                sb.append((char) ('0' + ((mac[i] & 0xFF) % 10)));
            }
            sb.append((char) ('A' + ((mac[9] & 0xFF) % 26)));
            return sb.toString();
        });
    }

    private String scrambleGstin(String gstin) {
        return gstinCache.computeIfAbsent(gstin, g -> {
            // GSTIN format: 99AAAAA9999A9Z9 — preserve structure
            byte[] mac = hmac("GSTIN:" + g);
            StringBuilder sb = new StringBuilder(15);
            for (int i = 0; i < 2; i++) {
                sb.append((char) ('0' + ((mac[i] & 0xFF) % 10)));
            }
            for (int i = 0; i < 5; i++) {
                sb.append((char) ('A' + ((mac[i + 2] & 0xFF) % 26)));
            }
            for (int i = 0; i < 4; i++) {
                sb.append((char) ('0' + ((mac[i + 7] & 0xFF) % 10)));
            }
            sb.append((char) ('A' + ((mac[11] & 0xFF) % 26)));
            sb.append((char) ('0' + ((mac[12] & 0xFF) % 10)));
            sb.append('Z');
            sb.append((char) ('0' + ((mac[14] & 0xFF) % 10)));
            return sb.toString();
        });
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC failure", e);
        }
    }
}
