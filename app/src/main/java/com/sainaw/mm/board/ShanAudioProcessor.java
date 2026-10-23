package com.sainaw.mm.board;

/** 
 * PCM16 mono audio processor.
 * Added Silence Trimming (VAD) to reduce payload size and speed up network upload. 
 */
public final class ShanAudioProcessor {
    private static final int MIN_DURATION_MS = 100;
    // အသံတိတ်နေခြင်းကို ခွဲခြားမည့် အတိုင်းအတာ (Threshold) - လိုအပ်ပါက အတိုးအလျှော့ လုပ်နိုင်သည်
    private static final int SILENCE_THRESHOLD = 800;

    private ShanAudioProcessor() { }

    /** Silence များကို ဖြတ်ထုတ်ပြီး လိုအပ်သော အသံကိုသာ ယူမည် */
    public static byte[] prepare(byte[] pcm, int rate) {
        if (pcm == null) throw new IllegalArgumentException("PCM must not be null");
        if (rate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 data must contain complete samples");
        if ((pcm.length / 2L) * 1000L < (long) rate * MIN_DURATION_MS)
            throw new IllegalArgumentException("Audio must be at least 100 ms");

        int startOffset = 0;
        int endOffset = pcm.length;

        // ရှေ့ပိုင်းမှ အသံတိတ်နေသည့်အပိုင်း (Silence) ကို ရှာ၍ ဖြတ်ထုတ်ခြင်း
        for (int i = 0; i < pcm.length - 1; i += 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (Math.abs(sample) > SILENCE_THRESHOLD) {
                // အသံမစမီ အနည်းငယ် (200ms ခန့်) ကို ချန်ထားပေးမည်
                int paddingBytes = (rate / 5) * 2; 
                startOffset = Math.max(0, i - paddingBytes);
                startOffset &= ~1; // Even byte boundary ဖြစ်စေရန်
                break;
            }
        }

        // နောက်ပိုင်းမှ အသံတိတ်နေသည့်အပိုင်း (Silence) ကို ရှာ၍ ဖြတ်ထုတ်ခြင်း
        for (int i = pcm.length - 2; i >= 0; i -= 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (Math.abs(sample) > SILENCE_THRESHOLD) {
                // အသံဆုံးပြီးနောက် အနည်းငယ် (300ms ခန့်) ကို ချန်ထားပေးမည်
                int paddingBytes = (rate * 3 / 10) * 2;
                endOffset = Math.min(pcm.length, i + 2 + paddingBytes);
                endOffset &= ~1;
                break;
            }
        }

        if (startOffset >= endOffset) {
            throw new IllegalArgumentException("Audio contains only silence");
        }

        int newLength = endOffset - startOffset;
        if (newLength == pcm.length) {
            return pcm.clone();
        }

        // ဖြတ်ထုတ်ပြီးသား အသံဖိုင်အသစ်ကိုသာ ပြန်ပေးမည်
        byte[] trimmedPcm = new byte[newLength];
        System.arraycopy(pcm, startOffset, trimmedPcm, 0, newLength);
        return trimmedPcm;
    }
}

