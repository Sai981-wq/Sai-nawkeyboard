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
        LogCollector.addLog("Lifecycle", "Service Created");
        initEngines();
    }

    private void initEngines() {
        String sPkg = prefs.getString("pref_engine_shan", "com.espeak.ng");
        String bPkg = prefs.getString("pref_engine_myanmar", "org.saomaicenter.myanmartts");
        String ePkg = prefs.getString("pref_engine_english", "com.google.android.tts");

        shanEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "Shan Status: " + status), sPkg);
        burmeseEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "Burmese Status: " + status), bPkg);
        englishEngine = new RemoteTextToSpeech(this, status -> LogCollector.addLog("Init", "English Status: " + status), ePkg);
    }

    @Override
    protected void onStop() {
        LogCollector.addLog("Lifecycle", "onStop called - Stopping all engines");
        if (shanEngine != null) { shanEngine.stop(); shanEngine.forceOpen(); }
        if (burmeseEngine != null) { burmeseEngine.stop(); burmeseEngine.forceOpen(); }
        if (englishEngine != null) { englishEngine.stop(); englishEngine.forceOpen(); }
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();
        LogCollector.addLog("Synthesize", "Incoming system request: " + text);

        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);
        if (chunks.isEmpty()) { 
            callback.done(); 
            return; 
        }

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        Bundle originalParams = request.getParams();

        for (int i = 0; i < chunks.size(); i++) {
            TTSUtils.Chunk chunk = chunks.get(i);
            RemoteTextToSpeech engine = chunk.lang.equals("SHAN") ? shanEngine : 
                                      (chunk.lang.equals("MYANMAR") ? burmeseEngine : englishEngine);

            if (engine == null) {
                LogCollector.addLog("Error", "Engine is NULL for lang: " + chunk.lang);
                continue;
            }

            LogCollector.addLog("Process", "Chunk [" + i + "]: " + chunk.text + " using " + engine.getEngineName());
            
            engine.forceOpen(); // Sync အဟောင်းများ ရှင်းထုတ်ခြင်း
            Bundle params = new Bundle(originalParams);
            String uId = "CH_" + System.currentTimeMillis() + "_" + i;
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

            int speakResult = engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, uId);
            LogCollector.addLog("Process", "speak() result: " + speakResult);

            // အင်ဂျင် ပြီးသည်အထိ စောင့်ဆိုင်းခြင်း
            engine.waitForCompletion(chunk.text);
        }

        LogCollector.addLog("Synthesize", "Batch processing complete");
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return -2;
        Locale locale = new Locale(lang, country, variant);
        try {
            if (shanEngine != null && lang.equalsIgnoreCase("shn")) return shanEngine.isLanguageAvailable(locale);
            if (burmeseEngine != null && (lang.equalsIgnoreCase("mya") || lang.equalsIgnoreCase("my"))) return burmeseEngine.isLanguageAvailable(locale);
            if (englishEngine != null && (lang.equalsIgnoreCase("eng") || lang.equalsIgnoreCase("en"))) return englishEngine.isLanguageAvailable(locale);
        } catch (Exception e) {}
        return -2;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
    }
}

