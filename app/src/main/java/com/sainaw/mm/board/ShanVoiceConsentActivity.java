package com.sainaw.mm.board;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Notice/permission only: never starts a recorder or makes a network request. */
public class ShanVoiceConsentActivity extends Activity {
    static final String PREFS = "ShanVoicePrivacy";
    static final String CONSENT = "consent_auto_upload_v2";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Voice Typing");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        TextView notice = new TextView(this);
        notice.setTextSize(18);
        notice.setText("အသံစာရိုက်ရာတွင် သင်ပြောသောအသံကို အင်တာနက်မှတစ်ဆင့် ပြင်ပဝန်ဆောင်မှုသို့ ပို့၍ ရှမ်းစာအဖြစ် ပြောင်းလဲပါမည်။ မိုက်ခွင့်ပြုခြင်းတစ်ခုတည်းဖြင့် အသံကို အလိုအလျောက် မဖမ်းပါ။");
        root.addView(notice);
        addButton(root, "အသံကို အင်တာနက်သို့ ပို့ရန် သဘောတူပြီး မိုက်ခွင့်ပြုမည်", () -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CONSENT, true).apply();
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 71);
            } else finish();
        });
        addButton(root, "သဘောမတူ / သဘောတူညီချက်ရုပ်သိမ်းမည်", () -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CONSENT, false).apply();
            finish();
        });
        setContentView(scroll);
    }
    private void addButton(LinearLayout root, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(v -> action.run());
        root.addView(button);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 71) {
            finish();
        }
    }
}
