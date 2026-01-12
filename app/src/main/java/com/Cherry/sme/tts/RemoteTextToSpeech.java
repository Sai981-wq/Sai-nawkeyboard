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
                syncVariable.close(); // အသံစထွက်လျှင် လမ်းကြောင်းပိတ်မည်
            }

            @Override
            public void onDone(String utteranceId) {
                LogCollector.addLog("EngineCallback", engineName + " DONE: " + utteranceId);
                syncVariable.open(); // အသံပြီးလျှင် လမ်းကြောင်းပြန်ဖွင့်မည်
            }

            @Override
            public void onError(String utteranceId) {
                LogCollector.addLog("EngineCallback", engineName + " ERROR: " + utteranceId);
                syncVariable.open(); // Error ဖြစ်လျှင်လည်း လမ်းကြောင်းပြန်ဖွင့်မည်
            }
        });
    }

    public void waitForCompletion(String text) {
        LogCollector.addLog("Sync", "Waiting for: " + engineName + " [" + text + "]");
        // အများဆုံး ၈ စက္ကန့် စောင့်မည်
        boolean success = syncVariable.block(8000); 
        if (!success) {
            LogCollector.addLog("Sync", "TIMEOUT: " + engineName + " failed to signal DONE");
        } else {
            LogCollector.addLog("Sync", "RELEASED: " + engineName);
        }
    }

    public void forceOpen() {
        syncVariable.open();
    }

    public String getEngineName() {
        return engineName;
    }
}

