package com.cherry.sme.tts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
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
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("deprecation")
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
    private static final int MAX_FAIL_BEFORE_REINIT = 1;
    private HandlerThread watchdogThread;
    private Handler watchdogHandler;
    private final AtomicBoolean isKeepAliveRunning = new AtomicBoolean(false);
    private volatile long lastSpeechFinishedTime = 0;
    private Thread keepAliveThread;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 4000;
    private final ReentrantLock keepAliveLock = new ReentrantLock();
    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();
    private final ReentrantLock engineInitLock = new ReentrantLock();

    private final UtteranceProgressListener globalListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            LogCollector.addLog("UTTERANCE", "Started: " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
            LogCollector.addLog("UTTERANCE", "Completed: " + utteranceId);
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            LogCollector.addError("UTTERANCE", "Error on: " + utteranceId);
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            LogCollector.addError("UTTERANCE", "Error on: " + utteranceId + " code=" + errorCode);
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
        LogCollector.recordServiceStart();
        LogCollector.addLog("SERVICE", "onCreate() - API " + Build.VERSION.SDK_INT);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        TTSUtils.loadMapping(this);
        LogCollector.addLog("SERVICE", "Word mapping loaded");
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            cpuWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CherrySME::CpuWakeLock"
            );
            cpuWakeLock.setReferenceCounted(false);
            LogCollector.addLog("WAKELOCK", "CPU WakeLock created");
            if (Build.VERSION.SDK_INT < 33) {
                screenWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                        "CherrySME::ScreenWakeLock"
                );
                screenWakeLock.setReferenceCounted(false);
                LogCollector.addLog("WAKELOCK", "Screen WakeLock created (API<33)");
            } else {
                LogCollector.addLog("WAKELOCK", "Screen WakeLock skipped (API>=33)");
            }
        }
        watchdogThread = new HandlerThread("TTS-Watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());
        LogCollector.addLog("SERVICE", "Watchdog thread started");
        
        watchdogHandler.post(new Runnable() {
            @Override
            public void run() {
                initAllEngines();
            }
        });
    }

    private void initAllEngines() {
        engineInitLock.lock();
        try {
            LogCollector.addLog("ENGINE", "initAllEngines() started");
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
            String shanPkg = getBestEngine("pref_engine_shan");
            LogCollector.addLog("ENGINE", "Shan engine: " + shanPkg);
            try {
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isShanReady.set(true);
                        LogCollector.addLog("ENGINE", "Shan engine READY");
                    } else {
                        LogCollector.addError("ENGINE", "Shan engine init FAILED status=" + status);
                    }
                }, shanPkg);
                shanEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                shanEngine = null;
                LogCollector.addError("ENGINE", "Shan engine creation failed", e);
            }
            String burPkg = getBestEngine("pref_engine_myanmar");
            LogCollector.addLog("ENGINE", "Burmese engine: " + burPkg);
            try {
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isBurmeseReady.set(true);
                        LogCollector.addLog("ENGINE", "Burmese engine READY");
                    } else {
                        LogCollector.addError("ENGINE", "Burmese engine init FAILED status=" + status);
                    }
                }, burPkg);
                burmeseEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                burmeseEngine = null;
                LogCollector.addError("ENGINE", "Burmese engine creation failed", e);
            }
            String engPkg = getBestEngine("pref_engine_english");
            LogCollector.addLog("ENGINE", "English engine: " + engPkg);
            try {
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isEnglishReady.set(true);
                        LogCollector.addLog("ENGINE", "English engine READY");
                    } else {
                        LogCollector.addError("ENGINE", "English engine init FAILED status=" + status);
                    }
                }, engPkg);
                englishEngine.setOnUtteranceProgressListener(globalListener);
            } catch (Exception e) {
                englishEngine = null;
                LogCollector.addError("ENGINE", "English engine creation failed", e);
            }
            LogCollector.addLog("ENGINE", "initAllEngines() completed");
        } finally {
            engineInitLock.unlock();
        }
    }

    private void reinitSingleEngine(String lang) {
        engineInitLock.lock();
        try {
            if (isDestroyed.get()) return;
            LogCollector.addWarn("ENGINE", "Reinitializing " + lang + " engine");
            if ("SHAN".equals(lang)) {
                if (shanEngine != null) {
                    try { shanEngine.shutdown(); } catch (Exception e) {}
                }
                isShanReady.set(false);
                isShanConfigured = false;
                shanFailCount.set(0);
                String pkg = getBestEngine("pref_engine_shan");
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isShanReady.set(true);
                        LogCollector.addLog("ENGINE", "Shan engine reinit READY");
                    } else {
                        LogCollector.addError("ENGINE", "Shan engine reinit FAILED");
                    }
                }, pkg);
                shanEngine.setOnUtteranceProgressListener(globalListener);
            } else if ("MYANMAR".equals(lang)) {
                if (burmeseEngine != null) {
                    try { burmeseEngine.shutdown(); } catch (Exception e) {}
                }
                isBurmeseReady.set(false);
                isBurmeseConfigured = false;
                burmeseFailCount.set(0);
                String pkg = getBestEngine("pref_engine_myanmar");
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isBurmeseReady.set(true);
                        LogCollector.addLog("ENGINE", "Burmese engine reinit READY");
                    } else {
                        LogCollector.addError("ENGINE", "Burmese engine reinit FAILED");
                    }
                }, pkg);
                burmeseEngine.setOnUtteranceProgressListener(globalListener);
            } else if ("ENGLISH".equals(lang)) {
                if (englishEngine != null) {
                    try { englishEngine.shutdown(); } catch (Exception e) {}
                }
                isEnglishReady.set(false);
                isEnglishConfigured = false;
                englishFailCount.set(0);
                String pkg = getBestEngine("pref_engine_english");
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        isEnglishReady.set(true);
                        LogCollector.addLog("ENGINE", "English engine reinit READY");
                    } else {
                        LogCollector.addError("ENGINE", "English engine reinit FAILED");
                    }
                }, pkg);
                englishEngine.setOnUtteranceProgressListener(globalListener);
            }
        } catch (Exception e) {
            LogCollector.addError("ENGINE", "reinitSingleEngine(" + lang + ") failed", e);
        } finally {
            engineInitLock.unlock();
        }
    }

    private void scheduleReinit(String lang) {
        if (watchdogHandler != null && !isDestroyed.get()) {
            LogCollector.addWarn("WATCHDOG", "Scheduling reinit for " + lang);
            watchdogHandler.post(() -> reinitSingleEngine(lang));
        }
    }

    private void recordFailure(String lang) {
        AtomicInteger counter;
        if ("SHAN".equals(lang)) counter = shanFailCount;
        else if ("MYANMAR".equals(lang)) counter = burmeseFailCount;
        else counter = englishFailCount;
        int count = counter.incrementAndGet();
        LogCollector.addError("SPEAK", lang + " failure #" + count);
        LogCollector.recordSpeakFailure();
        if (count >= MAX_FAIL_BEFORE_REINIT) {
            scheduleReinit(lang);
        }
    }

    private void recordSuccess(String lang) {
        if ("SHAN".equals(lang)) shanFailCount.set(0);
        else if ("MYANMAR".equals(lang)) burmeseFailCount.set(0);
        else englishFailCount.set(0);
        LogCollector.recordSpeakSuccess();
    }

    private void configureEngineIfNeeded(RemoteTextToSpeech engine, String lang) {
        if (engine == null || isDestroyed.get()) return;
        try {
            if ("MYANMAR".equals(lang) && !isBurmeseConfigured) {
                LogCollector.addLog("CONFIG", "Configuring Burmese engine");
                int res = engine.setLanguage(new Locale("mya"));
                if (res < 0) res = engine.setLanguage(new Locale("mya", "MM"));
                if (res < 0) engine.setLanguage(new Locale("my"));
                try {
                    Set<Voice> voices = engine.getVoices();
                    if (voices != null) {
                        LogCollector.addLog("CONFIG", "Burmese voices available: " + voices.size());
                        for (Voice v : voices) {
                            String vName = v.getName().toLowerCase();
                            if (vName.contains("my") || vName.contains("burmese") || vName.contains("mya")) {
                                engine.setVoice(v);
                                LogCollector.addLog("CONFIG", "Selected voice: " + v.getName());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    LogCollector.addWarn("CONFIG", "Voice selection failed for Burmese");
                }
                isBurmeseConfigured = true;
            } else if ("SHAN".equals(lang) && !isShanConfigured) {
                LogCollector.addLog("CONFIG", "Configuring Shan engine (locale: shn)");
                engine.setLanguage(new Locale("shn"));
                isShanConfigured = true;
            } else if ("ENGLISH".equals(lang) && !isEnglishConfigured) {
                LogCollector.addLog("CONFIG", "Configuring English engine (locale: en_US)");
                engine.setLanguage(Locale.US);
                isEnglishConfigured = true;
            }
        } catch (Exception e) {
            LogCollector.addError("CONFIG", "configureEngine(" + lang + ") failed", e);
        }
    }

    private void triggerKeepAlive() {
        keepAliveLock.lock();
        try {
            lastSpeechFinishedTime = System.currentTimeMillis();
            if (!isKeepAliveRunning.get() && !isDestroyed.get()) {
                isKeepAliveRunning.set(true);
                LogCollector.addLog("KEEPALIVE", "Starting keep-alive audio stream");
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
                            LogCollector.addError("KEEPALIVE", "AudioTrack failed to initialize");
                            return;
                        }
                        keepAliveTrack.setVolume(AudioTrack.getMaxVolume());
                        keepAliveTrack.play();
                        while (isKeepAliveRunning.get() && !isDestroyed.get()) {
                            keepAliveTrack.write(silenceBuffer, 0, silenceBuffer.length);
                            if (System.currentTimeMillis() - lastSpeechFinishedTime > KEEP_ALIVE_TIMEOUT_MS) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        LogCollector.addError("KEEPALIVE", "Keep-alive error", e);
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
                        LogCollector.addLog("KEEPALIVE", "Keep-alive stopped");
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
            LogCollector.addWarn("SYNTH", "Service destroyed, ignoring request");
            safeCallbackDone(callback);
            return;
        }
        stopRequested.set(false);
        LogCollector.recordSpeakRequest();
        String text = null;
        try {
            text = request.getText();
        } catch (Exception e) {
            LogCollector.addError("SYNTH", "Failed to get text from request", e);
        }
        if (text == null || text.trim().isEmpty()) {
            LogCollector.addWarn("SYNTH", "Empty text received");
            safeCallbackDone(callback);
            releaseWakeLocks();
            return;
        }
        int textLen = text.length();
        String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
        LogCollector.addLog("SYNTH", "Request: len=" + textLen + " text=\"" + preview + "\"");
        if (cpuWakeLock != null) {
            try {
                long cpuTimeout = Math.max(120000L, text.length() * 300L);
                cpuWakeLock.acquire(cpuTimeout);
                LogCollector.addLog("WAKELOCK", "CPU acquired (" + cpuTimeout + "ms)");
            } catch (Exception e) {
                LogCollector.addError("WAKELOCK", "CPU acquire failed", e);
            }
        }
        if (screenWakeLock != null) {
            try {
                long timeoutMs = Math.max(60000L, text.length() * 300L);
                screenWakeLock.acquire(timeoutMs);
                LogCollector.addLog("WAKELOCK", "Screen acquired (" + timeoutMs + "ms)");
            } catch (Exception e) {
                LogCollector.addError("WAKELOCK", "Screen acquire failed", e);
            }
        }
        triggerKeepAlive();
        List<TTSUtils.Chunk> chunks = null;
        try {
            chunks = TTSUtils.splitHelper(text);
        } catch (Exception e) {
            LogCollector.addError("SYNTH", "Text splitting failed", e);
        }
        if (chunks == null || chunks.isEmpty()) {
            LogCollector.addWarn("SYNTH", "No chunks after splitting");
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLocks();
            return;
        }
        LogCollector.addLog("SYNTH", "Split into " + chunks.size() + " chunks");
        for (int ci = 0; ci < chunks.size(); ci++) {
            TTSUtils.Chunk c = chunks.get(ci);
            String cPreview = c.text.length() > 30 ? c.text.substring(0, 30) + "..." : c.text;
            LogCollector.addLog("SYNTH", "  Chunk[" + ci + "] lang=" + c.lang + " len=" + c.text.length() + " \"" + cPreview + "\"");
        }
        Bundle params = new Bundle();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        params.putParcelable("audioAttributes", audioAttributes);
        float rate = 1.0f;
        float pitch = 1.0f;
        try {
            rate = request.getSpeechRate() / 100.0f;
            pitch = request.getPitch() / 100.0f;
        } catch (Exception e) {}
        LogCollector.addLog("SYNTH", "Rate=" + rate + " Pitch=" + pitch);
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested.get() || isDestroyed.get()) {
                    LogCollector.addWarn("SYNTH", "Stop requested at chunk " + i);
                    break;
                }
                lastSpeechFinishedTime = System.currentTimeMillis();
                TTSUtils.Chunk chunk = null;
                try {
                    chunk = chunks.get(i);
                } catch (Exception e) {
                    continue;
                }
                if (chunk == null || chunk.text == null || chunk.text.trim().isEmpty()) continue;
                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) {
                    LogCollector.addError("SYNTH", "No engine for " + chunk.lang + " (null)");
                    scheduleReinit(chunk.lang);
                    continue;
                }
                if (!waitForEngine(chunk.lang)) {
                    LogCollector.addError("SYNTH", chunk.lang + " engine not ready (timeout)");
                    recordFailure(chunk.lang);
                    continue;
                }
                try {
                    configureEngineIfNeeded(targetEngine, chunk.lang);
                } catch (Exception e) {
                    LogCollector.addError("SYNTH", "Configure failed for " + chunk.lang, e);
                }
                try {
                    targetEngine.setSpeechRate(rate);
                    targetEngine.setPitch(pitch);
                } catch (Exception e) {}
                int maxLen = 3500;
                int chunkTextLen = chunk.text.length();
                int startIndex = 0;
                while (startIndex < chunkTextLen) {
                    if (stopRequested.get() || isDestroyed.get()) break;
                    int endIndex = Math.min(startIndex + maxLen, chunkTextLen);
                    if (endIndex < chunkTextLen) {
                        int breakPoint = -1;
                        for (int j = endIndex - 1; j >= startIndex && j > startIndex + (maxLen - 500); j--) {
                            char c = chunk.text.charAt(j);
                            if (c == ' ' || c == '\n' || c == '\u104A' || c == '\u104B' || c == '.' || c == ',') {
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
                    utteranceLatches.put(utteranceId, latch);
                    int result = TextToSpeech.ERROR;
                    try {
                        result = targetEngine.speak(subText, TextToSpeech.QUEUE_ADD, params, utteranceId);
                    } catch (Exception e) {
                        utteranceLatches.remove(utteranceId);
                        LogCollector.addError("SYNTH", "speak() threw exception for " + chunk.lang, e);
                        recordFailure(chunk.lang);
                        break;
                    }
                    if (result == TextToSpeech.ERROR) {
                        utteranceLatches.remove(utteranceId);
                        LogCollector.addError("SYNTH", "speak() returned ERROR for " + chunk.lang + " subLen=" + subText.length());
                        recordFailure(chunk.lang);
                        break;
                    }
                    LogCollector.addLog("SYNTH", "speak() OK " + chunk.lang + " id=" + utteranceId + " len=" + subText.length());
                    try {
                        long timeout = Math.max(30000L, subText.length() * 300L);
                        boolean done = latch.await(timeout, TimeUnit.MILLISECONDS);
                        if (!done && !stopRequested.get() && !isDestroyed.get()) {
                            utteranceLatches.remove(utteranceId);
                            LogCollector.addError("SYNTH", "Timeout waiting for " + chunk.lang + " (" + timeout + "ms)");
                            try { targetEngine.stop(); } catch (Exception e) {}
                            recordFailure(chunk.lang);
                        } else if (done) {
                            recordSuccess(chunk.lang);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopRequested.set(true);
                        LogCollector.addWarn("SYNTH", "Interrupted");
                    }
                }
            }
        } catch (Exception e) {
            LogCollector.addError("SYNTH", "Unexpected error in synthesis loop", e);
        } finally {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLocks();
            LogCollector.addLog("SYNTH", "Request completed");
        }
    }

    private void safeCallbackDone(SynthesisCallback callback) {
        try {
            if (callback != null) {
                try {
                    callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
                    byte[] dummy = new byte[2];
                    callback.audioAvailable(dummy, 0, dummy.length);
                } catch (Exception e) {
                }
                callback.done();
            }
        } catch (Exception e) {
            LogCollector.addError("CALLBACK", "callback.done() failed", e);
        }
    }

    private boolean waitForEngine(String lang) {
        long timeout = 2500;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeout && !stopRequested.get() && !isDestroyed.get()) {
            if ("SHAN".equals(lang) && isShanReady.get()) return true;
            if ("MYANMAR".equals(lang) && isBurmeseReady.get()) return true;
            if ("ENGLISH".equals(lang) && isEnglishReady.get()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    protected void onStop() {
        LogCollector.addLog("SYNTH", "onStop() called");
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
        } finally {
            engineInitLock.unlock();
        }
    }

    @Override
    public void onDestroy() {
        LogCollector.addLog("SERVICE", "onDestroy() called");
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
        LogCollector.addLog("SERVICE", "Service destroyed");
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

