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
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        String shanPkg = prefs.getString("pref_engine_shan", defaultShanPkg);
        String burmesePkg = prefs.getString("pref_engine_myanmar", defaultBurmesePkg);
        String englishPkg = prefs.getString("pref_engine_english", defaultEnglishPkg);

        shanEngine = new RemoteTextToSpeech(getApplicationContext(), shanPkg);
        burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), burmesePkg);
        englishEngine = new RemoteTextToSpeech(getApplicationContext(), englishPkg);
    }

    @Override
    public void onDestroy() {
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
        super.onDestroy();
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

        int requestRate = request.getSpeechRate();
        int requestPitch = request.getPitch();

        callback.start(16000, android.media.AudioFormat.ENCODING_PCM_16BIT, 1);

        for (TTSUtils.Chunk chunk : chunks) {
            if (stopRequested) break;
            if (chunk.text == null || chunk.text.trim().isEmpty()) continue;

            RemoteTextToSpeech engine;
            if ("SHAN".equals(chunk.lang)) {
                engine = shanEngine;
            } else if ("MYANMAR".equals(chunk.lang)) {
                engine = burmeseEngine;
            } else {
                engine = englishEngine;
            }

            if (engine == null || !engine.isReady()) continue;

            engine.setSpeechRate(requestRate / 100.0f);
            engine.setPitch(requestPitch / 100.0f);

            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);

            Bundle requestParams = request.getParams();
            if (requestParams != null) {
                String streamKey = TextToSpeech.Engine.KEY_PARAM_STREAM;
                if (requestParams.containsKey(streamKey)) {
                    params.putString(streamKey, requestParams.getString(streamKey));
                }
            }

            engine.speakAndWait(chunk.text, params);

            try {
                if (!stopRequested) Thread.sleep(20);
            } catch (InterruptedException e) {
                break;
            }
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

