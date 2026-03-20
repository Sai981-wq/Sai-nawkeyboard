package com.cherry.sme.tts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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

    private final AtomicBoolean isKeepAliveRunning = new AtomicBoolean(false);
    private volatile long lastSpeechFinishedTime = 0;
    private Thread keepAliveThread;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 4000;
    private final ReentrantLock keepAliveLock = new ReentrantLock();

    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();
    private final ReentrantLock engineInitLock = new ReentrantLock();

    private int volShan = 100;
    private int speedShan = 50;
    private int volBurmese = 100;
    private int speedBurmese = 50;
    private int volEnglish = 100;
    private int speedEnglish = 50;

    private void loadConfigDirectly() {
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

    private void recordSuccess(String lang) {
        if ("SHAN".equals(lang)) shanFailCount.set(0);
        else if ("MYANMAR".equals(lang)) burmeseFailCount.set(0);
        else englishFailCount.set(0);
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

    private void triggerKeepAlive() {
        keepAliveLock.lock();
        try {
            lastSpeechFinishedTime = System.currentTimeMillis();
            if (!isKeepAliveRunning.get() && !isDestroyed.get()) {
                isKeepAliveRunning.set(true);
                keepAliveThread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    int minBufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBufferSize <= 0) minBufferSize = 32000;
                    byte[] silenceBuffer = new byte[minBufferSize];
                    for (int i = 0; i < silenceBuffer.length; i += 2) {
                        silenceBuffer[i] = 1;
                        silenceBuffer[i + 1] = 0;
                    }
                    AudioTrack keepAliveTrack = null;
                    try {
                        keepAliveTrack = new AudioTrack(
                                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                                new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                                minBufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
                        );
                        if (keepAliveTrack.getState() == AudioTrack.STATE_UNINITIALIZED) return;
                        keepAliveTrack.setVolume(AudioTrack.getMaxVolume());
                        keepAliveTrack.play();
                        while (isKeepAliveRunning.get() && !isDestroyed.get()) {
                            keepAliveTrack.write(silenceBuffer, 0, silenceBuffer.length);
                            if (System.currentTimeMillis() - lastSpeechFinishedTime > KEEP_ALIVE_TIMEOUT_MS) break;
                        }
                    } catch (Exception e) {
                    } finally {
                        isKeepAliveRunning.set(false);
                        try {
                            if (keepAliveTrack != null && keepAliveTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                                if (keepAliveTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) keepAliveTrack.stop();
                                keepAliveTrack.release();
                            }
                        } catch (Exception e) {}
                    }
                });
                keepAliveThread.start();
            }
        } finally {
            keepAliveLock.unlock();
        }
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
            releaseWakeLocks();
            return;
        }

        if (cpuWakeLock != null) { try { cpuWakeLock.acquire(60000); } catch (Exception e) {} }
        if (screenWakeLock != null) {
            try {
                long timeoutMs = Math.min(2000 + (text.length() * 50L), 30000);
                screenWakeLock.acquire(timeoutMs);
            } catch (Exception e) {}
        }

        triggerKeepAlive();
        loadConfigDirectly();

        List<TTSUtils.Chunk> chunks = null;
        try { chunks = TTSUtils.splitHelper(text); } catch (Exception e) {}

        if (chunks == null || chunks.isEmpty()) {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLocks();
            return;
        }

        float talkBackRate = 1.0f;
        float talkBackPitch = 1.0f;
        try {
            talkBackRate = request.getSpeechRate() / 100.0f;
            talkBackPitch = request.getPitch() / 100.0f;
        } catch (Exception e) {}

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested.get() || isDestroyed.get()) break;
                lastSpeechFinishedTime = System.currentTimeMillis();

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

                float finalEngineRate = talkBackRate;
                float finalEngineVolume = 1.0f;
                
                if ("SHAN".equals(chunk.lang)) {
                    finalEngineRate = talkBackRate * (speedShan / 50.0f);
                    finalEngineVolume = volShan / 100.0f;
                } else if ("MYANMAR".equals(chunk.lang)) {
                    finalEngineRate = talkBackRate * (speedBurmese / 50.0f);
                    finalEngineVolume = volBurmese / 100.0f;
                } else {
                    finalEngineRate = talkBackRate * (speedEnglish / 50.0f);
                    finalEngineVolume = volEnglish / 100.0f;
                }

                try {
                    targetEngine.setSpeechRate(finalEngineRate);
                    targetEngine.setPitch(talkBackPitch);
                } catch (Exception e) {}
                
                Bundle params = new Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, finalEngineVolume);
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY);
                
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                params.putParcelable("audioAttributes", audioAttributes);

                String utteranceId = "utt_" + System.nanoTime();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                CountDownLatch latch = new CountDownLatch(1);
                utteranceLatches.put(utteranceId, latch);

                int result = TextToSpeech.ERROR;
                try {
                    result = targetEngine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);
                } catch (Exception e) {
                    utteranceLatches.remove(utteranceId);
                    recordFailure(chunk.lang);
                    continue;
                }

                if (result == TextToSpeech.ERROR) {
                    utteranceLatches.remove(utteranceId);
                    recordFailure(chunk.lang);
                    continue;
                }

                try {
                    long timeout = 10000 + (chunk.text.length() * 250L);
                    boolean done = latch.await(timeout, TimeUnit.MILLISECONDS);
                    if (!done && !stopRequested.get() && !isDestroyed.get()) {
                        utteranceLatches.remove(utteranceId);
                        try { if (targetEngine != null) targetEngine.stop(); } catch (Exception e) {}
                        recordFailure(chunk.lang);
                    } else if (done) {
                        recordSuccess(chunk.lang);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopRequested.set(true);
                }

                if (stopRequested.get() || isDestroyed.get()) {
                    try { if (targetEngine != null) targetEngine.stop(); } catch (Exception e) {}
                    break;
                }
            }
        } catch (Exception e) {
        } finally {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
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
        isKeepAliveRunning.set(false);
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            try { keepAliveThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
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

