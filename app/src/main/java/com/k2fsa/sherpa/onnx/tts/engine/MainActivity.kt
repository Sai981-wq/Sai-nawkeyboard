package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

const val TAG = "sherpa-onnx-tts-engine"

class MainActivity : ComponentActivity() {

    private lateinit var track: AudioTrack
    private var stopped: Boolean = false
    private var samplesChannel = Channel<FloatArray>()
    private lateinit var preferenceHelper: PreferenceHelper
    private lateinit var langDB: LangDB

    override fun onPause() {
        super.onPause()
        samplesChannel.close()
    }

    override fun onResume() {
        if (TtsEngine.lang != "") {
            val db = LangDB.getInstance(this)
            val allLanguages = db.allInstalledLanguages
            if (allLanguages.isNotEmpty()) {
                val currentLanguage = allLanguages.firstOrNull { it.lang == TtsEngine.lang }
                if (currentLanguage != null) {
                    TtsEngine.speed.value = currentLanguage.speed
                }
            }
        }
        super.onResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceHelper = PreferenceHelper(this)
        langDB = LangDB.getInstance(this)

        val allLangs = langDB.allInstalledLanguages
        var hasShan = false
        for (language in allLangs) {
            if (language.lang == "shn") {
                hasShan = true
                break
            }
        }

        if (!hasShan) {
            langDB.addLanguage(
                "Shan (Cherry TTS)",
                "shn",
                "MM",
                0,
                1.0f,
                1.0f,
                "vits"
            )
            preferenceHelper.setCurrentLanguage("shn")
            recreate()
        }

        if (!preferenceHelper.getCurrentLanguage().equals("")) {
            TtsEngine.createTts(this, preferenceHelper.getCurrentLanguage()!!)
            initAudioTrack()
            setupDisplay(langDB, preferenceHelper)
            ThemeUtil.setStatusBarAppearance(this)
        }
    }

    private fun restart() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
    private fun setupDisplay(
        langDB: LangDB,
        preferenceHelper: PreferenceHelper
    ) {
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("SherpaTTS") })
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                intent = Intent()
                                intent.setAction("com.android.settings.TTS_SETTINGS")
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                this.startActivity(intent)
                                finish()
                            }
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "TTS Settings")
                        }
                    }) {
                    Box(modifier = Modifier.padding(it)) {
                        var sampleText by remember { mutableStateOf(getSampleText(TtsEngine.lang ?: "")) }
                        val numLanguages = langDB.allInstalledLanguages.size
                        val allLanguages = langDB.allInstalledLanguages
                        var currentLanguageIndex = allLanguages.indexOfFirst { it.lang == preferenceHelper.getCurrentLanguage()!! }
                        if (currentLanguageIndex == -1) currentLanguageIndex = 0
                        val numSpeakers = if (TtsEngine.tts != null) TtsEngine.tts!!.numSpeakers() else 1

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            item {
                                Text(
                                    getString(R.string.speed) + " " + String.format(
                                        "%.1f",
                                        TtsEngine.speed.value
                                    )
                                )
                            }
                            item {
                                Slider(
                                    value = TtsEngine.speed.value,
                                    onValueChange = {
                                        TtsEngine.speed.value = it
                                    },
                                    onValueChangeFinished = {
                                        langDB.updateLang(
                                            TtsEngine.lang,
                                            TtsEngine.speakerId.value,
                                            TtsEngine.speed.value,
                                            TtsEngine.volume.value
                                        )
                                    },
                                    valueRange = 0.2F..3.0F,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = colorResource(R.color.primaryDark),
                                        activeTrackColor = colorResource(R.color.primaryDark)
                                    )
                                )
                            }

                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    var applySystemSpeed by remember {
                                        mutableStateOf(
                                            preferenceHelper.applySystemSpeed()
                                        )
                                    }
                                    Checkbox(
                                        checked = applySystemSpeed,
                                        onCheckedChange = { isChecked ->
                                            preferenceHelper.setApplySystemSpeed(isChecked)
                                            applySystemSpeed = isChecked
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = colorResource(R.color.primaryDark)
                                        )
                                    )
                                    Text(getString(R.string.apply_system_speed))
                                }
                            }

                            item { Spacer(modifier = Modifier.height(10.dp)) }

                            item {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    var expanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it }
                                    ) {
                                        val keyboardController = LocalSoftwareKeyboardController.current
                                        var displayText = ""
                                        if (allLanguages.isNotEmpty() && currentLanguageIndex < allLanguages.size) {
                                             displayText = allLanguages[currentLanguageIndex].lang
                                            if (allLanguages[currentLanguageIndex].name.isNotEmpty()) displayText =
                                                "$displayText (${allLanguages[currentLanguageIndex].name})"
                                        }

                                        OutlinedTextField(
                                            value = displayText,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(getString(R.string.language_id)) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .menuAnchor()
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        expanded = true
                                                        keyboardController?.hide()
                                                    }
                                                },
                                            trailingIcon = {
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = "Dropdown"
                                                )
                                            }
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            val langList = (0 until numLanguages).toList()
                                            langList.forEach { langId ->
                                                var dropdownText = allLanguages[langId].lang
                                                if (allLanguages[langId].name.isNotEmpty()) dropdownText =
                                                    "$dropdownText (${allLanguages[langId].name})"
                                                DropdownMenuItem(
                                                    text = { Text(dropdownText) },
                                                    onClick = {
                                                        preferenceHelper.setCurrentLanguage(
                                                            allLanguages[langId].lang
                                                        )
                                                        expanded = false
                                                        restart()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                OutlinedTextField(
                                    value = sampleText,
                                    onValueChange = { sampleText = it },
                                    label = { Text(getString(R.string.input)) },
                                    maxLines = 10,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                        .wrapContentHeight(),
                                    singleLine = false
                                )
                            }


                            item {
                                Text(
                                    getString(R.string.volume) + " " + String.format(
                                        "%.1f",
                                        TtsEngine.volume.value
                                    )
                                )
                            }

                            item {
                                Slider(
                                    value = TtsEngine.volume.value,
                                    onValueChange = {
                                        TtsEngine.volume.value = it
                                    },
                                    onValueChangeFinished = {
                                        langDB.updateLang(
                                            TtsEngine.lang,
                                            TtsEngine.speakerId.value,
                                            TtsEngine.speed.value,
                                            TtsEngine.volume.value
                                        )
                                    },
                                    valueRange = 0.2F..5.0F,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = colorResource(R.color.primaryDark),
                                        activeTrackColor = colorResource(R.color.primaryDark)
                                    )
                                )
                            }

                            item {
                                Row {
                                    Button(
                                        enabled = true,
                                        modifier = Modifier.padding(5.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colorResource(R.color.primaryDark),
                                            contentColor = colorResource(R.color.white)
                                        ),
                                        onClick = {
                                            if (sampleText.isBlank()) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    getString(R.string.input),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                stopped = false
                                                track.pause()
                                                track.flush()
                                                track.play()
                                                samplesChannel = Channel<FloatArray>()

                                                CoroutineScope(Dispatchers.IO).launch {
                                                    for (samples in samplesChannel) {
                                                        for (i in samples.indices) {
                                                            samples[i] *= TtsEngine.volume.value
                                                        }
                                                        track.write(
                                                            samples,
                                                            0,
                                                            samples.size,
                                                            AudioTrack.WRITE_BLOCKING
                                                        )
                                                    }
                                                }

                                                CoroutineScope(Dispatchers.Default).launch {
                                                    TtsEngine.tts!!.generateWithCallback(
                                                        text = sampleText,
                                                        sid = TtsEngine.speakerId.value,
                                                        speed = TtsEngine.speed.value,
                                                        callback = ::callback,
                                                    )
                                                }.start()
                                            }
                                        }) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_play_24dp),
                                            contentDescription = stringResource(id = R.string.play)
                                        )
                                    }

                                    Button(
                                        modifier = Modifier.padding(5.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colorResource(R.color.primaryDark),
                                            contentColor = colorResource(R.color.white)
                                        ),
                                        onClick = {
                                            stopped = true
                                            track.pause()
                                            track.flush()
                                        }) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_stop_24dp),
                                            contentDescription = stringResource(id = R.string.stop)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (this::track.isInitialized) track.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun callback(samples: FloatArray): Int {
        if (!stopped) {
            val samplesCopy = samples.copyOf()
            CoroutineScope(Dispatchers.IO).launch {
                if (!samplesChannel.isClosedForSend) samplesChannel.send(samplesCopy)
            }
            return 1
        } else {
            track.stop()
            return 0
        }
    }

    private fun initAudioTrack() {
        val sampleRate = if (TtsEngine.tts != null) TtsEngine.tts!!.sampleRate() else 16000
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val attr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        track = AudioTrack(
            attr, format, bufLength, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track.play()
    }
}

