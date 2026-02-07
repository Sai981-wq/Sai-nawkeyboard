package com.cherry.sme.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;

public class RemoteTextToSpeech extends TextToSpeech {

    // Constructor အဟောင်း (မသုံးတော့ပါ)
    // public RemoteTextToSpeech(Context context, String engineName) { ... }

    // Constructor အသစ် - Listener လက်ခံနိုင်အောင် ပြင်ထားသည်
    public class RemoteTextToSpeech extends TextToSpeech {
        public RemoteTextToSpeech(Context context, OnInitListener listener, String engineName) {
            super(context, listener, engineName);
        }
    }
}

