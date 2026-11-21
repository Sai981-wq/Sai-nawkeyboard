package com.sainaw.mm.board;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import java.util.List;

/**
 * Custom keyboard renderer with Material Light/Dark key styling, dynamic glyph auto-fitting
 * for wide Myanmar/Shan characters, automatic icon tinting, and undecorated Shan voice row support.
 */
public class SaiNawKeyboardView extends KeyboardView {
    private final Rect keyPadding = new Rect();
    private final RectF keyRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final Rect textBounds = new Rect();
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean plainShanVoice;
    private final boolean isDarkTheme;

    private final float baseKeyTextSizePx;
    private final float baseLabelTextSizePx;
    private final float spaceLabelTextSizePx;
    private final float minTextSizePx;

    private final float insetX;
    private final float insetY;
    private final float cornerRadius;
    private final float shadowOffset;
    private final float textPadX;
    private final float textPadY;
    private final float iconPad;

    // Theme palette
    private final int charBgColor;
    private final int charPressedBgColor;
    private final int charShadowColor;
    private final int charTextColor;

    private final int modBgColor;
    private final int modPressedBgColor;
    private final int modActiveBgColor;
    private final int modShadowColor;
    private final int modTextColor;

    private final int enterBgColor;
    private final int enterPressedBgColor;
    private final int enterShadowColor;
    private final int enterTextColor;

    private final int spaceTextColor;

    // Pre-allocated icon tint filters (zero allocations in onDraw)
    private final ColorFilter normalIconFilter;
    private final ColorFilter enterIconFilter;

    public SaiNawKeyboardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SaiNawKeyboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        insetX = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, dm);
        insetY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, dm);
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6.0f, dm);
        shadowOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, dm);
        textPadX = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4.0f, dm);
        textPadY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.0f, dm);
        iconPad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.0f, dm);

        float defaultKeySp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 19.0f, dm);
        float defaultLabelSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14.0f, dm);
        spaceLabelTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13.0f, dm);
        minTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, dm);

        int attrTextColor = Color.parseColor("#202124");
        float parsedKeySize = defaultKeySp;
        float parsedLabelSize = defaultLabelSp;

        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{
                android.R.attr.keyBackground,
                android.R.attr.keyTextColor,
                android.R.attr.keyTextSize,
                android.R.attr.labelTextSize
        });
        Drawable background = a.getDrawable(0);
        if (background != null) background.getPadding(keyPadding);
        attrTextColor = a.getColor(1, attrTextColor);
        parsedKeySize = a.getDimension(2, defaultKeySp);
        parsedLabelSize = a.getDimension(3, defaultLabelSp);
        a.recycle();

        baseKeyTextSizePx = parsedKeySize;
        baseLabelTextSizePx = parsedLabelSize;

        // Automatically detect Dark vs Light theme from keyTextColor luminance
        int r = Color.red(attrTextColor);
        int g = Color.green(attrTextColor);
        int b = Color.blue(attrTextColor);
        isDarkTheme = (0.299f * r + 0.587f * g + 0.114f * b) > 128f;

        if (isDarkTheme) {
            charBgColor = Color.parseColor("#323336");
            charPressedBgColor = Color.parseColor("#4B4D52");
            charShadowColor = Color.parseColor("#101012");
            charTextColor = Color.parseColor("#F1F3F4");

            modBgColor = Color.parseColor("#242528");
            modPressedBgColor = Color.parseColor("#3B3D42");
            modActiveBgColor = Color.parseColor("#3F495A");
            modShadowColor = Color.parseColor("#0D0D0F");
            modTextColor = Color.parseColor("#E8EAED");

            enterBgColor = Color.parseColor("#3B78E7");
            enterPressedBgColor = Color.parseColor("#2A5DB0");
            enterShadowColor = Color.parseColor("#1F4A94");
            enterTextColor = Color.WHITE;

            spaceTextColor = Color.parseColor("#9AA0A6");
        } else {
            charBgColor = Color.parseColor("#FFFFFF");
            charPressedBgColor = Color.parseColor("#D2D6DC");
            charShadowColor = Color.parseColor("#C2C6CC");
            charTextColor = Color.parseColor("#202124");

            modBgColor = Color.parseColor("#D5D9E0");
            modPressedBgColor = Color.parseColor("#BDC3CC");
            modActiveBgColor = Color.parseColor("#C2D4EE");
            modShadowColor = Color.parseColor("#B5BBC4");
            modTextColor = Color.parseColor("#202124");

            enterBgColor = Color.parseColor("#1A73E8");
            enterPressedBgColor = Color.parseColor("#1557B0");
            enterShadowColor = Color.parseColor("#1252A8");
            enterTextColor = Color.WHITE;

            spaceTextColor = Color.parseColor("#5F6368");
        }

        normalIconFilter = new PorterDuffColorFilter(modTextColor, PorterDuff.Mode.SRC_IN);
        enterIconFilter = new PorterDuffColorFilter(enterTextColor, PorterDuff.Mode.SRC_IN);

        bgPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);
    }

    void setPlainShanVoice(boolean enabled) {
        if (plainShanVoice != enabled) {
            plainShanVoice = enabled;
            invalidateAllKeys();
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) return;
        List<Keyboard.Key> keys = keyboard.getKeys();
        if (keys == null || keys.isEmpty()) return;

        int padLeft = getPaddingLeft();
        int padTop = getPaddingTop();

        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key == null) continue;

            int code = (key.codes != null && key.codes.length > 0) ? key.codes[0] : 0;

            // Leave empty spacer regions completely undecorated on the keyboard surface
            if (code == -100) continue;

            // Keep Shan's top voice icon row undecorated to match ShanVoiceInput overlay
            if (plainShanVoice && code == -10) {
                if (key.icon != null) {
                    Rect iconBounds = voiceIconBounds(key);
                    drawTintedIcon(canvas, key.icon, iconBounds.left, iconBounds.top,
                            iconBounds.width(), iconBounds.height(), normalIconFilter);
                }
                continue;
            }

            float left = padLeft + key.x + insetX;
            float top = padTop + key.y + insetY;
            float right = padLeft + key.x + key.width - insetX;
            float bottom = padTop + key.y + key.height - insetY;
            if (right <= left || bottom <= top) continue;

            boolean isEnter = (code == -4);
            boolean isSpace = (code == 32);
            boolean isModifier = isModifierKey(code);

            int fillColor;
            int shadowColor;
            if (isEnter) {
                fillColor = key.pressed ? enterPressedBgColor : enterBgColor;
                shadowColor = enterShadowColor;
            } else if (isModifier) {
                fillColor = key.pressed ? modPressedBgColor : (key.on ? modActiveBgColor : modBgColor);
                shadowColor = modShadowColor;
            } else {
                fillColor = key.pressed ? charPressedBgColor : charBgColor;
                shadowColor = charShadowColor;
            }

            // Subtle bottom depth shadow when unpressed
            if (!key.pressed) {
                shadowRect.set(left, top + shadowOffset, right, bottom);
                bgPaint.setColor(shadowColor);
                canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, bgPaint);

                keyRect.set(left, top, right, bottom - shadowOffset);
            } else {
                keyRect.set(left, top + (shadowOffset * 0.5f), right, bottom);
            }

            bgPaint.setColor(fillColor);
            canvas.drawRoundRect(keyRect, cornerRadius, cornerRadius, bgPaint);

            // Draw icon or auto-fitted label
            if (key.icon != null && (key.label == null || key.label.length() == 0)) {
                int intrinsicW = key.icon.getIntrinsicWidth();
                int intrinsicH = key.icon.getIntrinsicHeight();
                if (intrinsicW > 0 && intrinsicH > 0) {
                    float availW = Math.max(1f, keyRect.width() - iconPad * 2f);
                    float availH = Math.max(1f, keyRect.height() - iconPad * 2f);
                    float scale = Math.min(1f, Math.min(availW / intrinsicW, availH / intrinsicH));
                    int drawW = Math.max(1, Math.round(intrinsicW * scale));
                    int drawH = Math.max(1, Math.round(intrinsicH * scale));
                    int iconLeft = Math.round(keyRect.centerX() - drawW / 2f);
                    int iconTop = Math.round(keyRect.centerY() - drawH / 2f);

                    ColorFilter filter = isEnter ? enterIconFilter : (code < 0 ? normalIconFilter : null);
                    drawTintedIcon(canvas, key.icon, iconLeft, iconTop, drawW, drawH, filter);
                }
            } else if (key.label != null && key.label.length() > 0) {
                String label = key.label.toString();
                drawFittedLabel(canvas, label, keyRect, isEnter, isSpace, isModifier);
            }
        }
    }

    private void drawTintedIcon(Canvas canvas, Drawable icon, int left, int top, int width, int height, ColorFilter filter) {
        int save = canvas.save();
        canvas.translate(left, top);
        icon.setBounds(0, 0, width, height);
        if (filter != null) {
            icon.setColorFilter(filter);
        }
        icon.draw(canvas);
        if (filter != null) {
            icon.clearColorFilter();
        }
        canvas.restoreToCount(save);
    }

    private void drawFittedLabel(Canvas canvas, String label, RectF bounds,
                                 boolean isEnter, boolean isSpace, boolean isModifier) {
        if (isEnter) {
            textPaint.setColor(enterTextColor);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (isSpace) {
            textPaint.setColor(spaceTextColor);
            textPaint.setTypeface(Typeface.DEFAULT);
        } else if (isModifier) {
            textPaint.setColor(modTextColor);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            textPaint.setColor(charTextColor);
            textPaint.setTypeface(Typeface.DEFAULT);
        }

        float targetSize;
        if (isSpace) {
            targetSize = spaceLabelTextSizePx;
        } else if (isEnter || isModifier) {
            targetSize = baseLabelTextSizePx;
        } else {
            targetSize = baseKeyTextSizePx;
        }
        textPaint.setTextSize(targetSize);

        float maxW = Math.max(1f, bounds.width() - textPadX * 2f);
        float maxH = Math.max(1f, bounds.height() - textPadY * 2f);

        float advanceW = textPaint.measureText(label);
        textPaint.getTextBounds(label, 0, label.length(), textBounds);
        float visualW = Math.max(advanceW, textBounds.width());
        float visualH = textBounds.height();

        float scaleW = (visualW > maxW) ? (maxW / visualW) : 1f;
        float scaleH = (visualH > maxH) ? (maxH / visualH) : 1f;
        float scale = Math.min(scaleW, scaleH);

        if (scale < 1f) {
            textPaint.setTextSize(Math.max(minTextSizePx, targetSize * scale));
            textPaint.getTextBounds(label, 0, label.length(), textBounds);
        }

        // Horizontally center actual inked glyph bounds (prevents wide Myanmar vowels like ဪ from clipping)
        float drawX = bounds.centerX() - (textBounds.left + textBounds.right) * 0.5f;

        // Vertically center using FontMetrics for a steady baseline across keys
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float drawY = bounds.centerY() - (fm.ascent + fm.descent) * 0.5f;

        canvas.drawText(label, drawX, drawY, textPaint);
    }

    private static boolean isModifierKey(int code) {
        return code == -1 || code == -2 || code == -5 || code == -6 || code == -7
                || code == -10 || code == -101 || code == -102 || code == -103
                || code == -104 || code == -105;
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

