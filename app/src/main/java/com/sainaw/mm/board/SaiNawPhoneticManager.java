package com.sainaw.mm.board;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;

public class SaiNawPhoneticManager {
    
    private HashMap<Integer, String> phoneticMap = new HashMap<>();
    private final Context context;

    public SaiNawPhoneticManager(Context context) {
        this.context = context;
        loadMappingFromFile();
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
        if (phoneticMap.containsKey(code)) {
            return phoneticMap.get(code);
        }
        return String.valueOf((char) code);
    }
}
