package com.sainaw.mm.board;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class LayoutRegressionTest {
    private ServiceController<SaiNawKeyboardService> controller;
    private SaiNawKeyboardService service;
    private SaiNawLayoutManager manager;
    private ShanVoiceInput voice;
    private LinearLayout root;

    @Before public void setUp() {
        controller = Robolectric.buildService(SaiNawKeyboardService.class).create();
        service = controller.get();
        manager = new SaiNawLayoutManager(service);
        manager.initKeyboards(service.getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE));
        set(service, "layoutManager", manager);
        EditorInfo editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        setCurrentEditor(editor);
        root = (LinearLayout) service.getLayoutInflater().inflate(R.layout.input_view, null);
        voice = new ShanVoiceInput(service, root, false);
        set(service, "shanVoiceInput", voice);
    }

    @After public void tearDown() {
        if (voice != null) voice.destroy();
        if (controller != null) controller.destroy();
    }

    @Test public void lightAndDarkKeyboardResourcesInflateWithOriginalStructureAndDimensions() {
        for (int id : new int[]{R.layout.input_view, R.layout.input_view_dark}) {
            LinearLayout layout = (LinearLayout) service.getLayoutInflater().inflate(id, null);
            assertEquals(2, layout.getChildCount());
            LinearLayout candidates = layout.findViewById(R.id.candidates_container);
            KeyboardView keyboard = layout.findViewById(R.id.keyboard_view);
            assertNotNull(candidates);
            assertNotNull(keyboard);
            assertEquals(48, candidates.getLayoutParams().height);
            assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, keyboard.getLayoutParams().width);
            assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, keyboard.getLayoutParams().height);
            assertEquals(8, keyboard.getPaddingTop());
            assertEquals(8, keyboard.getPaddingBottom());
        }
    }

    @Test public void voiceAvailabilityDoesNotAddViewsAndLanguageChangeCancelsStartingCapture() throws Exception {
        assertEquals(2, root.getChildCount());
        String english = keys(manager.qwertyKeyboard);
        manager.currentLanguageId = 0;
        invoke(service, "updateHelperState");
        assertEquals(2, root.getChildCount());
        assertEquals(english, keys(manager.qwertyKeyboard));

        manager.currentLanguageId = 2;
        invoke(service, "updateHelperState");
        assertTrue((Boolean) field(voice, "visible"));
        assertEquals(2, root.getChildCount());
        int generation = (Integer) field(voice, "generation");
        set(voice, "state", enumValue(field(voice, "state").getClass(), "STARTING"));
        manager.currentLanguageId = 1;
        invoke(service, "updateHelperState");
        assertEquals(2, root.getChildCount());
        assertEquals("IDLE", field(voice, "state").toString());
        assertTrue((int) field(voice, "generation") > generation);
        assertEquals(english, keys(manager.qwertyKeyboard));
    }

    @Test public void shanMicMatchesQwertyMicOnNormalAndShiftRows() {
        for (Keyboard shan : new Keyboard[]{manager.shanKeyboard, manager.shanShiftKeyboard}) {
            Keyboard.Key expected = manager.qwertyKeyboard.getKeys().get(1);
            Keyboard.Key expectedBlank = manager.qwertyKeyboard.getKeys().get(0);
            int count = 0;
            List<Keyboard.Key> keys = shan.getKeys();
            assertEquals(-100, keys.get(0).codes[0]);
            assertEquals(-10, keys.get(1).codes[0]);
            assertEquals(expectedBlank.width, keys.get(0).width, 0.01f);
            assertEquals(expected.width, keys.get(1).width, 0.01f);
            assertEquals(expected.codes[0], keys.get(1).codes[0]);
            assertNotNull(expected.icon);
            assertNotNull(keys.get(1).icon);
            assertEquals(Shadows.shadowOf(expected.icon).getCreatedFromResId(),
                    Shadows.shadowOf(keys.get(1).icon).getCreatedFromResId());
            assertEquals(expected.icon.getIntrinsicWidth(), keys.get(1).icon.getIntrinsicWidth());
            assertEquals(expected.icon.getIntrinsicHeight(), keys.get(1).icon.getIntrinsicHeight());
            for (Keyboard.Key key : keys) if (key.codes[0] == -10) count++;
            assertEquals(1, count);
        }
    }

    @Test public void accessibilityFocusUsesCustomShanSpeechAndMicRemainsVoiceTypingAction() {
        KeyboardView host = root.findViewById(R.id.keyboard_view);
        final List<String> callbacks = new ArrayList<>();
        SaiNawAccessibilityHelper helper = new SaiNawAccessibilityHelper(host,
                new SaiNawAccessibilityHelper.OnAccessibilityKeyListener() {
                    @Override public void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key) { }
                    @Override public String onCursorMoveAndGetText(boolean isForward, int granularity) { return ""; }
                    @Override public void onCustomAnnounce(String text) { callbacks.add("announce:" + text); }
                }, new SaiNawPhoneticManager(service), new SaiNawEmojiManager(service));
        manager.currentLanguageId = 2;
        helper.setKeyboard(manager.shanKeyboard, 2, false);
        helper.setPhoneticEnabled(true, true);

        AccessibilityEvent focused = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        helper.onPopulateEventForVirtualView(2, focused); // First Shan letter: uses the custom speech path.
        assertEquals(1, callbacks.size());
        assertTrue(callbacks.get(0).startsWith("announce:"));
        assertEquals("သ", callbacks.get(0).substring("announce:".length()));

        callbacks.clear();
        AccessibilityEvent unrelated = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        helper.onPopulateEventForVirtualView(1, unrelated);
        assertTrue(callbacks.isEmpty());

        AccessibilityNodeInfoCompat node = helper.getAccessibilityNodeProvider(host)
                .createAccessibilityNodeInfo(1);
        assertNotNull(node);
        assertEquals("Voice Typing", node.getContentDescription().toString());
        assertTrue(node.isClickable());
        assertTrue(node.getActionList().stream().anyMatch(a -> a.getId() == AccessibilityNodeInfoCompat.ACTION_CLICK));
        focused.recycle();
        unrelated.recycle();
    }

    @Test public void voiceControllerNeverAddsViewsInAnyCaptureState() throws Exception {
        for (String state : new String[]{"IDLE", "STARTING", "RECORDING", "STOPPING", "UPLOADING"}) {
            set(voice, "state", enumValue(field(voice, "state").getClass(), state));
            assertEquals("state=" + state, 2, root.getChildCount());
        }
    }

    @Test public void voiceAvailabilityGatesLanguageSymbolsEmojiAndPasswordVariations() throws Exception {
        Method allowed = service.getClass().getDeclaredMethod("canUseShanVoice");
        allowed.setAccessible(true);
        manager.currentLanguageId = 0;
        assertFalse((Boolean) allowed.invoke(service));
        manager.currentLanguageId = 1;
        assertFalse((Boolean) allowed.invoke(service));
        manager.currentLanguageId = 2;
        assertTrue((Boolean) allowed.invoke(service));
        manager.isSymbols = true;
        assertFalse((Boolean) allowed.invoke(service));
        manager.isSymbols = false;
        manager.isEmoji = true;
        assertFalse((Boolean) allowed.invoke(service));
        manager.isEmoji = false;

        for (int type : new int[]{InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD}) {
            EditorInfo info = new EditorInfo(); info.inputType = type; setCurrentEditor(info);
            assertFalse("inputType=" + type, (Boolean) allowed.invoke(service));
        }
        EditorInfo ordinary = new EditorInfo(); ordinary.inputType = InputType.TYPE_CLASS_TEXT;
        setCurrentEditor(ordinary);
        assertTrue((Boolean) allowed.invoke(service));
    }

    @Test public void changingAwayFromShanCancelsStartingCapture() throws Exception {
        voice.setAvailable(true);
        set(voice, "state", enumValue(field(voice, "state").getClass(), "STARTING"));
        int previous = (Integer) field(voice, "generation");
        manager.currentLanguageId = 1;
        invoke(service, "updateHelperState");
        assertEquals(2, root.getChildCount());
        assertEquals("IDLE", field(voice, "state").toString());
        assertTrue((int) field(voice, "generation") > previous);
    }

    @Test public void legacyRecognizerSetupKeepsMyanmarLocaleAndPermissionPathNeverRecordsWithoutPermission() throws Exception {
        installOfflineRecognizer();
        Shadows.shadowOf(service.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        invoke(service, "setupSpeechRecognizer");
        SpeechRecognizer recognizer = (SpeechRecognizer) field(service, "speechRecognizer");
        assertNotNull(recognizer);
        assertEquals("my-MM", ((android.content.Intent) field(service, "speechIntent")).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));

        Shadows.shadowOf(service.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        manager.currentLanguageId = 0;
        invoke(service, "startVoiceInput");
        assertFalse((Boolean) field(service, "isListening"));
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Shadows.shadowOf(service).getNextStartedActivity().getAction());
        assertEquals("my-MM", ((android.content.Intent) field(service, "speechIntent")).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
    }

    @Test public void switchingToShanDoesNotStartAndroidRecognizer() throws Exception {
        installOfflineRecognizer();
        invoke(service, "setupSpeechRecognizer");
        assertNotNull(field(service, "speechRecognizer"));
        manager.currentLanguageId = 2;
        invoke(service, "startVoiceInput");
        assertFalse((Boolean) field(service, "isListening"));
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }

    private void installOfflineRecognizer() {
        android.content.pm.ResolveInfo match = new android.content.pm.ResolveInfo();
        match.serviceInfo = new android.content.pm.ServiceInfo();
        match.serviceInfo.packageName = "offline.test";
        match.serviceInfo.name = "OfflineRecognitionService";
        Shadows.shadowOf(service.getPackageManager()).addResolveInfoForIntent(
                new android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE), match);
    }

    private static void measure(View view) {
        int width = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST);
        view.measure(width, height);
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private static String geometry(LinearLayout layout) {
        StringBuilder result = new StringBuilder();
        for (int id : new int[]{R.id.candidates_container, R.id.keyboard_view}) {
            View child = layout.findViewById(id);
            result.append(child.getMeasuredWidth()).append('x').append(child.getMeasuredHeight())
                    .append('@').append(child.getLeft()).append(',').append(child.getTop()).append(';');
        }
        return result.toString();
    }
    private void setCurrentEditor(EditorInfo info) { set(service, "mInputEditorInfo", info); }
    private static String keys(Keyboard keyboard) {
        StringBuilder out = new StringBuilder();
        for (Keyboard.Key key : keyboard.getKeys()) out.append(key.label).append('@').append(key.codes[0]).append(':').append(key.x).append(',').append(key.y).append(',').append(key.width).append(',').append(key.height).append(';');
        return out.toString();
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        throw new AssertionError("Missing field " + name);
    }
    private static void invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method); m.setAccessible(true); m.invoke(target);
    }
    private static Object enumValue(Class<?> type, String name) throws Exception {
        for (Object value : (Object[]) type.getDeclaredMethod("values").invoke(null)) if (value.toString().equals(name)) return value;
        throw new AssertionError("Missing enum value " + name);
    }
}
