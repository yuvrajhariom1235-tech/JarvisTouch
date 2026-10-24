package com.jarvis.touch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    companion object {
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        const val GEMINI_MODEL = "gemini-flash-latest"
        const val OR_URL = "https://openrouter.ai/api/v1"
        const val OR_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
        const val GROQ_URL = "https://api.groq.com/openai/v1"
        const val GROQ_MODEL = "llama-3.3-70b-versatile"
    }

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
        key.hint = "API key"
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setText(prefs.getString("key", ""))
        root.addView(key)

        val url = EditText(this)
        url.hint = "API base URL"
        url.setText(prefs.getString("url", GEMINI_URL))
        root.addView(url)

        val model = EditText(this)
        model.hint = "Model name"
        model.setText(prefs.getString("model", GEMINI_MODEL))
        root.addView(model)

        btn("Preset: Gemini (free)") { url.setText(GEMINI_URL); model.setText(GEMINI_MODEL) }
        btn("Preset: OpenRouter") { url.setText(OR_URL); model.setText(OR_MODEL) }
        btn("Preset: Groq") { url.setText(GROQ_URL); model.setText(GROQ_MODEL) }

        btn("2. Key + settings save karo") {
            prefs.edit()
                .putString("key", key.text.toString().trim())
                .putString("url", url.text.toString().trim())
                .putString("model", model.text.toString().trim())
                .apply()
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

        btn("Run") {
            runTask(
                prefs.getString("key", "") ?: "",
                prefs.getString("url", GEMINI_URL) ?: GEMINI_URL,
                prefs.getString("model", GEMINI_MODEL) ?: GEMINI_MODEL
            )
        }
        btn("Stop") { AgentService.instance?.stopTask() }

        btn("App update check karo") { checkUpdate(true) }

        status = TextView(this)
        status.text = "Ready"
        status.textSize = 15f
        root.addView(status)
        checkUpdate(false)
    }

    private fun checkUpdate(manual: Boolean) {
        Thread {
            try {
                val info = Updater.latest()
                runOnUiThread {
                    when {
                        info == null -> if (manual) status.text = "Update info nahi mili (repo public hai?)"
                        info.build > Updater.currentBuild(this) -> {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Naya update mila")
                                .setMessage("Version ${info.build} available hai. Abhi install karein?")
                                .setPositiveButton("Update") { _, _ -> doUpdate(info) }
                                .setNegativeButton("Baad me", null)
                                .show()
                        }
                        else -> if (manual) status.text = "App up to date (build ${Updater.currentBuild(this)})"
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { status.text = "Update check error: ${e.message}" }
            }
        }.start()
    }

    private fun doUpdate(info: Updater.Info) {
        status.text = "Download ho raha hai..."
        Thread {
            try {
                val f = Updater.download(this, info.url)
                runOnUiThread {
                    if (!Updater.install(this, f)) {
                        status.text = "Allow karo 'Install unknown apps', phir wapas aake Update dabao"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Download error: ${e.message}" }
            }
        }.start()
    }

    private fun runTask(key: String, url: String, model: String) {
        val goal = cmd.text.toString().trim()
        val svc = AgentService.instance
        when {
            svc == null -> Toast.makeText(this, "Pehle Accessibility permission ON karo", Toast.LENGTH_LONG).show()
            key.isEmpty() -> Toast.makeText(this, "API key save karo", Toast.LENGTH_LONG).show()
            goal.isEmpty() -> Toast.makeText(this, "Command likho ya bolo", Toast.LENGTH_SHORT).show()
            else -> {
                svc.startTask(goal, key, url, model)
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
