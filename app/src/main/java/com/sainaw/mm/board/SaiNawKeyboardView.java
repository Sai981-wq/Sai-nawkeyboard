package com.sainaw.mm.board;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.View;

/** Original renderer for every key except Shan's undecorated voice-icon row. */
public class SaiNawKeyboardView extends KeyboardView {
    private final Rect keyPadding = new Rect();
    private boolean plainShanVoice;

    public SaiNawKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.keyBackground});
        Drawable background = a.getDrawable(0);
        if (background != null) background.getPadding(keyPadding);
        a.recycle();
    }

    void setPlainShanVoice(boolean enabled) {
        if (plainShanVoice != enabled) {
            plainShanVoice = enabled;
            invalidateAllKeys();
        }
    }

    @Override public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Keyboard keyboard = getKeyboard();
        if (!plainShanVoice || keyboard == null) return;
        for (Keyboard.Key key : keyboard.getKeys()) {
            if (key.codes == null || key.codes.length == 0) continue;
            int code = key.codes[0];
            if (code != -100 && code != -10) continue;
            // The stock KeyboardView paints the common rounded key background even
            // for the empty spacer. Replace only these two regions with the exact
            // enclosing keyboard surface; no added view, panel, label or hit target.
            int left = getPaddingLeft() + key.x;
            int top = getPaddingTop() + key.y;
            int save = canvas.save();
            canvas.clipRect(left, top, left + key.width, top + key.height);
            Drawable surface = getBackground();
            if (surface != null) surface.draw(canvas);
            else if (getParent() instanceof View) {
                View parent = (View) getParent();
                surface = parent.getBackground();
                if (surface != null) {
                    canvas.translate(-getLeft(), -getTop());
                    surface.draw(canvas);
                    canvas.translate(getLeft(), getTop());
                }
            }
            canvas.restoreToCount(save);
            if (code == -10 && key.icon != null) {
                // Identical icon resource, intrinsic size, and integer centering to
                // Android KeyboardView (including the original key drawable padding).
                Rect icon = voiceIconBounds(key);
                save = canvas.save();
                canvas.translate(icon.left, icon.top);
                key.icon.setBounds(0, 0, icon.width(), icon.height());
                key.icon.draw(canvas);
                canvas.restoreToCount(save);
            }
        }
    }

    Rect voiceIconBounds(Keyboard.Key key) {
        int width = key.icon.getIntrinsicWidth();
        int height = key.icon.getIntrinsicHeight();
        int left = getPaddingLeft() + key.x + keyPadding.left
                + (key.width - keyPadding.left - keyPadding.right - width) / 2;
        int top = getPaddingTop() + key.y + keyPadding.top
                + (key.height - keyPadding.top - keyPadding.bottom - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }
}
