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
import android.speech.tts.UtteranceProgressListener;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;

    private boolean isRestarting = false;
    private Handler mainHandler;

    private volatile CountDownLatch currentLatch;
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
        shutdownEngines();

        String shanPkg = getBestEngine("pref_engine_shan");
        shanEngine = new RemoteTextToSpeech(this, status -> {
            setupEngineListener(shanEngine); 
            initBurmeseEngine();
        }, shanPkg);
    }

    private void initBurmeseEngine() {
        String burmesePkg = getBestEngine("pref_engine_myanmar");
        burmeseEngine = new RemoteTextToSpeech(this, status -> {
            setupEngineListener(burmeseEngine);
            initEnglishEngine();
        }, burmesePkg);
    }

    private void initEnglishEngine() {
        String englishPkg = getBestEngine("pref_engine_english");
        englishEngine = new RemoteTextToSpeech(this, status -> {
            setupEngineListener(englishEngine);
            isRestarting = false;
        }, englishPkg);
    }

    private void setupEngineListener(RemoteTextToSpeech engine) {
        if (engine == null) return;
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                if (currentLatch != null) {
                    currentLatch.countDown();
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (currentLatch != null) {
                    currentLatch.countDown();
                }
            }
        });
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
        Bundle originalParams = request.getParams();
        float userRate = request.getSpeechRate() / 100.0f;
        float userPitch = request.getPitch() / 100.0f;

        try {
            StringBuilder batchText = new StringBuilder();
            RemoteTextToSpeech currentEngine = null;

            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested) break;
                
                TTSUtils.Chunk chunk = chunks.get(i);
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine;
                if (chunk.lang.equals("SHAN")) targetEngine = shanEngine;
                else if (chunk.lang.equals("MYANMAR")) targetEngine = burmeseEngine;
                else targetEngine = englishEngine;

                if (targetEngine == null) continue;

                targetEngine.setSpeechRate(userRate);
                targetEngine.setPitch(userPitch);

                boolean isEngineChanged = (currentEngine != null && targetEngine != currentEngine);
                
                if (isEngineChanged && batchText.length() > 0) {
                    speakAndWait(currentEngine, batchText.toString(), originalParams);
                    batchText.setLength(0);
                }

                batchText.append(chunk.text).append(" ");
                currentEngine = targetEngine;
            }

            if (batchText.length() > 0 && currentEngine != null && !stopRequested) {
                speakAndWait(currentEngine, batchText.toString(), originalParams);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            callback.done();
        }
    }

    private void speakAndWait(RemoteTextToSpeech engine, String text, Bundle params) {
        if (stopRequested) return;

        currentLatch = new CountDownLatch(1);
        String utteranceId = "ID_" + System.currentTimeMillis();

        engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId);

        try {
            currentLatch.await(6000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
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
        if (currentLatch != null) {
            currentLatch.countDown();
        }
        if (shanEngine != null) shanEngine.stop();
        if (burmeseEngine != null) burmeseEngine.stop();
        if (englishEngine != null) englishEngine.stop();
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

