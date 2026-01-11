package com.cherry.sme.tts;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Bundle;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

public class RemoteTextToSpeech extends TextToSpeech {

    private boolean isSpeaking = false;
    private final String engineName;
    private SynthesisCallback currentCallback;

    public RemoteTextToSpeech(Context context, String engineName) {
        super(context, status -> {}, engineName);
        this.engineName = engineName;

        this.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isSpeaking = true;
                if (currentCallback != null) {
                    currentCallback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
                }
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking = false;
                if (currentCallback != null) {
                    currentCallback.done();
                    currentCallback = null;
                }
            }

            @Override
            public void onError(String utteranceId) {
                isSpeaking = false;
                if (currentCallback != null) {
                    currentCallback.error();
                    currentCallback = null;
                }
            }
        });
    }

    public void speakWithCallback(String text, int queueMode, Bundle params, SynthesisCallback callback) {
        this.currentCallback = callback;
        this.speak(text, queueMode, params, params.getString(Engine.KEY_PARAM_UTTERANCE_ID));
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }

    public String getEngineName() {
        return engineName;
    }
}

