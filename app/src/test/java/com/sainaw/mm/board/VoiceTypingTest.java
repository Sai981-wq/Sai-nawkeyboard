package com.sainaw.mm.board;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.widget.LinearLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.*;

/** Single existing voice key only; no secondary UI in any state. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34})
@LooperMode(LooperMode.Mode.PAUSED)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VoiceTypingTest {
    private ServiceController<SaiNawKeyboardService> controller;
    private SaiNawKeyboardService service;
    private LinearLayout parent;
    private ShanVoiceInput input;
    @Before public void setup() {
        controller = Robolectric.buildService(SaiNawKeyboardService.class).create();
        service = controller.get();
        parent = new LinearLayout(service);
        input = new ShanVoiceInput(service, parent, false);
    }
    @After public void cleanup() {
        input.destroy(); controller.destroy();
    }
    @Test public void noExtraViewOrButtonIsCreatedInAnyStateOrTheme() throws Exception {
        input.setAvailable(true);
        for (String name : new String[]{"IDLE", "STARTING", "RECORDING", "STOPPING", "UPLOADING"}) {
            state(name); input.setAvailable(true);
            assertEquals(name, 0, parent.getChildCount());
        }
        LinearLayout darkParent = new LinearLayout(service);
        ShanVoiceInput dark = new ShanVoiceInput(service, darkParent, true);
        dark.setAvailable(true);
        assertEquals(0, darkParent.getChildCount());
        dark.destroy();
    }
    @Test public void recordingToggleStopsImmediatelyAndRepeatedToggleDoesNotResubmit() throws Exception {
        input.setAvailable(true); state("RECORDING"); set("recording", true);
        int token = (Integer)get("generation");
        input.toggle();
        assertFalse((Boolean)get("recording"));
        assertEquals("STOPPING", get("state").toString());
        input.toggle(); input.toggle();
        assertEquals("STOPPING", get("state").toString());
        assertEquals(token, get("generation"));
    }
    @Test public void toggleDuringStartCancelsQueuedCaptureWithoutNewControl() throws Exception {
        input.setAvailable(true); state("STARTING");
        int token = (Integer)get("generation");
        input.toggle();
        assertEquals("IDLE", get("state").toString());
        assertEquals(token + 1, get("generation"));
        assertFalse(input.isCapturePending());
    }
    @Test public void uploadingToggleDoesNotOpenAnotherCaptureOrPermissionPrompt() throws Exception {
        input.setAvailable(true); state("UPLOADING");
        int token = (Integer)get("generation"); input.toggle();
        assertEquals("UPLOADING", get("state").toString());
        assertEquals(token, get("generation"));
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }
    @Test public void unavailableToggleDoesNothing() {
        input.toggle();
        assertEquals("IDLE", get("state").toString());
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }
    @Test public void missingConsentOpensNoticeButNeverStartsMicrophone() {
        input.setAvailable(true); input.toggle();
        assertEquals(ShanVoiceConsentActivity.class.getName(),
            Shadows.shadowOf(service).getNextStartedActivity().getComponent().getClassName());
        assertEquals("IDLE", get("state").toString());
        assertFalse((Boolean)get("recording"));
        assertEquals(0, get("generation"));
    }
    @Test public void storedConsentDoesNotBypassRuntimeMicrophonePermission() {
        service.getSharedPreferences(ShanVoiceConsentActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ShanVoiceConsentActivity.CONSENT, true).commit();
        Shadows.shadowOf(service.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        input.setAvailable(true); input.toggle();
        assertEquals(ShanVoiceConsentActivity.class.getName(),
            Shadows.shadowOf(service).getNextStartedActivity().getComponent().getClassName());
        assertEquals("IDLE", get("state").toString());
        assertFalse((Boolean)get("recording"));
    }
    @Test public void stateChangesAndErrorsNeverCreateToastOrPanel() throws Exception {
        org.robolectric.shadows.ShadowToast.reset();
        input.setAvailable(true);
        Method render = ShanVoiceInput.class.getDeclaredMethod("render", String.class); render.setAccessible(true);
        Method fail = ShanVoiceInput.class.getDeclaredMethod("fail", String.class); fail.setAccessible(true);
        for (String name : new String[]{"IDLE", "STARTING", "RECORDING", "STOPPING", "UPLOADING"}) {
            state(name); render.invoke(input, "status");
            assertEquals(0, parent.getChildCount());
        }
        fail.invoke(input, "error");
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertEquals(0, parent.getChildCount());
    }
    @Test public void cancelAndUnavailableInvalidateSession() throws Exception {
        input.setAvailable(true); state("STARTING");
        int token = (Integer)get("generation"); input.setAvailable(false);
        assertEquals(token + 1, get("generation"));
        assertEquals("IDLE", get("state").toString());
        assertFalse(input.isCapturePending());
    }
    @Test public void destroyIsIdempotentAndPreventsNewSession() {
        input.setAvailable(true); input.destroy(); int token = (Integer)get("generation");
        input.destroy(); input.toggle();
        assertEquals(token, get("generation"));
        assertTrue((Boolean)get("destroyed"));
        assertNull(Shadows.shadowOf(service).getNextStartedActivity());
    }
    private Object get(String name) {
        try { Field f=ShanVoiceInput.class.getDeclaredField(name); f.setAccessible(true); return f.get(input); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private void set(String name,Object value) throws Exception {
        Field f=ShanVoiceInput.class.getDeclaredField(name); f.setAccessible(true); f.set(input,value);
    }
    private void state(String name) throws Exception {
        for(Object v:get("state").getClass().getEnumConstants()) if(v.toString().equals(name)) { set("state",v); return; }
        throw new AssertionError(name);
    }
}
