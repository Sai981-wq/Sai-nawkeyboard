package com.sainaw.mm.board;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;

public class SaiNawEmojiManager {

    private final HashMap<Integer, String> emojiMap = new HashMap<>();
    private final Context context;

    public SaiNawEmojiManager(Context context) {
        this.context = context;
        loadMappingFromFile();
    }

    private void loadMappingFromFile() {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("emoji_mapping.txt")));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replace("", "").trim();
                String[] parts = line.split("=");
                
                if (parts.length == 2) {
                    String emojiChar = parts[0].trim();
                    String desc = parts[1].trim();
                    
                    if (!emojiChar.isEmpty()) {
                        int codePoint = emojiChar.codePointAt(0);
                        emojiMap.put(codePoint, desc);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getMmDescription(int code) {
        if (emojiMap.containsKey(code)) {
            return emojiMap.get(code);
        }
        return null;
    }

    public boolean hasDescription(int code) {
        return emojiMap.containsKey(code);
    }
}

