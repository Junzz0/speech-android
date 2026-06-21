package audio.soniqo.speech.demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Exercises the framework `SpeechRecognizer` API without going through a
 * keyboard. Mirrors what Gboard / a third-party app would do, but uses
 * `createSpeechRecognizer(ctx)` (no `ComponentName`) which routes the
 * request to whatever is configured as the system default voice
 * recognition service.
 *
 * If our `SpeechRecognitionService` is set as the default
 * (`settings put secure voice_recognition_service
 *  audio.soniqo.speech.demo/audio.soniqo.speech.service.SpeechRecognitionService`),
 * tapping "start" here ends up in our `onStartListening` and audio
 * flows through our pipeline. The log view shows every callback the
 * framework delivers — useful for diagnosing the binder round-trip
 * without needing logcat.
 */
class SpeechRecognizerTestActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "no recognition service available on this device"
            return
        }
        statusView.text = "ready — tap start"
        startBtn.isEnabled = true
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(48, 96, 48, 48)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop + sb.top,
                v.paddingRight,
                v.paddingBottom + sb.bottom,
            )
            insets
        }

        root.addView(TextView(this).apply {
            text = "SpeechRecognizer test"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Calls SpeechRecognizer.createSpeechRecognizer(ctx) with " +
                "no component, exercising the system default. " +
                "Set our service as default first via " +
                "Settings → Voice input, or:\n\n" +
                "adb shell settings put secure voice_recognition_service " +
                "audio.soniqo.speech.demo/audio.soniqo.speech.service.SpeechRecognitionService"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, 24)
        })

        statusView = TextView(this).apply {
            text = "initialising..."
            textSize = 14f
            setTextColor(Color.parseColor("#4FC3F7"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusView)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        startBtn = button("Start").apply {
            isEnabled = false
            setOnClickListener { handleStart() }
        }
        stopBtn = button("Stop").apply {
            isEnabled = false
            setOnClickListener { handleStop() }
        }
        buttons.addView(startBtn)
        buttons.addView(stopBtn)
        root.addView(buttons)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#CCCCCC"))
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#181818"))
            setTextIsSelectable(true)
        }
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)
    }

    private fun button(label: String) = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.parseColor("#4FC3F7"))
        setPadding(48, 16, 48, 16)
        gravity = Gravity.CENTER
    }

    private fun handleStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        appendLog("→ createSpeechRecognizer(ctx)  // no component")
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        appendLog("→ startListening(...)")
        statusView.text = "listening..."
        recognizer?.startListening(intent)
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
    }

    private fun handleStop() {
        appendLog("→ stopListening()")
        recognizer?.stopListening()
        stopBtn.isEnabled = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) =
            runOnUiThread {
                appendLog("← onReadyForSpeech")
                statusView.text = "speak now"
            }

        override fun onBeginningOfSpeech() =
            runOnUiThread {
                appendLog("← onBeginningOfSpeech")
                statusView.text = "hearing speech"
            }

        override fun onRmsChanged(rmsdB: Float) = Unit  // too chatty for the log

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() =
            runOnUiThread {
                appendLog("← onEndOfSpeech")
                statusView.text = "transcribing..."
            }

        override fun onError(error: Int) =
            runOnUiThread {
                appendLog("← onError(${errorName(error)})")
                statusView.text = "error: ${errorName(error)}"
                resetButtons()
            }

        override fun onResults(results: Bundle?) =
            runOnUiThread {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                appendLog("← onResults: ${texts?.firstOrNull() ?: "(empty)"}")
                statusView.text = "done — ${texts?.firstOrNull() ?: ""}"
                resetButtons()
            }

        override fun onPartialResults(partialResults: Bundle?) =
            runOnUiThread {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrEmpty()) {
                    appendLog("← onPartialResults: $text")
                }
            }

        override fun onEvent(eventType: Int, params: Bundle?) =
            runOnUiThread {
                appendLog("← onEvent(type=$eventType)")
            }
    }

    private fun resetButtons() {
        startBtn.isEnabled = true
        stopBtn.isEnabled = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            handleStart()
        } else {
            statusView.text = "RECORD_AUDIO denied"
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun appendLog(line: String) {
        log.appendLine(line)
        logView.text = log.toString()
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        else -> "code=$code"
    }
}
