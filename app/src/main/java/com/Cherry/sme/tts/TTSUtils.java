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

    private static final Pattern SHAN_SPECIFIC_PATTERN = Pattern.compile("[\\u1022\\u1035\\u1062\\u1064\\u1067-\\u106D\\u1075-\\u108F\\u1090-\\u109F\\uaa60-\\uaa7f]");
    private static final Pattern MYANMAR_RANGE_PATTERN = Pattern.compile("[\\u1000-\\u109F]");
    private static final Pattern ENGLISH_CHAR_PATTERN = Pattern.compile("[a-zA-Z]");
    
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

        String[] words = text.split("(?<=\\s)|(?=\\s)|(?<=[\\u1000-\\u109F\\uAA60-\\uAA7F])(?=[^\\u1000-\\u109F\\uAA60-\\uAA7F])|(?<=[^\\u1000-\\u109F\\uAA60-\\uAA7F])(?=[\\u1000-\\u109F\\uAA60-\\uAA7F])");
        
        StringBuilder currentBuffer = new StringBuilder();
        String currentLang = null;

        for (String word : words) {
            if (word.isEmpty()) continue;
            
            if (word.trim().isEmpty()) {
                if (currentBuffer.length() > 0) {
                    currentBuffer.append(word);
                }
                continue;
            }

            String trimmedWord = word.trim();
            String detectedLang = null;

            if (wordMapping.containsKey(trimmedWord)) {
                detectedLang = wordMapping.get(trimmedWord);
            } else {
                boolean hasShanSpecific = SHAN_SPECIFIC_PATTERN.matcher(word).find();
                boolean hasMyanmarRange = MYANMAR_RANGE_PATTERN.matcher(word).find();
                boolean hasEnglish = ENGLISH_CHAR_PATTERN.matcher(word).find();

                if (hasShanSpecific) {
                    detectedLang = "SHAN";
                } else if (hasMyanmarRange) {
                    detectedLang = "MYANMAR";
                } else if (hasEnglish) {
                    detectedLang = "ENGLISH";
                } else {
                    boolean isForeign = false;
                    for (char c : word.toCharArray()) {
                        if (Character.isLetter(c)) { 
                            isForeign = true;
                            break;
                        }
                    }

                    if (isForeign) {
                        continue; 
                    }

                    if (currentLang != null) {
                        detectedLang = currentLang;
                    } else {
                        detectedLang = "ENGLISH";
                    }
                }
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

