package com.cherry.sme.tts;

import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
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

    private volatile boolean stopRequested = false;
    private String mLanguage = "eng";
    private String mCountry = "";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        initEnginesStepByStep();
    }

    private void initEnginesStepByStep() {
        String sysDefault = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        
        String shanPkg = prefs.getString("pref_engine_shan", sysDefault);
        shanEngine = new RemoteTextToSpeech(this, status -> initBurmeseEngine(sysDefault), shanPkg);
    }

    private void initBurmeseEngine(String sysDefault) {
        String burmesePkg = prefs.getString("pref_engine_myanmar", sysDefault);
        burmeseEngine = new RemoteTextToSpeech(this, status -> initEnglishEngine(sysDefault), burmesePkg);
    }

    private void initEnglishEngine(String sysDefault) {
        String englishPkg = prefs.getString("pref_engine_english", sysDefault);
        englishEngine = new RemoteTextToSpeech(this, status -> {}, englishPkg);
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

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        float userRate = request.getSpeechRate() / 100.0f;
        float userPitch = request.getPitch() / 100.0f;
        Bundle originalParams = request.getParams();

        for (int i = 0; i < chunks.size(); i++) {
            if (stopRequested) break;
            TTSUtils.Chunk chunk = chunks.get(i);
            if (chunk.text.trim().isEmpty()) continue;

            RemoteTextToSpeech engine;
            if (chunk.lang.equals("SHAN")) engine = shanEngine;
            else if (chunk.lang.equals("MYANMAR")) engine = burmeseEngine;
            else engine = englishEngine;

            if (engine == null) continue;

            engine.setSpeechRate(userRate);
            engine.setPitch(userPitch);

            Bundle params = new Bundle(originalParams);
            String uId = "CH_" + System.currentTimeMillis() + "_" + i;
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

            try {
                Thread.sleep(15);
                engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, uId);

                int startWait = 0;
                while (!engine.isSpeaking() && startWait < 150 && !stopRequested) {
                    Thread.sleep(10);
                    startWait++;
                }
                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(10);
                }
                if (!stopRequested) {
                    Thread.sleep(35);
                }
            } catch (Exception e) {
                break;
            }
        }
        callback.done();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED;

        Locale locale = new Locale(lang, country, variant);
        int result = TextToSpeech.LANG_NOT_SUPPORTED;

        try {
            if (lang.equalsIgnoreCase("shn") || lang.toLowerCase().contains("shan")) {
                if (shanEngine != null) {
                    result = shanEngine.isLanguageAvailable(locale);
                }
            }
            else if (lang.equalsIgnoreCase("my") || lang.equalsIgnoreCase("mya")) {
                if (burmeseEngine != null) {
                    result = burmeseEngine.isLanguageAvailable(locale);
                }
            }
            else {
                if (englishEngine != null) {
                    result = englishEngine.isLanguageAvailable(locale);
                }
            }
        } catch (Exception e) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }

        return result;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{mLanguage, mCountry, mVariant};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int result = onIsLanguageAvailable(lang, country, variant);
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            mLanguage = lang;
            mCountry = country;
            mVariant = variant;
        }
        return result;
    }
}

