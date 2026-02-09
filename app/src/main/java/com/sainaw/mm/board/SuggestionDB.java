package com.sainaw.mm.board;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class SuggestionDB extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "sainaw_dict.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_SUGGESTIONS = "suggestions";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQUENCY = "frequency";

    private final SaiNawTextProcessor textProcessor;

    public SuggestionDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.textProcessor = new SaiNawTextProcessor();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_SUGGESTIONS + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_WORD + " TEXT UNIQUE,"
                + COLUMN_FREQUENCY + " INTEGER DEFAULT 1" + ")";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SUGGESTIONS);
        onCreate(db);
    }

    public void insertWord(String word) {
        if (word == null || word.trim().isEmpty()) return;

        String normalizedWord = textProcessor.normalizeText(word);
        if (normalizedWord == null || normalizedWord.trim().isEmpty()) return;

        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = null;

        try {
            cursor = db.query(TABLE_SUGGESTIONS, new String[]{COLUMN_FREQUENCY},
                    COLUMN_WORD + "=?", new String[]{normalizedWord},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                int currentFreq = cursor.getInt(0);
                ContentValues values = new ContentValues();
                values.put(COLUMN_FREQUENCY, currentFreq + 1);
                db.update(TABLE_SUGGESTIONS, values, COLUMN_WORD + "=?", new String[]{normalizedWord});
            } else {
                ContentValues values = new ContentValues();
                values.put(COLUMN_WORD, normalizedWord);
                values.put(COLUMN_FREQUENCY, 1);
                db.insert(TABLE_SUGGESTIONS, null, values);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public List<String> getSuggestions(String input) {
        List<String> suggestions = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) return suggestions;

        String normalizedInput = textProcessor.normalizeText(input);
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;

        try {
            cursor = db.query(TABLE_SUGGESTIONS, new String[]{COLUMN_WORD},
                    COLUMN_WORD + " LIKE ?", new String[]{normalizedInput + "%"},
                    null, null, COLUMN_FREQUENCY + " DESC", "5");

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    suggestions.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return suggestions;
    }

    public void deleteWord(String word) {
        if (word == null) return;
        String normalizedWord = textProcessor.normalizeText(word);
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_SUGGESTIONS, COLUMN_WORD + "=?", new String[]{normalizedWord});
    }
}

