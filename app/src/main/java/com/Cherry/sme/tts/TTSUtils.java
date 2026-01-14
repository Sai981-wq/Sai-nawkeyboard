package com.cherry.sme.tts;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TTSUtils {

    private static Pattern SHAN_PATTERN;
    
    private static final String DEFAULT_SHAN_RANGE = "[\\u1022\\u1035\\u1062\\u1064\\u1067-\\u106D\\u1075-\\u108F\\u1090-\\u109F\\uaa60-\\uaa7f]";
    private static final Pattern MYANMAR_PATTERN = Pattern.compile("[\\u1000-\\u109F]");

    static {
        SHAN_PATTERN = Pattern.compile(DEFAULT_SHAN_RANGE);
    }

    public static void loadShanMapping(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(DEFAULT_SHAN_RANGE);

        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("shan_mapping.txt")));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    sb.append("|").append(Pattern.quote(line));
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        SHAN_PATTERN = Pattern.compile(sb.toString());
    }

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

        String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[\\u1000-\\u109F\\uAA60-\\uAA7F])(?=[^\\u1000-\\u109F\\uAA60-\\uAA7F])|(?<=[^\\u1000-\\u109F\\uAA60-\\uAA7F])(?=[\\u1000-\\u109F\\uAA60-\\uAA7F])");
        
        StringBuilder currentBuffer = new StringBuilder();
        String currentLang = null;

        for (String word : words) {
            if (word.isEmpty()) continue;
            
            if (word.trim().isEmpty()) {
                if (currentLang != null) {
                    currentBuffer.append(word);
                }
                continue;
            }

            String detectedLang = "ENGLISH";
            if (SHAN_PATTERN.matcher(word).find()) {
                detectedLang = "SHAN";
            } else if (MYANMAR_PATTERN.matcher(word).find()) {
                detectedLang = "MYANMAR";
            }

            if (currentLang == null) {
                currentLang = detectedLang;
                currentBuffer.append(word);
            } else if (currentLang.equals(detectedLang)) {
                currentBuffer.append(word);
            } else {
                chunks.add(new Chunk(currentBuffer.toString(), currentLang));
                currentBuffer.setLength(0);
                currentBuffer.append(word);
                currentLang = detectedLang;
            }
        }

        if (currentBuffer.length() > 0) {
            chunks.add(new Chunk(currentBuffer.toString(), currentLang));
        }

        return chunks;
    }
}

