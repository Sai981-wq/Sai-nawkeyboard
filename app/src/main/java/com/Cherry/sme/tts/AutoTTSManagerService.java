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

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);

        float userRate = request.getSpeechRate() / 100.0f;
        float userPitch = request.getPitch() / 100.0f;
        Bundle requestParams = request.getParams();

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

            Bundle params = new Bundle();
            if (requestParams != null) {
                params.putAll(requestParams);
            }
            
            if (!params.containsKey(TextToSpeech.Engine.KEY_PARAM_STREAM)) {
                params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, "10");
            }

            String utteranceId = "ID_" + System.currentTimeMillis();
            
            // ပထမဆုံး Chunk ကို QUEUE_FLUSH သုံးပြီး အရင်အသံတွေကို ချက်ချင်းဖြတ်ခိုင်းသည်
            int queueMode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            engine.speak(chunk.text, queueMode, params, utteranceId);

            try {
                // အင်ဂျင်အကူးအပြောင်းတွင် TalkBack မပိတ်မိစေရန် အနုစိတ်ဆုံး စောင့်ဆိုင်းချိန်ကိုသာ သုံးသည်
                int startWait = 0;
                while (!engine.isSpeaking() && startWait < 15 && !stopRequested) {
                    Thread.sleep(10);
                    startWait++;
                }

                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(5);
                }
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

