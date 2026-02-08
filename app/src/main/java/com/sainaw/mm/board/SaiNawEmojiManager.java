package com.sainaw.mm.board;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
            // UTF-8 ဖြင့် အတိအကျ ဖတ်ရန် ပြင်ဆင်ထားသည်
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("emoji_mapping.txt"), StandardCharsets.UTF_8));
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("=")) continue;

                // Description ထဲတွင် = ပါခဲ့လျှင် မပျက်အောင် limit 2 ထားသည်
                String[] parts = line.split("=", 2);
                
                if (parts.length == 2) {
                    String emojiChar = parts[0].trim();
                    String desc = parts[1].trim();
                    
                    if (!emojiChar.isEmpty()) {
                        // Emoji ၏ Unicode Code Point အမှန်ကို ရယူသည်
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
        return emojiMap.get(code);
    }

    public boolean hasDescription(int code) {
        return emojiMap.containsKey(code);
    }
}

