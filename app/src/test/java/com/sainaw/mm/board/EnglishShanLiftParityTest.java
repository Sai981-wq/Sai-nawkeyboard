package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;

/** Real, unchanged original touch handler; no hardware microphone/network involved. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class EnglishShanLiftParityTest {
    public static class TrackingService extends SaiNawKeyboardService {
        final List<Integer> actions = new ArrayList<>();
        ShanVoiceInput testVoice;
        @Override public void handleInput(int code, Keyboard.Key key) {
            actions.add(code);
            if (code == -10 && testVoice != null) testVoice.toggle();
        }
    }
    @Test public void defaultEnglishAndShanFocusFirstAndActivateOnceOnLift() throws Exception {
        for (int language : new int[]{0, 2}) {
            try (Fixture f = new Fixture(language, null)) {
                f.hover(MotionEvent.ACTION_HOVER_ENTER, f.mic);
                f.hover(MotionEvent.ACTION_HOVER_MOVE, f.mic);
                assertTrue("Touch/focus must not activate", f.service.actions.isEmpty());
                f.hover(MotionEvent.ACTION_HOVER_EXIT, f.mic);
                assertEquals(java.util.Arrays.asList(-10), f.service.actions);
                f.hover(MotionEvent.ACTION_HOVER_EXIT, f.mic);
                assertEquals("No duplicate activation", 1, f.service.actions.size());
            }
        }
    }
    @Test public void liftDisabledPreferenceMatchesEnglishAndNeverActivatesOnHover() throws Exception {
        for (int language : new int[]{0, 2}) {
            try (Fixture f = new Fixture(language, false)) {
                f.gesture(f.mic);
                assertTrue(f.service.actions.isEmpty());
            }
        }
    }
    @Test public void liftingFromShanMicStopsActiveRecordingWithoutDoubleTap() throws Exception {
        try (Fixture f = new Fixture(2, true)) {
            ShanVoiceInput voice = f.voice();
            set(voice, "state", enumValue(voice, "RECORDING"));
            set(voice, "recording", true);
            f.hover(MotionEvent.ACTION_HOVER_ENTER, f.mic);
            f.hover(MotionEvent.ACTION_HOVER_MOVE, f.mic);
            assertEquals("RECORDING", get(voice, "state").toString());
            f.hover(MotionEvent.ACTION_HOVER_EXIT, f.mic);
            assertEquals("STOPPING", get(voice, "state").toString());
            assertFalse((Boolean)get(voice, "recording"));
            assertEquals(java.util.Arrays.asList(-10), f.service.actions);
        }
    }
    @Test public void liftingUnrelatedKeyDoesNotStopRecording() throws Exception {
        try (Fixture f = new Fixture(2, true)) {
            ShanVoiceInput voice = f.voice();
            set(voice, "state", enumValue(voice, "RECORDING")); set(voice, "recording", true);
            Keyboard.Key letter = f.keyboard.getKeys().get(2);
            f.gesture(letter);
            assertEquals(java.util.Arrays.asList(letter.codes[0]), f.service.actions);
            assertEquals("RECORDING", get(voice, "state").toString());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ServiceController<TrackingService> controller = Robolectric.buildService(TrackingService.class).create();
        final TrackingService service = controller.get();
        final KeyboardView view = new KeyboardView(service, null);
        final Keyboard keyboard;
        final Keyboard.Key mic;
        long time;
        Fixture(int language, Boolean lift) throws Exception {
            SharedPreferences prefs = service.getSharedPreferences("ParityPrefs", Context.MODE_PRIVATE);
            prefs.edit().clear().commit();
            if (lift != null) prefs.edit().putBoolean("lift_to_type", lift).commit();
            SaiNawLayoutManager layout = new SaiNawLayoutManager(service);
            keyboard = new Keyboard(service, language == 0 ? R.xml.qwerty : R.xml.shan);
            view.setKeyboard(keyboard);
            layout.currentLanguageId = language;
            set(layout, "currentKeyboard", keyboard);
            set(service, "layoutManager", layout); set(service, "keyboardView", view);
            SaiNawTouchHandler touch = new SaiNawTouchHandler(service, layout,
                    new SaiNawFeedbackManager(service), new SaiNawEmojiManager(service));
            touch.loadSettings(prefs);
            set(service, "touchHandler", touch);
            mic = keyboard.getKeys().get(1);
            assertEquals(-10, mic.codes[0]);
        }
        ShanVoiceInput voice() {
            service.testVoice = new ShanVoiceInput(service, new LinearLayout(service), false,
                    () -> { throw new AssertionError("No network permitted"); });
            service.testVoice.setAvailable(true);
            return service.testVoice;
        }
        void hover(int action, Keyboard.Key key) {
            float x=view.getPaddingLeft()+key.x+key.width/2f;
            float y=view.getPaddingTop()+key.y+key.height/2f;
            MotionEvent e = MotionEvent.obtain(++time, time, action, x, y, 0);
            try { assertTrue(service.handleKeyboardHover(e)); } finally { e.recycle(); }
        }
        void gesture(Keyboard.Key key) { hover(MotionEvent.ACTION_HOVER_ENTER,key); hover(MotionEvent.ACTION_HOVER_MOVE,key); hover(MotionEvent.ACTION_HOVER_EXIT,key); }
        @Override public void close() {
            if (service.testVoice != null) service.testVoice.destroy();
            controller.destroy();
        }
    }
    private static Object enumValue(Object voice, String name) throws Exception {
        for (Object v : get(voice,"state").getClass().getEnumConstants()) if (v.toString().equals(name)) return v;
        throw new AssertionError(name);
    }
    private static Field field(Object object, String name) throws Exception {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object o,String name) throws Exception { return field(o,name).get(o); }
    private static void set(Object o,String name,Object value) throws Exception { field(o,name).set(o,value); }
}
