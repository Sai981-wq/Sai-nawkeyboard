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
    private volatile boolean stopRequested = false;
    private PowerManager.WakeLock wakeLock;

    private final AtomicInteger shanFailCount = new AtomicInteger(0);
    private final AtomicInteger burmeseFailCount = new AtomicInteger(0);
    private final AtomicInteger englishFailCount = new AtomicInteger(0);
    private static final int MAX_FAIL_BEFORE_REINIT = 3;

    private HandlerThread watchdogThread;
    private Handler watchdogHandler;

    private volatile boolean isKeepAliveRunning = false;
    private volatile long lastSpeechFinishedTime = 0;
    private Thread keepAliveThread;
    private AudioTrack keepAliveTrack;
    private static final long KEEP_ALIVE_TIMEOUT_MS = 10000; 

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
        TTSUtils.loadMapping(this);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CherrySME::TTSWakeLock"
            );
            wakeLock.setReferenceCounted(false);
        }

        watchdogThread = new HandlerThread("TTS-Watchdog");
        watchdogThread.start();
        watchdogHandler = new Handler(watchdogThread.getLooper());

        initAllEngines();
    }

    private void initAllEngines() {
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
        } catch (Exception e) {
            shanEngine = null;
        }

        try {
            burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
            }, getBestEngine("pref_engine_myanmar"));
            burmeseEngine.setOnUtteranceProgressListener(globalListener);
        } catch (Exception e) {
            burmeseEngine = null;
        }

        try {
            englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
            }, getBestEngine("pref_engine_english"));
            englishEngine.setOnUtteranceProgressListener(globalListener);
        } catch (Exception e) {
            englishEngine = null;
        }
    }

    private void reinitSingleEngine(String lang) {
        try {
            if ("SHAN".equals(lang)) {
                if (shanEngine != null) {
                    try { shanEngine.shutdown(); } catch (Exception ignored) {}
                }
                isShanReady.set(false);
                isShanConfigured = false;
                shanFailCount.set(0);
                shanEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isShanReady.set(true);
                }, getBestEngine("pref_engine_shan"));
                shanEngine.setOnUtteranceProgressListener(globalListener);

            } else if ("MYANMAR".equals(lang)) {
                if (burmeseEngine != null) {
                    try { burmeseEngine.shutdown(); } catch (Exception ignored) {}
                }
                isBurmeseReady.set(false);
                isBurmeseConfigured = false;
                burmeseFailCount.set(0);
                burmeseEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isBurmeseReady.set(true);
                }, getBestEngine("pref_engine_myanmar"));
                burmeseEngine.setOnUtteranceProgressListener(globalListener);

            } else if ("ENGLISH".equals(lang)) {
                if (englishEngine != null) {
                    try { englishEngine.shutdown(); } catch (Exception ignored) {}
                }
                isEnglishReady.set(false);
                isEnglishConfigured = false;
                englishFailCount.set(0);
                englishEngine = new RemoteTextToSpeech(getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS) isEnglishReady.set(true);
                }, getBestEngine("pref_engine_english"));
                englishEngine.setOnUtteranceProgressListener(globalListener);
            }
        } catch (Exception ignored) {}
    }

    private void scheduleReinit(String lang) {
        if (watchdogHandler != null) {
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
                } catch (Exception ignored) {}
                isBurmeseConfigured = true;

            } else if ("SHAN".equals(lang) && !isShanConfigured) {
                engine.setLanguage(new Locale("shn"));
                isShanConfigured = true;

            } else if ("ENGLISH".equals(lang) && !isEnglishConfigured) {
                engine.setLanguage(Locale.US);
                isEnglishConfigured = true;
            }
        } catch (Exception ignored) {}
    }

    private synchronized void triggerKeepAlive() {
        lastSpeechFinishedTime = System.currentTimeMillis();
        
        if (!isKeepAliveRunning) {
            isKeepAliveRunning = true;
            keepAliveThread = new Thread(() -> {
                int minBufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                byte[] silenceBuffer = new byte[minBufferSize];

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
                    keepAliveTrack.play();
                } catch (Exception e) {
                    isKeepAliveRunning = false;
                    return;
                }

                while (isKeepAliveRunning) {
                    try {
                        keepAliveTrack.write(silenceBuffer, 0, silenceBuffer.length);
                    } catch (Exception ignored) {}

                    if (System.currentTimeMillis() - lastSpeechFinishedTime > KEEP_ALIVE_TIMEOUT_MS) {
                        break;
                    }
                }

                isKeepAliveRunning = false;
                try {
                    if (keepAliveTrack != null) {
                        keepAliveTrack.stop();
                        keepAliveTrack.release();
                        keepAliveTrack = null;
                    }
                } catch (Exception ignored) {}
            });
            keepAliveThread.start();
        }
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        if (wakeLock != null) {
            try {
                wakeLock.acquire(60000);
            } catch (Exception ignored) {}
        }

        stopRequested = false;
        triggerKeepAlive();
        
        String text = null;

        try {
            text = request.getText();
        } catch (Exception ignored) {}

        if (text == null || text.trim().isEmpty()) {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLock();
            return;
        }

        List<TTSUtils.Chunk> chunks = null;
        try {
            chunks = TTSUtils.splitHelper(text);
        } catch (Exception ignored) {}

        if (chunks == null || chunks.isEmpty()) {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLock();
            return;
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
        } catch (Exception ignored) {}

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested) break;

                TTSUtils.Chunk chunk = null;
                try {
                    chunk = chunks.get(i);
                } catch (Exception ignored) {
                    continue;
                }

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

                try {
                    configureEngineIfNeeded(targetEngine, chunk.lang);
                } catch (Exception ignored) {}

                try {
                    targetEngine.setSpeechRate(rate);
                    targetEngine.setPitch(pitch);
                } catch (Exception ignored) {}

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
                    
                    if (!done && !stopRequested) {
                        utteranceLatches.remove(utteranceId);
                        try { targetEngine.stop(); } catch (Exception ignored) {}
                        recordFailure(chunk.lang);
                    } else if (done) {
                        recordSuccess(chunk.lang);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopRequested = true;
                }

                if (stopRequested) {
                    try { targetEngine.stop(); } catch (Exception ignored) {}
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            safeCallbackDone(callback);
            lastSpeechFinishedTime = System.currentTimeMillis();
            releaseWakeLock();
        }
    }

    private void safeCallbackDone(SynthesisCallback callback) {
        try {
            if (callback != null) {
                callback.done();
            }
        } catch (Exception ignored) {}
    }

    private boolean waitForEngine(String lang) {
        long timeout = 4000;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < timeout) {
            if (stopRequested) return false;
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
        stopRequested = true;
        for (CountDownLatch latch : utteranceLatches.values()) {
            try { latch.countDown(); } catch (Exception ignored) {}
        }
        utteranceLatches.clear();
        try { if (shanEngine != null) shanEngine.stop(); } catch (Exception ignored) {}
        try { if (burmeseEngine != null) burmeseEngine.stop(); } catch (Exception ignored) {}
        try { if (englishEngine != null) englishEngine.stop(); } catch (Exception ignored) {}
        releaseWakeLock();
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
        } catch (Exception ignored) {}

        try {
            String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
            if (sysDef != null && !sysDef.equals(getPackageName())) return sysDef;
        } catch (Exception ignored) {}

        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo info : services) {
                String p = info.serviceInfo.packageName;
                if (!p.equals(getPackageName()) && !p.contains("samsung")) return p;
            }
        } catch (Exception ignored) {}

        return "com.google.android.tts";
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
    }

    private void shutdownEngines() {
        try { if (shanEngine != null) shanEngine.shutdown(); } catch (Exception ignored) {}
        try { if (burmeseEngine != null) burmeseEngine.shutdown(); } catch (Exception ignored) {}
        try { if (englishEngine != null) englishEngine.shutdown(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        isKeepAliveRunning = false;
        shutdownEngines();
        releaseWakeLock();
        if (watchdogThread != null) {
            try { watchdogThread.quitSafely(); } catch (Exception ignored) {}
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

