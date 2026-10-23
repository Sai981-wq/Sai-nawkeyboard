package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VoiceConsentSettingsTest {
    @Test public void settingsHasNoVoicePrivacySectionOrEntry() {
        SettingsActivity activity=Robolectric.buildActivity(SettingsActivity.class).setup().get();
        String text=allText(activity.findViewById(android.R.id.content)).toLowerCase(java.util.Locale.ROOT);
        assertFalse(text.contains("voice typing"));
        assertFalse(text.contains("privacy & microphone"));
        assertNotNull(activity.findViewById(R.id.btn_languages));
        assertNotNull(activity.findViewById(R.id.btn_accessibility));
        activity.finish();
    }
    @Test public void shortOnlineNoticeHasNoTutorialDetailsOrTimeWarning() {
        ShanVoiceConsentActivity activity=Robolectric.buildActivity(ShanVoiceConsentActivity.class).create().get();
        String text=allText(activity.findViewById(android.R.id.content));
        assertEquals("Voice Typing",activity.getTitle().toString());
        assertTrue(text.contains("အင်တာနက်"));
        assertTrue(text.contains("အလိုအလျောက် မဖမ်းပါ"));
        assertFalse(text.contains("https://"));
        assertFalse(text.contains("2 minutes"));
        assertFalse(text.contains("မိနစ်"));
        assertFalse(text.contains("အသေးစိတ်"));
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        activity.finish();
    }
    @Test public void agreeRequestsAndroidMicrophonePermissionButDoesNotStartCapture() {
        ShanVoiceConsentActivity activity=Robolectric.buildActivity(ShanVoiceConsentActivity.class).create().get();
        Shadows.shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        Button agree=findButton(activity.findViewById(android.R.id.content),"အသံကို အင်တာနက်သို့ ပို့ရန် သဘောတူပြီး မိုက်ခွင့်ပြုမည်");
        assertNotNull(agree); agree.performClick();
        org.robolectric.shadows.ShadowActivity.PermissionsRequest request=Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request); assertEquals(71,request.requestCode);
        assertArrayEquals(new String[]{Manifest.permission.RECORD_AUDIO},request.requestedPermissions);
        assertTrue(activity.getSharedPreferences(ShanVoiceConsentActivity.PREFS,Context.MODE_PRIVATE).getBoolean(ShanVoiceConsentActivity.CONSENT,false));
        android.content.Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", launched.getAction());
        activity.onRequestPermissionsResult(71,new String[]{Manifest.permission.RECORD_AUDIO},new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
        assertTrue(activity.isFinishing());
    }
    @Test public void declineRevokesConsentAndClosesWithoutRecording() {
        ShanVoiceConsentActivity activity=Robolectric.buildActivity(ShanVoiceConsentActivity.class).create().get();
        activity.getSharedPreferences(ShanVoiceConsentActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean(ShanVoiceConsentActivity.CONSENT,true).commit();
        Button decline=findButton(activity.findViewById(android.R.id.content),"သဘောမတူ / သဘောတူညီချက်ရုပ်သိမ်းမည်");
        assertNotNull(decline); decline.performClick();
        assertFalse(activity.getSharedPreferences(ShanVoiceConsentActivity.PREFS,Context.MODE_PRIVATE).getBoolean(ShanVoiceConsentActivity.CONSENT,true));
        assertTrue(activity.isFinishing()); assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
    private static String allText(View view) {
        StringBuilder b=new StringBuilder();
        if(view instanceof TextView)b.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)b.append(allText(((ViewGroup)view).getChildAt(i)));
        return b.toString();
    }
    private static Button findButton(View view,String text) {
        if(view instanceof Button && text.equals(((Button)view).getText().toString()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            Button found=findButton(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
}
