package com.sainaw.mm.board;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawCoreLogic {

    private final Context context;
    private StringBuilder currentWord = new StringBuilder();
    private SuggestionDB suggestionDB;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Map<Integer, String> phoneticMap = new HashMap<>();
    private final Map<String, String> emojiMeaningMap = new HashMap<>();

    public SaiNawCoreLogic(Context context) {
        this.context = context;
        initDB(context);
        loadPhonetics();
        loadEmojiMeanings();
    }

    public void initDB(Context context) {
        if (suggestionDB == null) {
            suggestionDB = SuggestionDB.getInstance(context);
        }
    }

    public String processInput(int code) {
        String charStr = String.valueOf((char) code);
        
        if (code >= 4100 && code <= 4255) {
            currentWord.append(charStr);
            return fixOrdering(currentWord.toString(), charStr);
        }
        
        currentWord.append(charStr);
        return charStr;
    }

    private String fixOrdering(String fullWord, String lastChar) {
        String output = lastChar;
        
        if (lastChar.equals("\u1084")) { 
             return "\u200B" + lastChar; 
        }
        
        if (fullWord.matches(".*[\u1084][\u1000-\u102A\uAA60-\uAA7F]$")) {
             return fixShanOrdering(fullWord);
        }
        if (fullWord.matches(".*[\u1087-\u108C][\u1084]$")) {
             return fixShanOrdering(fullWord);
        }

        return output;
    }

    private String fixShanOrdering(String input) {
        String output = input;
        output = output.replace("\u200B", "");
        output = output.replaceAll("(\u1084)([\u1000-\u102A\uAA60-\uAA7F])", "$2$1");
        output = output.replaceAll("([\u1087-\u108C])(\u1084)", "$2$1");
        
        currentWord.setLength(0);
        currentWord.append(output);
        return output.substring(output.length() - 1); 
    }
    
    public String normalizeText(String input) {
        String output = input;
        output = output.replace("\u200B", "");
        output = output.replaceAll("(\u1084)([\u1000-\u102A\uAA60-\uAA7F])", "$2$1");
        output = output.replaceAll("([\u1087-\u108C])(\u1084)", "$2$1");
        output = output.replaceAll("([\u1031])([\u1000-\u102A])", "$2$1");
        output = output.replaceAll("([\u1031])([\u103B-\u103E])", "$2$1");
        return output;
    }

    public void backspace() {
        if (currentWord.length() > 0) {
            currentWord.deleteCharAt(currentWord.length() - 1);
        }
    }

    public void resetCurrentWord() {
        currentWord.setLength(0);
    }

    public void saveAndReset() {
        if (suggestionDB != null && currentWord.length() > 0) {
            final String wordToSave = normalizeText(currentWord.toString());
            dbExecutor.execute(() -> suggestionDB.saveWord(wordToSave));
        }
        currentWord.setLength(0);
    }

    private void loadPhonetics() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("pronunciation_mapping.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    phoneticMap.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
                }
            }
            reader.close();
        } catch (Exception ignored) {}
    }

    private void loadEmojiMeanings() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("emoji_mapping.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    emojiMeaningMap.put(parts[0].trim(), parts[1].trim());
                }
            }
            reader.close();
        } catch (Exception ignored) {
            emojiMeaningMap.put("😀", "ပြုံးနေသော မျက်နှာ");
            emojiMeaningMap.put("😂", "မျက်ရည်ထွက်မတတ် ရယ်နေသော မျက်နှာ");
            emojiMeaningMap.put("❤️", "အနီရောင် အသည်းနှလုံး");
            emojiMeaningMap.put("👍", "လက်မထောင်ထားသည်");
        }
    }

    public String getPhonetic(int code) {
        return phoneticMap.get(code);
    }

    public String getEmojiMeaning(String emoji) {
        return emojiMeaningMap.getOrDefault(emoji, "အီမိုဂျီ");
    }

    public void close() {
        if (suggestionDB != null) suggestionDB.close();
        dbExecutor.shutdown();
    }

    public static class SuggestionDB extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "sainaw_dict.db";
        private static final int DATABASE_VERSION = 1;
        private static final String TABLE_WORDS = "words";
        private static final String COLUMN_WORD = "word";
        private static final String COLUMN_FREQ = "frequency";
        private static SuggestionDB instance;

        public static synchronized SuggestionDB getInstance(Context context) {
            if (instance == null) instance = new SuggestionDB(context.getApplicationContext());
            return instance;
        }

        private SuggestionDB(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_WORDS + "(" + COLUMN_WORD + " TEXT PRIMARY KEY," + COLUMN_FREQ + " INTEGER DEFAULT 1)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORDS);
            onCreate(db);
        }

        public void saveWord(String word) {
            if (word == null || word.trim().isEmpty()) return;
            SQLiteDatabase db = this.getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("INSERT OR IGNORE INTO " + TABLE_WORDS + "(" + COLUMN_WORD + ", " + COLUMN_FREQ + ") VALUES (?, 1)", new Object[]{word});
                db.execSQL("UPDATE " + TABLE_WORDS + " SET " + COLUMN_FREQ + " = " + COLUMN_FREQ + " + 1 WHERE " + COLUMN_WORD + " = ?", new Object[]{word});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        public List<String> getSuggestions(String prefix) {
            List<String> suggestions = new ArrayList<>();
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.query(TABLE_WORDS, new String[]{COLUMN_WORD}, COLUMN_WORD + " LIKE ?", new String[]{prefix + "%"}, null, null, COLUMN_FREQ + " DESC", "3");
            while (cursor.moveToNext()) suggestions.add(cursor.getString(0));
            cursor.close();
            return suggestions;
        }
    }
}
