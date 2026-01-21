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

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;

    private boolean isRestarting = false;
    private Handler mainHandler;
    private volatile boolean stopRequested = false;
    private boolean enginesReady = false;

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
        enginesReady = false;
        shutdownEngines();
        String shanPkg = getBestEngine("pref_engine_shan");
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
            enginesReady = true;
        }, englishPkg);
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        if (isRestarting || !enginesReady) {
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
        Bundle params = request.getParams();
        float rate = request.getSpeechRate() / 100.0f;
        float pitch = request.getPitch() / 100.0f;

        try {
            StringBuilder buffer = new StringBuilder();
            RemoteTextToSpeech currentEngine = null;
            String currentLang = "";

            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested) break;
                TTSUtils.Chunk chunk = chunks.get(i);
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) continue;

                if (currentEngine != null && (!chunk.lang.equals(currentLang) || buffer.length() > 400)) {
                    speakSync(currentEngine, buffer.toString(), params, rate, pitch);
                    buffer.setLength(0);
                }

                buffer.append(chunk.text).append(" ");
                currentEngine = targetEngine;
                currentLang = chunk.lang;
            }

            if (buffer.length() > 0 && currentEngine != null && !stopRequested) {
                speakSync(currentEngine, buffer.toString(), params, rate, pitch);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            callback.done();
        }
    }

    private RemoteTextToSpeech getEngineByLang(String lang) {
        if ("SHAN".equals(lang)) return shanEngine;
        if ("MYANMAR".equals(lang)) return burmeseEngine;
        return englishEngine;
    }

    private void speakSync(RemoteTextToSpeech engine, String text, Bundle params, float rate, float pitch) {
        if (stopRequested || engine == null) return;
        try {
            engine.setSpeechRate(rate);
            engine.setPitch(pitch);
            String id = "U_" + System.currentTimeMillis();
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);

            int w = 0;
            while (!engine.isSpeaking() && w < 40 && !stopRequested) {
                Thread.sleep(50);
                w++;
            }
            while (engine.isSpeaking() && !stopRequested) {
                Thread.sleep(50);
            }
        } catch (Exception e) {}
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
                if (!i.serviceInfo.packageName.equals(getPackageName())) return i.serviceInfo.packageName;
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
    }

    @Override
    protected void onStop() {
        stopRequested = true;
        try { if (shanEngine != null) shanEngine.stop(); } catch (Exception e) {}
        try { if (burmeseEngine != null) burmeseEngine.stop(); } catch (Exception e) {}
        try { if (englishEngine != null) englishEngine.stop(); } catch (Exception e) {}
    }

    @Override protected int onIsLanguageAvailable(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
    @Override protected String[] onGetLanguage() { return new String[]{"eng", "USA", ""}; }
    @Override protected int onLoadLanguage(String l, String c, String v) { return TextToSpeech.LANG_AVAILABLE; }
}

