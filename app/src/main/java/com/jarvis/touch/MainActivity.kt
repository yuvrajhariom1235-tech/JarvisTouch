package com.jarvis.touch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var cmd: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        fun btn(label: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        val title = TextView(this)
        title.text = "JARVIS Touch"
        title.textSize = 22f
        root.addView(title)

        btn("1. Accessibility permission ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val key = EditText(this)
        key.hint = "Claude API key (sk-ant-...)"
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setText(prefs.getString("key", ""))
        root.addView(key)

        btn("2. Key save karo") {
            prefs.edit().putString("key", key.text.toString().trim()).apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        cmd = EditText(this)
        cmd.hint = "Command, jaise: YouTube pe Kesariya chalao"
        root.addView(cmd)

        btn("Bolo (voice)") {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            try { startActivityForResult(i, 1) }
            catch (e: Exception) { Toast.makeText(this, "Voice input available nahi", Toast.LENGTH_SHORT).show() }
        }

        btn("Run") { runTask(prefs.getString("key", "") ?: "") }
        btn("Stop") { AgentService.stopFlag = true }

        status = TextView(this)
        status.text = "Ready"
        status.textSize = 15f
        root.addView(status)
    }

    private fun runTask(key: String) {
        val goal = cmd.text.toString().trim()
        val svc = AgentService.instance
        when {
            svc == null -> Toast.makeText(this, "Pehle Accessibility permission ON karo", Toast.LENGTH_LONG).show()
            key.isEmpty() -> Toast.makeText(this, "API key save karo", Toast.LENGTH_LONG).show()
            goal.isEmpty() -> Toast.makeText(this, "Command likho ya bolo", Toast.LENGTH_SHORT).show()
            else -> {
                AgentService.stopFlag = false
                svc.startTask(goal, key)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentService.onStatus = { status.text = it }
    }

    override fun onPause() {
        super.onPause()
        AgentService.onStatus = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!r.isNullOrEmpty()) cmd.setText(r[0])
        }
    }
}
