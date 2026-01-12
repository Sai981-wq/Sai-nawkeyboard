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
    public static final String[] SUPPORTED_LANGUAGES = {"eng-USA", "mya-MMR", "shn-MMR"};

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
        initEnginesStepByStep();
    }

    private void initEnginesStepByStep() {
        String shanPkg = prefs.getString("pref_engine_shan", "com.espeak.ng");
        shanEngine = new RemoteTextToSpeech(this, status -> {
            Log.d(TAG, "Shan Engine Ready: " + status);
            initBurmeseEngine();
        }, shanPkg);
    }

    private void initBurmeseEngine() {
        String burmesePkg = prefs.getString("pref_engine_myanmar", "org.saomaicenter.myanmartts");
        burmeseEngine = new RemoteTextToSpeech(this, status -> {
            Log.d(TAG, "Burmese Engine Ready: " + status);
            initEnglishEngine();
        }, burmesePkg);
    }

    private void initEnglishEngine() {
        String englishPkg = prefs.getString("pref_engine_english", "com.google.android.tts");
        englishEngine = new RemoteTextToSpeech(this, status -> {
            Log.d(TAG, "English Engine Ready: " + status);
        }, englishPkg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (shanEngine != null) shanEngine.shutdown();
            if (burmeseEngine != null) burmeseEngine.shutdown();
            if (englishEngine != null) englishEngine.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Shutdown Error: " + e.getMessage());
        }
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

            if (engine == null) continue;

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

                while (engine.isSpeaking()) {
                    Thread.sleep(10);
                }
                Thread.sleep(35);

            } catch (Exception e) {
                Log.e(TAG, "Synthesis Loop Error: " + e.getMessage());
                break;
            }
        }
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        Locale locale = new Locale(lang, country, variant);

        try {
            if (shanEngine != null && shanEngine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE;
            }
            if (burmeseEngine != null && burmeseEngine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE;
            }
            if (englishEngine != null && englishEngine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                return TextToSpeech.LANG_COUNTRY_AVAILABLE;
            }
        } catch (Exception e) {
            Log.e(TAG, "Availability Check Error: " + e.getMessage());
        }
        return TextToSpeech.LANG_NOT_SUPPORTED;
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
        return onIsLanguageAvailable(lang, country, variant);
    }
}

