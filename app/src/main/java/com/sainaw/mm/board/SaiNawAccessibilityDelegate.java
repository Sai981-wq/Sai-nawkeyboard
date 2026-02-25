package com.sainaw.mm.board;

import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat;
import java.util.List;

public class SaiNawAccessibilityDelegate extends AccessibilityDelegateCompat {
    private final View mKeyboardView;
    private Keyboard mKeyboard;
    private final AccessibilityManager mAccessibilityManager;
    private final Vibrator vibrator;
    private SaiNawNodeProvider mNodeProvider;
    private int mLastHoverKeyIndex = -1;
    private final Rect tempRect = new Rect();
    
    private SaiNawPhoneticManager phoneticManager;
    private SaiNawEmojiManager emojiManager;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;
    private boolean isSymbols = false;
    private boolean isPhoneticEnabled = true;

    public interface OnAccessibilityKeyListener {
        void onAccessibilityKeyClick(int primaryCode, Keyboard.Key key);
    }
    private OnAccessibilityKeyListener listener;

    public SaiNawAccessibilityDelegate(View keyboardView, OnAccessibilityKeyListener listener, 
                                       SaiNawPhoneticManager phoneticManager, SaiNawEmojiManager emojiManager) {
        this.mKeyboardView = keyboardView;
        this.listener = listener;
        this.phoneticManager = phoneticManager;
        this.emojiManager = emojiManager;
        this.mAccessibilityManager = (AccessibilityManager) keyboardView.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        this.vibrator = (Vibrator) keyboardView.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        ViewCompat.setAccessibilityDelegate(mKeyboardView, this);
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps, boolean isSymbols) {
        this.mKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        this.isSymbols = isSymbols;
        if (mNodeProvider != null) {
            mKeyboardView.invalidate();
            sendWindowStateChanged();
        }
    }

    public void setPhoneticEnabled(boolean enabled) {
        this.isPhoneticEnabled = enabled;
    }

    @Override
    public AccessibilityNodeProviderCompat getAccessibilityNodeProvider(View host) {
        if (mNodeProvider == null) {
            mNodeProvider = new SaiNawNodeProvider();
        }
        return mNodeProvider;
    }

    public boolean onHoverEvent(MotionEvent event) {
        if (mKeyboard == null) return false;
        
        int action = event.getActionMasked();
        int keyIndex = getHoverKeyIndex(event);

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                if (keyIndex != -1) onHoverEnterTo(keyIndex);
                mLastHoverKeyIndex = keyIndex;
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                if (keyIndex != mLastHoverKeyIndex) {
                    if (mLastHoverKeyIndex != -1) onHoverExitFrom(mLastHoverKeyIndex);
                    if (keyIndex != -1) onHoverEnterTo(keyIndex);
                }
                mLastHoverKeyIndex = keyIndex;
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (mLastHoverKeyIndex != -1) {
                    onHoverExitFrom(mLastHoverKeyIndex);
                }
                if (keyIndex != -1) {
                    performClickOn(keyIndex);
                }
                mLastHoverKeyIndex = -1;
                break;
        }
        return true;
    }

    private void onHoverEnterTo(int keyIndex) {
        if (mNodeProvider != null) {
            mNodeProvider.mHoveringNodeId = keyIndex;
            mNodeProvider.sendAccessibilityEventForKey(keyIndex, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            mNodeProvider.sendAccessibilityEventForKey(keyIndex, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        }
    }

    private void onHoverExitFrom(int keyIndex) {
        if (mNodeProvider != null) {
            mNodeProvider.mHoveringNodeId = -1;
            mNodeProvider.sendAccessibilityEventForKey(keyIndex, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            mNodeProvider.sendAccessibilityEventForKey(keyIndex, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        }
    }

    private void performClickOn(int keyIndex) {
        if (mKeyboard == null || listener == null) return;
        List<Keyboard.Key> keys = mKeyboard.getKeys();
        if (keyIndex >= 0 && keyIndex < keys.size()) {
            Keyboard.Key key = keys.get(keyIndex);
            if (key.codes[0] != -100) {
                forceHapticFeedback(key.codes[0]);
                listener.onAccessibilityKeyClick(key.codes[0], key);
            }
        }
    }

    private int getHoverKeyIndex(MotionEvent event) {
        if (mKeyboard == null) return -1;
        List<Keyboard.Key> keys = mKeyboard.getKeys();
        if (keys == null || keys.isEmpty()) return -1;

        int x = (int) event.getX();
        int y = (int) event.getY();

        if (mLastHoverKeyIndex != -1 && mLastHoverKeyIndex < keys.size()) {
            Keyboard.Key lastKey = keys.get(mLastHoverKeyIndex);
            if (lastKey.codes[0] != -100) {
                int stickyTop = lastKey.height / 3;
                int stickyBottom = lastKey.height / 3;
                int stickyLeft = lastKey.width / 3;
                int stickyRight = lastKey.width / 3;

                if (lastKey.codes[0] == -5) { 
                    stickyTop = lastKey.height / 2; 
                    stickyLeft = lastKey.width / 2;
                    stickyBottom = lastKey.height;
                } else if (lastKey.codes[0] == -4 || lastKey.codes[0] == -1 || lastKey.codes[0] == 32) {
                    stickyTop = lastKey.height / 2;
                }

                if (x >= (lastKey.x - stickyLeft) && x <= (lastKey.x + lastKey.width + stickyRight) &&
                    y >= (lastKey.y - stickyTop) && y <= (lastKey.y + lastKey.height + stickyBottom)) {
                    return mLastHoverKeyIndex;
                }
            }
        }

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.codes[0] == -100) continue;
            if (x >= key.x && x < (key.x + key.width) && y >= key.y && y < (key.y + key.height)) {
                return i;
            }
        }
        return -1;
    }

    private void sendWindowStateChanged() {
        if (mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            mKeyboardView.onInitializeAccessibilityEvent(event);
            if (mKeyboardView.getParent() != null) {
                mKeyboardView.getParent().requestSendAccessibilityEvent(mKeyboardView, event);
            }
        }
    }

    private class SaiNawNodeProvider extends AccessibilityNodeProviderCompat {
        public int mHoveringNodeId = -1;

        @Override
        public AccessibilityNodeInfoCompat createAccessibilityNodeInfo(int virtualViewId) {
            if (mKeyboard == null) return null;

            if (virtualViewId == View.NO_ID) {
                AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain(mKeyboardView);
                ViewCompat.onInitializeAccessibilityNodeInfo(mKeyboardView, info);
                List<Keyboard.Key> keys = mKeyboard.getKeys();
                for (int i = 0; i < keys.size(); i++) {
                    if (keys.get(i).codes[0] != -100) {
                        info.addChild(mKeyboardView, i);
                    }
                }
                return info;
            }

            List<Keyboard.Key> keys = mKeyboard.getKeys();
            if (virtualViewId < 0 || virtualViewId >= keys.size()) return null;

            Keyboard.Key key = keys.get(virtualViewId);
            AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.obtain();
            info.setPackageName(mKeyboardView.getContext().getPackageName());
            info.setClassName(Keyboard.Key.class.getName());
            info.setContentDescription(getKeyDescription(key));
            
            tempRect.set(key.x, key.y, key.x + key.width, key.y + key.height);
            info.setBoundsInParent(tempRect);
            
            int[] locationOnScreen = new int[2];
            mKeyboardView.getLocationOnScreen(locationOnScreen);
            Rect screenBounds = new Rect(tempRect);
            screenBounds.offset(locationOnScreen[0], locationOnScreen[1]);
            info.setBoundsInScreen(screenBounds);

            info.setParent(mKeyboardView);
            info.setSource(mKeyboardView, virtualViewId);
            info.setEnabled(true);
            info.setVisibleToUser(true);

            if (virtualViewId != mHoveringNodeId) {
                info.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
                info.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK);
            }

            info.addAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
            return info;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (mKeyboard == null) return false;
            List<Keyboard.Key> keys = mKeyboard.getKeys();
            if (virtualViewId < 0 || virtualViewId >= keys.size()) return false;

            Keyboard.Key key = keys.get(virtualViewId);

            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                performClickOn(virtualViewId);
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
                return false;
            } else if (action == AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS) {
                sendAccessibilityEventForKey(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                return true;
            } else if (action == AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                sendAccessibilityEventForKey(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                return true;
            }
            return false;
        }

        public void sendAccessibilityEventForKey(int virtualViewId, int eventType) {
            if (!mAccessibilityManager.isEnabled()) return;
            List<Keyboard.Key> keys = mKeyboard.getKeys();
            if (virtualViewId < 0 || virtualViewId >= keys.size()) return;

            Keyboard.Key key = keys.get(virtualViewId);
            AccessibilityEvent event = AccessibilityEvent.obtain(eventType);
            event.setPackageName(mKeyboardView.getContext().getPackageName());
            event.setClassName(Keyboard.Key.class.getName());
            event.setContentDescription(getKeyDescription(key));
            event.setEnabled(true);
            event.setSource(mKeyboardView, virtualViewId);
            mAccessibilityManager.sendAccessibilityEvent(event);
        }
    }

    private void announceTextImmediate(String text) {
        if (mAccessibilityManager != null && mAccessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            mAccessibilityManager.sendAccessibilityEvent(event);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(80);
        }
    }

    private void forceHapticFeedback(int keyCode) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                int effectId = (keyCode == -5 || keyCode == 32 || keyCode == -4 || keyCode == 10) ? 
                               VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                vibrator.vibrate(VibrationEffect.createPredefined(effectId));
            } catch (Exception e) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, 200));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                long duration = (keyCode == -5 || keyCode == 32 || keyCode == -4) ? 50 : 35;
                int amplitude = (keyCode == -5 || keyCode == 32 || keyCode == -4) ? 255 : 180;
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            } catch (Exception e) {
                vibrator.vibrate(40);
            }
        } else {
            vibrator.vibrate(40);
        }
    }

    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];

        if (code == -4 && key.label != null) return key.label.toString();

        if (isPhoneticEnabled && phoneticManager != null) {
            String phonetic = phoneticManager.getPronunciation(code);
            if (phonetic != null && !phonetic.equals(String.valueOf((char)code))) {
                return phonetic;
            }
        }

        if (code == -5) return "Delete";
        
        if (code == -1) {
            if (isSymbols) {
                return isCaps ? "Symbols" : "More Symbols";
            }
            return isCaps ? "Shift On" : "Shift";
        }

        if (code == 32) return "Space";
        if (code == -2) return "Symbol Keyboard";
        if (code == -6) return "Alphabet Keyboard";
        if (code == -101) return "Switch Language";
        if (code == -10) return "Voice Typing";
        if (code == -100) return ""; 

        String label = null;
        if (key.label != null) label = key.label.toString();
        else if (key.text != null) label = key.text.toString();

        if (!isShanOrMyanmar && isCaps && label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
             return "Capital " + label;
        }

        return label != null ? label : "Unlabeled Key";
    }
}
