package com.cherry.sme.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;

public class RemoteTextToSpeech extends TextToSpeech {

    public RemoteTextToSpeech(Context context, String engineName) {
        super(context, status -> {}, engineName);
    }

    @Override
    public int setOnUtteranceProgressListener(android.speech.tts.UtteranceProgressListener listener) {
        return super.setOnUtteranceProgressListener(listener);
    }
}

