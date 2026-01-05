package com.cherry.sme.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TTSUtils {

    private static final Pattern SHAN_PATTERN = Pattern.compile("[\\u1022\\u1035\\u1062\\u1064\\u1067-\\u106D\\u1075-\\u108F\\u1090-\\u109F\\uaa60-\\uaa7f]");
    private static final Pattern MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]");

    public static class Chunk {
        public String text;
        public String lang;

        public Chunk(String text, String lang) {
            this.text = text;
            this.lang = lang;
        }
    }

    public static List<Chunk> splitHelper(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        StringBuilder currentBuffer = new StringBuilder();
        String currentLang = null;

        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            String currentChar = new String(Character.toChars(codePoint));
            String detectedLang;

            if (currentChar.trim().isEmpty()) {
                detectedLang = (currentLang != null) ? currentLang : "ENGLISH";
            } else if (SHAN_PATTERN.matcher(currentChar).find()) {
                detectedLang = "SHAN";
            } else if (MYANMAR_PATTERN.matcher(currentChar).find()) {
                detectedLang = "MYANMAR";
            } else {
                detectedLang = "ENGLISH";
            }

            if (currentLang == null) {
                currentLang = detectedLang;
                currentBuffer.append(currentChar);
            } else if (currentLang.equals(detectedLang)) {
                currentBuffer.append(currentChar);
            } else {
                chunks.add(new Chunk(currentBuffer.toString(), currentLang));
                currentBuffer.setLength(0);
                currentBuffer.append(currentChar);
                currentLang = detectedLang;
            }
            i += Character.charCount(codePoint);
        }

        if (currentBuffer.length() > 0) {
            chunks.add(new Chunk(currentBuffer.toString(), currentLang));
        }

        return chunks;
    }
}

