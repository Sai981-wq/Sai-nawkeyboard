package com.sainaw.mm.board;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

/** Optimized network upload with fast timeout, auto-spacing, AAC fallback and auto-retry. */
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
    private volatile SharedPreferences privacy;
    private final SharedPreferences.OnSharedPreferenceChangeListener privacyListener = (prefs, key) -> {
        if (ShanVoiceConsentActivity.CONSENT.equals(key) && !prefs.getBoolean(key, false))
            main.post(() -> { if (!destroyed) cancel(); });
    };

    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark) {
        this(service, parent, dark, () -> (HttpURLConnection) new URL(ENDPOINT).openConnection());
    }
    ShanVoiceInput(SaiNawKeyboardService service, LinearLayout parent, boolean dark, ConnectionFactory connectionFactory) {
        this(service, parent, dark, connectionFactory, ShanVoiceInput::createMicrophone);
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
    void toggle() {
        if (destroyed || !visible) return;
        if (state == State.RECORDING) { stopRecording(); return; }
        if (state == State.STARTING) { cancel(); return; }
        if (state != State.IDLE) return;
        if (!hasConsent() || ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(service, ShanVoiceConsentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            return; 
        }
        if (recorder != null || openingMicrophone) { fail("မိုက်ပိတ်သိမ်းနေဆဲ ဖြစ်သည်။ ခဏစောင့်ပြီး ပြန်နှိပ်ပါ။"); return; }
        final int token = ++generation;
        int settleMillis = service.prepareShanVoiceCapture();
        state = State.STARTING;
        render("Voice Typing — အသံဖမ်းရန် ပြင်ဆင်နေသည်။ ထပ်နှိပ်၍ ပယ်ဖျက်နိုင်သည်။");
        
        main.postDelayed(() -> {
            if (!destroyed && visible && generation == token && state == State.STARTING)
                bells.start(() -> { if (!destroyed && visible && generation == token && state == State.STARTING) startRecording(token); });
        }, settleMillis);
    }
    private synchronized boolean hasConsent() {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            android.os.UserManager users = (android.os.UserManager) service.getSystemService(Context.USER_SERVICE);
            if (users == null || !users.isUserUnlocked()) return false;
        }
        if (privacy == null) {
            privacy = service.getSharedPreferences(ShanVoiceConsentActivity.PREFS, Context.MODE_PRIVATE);
            privacy.registerOnSharedPreferenceChangeListener(privacyListener);
        }
        return privacy.getBoolean(ShanVoiceConsentActivity.CONSENT, false);
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
        if (!hasConsent() || service.getCurrentInputConnection() == null ||
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("မိုက်ခွင့် သို့မဟုတ် စာရိုက်နေရာ မရပါ။ အသံမဖမ်းပါ။"); return;
        }
        recording = true;
        openingMicrophone = true;
        state = State.RECORDING;
        render(""); 
        audioWorker.execute(() -> openAndCapture(token));
    }
    private static CaptureDevice createMicrophone() {
        return ShanMicrophone.create(RATE);
    }

    private void openAndCapture(int token) {
        CaptureDevice local = null;
        EncodingSession encoder = null;
        boolean openFailed = false, openingComplete = false, handedToCapture = false;
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); }
        catch (RuntimeException ignored) { }
        try {
            if (!recording || generation != token) return;
            try { encoder = encodingFactory.open(); } catch (Exception | LinkageError unavailable) { }
            synchronized (recorderLock) {
                if (!recording || generation != token) return;
                local = captureFactory.create();
                recorder = local;
                if (!recording || generation != token) return;
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
                    fail("အသံမရပါ။ Voice Typing ကို ပြန်စမ်းနိုင်ပါသည်။");
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
        try {
            byte[] chunk = new byte[640]; 
            while (recording && generation == token && pcm.size() < MAX_PCM_BYTES) {
                int n = capture.read(chunk, 0, Math.min(chunk.length, MAX_PCM_BYTES - pcm.size()));
                if (n > 0 && generation == token) {
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
            if (!failed && generation == token && hasConsent()) {
                byte[] samples = pcm.toByteArray();
                ShanAudioProcessor.prepare(samples, RATE);
                byte[] ogg = null;
                if (encoder != null) {
                    try { ogg = encoder.finish(); } catch (Exception | LinkageError unavailable) { }
                }
                payload = ShanVoicePayload.completed(service, samples, RATE, ogg);
            }
        } catch (IllegalArgumentException e) { failed = true; }
        finally { if (encoder != null) encoder.close(); }
        
        if (payload != null && generation == token) upload(payload, token);
        else {
            final boolean error = failed;
            main.post(() -> {
                if (destroyed || generation != token) return;
                if (error || state == State.STOPPING || state == State.RECORDING)
                    fail("အသံမရပါ သို့မဟုတ် တိုလွန်းပါသည်။ ပြန်စမ်းပါ။ အသံမပို့ထားပါ။");
            });
        }
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
    
    private void upload(byte[] payload, int token) {
        upload(ShanVoicePayload.aacOrWav(service, payload, RATE), token);
    }
    
    private void upload(ShanVoicePayload encoded, int token) {
        if (generation != token || !hasConsent()) {
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
                    fail("စာပြောင်းချိန် ကျော်လွန်သွားပါပြီ။ အင်တာနက်စစ်ပြီး ပြန်စမ်းပါ။");
                }
            };
            // အမြန်ဆုံး တုံ့ပြန်မှုရရန် Timeout ကို စက္ကန့် ၆၀ သို့ လျှော့ချထားပါသည်
            main.postDelayed(deadline, 60000); 
        };
        if (Looper.myLooper() == Looper.getMainLooper()) updateState.run();
        else main.post(updateState);
        
        try { worker.execute(() -> {
            int maxRetries = 1; // မြန်ဆန်စေရန် Retry ကို ၁ ကြိမ်သာ ထားပါသည်
            boolean success = false;
            
            for (int attempt = 0; attempt <= maxRetries && !success; attempt++) {
                if (generation != token || destroyed) return;
                
                HttpURLConnection http = null;
                try {
                    http = connectionFactory.open();
                    connection = http;
                    http.setRequestMethod("POST");
                    
                    // မြန်ဆန်စေရန် Connection Timeout ကို 8 စက္ကန့်နှင့် Read Timeout ကို 30 စက္ကန့်သာ ထားပါသည်
                    http.setConnectTimeout(8000); 
                    http.setReadTimeout(30000);    
                    http.setInstanceFollowRedirects(false);
                    http.setDoOutput(true);
                    
                    String boundary = "ShanVoice" + java.util.UUID.randomUUID().toString().replace("-", "");
                    byte[] payload = encoded.bytes;
                    byte[] head = encoded.multipartHead(boundary);
                    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                    
                    http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    http.setRequestProperty("Accept", "application/json");
                    http.setFixedLengthStreamingMode(head.length + payload.length + tail.length);
                    
                    try (OutputStream out = http.getOutputStream()) {
                        if (generation != token) return;
                        out.write(head);
                        for (int offset = 0; offset < payload.length; offset += 8192) {
                            if (generation != token || !hasConsent()) return;
                            out.write(payload, offset, Math.min(8192, payload.length - offset));
                        }
                        if (generation != token || !hasConsent()) return;
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
                    
                    // စကားစုတစ်စုနှင့်တစ်စုကြား Space ( ) လေး အလိုအလျောက်ခြားပေးရန် ပေါင်းထည့်ထားပါသည်
                    final String text = ((String)value).trim() + " ";
                    success = true;
                    
                    main.post(() -> {
                        if (destroyed || generation != token || state != State.UPLOADING) return;
                        clearDeadline();
                        if (!hasConsent()) { cancel(); return; }
                        if (service.commitShanTranscript(text)) {
                            cancel(); bells.success();
                            render("ရှမ်းစာ ထည့်ပြီးပါပြီ။ ဆက်ရိုက်နိုင်ပါသည်။");
                        } else {
                            fail("စာရိုက်နေရာတွင် စာထည့်မရပါ။");
                        }
                    });
                } catch (Exception e) {
                    if (attempt == maxRetries) {
                        main.post(() -> {
                            if (destroyed || generation != token || state != State.UPLOADING) return;
                            clearDeadline();
                            fail("စာပြောင်းမရပါ။ အင်တာနက် သို့မဟုတ် ဆာဗာအခြေအနေကို စစ်ပါ။");
                        });
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ie) {} // မြန်ဆန်စေရန် Retry စောင့်ချိန် 0.5 စက္ကန့်
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
        if (privacy != null) privacy.unregisterOnSharedPreferenceChangeListener(privacyListener);
        worker.shutdownNow();
        audioWorker.shutdown(); 
        audioStopper.shutdown();
        bells.release();
    }
}

