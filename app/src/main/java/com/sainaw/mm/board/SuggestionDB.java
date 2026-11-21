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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SuggestionDB extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "sainaw_dict.db";
    private static final int DATABASE_VERSION = 4;
    private static final String TABLE_WORDS = "words";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQ = "frequency";

    private static final class WordEntry implements Comparable<WordEntry> {
        final String word;
        final String lower;
        final int baseFreq;

        WordEntry(String word, String lower, int baseFreq) {
            this.word = word;
            this.lower = lower;
            this.baseFreq = baseFreq;
        }

        @Override
        public int compareTo(WordEntry other) {
            return this.lower.compareTo(other.lower);
        }
    }

    private static volatile SuggestionDB instance;
    private final SaiNawTextProcessor textProcessor;
    private final Context appContext;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // Immutable per-initial-char sorted buckets for zero-allocation binary search
    private volatile Map<Character, List<WordEntry>> buckets = Collections.emptyMap();
    // User-typed / boosted frequencies (also stores newly learned words without copying main list)
    private final ConcurrentHashMap<String, Integer> userBoostMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> userLowerMap = new ConcurrentHashMap<>();
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
                Map<String, WordEntry> dedup = new HashMap<>(65536);

                // 1. Load Myanmar dictionary
                loadAssetWordList(dedup, "myanmar_suggestiondb_all_words.txt", false);

                // 2. Load English 50k (if present)
                loadAssetWordList(dedup, "en_50k.txt", true);

                // 3. Load user-saved words from SQLite
                try {
                    SQLiteDatabase db = getReadableDatabase();
                    try (Cursor cursor = db.query(TABLE_WORDS, new String[]{COLUMN_WORD, COLUMN_FREQ}, null, null, null, null, null)) {
                        while (cursor.moveToNext()) {
                            String w = cursor.getString(0);
                            int freq = cursor.getInt(1);
                            if (w != null && !w.isEmpty()) {
                                String lower = fastLower(w);
                                userBoostMap.put(w, Math.max(freq, 15));
                                if (!dedup.containsKey(lower)) {
                                    userLowerMap.put(w, lower);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) { }

                // Group by first character of lowercase key and sort each bucket once
                Map<Character, List<WordEntry>> built = new HashMap<>(128);
                for (WordEntry entry : dedup.values()) {
                    char first = entry.lower.charAt(0);
                    List<WordEntry> list = built.get(first);
                    if (list == null) {
                        list = new ArrayList<>();
                        built.put(first, list);
                    }
                    list.add(entry);
                }
                dedup.clear();

                for (List<WordEntry> list : built.values()) {
                    Collections.sort(list);
                }

                buckets = built;
                ramLoaded = true;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    private static String fastLower(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals(s) ? s : lower;
    }

    private void loadAssetWordList(Map<String, WordEntry> target, String assetFileName, boolean isFrequencyList) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(appContext.getAssets().open(assetFileName), StandardCharsets.UTF_8), 16384)) {
            String line;
            int lineIndex = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String wordPart;
                int baseFreq = 2;

                if (isFrequencyList) {
                    int spaceIdx = line.lastIndexOf(' ');
                    if (spaceIdx <= 0) continue;
                    wordPart = line.substring(0, spaceIdx).trim();
                    if (wordPart.length() < 2 || wordPart.startsWith("'") || !Character.isLetter(wordPart.charAt(0))) {
                        continue;
                    }
                    try {
                        long rawCount = Long.parseLong(line.substring(spaceIdx + 1).trim());
                        baseFreq = (int) Math.max(2, Math.min(5000, (long) (Math.sqrt(rawCount) / 2.0)));
                    } catch (NumberFormatException ignored) {
                        baseFreq = 2;
                    }
                } else {
                    wordPart = line.replace("\u200B", "").trim();
                    baseFreq = Math.max(2, 120 - (lineIndex / 500));
                }

                String normalized = textProcessor.normalizeText(wordPart);
                if (normalized == null) continue;
                String clean = normalized.trim();
                if (clean.isEmpty()) continue;

                String lower = fastLower(clean);
                WordEntry existing = target.get(lower);
                if (existing == null || baseFreq > existing.baseFreq) {
                    target.put(lower, new WordEntry(clean, lower, baseFreq));
                }
                lineIndex++;
            }
        } catch (Exception ignored) {
            // Asset file optional if not bundled
        }
    }

    public void saveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;

        String cleaned = word.replace("\u200B", "").trim();
        final String normalizedWord = textProcessor.normalizeText(cleaned);
        if (normalizedWord == null || normalizedWord.trim().isEmpty()) return;

        final String finalWord = normalizedWord.trim();
        final String lower = fastLower(finalWord);

        // O(1) RAM update - no list copying or sorting
        Integer prev = userBoostMap.get(finalWord);
        final int updatedScore = (prev == null ? 20 : prev + 10);
        userBoostMap.put(finalWord, updatedScore);
        userLowerMap.put(finalWord, lower);

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
        if (normalizedPrefix == null) return results;
        String trimmed = normalizedPrefix.trim();
        if (trimmed.isEmpty()) return results;

        final String lowerPrefix = fastLower(trimmed);
        final char firstChar = lowerPrefix.charAt(0);

        String[] topWords = new String[3];
        int[] topScores = new int[]{-1, -1, -1};

        // 1. Binary search inside matching first-character bucket (zero String allocation)
        List<WordEntry> bucket = buckets.get(firstChar);
        if (bucket != null && !bucket.isEmpty()) {
            int lo = 0;
            int hi = bucket.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (bucket.get(mid).lower.compareTo(lowerPrefix) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }

            for (int i = lo; i < bucket.size(); i++) {
                WordEntry entry = bucket.get(i);
                if (!entry.lower.startsWith(lowerPrefix)) {
                    break;
                }
                Integer boost = userBoostMap.get(entry.word);
                int score = (boost != null) ? (entry.baseFreq + boost) : entry.baseFreq;
                insertTop3(entry.word, score, topWords, topScores);
            }
        }

        // 2. Check newly learned user words
        for (Map.Entry<String, String> e : userLowerMap.entrySet()) {
            if (e.getValue().startsWith(lowerPrefix)) {
                String w = e.getKey();
                Integer boost = userBoostMap.get(w);
                int score = (boost != null) ? boost : 20;
                insertTop3(w, score, topWords, topScores);
            }
        }

        for (int i = 0; i < 3; i++) {
            if (topWords[i] != null) results.add(topWords[i]);
        }
        return results;
    }

    private static void insertTop3(String word, int score, String[] topWords, int[] topScores) {
        // Avoid duplicates if word exists in both static bucket and userLowerMap
        for (int i = 0; i < 3; i++) {
            if (word.equals(topWords[i])) {
                if (score > topScores[i]) {
                    topScores[i] = score;
                    // Re-bubble upward if score increased
                    for (int j = i; j > 0 && isBetter(topWords[j], topScores[j], topWords[j - 1], topScores[j - 1]); j--) {
                        String tmpW = topWords[j - 1];
                        int tmpS = topScores[j - 1];
                        topWords[j - 1] = topWords[j];
                        topScores[j - 1] = topScores[j];
                        topWords[j] = tmpW;
                        topScores[j] = tmpS;
                    }
                }
                return;
            }
        }

        for (int i = 0; i < 3; i++) {
            if (topWords[i] == null || isBetter(word, score, topWords[i], topScores[i])) {
                for (int j = 2; j > i; j--) {
                    topWords[j] = topWords[j - 1];
                    topScores[j] = topScores[j - 1];
                }
                topWords[i] = word;
                topScores[i] = score;
                return;
            }
        }
    }

    private static boolean isBetter(String wordA, int scoreA, String wordB, int scoreB) {
        if (scoreA != scoreB) return scoreA > scoreB;
        if (wordA.length() != wordB.length()) return wordA.length() < wordB.length();
        return wordA.compareToIgnoreCase(wordB) < 0;
    }
}

