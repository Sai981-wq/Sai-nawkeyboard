package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;

public class CheckVoiceData extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> available = new ArrayList<>();
        available.add("eng-USA");
        available.add("mya-MMR");
        available.add("shn-MMR");
        
        Intent data = new Intent();
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<String>());
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data);
        finish();
    }
}

