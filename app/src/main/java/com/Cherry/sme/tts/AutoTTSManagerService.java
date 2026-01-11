package com.cherry.sme.tts;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import java.util.HashMap;
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

            HashMap<String, String> params = new HashMap<>();
            if (requestParams != null) {
                for (String key : requestParams.keySet()) {
                    params.put(key, String.valueOf(requestParams.get(key)));
                }
            }
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ID_" + System.currentTimeMillis());

            // အင်္ဂလိပ် Engine မဝင်ခင် မြန်မာ Engine အလုပ်ပြီးမြောက်အောင် Strict Wait ပြုလုပ်သည်
            try {
                // အင်ဂျင်အကူးအပြောင်း Latency အတွက် အနည်းငယ်စောင့်သည်
                Thread.sleep(10);
                
                engine.speakWithCallback(chunk.text, TextToSpeech.QUEUE_FLUSH, params, callback);

                // ၁။ အင်ဂျင်က အသံစထွက်ကြောင်း (onStart) အချက်ပြသည်အထိ သေချာပေါက်စောင့်သည်
                int startTimeout = 0;
                while (!engine.isSpeaking() && startTimeout < 100 && !stopRequested) {
                    Thread.sleep(10);
                    startTimeout++;
                }

                // ၂။ အင်ဂျင်က အသံထွက်ပြီးကြောင်း (onDone) အချက်ပြသည်အထိ ဆက်လက်စောင့်သည်
                while (engine.isSpeaking() && !stopRequested) {
                    Thread.sleep(5);
                }

                // ၃။ Audio Buffer ကုန်ဆုံးစေရန်နှင့် Engine အချင်းချင်း မတိုက်မိစေရန် Delay ထည့်သည်
                if (!stopRequested) {
                    Thread.sleep(20);
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
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
}

