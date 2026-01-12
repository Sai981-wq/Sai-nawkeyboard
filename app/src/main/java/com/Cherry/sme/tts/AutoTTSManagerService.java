package com.cherry.sme.tts;

import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;
import java.util.List;
import java.util.Locale;

public class AutoTTSManagerService extends TextToSpeechService {

    private static final String TAG = "CherryTTS";
    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;

    private String mLanguage = "eng";
    private String mCountry = "USA";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initEngines();
    }

    private void initEngines() {
        shanEngine = new RemoteTextToSpeech(this, s -> initBurmese(), prefs.getString("pref_engine_shan", "com.espeak.ng"));
    }
    private void initBurmese() {
        burmeseEngine = new RemoteTextToSpeech(this, s -> initEnglish(), prefs.getString("pref_engine_myanmar", "org.saomaicenter.myanmartts"));
    }
    private void initEnglish() {
        englishEngine = new RemoteTextToSpeech(this, s -> {}, prefs.getString("pref_engine_english", "com.google.android.tts"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();
        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);
        if (chunks.isEmpty()) { callback.done(); return; }

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        Bundle originalParams = request.getParams();

        for (int i = 0; i < chunks.size(); i++) {
            TTSUtils.Chunk chunk = chunks.get(i);
            RemoteTextToSpeech engine = chunk.lang.equals("SHAN") ? shanEngine : (chunk.lang.equals("MYANMAR") ? burmeseEngine : englishEngine);
            if (engine == null) continue;

            Bundle params = new Bundle(originalParams);
            String uId = "CH_" + System.currentTimeMillis() + "_" + i;
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

            try {
                engine.speak(chunk.text, (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, uId);
                int wait = 0;
                while (!engine.isSpeaking() && wait++ < 100) Thread.sleep(10);
                while (engine.isSpeaking()) Thread.sleep(10);
                Thread.sleep(30);
            } catch (Exception e) { Log.e(TAG, "Sync Error: " + e.getMessage()); }
        }
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        Locale locale = new Locale(lang, country, variant);
        try {
            if (shanEngine != null && shanEngine.isLanguageAvailable(locale) >= 0) return 2;
            if (burmeseEngine != null && burmeseEngine.isLanguageAvailable(locale) >= 0) return 2;
            if (englishEngine != null && englishEngine.isLanguageAvailable(locale) >= 0) return 2;
        } catch (Exception e) {}
        return -2;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{mLanguage, mCountry, mVariant};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        mLanguage = lang; mCountry = country; mVariant = variant;
        return onIsLanguageAvailable(lang, country, variant);
    }
}

