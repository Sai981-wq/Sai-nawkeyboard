package com.sainaw.mm.board;

import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import java.util.List;

public class SaiNawAccessibilityHelper extends ExploreByTouchHelper {
    private final View view;
    private Keyboard currentKeyboard;
    private boolean isShanOrMyanmar = false;
    private boolean isCaps = false;

    public SaiNawAccessibilityHelper(@NonNull View view) {
        super(view);
        this.view = view;
    }

    public void setKeyboard(Keyboard keyboard, boolean isShanOrMyanmar, boolean isCaps) {
        this.currentKeyboard = keyboard;
        this.isShanOrMyanmar = isShanOrMyanmar;
        this.isCaps = isCaps;
        // Keyboard ပြောင်းတိုင်း TalkBack ကို အသစ်ပြန်စစ်ခိုင်းမယ်
        invalidateRoot(); 
    }

    @Override
    protected int getVirtualViewAt(float x, float y) {
        if (currentKeyboard == null) return HOST_ID;
        
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        // ညီကို့ရဲ့ Fast Search Logic ကို ဒီမှာသုံးထားပါတယ် (TalkBack ရှာရတာ မြန်အောင်)
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key.isInside((int) x, (int) y)) {
                // -100 ဆိုတာ နေရာလွတ် (Spacer) မို့လို့ TalkBack ကို မဖတ်ခိုင်းဘူး
                if (key.codes[0] == -100) return HOST_ID; 
                return i;
            }
        }
        return HOST_ID;
    }

    @Override
    protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
        if (currentKeyboard == null) return;
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        for (int i = 0; i < keys.size(); i++) {
            // နေရာလွတ် (-100) မဟုတ်ရင် List ထဲထည့်မယ်
            if (keys.get(i).codes[0] != -100) {
                virtualViewIds.add(i);
            }
        }
    }

    @Override
    protected void onPopulateNodeForVirtualView(int virtualViewId, @NonNull AccessibilityNodeInfoCompat node) {
        if (currentKeyboard == null) return;
        
        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (virtualViewId >= keys.size()) return;
        
        Keyboard.Key key = keys.get(virtualViewId);
        String description = getKeyDescription(key);

        node.setContentDescription(description);
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
        
        // TalkBack အကွက်နေရာသတ်မှတ်ခြင်း
        Rect bounds = new Rect(key.x, key.y, key.x + key.width, key.y + key.height);
        // Offset လိုအပ်ရင်ထည့်ရန် (များသောအားဖြင့် KeyboardView မှာမလိုပါ)
        node.setBoundsInParent(bounds); 
    }

    @Override
    protected boolean onPerformActionForVirtualView(int virtualViewId, int action, @Nullable Bundle arguments) {
        // TalkBack က Double Tap နှိပ်ရင် ဘာလုပ်မလဲ (Service ဘက်က Handle လုပ်မှာမို့ false ပြန်ထားရင်ရပြီ)
        return false; 
    }

    // Key တစ်လုံးချင်းစီအတွက် အသံထွက်မယ့်စာသား သတ်မှတ်ခြင်း
    private String getKeyDescription(Keyboard.Key key) {
        int code = key.codes[0];
        
        // Function Keys
        if (code == -5) return "Delete";
        if (code == -1) return isCaps ? "Shift On" : "Shift";
        if (code == 32) return "Space";
        if (code == -4) return "Enter";
        if (code == -2) return "Symbol Keyboard";
        if (code == -101) return "Switch Language";
        if (code == -10) return "Voice Typing";
        if (code == -100) return ""; // Spacer

        String label = null;
        if (key.label != null) label = key.label.toString();
        else if (key.text != null) label = key.text.toString();

        // ရှမ်း/မြန်မာ Shift မိထားရင် "Sub" (အောက်ဆင့်) ထည့်ဖတ်မယ်
        if (label != null && isShanOrMyanmar && isCaps) {
            return "Sub " + label;
        }

        return label != null ? label : "Unlabeled Key";
    }
}
