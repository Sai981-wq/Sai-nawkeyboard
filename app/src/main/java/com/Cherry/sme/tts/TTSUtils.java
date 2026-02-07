package com.cherry.sme.tts;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class TTSUtils {

    // ရှမ်း Pattern
    private static final Pattern SHAN_PATTERN = Pattern.compile("[\\u1022\\u1035\\u1062\\u1064\\u1067-\\u106D\\u1075-\\u108F\\u1090-\\u109F\\uAA60-\\uAA7F]");
    // မြန်မာ Pattern
    private static final Pattern MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]");
    
    private static final Map<String, String> wordMapping = new HashMap<>();

    public static class Chunk {
        public String text;
        public String lang;

        public Chunk(String text, String lang) {
            this.text = text;
            this.lang = lang;
        }
    }

    public static void loadMapping(Context context) {
        new Thread(() -> {
            try {
                wordMapping.clear();
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(context.getAssets().open("mapping.txt")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("=");
                        if (parts.length == 2) {
                            wordMapping.put(parts[0].trim(), parts[1].trim());
                        }
                    }
                    reader.close();
                } catch (Exception e) {}
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static List<Chunk> splitHelper(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;

        StringBuilder currentBuffer = new StringBuilder();
        String currentLang = null;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String charStr = String.valueOf(c);
            String detectedLang;

            if (Character.isWhitespace(c) || isPunctuation(c)) {
                // Space တွေ့ရင် လက်ရှိ Language အတိုင်းဆက်သွား၊ မရှိသေးရင် English
                detectedLang = (currentLang != null) ? currentLang : "ENGLISH";
            } else {
                detectedLang = identifyLang(charStr);
            }

            if (currentLang == null) {
                currentLang = detectedLang;
                currentBuffer.append(c);
            } else if (currentLang.equals(detectedLang)) {
                currentBuffer.append(c);
            } else {
                chunks.add(new Chunk(currentBuffer.toString(), currentLang));
                currentBuffer.setLength(0);
                currentBuffer.append(c);
                currentLang = detectedLang;
            }
        }

        if (currentBuffer.length() > 0) {
            if (currentLang == null) currentLang = "ENGLISH";
            chunks.add(new Chunk(currentBuffer.toString(), currentLang));
        }

        return chunks;
    }

    private static String identifyLang(String text) {
        if (wordMapping.containsKey(text)) {
            return wordMapping.get(text);
        }
        if (SHAN_PATTERN.matcher(text).find()) {
            return "SHAN";
        }
        if (MYANMAR_PATTERN.matcher(text).find()) {
            return "MYANMAR";
        }
        // ကျန်တာမှန်သမျှ English
        return "ENGLISH";
    }

    private static boolean isPunctuation(char c) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~၊။".indexOf(c) >= 0;
    }
}

