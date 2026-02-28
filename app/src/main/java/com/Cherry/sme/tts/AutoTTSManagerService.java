package com.cherry.sme.tts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    
    private volatile boolean isShanReady = false;
    private volatile boolean isBurmeseReady = false;
    private volatile boolean isEnglishReady = false;

    private boolean isShanConfigured = false;
    private boolean isBurmeseConfigured = false;
    private boolean isEnglishConfigured = false;

    private SharedPreferences prefs;
    private volatile boolean stopRequested = false;
    private PowerManager.WakeLock wakeLock;
    
    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();

    private final UtteranceProgressListener globalListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {}

        @Override
        public void onDone(String utteranceId) {
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            releaseLatch(utteranceId);
        }

        private void releaseLatch(String utteranceId) {
            if (utteranceId != null) {
                CountDownLatch latch = utteranceLatches.remove(utteranceId);
                if (latch != null) {
                    while (latch.getCount() > 0) {
                        latch.countDown();
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        TTSUtils.loadMapping(this);
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "CherrySME::WakeLock");
        }
        
        initAllEngines();
    }

    private void initAllEngines() {
        shutdownEngines();
        
        isShanConfigured = false;
        isBurmeseConfigured = false;
        isEnglishConfigured = false;

        shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
            if(status == TextToSpeech.SUCCESS) isShanReady = true;
        }, getBestEngine("pref_engine_shan"));
        shanEngine.setOnUtteranceProgressListener(globalListener);
        
        burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
            if(status == TextToSpeech.SUCCESS) isBurmeseReady = true;
        }, getBestEngine("pref_engine_myanmar"));
        burmeseEngine.setOnUtteranceProgressListener(globalListener);

        englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
            if(status == TextToSpeech.SUCCESS) isEnglishReady = true;
        }, getBestEngine("pref_engine_english"));
        englishEngine.setOnUtteranceProgressListener(globalListener);
    }

    private void configureEngineIfNeeded(RemoteTextToSpeech engine, String lang) {
        try {
            if ("MYANMAR".equals(lang) && !isBurmeseConfigured) {
                int res = engine.setLanguage(new Locale("mya"));
                if (res < 0) res = engine.setLanguage(new Locale("mya", "MM"));
                if (res < 0) engine.setLanguage(new Locale("my"));
                
                try {
                    Set<Voice> voices = engine.getVoices();
                    if (voices != null) {
                        for (Voice v : voices) {
                            String vName = v.getName().toLowerCase();
                            if (vName.contains("my") || vName.contains("burmese") || vName.contains("mya")) {
                                engine.setVoice(v);
                                break;
                            }
                        }
                    }
                } catch (Exception ve) {}
                isBurmeseConfigured = true;

            } else if ("SHAN".equals(lang) && !isShanConfigured) {
                engine.setLanguage(new Locale("shn")); 
                isShanConfigured = true;

            } else if ("ENGLISH".equals(lang) && !isEnglishConfigured) {
                engine.setLanguage(Locale.US);
                isEnglishConfigured = true;
            }
        } catch (Exception e) {}
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
                wakeLock.acquire(10000);
            } catch (Exception e) {}
        }
        
        stopRequested = false;
        String text = request.getText();
        
        if (text == null || text.trim().isEmpty()) {
            callback.done();
            return;
        }

        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);
        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        Bundle params = new Bundle();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        
        params.putParcelable("audioAttributes", audioAttributes);

        float rate = request.getSpeechRate() / 100.0f;
        float pitch = request.getPitch() / 100.0f;

        try {
            for (int i = 0; i < chunks.size(); i++) {
                TTSUtils.Chunk chunk = chunks.get(i);
                
                if (stopRequested) break;
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) continue;

                if (!waitForEngine(chunk.lang)) {
                    continue;
                }

                configureEngineIfNeeded(targetEngine, chunk.lang);

                targetEngine.setSpeechRate(rate);
                targetEngine.setPitch(pitch);

                String utteranceId = String.valueOf(System.nanoTime());
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                
                CountDownLatch latch = new CountDownLatch(1);
                utteranceLatches.put(utteranceId, latch);

                int result = targetEngine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);

                if (result == TextToSpeech.ERROR) {
                    utteranceLatches.remove(utteranceId);
                    continue;
                }

                try {
                    long timeout = 5000 + (chunk.text.length() * 150L);
                    boolean done = latch.await(timeout, TimeUnit.MILLISECONDS);
                    
                    if (!done) {
                        utteranceLatches.remove(utteranceId);
                    }

                    if (i < chunks.size() - 1) {
                         Thread.sleep(15); 
                    }
                } catch (InterruptedException e) {
                    stopRequested = true;
                }

                if (stopRequested) {
                    targetEngine.stop();
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            synchronized (callback) {
                callback.done();
            }
        }
    }

    private boolean waitForEngine(String lang) {
        long timeout = 2500;
        long start = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - start < timeout) {
            if ("SHAN".equals(lang) && isShanReady) return true;
            if ("MYANMAR".equals(lang) && isBurmeseReady) return true;
            if ("ENGLISH".equals(lang) && isEnglishReady) return true;
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }
        return false;
    }

    @Override
    protected void onStop() {
        stopRequested = true;
        for (CountDownLatch latch : utteranceLatches.values()) {
            while (latch.getCount() > 0) latch.countDown();
        }
        utteranceLatches.clear();
        if (shanEngine != null) shanEngine.stop();
        if (burmeseEngine != null) burmeseEngine.stop();
        if (englishEngine != null) englishEngine.stop();
    }

    private RemoteTextToSpeech getEngineByLang(String lang) {
        if ("SHAN".equals(lang)) return shanEngine;
        if ("MYANMAR".equals(lang)) return burmeseEngine;
        return englishEngine;
    }

    private String getBestEngine(String prefKey) {
        String pkg = prefs.getString(prefKey, null);
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) return pkg;
        
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        if (sysDef != null && !sysDef.equals(getPackageName())) return sysDef;

        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> s = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo i : s) {
                String p = i.serviceInfo.packageName;
                if (!p.equals(getPackageName()) && !p.contains("samsung")) return p; 
            }
        } catch (Exception e) {}

        return "com.google.android.tts";
    }

    private void shutdownEngines() {
        try { if (shanEngine != null) shanEngine.shutdown(); } catch (Exception e) {}
        try { if (burmeseEngine != null) burmeseEngine.shutdown(); } catch (Exception e) {}
        try { if (englishEngine != null) englishEngine.shutdown(); } catch (Exception e) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shutdownEngines();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {}
        }
    }

    @Override protected int onIsLanguageAvailable(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
    @Override protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }
    @Override protected int onLoadLanguage(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
}

