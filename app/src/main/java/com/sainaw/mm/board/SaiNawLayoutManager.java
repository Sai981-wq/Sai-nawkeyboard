package com.sainaw.mm.board;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.os.UserManager;
import android.view.inputmethod.EditorInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SaiNawLayoutManager {
    private static final String PREFS_NAME = "KeyboardPrefs";
    private static final String KEY_LAST_LANGUAGE_ID = "last_language_id";

    private final Context context;
    private final SaiNawKeyboardService service;

    public Keyboard qwertyKeyboard, qwertyShiftKeyboard;
    public Keyboard myanmarKeyboard, myanmarShiftKeyboard;
    public Keyboard shanKeyboard, shanShiftKeyboard;
    public Keyboard symbolsEnKeyboard, symbolsMmKeyboard;
    public Keyboard symbolsEnShiftKeyboard, symbolsMmShiftKeyboard;
    public Keyboard numberKeyboard;
    public Keyboard emojiKeyboard1, emojiKeyboard2, emojiKeyboard3, emojiKeyboard4;

    private Keyboard currentKeyboard;
    private KeyGrid currentKeyGrid;
    private EditorInfo currentEditorInfo;

    public boolean isCaps = false;
    public boolean isCapsLocked = false;
    public boolean isSymbols = false;
    public boolean isEmoji = false;
    public int currentLanguageId = 0;
    public int currentEmojiPage = 1;

    private boolean languageRestored = false;
    private Boolean cachedShowNumRow = null;
    private final List<Integer> enabledLanguages = new ArrayList<>();
    private final Map<Keyboard, KeyGrid> gridCache = new HashMap<>(16);

    public SaiNawLayoutManager(SaiNawKeyboardService service) {
        this.service = service;
        this.context = service;
    }

    private SharedPreferences getSafePrefs() {
        Context safeCtx = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (um == null || !um.isUserUnlocked()) {
                safeCtx = context.createDeviceProtectedStorageContext();
            }
        }
        return safeCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void loadLanguageSettings(SharedPreferences prefs) {
        if (!languageRestored) {
            currentLanguageId = prefs.getInt(KEY_LAST_LANGUAGE_ID, 0);
            languageRestored = true;
        }

        boolean useMm = prefs.getBoolean("enable_mm", true);
        boolean useShan = prefs.getBoolean("enable_shan", true);

        enabledLanguages.clear();
        enabledLanguages.add(0);
        if (useMm) enabledLanguages.add(1);
        if (useShan) enabledLanguages.add(2);

        if (!enabledLanguages.contains(currentLanguageId)) {
            currentLanguageId = 0;
            saveCurrentLanguage(prefs);
        }
    }

    private void saveCurrentLanguage(SharedPreferences prefs) {
        try {
            prefs.edit().putInt(KEY_LAST_LANGUAGE_ID, currentLanguageId).apply();
        } catch (Exception ignored) { }
    }

    public void initKeyboards(SharedPreferences prefs) {
        loadLanguageSettings(prefs);
        boolean showNumRow = prefs.getBoolean("number_row", false);

        if (qwertyKeyboard != null && cachedShowNumRow != null && cachedShowNumRow == showNumRow) {
            return;
        }

        gridCache.clear();
        currentKeyGrid = null;

        try {
            String suffix = showNumRow ? "_num" : "";

            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty" + suffix));
            qwertyShiftKeyboard = new Keyboard(context, service.getResId("qwerty" + suffix + "_shift"));

            myanmarKeyboard = new Keyboard(context, service.getResId("myanmar" + suffix));
            myanmarShiftKeyboard = new Keyboard(context, service.getResId("myanmar_shift"));

            shanKeyboard = new Keyboard(context, service.getResId("shan"));
            shanShiftKeyboard = new Keyboard(context, service.getResId("shan_shift"));

            int symEnId = service.getResId("symbols");
            int symMmId = service.getResId("symbols_mm");
            symbolsEnKeyboard = (symEnId != 0) ? new Keyboard(context, symEnId) : qwertyKeyboard;
            symbolsMmKeyboard = (symMmId != 0) ? new Keyboard(context, symMmId) : symbolsEnKeyboard;

            int symEnShiftId = service.getResId("symbols_shift");
            int symMmShiftId = service.getResId("symbols_mm_shift");
            symbolsEnShiftKeyboard = (symEnShiftId != 0) ? new Keyboard(context, symEnShiftId) : symbolsEnKeyboard;
            symbolsMmShiftKeyboard = (symMmShiftId != 0) ? new Keyboard(context, symMmShiftId) : symbolsMmKeyboard;

            int numPadId = service.getResId("number_pad");
            numberKeyboard = (numPadId != 0) ? new Keyboard(context, numPadId) : symbolsEnKeyboard;

            int emojiId = service.getResId("emoji");
            emojiKeyboard1 = (emojiId != 0) ? new Keyboard(context, emojiId) : symbolsEnKeyboard;

            int emoji2Id = service.getResId("kbd_emoji_2");
            emojiKeyboard2 = (emoji2Id != 0) ? new Keyboard(context, emoji2Id) : emojiKeyboard1;

            int emoji3Id = service.getResId("kbd_emoji_3");
            emojiKeyboard3 = (emoji3Id != 0) ? new Keyboard(context, emoji3Id) : emojiKeyboard1;

            int emoji4Id = service.getResId("kbd_emoji_4");
            emojiKeyboard4 = (emoji4Id != 0) ? new Keyboard(context, emoji4Id) : emojiKeyboard1;

            cachedShowNumRow = showNumRow;
        } catch (Exception e) {
            e.printStackTrace();
            qwertyKeyboard = new Keyboard(context, service.getResId("qwerty"));
            myanmarKeyboard = new Keyboard(context, service.getResId("myanmar"));
            cachedShowNumRow = false;
        }
    }

    public void updateEditorInfo(EditorInfo info) {
        this.currentEditorInfo = info;
    }

    public void determineKeyboardForInputType() {
        isEmoji = false;
        currentEmojiPage = 1;

        if (currentEditorInfo == null) {
            updateKeyboardLayout();
            return;
        }

        int inputType = currentEditorInfo.inputType & EditorInfo.TYPE_MASK_CLASS;
        if (inputType == EditorInfo.TYPE_CLASS_PHONE ||
            inputType == EditorInfo.TYPE_CLASS_NUMBER ||
            inputType == EditorInfo.TYPE_CLASS_DATETIME) {
            currentKeyboard = numberKeyboard;
            isSymbols = true;
            isCaps = false;
        } else {
            if (currentKeyboard == numberKeyboard) {
                isSymbols = false;
                isCaps = false;
            }
            updateKeyboardLayout();
            return;
        }
        applyKeyboard();
    }

    public void updateKeyboardLayout() {
        try {
            Keyboard nextKeyboard;
            if (isEmoji) {
                if (currentEmojiPage == 2) nextKeyboard = emojiKeyboard2;
                else if (currentEmojiPage == 3) nextKeyboard = emojiKeyboard3;
                else if (currentEmojiPage == 4) nextKeyboard = emojiKeyboard4;
                else nextKeyboard = emojiKeyboard1;
            } else if (isSymbols) {
                if (currentKeyboard == numberKeyboard) {
                    nextKeyboard = numberKeyboard;
                } else {
                    if (currentLanguageId == 1) {
                        nextKeyboard = isCaps ? symbolsMmShiftKeyboard : symbolsMmKeyboard;
                    } else {
                        nextKeyboard = isCaps ? symbolsEnShiftKeyboard : symbolsEnKeyboard;
                    }
                }
            } else {
                if (currentLanguageId == 1) nextKeyboard = isCaps ? myanmarShiftKeyboard : myanmarKeyboard;
                else if (currentLanguageId == 2) nextKeyboard = isCaps ? shanShiftKeyboard : shanKeyboard;
                else nextKeyboard = isCaps ? qwertyShiftKeyboard : qwertyKeyboard;
            }
            currentKeyboard = (nextKeyboard != null) ? nextKeyboard : qwertyKeyboard;
            applyKeyboard();
        } catch (Exception e) {
            currentKeyboard = qwertyKeyboard;
            applyKeyboard();
        }
    }

    private void applyKeyboard() {
        if (currentKeyboard == null) return;
        currentKeyGrid = getOrBuildGrid(currentKeyboard);
        KeyboardView kv = service.getKeyboardView();
        if (kv == null) return;

        int updatedEnterIndex = updateEnterKeyLabel();
        if (kv.getKeyboard() != currentKeyboard) {
            kv.setKeyboard(currentKeyboard);
        } else if (updatedEnterIndex >= 0) {
            kv.invalidateKey(updatedEnterIndex);
        }
        service.updateHelperState();
    }

    private KeyGrid getOrBuildGrid(Keyboard keyboard) {
        KeyGrid grid = gridCache.get(keyboard);
        if (grid == null) {
            grid = new KeyGrid(keyboard);
            gridCache.put(keyboard, grid);
        }
        return grid;
    }

    public int findKeyIndexAt(int x, int y) {
        if (currentKeyboard == null) return -1;
        KeyGrid grid = currentKeyGrid;
        if (grid == null) {
            grid = getOrBuildGrid(currentKeyboard);
            currentKeyGrid = grid;
        }
        return grid.findKeyIndex(x, y);
    }

    private int updateEnterKeyLabel() {
        if (currentKeyboard == null || currentEditorInfo == null) return -1;

        boolean isMultiLine = (currentEditorInfo.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        int action = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "Enter";

        if (!isMultiLine) {
            switch (action) {
                case EditorInfo.IME_ACTION_GO: label = "Go"; break;
                case EditorInfo.IME_ACTION_NEXT: label = "Next"; break;
                case EditorInfo.IME_ACTION_SEARCH: label = "Search"; break;
                case EditorInfo.IME_ACTION_SEND: label = "Send"; break;
                case EditorInfo.IME_ACTION_DONE: label = "Done"; break;
                default: label = "Enter"; break;
            }
        }

        List<Keyboard.Key> keys = currentKeyboard.getKeys();
        if (keys == null) return -1;
        for (int i = 0; i < keys.size(); i++) {
            Keyboard.Key key = keys.get(i);
            if (key != null && key.codes != null && key.codes.length > 0 && key.codes[0] == -4) {
                String oldLabel = (key.label != null) ? key.label.toString() : null;
                if (!label.equals(oldLabel) || key.icon != null || key.iconPreview != null) {
                    key.label = label;
                    key.icon = null;
                    key.iconPreview = null;
                    return i;
                }
                return -1;
            }
        }
        return -1;
    }

    public void changeLanguage() {
        if (enabledLanguages.isEmpty()) return;
        int currentIndex = enabledLanguages.indexOf(currentLanguageId);
        int nextIndex = (currentIndex + 1) % enabledLanguages.size();
        currentLanguageId = enabledLanguages.get(nextIndex);

        saveCurrentLanguage(getSafePrefs());

        isCaps = false;
        isSymbols = false;
        isCapsLocked = false;
        isEmoji = false;
        currentEmojiPage = 1;

        updateKeyboardLayout();
    }

    public Keyboard getCurrentKeyboard() { return currentKeyboard; }
    public List<Keyboard.Key> getCurrentKeys() { return currentKeyboard != null ? currentKeyboard.getKeys() : null; }
    public boolean isShanOrMyanmar() {
        return currentKeyboard == myanmarKeyboard || currentKeyboard == myanmarShiftKeyboard ||
               currentKeyboard == shanKeyboard || currentKeyboard == shanShiftKeyboard;
    }

    /**
     * Compact 48x24 spatial grid (~2.3 KB per keyboard) for O(1) zero-allocation hit testing.
     */
    private static final class KeyGrid {
        private static final int COLS = 48;
        private static final int ROWS = 24;
        private static final int[] NEIGHBOR_DC = {-1, 1, 0, 0};
        private static final int[] NEIGHBOR_DR = {0, 0, -1, 1};

        private final List<Keyboard.Key> keys;
        private final int width;
        private final int height;
        private final short[] cells;

        KeyGrid(Keyboard keyboard) {
            this.keys = keyboard.getKeys();
            int maxW = keyboard.getMinWidth();
            int maxH = keyboard.getHeight();
            if (keys != null) {
                for (int i = 0; i < keys.size(); i++) {
                    Keyboard.Key k = keys.get(i);
                    if (k != null) {
                        int r = k.x + k.width;
                        int b = k.y + k.height;
                        if (r > maxW) maxW = r;
                        if (b > maxH) maxH = b;
                    }
                }
            }
            this.width = Math.max(1, maxW);
            this.height = Math.max(1, maxH);
            this.cells = new short[COLS * ROWS];

            if (keys == null || keys.isEmpty()) {
                for (int i = 0; i < cells.length; i++) cells[i] = -1;
                return;
            }

            for (int r = 0; r < ROWS; r++) {
                int cy = ((r * 2 + 1) * height) / (ROWS * 2);
                int rowOffset = r * COLS;
                for (int c = 0; c < COLS; c++) {
                    int cx = ((c * 2 + 1) * width) / (COLS * 2);
                    cells[rowOffset + c] = (short) findNearestLinear(cx, cy);
                }
            }
        }

        private int findNearestLinear(int x, int y) {
            int bestIdx = -1;
            int minDist = Integer.MAX_VALUE;
            for (int i = 0; i < keys.size(); i++) {
                Keyboard.Key k = keys.get(i);
                if (k == null || k.codes == null || k.codes.length == 0 || k.codes[0] == -100) continue;
                if (contains(k, x, y)) return i;
                int d = distSq(k, x, y);
                if (d < minDist) {
                    minDist = d;
                    bestIdx = i;
                }
            }
            return bestIdx;
        }

        int findKeyIndex(int x, int y) {
            if (y < -12 || keys == null || keys.isEmpty()) return -1;

            int clampedX = x < 0 ? 0 : (x >= width ? width - 1 : x);
            int clampedY = y < 0 ? 0 : (y >= height ? height - 1 : y);

            int col = (clampedX * COLS) / width;
            int row = (clampedY * ROWS) / height;

            int primaryIdx = cells[row * COLS + col];
            if (primaryIdx >= 0 && primaryIdx < keys.size()) {
                Keyboard.Key primaryKey = keys.get(primaryIdx);
                if (contains(primaryKey, x, y)) {
                    return primaryIdx;
                }
            }

            int bestIdx = primaryIdx;
            int bestDist = (primaryIdx >= 0 && primaryIdx < keys.size())
                    ? distSq(keys.get(primaryIdx), x, y) : Integer.MAX_VALUE;

            for (int d = 0; d < 4; d++) {
                int nc = col + NEIGHBOR_DC[d];
                int nr = row + NEIGHBOR_DR[d];
                if (nc >= 0 && nc < COLS && nr >= 0 && nr < ROWS) {
                    int candidate = cells[nr * COLS + nc];
                    if (candidate >= 0 && candidate != primaryIdx && candidate < keys.size()) {
                        Keyboard.Key k = keys.get(candidate);
                        if (contains(k, x, y)) return candidate;
                        int dist = distSq(k, x, y);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestIdx = candidate;
                        }
                    }
                }
            }
            return bestIdx;
        }

        private static boolean contains(Keyboard.Key k, int x, int y) {
            return x >= k.x && x < (k.x + k.width) && y >= k.y && y < (k.y + k.height);
        }

        private static int distSq(Keyboard.Key k, int x, int y) {
            int dx = 0;
            int dy = 0;
            if (x < k.x) dx = k.x - x;
            else if (x > k.x + k.width) dx = x - (k.x + k.width);
            if (y < k.y) dy = k.y - y;
            else if (y > k.y + k.height) dy = y - (k.y + k.height);
            return (dx * dx) + (dy * dy);
        }
    }
}

