package com.cherry.sme.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

public class GetSampleText extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent data = new Intent();
        data.putExtra("sampleText", "Cherry SME TTS is active.");
        setResult(TextToSpeech.LANG_AVAILABLE, data);
        finish();
    }
}

