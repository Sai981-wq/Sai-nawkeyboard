package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
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

public class SaiNawAccessibilityHelper extends ExploreByTouchHelper {
    private final Vibrator vibrator;
    private final AccessibilityManager accessibilityManager;
    private final Rect tempRect = new Rect();
    private final View hostView;
    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isPhoneticEnabled = true;
    private OnAccessibilityKeyListener listener;
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;
    private boolean isSymbols = false;

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
        String onCursorMoveAndGetText(boolean isForward, int granularity);
    }

    public SaiNawAccessibilityHelper(@NonNull View view, OnAccessibilityKeyListener listener, 
                                     SaiNawPhoneticManager manager, SaiNawEmojiManager emojiManager) {
        super(view);
        this.hostView = view;
        this.listener = listener;
        this.phoneticManager = manager;
        this.emojiManager = emojiManager;
        this.vibrator = (Vibrator) view.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        this.accessibilityManager = (AccessibilityManager) view.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        info.setMovementGranularities(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER | AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD);
    }

    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY || 
            action == AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) {
            if (listener != null) {
                boolean isForward = (action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
                int granularity = args != null ? args.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER) : AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER;
                
                String traversed = listener.onCursorMoveAndGetText(isForward, granularity);
                if (traversed != null && !traversed.isEmpty()) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY);
                    event.setPackageName(host.getContext().getPackageName());
                    event.setClassName(host.getClass().getName());
                    event.setSource(host);
                    event.setMovementGranularity(granularity);
                    event.setAction(action);
                    event.getText().add(traversed);
                    event.setFromIndex(0);
                    event.setToIndex(traversed.length());
                    host.getParent().requestSendAccessibilityEvent(host, event);
                    return true;
                }
            }
            return false;
        }
        return super.performAccessibilityAction(host, action, args);
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps) {
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        
        this.isSymbols = false;
        if (keyboard != null) {
            List<Keyboard.Key> keys = keyboard.getKeys();
            if (keys != null) {
                for (Keyboard.Key key : keys) {
                    if (key.codes[0] == -6) {
                        this.isSymbols = true;
                        break;
                    }
                }
            }
        }
        
        invalidateRoot();
        sendEventForVirtualView(HOST_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public void setPhoneticEnabled(boolean enabled) {
        this.isPhoneticEnabled = enabled;
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;
        
        int touchX = (int) x - hostView.getPaddingLeft();
        int touchY = (int) y - hostView.getPaddingTop();
        return getNearestKeyIndex(touchX, touchY);
    }

    private int getNearestKeyIndex(int x, int y) {
        if (currentKeyboard == null) return HOST_ID;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return HOST_ID;
        if (y < 0) return HOST_ID;

        int bestKeyIndex = HOST_ID;
        int minDistance = Integer.MAX_VALUE;

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key == null || key.codes == null || key.codes.length == 0 || key.codes[0] == -100) continue;

            tempRect.set(key.x, key.y, key.x + key.width, key.y + key.height);
            if (tempRect.contains(x, y)) {
                return i;
            }

            int dist = getDistanceSq(key, x, y);
            if (dist < minDistance) {
                minDistance = dist;
                bestKeyIndex = i;
            }
        }
        return bestKeyIndex;
    }

    private int getDistanceSq(Keyboard.Key key, int x, int y) {
        int dx = 0;
        int dy = 0;
        if (x < key.x) dx = key.x - x;
        else if (x > key.x + key.width) dx = x - (key.x + key.width);
        if (y < key.y) dy = key.y - y;
        else if (y > key.y + key.height) dy = y - (key.y + key.height);
        return (dx * dx) + (dy * dy);
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
        if (currentKeyboard == null) {
            node.setContentDescription("");
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) {
            node.setContentDescription("");
            node.setBoundsInParent(new Rect(0, 0, 1, 1));
            return;
        }
        Keyboard.Key key = keys.get(virtualViewId);
        String description = getKeyDescription(key);
        node.setContentDescription(description);
        
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        node.setClickable(true);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
        node.setLongClickable(true);
        
        node.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        node.setMovementGranularities(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER | AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD);
        
        int left = key.x + hostView.getPaddingLeft();
        int top = key.y + hostView.getPaddingTop();
        int right = left + key.width;
        int bottom = top + key.height;

        if (right <= left || bottom <= top) {
            tempRect.set(0, 0, 1, 1);
        } else {
            tempRect.set(left, top, right, bottom);
        }
        node.setBoundsInParent(tempRect);
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        if (currentKeyboard == null) return false;
        
        if (action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY || 
            action == AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY) {
            if (listener != null) {
                boolean isForward = (action == AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
                int granularity = AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER;
                if (arguments != null) {
                    granularity = arguments.getInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER);
                }
                
                String traversed = listener.onCursorMoveAndGetText(isForward, granularity);
                if (traversed != null && !traversed.isEmpty()) {
                    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY);
                    event.setPackageName(hostView.getContext().getPackageName());
                    event.setClassName(hostView.getClass().getName());
                    event.setSource(hostView, virtualViewId);
                    event.setMovementGranularity(granularity);
                    event.setAction(action);
                    event.getText().add(traversed);
                    event.setFromIndex(0);
                    event.setToIndex(traversed.length());
                    hostView.getParent().requestSendAccessibilityEvent(hostView, event);
                    return true;
                }
            }
            return false;
        }
        
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null || virtualViewId < 0 || virtualViewId >= keys.size()) return false;
        
        Keyboard.Key key = keys.get(virtualViewId);

        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            forceHapticFeedback(key.codes[0]);
            if (listener != null) {
                listener.onAccessibilityKeyClick(key.codes[0], key);
            }
            return true;
        } else if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK) {
            int emojiCode = resolveEmojiCode(key);
            if (emojiCode != 0 && emojiManager != null) {
                String desc = emojiManager.getMmDescription(emojiCode);
                if (desc != null) {
                    performLongPressFeedback();
                    announceTextImmediate(desc);
                    return true;
                }
            }
             if (key.codes[0] == -1 || key.codes[0] == -5) {
                 performLongPressFeedback();
                 return true;
             }
        }
        return false;
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
        if (emojiManager != null && emojiManager.hasDescription(code)) {
            return code;
        }
        if (key.label != null && key.label.length() > 0 && emojiManager != null) {
            int labelCode = Character.codePointAt(key.label, 0);
            if (emojiManager.hasDescription(labelCode)) {
                return labelCode;
            }
        }
        return 0;
    }

    private void performLongPressFeedback() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        SharedPreferences prefs = hostView.getContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("vibrate_on", true)) return;
        
        int strength = prefs.getInt("vibrate_strength", 0);
        long duration = 80;
        int amplitude = VibrationEffect.DEFAULT_AMPLITUDE;
        
        if (strength == 1) { duration = 40; amplitude = 100; }
        else if (strength == 2) { duration = 80; amplitude = 180; }
        else if (strength == 3) { duration = 120; amplitude = 255; }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (strength == 0) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
        } else {
            vibrator.vibrate(duration);
        }
    }

    private void forceHapticFeedback(int keyCode) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        SharedPreferences prefs = hostView.getContext().getSharedPreferences("KeyboardPrefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("vibrate_on", true)) return;
        
        int strength = prefs.getInt("vibrate_strength", 0);
        long duration = (keyCode == -5 || keyCode == 32 || keyCode == -4) ? 50 : 35;
        int amplitude = (keyCode == -5 || keyCode == 32 || keyCode == -4) ? 255 : 180;
        
        if (strength == 1) { duration = 20; amplitude = 80; }
        else if (strength == 2) { duration = 40; amplitude = 150; }
        else if (strength == 3) { duration = 60; amplitude = 255; }
        else { strength = 0; } 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (strength == 0) {
                    int effectId = (keyCode == -5 || keyCode == 32 || keyCode == -4 || keyCode == 10) 
                                    ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                    vibrator.vibrate(VibrationEffect.createPredefined(effectId));
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                }
            } catch (Exception e) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (strength == 0) vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
                else vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            } catch (Exception e) {
                vibrator.vibrate(duration);
            }
        } else {
            vibrator.vibrate(duration);
        }
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        if (code == -4 && key.label != null) return key.label.toString();
        if (isPhoneticEnabled) {
            String phonetic = phoneticManager.getPronunciation(code);
            if (phonetic != null && !phonetic.equals(String.valueOf((char)code))) return phonetic;
        }
        if (code == -5) return "Delete";
        if (code == -1) return isSymbols ? (isCaps ? "Symbols" : "More Symbols") : (isCaps ? "Shift On" : "Shift");
        if (code == 32) return "Space";
        if (code == -2) return "Symbol Keyboard";
        if (code == -6) return "Alphabet Keyboard";
        if (code == -101) return "Switch Language";
        if (code == -10) return "Voice Typing";
        if (code == -100) return ""; 

        String label = (key.label != null) ? key.label.toString() : ((key.text != null) ? key.text.toString() : null);
        if (!isShanOrMyanmar && isCaps && label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }
        return label != null ? label : "Unlabeled Key";
    }
}
