package com.cherry.sme.tts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class AutoTTSManagerService extends TextToSpeechService {

    private volatile RemoteTextToSpeech shanEngine;
    private volatile RemoteTextToSpeech burmeseEngine;
    private volatile RemoteTextToSpeech englishEngine;

    private final AtomicBoolean isShanReady = new AtomicBoolean(false);
    private final AtomicBoolean isBurmeseReady = new AtomicBoolean(false);
    private final AtomicBoolean isEnglishReady = new AtomicBoolean(false);

    private volatile boolean isShanConfigured = false;
    private volatile boolean isBurmeseConfigured = false;
    private volatile boolean isEnglishConfigured = false;

    private SharedPreferences prefs;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    
    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    private final AtomicInteger shanFailCount = new AtomicInteger(0);
    private final AtomicInteger burmeseFailCount = new AtomicInteger(0);
    private final AtomicInteger englishFailCount = new AtomicInteger(0);
    private static final int MAX_FAIL_BEFORE_REINIT = 3;

    private HandlerThread watchdogThread;
    private Handler watchdogHandler;

    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();
    private final ReentrantLock engineInitLock = new ReentrantLock();

    private int volShan = 100;
    private int speedShan = 50;
    private int volBurmese = 100;
    private int speedBurmese = 50;
    private int volEnglish = 100;
    private int speedEnglish = 50;

    private void loadConfigFromFile() {
        try {
            File file = new File(getFilesDir(), "tts_settings.txt");
            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                byte[] bytes = new byte[(int) file.length()];
                fis.read(bytes);
                fis.close();
                String[] parts = new String(bytes).split(",");
                if (parts.length >= 6) {
                    volShan = Integer.parseInt(parts[0].trim());
                    speedShan = Integer.parseInt(parts[1].trim());
                    volBurmese = Integer.parseInt(parts[2].trim());
                    speedBurmese = Integer.parseInt(parts[3].trim());
                    volEnglish = Integer.parseInt(parts[4].trim());
                    speedEnglish = Integer.parseInt(parts[5].trim());
                }
            }
        } catch (Exception e) {}
    }

    private final UtteranceProgressListener globalListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {}

        @Override
        public void onDone(String utteranceId) { releaseLatch(utteranceId); }

        @Override
        public void onError(String utteranceId) { releaseLatch(utteranceId); }

        @Override
        public void onError(String utteranceId, int errorCode) { releaseLatch(utteranceId); }

        private void releaseLatch(String utteranceId) {
            if (utteranceId == null) return;
            CountDownLatch latch = utteranceLatches.remove(utteranceId);
            if (latch != null) latch.countDown();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        TTSUtils.loadMapping(this);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CherrySME::CpuWakeLock");
            cpuWakeLock.setReferenceCounted(false);
            screenWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "CherrySME::ScreenWakeLock");
            screenWakeLock.setReferenceCounted(false);
        }

        watchdogThread = new HandlerThread("TTS-Watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());

        initAllEngines();
    }

    private void initAllEngines() {
        engineInitLock.lock();
        try {
            shutdownEngines();
            isShanConfigured = false;
            isBurmeseConfigured = false;
            isEnglishConfigured = false;
            isShanReady.set(false);
            isBurmeseReady.set(false);
            isEnglishReady.set(false);
            shanFailCount.set(0);
            burmeseFailCount.set(0);
            englishFailCount.set(0);

            try {
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isShanReady.set(true);
                }, getBestEngine("pref_engine_shan"));
                shanEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) { shanEngine = null; }

            try {
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
                }, getBestEngine("pref_engine_myanmar"));
                burmeseEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) { burmeseEngine = null; }

            try {
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
                }, getBestEngine("pref_engine_english"));
                englishEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) { englishEngine = null; }
        } finally {
            engineInitLock.unlock();
        }
    }

    private void reinitSingleEngine(String lang) {
        engineInitLock.lock();
        try {
            if (isDestroyed.get()) return;
            if ("SHAN".equals(lang)) {
                if (shanEngine != null) { try { shanEngine.shutdown(); } catch (Exception e) {} }
                isShanReady.set(false);
                isShanConfigured = false;
                shanFailCount.set(0);
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isShanReady.set(true);
                }, getBestEngine("pref_engine_shan"));
                shanEngine.setOnUtteranceProgressListener(globalListener);
            } else if ("MYANMAR".equals(lang)) {
                if (burmeseEngine != null) { try { burmeseEngine.shutdown(); } catch (Exception e) {} }
                isBurmeseReady.set(false);
                isBurmeseConfigured = false;
                burmeseFailCount.set(0);
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
                }, getBestEngine("pref_engine_myanmar"));
                burmeseEngine.setOnUtteranceProgressListener(globalListener);
            } else if ("ENGLISH".equals(lang)) {
                if (englishEngine != null) { try { englishEngine.shutdown(); } catch (Exception e) {} }
                isEnglishReady.set(false);
                isEnglishConfigured = false;
                englishFailCount.set(0);
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
                }, getBestEngine("pref_engine_english"));
                englishEngine.setOnUtteranceProgressListener(globalListener);
            }
        } catch (Exception e) {
        } finally {
            engineInitLock.unlock();
        }
    }

    private void scheduleReinit(String lang) {
        if (watchdogHandler != null && !isDestroyed.get()) {
            watchdogHandler.post(() -> reinitSingleEngine(lang));
        }
    }

    private void recordFailure(String lang) {
        AtomicInteger counter;
        if ("SHAN".equals(lang)) counter = shanFailCount;
        else if ("MYANMAR".equals(lang)) counter = burmeseFailCount;
        else counter = englishFailCount;

        if (counter.incrementAndGet() >= MAX_FAIL_BEFORE_REINIT) scheduleReinit(lang);
    }

    private void configureEngineIfNeeded(RemoteTextToSpeech engine, String lang) {
        if (engine == null || isDestroyed.get()) return;
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
                } catch (Exception e) {}
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
        if (isDestroyed.get()) {
            safeCallbackDone(callback);
            return;
        }
        stopRequested.set(false);
        String text = null;

        try { text = request.getText(); } catch (Exception e) {}

        if (text == null || text.trim().isEmpty()) {
            safeCallbackDone(callback);
            return;
        }

        if (cpuWakeLock != null) { try { cpuWakeLock.acquire(60000); } catch (Exception e) {} }
        
        loadConfigFromFile();

        List<TTSUtils.Chunk> chunks = null;
        try { chunks = TTSUtils.splitHelper(text); } catch (Exception e) {}

        if (chunks == null || chunks.isEmpty()) {
            safeCallbackDone(callback);
            releaseWakeLocks();
            return;
        }

        Bundle params = new Bundle();
        float baseRate = 1.0f;
        float pitch = 1.0f;
        try {
            baseRate = request.getSpeechRate() / 100.0f;
            pitch = request.getPitch() / 100.0f;
        } catch (Exception e) {}

        boolean isCallbackStarted = false;

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested.get() || isDestroyed.get()) break;

                TTSUtils.Chunk chunk = null;
                try { chunk = chunks.get(i); } catch (Exception e) { continue; }
                if (chunk == null || chunk.text == null || chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) {
                    scheduleReinit(chunk.lang);
                    continue;
                }

                if (!waitForEngine(chunk.lang)) {
                    recordFailure(chunk.lang);
                    continue;
                }

                try { configureEngineIfNeeded(targetEngine, chunk.lang); } catch (Exception e) {}

                float engineRate = baseRate;
                float engineVolume = 1.0f;
                if ("SHAN".equals(chunk.lang)) {
                    engineRate *= (speedShan / 50.0f);
                    engineVolume = volShan / 100.0f;
                } else if ("MYANMAR".equals(chunk.lang)) {
                    engineRate *= (speedBurmese / 50.0f);
                    engineVolume = volBurmese / 100.0f;
                } else {
                    engineRate *= (speedEnglish / 50.0f);
                    engineVolume = volEnglish / 100.0f;
                }

                try {
                    targetEngine.setSpeechRate(engineRate);
                    targetEngine.setPitch(pitch);
                } catch (Exception e) {}

                File tempWav = new File(getCacheDir(), "temp_chunk_" + System.nanoTime() + ".wav");
                String utteranceId = "utt_" + System.nanoTime();
                CountDownLatch fileLatch = new CountDownLatch(1);
                utteranceLatches.put(utteranceId, fileLatch);

                int result = TextToSpeech.ERROR;
                try {
                    result = targetEngine.synthesizeToFile(chunk.text, params, tempWav, utteranceId);
                } catch (Exception e) {
                    utteranceLatches.remove(utteranceId);
                    recordFailure(chunk.lang);
                    continue;
                }

                if (result == TextToSpeech.SUCCESS) {
                    try {
                        fileLatch.await(10000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopRequested.set(true);
                    }

                    if (tempWav.exists() && tempWav.length() > 44 && !stopRequested.get()) {
                        try {
                            FileInputStream fis = new FileInputStream(tempWav);
                            byte[] header = new byte[100];
                            fis.read(header);
                            
                            int sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8) | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
                            
                            int dataOffset = 44;
                            for (int h = 0; h < header.length - 4; h++) {
                                if (header[h] == 'd' && header[h+1] == 'a' && header[h+2] == 't' && header[h+3] == 'a') {
                                    dataOffset = h + 8;
                                    break;
                                }
                            }

                            if (!isCallbackStarted) {
                                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1);
                                isCallbackStarted = true;
                            }

                            fis.close();
                            fis = new FileInputStream(tempWav);
                            fis.skip(dataOffset);

                            byte[] buffer = new byte[4096];
                            int bytesRead;
                            while ((bytesRead = fis.read(buffer)) != -1 && !stopRequested.get()) {
                                if (engineVolume != 1.0f) {
                                    for (int j = 0; j < bytesRead - 1; j += 2) {
                                        short sample = (short) ((buffer[j] & 0xFF) | (buffer[j+1] << 8));
                                        float newSample = sample * engineVolume;
                                        if (newSample > 32767) newSample = 32767;
                                        if (newSample < -32768) newSample = -32768;
                                        buffer[j] = (byte) ((int)newSample & 0xFF);
                                        buffer[j+1] = (byte) (((int)newSample >> 8) & 0xFF);
                                    }
                                }
                                callback.audioAvailable(buffer, 0, bytesRead);
                            }
                            fis.close();
                        } catch (Exception e) {}
                        tempWav.delete();
                    }
                }
                
                if (stopRequested.get() || isDestroyed.get()) {
                    break;
                }
            }
        } catch (Exception e) {
        } finally {
            safeCallbackDone(callback);
            releaseWakeLocks();
        }
    }

    private void safeCallbackDone(SynthesisCallback callback) {
        try { if (callback != null) callback.done(); } catch (Exception e) {}
    }

    private boolean waitForEngine(String lang) {
        long timeout = 4000;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeout && !stopRequested.get() && !isDestroyed.get()) {
            if ("SHAN".equals(lang) && isShanReady.get()) return true;
            if ("MYANMAR".equals(lang) && isBurmeseReady.get()) return true;
            if ("ENGLISH".equals(lang) && isEnglishReady.get()) return true;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    @Override
    protected void onStop() {
        stopRequested.set(true);
        for (CountDownLatch latch : utteranceLatches.values()) {
            try { latch.countDown(); } catch (Exception e) {}
        }
        utteranceLatches.clear();
        try { if (shanEngine != null) shanEngine.stop(); } catch (Exception e) {}
        try { if (burmeseEngine != null) burmeseEngine.stop(); } catch (Exception e) {}
        try { if (englishEngine != null) englishEngine.stop(); } catch (Exception e) {}
        releaseWakeLocks();
    }

    private RemoteTextToSpeech getEngineByLang(String lang) {
        if ("SHAN".equals(lang)) return shanEngine;
        if ("MYANMAR".equals(lang)) return burmeseEngine;
        return englishEngine;
    }

    private String getBestEngine(String prefKey) {
        try {
            String pkg = prefs.getString(prefKey, null);
            if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) return pkg;
        } catch (Exception e) {}
        try {
            String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
            if (sysDef != null && !sysDef.equals(getPackageName())) return sysDef;
        } catch (Exception e) {}
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo info : services) {
                String p = info.serviceInfo.packageName;
                if (!p.equals(getPackageName()) && !p.contains("samsung")) return p;
            }
        } catch (Exception e) {}
        return "com.google.android.tts";
    }

    private void releaseWakeLocks() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) { try { cpuWakeLock.release(); } catch (Exception e) {} }
        if (screenWakeLock != null && screenWakeLock.isHeld()) { try { screenWakeLock.release(); } catch (Exception e) {} }
    }

    private void shutdownEngines() {
        engineInitLock.lock();
        try {
            if (shanEngine != null) { try { shanEngine.shutdown(); } catch (Exception e) {} shanEngine = null; }
            if (burmeseEngine != null) { try { burmeseEngine.shutdown(); } catch (Exception e) {} burmeseEngine = null; }
            if (englishEngine != null) { try { englishEngine.shutdown(); } catch (Exception e) {} englishEngine = null; }
        } finally {
            engineInitLock.unlock();
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed.set(true);
        stopRequested.set(true);
        shutdownEngines();
        releaseWakeLocks();
        if (watchdogThread != null) {
            try { watchdogThread.quitSafely(); } catch (Exception e) {}
            try { watchdogThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        super.onDestroy();
    }

    @Override
    protected int onIsLanguageAvailable(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }

    @Override
    protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }

    @Override
    protected int onLoadLanguage(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
}

