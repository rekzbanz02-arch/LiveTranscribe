package com.rekzbanz.livetranscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.text.style.ForegroundColorSpan
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var sourceSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var scroll: ScrollView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // Empty string = auto-detect (best for mixed English / Tagalog / Cebuano speech).
    private val languageCodes = arrayOf("", "fil", "en")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) proceed()
            else toast(R.string.toast_need_mic)
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                launchService(sourceSpinner.selectedItemPosition, result.resultCode, data)
            } else {
                toast(R.string.toast_need_capture)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        languageSpinner = findViewById(R.id.languageSpinner)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        scroll = findViewById(R.id.scroll)

        sourceSpinner.adapter = spinnerAdapter(R.array.source_options)
        languageSpinner.adapter = spinnerAdapter(R.array.language_options)

        apiKeyInput.setText(prefs.getString("api_key", ""))
        sourceSpinner.setSelection(prefs.getInt("source", 0))
        languageSpinner.setSelection(prefs.getInt("language", 0))

        // Lets you see what was actually pasted (a wrong or half-copied key is the usual cause of HTTP 400).
        findViewById<CheckBox>(R.id.showKeyCheck).setOnCheckedChangeListener { _, checked ->
            val cursor = apiKeyInput.selectionEnd
            apiKeyInput.transformationMethod =
                if (checked) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            apiKeyInput.setSelection(cursor.coerceIn(0, apiKeyInput.text.length))
        }

        startButton.setOnClickListener { onStartStop() }
        findViewById<Button>(R.id.copyButton).setOnClickListener { copyTranscript() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { shareTranscript() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { TranscriptBus.clear() }

        CrashReporter.take(this)?.let { showCrashReport(it) }
    }

    private fun showCrashReport(report: String) {
        AlertDialog.Builder(this)
            .setTitle("The app crashed last time")
            .setMessage(report.take(3000))
            .setPositiveButton(R.string.copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                toast(R.string.toast_copied)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        TranscriptBus.listener = { render() }
        render()
    }

    override fun onStop() {
        TranscriptBus.listener = null
        super.onStop()
    }

    // ------------------------------------------------------------------ UI

    private fun render() {
        val running = TranscriptBus.running
        startButton.setText(if (running) R.string.stop else R.string.start)
        apiKeyInput.isEnabled = !running
        sourceSpinner.isEnabled = !running
        languageSpinner.isEnabled = !running
        statusText.text = TranscriptBus.status.ifEmpty { getString(R.string.status_idle) }

        val (done, live) = TranscriptBus.texts()
        val sb = SpannableStringBuilder(done)
        if (live.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            val start = sb.length
            sb.append(live)
            sb.setSpan(
                ForegroundColorSpan(Color.GRAY), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        transcriptText.text = sb
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun spinnerAdapter(arrayRes: Int): ArrayAdapter<CharSequence> {
        val adapter = ArrayAdapter.createFromResource(
            this, arrayRes, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    // --------------------------------------------------------------- start/stop

    private fun onStartStop() {
        if (TranscriptBus.running) {
            startService(
                Intent(this, TranscribeService::class.java).setAction(TranscribeService.ACTION_STOP)
            )
            return
        }
        val key = KeyCheck.clean(apiKeyInput.text.toString())
        if (key.isEmpty()) {
            toast(R.string.toast_need_key)
            return
        }
        // Show the cleaned key so the field always matches what is sent.
        if (key != apiKeyInput.text.toString()) apiKeyInput.setText(key)
        if (!KeyCheck.looksLikeXaiKey(key)) {
            Toast.makeText(this, R.string.toast_key_format, Toast.LENGTH_LONG).show()
        }
        prefs.edit()
            .putString("api_key", key)
            .putInt("source", sourceSpinner.selectedItemPosition)
            .putInt("language", languageSpinner.selectedItemPosition)
            .apply()

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { !hasPermission(it) }
        if (missing.isEmpty()) proceed() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun proceed() {
        if (sourceSpinner.selectedItemPosition == TranscribeService.SOURCE_MIC) {
            launchService(TranscribeService.SOURCE_MIC, 0, null)
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun launchService(source: Int, resultCode: Int, projectionData: Intent?) {
        val intent = Intent(this, TranscribeService::class.java)
            .setAction(TranscribeService.ACTION_START)
            .putExtra(TranscribeService.EXTRA_API_KEY, KeyCheck.clean(apiKeyInput.text.toString()))
            .putExtra(TranscribeService.EXTRA_SOURCE, source)
            .putExtra(TranscribeService.EXTRA_LANGUAGE, languageCodes[languageSpinner.selectedItemPosition])
            .putExtra(TranscribeService.EXTRA_RESULT_CODE, resultCode)
        if (projectionData != null) {
            intent.putExtra(TranscribeService.EXTRA_PROJECTION_DATA, projectionData)
        }
        TranscriptBus.setState(true, "Starting…")
        ContextCompat.startForegroundService(this, intent)
    }

    // ------------------------------------------------------------ copy / share

    private fun copyTranscript() {
        val text = TranscriptBus.plainText()
        if (text.isEmpty()) {
            toast(R.string.toast_empty)
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
        toast(R.string.toast_copied)
    }

    private fun shareTranscript() {
        val text = TranscriptBus.plainText()
        if (text.isEmpty()) {
            toast(R.string.toast_empty)
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    // ----------------------------------------------------------------- helpers

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
