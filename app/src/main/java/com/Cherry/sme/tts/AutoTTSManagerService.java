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

    private String mLanguage = "eng";
    private String mCountry = "USA";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        LogCollector.addLog("Service", "Created");
        initEnginesStepByStep();
    }

    private void initEnginesStepByStep() {
        String shanPkg = prefs.getString("pref_engine_shan", "com.espeak.ng");
        shanEngine = new RemoteTextToSpeech(this, status -> {
            LogCollector.addLog("ShanInit", "Status: " + status);
            initBurmeseEngine();
        }, shanPkg);
    }

    private void initBurmeseEngine() {
        String burmesePkg = prefs.getString("pref_engine_myanmar", "org.saomaicenter.myanmartts");
        burmeseEngine = new RemoteTextToSpeech(this, status -> {
            LogCollector.addLog("BurmeseInit", "Status: " + status);
            initEnglishEngine();
        }, burmesePkg);
    }

    private void initEnglishEngine() {
        String englishPkg = prefs.getString("pref_engine_english", "com.google.android.tts");
        englishEngine = new RemoteTextToSpeech(this, status -> {
            LogCollector.addLog("EnglishInit", "Status: " + status);
        }, englishPkg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
    }

    @Override
    protected void onStop() {
        if (shanEngine != null) shanEngine.stop();
        if (burmeseEngine != null) burmeseEngine.stop();
        if (englishEngine != null) englishEngine.stop();
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
            RemoteTextToSpeech engine = chunk.lang.equals("SHAN") ? shanEngine : 
                                      (chunk.lang.equals("MYANMAR") ? burmeseEngine : englishEngine);

            if (engine == null) {
                LogCollector.addLog("Synthesize", "Engine NULL for: " + chunk.lang);
                continue;
            }

            Bundle params = new Bundle(originalParams);
            String uId = "CH_" + System.currentTimeMillis() + "_" + i;
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

            try {
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                engine.speak(chunk.text, mode, params, uId);

                int wait = 0;
                while (!engine.isSpeaking() && wait < 150) {
                    Thread.sleep(10);
                    wait++;
                }
                while (engine.isSpeaking()) { Thread.sleep(10); }
                Thread.sleep(30);

            } catch (Exception e) {
                LogCollector.addLog("Error", "Synthesis: " + e.getMessage());
                break;
            }
        }
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        Locale locale = new Locale(lang, country, variant);
        LogCollector.addLog("CheckLang", lang);

        try {
            if (shanEngine != null && shanEngine.isLanguageAvailable(locale) >= 0) return 2;
            if (burmeseEngine != null && burmeseEngine.isLanguageAvailable(locale) >= 0) return 2;
            if (englishEngine != null && englishEngine.isLanguageAvailable(locale) >= 0) return 2;
        } catch (Exception e) {
            LogCollector.addLog("CrashGuard", e.getMessage());
        }
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

