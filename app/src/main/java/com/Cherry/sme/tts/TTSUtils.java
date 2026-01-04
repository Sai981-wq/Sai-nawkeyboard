package com.cherry.sme.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TTSUtils {

    private static final Pattern SHAN_PATTERN = Pattern.compile("[\\u1075-\\u108F\\u1090-\\u109F\\uaa60-\\uaa7f]");
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

        String[] words = text.split("\\s+");

        for (String word : words) {
            if (word.trim().isEmpty()) continue;

            String detectedLang = "ENGLISH";

            if (SHAN_PATTERN.matcher(word).find()) {
                detectedLang = "SHAN";
            } else if (MYANMAR_PATTERN.matcher(word).find()) {
                detectedLang = "MYANMAR";
            }

            chunks.add(new Chunk(word, detectedLang));
        }
        return chunks;
    }
}

