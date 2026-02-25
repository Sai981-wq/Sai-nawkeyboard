package com.sainaw.mm.board;

import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaiNawAccessibilityHelper extends ExploreByTouchHelper {
    private final Vibrator vibrator;
    private final AccessibilityManager accessibilityManager;
    private final Rect tempRect = new Rect();
    private final ExecutorService feedbackExecutor = Executors.newSingleThreadExecutor();
    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isSymbols = false;
    private boolean isPhoneticEnabled = true;
    private OnAccessibilityKeyListener listener;
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }

    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener, 
                                     SaiNawPhoneticManager manager, SaiNawEmojiManager emojiManager) {
        super(view);
        this.listener = listener;
        this.phoneticManager = manager;
        this.emojiManager = emojiManager;
        this.vibrator = (Vibrator) view.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        this.accessibilityManager = (AccessibilityManager) view.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    // parameter ၄ ခုဖြစ်အောင် ပြန်ပြင်လိုက်ပါတယ်
    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps, boolean isSymbols) {
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        this.isSymbols = isSymbols;
        invalidateRoot();
        sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public void setPhoneticEnabled(boolean enabled) {
        this.isPhoneticEnabled = enabled;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null || y < 0) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return HOST_ID;

        int ix = (int) x, iy = (int) y;
        int closestKeyIndex = HOST_ID;
        long minDistanceSq = Long.MAX_VALUE;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key == null || key.codes == null || key.codes.length == 0 || key.codes[0] == -100) continue;

            if (key.isInside(ix, iy)) return i;

            int centerX = key.x + (key.width / 2);
            int centerY = key.y + (key.height / 2);
            long dx = ix - centerX;
            long dy = iy - centerY;
            long distSq = (dx * dx) + (dy * dy);

            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                closestKeyIndex = i;
            }
        }
        return (minDistanceSq < 4900) ? closestKeyIndex : HOST_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).codes[0] != -100) virtualViewIds.add(i);
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) return;
        
        Keyboard.Key key = keys.get(virtualViewId);
        node.setContentDescription(getKeyDescription(key));
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        node.setLongClickable(true);
        
        tempRect.set(key.x, key.y, key.x + key.width, key.y + key.height);
        node.setBoundsInParent(tempRect);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (currentKeyboard == null) return false;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) return false;
        
        Keyboard.Key key = keys.get(virtualViewId);

        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            forceHapticFeedback(key.codes[0]);
            if (listener != null) listener.onAccessibilityKeyClick(key.codes[0], key);
            return true;
        } else if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
            handleLongClick(key);
            return true;
        }
        return false;
    }

    private void handleLongClick(Keyboard.Key key) {
        int emojiCode = resolveEmojiCode(key);
        if (emojiCode != 0 && emojiManager != null) {
            String desc = emojiManager.getMmDescription(emojiCode);
            if (desc != null) {
                performLongPressFeedback();
                announceTextImmediate(desc);
            }
        } else if (key.codes[0] == -1 || key.codes[0] == -5) {
            performLongPressFeedback();
        }
    }

    private void announceTextImmediate(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private int resolveEmojiCode(Keyboard.Key key) {
        int code = key.codes[0];
        if (emojiManager != null && emojiManager.hasDescription(code)) return code;
        if (key.label != null && key.label.length() > 0 && emojiManager != null) {
            int labelCode = Character.codePointAt(key.label, 0);
            if (emojiManager.hasDescription(labelCode)) return labelCode;
        }
        return 0;
    }

    private void performLongPressFeedback() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        feedbackExecutor.execute(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else vibrator.vibrate(80);
        });
    }

    private void forceHapticFeedback(int keyCode) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        feedbackExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    int effectId = (keyCode == -5 || keyCode == 32 || keyCode == -4) ? 
                            VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                    vibrator.vibrate(VibrationEffect.createPredefined(effectId));
                } else vibrator.vibrate(40);
            } catch (Exception e) {}
        });
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        if (code == -5) return "Delete";
        if (code == 32) return "Space";
        if (code == -4 && key.label != null) return key.label.toString();
        if (code == -1) {
            if (isSymbols) return isCaps ? "Symbols" : "More Symbols";
            return isCaps ? "Shift On" : "Shift Off";
        }
        if (code == -2) return "Symbols";
        if (code == -101) return "Switch Language";

        if (isPhoneticEnabled && phoneticManager != null) {
            String phonetic = phoneticManager.getPronunciation(code);
            if (phonetic != null) return phonetic;
        }

        String label = (key.label != null) ? key.label.toString() : (key.text != null ? key.text.toString() : "");
        if (!isShanOrMyanmar && isCaps && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }
        return label.isEmpty() ? "Unlabeled Key" : label;
    }
}

