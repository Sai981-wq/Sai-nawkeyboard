package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.Field;

/** Real onCreateInputView listener -> handleInput -> startVoiceInput -> toggle, no hardware/network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VoiceServiceToggleTest {
    private ServiceController<SaiNawKeyboardService> controller;
    private SaiNawKeyboardService service;
    private ShanVoiceInput voice;
    private AccessibilityNodeProviderCompat provider;
    @Before public void setup() throws Exception {
        controller=Robolectric.buildService(SaiNawKeyboardService.class).create(); service=controller.get();
        LinearLayout root=(LinearLayout)service.onCreateInputView();
        BaseInputConnection connection=new BaseInputConnection(new View(service),true);
        set(service,"mInputConnection",connection); set(service,"mStartedInputConnection",connection);
        EditorInfo info=new EditorInfo();info.inputType=InputType.TYPE_CLASS_TEXT;set(service,"mInputEditorInfo",info);
        SaiNawLayoutManager layout=(SaiNawLayoutManager)get(service,"layoutManager"); layout.currentLanguageId=2;
        SaiNawAccessibilityHelper helper=(SaiNawAccessibilityHelper)get(service,"accessibilityHelper");
        helper.setKeyboard(layout.shanKeyboard,2,false);
        KeyboardView host=root.findViewById(R.id.keyboard_view);
        provider=helper.getAccessibilityNodeProvider(host);
        voice=(ShanVoiceInput)get(service,"shanVoiceInput");voice.setAvailable(true);
        AccessibilityManager am=(AccessibilityManager)service.getSystemService(Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(am).setEnabled(true);Shadows.shadowOf(am).setTouchExplorationEnabled(true);
        assertNotNull(service.getCurrentInputConnection());
    }
    @After public void cleanup(){controller.destroy();}
    @Test public void sameAccessibleKeyStartsImmediatelyAndCanCancelBeforeMicOpens() throws Exception {
        service.getSharedPreferences(ShanVoiceConsentActivity.PREFS,Context.MODE_PRIVATE).edit().putBoolean(ShanVoiceConsentActivity.CONSENT,true).commit();
        Shadows.shadowOf(service.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        assertTrue(provider.performAction(1,AccessibilityNodeInfoCompat.ACTION_CLICK,null));
        assertEquals("STARTING",get(voice,"state").toString());
        assertTrue(provider.performAction(1,AccessibilityNodeInfoCompat.ACTION_CLICK,null));
        assertEquals("IDLE",get(voice,"state").toString());
        assertFalse(voice.isCapturePending());
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }
    @Test public void voiceFocusDoesNotStopButSameVoiceClickStopsImmediately() throws Exception {
        recording();
        assertTrue(provider.performAction(1,AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS,null));
        assertEquals("RECORDING",get(voice,"state").toString());
        assertEquals("Voice Typing",provider.createAccessibilityNodeInfo(1).getContentDescription().toString());
        assertTrue(provider.performAction(1,AccessibilityNodeInfoCompat.ACTION_CLICK,null));
        assertEquals("STOPPING",get(voice,"state").toString());
        assertFalse((Boolean)get(voice,"recording"));
        assertTrue(provider.performAction(1,AccessibilityNodeInfoCompat.ACTION_CLICK,null));
        assertEquals("STOPPING",get(voice,"state").toString());
    }
    @Test public void regularTouchKeyUsesSameToggleAndMissingPermissionNeverCaptures() throws Exception {
        recording();service.onKey(-10,new int[]{-10});
        assertEquals("STOPPING",get(voice,"state").toString());
        voice.cancel();
        Shadows.shadowOf(service.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        service.onKey(-10,new int[]{-10});
        assertEquals("IDLE",get(voice,"state").toString());
        assertEquals(ShanVoiceConsentActivity.class.getName(),Shadows.shadowOf(service).getNextStartedActivity().getComponent().getClassName());
    }
    @Test public void captureWaitsForActivationSettleAndReadyBell() throws Exception {
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        set(voice, "captureFactory", (ShanVoiceInput.CaptureFactory) () -> {
            opens.incrementAndGet(); throw new IllegalStateException("Synthetic capture seam only");
        });
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> null);
        service.getSharedPreferences(ShanVoiceConsentActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ShanVoiceConsentActivity.CONSENT, true).commit();
        Shadows.shadowOf(service.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        service.onKey(-10, new int[]{-10});
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(179));
        assertEquals("STARTING", get(voice, "state").toString()); assertEquals(0, opens.get());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
        assertEquals("No capture before ready bell's 300ms window", 0, opens.get());
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500));
        ((java.util.concurrent.ExecutorService)get(voice, "audioWorker")).submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, opens.get());
        assertTrue("TalkBack remains enabled", ((AccessibilityManager)service.getSystemService(Context.ACCESSIBILITY_SERVICE)).isEnabled());
    }
    @Test public void shanMicDoesNotPlayOrdinaryKeyClickButEnglishStillDoes() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
        SaiNawFeedbackManager fake = new SaiNawFeedbackManager(service) {
            @Override public void playSound(int code) { clicks.incrementAndGet(); }
        };
        set(service, "feedbackManager", fake);
        recording(); service.onKey(-10, new int[]{-10});
        assertEquals(0, clicks.get());
        SaiNawLayoutManager layout=(SaiNawLayoutManager)get(service,"layoutManager");
        layout.currentLanguageId=0;
        service.onKey(-10,new int[]{-10});
        assertEquals(1, clicks.get());
    }
    private void recording() throws Exception {
        Object state=get(voice,"state");
        for(Object v:state.getClass().getEnumConstants())if(v.toString().equals("RECORDING"))set(voice,"state",v);
        set(voice,"recording",true);set(voice,"openingMicrophone",true);
    }
    private static Object get(Object target,String name)throws Exception {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static void set(Object target,String name,Object value)throws Exception {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
}
