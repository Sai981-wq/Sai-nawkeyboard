package com.cherry.sme.tts;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.SystemClock;
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
    private volatile boolean stopRequested = false;

    @Override
    public void onCreate() {
        super.onCreate();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        TTSUtils.loadMapping(this);

        initAllEngines();
    }

    private void initAllEngines() {
        shutdownEngines();

        String shanPkg = getBestEngine("pref_engine_shan");
        shanEngine = new RemoteTextToSpeech(this, status -> {}, shanPkg);

        String burmesePkg = getBestEngine("pref_engine_myanmar");
        burmeseEngine = new RemoteTextToSpeech(this, status -> {}, burmesePkg);

        String englishPkg = getBestEngine("pref_engine_english");
        englishEngine = new RemoteTextToSpeech(this, status -> {}, englishPkg);
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        String text = request.getText();
        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);

        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        synchronized (callback) {
            callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        }

        Bundle params = request.getParams();
        float rate = request.getSpeechRate() / 100.0f;
        float pitch = request.getPitch() / 100.0f;

        try {
            for (TTSUtils.Chunk chunk : chunks) {
                if (stopRequested) break;
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech targetEngine = getEngineByLang(chunk.lang);
                if (targetEngine == null) continue;

                targetEngine.setSpeechRate(rate);
                targetEngine.setPitch(pitch);

                String utteranceId = "ID_" + System.currentTimeMillis();
                targetEngine.speak(chunk.text, TextToSpeech.QUEUE_ADD, params, utteranceId);

                int waitStart = 0;
                while (!targetEngine.isSpeaking() && waitStart < 20 && !stopRequested) {
                    SystemClock.sleep(50);
                    waitStart++;
                }

                long startTime = System.currentTimeMillis();
                while (targetEngine.isSpeaking() && !stopRequested) {
                    if (System.currentTimeMillis() - startTime > 60000) break;
                    SystemClock.sleep(50);
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

