package com.cherry.sme.tts;

import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import java.util.List;
import java.util.Locale;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        LogCollector.addLog("Lifecycle", "Service onCreate - Initializing Engines");
        initEngines();
    }

    private void initEngines() {
        String sPkg = prefs.getString("pref_engine_shan", "com.espeak.ng");
        String bPkg = prefs.getString("pref_engine_myanmar", "org.saomaicenter.myanmartts");
        String ePkg = prefs.getString("pref_engine_english", "com.google.android.tts");

        shanEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "Shan: " + status), sPkg);
        burmeseEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "Burmese: " + status), bPkg);
        englishEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "English: " + status), ePkg);
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();
        LogCollector.addLog("Synthesize", "Received system request: " + text);

        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);
        LogCollector.addLog("Splitter", "Chunks count: " + chunks.size());

        if (chunks.isEmpty()) {
            LogCollector.addLog("Synthesize", "No chunks to play");
            callback.done();
            return;
        }

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        Bundle originalParams = request.getParams();

        for (int i = 0; i < chunks.size(); i++) {
            TTSUtils.Chunk chunk = chunks.get(i);
            RemoteTextToSpeech engine = null;

            if (chunk.lang.equals("SHAN")) engine = shanEngine;
            else if (chunk.lang.equals("MYANMAR")) engine = burmeseEngine;
            else engine = englishEngine;

            if (engine == null) {
                LogCollector.addLog("Error", "Engine is NULL for lang: " + chunk.lang);
                continue;
            }

            LogCollector.addLog("Process", "Speaking chunk [" + i + "]: " + chunk.text + " with " + engine.getEngineName());
            
            engine.forceOpen(); // Reset sync state
            Bundle params = new Bundle(originalParams);
            String uId = "ID_" + System.currentTimeMillis();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

            int result = engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, uId);
            LogCollector.addLog("Process", "Engine speak() result: " + result);

            engine.waitForCompletion(chunk.text);
        }

        LogCollector.addLog("Synthesize", "All chunks processed. Sending callback.done()");
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        LogCollector.addLog("SystemCheck", "Checking lang: " + lang);
        if (lang == null) return -2;
        Locale locale = new Locale(lang, country, variant);
        try {
            if (shanEngine != null && lang.equalsIgnoreCase("shn")) return shanEngine.isLanguageAvailable(locale);
            if (burmeseEngine != null && (lang.equalsIgnoreCase("mya") || lang.equalsIgnoreCase("my"))) return burmeseEngine.isLanguageAvailable(locale);
            if (englishEngine != null && (lang.equalsIgnoreCase("eng") || lang.equalsIgnoreCase("en"))) return englishEngine.isLanguageAvailable(locale);
        } catch (Exception e) {
            LogCollector.addLog("CrashGuard", "Error in IsLanguageAvailable: " + e.getMessage());
        }
        return -2;
    }

    @Override
    protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) { return onIsLanguageAvailable(lang, country, variant); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogCollector.addLog("Lifecycle", "Service onDestroy");
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
    }
}

