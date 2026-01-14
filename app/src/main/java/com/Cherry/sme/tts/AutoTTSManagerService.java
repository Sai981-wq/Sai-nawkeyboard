package com.cherry.sme.tts;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private boolean isRestarting = false;
    private Handler mainHandler;

    private volatile boolean stopRequested = false;
    private String mLanguage = "eng";
    private String mCountry = "USA";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mainHandler = new Handler(Looper.getMainLooper());

        initEnginesStepByStep();
    }

    private synchronized void initEnginesStepByStep() {
        if (isRestarting) return;

        String shanPkg = getBestEngine("pref_engine_shan");
        shutdownEngines();

        shanEngine = new RemoteTextToSpeech(this, status -> initBurmeseEngine(), shanPkg);
    }

    private void initBurmeseEngine() {
        String burmesePkg = getBestEngine("pref_engine_myanmar");
        burmeseEngine = new RemoteTextToSpeech(this, status -> initEnglishEngine(), burmesePkg);
    }

    private void initEnglishEngine() {
        String englishPkg = getBestEngine("pref_engine_english");
        englishEngine = new RemoteTextToSpeech(this, status -> {
            isRestarting = false;
        }, englishPkg);
    }

    private void triggerWatchdogRestart() {
        if (!isRestarting) {
            isRestarting = true;
            mainHandler.post(this::initEnginesStepByStep);
        }
    }

    private String getBestEngine(String prefKey) {
        String pkg = prefs.getString(prefKey, null);
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) {
            return pkg;
        }
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        if (sysDef != null && !sysDef.equals(getPackageName())) {
            return sysDef;
        }
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo info : services) {
                String p = info.serviceInfo.packageName;
                if (!p.equals(getPackageName())) return p;
            }
        } catch (Exception e) {}
        return "com.google.android.tts";
    }

    private void shutdownEngines() {
        if (shanEngine != null) {
            try { shanEngine.shutdown(); } catch (Exception e) {}
        }
        if (burmeseEngine != null) {
            try { burmeseEngine.shutdown(); } catch (Exception e) {}
        }
        if (englishEngine != null) {
            try { englishEngine.shutdown(); } catch (Exception e) {}
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdownEngines();
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

        if (isRestarting) {
            callback.error();
            return;
        }

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

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested) break;
                TTSUtils.Chunk chunk = chunks.get(i);
                
                if (chunk.text.trim().length() < 1) continue;

                RemoteTextToSpeech engine;
                if (chunk.lang.equals("SHAN")) engine = shanEngine;
                else if (chunk.lang.equals("MYANMAR")) engine = burmeseEngine;
                else engine = englishEngine;

                if (engine == null) {
                    triggerWatchdogRestart();
                    break;
                }

                engine.setSpeechRate(userRate);
                engine.setPitch(userPitch);

                Bundle params = new Bundle(originalParams);
                String uId = "CH_" + System.currentTimeMillis() + "_" + i;
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

                try {
                    engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, uId);

                    int startWait = 0;
                    while (!engine.isSpeaking() && startWait < 500 && !stopRequested) {
                        Thread.sleep(10);
                        startWait++;
                    }

                    while (!stopRequested) {
                        if (!engine.isSpeaking()) {
                            Thread.sleep(25);
                            if (!engine.isSpeaking()) break;
                        }
                        Thread.sleep(25);
                    }

                    if (!stopRequested) {
                        Thread.sleep(0);
                    }

                } catch (Exception e) {
                    triggerWatchdogRestart();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            callback.done();
        }
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        mLanguage = "eng";
        mCountry = "USA";
        mVariant = "";
        return TextToSpeech.LANG_AVAILABLE;
    }
}
