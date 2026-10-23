package com.sainaw.mm.board;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;

public class SaiNawPhoneticManager {
    
    private HashMap<Integer, String> phoneticMap = new HashMap<>();
    private final Context context;
    private int currentLanguageId = 0; // 0=Eng, 1=MM, 2=Shan

    public SaiNawPhoneticManager(Context context) {
        this.context = context;
        loadMappingFromFile();
    }

    // *** New Method: ဘာသာစကား အပြောင်းအလဲကို လက်ခံရန် ***
    public void setLanguageId(int languageId) {
        this.currentLanguageId = languageId;
    }

    private void loadMappingFromFile() {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("pronunciation_mapping.txt")));
            
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    try {
                        int code = Integer.parseInt(parts[0].trim());
                        String text = parts[1].trim();
                        phoneticMap.put(code, text);
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getPronunciation(int code) {
        // *** FIX: မြန်မာစာ (ID=1) ဖြစ်မှသာ Mapping ကို သုံးမည် ***
        if (currentLanguageId == 1) { 
            if (phoneticMap.containsKey(code)) {
                return phoneticMap.get(code);
            }
        }
        
        // ရှမ်း (ID=2) သို့မဟုတ် English (ID=0) ဆိုရင် မူရင်းအတိုင်းပြန်ပေးမယ်
        // (TalkBack က ရှမ်းအသံထွက်မှန်အောင် သူ့ဘာသာသူ ဖတ်ပါလိမ့်မယ်)
        return String.valueOf((char) code);
    }
}

