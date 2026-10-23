package com.sainaw.mm.board;

import android.media.MediaRecorder;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ShanMicrophoneTest {
    @Test public void retriesNextSourceWhenRecorderInitializationFails() {
        FakePlatform p = new FakePlatform(); p.initialized = false;
        try { ShanMicrophone.create(16000, p); fail("expected failure"); }
        catch (IllegalStateException expected) { }
        assertEquals(list(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), p.sources);
        assertEquals(3, p.releasedRecorders);
    }

    @Test public void failedStartReleasesRecorderAndEffects() {
        FakePlatform p = new FakePlatform(); p.startFails = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        assertEquals(list("aec:57", "ns:57"), p.effectCalls);
        d.start();
        assertEquals(list(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC), p.sources);
        assertEquals(list("aec:57", "ns:57", "aec:57", "aec:57", "ns:57"), p.effectCalls);
        d.release();
        assertEquals(3, p.releasedRecorders);
        assertEquals(5, p.releasedEffects);
    }

    @Test public void effectFailureIsIndependentAndReleaseIsReliable() {
        FakePlatform p = new FakePlatform(); p.aecFails = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        assertTrue(p.effectCalls.contains("ns:57"));
        d.start(); d.stop(); d.release(); d.release();
        assertEquals(1, p.releasedRecorders);
        assertEquals(1, p.releasedEffects);
    }

    @Test public void failedEffectEnableReleasesThatEffect() {
        FakePlatform p = new FakePlatform(); p.aecEnableFails = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        assertEquals(1, p.releasedEffects);
        d.release();
        assertEquals("Failed AEC and surviving NS each released exactly once", 2, p.releasedEffects);
        d.release(); assertEquals(2, p.releasedEffects);
    }

    @Test public void absentEffectsStillAllowCaptureAndRelease() {
        FakePlatform p = new FakePlatform(); p.noEffects = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        d.start(); d.stop(); d.release();
        assertEquals(1, p.releasedRecorders); assertEquals(0, p.releasedEffects);
    }
    @Test public void optionalEffectLinkageFailureDoesNotPreventOtherEffect() {
        FakePlatform p = new FakePlatform(); p.aecLinkageFails = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        d.start(); d.release();
        assertEquals(1, p.releasedRecorders); assertEquals(1, p.releasedEffects);
    }
    @Test public void allStartFailuresReleaseEveryRecorderAndEffect() {
        FakePlatform p = new FakePlatform(); p.allStartFails = true;
        ShanVoiceInput.CaptureDevice d = ShanMicrophone.create(16000, p);
        assertThrows(IllegalStateException.class, d::start);
        d.release();
        assertEquals(3, p.releasedRecorders); assertEquals(5, p.releasedEffects);
    }
    @Test public void unsupportedPcmNeverCreatesRecorder() {
        FakePlatform p = new FakePlatform(); p.minBuffer = -2;
        assertThrows(IllegalStateException.class, () -> ShanMicrophone.create(16000, p));
        assertTrue(p.sources.isEmpty());
    }

    private static List<Integer> list(int... values) {
        List<Integer> out = new ArrayList<>(); for (int v : values) out.add(v); return out;
    }
    private static List<String> list(String... values) {
        List<String> out = new ArrayList<>(); for (String v : values) out.add(v); return out;
    }
    private static final class FakePlatform implements ShanMicrophone.Platform {
        final List<Integer> sources = new ArrayList<>(); final List<String> effectCalls = new ArrayList<>();
        boolean initialized = true, startFails, aecFails, aecEnableFails, noEffects, aecLinkageFails, allStartFails;
        int minBuffer = 320;
        int releasedRecorders, releasedEffects;
        public int minBufferSize(int rate) { return minBuffer; }
        public ShanMicrophone.Recorder createRecorder(int source, int rate, int bytes) {
            sources.add(source);
            if (initialized) return new ShanMicrophone.Recorder() {
                public boolean initialized() { return true; }
                public int sessionId() { return 57; }
                public void start() { if (allStartFails || (startFails && source != MediaRecorder.AudioSource.MIC)) throw new IllegalStateException("start"); }
                public int read(byte[] d, int o, int c) { return c; }
                public void stop() { }
                public void release() { releasedRecorders++; }
            };
            return new ShanMicrophone.Recorder() {
                public boolean initialized() { return false; }
                public int sessionId() { return 57; }
                public void start() { }
                public int read(byte[] d, int o, int c) { return 0; }
                public void stop() { }
                public void release() { releasedRecorders++; }
            };
        }
        public ShanMicrophone.Effect createAec(int session) {
            effectCalls.add("aec:" + session); if (aecLinkageFails) throw new UnsatisfiedLinkError("optional AEC"); if (aecFails) throw new IllegalStateException("aec");
            return noEffects ? null : effect();
        }
        public ShanMicrophone.Effect createNoiseSuppressor(int session) {
            effectCalls.add("ns:" + session); return noEffects ? null : effect();
        }
        private ShanMicrophone.Effect effect() {
            return new ShanMicrophone.Effect() {
                public void enable() { if (aecEnableFails && effectCalls.get(effectCalls.size() - 1).startsWith("aec:")) throw new IllegalStateException("enable"); }
                public void release() { releasedEffects++; }
            };
        }
    }
}
