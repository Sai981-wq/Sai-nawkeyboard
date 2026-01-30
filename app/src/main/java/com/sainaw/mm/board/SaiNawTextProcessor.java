package com.sainaw.mm.board;

public class SaiNawTextProcessor {

    private static final char ZWSP = '\u200B';

    public boolean isConsonant(int c) {
        return (c >= '\u1000' && c <= '\u102A') ||
               (c == '\u103F') ||
               (c >= '\u1040' && c <= '\u1049') ||
               (c == '\u104E') ||
               (c >= '\u1050' && c <= '\u1055') ||
               (c >= '\u1075' && c <= '\u1081') ||
               (c >= '\uA9E0' && c <= '\uA9E6') ||
               (c >= '\uAA60' && c <= '\uAA6F');
    }

    public boolean isMedial(int code) {
        return (code >= '\u103B' && code <= '\u103E') || (code == '\u1082');
    }

    public String normalizeText(String input) {
        if (input == null || input.isEmpty()) return input;

        String cleanStr = input.replace(String.valueOf(ZWSP), "");
        char[] chars = cleanStr.toCharArray();
        int len = chars.length;

        for (int i = 1; i < len; i++) {
            char current = chars[i];
            char prev = chars[i - 1];

            if (prev == '\u1031' || prev == '\u1084') {
                if (isMedial(current)) {
                    chars[i - 1] = current;
                    chars[i] = prev;
                } else if (isConsonant(current)) {
                    boolean shouldSwap = true;

                    if (i + 1 < len) {
                        char nextChar = chars[i + 1];
                        if (nextChar == '\u103A' || nextChar == '\u1039') {
                            shouldSwap = false;
                        }
                    }

                    if (shouldSwap) {
                        boolean isAlreadyAttached = false;
                        if (i >= 2) {
                            char prevPrev = chars[i - 2];
                            if (isConsonant(prevPrev) || isMedial(prevPrev)) {
                                isAlreadyAttached = true;
                            }
                        }

                        if (!isAlreadyAttached) {
                            chars[i - 1] = current;
                            chars[i] = prev;
                        }
                    }
                }
                continue;
            }

            if (current == '\u102D' && (prev == '\u102F' || prev == '\u1030')) {
                chars[i - 1] = current;
                chars[i] = prev;
            }

            if (current == '\u102F' && prev == '\u1036') {
                chars[i - 1] = current;
                chars[i] = prev;
            }

            if (current == '\u102F' && prev == '\u1037') {
                chars[i - 1] = current;
                chars[i] = prev;
            }

            if (current == '\u103D' && prev == '\u103E') {
                chars[i - 1] = current;
                chars[i] = prev;
            }
        }
        return new String(chars);
    }

    public String getSyllableToSpeak(CharSequence textBefore) {
        if (textBefore == null || textBefore.length() == 0) return null;

        String text = textBefore.toString();
        int endIndex = text.length();
        int startIndex = endIndex;

        boolean isKilledOrStacked = false;

        for (int i = endIndex - 1; i >= 0; i--) {
            char c = text.charAt(i);

            if (c == '\u103A' || c == '\u1039') {
                isKilledOrStacked = true;
                continue;
            }

            if (isConsonant(c)) {
                if (isKilledOrStacked) {
                    isKilledOrStacked = false;
                } else {
                    if (i > 0 && text.charAt(i - 1) == '\u1039') {
                        continue;
                    }
                    startIndex = i;
                    break;
                }
            } else if (c == ' ' || c == '\n' || c == '\t') {
                startIndex = i + 1;
                break;
            } else {
                if (isKilledOrStacked) isKilledOrStacked = false;
            }
        }

        if (startIndex < endIndex) {
            String syllable = text.substring(startIndex, endIndex);
            return syllable.replace(String.valueOf(ZWSP), "");
        }
        return null;
    }

    public String getWordForEcho(CharSequence textBefore) {
        if (textBefore == null || textBefore.length() == 0) return null;

        String text = textBefore.toString();
        int lastSpaceIndex = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t') {
                lastSpaceIndex = i;
                break;
            }
        }

        String word = (lastSpaceIndex == -1) ? text : text.substring(lastSpaceIndex + 1);
        return word.replace(String.valueOf(ZWSP), "").trim();
    }
}

