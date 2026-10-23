package com.sainaw.mm.board;

import android.content.Context;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ShanOpusTest {
    @Test
    @Config(sdk = 28)
    public void rejectsOggMuxingBeforeApi29() {
        Context context = RuntimeEnvironment.getApplication();
        assertThrows(UnsupportedOperationException.class, () -> ShanOpus.encode(context, new byte[3200], 16000));
    }

    @Test
    @Config(sdk = 29)
    public void rejectsOddLengthPcmBeforeTouchingCodec() {
        Context context = RuntimeEnvironment.getApplication();
        assertThrows(IllegalArgumentException.class, () -> ShanOpus.encode(context, new byte[3], 16000));
    }

    @Test
    @Config(sdk = 29)
    public void rejectsAudioOverThirtySeconds() {
        Context context = RuntimeEnvironment.getApplication();
        assertThrows(IllegalArgumentException.class, () -> ShanOpus.encode(context, new byte[16000 * 31 * 2], 16000));
    }
}
