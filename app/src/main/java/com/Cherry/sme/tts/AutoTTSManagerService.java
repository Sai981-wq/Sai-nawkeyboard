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
        Bundle requestParams = request.getParams();

        for (TTSUtils.Chunk chunk : chunks) {
            if (stopRequested) break;
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

            Bundle params = new Bundle();
            if (requestParams != null && requestParams.containsKey(TextToSpeech.Engine.KEY_PARAM_STREAM)) {
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, requestParams.getInt(TextToSpeech.Engine.KEY_PARAM_STREAM));
            } else {
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, 10);
            }
            
            String utteranceId = "CHERRY_" + System.currentTimeMillis();
            
            try {
                engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);

                int startWait = 0;
                while (!engine.isSpeaking() && startWait < 50 && !stopRequested) {
                    Thread.sleep(5);
                    startWait++;
                }

                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(2);
                }

                if (!stopRequested) {
                    Thread.sleep(40);
                }

            } catch (InterruptedException e) {
                break;
            }
        }
        
        if (!stopRequested) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {}
        }
        
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
}

