package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CheckVoiceData extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        List<String> checkList = Arrays.asList("eng-USA", "mya-MMR", "shn-MMR");
        ArrayList<String> available = new ArrayList<>(checkList);
        ArrayList<String> unavailable = new ArrayList<>();
        
        Intent data = new Intent();
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available);
        data.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable);
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data);
        finish();
    }
}

