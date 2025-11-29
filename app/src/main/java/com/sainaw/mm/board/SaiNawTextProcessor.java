package com.sainaw.mm.board;

public class SaiNawTextProcessor {

    private static final char ZWSP = '\u200B';

    // ဗျည်း ဟုတ်/မဟုတ် စစ်ဆေးခြင်း
    public boolean isConsonant(int c) {
        return (c >= '\u1000' && c <= '\u102A') || // Myanmar Main
               (c == '\u103F') ||                   // Great Sa
               (c >= '\u1040' && c <= '\u1049') || // Digits
               (c == '\u104E') ||                   // La Gaung
               (c >= '\u1050' && c <= '\u1055') || // Mon/Pali
               (c >= '\u1075' && c <= '\u1081') || // Shan Consonants
               (c >= '\uA9E0' && c <= '\uA9E6') || // Shan Digits
               (c >= '\uAA60' && c <= '\uAA6F');   // Khamti Shan
    }

    public boolean isMedial(int code) {
        return (code >= 4155 && code <= 4158) || (code == 4226);
    }

    // *** NEW: Database မထည့်ခင် စာလုံးစီစစ်ပြီး နေရာပြန်ချပေးမယ့် Function ***
    public String normalizeText(String input) {
        if (input == null || input.isEmpty()) return input;
        
        // ZWSP တွေပါနေရင် အရင်ဖယ်ထုတ်မယ် (Database ထဲမှာ မလိုလို့ပါ)
        String cleanStr = input.replace(String.valueOf(ZWSP), "");
        char[] chars = cleanStr.toCharArray();
        int len = chars.length;

        // စာတစ်လုံးချင်းစီကို လိုက်စစ်ပြီး နေရာမှားနေရင် လဲမယ် (Swap)
        for (int i = 1; i < len; i++) {
            char current = chars[i];
            char prev = chars[i-1];

            // ၁။ သဝေထိုး ပြဿနာ (ေ + က -> က + ေ)
            // ရှေ့ကစာလုံးက 'ေ' (သို့) ရှမ်း 'ႄ' ဖြစ်ပြီး၊ လက်ရှိစာလုံးက "ဗျည်း" ဖြစ်နေရင် နေရာချင်းလဲမယ်
            if ((prev == '\u1031' || prev == '\u1084') && isConsonant(current)) {
                chars[i-1] = current;
                chars[i] = prev;
            }

            // ၂။ လုံးကြီးတင်/တစ်ချောင်းငင် ပြဿနာ (ု + ိ -> ိ + ု)
            // လက်ရှိက 'ိ' ဖြစ်ပြီး၊ ရှေ့က 'ု' (သို့) 'ူ' ဖြစ်နေရင် နေရာချင်းလဲမယ်
            if (current == '\u102D' && (prev == '\u102F' || prev == '\u1030')) {
                chars[i-1] = current;
                chars[i] = prev;
            }

            // ၃။ သေးသေးတင်/တစ်ချောင်းငင် ပြဿနာ (ံ + ု -> ု + ံ)
            // လက်ရှိက 'ု' ဖြစ်ပြီး၊ ရှေ့က 'ံ' ဖြစ်နေရင် နေရာချင်းလဲမယ်
            if (current == '\u102F' && prev == '\u1036') {
                chars[i-1] = current;
                chars[i] = prev;
            }
            
            // ၄။ အောက်ကမြစ်/တစ်ချောင်းငင် (့ + ု -> ု + ့)
            if (current == '\u102F' && prev == '\u1037') {
                chars[i-1] = current;
                chars[i] = prev;
            }
        }
        return new String(chars);
    }

    // စာလုံးပေါင်း (Syllable) ကို ရှာဖွေပေးမယ့် Logic
    public String getSyllableToSpeak(CharSequence textBefore) {
        if (textBefore == null || textBefore.length() == 0) return null;

        String text = textBefore.toString();
        int endIndex = text.length();
        int startIndex = endIndex;
        
        boolean isKilledOrStacked = false;

        for (int i = endIndex - 1; i >= 0; i--) {
            char c = text.charAt(i);
            
            if (c == '\u103A' || c == '\u1039') {
                isKilledOrStacked = true;
                continue;
            }

            if (isConsonant(c)) {
                if (isKilledOrStacked) {
                    isKilledOrStacked = false; 
                } else {
                    if (i > 0 && text.charAt(i - 1) == '\u1039') {
                        continue; 
                    }
                    startIndex = i;
                    break; 
                }
            } 
            else if (c == ' ' || c == '\n' || c == '\t') {
                startIndex = i + 1;
                break;
            } 
            else {
                if (isKilledOrStacked) isKilledOrStacked = false;
            }
        }

        if (startIndex < endIndex) {
            String syllable = text.substring(startIndex, endIndex);
            return syllable.replace(String.valueOf(ZWSP), "");
        }
        return null;
    }

    // Space ခြားလိုက်တဲ့အခါ စကားစုကို ရှာပေးမယ့် Logic
    public String getWordForEcho(CharSequence textBefore) {
        if (textBefore == null || textBefore.length() == 0) return null;

        String text = textBefore.toString();
        int lastSpaceIndex = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t') {
                lastSpaceIndex = i;
                break;
            }
        }

        String word = (lastSpaceIndex == -1) ? text : text.substring(lastSpaceIndex + 1);
        return word.replace(String.valueOf(ZWSP), "").trim();
    }
}

