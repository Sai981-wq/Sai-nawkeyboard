package com.cherry.sme.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TTSUtils {
    private static final Pattern SHAN_PATTERN = Pattern.compile("[\\u1075-\\u108F\\u1090-\\u109F\\uaa60-\\uaa7f]+");
    private static final Pattern MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]+");

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

        StringBuilder buffer = new StringBuilder();
        String currentLang = "";

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String charStr = String.valueOf(c);

            if (Character.isWhitespace(c)) {
                buffer.append(c);
                continue;
            }

            String detectedLang = "ENGLISH";
            if (SHAN_PATTERN.matcher(charStr).find()) {
                detectedLang = "SHAN";
            } else if (MYANMAR_PATTERN.matcher(charStr).find()) {
                detectedLang = "MYANMAR";
            }

            if (buffer.length() == 0) {
                currentLang = detectedLang;
                buffer.append(c);
            } else if (detectedLang.equals(currentLang)) {
                buffer.append(c);
            } else {
                chunks.add(new Chunk(buffer.toString(), currentLang));
                buffer.setLength(0);
                buffer.append(c);
                currentLang = detectedLang;
            }
        }

        if (buffer.length() > 0) {
            chunks.add(new Chunk(buffer.toString(), currentLang));
        }
        return chunks;
    }
}
