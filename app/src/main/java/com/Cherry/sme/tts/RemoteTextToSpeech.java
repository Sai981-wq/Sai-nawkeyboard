package com.cherry.sme.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

public class RemoteTextToSpeech extends TextToSpeech {

    private boolean isSpeaking = false;
    private final String engineName;

    public RemoteTextToSpeech(Context context, OnInitListener listener, String engineName) {
        super(context, listener, engineName);
        this.engineName = engineName;

        this.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isSpeaking = true;
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking = false;
            }

            @Override
            public void onError(String utteranceId) {
                isSpeaking = false;
            }
        });
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public String getEngineName() {
        return engineName;
    }
}

