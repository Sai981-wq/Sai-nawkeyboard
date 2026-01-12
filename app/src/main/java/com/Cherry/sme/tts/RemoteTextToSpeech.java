package com.cherry.sme.tts;

import android.content.Context;
import android.os.ConditionVariable;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

public class RemoteTextToSpeech extends TextToSpeech {

    private final ConditionVariable syncVariable = new ConditionVariable(true);
    private final String engineName;

    public RemoteTextToSpeech(Context context, OnInitListener listener, String engineName) {
        super(context, listener, engineName);
        this.engineName = engineName;

        this.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                LogCollector.addLog("EngineCallback", engineName + " STARTED: " + utteranceId);
                syncVariable.close();
            }

            @Override
            public void onDone(String utteranceId) {
                LogCollector.addLog("EngineCallback", engineName + " DONE: " + utteranceId);
                syncVariable.open();
            }

            @Override
            public void onError(String utteranceId) {
                LogCollector.addLog("EngineCallback", engineName + " ERROR: " + utteranceId);
                syncVariable.open();
            }
        });
    }

    public void waitForCompletion(String text) {
        LogCollector.addLog("Sync", "Waiting for: " + engineName + " to finish [" + text + "]");
        // ၈ စက္ကန့်ထက်ပိုမစောင့်ဘဲ timeout ထားထားပါတယ်
        boolean success = syncVariable.block(8000); 
        if (!success) {
            LogCollector.addLog("Sync", "TIMEOUT: " + engineName + " failed to signal DONE");
        } else {
            LogCollector.addLog("Sync", "RELEASED: " + engineName + " finished or was ready");
        }
    }

    public void forceOpen() {
        LogCollector.addLog("Sync", "Force opening: " + engineName);
        syncVariable.open();
    }
}

