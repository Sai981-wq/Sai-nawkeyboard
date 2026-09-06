package com.cherry.sme.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;

public class RemoteTextToSpeech extends TextToSpeech {

    public RemoteTextToSpeech(Context context, OnInitListener listener, String engineName) {
        super(context, listener, engineName);
    }
}

