package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Pure accessibility node/action tests, not a substitute for TalkBack on a phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VoiceAccessibilityTest {
    private static final class Listener implements SaiNawAccessibilityHelper.OnAccessibilityKeyListener {
        int clicks;
        @Override public void onAccessibilityKeyClick(int code, Keyboard.Key key) { clicks++; }
        @Override public String onCursorMoveAndGetText(boolean forward, int granularity) { return ""; }
        @Override public void onCustomAnnounce(String text) { }
    }

    @Test public void shanVoiceNodeMatchesOriginalEnglishRoleAndName() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        try {
            KeyboardView host = new KeyboardView(activity, null);
            activity.setContentView(host);
            Listener listener = new Listener();
            SaiNawAccessibilityHelper helper = new SaiNawAccessibilityHelper(host, listener,
                    new SaiNawPhoneticManager(activity), new SaiNawEmojiManager(activity));
            helper.setKeyboard(new Keyboard(activity, R.xml.shan), 2, false);
            AccessibilityNodeProviderCompat provider = helper.getAccessibilityNodeProvider(host);
            AccessibilityNodeInfoCompat node = provider.createAccessibilityNodeInfo(1);
            assertEquals("Voice Typing", node.getContentDescription().toString());
            assertNotEquals("android.widget.Button", node.getClassName().toString());
            String shanRole = node.getClassName().toString();
            int shanActions = node.getActions();
            assertTrue(node.isClickable());
            assertEquals("Voice Typing", provider.createAccessibilityNodeInfo(1).getContentDescription().toString());
            helper.setKeyboard(new Keyboard(activity, R.xml.qwerty), 0, false);
            assertEquals("Voice Typing", provider.createAccessibilityNodeInfo(1).getContentDescription().toString());
            assertEquals(shanRole, provider.createAccessibilityNodeInfo(1).getClassName().toString());
            assertEquals(shanActions, provider.createAccessibilityNodeInfo(1).getActions());
        } finally { activity.finish(); }
    }

    @Test public void virtualClickDispatchesImmediatelyAndFocusDoesNotToggleCapture() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        try {
            android.view.accessibility.AccessibilityManager manager = (android.view.accessibility.AccessibilityManager)
                    activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true);
            org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true);
            KeyboardView host = new KeyboardView(activity, null); activity.setContentView(host);
            Listener listener = new Listener();
            SaiNawAccessibilityHelper helper = new SaiNawAccessibilityHelper(host, listener,
                    new SaiNawPhoneticManager(activity), new SaiNawEmojiManager(activity));
            helper.setKeyboard(new Keyboard(activity, R.xml.shan), 2, false);
            AccessibilityNodeProviderCompat provider = helper.getAccessibilityNodeProvider(host);
            assertTrue(provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_CLICK, null));
            assertEquals("Virtual action is delivered without a defer gate", 1, listener.clicks);
            assertTrue(provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null));
            provider.performAction(1, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            assertEquals("Focus changes are not key clicks", 1, listener.clicks);
        } finally { activity.finish(); }
    }
}
