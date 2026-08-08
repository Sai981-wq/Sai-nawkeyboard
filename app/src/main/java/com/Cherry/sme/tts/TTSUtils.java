package com.cherry.sme.tts;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TTSUtils {

    private static final Pattern SHAN_PATTERN = Pattern.compile("[\\u1022\\u1035\\u1062\\u1064\\u1067-\\u106D\\u1075-\\u108F\\u1090-\\u109F\\uAA60-\\uAA7F]");
    private static final Pattern MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([\\u1000-\\u109F\\uAA60-\\uAA7F]+)|([^\\u1000-\\u109F\\uAA60-\\uAA7F\\s]+)|(\\s+)");
    
    private static final Map<String, String> wordMapping = new ConcurrentHashMap<>();

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
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(context.getAssets().open("mapping.txt")));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        wordMapping.put(parts[0].trim(), parts[1].trim());
                    } else {
                        wordMapping.put(line, "SHAN");
                    }
                }
                reader.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static List<Chunk> splitHelper(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;

        Matcher matcher = TOKEN_PATTERN.matcher(text);
        StringBuilder currentBuffer = new StringBuilder();
        String currentLang = null;

        while (matcher.find()) {
            String token = matcher.group();
            if (token.isEmpty()) continue;

            if (matcher.group(3) != null) {
                if (currentBuffer.length() > 0) {
                    currentBuffer.append(token);
                }
                continue;
            }

            String trimmedToken = token.trim();
            String detectedLang = "ENGLISH";

            if (wordMapping.containsKey(trimmedToken)) {
                detectedLang = wordMapping.get(trimmedToken);
            } else if (matcher.group(1) != null) {
                if (SHAN_PATTERN.matcher(token).find()) {
                    detectedLang = "SHAN";
                } else {
                    detectedLang = "MYANMAR";
                }
            }

            if (currentLang == null) {
                currentLang = detectedLang;
                currentBuffer.append(token);
            } else if (currentLang.equals(detectedLang)) {
                currentBuffer.append(token);
            } else {
                chunks.add(new Chunk(currentBuffer.toString(), currentLang));
                currentBuffer.setLength(0);
                currentBuffer.append(token);
                currentLang = detectedLang;
            }
        }

        if (currentBuffer.length() > 0) {
            chunks.add(new Chunk(currentBuffer.toString(), currentLang));
        }

        return chunks;
    }
}

