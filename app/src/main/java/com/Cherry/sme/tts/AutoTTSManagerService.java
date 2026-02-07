package com.cherry.sme.tts;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.UtteranceProgressListener;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;
    private volatile boolean stopRequested = false;
    
    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();

    private final UtteranceProgressListener globalListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            // LogCollector.addLog("Listener", "Started: " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
            // LogCollector.addLog("Listener", "Done: " + utteranceId);
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            LogCollector.addLog("Listener", "Error on: " + utteranceId);
            releaseLatch(utteranceId);
        }

        @Override
        public void onError(String utteranceId, int errorCode) {
            LogCollector.addLog("Listener", "Error (" + errorCode + ") on: " + utteranceId);
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
        LogCollector.clear(); // Service စတိုင်း Log အဟောင်းရှင်းမယ်
        LogCollector.addLog("Service", "AutoTTS Service Created");
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        TTSUtils.loadMapping(this);
        initAllEngines();
    }

    private void initAllEngines() {
        LogCollector.addLog("Service", "Initializing Engines...");
        shutdownEngines();
        shanEngine = new RemoteTextToSpeech(this, getBestEngine("pref_engine_shan"));
        shanEngine.setOnUtteranceProgressListener(globalListener);
        
        burmeseEngine = new RemoteTextToSpeech(this, getBestEngine("pref_engine_myanmar"));
        burmeseEngine.setOnUtteranceProgressListener(globalListener);
        
        englishEngine = new RemoteTextToSpeech(this, getBestEngine("pref_engine_english"));
        englishEngine.setOnUtteranceProgressListener(globalListener);
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        String text = request.getText();
        // LogCollector.addLog("Process", "Received text: " + (text.length() > 20 ? text.substring(0, 20) + "..." : text));

        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);

        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        synchronized (callback) {
            callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        }

        Bundle requestParams = request.getParams();
        Bundle params = new Bundle();
        if (requestParams != null) {
            params.putAll(requestParams);
        }
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);

        float rate = request.getSpeechRate() / 100.0f;
        float pitch = request.getPitch() / 100.0f;

        try {
            for (TTSUtils.Chunk chunk : chunks) {
                if (stopRequested) break;
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) {
                    LogCollector.addLog("Error", "No engine found for: " + chunk.lang);
                    continue;
                }

                try {
                    if ("MYANMAR".equals(chunk.lang)) {
                        int res = targetEngine.setLanguage(new Locale("my", "MM"));
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            LogCollector.addLog("Lang", "my_MM failed, trying mya_MM");
                            res = targetEngine.setLanguage(new Locale("mya", "MM"));
                        }
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            LogCollector.addLog("Lang", "mya_MM failed, trying mmr_MM");
                            targetEngine.setLanguage(new Locale("mmr", "MM"));
                        }
                    } else if ("SHAN".equals(chunk.lang)) {
                        targetEngine.setLanguage(new Locale("shn", "MM")); 
                    } else {
                        targetEngine.setLanguage(Locale.US);
                    }
                } catch (Exception e) {
                    LogCollector.addLog("Exception", "SetLang Error: " + e.getMessage());
                }

                targetEngine.setSpeechRate(rate);
                targetEngine.setPitch(pitch);

                String utteranceId = String.valueOf(System.nanoTime());
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                
                CountDownLatch latch = new CountDownLatch(1);
                utteranceLatches.put(utteranceId, latch);

                int result = targetEngine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);
                
                // ဒီနေရာမှာ အရေးကြီးဆုံး Log ပါ
                if (result == TextToSpeech.ERROR) {
                    LogCollector.addLog("Speak", "❌ Speak ERROR for: " + chunk.lang);
                    utteranceLatches.remove(utteranceId);
                    continue;
                } 
                // else { LogCollector.addLog("Speak", "✅ Request sent: " + chunk.lang); }

                try {
                    long timeout = 2000 + (chunk.text.length() * 100L);
                    long waited = 0;
                    while (!stopRequested && waited < timeout) {
                        boolean done = latch.await(50, TimeUnit.MILLISECONDS);
                        if (done) break;
                        waited += 50;
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
            LogCollector.addLog("Exception", "Synthesize Loop: " + e.getMessage());
            e.printStackTrace();
        } finally {
            synchronized (callback) {
                callback.done();
            }
        }
    }

    @Override
    protected void onStop() {
        stopRequested = true;
        LogCollector.addLog("Service", "Stop Requested");
        
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
        LogCollector.addLog("Service", "Destroyed");
    }

    @Override protected int onIsLanguageAvailable(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
    @Override protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }
    @Override protected int onLoadLanguage(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
}

