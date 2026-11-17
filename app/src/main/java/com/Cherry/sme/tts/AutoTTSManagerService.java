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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class AutoTTSManagerService extends TextToSpeechService {

    private volatile RemoteTextToSpeech shanEngine;
    private volatile RemoteTextToSpeech burmeseEngine;
    private volatile RemoteTextToSpeech englishEngine;
    private volatile RemoteTextToSpeech lastActiveEngine;

    private volatile String activeShanPkg = "";
    private volatile String activeBurmesePkg = "";
    private volatile String activeEnglishPkg = "";

    private final AtomicBoolean isShanReady = new AtomicBoolean(false);
    private final AtomicBoolean isBurmeseReady = new AtomicBoolean(false);
    private final AtomicBoolean isEnglishReady = new AtomicBoolean(false);

    private volatile boolean isShanConfigured = false;
    private volatile boolean isBurmeseConfigured = false;
    private volatile boolean isEnglishConfigured = false;

    private volatile float lastShanRate = -1f;
    private volatile float lastBurmeseRate = -1f;
    private volatile float lastEnglishRate = -1f;

    private volatile float lastShanPitch = -1f;
    private volatile float lastBurmesePitch = -1f;
    private volatile float lastEnglishPitch = -1f;

    private volatile float cachedShanRateMult = 1.0f;
    private volatile float cachedBurmeseRateMult = 1.0f;
    private volatile float cachedEnglishRateMult = 1.0f;

    private volatile float cachedShanVol = 1.0f;
    private volatile float cachedBurmeseVol = 1.0f;
    private volatile float cachedEnglishVol = 1.0f;

    private SharedPreferences prefs;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicLong activeRequestId = new AtomicLong(0);
    private final Object speakLock = new Object();

    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock screenWakeLock;

    private final AtomicInteger shanFailCount = new AtomicInteger(0);
    private final AtomicInteger burmeseFailCount = new AtomicInteger(0);
    private final AtomicInteger englishFailCount = new AtomicInteger(0);
    private static final int MAX_FAIL_BEFORE_REINIT = 1;

    private HandlerThread watchdogThread;
    private Handler watchdogHandler;

    private final AtomicBoolean isKeepAliveRunning = new AtomicBoolean(false);
    private volatile long lastSpeechFinishedTime = 0;
    private Thread keepAliveThread;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 1500;
    private final ReentrantLock keepAliveLock = new ReentrantLock();

    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();
    private final ReentrantLock engineInitLock = new ReentrantLock();

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            refreshCachedPrefs();
            if ("pref_engine_shan".equals(key)) {
                scheduleReinit("SHAN");
            } else if ("pref_engine_myanmar".equals(key)) {
                scheduleReinit("MYANMAR");
            } else if ("pref_engine_english".equals(key)) {
                scheduleReinit("ENGLISH");
            }
        }
    };

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

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            releaseLatch(utteranceId);
        }

        private void releaseLatch(String utteranceId) {
            if (utteranceId == null) return;
            CountDownLatch latch = utteranceLatches.remove(utteranceId);
            if (latch != null) {
                latch.countDown();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        refreshCachedPrefs();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        TTSUtils.loadMapping(this);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            cpuWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CherrySME::CpuWakeLock"
            );
            cpuWakeLock.setReferenceCounted(false);
            screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "CherrySME::ScreenWakeLock"
            );
            screenWakeLock.setReferenceCounted(false);
        }

        watchdogThread = new HandlerThread("TTS-Watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());

        initAllEngines();
    }

    private void refreshCachedPrefs() {
        try {
            cachedShanRateMult = Math.max(10, prefs.getInt("pref_rate_shan", 100)) / 100.0f;
            cachedBurmeseRateMult = Math.max(10, prefs.getInt("pref_rate_myanmar", 100)) / 100.0f;
            cachedEnglishRateMult = Math.max(10, prefs.getInt("pref_rate_english", 100)) / 100.0f;

            cachedShanVol = Math.max(0, Math.min(100, prefs.getInt("pref_vol_shan", 100))) / 100.0f;
            cachedBurmeseVol = Math.max(0, Math.min(100, prefs.getInt("pref_vol_myanmar", 100))) / 100.0f;
            cachedEnglishVol = Math.max(0, Math.min(100, prefs.getInt("pref_vol_english", 100))) / 100.0f;
        } catch (Exception e) {}
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

            lastShanRate = -1f;
            lastBurmeseRate = -1f;
            lastEnglishRate = -1f;
            lastShanPitch = -1f;
            lastBurmesePitch = -1f;
            lastEnglishPitch = -1f;

            try {
                activeShanPkg = getBestEngine("pref_engine_shan");
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isShanReady.set(true);
                }, activeShanPkg);
                shanEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                shanEngine = null;
            }

            try {
                activeBurmesePkg = getBestEngine("pref_engine_myanmar");
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
                }, activeBurmesePkg);
                burmeseEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                burmeseEngine = null;
            }

            try {
                activeEnglishPkg = getBestEngine("pref_engine_english");
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
                }, activeEnglishPkg);
                englishEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                englishEngine = null;
            }
        } finally {
            engineInitLock.unlock();
        }
    }

    private void reinitSingleEngine(String lang) {
        engineInitLock.lock();
        try {
            if (isDestroyed.get()) return;
            if ("SHAN".equals(lang)) {
                if (shanEngine != null) {
                    try { shanEngine.stop(); } catch (Exception e) {}
                    try { shanEngine.shutdown(); } catch (Exception e) {}
                }
                isShanReady.set(false);
                isShanConfigured = false;
                lastShanRate = -1f;
                lastShanPitch = -1f;
                shanFailCount.set(0);
                activeShanPkg = getBestEngine("pref_engine_shan");
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isShanReady.set(true);
                }, activeShanPkg);
                shanEngine.setOnUtteranceProgressListener(globalListener);

            } else if ("MYANMAR".equals(lang)) {
                if (burmeseEngine != null) {
                    try { burmeseEngine.stop(); } catch (Exception e) {}
                    try { burmeseEngine.shutdown(); } catch (Exception e) {}
                }
                isBurmeseReady.set(false);
                isBurmeseConfigured = false;
                lastBurmeseRate = -1f;
                lastBurmesePitch = -1f;
                burmeseFailCount.set(0);
                activeBurmesePkg = getBestEngine("pref_engine_myanmar");
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
                }, activeBurmesePkg);
                burmeseEngine.setOnUtteranceProgressListener(globalListener);

            } else if ("ENGLISH".equals(lang)) {
                if (englishEngine != null) {
                    try { englishEngine.stop(); } catch (Exception e) {}
                    try { englishEngine.shutdown(); } catch (Exception e) {}
                }
                isEnglishReady.set(false);
                isEnglishConfigured = false;
                lastEnglishRate = -1f;
                lastEnglishPitch = -1f;
                englishFailCount.set(0);
                activeEnglishPkg = getBestEngine("pref_engine_english");
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
                }, activeEnglishPkg);
                englishEngine.setOnUtteranceProgressListener(globalListener);
            }
        } catch (Exception e) {
        } finally {
            engineInitLock.unlock();
        }
    }

    private void ensureCorrectEngineLoaded(String lang) {
        if ("SHAN".equals(lang)) {
            String desired = prefs.getString("pref_engine_shan", activeShanPkg);
            if (shanEngine == null || (desired != null && !desired.isEmpty() && !desired.equals(activeShanPkg))) {
                reinitSingleEngine("SHAN");
            }
        } else if ("MYANMAR".equals(lang)) {
            String desired = prefs.getString("pref_engine_myanmar", activeBurmesePkg);
            if (burmeseEngine == null || (desired != null && !desired.isEmpty() && !desired.equals(activeBurmesePkg))) {
                reinitSingleEngine("MYANMAR");
            }
        } else if ("ENGLISH".equals(lang)) {
            String desired = prefs.getString("pref_engine_english", activeEnglishPkg);
            if (englishEngine == null || (desired != null && !desired.isEmpty() && !desired.equals(activeEnglishPkg))) {
                reinitSingleEngine("ENGLISH");
            }
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

        if (counter.incrementAndGet() >= MAX_FAIL_BEFORE_REINIT) {
            scheduleReinit(lang);
        }
    }

    private void recordSuccess(String lang) {
        if ("SHAN".equals(lang)) shanFailCount.set(0);
        else if ("MYANMAR".equals(lang)) burmeseFailCount.set(0);
        else englishFailCount.set(0);
    }

    private void configureEngineIfNeeded(RemoteTextToSpeech engine, String lang) {
        if (engine == null || isDestroyed.get()) return;
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            engine.setAudioAttributes(audioAttributes);

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

    private void applyRateAndPitchIfNeeded(RemoteTextToSpeech engine, String lang, float rate, float pitch) {
        try {
            if ("SHAN".equals(lang)) {
                if (Math.abs(lastShanRate - rate) > 0.01f) {
                    engine.setSpeechRate(rate);
                    lastShanRate = rate;
                }
                if (Math.abs(lastShanPitch - pitch) > 0.01f) {
                    engine.setPitch(pitch);
                    lastShanPitch = pitch;
                }
            } else if ("MYANMAR".equals(lang)) {
                if (Math.abs(lastBurmeseRate - rate) > 0.01f) {
                    engine.setSpeechRate(rate);
                    lastBurmeseRate = rate;
                }
                if (Math.abs(lastBurmesePitch - pitch) > 0.01f) {
                    engine.setPitch(pitch);
                    lastBurmesePitch = pitch;
                }
            } else {
                if (Math.abs(lastEnglishRate - rate) > 0.01f) {
                    engine.setSpeechRate(rate);
                    lastEnglishRate = rate;
                }
                if (Math.abs(lastEnglishPitch - pitch) > 0.01f) {
                    engine.setPitch(pitch);
                    lastEnglishPitch = pitch;
                }
            }
        } catch (Exception e) {}
    }

    private float getEngineRateMultiplier(String lang) {
        if ("SHAN".equals(lang)) return cachedShanRateMult;
        if ("MYANMAR".equals(lang)) return cachedBurmeseRateMult;
        return cachedEnglishRateMult;
    }

    private float getEngineVolume(String lang) {
        if ("SHAN".equals(lang)) return cachedShanVol;
        if ("MYANMAR".equals(lang)) return cachedBurmeseVol;
        return cachedEnglishVol;
    }

    private void triggerKeepAlive() {
        lastSpeechFinishedTime = System.currentTimeMillis();
        if (isKeepAliveRunning.get() || isDestroyed.get()) return;

        keepAliveLock.lock();
        try {
            if (!isKeepAliveRunning.get() && !isDestroyed.get()) {
                isKeepAliveRunning.set(true);
                keepAliveThread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    int minBufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBufferSize <= 0) minBufferSize = 32000;

                    byte[] silenceBuffer = new byte[minBufferSize];
                    for (int i = 0; i < silenceBuffer.length; i += 2) {
                        silenceBuffer[i] = 0;
                        silenceBuffer[i + 1] = 0;
                    }

                    AudioTrack keepAliveTrack = null;
                    try {
                        keepAliveTrack = new AudioTrack(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build(),
                                new AudioFormat.Builder()
                                        .setSampleRate(16000)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build(),
                                minBufferSize,
                                AudioTrack.MODE_STREAM,
                                AudioManager.AUDIO_SESSION_ID_GENERATE
                        );

                        if (keepAliveTrack.getState() == AudioTrack.STATE_UNINITIALIZED) {
                            return;
                        }

                        keepAliveTrack.setVolume(0.01f);
                        keepAliveTrack.play();

                        while (isKeepAliveRunning.get() && !isDestroyed.get()) {
                            keepAliveTrack.write(silenceBuffer, 0, silenceBuffer.length);
                            if (System.currentTimeMillis() - lastSpeechFinishedTime > KEEP_ALIVE_TIMEOUT_MS) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                    } finally {
                        isKeepAliveRunning.set(false);
                        try {
                            if (keepAliveTrack != null && keepAliveTrack.getState() != AudioTrack.STATE_UNINITIALIZED) {
                                if (keepAliveTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                    keepAliveTrack.stop();
                                }
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

        final long myRequestId = activeRequestId.incrementAndGet();
        stopRequested.set(false);

        String text = null;
        try {
            text = request.getText();
        } catch (Exception e) {}

        if (text == null || text.trim().isEmpty()) {
            safeCallbackDone(callback);
            return;
        }

        acquireWakeLocksIfNeeded(text.length());
        triggerKeepAlive();

        List<TTSUtils.Chunk> chunks = null;
        try {
            chunks = TTSUtils.splitHelper(text);
        } catch (Exception e) {}

        if (chunks == null || chunks.isEmpty()) {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            return;
        }

        Bundle params = new Bundle();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        params.putParcelable("audioAttributes", audioAttributes);
        params.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, "10");
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, 10);

        float baseRate = 1.0f;
        float pitch = 1.0f;
        try {
            baseRate = request.getSpeechRate() / 100.0f;
            pitch = request.getPitch() / 100.0f;
        } catch (Exception e) {}

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested.get() || myRequestId != activeRequestId.get() || isDestroyed.get()) break;

                lastSpeechFinishedTime = System.currentTimeMillis();

                TTSUtils.Chunk chunk = chunks.get(i);
                if (chunk == null || chunk.text == null || chunk.text.trim().isEmpty()) continue;

                ensureCorrectEngineLoaded(chunk.lang);

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) {
                    scheduleReinit(chunk.lang);
                    continue;
                }

                if (!waitForEngine(chunk.lang, myRequestId)) {
                    if (!stopRequested.get() && myRequestId == activeRequestId.get()) {
                        recordFailure(chunk.lang);
                    }
                    continue;
                }

                configureEngineIfNeeded(targetEngine, chunk.lang);

                float finalRate = baseRate * getEngineRateMultiplier(chunk.lang);
                float engineVol = getEngineVolume(chunk.lang);

                applyRateAndPitchIfNeeded(targetEngine, chunk.lang, finalRate, pitch);
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, engineVol);

                int maxLen = 3500;
                int textLen = chunk.text.length();
                int startIndex = 0;

                while (startIndex < textLen) {
                    if (stopRequested.get() || myRequestId != activeRequestId.get() || isDestroyed.get()) break;

                    int endIndex = Math.min(startIndex + maxLen, textLen);

                    if (endIndex < textLen) {
                        int breakPoint = -1;
                        for (int j = endIndex - 1; j >= startIndex && j > startIndex + (maxLen - 500); j--) {
                            char c = chunk.text.charAt(j);
                            if (c == ' ' || c == '\n' || c == '။' || c == '၊' || c == '.' || c == ',') {
                                breakPoint = j + 1;
                                break;
                            }
                        }
                        if (breakPoint != -1) {
                            endIndex = breakPoint;
                        }
                    }

                    String subText = chunk.text.substring(startIndex, endIndex);
                    startIndex = endIndex;

                    String utteranceId = "utt_" + System.nanoTime();
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

                    CountDownLatch latch = new CountDownLatch(1);
                    int result = TextToSpeech.ERROR;

                    synchronized (speakLock) {
                        if (stopRequested.get() || myRequestId != activeRequestId.get() || isDestroyed.get()) {
                            break;
                        }
                        if (lastActiveEngine != null && lastActiveEngine != targetEngine) {
                            try { lastActiveEngine.stop(); } catch (Exception e) {}
                        }
                        lastActiveEngine = targetEngine;
                        utteranceLatches.put(utteranceId, latch);
                        try {
                            result = targetEngine.speak(subText, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
                        } catch (Exception e) {
                            utteranceLatches.remove(utteranceId);
                            recordFailure(chunk.lang);
                            break;
                        }
                    }

                    if (result == TextToSpeech.ERROR) {
                        utteranceLatches.remove(utteranceId);
                        recordFailure(chunk.lang);
                        break;
                    }

                    try {
                        long timeout = Math.max(30000L, subText.length() * 300L);
                        boolean done = latch.await(timeout, TimeUnit.MILLISECONDS);

                        if (!done && !stopRequested.get() && myRequestId == activeRequestId.get() && !isDestroyed.get()) {
                            utteranceLatches.remove(utteranceId);
                            try { targetEngine.stop(); } catch (Exception e) {}
                            recordFailure(chunk.lang);
                        } else if (done) {
                            recordSuccess(chunk.lang);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopRequested.set(true);
                        break;
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
        }
    }

    private void acquireWakeLocksIfNeeded(int textLen) {
        if (cpuWakeLock != null) {
            try {
                if (!cpuWakeLock.isHeld() || textLen > 200) {
                    long cpuTimeout = Math.max(30000L, textLen * 300L);
                    cpuWakeLock.acquire(cpuTimeout);
                }
            } catch (Exception e) {}
        }
        if (screenWakeLock != null && textLen > 200) {
            try {
                long timeoutMs = Math.max(30000L, textLen * 300L);
                screenWakeLock.acquire(timeoutMs);
            } catch (Exception e) {}
        }
    }

    private void safeCallbackDone(SynthesisCallback callback) {
        try {
            if (callback != null) {
                callback.done();
            }
        } catch (Exception e) {}
    }

    private boolean waitForEngine(String lang, long myRequestId) {
        long timeout = 2500;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeout && !stopRequested.get() && myRequestId == activeRequestId.get() && !isDestroyed.get()) {
            if ("SHAN".equals(lang) && isShanReady.get()) return true;
            if ("MYANMAR".equals(lang) && isBurmeseReady.get()) return true;
            if ("ENGLISH".equals(lang) && isEnglishReady.get()) return true;
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    protected void onStop() {
        stopRequested.set(true);
        activeRequestId.incrementAndGet();

        synchronized (speakLock) {
            for (CountDownLatch latch : utteranceLatches.values()) {
                try { latch.countDown(); } catch (Exception e) {}
            }
            utteranceLatches.clear();

            if (lastActiveEngine != null) {
                try { lastActiveEngine.stop(); } catch (Exception e) {}
            }
            try { if (shanEngine != null && shanEngine != lastActiveEngine) shanEngine.stop(); } catch (Exception e) {}
            try { if (burmeseEngine != null && burmeseEngine != lastActiveEngine) burmeseEngine.stop(); } catch (Exception e) {}
            try { if (englishEngine != null && englishEngine != lastActiveEngine) englishEngine.stop(); } catch (Exception e) {}
        }
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
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            try {
                cpuWakeLock.release();
            } catch (Exception e) {}
        }
        if (screenWakeLock != null && screenWakeLock.isHeld()) {
            try {
                screenWakeLock.release();
            } catch (Exception e) {}
        }
    }

    private void shutdownEngines() {
        engineInitLock.lock();
        try {
            if (shanEngine != null) { try { shanEngine.shutdown(); } catch (Exception e) {} shanEngine = null; }
            if (burmeseEngine != null) { try { burmeseEngine.shutdown(); } catch (Exception e) {} burmeseEngine = null; }
            if (englishEngine != null) { try { englishEngine.shutdown(); } catch (Exception e) {} englishEngine = null; }
            lastActiveEngine = null;
        } finally {
            engineInitLock.unlock();
        }
    }

    @Override
    public void onDestroy() {
        isDestroyed.set(true);
        stopRequested.set(true);
        activeRequestId.incrementAndGet();
        isKeepAliveRunning.set(false);
        if (prefs != null) {
            try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener); } catch (Exception e) {}
        }
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
    protected int onIsLanguageAvailable(String l, String c, String v) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String l, String c, String v) {
        return TextToSpeech.LANG_AVAILABLE;
    }
}

