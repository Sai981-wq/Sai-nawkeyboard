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
    private static final String TABLE_WORDS = "words";
    private static final String COLUMN_WORD = "word";
    private static final String COLUMN_FREQ = "frequency";

    public SuggestionDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
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
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.query(TABLE_WORDS, null, COLUMN_WORD + "=?", new String[]{word}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int currentFreq = cursor.getInt(cursor.getColumnIndex(COLUMN_FREQ));
            ContentValues values = new ContentValues();
            values.put(COLUMN_FREQ, currentFreq + 1);
            db.update(TABLE_WORDS, values, COLUMN_WORD + "=?", new String[]{word});
            cursor.close();
        } else {
            ContentValues values = new ContentValues();
            values.put(COLUMN_WORD, word);
            values.put(COLUMN_FREQ, 1);
            db.insert(TABLE_WORDS, null, values);
            if(cursor != null) cursor.close();
        }
    }

    public List<String> getSuggestions(String prefix) {
        List<String> suggestions = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return suggestions;
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_WORDS, new String[]{COLUMN_WORD}, 
                COLUMN_WORD + " LIKE ?", new String[]{prefix + "%"}, 
                null, null, COLUMN_FREQ + " DESC", "5");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                suggestions.add(cursor.getString(0));
            }
            cursor.close();
        }
        return suggestions;
    }
}
