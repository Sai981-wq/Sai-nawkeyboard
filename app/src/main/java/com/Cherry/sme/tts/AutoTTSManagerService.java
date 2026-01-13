package com.cherry.sme.tts;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import java.util.List;
import java.util.Locale;

public class AutoTTSManagerService extends TextToSpeechService {

    private RemoteTextToSpeech shanEngine;
    private RemoteTextToSpeech burmeseEngine;
    private RemoteTextToSpeech englishEngine;
    private SharedPreferences prefs;

    private PowerManager.WakeLock wakeLock;

    private volatile boolean stopRequested = false;
    private String mLanguage = "eng";
    private String mCountry = "USA";
    private String mVariant = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoTTS:SpeakingLock");
        }

        initEnginesStepByStep();
    }

    private void initEnginesStepByStep() {
        String shanPkg = getBestEngine("pref_engine_shan");
        shanEngine = new RemoteTextToSpeech(this, status -> initBurmeseEngine(), shanPkg);
    }

    private void initBurmeseEngine() {
        String burmesePkg = getBestEngine("pref_engine_myanmar");
        burmeseEngine = new RemoteTextToSpeech(this, status -> initEnglishEngine(), burmesePkg);
    }

    private void initEnglishEngine() {
        String englishPkg = getBestEngine("pref_engine_english");
        englishEngine = new RemoteTextToSpeech(this, status -> {}, englishPkg);
    }

    private String getBestEngine(String prefKey) {
        String pkg = prefs.getString(prefKey, null);
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(getPackageName())) {
            return pkg;
        }
        String sysDef = Settings.Secure.getString(getContentResolver(), "tts_default_synth");
        if (sysDef != null && !sysDef.equals(getPackageName())) {
            return sysDef;
        }
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(intent, 0);
            for (ResolveInfo info : services) {
                String p = info.serviceInfo.packageName;
                if (!p.equals(getPackageName())) return p;
            }
        } catch (Exception e) {}
        return "com.google.android.tts";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (shanEngine != null) shanEngine.shutdown();
        if (burmeseEngine != null) burmeseEngine.shutdown();
        if (englishEngine != null) englishEngine.shutdown();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onStop() {
        stopRequested = true;
        if (shanEngine != null) shanEngine.stop();
        if (burmeseEngine != null) burmeseEngine.stop();
        if (englishEngine != null) englishEngine.stop();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopRequested = false;
        
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }

        String text = request.getText();
        List<TTSUtils.Chunk> chunks = TTSUtils.splitHelper(text);

        if (chunks.isEmpty()) {
            callback.done();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            return;
        }

        callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
        float userRate = request.getSpeechRate() / 100.0f;
        float userPitch = request.getPitch() / 100.0f;
        Bundle originalParams = request.getParams();

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopRequested) break;
                TTSUtils.Chunk chunk = chunks.get(i);
                if (chunk.text.trim().isEmpty()) continue;

                RemoteTextToSpeech engine;
                if (chunk.lang.equals("SHAN")) engine = shanEngine;
                else if (chunk.lang.equals("MYANMAR")) engine = burmeseEngine;
                else engine = englishEngine;

                if (engine == null) continue;

                engine.setSpeechRate(userRate);
                engine.setPitch(userPitch);

                Bundle params = new Bundle(originalParams);
                String uId = "CH_" + System.currentTimeMillis() + "_" + i;
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);

                engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, params, uId);

                int startWait = 0;
                while (!engine.isSpeaking() && startWait < 500 && !stopRequested) {
                    Thread.sleep(10);
                    startWait++;
                }

                while (!stopRequested) {
                    if (!engine.isSpeaking()) {
                        Thread.sleep(100); 
                        if (!engine.isSpeaking()) {
                            break;
                        }
                    }
                    Thread.sleep(10);
                }
                
                if (!stopRequested) {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            callback.done();
        }
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        mLanguage = "eng";
        mCountry = "USA";
        mVariant = "";
        return TextToSpeech.LANG_AVAILABLE;
    }
}

