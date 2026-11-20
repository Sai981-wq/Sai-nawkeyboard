package com.sainaw.mm.board;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShanVoiceInput {
    static final String ENDPOINT = "https://myandev-myantts.hf.space/transcribe";
    private static final int RATE = 16000, MAX_PCM_BYTES = RATE * 2 * 30;
    private enum State { IDLE, STARTING, RECORDING, STOPPING, UPLOADING }
    interface ConnectionFactory { HttpURLConnection open() throws java.io.IOException; }

    interface CaptureDevice {
        void start();
        int read(byte[] data, int offset, int count);
        void stop();
        void release();
    }
    interface CaptureFactory { CaptureDevice create(); }
    interface EncodingSession {
        void write(byte[] bytes, int offset, int count) throws Exception;
        byte[] finish() throws Exception;
        void close();
    }
    interface EncodingFactory { EncodingSession open() throws Exception; }

    private final EncodingFactory encodingFactory;
    private final CaptureFactory captureFactory;
    private final ConnectionFactory connectionFactory;
    private final SaiNawKeyboardService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService audioStopper = Executors.newSingleThreadExecutor();
    private final Object recorderLock = new Object();
    private volatile boolean openingMicrophone;
    private final ShanVoiceBells bells;
    private volatile int generation;
    private volatile boolean recording;
    private volatile CaptureDevice recorder;
    private volatile HttpURLConnection connection;
    private State state = State.IDLE;
    private boolean visible, destroyed;
    private Runnable deadline;
    private String activeLanguageCode = "shn";

    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark) {
        this(service, parent, dark, () -> (HttpURLConnection) new URL(ENDPOINT).openConnection());
    }
    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark, ConnectionFactory connectionFactory) {
        this(service, parent, dark, connectionFactory, ShanVoiceInput::createHighQualityMicrophone);
    }
    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark,
                   ConnectionFactory connectionFactory, CaptureFactory captureFactory) {
        this(service, parent, dark, connectionFactory, captureFactory, () -> {
            ShanStreamingOpus encoder = ShanStreamingOpus.open(service, RATE);
            return new EncodingSession() {
                public void write(byte[] b, int o, int n) throws Exception { encoder.write(b, o, n); }
                public byte[] finish() throws Exception { return encoder.finish(); }
                public void close() { encoder.close(); }
            };
        });
    }
    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark,
                   ConnectionFactory connectionFactory, CaptureFactory captureFactory,
                   EncodingFactory encodingFactory) {
        this.service = service;
        this.encodingFactory = encodingFactory;
        this.connectionFactory = connectionFactory;
        this.captureFactory = captureFactory;
        bells = new ShanVoiceBells(service);
        render("");
    }
    void setAvailable(boolean available) {
        if (visible && !available) cancel();
        visible = available;
    }
    void setLanguageCode(String langCode) {
        this.activeLanguageCode = (langCode != null && !langCode.isEmpty()) ? langCode : "shn";
    }
    void toggle() {
        if (destroyed || !visible) return;
        if (state == State.RECORDING) { stopRecording(); return; }
        if (state == State.STARTING) { cancel(); return; }
        if (state != State.IDLE) {
            cancel();
        }
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(service, ShanVoiceConsentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return;
        }
        if (recorder != null || openingMicrophone) {
            CaptureDevice stale = recorder;
            if (stale != null) closeRecorder(stale);
            openingMicrophone = false;
        }
        final int token = ++generation;
        int settleMillis = service.prepareShanVoiceCapture();
        state = State.STARTING;
        render("");

        main.postDelayed(() -> {
            if (!destroyed && visible && generation == token && state == State.STARTING)
                bells.start(() -> { if (!destroyed && visible && generation == token && state == State.STARTING) startRecording(token); });
        }, settleMillis);
    }
    private boolean isUserUnlocked() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            android.os.UserManager users = (android.os.UserManager) service.getSystemService(Context.USER_SERVICE);
            return users != null && users.isUserUnlocked();
        }
        return true;
    }
    private void render(String message) {
        service.onShanVoiceStateChanged();
    }
    String accessibilityLabel() { return "Voice Typing"; }
    boolean isCapturePending() {
        return state == State.STARTING || (state == State.RECORDING && recording)
                || openingMicrophone || recorder != null;
    }
    private void fail(String message) {
        state = State.IDLE;
        if (recorder == null && !openingMicrophone) bells.error();
        render(message);
    }
    private void startRecording(int token) {
        if (!isUserUnlocked() || service.getCurrentInputConnection() == null ||
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(""); return;
        }
        recording = true;
        openingMicrophone = true;
        state = State.RECORDING;
        render("");
        audioWorker.execute(() -> openAndCapture(token));
    }

    @SuppressLint("MissingPermission")
    private static CaptureDevice createHighQualityMicrophone() {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioRecord.getMinBufferSize(RATE, channelConfig, audioFormat);
        int bufferSize = Math.max(minBuffer * 2, RATE * 2);

        AudioRecord audioRecord = null;
        int[] sources = new int[]{
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        };

        for (int src : sources) {
            try {
                AudioRecord candidate = new AudioRecord(src, RATE, channelConfig, audioFormat, bufferSize);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate;
                    break;
                } else {
                    candidate.release();
                }
            } catch (Exception ignored) { }
        }

        if (audioRecord == null) {
            return ShanMicrophone.create(RATE);
        }

        final AudioRecord finalRecord = audioRecord;
        final int sessionId = finalRecord.getAudioSessionId();
        final NoiseSuppressor ns = NoiseSuppressor.isAvailable() ? NoiseSuppressor.create(sessionId) : null;
        final AutomaticGainControl agc = AutomaticGainControl.isAvailable() ? AutomaticGainControl.create(sessionId) : null;
        final AcousticEchoCanceler aec = AcousticEchoCanceler.isAvailable() ? AcousticEchoCanceler.create(sessionId) : null;

        if (ns != null) { try { ns.setEnabled(true); } catch (Exception ignored) { } }
        if (agc != null) { try { agc.setEnabled(true); } catch (Exception ignored) { } }
        if (aec != null) { try { aec.setEnabled(true); } catch (Exception ignored) { } }

        return new CaptureDevice() {
            @Override
            public void start() {
                finalRecord.startRecording();
            }
            @Override
            public int read(byte[] data, int offset, int count) {
                return finalRecord.read(data, offset, count);
            }
            @Override
            public void stop() {
                try {
                    if (finalRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        finalRecord.stop();
                    }
                } catch (Exception ignored) { }
            }
            @Override
            public void release() {
                if (ns != null) { try { ns.release(); } catch (Exception ignored) { } }
                if (agc != null) { try { agc.release(); } catch (Exception ignored) { } }
                if (aec != null) { try { aec.release(); } catch (Exception ignored) { } }
                try { finalRecord.release(); } catch (Exception ignored) { }
            }
        };
    }

    private void openAndCapture(int token) {
        CaptureDevice local = null;
        EncodingSession encoder = null;
        boolean openFailed = false, openingComplete = false, handedToCapture = false;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); }
        catch (RuntimeException ignored) { }
        try {
            if (!recording || generation != token) return;
            try { encoder = encodingFactory.open(); } catch (Exception | LinkageError unavailable) { }
            synchronized (recorderLock) {
                if (!recording || generation != token) return;
                local = captureFactory.create();
                recorder = local;
                if (generation != token) return;
                local.start();
            }
            openingMicrophone = false;
            openingComplete = true;
            handedToCapture = true;
            capture(local, token, encoder);
            encoder = null;
            local = null;
        } catch (Exception e) {
            openFailed = true;
        } finally {
            if (encoder != null) encoder.close();
            if (local != null) closeRecorder(local);
            if (!openingComplete) openingMicrophone = false;
            final boolean error = openFailed;
            final boolean noCapture = !handedToCapture;
            main.post(() -> {
                if (!destroyed && generation == token && (error || (noCapture && state == State.STOPPING))) {
                    recording = false;
                    fail("");
                }
            });
        }
    }
    private void closeRecorder(CaptureDevice local) {
        synchronized (recorderLock) {
            try { local.stop(); } catch (Exception ignored) { }
            try { local.release(); } catch (Exception ignored) { }
            if (recorder == local) recorder = null;
        }
    }

    private void capture(CaptureDevice capture, int token, EncodingSession encoder) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean failed = false;

        long startTime = System.currentTimeMillis();
        long lastVoiceTime = startTime;
        boolean speechDetected = false;
        int maxSilenceAfterSpeechMs = 500;
        int maxInitialSilenceMs = 4500;

        final float alpha = 0.967f;
        float prevInput = 0f;
        float prevOutput = 0f;

        double noiseFloorRms = 180.0;
        int calibrationChunks = 0;

        try {
            byte[] chunk = new byte[640];
            while (recording && generation == token && pcm.size() < MAX_PCM_BYTES) {
                int n = capture.read(chunk, 0, Math.min(chunk.length, MAX_PCM_BYTES - pcm.size()));
                if (n > 0 && generation == token) {
                    int sampleCount = n / 2;
                    double sumSquares = 0.0;

                    for (int i = 0; i < sampleCount; i++) {
                        int idx = i * 2;
                        short rawSample = (short) ((chunk[idx] & 0xFF) | (chunk[idx + 1] << 8));
                        float filtered = alpha * (prevOutput + rawSample - prevInput);
                        prevInput = rawSample;
                        prevOutput = filtered;

                        int cleanSample = Math.max(-32768, Math.min(32767, Math.round(filtered)));
                        if (Math.abs(cleanSample) < 90) {
                            cleanSample = 0;
                        }
                        chunk[idx] = (byte) (cleanSample & 0xFF);
                        chunk[idx + 1] = (byte) ((cleanSample >> 8) & 0xFF);

                        sumSquares += (double) cleanSample * cleanSample;
                    }

                    double rms = sampleCount > 0 ? Math.sqrt(sumSquares / sampleCount) : 0.0;

                    if (calibrationChunks < 6) {
                        noiseFloorRms = (noiseFloorRms * calibrationChunks + Math.min(rms, 600.0)) / (calibrationChunks + 1);
                        calibrationChunks++;
                    }

                    double dynamicThreshold = Math.max(240.0, noiseFloorRms * 2.2);
                    boolean hasVoice = rms > dynamicThreshold;

                    long now = System.currentTimeMillis();
                    if (hasVoice) {
                        speechDetected = true;
                        lastVoiceTime = now;
                    } else {
                        if (!speechDetected && calibrationChunks >= 6) {
                            noiseFloorRms = noiseFloorRms * 0.92 + Math.min(rms, 500.0) * 0.08;
                        }
                        if (speechDetected && (now - lastVoiceTime) >= maxSilenceAfterSpeechMs) {
                            recording = false;
                            main.post(this::stopMicrophone);
                            break;
                        } else if (!speechDetected && (now - startTime) >= maxInitialSilenceMs) {
                            recording = false;
                            main.post(this::stopMicrophone);
                            break;
                        }
                    }

                    pcm.write(chunk, 0, n);
                    if (encoder != null) {
                        try { encoder.write(chunk, 0, n); }
                        catch (Exception | LinkageError unsupported) { encoder.close(); encoder = null; }
                    }
                } else if (recording && generation == token) { failed = true; break; }
                else break;
            }
        } catch (Exception e) { failed = generation == token && recording; }
        finally {
            recording = false;
            closeRecorder(capture);
        }
        ShanVoicePayload payload = null;
        try {
            if (!failed && speechDetected && generation == token && isUserUnlocked()) {
                byte[] samples = normalizePcmLevel(pcm.toByteArray());
                ShanAudioProcessor.prepare(samples, RATE);
                byte[] ogg = null;
                if (encoder != null) {
                    try { ogg = encoder.finish(); } catch (Exception | LinkageError unavailable) { }
                }
                payload = ShanVoicePayload.completed(samples, RATE, ogg);
            }
        } catch (IllegalArgumentException e) { failed = true; }
        finally { if (encoder != null) encoder.close(); }

        if (payload != null && generation == token) upload(payload, token, activeLanguageCode);
        else {
            final boolean error = failed;
            main.post(() -> {
                if (destroyed || generation != token) return;
                if (error || state == State.STOPPING || state == State.RECORDING)
                    fail("");
            });
        }
    }

    private byte[] normalizePcmLevel(byte[] samples) {
        if (samples == null || samples.length < 4) return samples;
        int peak = 0;
        for (int i = 0; i < samples.length - 1; i += 2) {
            int val = Math.abs((short) ((samples[i] & 0xFF) | (samples[i + 1] << 8)));
            if (val > peak) peak = val;
        }
        if (peak > 400 && peak < 24000) {
            float gain = Math.min(3.5f, 26000.0f / peak);
            byte[] normalized = new byte[samples.length];
            for (int i = 0; i < samples.length - 1; i += 2) {
                short raw = (short) ((samples[i] & 0xFF) | (samples[i + 1] << 8));
                int boosted = Math.max(-32768, Math.min(32767, Math.round(raw * gain)));
                normalized[i] = (byte) (boosted & 0xFF);
                normalized[i + 1] = (byte) ((boosted >> 8) & 0xFF);
            }
            return normalized;
        }
        return samples;
    }

    private void stopRecording() {
        recording = false;
        state = State.STOPPING;
        stopMicrophone();
        render("");
    }
    private void stopMicrophone() {
        final CaptureDevice expected = recorder;
        audioStopper.execute(() -> {
            synchronized (recorderLock) {
                CaptureDevice local = recorder == expected ? expected : null;
                if (local != null) try { local.stop(); } catch (Exception ignored) { }
            }
        });
    }

    private void upload(ShanVoicePayload encoded, int token, String langCode) {
        if (generation != token || !isUserUnlocked()) {
            Runnable abort = () -> { if (!destroyed && generation == token) cancel(); };
            if (Looper.myLooper() == Looper.getMainLooper()) abort.run();
            else main.post(abort);
            return;
        }
        Runnable updateState = () -> {
            if (destroyed || generation != token) return;
            state = State.UPLOADING;
            render("");
            deadline = () -> {
                if (!destroyed && generation == token && state == State.UPLOADING) {
                    cancel();
                    fail("");
                }
            };
            main.postDelayed(deadline, 45000);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) updateState.run();
        else main.post(updateState);

        try { worker.execute(() -> {
            int maxRetries = 2;
            boolean success = false;

            for (int attempt = 0; attempt <= maxRetries && !success; attempt++) {
                if (generation != token || destroyed) return;

                HttpURLConnection http = null;
                try {
                    http = connectionFactory.open();
                    connection = http;
                    http.setRequestMethod("POST");

                    http.setConnectTimeout(10000);
                    http.setReadTimeout(25000);
                    http.setInstanceFollowRedirects(true);
                    http.setDoOutput(true);

                    String boundary = "ShanVoice" + java.util.UUID.randomUUID().toString().replace("-", "");
                    byte[] payload = encoded.bytes;
                    byte[] langPart = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n" + langCode + "\r\n").getBytes(StandardCharsets.UTF_8);
                    byte[] head = encoded.multipartHead(boundary);
                    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

                    http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    http.setRequestProperty("Accept", "application/json");
                    http.setFixedLengthStreamingMode(langPart.length + head.length + payload.length + tail.length);

                    try (OutputStream out = http.getOutputStream()) {
                        if (generation != token) return;
                        out.write(langPart);
                        out.write(head);
                        for (int offset = 0; offset < payload.length; offset += 8192) {
                            if (generation != token || !isUserUnlocked()) return;
                            out.write(payload, offset, Math.min(8192, payload.length - offset));
                        }
                        if (generation != token || !isUserUnlocked()) return;
                        out.write(tail);
                    }

                    int code = http.getResponseCode();
                    if (code < 200 || code >= 300) throw new java.io.IOException("HTTP Error: " + code);

                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream in = http.getInputStream()) {
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            if (generation != token) return;
                            if (body.size() + n > 65536) throw new java.io.IOException("Response too large");
                            body.write(buffer, 0, n);
                        }
                    }

                    Object value = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)).opt("text");
                    if (!(value instanceof String) || ((String)value).trim().isEmpty()) throw new java.io.IOException("Empty text");

                    final String text = ((String)value).trim() + " ";
                    success = true;

                    main.post(() -> {
                        if (destroyed || generation != token || state != State.UPLOADING) return;
                        clearDeadline();
                        if (service.commitShanTranscript(text)) {
                            cancel();
                            bells.success();
                        } else {
                            fail("");
                        }
                    });
                } catch (Exception e) {
                    if (attempt == maxRetries) {
                        main.post(() -> {
                            if (destroyed || generation != token || state != State.UPLOADING) return;
                            clearDeadline();
                            fail("");
                        });
                    } else {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { }
                    }
                } finally {
                    if (http != null) http.disconnect();
                    if (connection == http) connection = null;
                }
            }
        }); } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { }
    }

    private void clearDeadline() {
        if (deadline != null) { main.removeCallbacks(deadline); deadline = null; }
    }
    void cancel() {
        if (destroyed) return;
        ++generation;
        recording = false;
        clearDeadline();
        bells.cancelPending();
        stopMicrophone();
        HttpURLConnection http = connection;
        if (http != null) new Thread(http::disconnect, "ShanVoiceCancel").start();
        state = State.IDLE;
        render("");
    }
    void destroy() {
        if (destroyed) return;
        cancel();
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        audioWorker.shutdown();
        audioStopper.shutdown();
        bells.release();
    }
}

