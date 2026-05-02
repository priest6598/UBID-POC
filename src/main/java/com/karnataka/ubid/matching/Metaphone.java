package com.karnataka.ubid.matching;

/**
 * Lightweight Metaphone implementation for phonetic name blocking.
 *
 * Reduces a name to a consonant skeleton so phonetic equivalents
 * (Ramesh / Rameshe / Ramesch) collide on the same key.
 */
public final class Metaphone {

    private Metaphone() {}

    public static String encode(String input) {
        if (input == null || input.isEmpty()) return "";
        String word = input.toUpperCase().replaceAll("[^A-Z]", "");
        if (word.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = word.length();

        // Drop common silent prefixes
        if (n >= 2) {
            String first2 = word.substring(0, 2);
            if (first2.equals("KN") || first2.equals("GN") ||
                first2.equals("PN") || first2.equals("AE") ||
                first2.equals("WR")) {
                i = 1;
            } else if (first2.equals("WH")) {
                out.append('W');
                i = 2;
            } else if (word.charAt(0) == 'X') {
                out.append('S');
                i = 1;
            }
        }

        while (i < n && out.length() < 6) {
            char c = word.charAt(i);
            char prev = i > 0 ? word.charAt(i - 1) : ' ';
            char next = i + 1 < n ? word.charAt(i + 1) : ' ';

            // Skip consecutive duplicates (except C which has special handling)
            if (c == prev && c != 'C') {
                i++;
                continue;
            }

            switch (c) {
                case 'A', 'E', 'I', 'O', 'U' -> {
                    if (i == 0) out.append(c);
                }
                case 'B' -> {
                    if (!(i == n - 1 && prev == 'M')) out.append('B');
                }
                case 'C' -> {
                    if (next == 'H') {
                        out.append('X');
                        i++;
                    } else if (next == 'I' || next == 'E' || next == 'Y') {
                        out.append('S');
                    } else {
                        out.append('K');
                    }
                }
                case 'D' -> {
                    if (next == 'G' && i + 2 < n) {
                        char nn = word.charAt(i + 2);
                        if (nn == 'E' || nn == 'I' || nn == 'Y') {
                            out.append('J');
                            i += 2;
                            break;
                        }
                    }
                    out.append('T');
                }
                case 'F' -> out.append('F');
                case 'G' -> {
                    if (next == 'H') {
                        if (i + 2 >= n || !isVowel(word.charAt(i + 2))) {
                            i++;
                            break;
                        }
                    }
                    if (next == 'N') break;
                    if (next == 'E' || next == 'I' || next == 'Y') {
                        out.append('J');
                    } else {
                        out.append('K');
                    }
                }
                case 'H' -> {
                    if (i > 0 && !isVowel(prev) && (next == ' ' || !isVowel(next))) break;
                    out.append('H');
                }
                case 'J' -> out.append('J');
                case 'K' -> {
                    if (prev != 'C') out.append('K');
                }
                case 'L' -> out.append('L');
                case 'M' -> out.append('M');
                case 'N' -> out.append('N');
                case 'P' -> {
                    if (next == 'H') {
                        out.append('F');
                        i++;
                    } else {
                        out.append('P');
                    }
                }
                case 'Q' -> out.append('K');
                case 'R' -> out.append('R');
                case 'S' -> {
                    if (next == 'H') {
                        out.append('X');
                        i++;
                    } else {
                        out.append('S');
                    }
                }
                case 'T' -> {
                    if (next == 'H') {
                        out.append('0');
                        i++;
                    } else {
                        out.append('T');
                    }
                }
                case 'V' -> out.append('F');
                case 'W' -> {
                    if (isVowel(next)) out.append('W');
                }
                case 'X' -> out.append("KS");
                case 'Y' -> {
                    if (isVowel(next)) out.append('Y');
                }
                case 'Z' -> out.append('S');
                default -> {}
            }
            i++;
        }
        return out.toString();
    }

    private static boolean isVowel(char c) {
        return "AEIOU".indexOf(c) >= 0;
    }
}
