package com.sainaw.mm.board;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class SuggestionDB extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "sainaw_dict.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_WORDS = "words";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQ = "frequency";

    private static final String PREFS_DICT = "SaiNawDictPrefs";
    private static final String KEY_SEEDED_V2 = "seeded_en_mm_v2";

    private static SuggestionDB instance;
    private final SaiNawTextProcessor textProcessor;
    private final Context appContext;

    public static synchronized SuggestionDB getInstance(Context context) {
        if (instance == null) {
            instance = new SuggestionDB(context.getApplicationContext());
        }
        return instance;
    }

    private SuggestionDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.appContext = context.getApplicationContext();
        this.textProcessor = new SaiNawTextProcessor();
        seedDictionariesIfNeeded();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_WORDS + "("
                + COLUMN_WORD + " TEXT PRIMARY KEY,"
                + COLUMN_FREQ + " INTEGER DEFAULT 1)";
        db.execSQL(CREATE_TABLE);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_prefix ON " + TABLE_WORDS + "(" + COLUMN_WORD + " COLLATE NOCASE, " + COLUMN_FREQ + " DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_prefix ON " + TABLE_WORDS + "(" + COLUMN_WORD + " COLLATE NOCASE, " + COLUMN_FREQ + " DESC)");
        }
    }

    private void seedDictionariesIfNeeded() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_DICT, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SEEDED_V2, false)) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                importAssetDictionary(db, "en_50k.txt", true);
                importAssetDictionary(db, "myanmar_suggestiondb_all_words.txt", false);
                prefs.edit().putBoolean(KEY_SEEDED_V2, true).apply();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void importAssetDictionary(SQLiteDatabase db, String assetFileName, boolean hasFrequencyColumn) {
        String insertSql = "INSERT OR IGNORE INTO " + TABLE_WORDS + " (" + COLUMN_WORD + ", " + COLUMN_FREQ + ") VALUES (?, ?)";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(appContext.getAssets().open(assetFileName), StandardCharsets.UTF_8), 32768)) {

            db.beginTransaction();
            try (SQLiteStatement stmt = db.compileStatement(insertSql)) {
                String line;
                int lineIndex = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String rawWord;
                    long freq = 1;

                    if (hasFrequencyColumn) {
                        int spaceIdx = line.lastIndexOf(' ');
                        if (spaceIdx <= 0) continue;
                        rawWord = line.substring(0, spaceIdx).trim();
                        if (rawWord.length() < 2 || rawWord.startsWith("'") || !Character.isLetter(rawWord.charAt(0))) {
                            continue;
                        }
                        try {
                            long rawCount = Long.parseLong(line.substring(spaceIdx + 1).trim());
                            // Scale huge corpus counts into clean ranking scores (2..5000) so user-typed words can still adapt
                            freq = Math.max(2, Math.min(5000, (long) (Math.sqrt(rawCount) / 2.0)));
                        } catch (NumberFormatException ignored) {
                            freq = 2;
                        }
                    } else {
                        rawWord = line.replace("\u200B", "").trim();
                        // Preserve alphabetical/dictionary order with a baseline priority
                        freq = Math.max(2, 100 - (lineIndex / 500));
                    }

                    String normalized = textProcessor.normalizeText(rawWord);
                    if (normalized == null || normalized.trim().isEmpty()) continue;

                    stmt.clearBindings();
                    stmt.bindString(1, normalized.trim());
                    stmt.bindLong(2, freq);
                    stmt.executeInsert();
                    lineIndex++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;

        String cleaned = word.replace("\u200B", "").trim();
        String normalizedWord = textProcessor.normalizeText(cleaned);
        if (normalizedWord == null || normalizedWord.trim().isEmpty()) return;

        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            String updateSql = "UPDATE " + TABLE_WORDS + " SET " + COLUMN_FREQ + " = " + COLUMN_FREQ + " + 10 WHERE " + COLUMN_WORD + " = ?";
            try (SQLiteStatement stmt = db.compileStatement(updateSql)) {
                stmt.bindString(1, normalizedWord);
                int rowsAffected = stmt.executeUpdateDelete();

                if (rowsAffected == 0) {
                    ContentValues values = new ContentValues();
                    values.put(COLUMN_WORD, normalizedWord);
                    values.put(COLUMN_FREQ, 15);
                    db.insert(TABLE_WORDS, null, values);
                }
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            db.endTransaction();
        }
    }

    public List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        if (prefix == null || prefix.trim().isEmpty()) return suggestions;

        String cleaned = prefix.replace("\u200B", "").trim();
        String normalizedPrefix = textProcessor.normalizeText(cleaned);
        if (normalizedPrefix == null || normalizedPrefix.trim().isEmpty()) return suggestions;

        String escapedPrefix = normalizedPrefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        SQLiteDatabase db = this.getReadableDatabase();
        try (Cursor cursor = db.query(
                TABLE_WORDS,
                new String[]{COLUMN_WORD},
                COLUMN_WORD + " LIKE ? ESCAPE '\\' COLLATE NOCASE",
                new String[]{escapedPrefix + "%"},
                null,
                null,
                COLUMN_FREQ + " DESC, LENGTH(" + COLUMN_WORD + ") ASC",
                "3")) {

            while (cursor.moveToNext()) {
                suggestions.add(cursor.getString(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return suggestions;
    }
}

