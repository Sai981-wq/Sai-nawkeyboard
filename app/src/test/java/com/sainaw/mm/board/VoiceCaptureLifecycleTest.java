package com.sainaw.mm.board;

import static org.junit.Assert.*;

import android.widget.LinearLayout;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises actual audioWorker capture lifecycle using entirely in-memory devices. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VoiceCaptureLifecycleTest {
    private ServiceController<SaiNawKeyboardService> controller;
    private SaiNawKeyboardService service;
    private ShanVoiceInput voice;
    private AtomicInteger factoryCalls;
    private FakeDevice device;
    private MemoryConnection connection;
    private final AtomicInteger uploads = new AtomicInteger();

    @Before public void setup() throws Exception {
        controller = Robolectric.buildService(SaiNawKeyboardService.class).create();
        service = controller.get();
        factoryCalls = new AtomicInteger();
        device = new FakeDevice();
        connection = new MemoryConnection();
        voice = new ShanVoiceInput(service, new LinearLayout(service), false, () -> {
            uploads.incrementAndGet(); return connection;
        }, () -> { factoryCalls.incrementAndGet(); return device; }, () -> null);
        voice.setAvailable(true);
        service.getSharedPreferences(ShanVoiceConsentActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ShanVoiceConsentActivity.CONSENT, true).commit();
    }

    @After public void cleanup() {
        if (voice != null) voice.destroy();
        if (controller != null) controller.destroy();
    }

    @Test public void stopBeforeQueuedOpenAbortsOpenerAndLeavesIdle() throws Exception {
        int token = prepareRecording();
        CountDownLatch workerGate = holdWorker();
        invokeOpenAsync(token);
        voice.toggle(); // explicit stop while the opener is queued
        workerGate.countDown();
        awaitAudio();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0, factoryCalls.get());
        assertEquals("IDLE", state());
        assertFalse(voice.isCapturePending());
    }

    @Test public void stopDuringBlockedFactoryDoesNotWaitOnUiAndAbortedOpenSettlesIdle() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1), allowFactory = new CountDownLatch(1);
        set(voice, "captureFactory", (ShanVoiceInput.CaptureFactory) () -> {
            factoryCalls.incrementAndGet(); factoryEntered.countDown(); await(allowFactory); return device;
        });
        int token = prepareRecording();
        invokeOpenAsync(token);
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
        long began = System.nanoTime();
        voice.toggle();
        assertTrue("UI stop must not wait for blocked factory", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began) < 500);
        assertEquals("STOPPING", state());
        allowFactory.countDown();
        awaitAudio();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("IDLE", state());
        assertFalse(voice.isCapturePending());
        assertEquals(0, device.starts.get());
    }

    @Test public void readAlreadyInFlightIsKeptUntilPhysicalReleaseWithoutFeedbackGating() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1), finishRead = new CountDownLatch(1);
        CountDownLatch releaseEntered = new CountDownLatch(1), finishRelease = new CountDownLatch(1);
        device.onRead = () -> { if (device.readCalls == 6) { readEntered.countDown(); await(finishRead); } };
        device.onRelease = () -> { releaseEntered.countDown(); await(finishRelease); };
        int token = prepareRecording(); invokeOpenAsync(token);
        assertTrue(readEntered.await(5, TimeUnit.SECONDS));
        voice.toggle();
        assertEquals("STOPPING", state());
        finishRead.countDown();
        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        finishRelease.countDown();
        awaitAudio();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        awaitHttp();
        assertEquals(3840, device.readBytes); // sixth 20 ms in-flight chunk retained
        byte[] pcm = payloadPcm();
        assertEquals(3840, pcm.length);
        for (int i = 0; i < pcm.length; i++) assertEquals((byte)(device.sample + i % 640), pcm[i]);
        assertEquals(1, device.releases.get());
        assertEquals(1, uploads.get()); // in-memory request only, no socket
    }

    @Test public void cancelInvalidatesBlockedOpenGenerationWithoutCreatingOrUploading() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        set(voice, "captureFactory", (ShanVoiceInput.CaptureFactory) () -> {
            factoryCalls.incrementAndGet(); factoryEntered.countDown(); await(releaseFactory); return device;
        });
        int token = prepareRecording(); invokeOpenAsync(token);
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
        voice.cancel();
        releaseFactory.countDown(); awaitAudio();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, factoryCalls.get());
        assertEquals(0, device.starts.get());
        assertEquals("IDLE", state());
        assertEquals(0, uploads.get());
    }

    @Test public void captureCapsAtExactlyThirtySecondsWithoutLosingBoundarySamples() throws Exception {
        device.readMode = FakeDevice.Mode.FILL;
        device.sample = 0x5a;
        int token = prepareRecording(); invokeOpenAsync(token);
        awaitAudio();
        // Audio cap is 16kHz * 2 bytes * 30 sec; fake read fills requested counts exactly.
        assertEquals(960000, device.readBytes);
        assertEquals(1500, device.readCalls);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        awaitHttp();
        assertEquals(1, uploads.get());
        byte[] pcm = payloadPcm();
        assertEquals(960000, pcm.length);
        assertEquals((byte)0x5a, pcm[0]);
        assertEquals((byte)0x5a, pcm[pcm.length - 1]);
        assertEquals(1, device.releases.get());
    }

    @Test public void streamingStartsBeforeMicAndHttpDoesNotWaitForUiLooper() throws Exception {
        FakeEncoding encoding = new FakeEncoding();
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> {
            assertEquals(0, device.starts.get()); return encoding;
        });
        device.readMode = FakeDevice.Mode.FILL;
        int token = prepareRecording(); invokeOpenAsync(token);
        awaitAudio(); awaitHttp(); // deliberately do not idle the UI looper
        assertEquals("HTTP is submitted without UI admission", 1, uploads.get());
        assertEquals(960000, encoding.bytes);
        assertEquals(1, encoding.finishes);
        assertEquals(1, encoding.closes);
        String body = new String(connection.bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("filename=\"speech.ogg\""));
        assertTrue(body.contains("Content-Type: audio/ogg"));
    }

    @Test public void streamingFinishFailureFallsBackToFullWavWithoutOfflineReencode() throws Exception {
        FakeEncoding encoding = new FakeEncoding(); encoding.failFinish = true;
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> encoding);
        device.readMode = FakeDevice.Mode.FILL;
        int token = prepareRecording(); invokeOpenAsync(token); awaitAudio(); awaitHttp();
        assertEquals(1, uploads.get()); assertEquals(960000, payloadPcm().length);
        assertEquals(960000, encoding.bytes); assertEquals(1, encoding.closes);
    }

    @Test public void streamingWriteFailureStillPreservesAllPcmInWav() throws Exception {
        FakeEncoding encoding = new FakeEncoding(); encoding.failWrite = true;
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> encoding);
        device.readMode = FakeDevice.Mode.FILL;
        int token = prepareRecording(); invokeOpenAsync(token); awaitAudio(); awaitHttp();
        assertEquals(1, uploads.get()); assertEquals(960000, payloadPcm().length);
        assertEquals(0, encoding.finishes); assertEquals(1, encoding.closes);
    }

    @Test public void cancelDuringCodecInitializationNeverStartsMicrophoneOrHttp() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        FakeEncoding encoding = new FakeEncoding();
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> {
            entered.countDown(); await(go); return encoding;
        });
        int token = prepareRecording(); invokeOpenAsync(token);
        assertTrue(entered.await(5, TimeUnit.SECONDS)); voice.cancel(); go.countDown();
        awaitAudio(); awaitHttp();
        assertEquals(0, device.starts.get()); assertEquals(0, uploads.get());
        assertEquals(1, encoding.closes);
    }

    @Test public void cancelDuringFinalizationDoesNotUploadOrInsertStaleResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        FakeEncoding encoding = new FakeEncoding();
        encoding.onFinish = () -> { entered.countDown(); await(go); };
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> encoding);
        device.readMode = FakeDevice.Mode.FILL;
        int token = prepareRecording(); invokeOpenAsync(token);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("Recorder closes before finalization", 1, device.releases.get());
        voice.cancel(); go.countDown(); awaitAudio(); awaitHttp();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0, uploads.get()); assertEquals("IDLE", state());
        assertEquals(1, encoding.closes);
    }

    @Test public void unavailableStreamingCodecUsesExactPcmWav() throws Exception {
        set(voice, "encodingFactory", (ShanVoiceInput.EncodingFactory) () -> {
            throw new UnsupportedOperationException("No Opus hardware");
        });
        device.readMode = FakeDevice.Mode.FILL;
        int token = prepareRecording(); invokeOpenAsync(token); awaitAudio(); awaitHttp();
        assertEquals(1, uploads.get()); assertEquals(960000, payloadPcm().length);
        assertEquals((byte)device.sample, payloadPcm()[959999]);
    }

    private final class FakeEncoding implements ShanVoiceInput.EncodingSession {
        int bytes, finishes, closes;
        boolean failWrite, failFinish;
        Runnable onFinish;
        public void write(byte[] b, int o, int n) {
            assertEquals("Encode incrementally while microphone open", 0, device.releases.get());
            if (failWrite) throw new IllegalStateException("Codec overload");
            bytes += n;
        }
        public byte[] finish() {
            assertEquals("Close recorder before any finalize/upload", 1, device.releases.get());
            finishes++;
            if (onFinish != null) onFinish.run();
            if (failFinish) throw new IllegalStateException("EOS timeout");
            byte[] ogg = new byte[32]; ogg[0]='O'; ogg[1]='g'; ogg[2]='g'; ogg[3]='S';
            return ogg; // fake codec seam only, not a playable/hardware codec assertion
        }
        public void close() { closes++; }
    }

    private void awaitHttp() throws Exception {
        ((ExecutorService)get(voice,"worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    private byte[] payloadPcm() {
        byte[] body = connection.bytes.toByteArray();
        byte[] marker = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        int start = -1;
        for (int i = 0; i < body.length - marker.length; i++) {
            if (body[i] == marker[0] && body[i+1] == marker[1] && body[i+2] == marker[2] && body[i+3] == marker[3]) { start = i + 4; break; }
        }
        assertTrue("WAV multipart payload", start >= 0);
        java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(body, start, 44).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(16000, header.getInt(24));
        assertEquals(1, header.getShort(22));
        assertEquals(16, header.getShort(34));
        int size = header.getInt(40);
        return java.util.Arrays.copyOfRange(body, start + 44, start + 44 + size);
    }
    private static final class MemoryConnection extends HttpURLConnection {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MemoryConnection() throws Exception { super(new URL("https://offline.invalid/transcribe")); }
        @Override public OutputStream getOutputStream() { return bytes; }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream("{\"text\":\"တႆး\"}".getBytes(StandardCharsets.UTF_8)); }
        @Override public void connect() { throw new AssertionError("Network prohibited"); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
    }

    private int prepareRecording() throws Exception {
        set(voice, "generation", 7); set(voice, "recording", true); set(voice, "openingMicrophone", true);
        set(voice, "state", enumState("RECORDING"));
        return 7;
    }
    private CountDownLatch holdWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        ((ExecutorService)get(voice,"audioWorker")).execute(() -> { entered.countDown(); await(go); });
        assertTrue(entered.await(5, TimeUnit.SECONDS)); return go;
    }
    private void invokeOpenAsync(int token) throws Exception {
        ((ExecutorService)get(voice,"audioWorker")).execute(() -> invokeOpen(token));
    }
    private void invokeOpen(int token) {
        try { Method m=ShanVoiceInput.class.getDeclaredMethod("openAndCapture", int.class); m.setAccessible(true); m.invoke(voice, token); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void awaitAudio() throws Exception { ((ExecutorService)get(voice,"audioWorker")).submit(() -> {}).get(5,TimeUnit.SECONDS); }
    private String state() throws Exception { return get(voice,"state").toString(); }
    private static Object enumState(String name) throws Exception {
        Class<?> c=Class.forName("com.sainaw.mm.board.ShanVoiceInput$State");
        for(Object v:c.getEnumConstants()) if(v.toString().equals(name)) return v;
        throw new AssertionError(name);
    }
    private static Object get(Object o,String n) throws Exception { Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    private static void set(Object o,String n,Object v) throws Exception { Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o,v); }
    private static void await(CountDownLatch latch) { try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }

    private static final class FakeDevice implements ShanVoiceInput.CaptureDevice {
        enum Mode { BLOCK_ONCE, FILL }
        final AtomicInteger starts=new AtomicInteger(), releases=new AtomicInteger();
        volatile Runnable onRead, onRelease;
        volatile Mode readMode=Mode.BLOCK_ONCE;
        volatile int sample=0x37, readBytes, readCalls;
        @Override public void start() { starts.incrementAndGet(); }
        @Override public int read(byte[] data,int offset,int count) {
            readCalls++; Runnable block=onRead; if(block!=null) block.run();
            if(readMode==Mode.BLOCK_ONCE){ for(int i=0;i<count;i++) data[offset+i]=(byte)(sample+i); readBytes+=count; return count; }
            for(int i=0;i<count;i++) data[offset+i]=(byte)sample;
            readBytes+=count; return count;
        }
        @Override public void stop() { }
        @Override public void release() { releases.incrementAndGet(); Runnable block=onRelease; if(block!=null) block.run(); }
    }
}
