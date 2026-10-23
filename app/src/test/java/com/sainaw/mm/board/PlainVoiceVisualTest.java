package com.sainaw.mm.board;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = {28, 34})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlainVoiceVisualTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @org.junit.Before public void requireSupportedNativeGraphicsHost() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        org.junit.Assume.assumeFalse("Robolectric 4.12 native graphics does not support Linux ARM64",
                os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")));
    }

    @Test public void originalEnglishMyanmarRenderingIsPixelIdenticalInBothThemes() throws Exception {
        for (int theme : new int[]{R.layout.input_view, R.layout.input_view_dark}) {
            for (int xml : new int[]{R.xml.qwerty, R.xml.qwerty_shift, R.xml.qwerty_num,
                    R.xml.qwerty_num_shift, R.xml.myanmar, R.xml.myanmar_shift, R.xml.myanmar_num}) {
                Pair pair = make(theme, xml, false);
                assertTrue("theme=" + theme + ", xml=" + xml, pair.stock.sameAs(pair.custom));
            }
        }
    }

    @Test public void shanMicHasNoKeyDecorationAndPreservesStockIconPixelsAndOtherKeys() throws Exception {
        for (int theme : new int[]{R.layout.input_view, R.layout.input_view_dark}) {
            for (int xml : new int[]{R.xml.shan, R.xml.shan_shift}) {
                Pair pair = make(theme, xml, true);
                int iconPixels = 0, removedPixels = 0;
                for (int y = 0; y < pair.custom.getHeight(); y++) {
                    for (int x = 0; x < pair.custom.getWidth(); x++) {
                        int stock = pair.stock.getPixel(x,y), custom = pair.custom.getPixel(x,y);
                        boolean mic = pair.mic.contains(x,y), blank = pair.blank.contains(x,y);
                        if (!mic && !blank) {
                            assertEquals("Other keys changed at " + x + "," + y, stock, custom);
                        } else if (blank || !pair.icon.contains(x,y)) {
                            assertEquals("Decoration remains at " + x + "," + y, pair.surface.getPixel(x,y), custom);
                            if (stock != custom) removedPixels++;
                        }
                        // The original vector uses pure black: exact placement/scale
                        // must match stock, not just an independently guessed center.
                        if (mic && (stock == Color.BLACK || custom == Color.BLACK)) {
                            assertEquals("Icon placement differs at " + x + "," + y, stock, custom);
                            iconPixels++;
                        }
                    }
                }
                assertTrue("Expected original microphone pixels", iconPixels > 20);
                assertTrue("Expected removed key backgrounds", removedPixels > 100);
            }
        }
    }

    @Test public void switchingAwayFromShanRestoresExactStockRendering() throws Exception {
        Pair pair = make(R.layout.input_view, R.xml.shan, true);
        pair.view.setPlainShanVoice(false);
        assertTrue(pair.stock.sameAs(render(pair.root)));
    }

    @Test public void pressedMicRemainsExactlyTheSameBareIcon() throws Exception {
        for (int theme : new int[]{R.layout.input_view, R.layout.input_view_dark}) {
            Pair pair = make(theme, R.xml.shan, true);
            for (Keyboard.Key key : pair.view.getKeyboard().getKeys()) {
                if (key.codes[0] == -10) key.pressed = true;
            }
            pair.view.invalidateAllKeys();
            assertTrue("Pressed background must remain invisible", pair.custom.sameAs(render(pair.root)));
        }
    }

    private Pair make(int theme, int xml, boolean plain) throws Exception {
        LinearLayout root = (LinearLayout) View.inflate(context, theme, null);
        SaiNawKeyboardView custom = root.findViewById(R.id.keyboard_view);
        Keyboard keyboard = new Keyboard(context, xml);
        custom.setKeyboard(keyboard);
        custom.setPlainShanVoice(plain);
        Bitmap customImage = render(root);
        Rect mic = null, blank = null, icon = null;
        for (Keyboard.Key key : keyboard.getKeys()) {
            Rect bounds = new Rect(custom.getLeft() + custom.getPaddingLeft() + key.x,
                    custom.getTop() + custom.getPaddingTop() + key.y,
                    custom.getLeft() + custom.getPaddingLeft() + key.x + key.width,
                    custom.getTop() + custom.getPaddingTop() + key.y + key.height);
            if (key.codes[0] == -10) {
                mic = bounds;
                icon = custom.voiceIconBounds(key);
                icon.offset(custom.getLeft(), custom.getTop());
            }
            if (key.codes[0] == -100) blank = bounds;
        }
        XmlResourceParser parser = context.getResources().getLayout(theme);
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlResourceParser.START_TAG
                    && parser.getName().equals("com.sainaw.mm.board.SaiNawKeyboardView")) break;
        }
        KeyboardView stock = new KeyboardView(context, parser);
        parser.close();
        stock.setKeyboard(new Keyboard(context, xml));
        LinearLayout stockRoot = (LinearLayout) View.inflate(context, theme, null);
        View old = stockRoot.findViewById(R.id.keyboard_view);
        int index = stockRoot.indexOfChild(old);
        android.view.ViewGroup.LayoutParams params = old.getLayoutParams();
        stockRoot.removeView(old);
        stockRoot.addView(stock, index, params);
        Pair pair = new Pair();
        pair.root=root; pair.view=custom; pair.stock=render(stockRoot); pair.custom=customImage;
        pair.mic=mic; pair.blank=blank; pair.icon=icon;
        // View.draw() paints the actual theme keyboard surface before onDraw().
        // Keep its exact measured bounds, then remove only the keys for reference.
        Keyboard empty = new Keyboard(context, xml);
        empty.getKeys().clear();
        stock.setKeyboard(empty);
        pair.surface = Bitmap.createBitmap(stockRoot.getWidth(), stockRoot.getHeight(), Bitmap.Config.ARGB_8888);
        stockRoot.draw(new Canvas(pair.surface));
        return pair;
    }

    private Bitmap render(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        return bitmap;
    }
    private static class Pair {
        LinearLayout root; SaiNawKeyboardView view;
        Bitmap stock, custom, surface; Rect mic, blank, icon;
    }
}
