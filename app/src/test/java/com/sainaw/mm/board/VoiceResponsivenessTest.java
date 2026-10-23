package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.widget.LinearLayout;
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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** No microphone, sockets, or real uploads: request I/O is an in-memory fake. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class VoiceResponsivenessTest {
    private ServiceController<SaiNawKeyboardService> controller;
    private SaiNawKeyboardService service;
    private ShanVoiceInput voice;
    private MemoryConnection connection;
    private int opens;
    @Before public void setup() throws Exception {
        controller = Robolectric.buildService(SaiNawKeyboardService.class).create();
        service = controller.get();
        connection = new MemoryConnection();
        voice = new ShanVoiceInput(service, new LinearLayout(service), false, () -> { opens++; return connection; });
        voice.setAvailable(true);
    }
    @After public void cleanup() {
        if (voice != null) voice.destroy();
        if (controller != null) controller.destroy();
    }
    @Test public void explicitStopReturnsImmediatelyWithoutWaitingForMicrophoneRelease() throws Exception {
        state("RECORDING"); set(voice, "recording", true); set(voice, "openingMicrophone", true);
        long began = System.nanoTime();
        voice.toggle();
        assertTrue("Physical stop is requested without blocking the caller", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began) < 500);
        assertEquals("STOPPING", get(voice, "state").toString());
        assertEquals(false, get(voice, "recording"));
        assertEquals(0, opens);
    }
    @Test public void stopControlLockNeverBlocksTheUiCaller() throws Exception {
        state("RECORDING"); set(voice, "recording", true); set(voice, "openingMicrophone", true);
        Object lock = get(voice, "recorderLock");
        synchronized (lock) {
            voice.toggle();
            assertEquals("STOPPING", get(voice, "state").toString());
        }
        assertEquals(0, opens);
    }
    @Test public void metadataDistinguishesStopAndTranscribingWithoutToasts() throws Exception {
        org.robolectric.shadows.ShadowToast.reset();
        state("RECORDING"); assertEquals("Voice Typing", voice.accessibilityLabel());
        state("UPLOADING"); assertEquals("Voice Typing", voice.accessibilityLabel());
        state("IDLE"); assertEquals("Voice Typing", voice.accessibilityLabel());
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }
    @Test public void wavRequestIsLosslessSingleAttemptAndRunsOffMainThread() throws Exception {
        consent(true);
        byte[] pcm = new byte[3200]; pcm[0] = 1; pcm[3199] = 1;
        byte[] wav = ShanWav.encode(ShanAudioProcessor.prepare(pcm, 16000), 16000);
        invokeUpload(wav);
        awaitWorker();
        assertEquals(1, opens);
        assertNotSame(Looper.getMainLooper().getThread(), connection.ioThread);
        assertEquals("POST", connection.getRequestMethod());
        assertEquals(20000, connection.getConnectTimeout());
        assertEquals(120000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects());
        String type = connection.getRequestProperty("Content-Type");
        assertTrue(type.startsWith("multipart/form-data; boundary="));
        String boundary = type.substring(type.indexOf("boundary=") + 9);
        byte[] body = connection.bytes.toByteArray();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
        byte[] prefix = head.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(prefix, java.util.Arrays.copyOf(body, prefix.length));
        assertArrayEquals(wav, java.util.Arrays.copyOfRange(body, prefix.length, prefix.length + wav.length));
        assertEquals("\r\n--" + boundary + "--\r\n", new String(body, prefix.length + wav.length,
                body.length - prefix.length - wav.length, StandardCharsets.UTF_8));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("IDLE", get(voice, "state").toString()); // No result panel; commit failure returns idle.
        assertTrue(connection.disconnected);
    }
    @Test public void consentRevocationPreventsRequestFactoryFromBeingOpened() throws Exception {
        consent(false);
        invokeUpload(new byte[3200]);
        awaitWorker();
        assertEquals(0, opens);
        assertEquals("IDLE", get(voice, "state").toString());
    }
    @Test public void serverFailureDoesNotRetryAndReturnsToIdle() throws Exception {
        consent(true); connection.code = 500;
        invokeUpload(new byte[3200]); awaitWorker();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, opens);
        assertEquals("IDLE", get(voice, "state").toString());
        assertTrue(connection.disconnected);
    }
    private void consent(boolean value) {
        service.getSharedPreferences(ShanVoiceConsentActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ShanVoiceConsentActivity.CONSENT, value).commit();
    }
    private void invokeUpload(byte[] data) throws Exception {
        Method m = ShanVoiceInput.class.getDeclaredMethod("upload", byte[].class, int.class);
        m.setAccessible(true); m.invoke(voice, data, (Integer) get(voice, "generation"));
    }
    private void awaitWorker() throws Exception {
        ((ExecutorService) get(voice, "worker")).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    private void state(String name) throws Exception {
        Object current = get(voice, "state");
        for (Object option : current.getClass().getEnumConstants()) if (option.toString().equals(name)) { set(voice, "state", option); return; }
        throw new AssertionError(name);
    }
    private static Object get(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    private static void invoke(Object object, String name) throws Exception {
        Method m = object.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(object);
    }
    private static final class MemoryConnection extends HttpURLConnection {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile Thread ioThread;
        volatile boolean disconnected;
        int code = 200;
        MemoryConnection() throws Exception { super(new URL("https://offline.invalid/transcribe")); }
        @Override public OutputStream getOutputStream() { ioThread = Thread.currentThread(); return bytes; }
        @Override public int getResponseCode() { return code; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream("{\"text\":\"တႆး\"}".getBytes(StandardCharsets.UTF_8));
        }
        @Override public void connect() { throw new AssertionError("Real connect prohibited"); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
    }
}
