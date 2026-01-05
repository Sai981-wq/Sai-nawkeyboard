package com.cherry.sme.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteTextToSpeech {

    private TextToSpeech tts;
    private final String engineName;
    private volatile boolean isInitialized = false;

    public RemoteTextToSpeech(Context context, String engineName) {
        this.engineName = engineName;
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true;
            }
        }, engineName);
    }

    public boolean isReady() {
        return isInitialized;
    }

    public void speakAndWait(String text, Bundle params) {
        if (!isInitialized || tts == null) return;

        final CountDownLatch latch = new CountDownLatch(1);
        String utteranceId = "ID_" + System.currentTimeMillis();

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                latch.countDown();
            }

            @Override
            public void onError(String utteranceId) {
                latch.countDown();
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                latch.countDown();
            }
        });

        int result = tts.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId);

        if (result == TextToSpeech.ERROR) {
            return;
        }

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public String getEngineName() {
        return engineName;
    }

    public void shutdown() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {
            }
        }
    }

    public void stop() {
        if (tts != null) {
            tts.stop();
        }
    }

    public int setSpeechRate(float speechRate) {
        return tts != null ? tts.setSpeechRate(speechRate) : TextToSpeech.ERROR;
    }

    public int setPitch(float pitch) {
        return tts != null ? tts.setPitch(pitch) : TextToSpeech.ERROR;
    }
}

