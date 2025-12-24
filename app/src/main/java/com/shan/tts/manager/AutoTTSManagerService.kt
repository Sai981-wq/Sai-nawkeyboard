package com.shan.tts.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class AutoTTSManagerService : TextToSpeechService() {

    private var shanEngine: TextToSpeech? = null
    private var burmeseEngine: TextToSpeech? = null
    private var englishEngine: TextToSpeech? = null

    private var shanPkgName: String = ""
    private var burmesePkgName: String = ""
    private var englishPkgName: String = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var configPrefs: SharedPreferences

    private val mIsStopped = AtomicBoolean(false)
    private val synthesisExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private val FIXED_OUTPUT_HZ = 24000

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences("TTS_SETTINGS", Context.MODE_PRIVATE)
            configPrefs = getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)

            val defaultEngine = try {
                val tts = TextToSpeech(this, null)
                val pkg = tts.defaultEngine
                tts.shutdown()
                pkg ?: "com.google.android.tts"
            } catch (e: Exception) { "com.google.android.tts" }

            shanPkgName = resolveEngine("pref_shan_pkg", listOf("com.shan.tts", "com.espeak.ng", "org.himnario.espeak"), defaultEngine)
            initEngine(shanPkgName, Locale("shn", "MM")) { shanEngine = it }

            burmesePkgName = resolveEngine("pref_burmese_pkg", listOf("org.saomaicenter.myanmartts", "com.google.android.tts", "com.samsung.SMT"), defaultEngine)
            initEngine(burmesePkgName, Locale("my", "MM")) { burmeseEngine = it }

            englishPkgName = resolveEngine("pref_english_pkg", listOf("com.google.android.tts", "com.samsung.SMT", "es.codefactory.eloquencetts"), defaultEngine)
            initEngine(englishPkgName, Locale.US) { englishEngine = it }

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun resolveEngine(prefKey: String, priorityList: List<String>, fallback: String): String {
        val userPref = prefs.getString(prefKey, "")
        if (!userPref.isNullOrEmpty() && isPackageInstalled(userPref)) return userPref
        for (pkg in priorityList) if (isPackageInstalled(pkg)) return pkg
        return fallback
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) { false }
    }

    private fun initEngine(pkg: String, locale: Locale, onReady: (TextToSpeech) -> Unit) {
        if (pkg.isEmpty()) return
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(this, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try { tts?.language = locale } catch (e: Exception) {}
                    onReady(tts!!)
                }
            }, pkg)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStop() {
        mIsStopped.set(true)
        AudioProcessor.flush()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        mIsStopped.set(false)
        val text = request.charSequenceText.toString()
        val chunks = splitTextToChunks(text)

        callback.start(FIXED_OUTPUT_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)

        val pendingTasks = ArrayList<Future<ProcessingResult>>()

        for (chunk in chunks) {
            val task = Callable {
                synthesizeToRam(chunk, request)
            }
            pendingTasks.add(synthesisExecutor.submit(task))
        }

        try {
            for (future in pendingTasks) {
                if (mIsStopped.get()) break
                // Timeout 4 စက္ကန့်ထားပေးပါ (Engine Stuck ဖြစ်ရင် ကျော်သွားအောင်)
                val result = try { future.get() } catch (e: Exception) { null }
                
                if (result != null && result.audioData.isNotEmpty()) {
                    processAndPlay(result, callback)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        callback.done()
    }

    private fun synthesizeToRam(chunk: Chunk, request: SynthesisRequest): ProcessingResult {
        if (mIsStopped.get()) return ProcessingResult(ByteArray(0), 22050)

        val langCode = when (chunk.lang) {
            "SHAN" -> "shn"
            "MYANMAR" -> "my"
            else -> "eng"
        }
        val engineData = getEngineDataForLang(langCode)
        val targetEngine = engineData.engine ?: englishEngine ?: return ProcessingResult(ByteArray(0), 22050)

        // Rate & Pitch Calculation
        val sysRate = request.speechRate / 100f
        val sysPitch = request.pitch / 100f

        // FIX: Thread Safety (မဖြစ်မနေ လိုအပ်သော အပိုင်း)
        // Thread တွေက Engine ကို Setting ချိန်တဲ့အခါ တစ်ယောက်ပြီးမှ တစ်ယောက်လုပ်ရမည်
        synchronized(targetEngine) {
            try {
                targetEngine.setSpeechRate(sysRate)
                targetEngine.setPitch(sysPitch)
            } catch (e: Exception) {}
        }

        val params = Bundle()
        val vol = if (engineData.pkgName.lowercase().contains("espeak")) 1.0f else 0.8f
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol)

        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]

            // Engine Call ကိုလည်း Thread Safe ဖြစ်အောင် ထိန်းထားပေးရင် ပိုကောင်းပါတယ်
            // ဒါမှ Engine State မငြိမ်ခင် နောက် Thread က ဝင်မရှုပ်မှာပါ
            synchronized(targetEngine) {
                targetEngine.synthesizeToFile(chunk.text, params, writeFd, UUID.randomUUID().toString())
            }
            
            writeFd.close()

            ParcelFileDescriptor.AutoCloseInputStream(readFd).use { fis ->
                val byteStream = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (mIsStopped.get()) break
                    byteStream.write(buffer, 0, bytesRead)
                }
                
                val engineHz = configPrefs.getInt("RATE_${engineData.pkgName}", 22050)
                return ProcessingResult(byteStream.toByteArray(), engineHz)
            }
        } catch (e: Exception) {
            return ProcessingResult(ByteArray(0), 22050)
        }
    }

    private fun processAndPlay(result: ProcessingResult, callback: SynthesisCallback) {
        val inputBytes = result.audioData
        val engineHz = result.engineHz

        // Hz မတူရင် Sonic သုံး၊ တူရင်လည်း Pass through
        if (engineHz != FIXED_OUTPUT_HZ) {
            AudioProcessor.initSonic(engineHz, 1)
        } else {
            AudioProcessor.initSonic(FIXED_OUTPUT_HZ, 1)
        }
        
        // Thread Safety အတွက် Buffer ကို Local မှာပဲ ကြေညာပါ (Jieshuo အတွက် အရေးကြီးသည်)
        val sonicInBuffer = ByteBuffer.allocateDirect(8192)
        val sonicOutBuffer = ByteBuffer.allocateDirect(8192)
        val sonicOutArray = ByteArray(8192)

        var offset = 0
        val totalLen = inputBytes.size

        while (offset < totalLen && !mIsStopped.get()) {
            val lengthToProcess = min(totalLen - offset, 4096)

            sonicInBuffer.clear()
            sonicInBuffer.put(inputBytes, offset, lengthToProcess)
            sonicInBuffer.flip()

            sonicOutBuffer.clear()
            val processedBytes = AudioProcessor.processAudio(
                sonicInBuffer,
                lengthToProcess,
                sonicOutBuffer,
                sonicOutBuffer.capacity()
            )

            if (processedBytes > 0) {
                sonicOutBuffer.get(sonicOutArray, 0, processedBytes)
                val maxBufferSize = callback.maxBufferSize
                var writeOffset = 0
                while (writeOffset < processedBytes && !mIsStopped.get()) {
                    val chunk = min(processedBytes - writeOffset, maxBufferSize)
                    callback.audioAvailable(sonicOutArray, writeOffset, chunk)
                    writeOffset += chunk
                }
            }
            offset += lengthToProcess
        }
    }

    private fun splitTextToChunks(text: String): List<Chunk> {
        val list = ArrayList<Chunk>()
        if (text.isBlank()) return list
        val regex = Regex("([\\u1000-\\u109F]+)|([^\\u1000-\\u109F]+)")
        val matches = regex.findAll(text)
        for (match in matches) {
            val str = match.value.trim()
            if (str.isNotEmpty()) {
                val lang = if (str.contains(Regex("[\\u1000-\\u109F]"))) "MYANMAR" else "ENG"
                val finalLang = if (lang == "MYANMAR" && str.contains(Regex("[\\u1060-\\u108F]"))) "SHAN" else lang
                list.add(Chunk(str, finalLang))
            }
        }
        return list
    }

    data class ProcessingResult(val audioData: ByteArray, val engineHz: Int)
    data class Chunk(val text: String, val lang: String)
    data class EngineData(val engine: TextToSpeech?, val pkgName: String)

    private fun getEngineDataForLang(lang: String): EngineData {
        return when (lang) {
            "SHAN", "shn" -> EngineData(if (shanEngine != null) shanEngine else englishEngine, shanPkgName)
            "MYANMAR", "my", "mya", "bur" -> EngineData(if (burmeseEngine != null) burmeseEngine else englishEngine, burmesePkgName)
            else -> EngineData(englishEngine, englishPkgName)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mIsStopped.set(true)
        synthesisExecutor.shutdownNow()
        shanEngine?.shutdown()
        burmeseEngine?.shutdown()
        englishEngine?.shutdown()
        AudioProcessor.stop()
    }

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
}

