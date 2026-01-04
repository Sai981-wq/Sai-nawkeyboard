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
    
    // Default Engine များ (User မရွေးရသေးခင် ယာယီသုံးရန်)
    private String defaultShanPkg = "com.shan.tts";
    private String defaultBurmesePkg = "org.saomaicenter.myanmartts";
    private String defaultEnglishPkg = "com.google.android.tts";

    private volatile boolean stopRequested = false;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        // Settings မှ User ရွေးထားသော Engine များကို ဖတ်ခြင်း
        // "pref_engine_shan", "pref_engine_myanmar" စသည်တို့သည် SettingsActivity တွင် Key ပေးရမည့် နာမည်များဖြစ်သည်
        String shanPkg = prefs.getString("pref_engine_shan", defaultShanPkg);
        String burmesePkg = prefs.getString("pref_engine_myanmar", defaultBurmesePkg);
        String englishPkg = prefs.getString("pref_engine_english", defaultEnglishPkg);

        // User ရွေးထားသော Engine များဖြင့် Initialize လုပ်ခြင်း
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
        
        // စာကြောင်းခွဲခြင်း (Logic မပြောင်းပါ)
        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);

        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        for (TTSUtils.Chunk chunk : chunks) {
            if (stopRequested) break;
            if (chunk.text.trim().isEmpty()) continue;

            RemoteTextToSpeech engine;

            // ဘာသာစကားအလိုက် Engine ရွေးချယ်ခြင်း
            if (chunk.lang.equals("SHAN")) {
                engine = shanEngine;
            } else if (chunk.lang.equals("MYANMAR")) {
                engine = burmeseEngine;
            } else {
                engine = englishEngine;
            }

            // Engine မရှိလျှင် (သို့) ပျက်နေလျှင် ကျော်သွားမည်
            if (engine == null) continue;

            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            
            String utteranceId = "ID_" + System.currentTimeMillis();
            engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);

            // Blocking Loop (Proxy Method)
            try {
                Thread.sleep(20);
                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(50);
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
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
}

