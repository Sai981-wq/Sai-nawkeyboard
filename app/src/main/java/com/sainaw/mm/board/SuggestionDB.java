package com.sainaw.mm.board;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import java.util.ArrayList;
import java.util.List;

public class SuggestionDB extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "sainaw_dict.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_WORDS = "words";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQ = "frequency";

    private static SuggestionDB instance;
    private final SaiNawTextProcessor textProcessor;

    public static synchronized SuggestionDB getInstance(Context context) {
        if (instance == null) {
            instance = new SuggestionDB(context.getApplicationContext());
        }
        return instance;
    }

    private SuggestionDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.textProcessor = new SaiNawTextProcessor();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_WORDS + "("
                + COLUMN_WORD + " TEXT PRIMARY KEY,"
                + COLUMN_FREQ + " INTEGER DEFAULT 1)";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORDS);
        onCreate(db);
    }

    public void saveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;

        String normalizedWord = textProcessor.normalizeText(word);
        if (normalizedWord == null || normalizedWord.trim().isEmpty()) return;

        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            String updateSql = "UPDATE " + TABLE_WORDS + " SET " + COLUMN_FREQ + " = " + COLUMN_FREQ + " + 1 WHERE " + COLUMN_WORD + " = ?";
            SQLiteStatement stmt = db.compileStatement(updateSql);
            stmt.bindString(1, normalizedWord);
            int rowsAffected = stmt.executeUpdateDelete();
            
            if (rowsAffected == 0) {
                ContentValues values = new ContentValues();
                values.put(COLUMN_WORD, normalizedWord);
                values.put(COLUMN_FREQ, 1);
                db.insert(TABLE_WORDS, null, values);
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

        String normalizedPrefix = textProcessor.normalizeText(prefix);
        if (normalizedPrefix == null || normalizedPrefix.trim().isEmpty()) return suggestions;

        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_WORDS, new String[]{COLUMN_WORD}, 
                    COLUMN_WORD + " LIKE ?", new String[]{normalizedPrefix + "%"}, 
                    null, null, COLUMN_FREQ + " DESC", "3");
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    suggestions.add(cursor.getString(0));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }
        return suggestions;
    }
}

