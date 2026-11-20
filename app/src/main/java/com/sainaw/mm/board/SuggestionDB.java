package com.sainaw.mm.board;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SuggestionDB extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "sainaw_dict.db";
    private static final int DATABASE_VERSION = 4;
    private static final String TABLE_WORDS = "words";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQ = "frequency";

    private static volatile SuggestionDB instance;
    private final SaiNawTextProcessor textProcessor;
    private final Context appContext;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // RAM Cache: loaded once into memory for instant prefix lookup without DB contention
    private volatile List<String> ramDictionary = Collections.emptyList();
    private final ConcurrentHashMap<String, Integer> ramBoostMap = new ConcurrentHashMap<>();
    private volatile boolean ramLoaded = false;

    public static SuggestionDB getInstance(Context context) {
        if (instance == null) {
            synchronized (SuggestionDB.class) {
                if (instance == null) {
                    instance = new SuggestionDB(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SuggestionDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.appContext = context.getApplicationContext();
        this.textProcessor = new SaiNawTextProcessor();
        preloadIntoRamAsync();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE_WORDS + "("
                + COLUMN_WORD + " TEXT PRIMARY KEY,"
                + COLUMN_FREQ + " INTEGER DEFAULT 1)";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    private void preloadIntoRamAsync() {
        ioExecutor.execute(() -> {
            try {
                Set<String> uniqueWords = new LinkedHashSet<>();

                // 1. Load Myanmar dictionary into RAM
                loadAssetWordList(uniqueWords, "myanmar_suggestiondb_all_words.txt", false);

                // 2. Load English 50k (if present in assets) into RAM
                loadAssetWordList(uniqueWords, "en_50k.txt", true);

                // 3. Load user-saved/boosted words from SQLite into RAM map
                try {
                    SQLiteDatabase db = getReadableDatabase();
                    try (Cursor cursor = db.query(TABLE_WORDS, new String[]{COLUMN_WORD, COLUMN_FREQ}, null, null, null, null, null)) {
                        while (cursor.moveToNext()) {
                            String w = cursor.getString(0);
                            int freq = cursor.getInt(1);
                            if (w != null && !w.isEmpty()) {
                                uniqueWords.add(w);
                                if (freq > 1) {
                                    ramBoostMap.put(w, freq);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) { }

                List<String> loadedList = new ArrayList<>(uniqueWords);
                Collections.sort(loadedList, String.CASE_INSENSITIVE_ORDER);
                ramDictionary = Collections.unmodifiableList(loadedList);
                ramLoaded = true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void loadAssetWordList(Set<String> targetSet, String assetFileName, boolean isFrequencyList) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(appContext.getAssets().open(assetFileName), StandardCharsets.UTF_8), 16384)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String wordPart;
                if (isFrequencyList) {
                    int spaceIdx = line.lastIndexOf(' ');
                    if (spaceIdx <= 0) continue;
                    wordPart = line.substring(0, spaceIdx).trim();
                    if (wordPart.length() < 2 || wordPart.startsWith("'") || !Character.isLetter(wordPart.charAt(0))) {
                        continue;
                    }
                } else {
                    wordPart = line.replace("\u200B", "").trim();
                }

                String normalized = textProcessor.normalizeText(wordPart);
                if (normalized != null && !normalized.trim().isEmpty()) {
                    targetSet.add(normalized.trim());
                }
            }
        } catch (Exception ignored) {
            // Asset file optional if not present
        }
    }

    public void saveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;

        String cleaned = word.replace("\u200B", "").trim();
        final String normalizedWord = textProcessor.normalizeText(cleaned);
        if (normalizedWord == null || normalizedWord.trim().isEmpty()) return;

        final String finalWord = normalizedWord.trim();

        // Update RAM immediately (zero UI/DB delay)
        int updatedScore = ramBoostMap.merge(finalWord, 15, Integer::sum);
        List<String> currentList = ramDictionary;
        if (!currentList.contains(finalWord)) {
            List<String> expanded = new ArrayList<>(currentList.size() + 1);
            expanded.addAll(currentList);
            expanded.add(finalWord);
            Collections.sort(expanded, String.CASE_INSENSITIVE_ORDER);
            ramDictionary = Collections.unmodifiableList(expanded);
        }

        // Persist to SQLite asynchronously in background
        ioExecutor.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                String updateSql = "UPDATE " + TABLE_WORDS + " SET " + COLUMN_FREQ + " = ? WHERE " + COLUMN_WORD + " = ?";
                try (SQLiteStatement stmt = db.compileStatement(updateSql)) {
                    stmt.bindLong(1, updatedScore);
                    stmt.bindString(2, finalWord);
                    int rows = stmt.executeUpdateDelete();
                    if (rows == 0) {
                        ContentValues values = new ContentValues();
                        values.put(COLUMN_WORD, finalWord);
                        values.put(COLUMN_FREQ, updatedScore);
                        db.insertWithOnConflict(TABLE_WORDS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
            } catch (Exception ignored) { }
        });
    }

    public List<String> getSuggestions(String prefix) {
        List<String> results = new ArrayList<>(3);
        if (prefix == null || prefix.trim().isEmpty() || !ramLoaded) return results;

        String cleaned = prefix.replace("\u200B", "").trim();
        String normalizedPrefix = textProcessor.normalizeText(cleaned);
        if (normalizedPrefix == null || normalizedPrefix.trim().isEmpty()) return results;

        final String lowerPrefix = normalizedPrefix.trim().toLowerCase(Locale.ROOT);
        List<String> snapshot = ramDictionary;
        List<String> matches = new ArrayList<>();

        for (int i = 0; i < snapshot.size(); i++) {
            String candidate = snapshot.get(i);
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matches.add(candidate);
            }
        }

        if (matches.isEmpty()) return results;

        Collections.sort(matches, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int scoreA = ramBoostMap.getOrDefault(a, 1);
                int scoreB = ramBoostMap.getOrDefault(b, 1);
                if (scoreA != scoreB) {
                    return Integer.compare(scoreB, scoreA);
                }
                int lenDiff = Integer.compare(a.length(), b.length());
                if (lenDiff != 0) return lenDiff;
                return a.compareToIgnoreCase(b);
            }
        });

        int limit = Math.min(3, matches.size());
        for (int i = 0; i < limit; i++) {
            results.add(matches.get(i));
        }
        return results;
    }
}

