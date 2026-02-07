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
        data.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, "မႂ်ႇသုင်ၶႃႈ၊ မင်္ဂလာပါ၊ Cherry SME TTS အဆင်သင့် ဖြစ်နေပါပြီ။ This is a sample text.");
        
        setResult(Activity.RESULT_OK, data);
        finish();
    }
}

