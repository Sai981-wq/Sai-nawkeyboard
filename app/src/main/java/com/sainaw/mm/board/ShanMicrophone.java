package com.sainaw.mm.board;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

/** PCM16 mono microphone capture. NoiseSuppressor is disabled for raw accuracy. */
final class ShanMicrophone {
    private ShanMicrophone() { }

    interface Platform {
        int minBufferSize(int rate);
        Recorder createRecorder(int source, int rate, int bufferBytes);
        Effect createAec(int sessionId);
        Effect createNoiseSuppressor(int sessionId);
    }
    interface Recorder {
        boolean initialized();
        int sessionId();
        void start();
        int read(byte[] data, int offset, int count);
        void stop();
        void release();
    }
    interface Effect { void enable(); void release(); }

    static ShanVoiceInput.CaptureDevice create(int rate) {
        return create(rate, new AndroidPlatform());
    }

    static ShanVoiceInput.CaptureDevice create(int rate, Platform platform) {
        if (rate <= 0) throw new IllegalArgumentException("rate must be positive");
        int min = platform.minBufferSize(rate);
        if (min <= 0) throw new IllegalStateException("Unsupported audio format");
        int bufferBytes = Math.max(min, rate / 5 * 2);
        int[] sources = { MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC };
        RuntimeException lastFailure = null;
        for (int i = 0; i < sources.length; i++) {
            try {
                Recorder recorder = createRecorder(platform, sources[i], rate, bufferBytes);
                EffectBundle effects = attachEffects(platform, recorder.sessionId(), sources[i]);
                return new Device(platform, rate, bufferBytes, sources, i, recorder, effects);
            } catch (RuntimeException failure) { lastFailure = failure; }
        }
        throw lastFailure != null ? lastFailure : new IllegalStateException("Unable to create AudioRecord");
    }

    private static Recorder createRecorder(Platform platform, int source, int rate, int bufferBytes) {
        Recorder recorder = null;
        try {
            recorder = platform.createRecorder(source, rate, bufferBytes);
            if (recorder == null || !recorder.initialized()) throw new IllegalStateException("AudioRecord not initialized");
            return recorder;
        } catch (RuntimeException failure) {
            if (recorder != null) safeReleaseRecorder(recorder);
            throw failure;
        }
    }

    private static EffectBundle attachEffects(Platform p, int session, int source) {
        EffectBundle bundle = new EffectBundle();
        bundle.add(createEffect(() -> p.createAec(session)));
        // ရှင်းလင်းသော သဘာဝအသံ (Raw Audio) ရရှိစေရန် Noise Suppressor ကို ပိတ်ထားပါသည်
        /*
        if (source != MediaRecorder.AudioSource.VOICE_RECOGNITION)
            bundle.add(createEffect(() -> p.createNoiseSuppressor(session)));
        */
        return bundle;
    }
    private interface EffectCreator { Effect create(); }
    private static Effect createEffect(EffectCreator creator) {
        Effect effect = null;
        try { effect = creator.create(); if (effect != null) effect.enable(); return effect; }
        catch (RuntimeException | LinkageError ignored) {
            if (effect != null) safeReleaseEffect(effect);
            return null;
        }
    }

    private static final class EffectBundle {
        private final java.util.ArrayList<Effect> effects = new java.util.ArrayList<>();
        void add(Effect effect) { if (effect != null) effects.add(effect); }
        void release() { for (Effect effect : effects) safeReleaseEffect(effect); effects.clear(); }
    }
    private static void safeReleaseEffect(Effect effect) {
        try { effect.release(); } catch (RuntimeException | LinkageError ignored) { }
    }

    private static final class Device implements ShanVoiceInput.CaptureDevice {
        private final Platform platform;
        private final int rate, bufferBytes;
        private final int[] sources;
        private int sourceIndex;
        private Recorder recorder;
        private EffectBundle effects;
        private boolean started, released;
        Device(Platform platform, int rate, int bufferBytes, int[] sources, int sourceIndex,
               Recorder recorder, EffectBundle effects) {
            this.platform = platform; this.rate = rate; this.bufferBytes = bufferBytes;
            this.sources = sources; this.sourceIndex = sourceIndex; this.recorder = recorder; this.effects = effects;
        }
        @Override public synchronized void start() {
            if (released) throw new IllegalStateException("Capture device released");
            RuntimeException lastFailure = null;
            while (sourceIndex < sources.length) {
                try {
                    if (recorder == null) {
                        recorder = createRecorder(platform, sources[sourceIndex], rate, bufferBytes);
                        effects = attachEffects(platform, recorder.sessionId(), sources[sourceIndex]);
                    }
                    recorder.start(); started = true; return;
                } catch (RuntimeException failure) {
                    lastFailure = failure;
                    releaseCurrent();
                    sourceIndex++;
                }
            }
            released = true;
            throw lastFailure != null ? lastFailure : new IllegalStateException("Unable to start AudioRecord");
        }
        @Override public int read(byte[] data, int offset, int count) { return recorder.read(data, offset, count); }
        @Override public synchronized void stop() {
            if (started && !released) { try { recorder.stop(); } catch (RuntimeException ignored) { } started = false; }
        }
        @Override public synchronized void release() { if (!released) { releaseCurrent(); released = true; } }
        private void releaseCurrent() {
            if (effects != null) effects.release();
            if (recorder != null) safeReleaseRecorder(recorder);
            effects = null; recorder = null; started = false;
        }
    }
    private static void safeReleaseRecorder(Recorder recorder) {
        try { recorder.stop(); } catch (RuntimeException ignored) { }
        try { recorder.release(); } catch (RuntimeException ignored) { }
    }

    private static final class AndroidPlatform implements Platform {
        @Override public int minBufferSize(int rate) {
            return AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }
        @Override public Recorder createRecorder(int source, int rate, int bufferBytes) {
            AudioRecord audio = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            return new Recorder() {
                public boolean initialized() { return audio.getState() == AudioRecord.STATE_INITIALIZED; }
                public int sessionId() { return audio.getAudioSessionId(); }
                public void start() {
                    audio.startRecording();
                    if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        throw new IllegalStateException("AudioRecord failed to start");
                }
                public int read(byte[] data, int offset, int count) { return audio.read(data, offset, count); }
                public void stop() { audio.stop(); }
                public void release() { audio.release(); }
            };
        }
        @Override public Effect createAec(int sessionId) {
            try {
                if (!AcousticEchoCanceler.isAvailable()) return null;
                AcousticEchoCanceler effect = AcousticEchoCanceler.create(sessionId);
                return effect == null ? null : new Effect() {
                    public void enable() { if (effect.setEnabled(true) != android.media.audiofx.AudioEffect.SUCCESS) throw new IllegalStateException("AEC enable failed"); }
                    public void release() { effect.release(); }
                };
            } catch (RuntimeException | LinkageError ignored) { return null; }
        }
        @Override public Effect createNoiseSuppressor(int sessionId) {
            try {
                if (!NoiseSuppressor.isAvailable()) return null;
                NoiseSuppressor effect = NoiseSuppressor.create(sessionId);
                return effect == null ? null : new Effect() {
                    public void enable() { if (effect.setEnabled(true) != android.media.audiofx.AudioEffect.SUCCESS) throw new IllegalStateException("NS enable failed"); }
                    public void release() { effect.release(); }
                };
            } catch (RuntimeException | LinkageError ignored) { return null; }
        }
    }
}

