package com.cherry.sme.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteTextToSpeech extends TextToSpeech {

    private final String engineName;
    private volatile boolean isInitialized = false;

    public RemoteTextToSpeech(Context context, String engineName) {
        super(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true;
            }
        }, engineName);
        this.engineName = engineName;
    }

    public boolean isReady() {
        return isInitialized;
    }

    public void speakAndWait(String text, Bundle params) {
        if (!isInitialized) return;

        final CountDownLatch latch = new CountDownLatch(1);
        String utteranceId = "ID_" + System.currentTimeMillis();

        this.setOnUtteranceProgressListener(new UtteranceProgressListener() {
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

        int result = this.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId);

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

    @Override
    public void shutdown() {
        try {
            super.shutdown();
        } catch (Exception e) {
        }
    }
}

