package com.cherry.sme.tts;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import java.util.List;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;

    private String defaultShanPkg = "com.shan.tts";
    private String defaultBurmesePkg = "org.saomaicenter.myanmartts";
    private String defaultEnglishPkg = "com.google.android.tts";

    private volatile boolean stopRequested = false;
    private SharedPreferences prefs;

    private String mLanguage = "eng";
    private String mCountry = "USA";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String shanPkg = prefs.getString("pref_engine_shan", defaultShanPkg);
        String burmesePkg = prefs.getString("pref_engine_myanmar", defaultBurmesePkg);
        String englishPkg = prefs.getString("pref_engine_english", defaultEnglishPkg);

        shanEngine = new RemoteTextToSpeech(this, shanPkg);
        burmeseEngine = new RemoteTextToSpeech(this, burmesePkg);
        englishEngine = new RemoteTextToSpeech(this, englishPkg);
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
        stopRequested = true;
        if (shanEngine != null) shanEngine.stop();
        if (burmeseEngine != null) burmeseEngine.stop();
        if (englishEngine != null) englishEngine.stop();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        String text = request.getText();
        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);

        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        float userRate = request.getSpeechRate() / 100.0f;
        float userPitch = request.getPitch() / 100.0f;
        Bundle originalParams = request.getParams();

        for (int i = 0; i < chunks.size(); i++) {
            if (stopRequested) break;
            TTSUtils.Chunk chunk = chunks.get(i);
            if (chunk.text.trim().isEmpty()) continue;

            RemoteTextToSpeech engine;
            if (chunk.lang.equals("SHAN")) {
                engine = shanEngine;
            } else if (chunk.lang.equals("MYANMAR")) {
                engine = burmeseEngine;
            } else {
                engine = englishEngine;
            }

            if (engine == null) continue;

            engine.setSpeechRate(userRate);
            engine.setPitch(userPitch);

            Bundle params = new Bundle(originalParams);
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "CH_" + System.currentTimeMillis() + "_" + i);

            try {
                Thread.sleep(15);
                
                engine.speakWithCallback(chunk.text, TextToSpeech.QUEUE_FLUSH, params, callback);

                int startTimeout = 0;
                while (!engine.isSpeaking() && startTimeout < 100 && !stopRequested) {
                    Thread.sleep(10);
                    startTimeout++;
                }

                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(5);
                }

                if (!stopRequested) {
                    Thread.sleep(30);
                }

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{mLanguage, mCountry, mVariant};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        mLanguage = lang;
        mCountry = country;
        mVariant = variant;
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
}

