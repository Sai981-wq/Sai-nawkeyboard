package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.inputmethodservice.KeyboardView;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;

/** Hover routing must not turn focus, lift, or unrelated keys into a voice stop action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VoiceTouchReleaseTest {
    private static MotionEvent event(int action, long t, float x) {
        return MotionEvent.obtain(t, t, action, x, 17f, 0);
    }

    @Test public void hoverRoutesAndEntryMoveDoNotStopAnActiveCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.recording();
            f.hover(MotionEvent.ACTION_HOVER_ENTER);
            f.hover(MotionEvent.ACTION_HOVER_MOVE);
            f.hover(MotionEvent.ACTION_HOVER_EXIT);
            assertEquals("RECORDING", get(f.voice, "state").toString());
            assertTrue((Boolean) get(f.voice, "recording"));
            assertEquals("Hover events always reach touch handler", 3, f.touch.actions.size());
        }
    }

    @Test public void hoverWhileUploadingStillRoutesNormallyWithoutNavigationGate() throws Exception {
        try (Fixture f = new Fixture()) {
            state(f.voice, "UPLOADING");
            f.normalGesture();
            assertEquals(3, f.touch.actions.size());
            assertEquals("UPLOADING", get(f.voice, "state").toString());
        }
    }

    @Test public void directKeyActionIsExplicitAndOnlyItCanToggleCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.recording();
            assertEquals("RECORDING", get(f.voice, "state").toString());
            // The service key listener calls toggle for the voice-key primary code; hover is not that action.
            f.voice.toggle();
            assertEquals("STOPPING", get(f.voice, "state").toString());
            assertFalse((Boolean) get(f.voice, "recording"));
        }
    }

    private static class TrackingTouch extends SaiNawTouchHandler {
        final List<Integer> actions = new ArrayList<>();
        TrackingTouch(SaiNawKeyboardService service) { super(service, null, null, null); }
        @Override public void handleHover(MotionEvent e) { actions.add(e.getActionMasked()); }
        @Override public void reset() { }
    }
    private static class TrackingAccessibility extends SaiNawAccessibilityHelper {
        int hitTests;
        TrackingAccessibility(KeyboardView host) {
            super(host, new OnAccessibilityKeyListener() {
                @Override public void onAccessibilityKeyClick(int code, android.inputmethodservice.Keyboard.Key key) { }
                @Override public String onCursorMoveAndGetText(boolean forward, int granularity) { return ""; }
                @Override public void onCustomAnnounce(String text) { }
            }, new SaiNawPhoneticManager(host.getContext()), new SaiNawEmojiManager(host.getContext()));
        }
        @Override protected int getVirtualViewAt(float x, float y) { hitTests++; return super.getVirtualViewAt(x, y); }
    }
    private static class Fixture implements AutoCloseable {
        final ServiceController<SaiNawKeyboardService> controller = Robolectric.buildService(SaiNawKeyboardService.class).create();
        final SaiNawKeyboardService service = controller.get();
        final LinearLayout root = (LinearLayout) service.getLayoutInflater().inflate(R.layout.input_view, null);
        final ShanVoiceInput voice = new ShanVoiceInput(service, root, false,
                () -> { throw new AssertionError("No audio upload allowed"); });
        final TrackingTouch touch = new TrackingTouch(service);
        final SaiNawLayoutManager layout = new SaiNawLayoutManager(service);
        final TrackingAccessibility helper;
        long time;
        int expectedHitTests;
        Fixture() throws Exception {
            AccessibilityManager manager = (AccessibilityManager) service.getSystemService(Context.ACCESSIBILITY_SERVICE);
            Shadows.shadowOf(manager).setEnabled(true);
            Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
            layout.initKeyboards(service.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE));
            layout.currentLanguageId = 2;
            set(service, "layoutManager", layout);
            KeyboardView keyboard = root.findViewById(R.id.keyboard_view);
            set(service, "keyboardView", keyboard);
            helper = new TrackingAccessibility(keyboard);
            helper.setKeyboard(layout.shanKeyboard, 2, false);
            set(service, "accessibilityHelper", helper);
            voice.setAvailable(true);
            set(service, "shanVoiceInput", voice);
            set(service, "touchHandler", touch);
        }
        void recording() throws Exception {
            state(voice, "RECORDING"); set(voice, "recording", true); set(voice, "openingMicrophone", true);
        }
        void hover(int action) {
            MotionEvent e = event(action, ++time, 20);
            try {
                assertTrue(service.handleKeyboardHover(e));
                if (action != MotionEvent.ACTION_HOVER_EXIT) expectedHitTests++;
                assertEquals("Hit testing must be delivered immediately", expectedHitTests, helper.hitTests);
            } finally { e.recycle(); }
        }
        void normalGesture() { hover(MotionEvent.ACTION_HOVER_ENTER); hover(MotionEvent.ACTION_HOVER_MOVE); hover(MotionEvent.ACTION_HOVER_EXIT); }
        @Override public void close() { voice.destroy(); controller.destroy(); }
    }
    private static Object get(Object o, String n) throws Exception { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    private static void set(Object o, String n, Object v) throws Exception { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
    private static void state(ShanVoiceInput o, String name) throws Exception {
        for (Object value : get(o, "state").getClass().getEnumConstants()) if (value.toString().equals(name)) { set(o, "state", value); return; }
        throw new AssertionError(name);
    }
}
