package com.cherry.sme.tts;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RemoteTextToSpeech extends TextToSpeech {

    private final String engineName;
    private CountDownLatch latch;

    public RemoteTextToSpeech(Context context, OnInitListener listener, String engineName) {
        super(context, listener, engineName);
        this.engineName = engineName;
    }

    public boolean speakAndWait(String text, Bundle params, String utteranceId) {
        latch = new CountDownLatch(1);
        
        this.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                if (latch != null) latch.countDown();
            }

            @Override
            public void onError(String utteranceId) {
                if (latch != null) latch.countDown();
            }
        });

        int result = this.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId);

        if (result == TextToSpeech.ERROR) {
            return false;
        }

        try {
            return latch.await(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    public String getEngineName() {
        return engineName;
    }
}

