package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkg = ""
    private var burmesePkg = ""
    private var englishPkg = ""

    private lateinit var prefs: SharedPreferences
    private val PREF_NAME = "TTS_CONFIG"

    @Volatile private var isStopped = false
    private var currentReadFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val defEngine = getDefaultEngineFallback()

            shanPkg = getPkg("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng"), defEngine)
            initTTS(shanPkg, Locale("shn", "MM")) { shanEngine = it }

            burmesePkg = getPkg("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts"), defEngine)
            initTTS(burmesePkg, Locale("my", "MM")) { burmeseEngine = it }

            englishPkg = getPkg("pref_english_pkg", listOf("com.google.android.tts", "es.codefactory.eloquencetts"), defEngine)
            initTTS(englishPkg, Locale.US) { englishEngine = it }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStop() {
        isStopped = true
        // Close FD immediately to break the Reader Loop
        try { currentReadFd?.close() } catch (e: Exception) {}
        currentReadFd = null
        
        stopSafe(shanEngine)
        stopSafe(burmeseEngine)
        stopSafe(englishEngine)
    }

    private fun stopSafe(engine: TextToSpeech?) {
        try { engine?.stop() } catch (e: Exception) {}
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        isStopped = false
        val text = request.charSequenceText.toString()

        val chunks = TTSUtils.splitHelper(text)
        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        val firstLang = chunks[0].lang
        val targetPkg = when (firstLang) {
            "SHAN" -> shanPkg
            "MYANMAR" -> burmesePkg
            else -> englishPkg
        }
        
        var hz = prefs.getInt("RATE_$targetPkg", 0)
        if (hz == 0) hz = if (targetPkg.contains("google")) 24000 else 22050
        
        val engineMult = prefs.getFloat("MULT_$targetPkg", 1.0f)

        var rawRate = request.speechRate / 100.0f
        var safeRate = if (rawRate < 0.35f) 0.35f else rawRate
        if (safeRate > 3.0f) safeRate = 3.0f

        val finalRate = safeRate * engineMult
        val finalPitch = (request.pitch / 100.0f).coerceIn(0.7f, 1.4f)

        try {
            callback.start(hz, AudioFormat.ENCODING_PCM_16BIT, 1)

            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            val uuid = UUID.randomUUID().toString()
            currentReadFd = readFd

            val writerThread = Thread {
                // Priority မြှင့်ထားခြင်းဖြင့် Pipe မပြည့်ခင် အမြန်ရေးနိုင်အောင် ကူညီပါမယ်
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                
                try {
                    for (chunk in chunks) {
                        if (isStopped) break
                        if (chunk.text.isBlank()) continue

                        val engine = when (chunk.lang) {
                            "SHAN" -> shanEngine
                            "MYANMAR" -> burmeseEngine
                            else -> englishEngine
                        } ?: englishEngine

                        if (engine == null) continue

                        val currentPkg = when (chunk.lang) {
                            "SHAN" -> shanPkg
                            "MYANMAR" -> burmesePkg
                            else -> englishPkg
                        }
                        
                        val chunkMult = prefs.getFloat("MULT_$currentPkg", 1.0f)
                        val chunkRate = (safeRate * chunkMult).coerceIn(0.2f, 3.5f)

                        engine.setSpeechRate(chunkRate)
                        engine.setPitch(finalPitch)

                        val latch = CountDownLatch(1)
                        
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { latch.countDown() }
                            override fun onError(id: String?) { latch.countDown() }
                            override fun onError(id: String?, code: Int) { latch.countDown() }
                        })

                        // Engine writes to Pipe (Writer Side)
                        val res = engine.synthesizeToFile(chunk.text, params, writeFd, uuid)
                        
                        if (res == TextToSpeech.SUCCESS) {
                            // Wait for this chunk to finish before sending next
                            // Timeout increased slightly to prevent cutting off long sentences
                            latch.await(5000, TimeUnit.MILLISECONDS)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // Critical: Close Write FD so Reader knows stream is done
                    try { writeFd.close() } catch (e: Exception) {}
                }
            }
            writerThread.start()

            // ★ BUFFER FIX: Increased to 16KB (Standard Android Audio Chunk is usually 4KB-16KB)
            // 4096 is too small and might cause Pipe Overflow on fast engines
            val buffer = ByteArray(16384) 
            
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                    while (!isStopped) {
                        // Read from Pipe (Reader Side)
                        val bytesRead = try { fis.read(buffer) } catch (e: IOException) { -1 }
                        
                        // -1 means Writer closed the pipe (Done)
                        if (bytesRead == -1) break 
                        
                        if (bytesRead > 0) {
                            val max = callback.maxBufferSize
                            var offset = 0
                            while (offset < bytesRead) {
                                if (isStopped) break
                                val chunkLen = Math.min(bytesRead - offset, max)
                                
                                // Send to System Audio
                                val ret = callback.audioAvailable(buffer, offset, chunkLen)
                                if (ret == TextToSpeech.ERROR) {
                                    isStopped = true
                                    break
                                }
                                offset += chunkLen
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (writerThread.isAlive) {
                   stopSafe(shanEngine)
                   stopSafe(burmeseEngine)
                   stopSafe(englishEngine)
                }
                if (!isStopped) callback.done()
            }

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun getDefaultEngineFallback(): String {
        return try {
            val tts = TextToSpeech(this, null)
            val p = tts.defaultEngine; tts.shutdown(); p ?: "com.google.android.tts"
        } catch (e: Exception) { "com.google.android.tts" }
    }
    private fun getPkg(key: String, list: List<String>, def: String): String {
        val p = prefs.getString(key, "")
        if (!p.isNullOrEmpty() && isInstalled(p)) return p
        for (i in list) if (isInstalled(i)) return i
        return def
    }
    private fun isInstalled(pkg: String): Boolean {
        return try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    }
    private fun initTTS(pkg: String, loc: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        try {
            var t: TextToSpeech? = null
            t = TextToSpeech(this, { if (it == TextToSpeech.SUCCESS) onReady(t!!) }, pkg)
        } catch (e: Exception) {}
    }
    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onDestroy() {
        super.onDestroy()
        onStop()
        shanEngine?.shutdown(); burmeseEngine?.shutdown(); englishEngine?.shutdown()
    }
}

