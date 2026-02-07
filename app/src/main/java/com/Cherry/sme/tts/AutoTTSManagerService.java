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
    private SharedPreferences prefs;
    private volatile boolean stopRequested = false;
    
    private final ConcurrentHashMap<String, CountDownLatch> utteranceLatches = new ConcurrentHashMap<>();

    private final UtteranceProgressListener globalListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
             LogCollector.addLog("Listener", "Started: " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
             LogCollector.addLog("Listener", "Done: " + utteranceId);
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
        LogCollector.clear(); 
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
        
        // စာသားအမှန်တကယ်ပါမပါ စစ်မယ်
        if (text == null || text.trim().isEmpty()) {
            callback.done();
            return;
        }

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
                    LogCollector.addLog("Error", "No engine for: " + chunk.lang);
                    continue;
                }

                try {
                    if ("MYANMAR".equals(chunk.lang)) {
                        
                        // 1. Language အရင်သတ်မှတ်မယ် (mya -> my -> mmr)
                        Locale[] localesToCheck = {
                            new Locale("mya", "MM"),
                            new Locale("mya"),
                            new Locale("my", "MM"),
                            new Locale("mmr", "MM")
                        };
                        
                        int langResult = TextToSpeech.LANG_NOT_SUPPORTED;
                        for (Locale loc : localesToCheck) {
                            langResult = targetEngine.setLanguage(loc);
                            if (langResult != TextToSpeech.LANG_MISSING_DATA && langResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                                // LogCollector.addLog("Lang", "Accepted: " + loc.toString());
                                break;
                            }
                        }

                        // 2. အရေးကြီးဆုံးအချက် - Voice ကိုပါ ရှာပြီး Set လုပ်မယ်
                        try {
                            Set<Voice> voices = targetEngine.getVoices();
                            if (voices != null) {
                                for (Voice v : voices) {
                                    String vName = v.getName().toLowerCase();
                                    // Voice နာမည်ထဲမှာ my, myanmar, burmese ပါရင် ရွေးမယ်
                                    if (vName.contains("my") || vName.contains("burmese") || vName.contains("mya")) {
                                        targetEngine.setVoice(v);
                                        // LogCollector.addLog("Voice", "Set Voice to: " + v.getName());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ve) {
                            LogCollector.addLog("VoiceError", ve.getMessage());
                        }

                    } else if ("SHAN".equals(chunk.lang)) {
                        targetEngine.setLanguage(new Locale("shn", "MM")); 
                    } else {
                        targetEngine.setLanguage(Locale.US);
                    }
                } catch (Exception e) {
                    LogCollector.addLog("LangError", e.getMessage());
                }

                targetEngine.setSpeechRate(rate);
                targetEngine.setPitch(pitch);

                String utteranceId = String.valueOf(System.nanoTime());
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                
                CountDownLatch latch = new CountDownLatch(1);
                utteranceLatches.put(utteranceId, latch);

                int result = targetEngine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);

                if (result == TextToSpeech.ERROR) {
                    LogCollector.addLog("Speak", "❌ ERROR for: " + chunk.lang);
                    utteranceLatches.remove(utteranceId);
                    continue;
                }

                try {
                    // Timeout တိုးပေးထားမယ် (3.5 Sec)
                    long timeout = 3500 + (chunk.text.length() * 100L);
                    long waited = 0;
                    while (!stopRequested && waited < timeout) {
                        boolean done = latch.await(100, TimeUnit.MILLISECONDS);
                        if (done) break;
                        waited += 100;
                    }
                    if (waited >= timeout) {
                        LogCollector.addLog("Timeout", "No audio from " + chunk.lang);
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
            LogCollector.addLog("Exception", "Loop: " + e.getMessage());
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

