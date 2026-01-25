
package com.sainaw.mm.board;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputConnection;

public class SaiNawSmartEcho {

    private final Context context;
    private final AccessibilityManager accessibilityManager;

    public SaiNawSmartEcho(Context context) {
        this.context = context;
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void announceText(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);
        }
    }

    // Char ရိုက်တိုင်း အလုပ်လုပ်မည့် method
    public void onCharTyped(InputConnection ic) {
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text == null || text.length() == 0) return;

        String s = text.toString();

        // နောက်ဆုံး စာကြောင်းကို ယူခြင်း
        int lastNewLineIndex = s.lastIndexOf('\n');
        if (lastNewLineIndex != -1) {
            s = s.substring(lastNewLineIndex + 1);
        }

        if (s.isEmpty()) return;

        // နောက်ဆုံး စကားလုံးကို ဖြတ်ထုတ်ခြင်း
        int lastSpaceIndex = s.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            announceText(s.substring(lastSpaceIndex + 1));
        } else {
            announceText(s);
        }
    }

    // Space ခေါက်တိုင်း အလုပ်လုပ်မည့် method
    public void onSpaceTyped(InputConnection ic) {
        processLastWordAnnouncement(ic, "Space");
    }

    // Enter ခေါက်တိုင်း အလုပ်လုပ်မည့် method
    public void onEnterTyped(InputConnection ic) {
        processLastWordAnnouncement(ic, "Enter");
    }

    /**
     * Space နှင့် Enter နှစ်ခုလုံးအတွက် နောက်ဆုံးစကားလုံးကို ရှာပြီးဖတ်ပေးမည့်
     * Common Logic (ကုဒ်အသန့်ဖြစ်စေရန် သီးသန့် method ခွဲထုတ်ထားခြင်းဖြစ်သည်)
     */
    private void processLastWordAnnouncement(InputConnection ic, String fallbackText) {
        if (ic == null) return;
        
        // Cursor ရှေ့က စာများကို ယူခြင်း
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        
        if (text == null || text.length() == 0) {
            announceText(fallbackText);
            return;
        }

        String s = text.toString();

        // အရေးကြီးဆုံးအချက် - Enter သို့မဟုတ် Space ကြောင့် ဖြစ်ပေါ်လာတဲ့ 
        // နောက်ဆုံးက အပို Space/NewLine တွေကို ဖယ်ရှားလိုက်မှ ရှေ့ကစာလုံးကို ရမည်။
        String trimmed = s.trim(); 

        if (trimmed.isEmpty()) {
            announceText(fallbackText);
            return;
        }

        // ဖြတ်ပြီးသားစာသားထဲမှ နောက်ဆုံးစာကြောင်းကို ပြန်ယူခြင်း (လုံခြုံမှုရှိစေရန်)
        int lastNewLineIndex = trimmed.lastIndexOf('\n');
        if (lastNewLineIndex != -1) {
            trimmed = trimmed.substring(lastNewLineIndex + 1);
        }

        // နောက်ဆုံး စကားလုံးကို ရှာဖွေခြင်း
        int lastSpaceIndex = trimmed.lastIndexOf(' ');
        if (lastSpaceIndex != -1) {
            announceText(trimmed.substring(lastSpaceIndex + 1));
        } else {
            announceText(trimmed);
        }
    }
}
