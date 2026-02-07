package com.cherry.sme.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;

public class RemoteTextToSpeech extends TextToSpeech {

    private String engineLabel;

    public RemoteTextToSpeech(Context context, String engineName) {
        super(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                LogCollector.addLog("RemoteTTS", engineName + " : Initialized SUCCESS ✅");
            } else {
                LogCollector.addLog("RemoteTTS", engineName + " : Initialized FAILED ❌");
            }
        }, engineName);
        this.engineLabel = engineName;
    }

    @Override
    public int setOnUtteranceProgressListener(android.speech.tts.UtteranceProgressListener listener) {
        return super.setOnUtteranceProgressListener(listener);
    }
    
    public String getLabel() {
        return engineLabel;
    }
}

