package com.jarvis.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AgentService : AccessibilityService() {

    companion object {
        var instance: AgentService? = null
        var onStatus: ((String) -> Unit)? = null
        @Volatile var stopFlag = false
        @Volatile var running = false
        const val MAX_STEPS = 20

        const val SYSTEM = """You control an Android phone through an accessibility service. 
You get the user's goal, your previous actions, and the current screen as a numbered list of elements.
Reply with ONLY one JSON object, no other text. Allowed actions:
{"action":"open_app","name":"YouTube"}
{"action":"tap","id":5}
{"action":"type","id":3,"text":"hello"}
{"action":"enter"}            (press the keyboard search/enter key on the focused field)
{"action":"scroll","direction":"down"}   (down or up)
{"action":"back"}
{"action":"home"}
{"action":"wait"}
{"action":"done","message":"short summary"}
Rules: one action per reply. Use element ids from the CURRENT screen only. 
Example for playing a song on YouTube: open_app YouTube, tap search icon, type the song name, enter (or tap the suggestion), tap the first video result, then done.
If the goal is complete, reply with done. If you are stuck, reply with done and explain."""
    }

    private val main = Handler(Looper.getMainLooper())
    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun status(msg: String) { main.post { onStatus?.invoke(msg) } }

    fun startTask(goal: String, key: String, baseUrl: String, model: String) {
        if (running) { status("Pehle se chal raha hai"); return }
        running = true
        Thread { runLoop(goal, key, baseUrl, model) }.start()
    }

    private fun runLoop(goal: String, key: String, baseUrl: String, model: String) {
        val history = ArrayList<String>()
        try {
            for (step in 1..MAX_STEPS) {
                if (stopFlag) { status("Stopped"); return }
                val screen = readScreen()
                val prompt = "Goal: $goal\n\nPrevious actions:\n" +
                    (if (history.isEmpty()) "(none)" else history.takeLast(8).joinToString("\n")) +
                    "\n\nCurrent screen:\n$screen"
                val reply = askLLM(key, baseUrl, model, prompt)
                val action = parseAction(reply)
                status("Step $step: $action")
                if (action.optString("action") == "done") {
                    status("Done: " + action.optString("message"))
                    return
                }
                val result = perform(action)
                history.add("$action -> $result")
                Thread.sleep(1500)
            }
            status("Max steps reached")
        } catch (e: Exception) {
            status("Error: ${e.message}")
        } finally {
            running = false
        }
    }

    // ---------- LLM API (OpenAI-compatible, or Anthropic if URL has anthropic.com) ----------
    private fun askLLM(key: String, baseUrl: String, model: String, user: String): String {
        val anthropic = baseUrl.contains("anthropic.com")
        val base = baseUrl.trim().trimEnd('/')
        val url = if (anthropic) "$base/v1/messages" else "$base/chat/completions"
        val body = JSONObject().put("model", model)
        if (anthropic) {
            body.put("max_tokens", 300).put("system", SYSTEM)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        } else {
            body.put("max_tokens", 1000).put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        if (anthropic) {
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
        } else {
            c.setRequestProperty("Authorization", "Bearer $key")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw Exception("HTTP $code: ${text.take(250)}")
        val j = JSONObject(text)
        return if (anthropic) j.getJSONArray("content").getJSONObject(0).getString("text")
        else j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun parseAction(reply: String): JSONObject {
        val s = reply.indexOf('{')
        val e = reply.lastIndexOf('}')
        if (s < 0 || e < s) throw Exception("Bad reply: ${reply.take(100)}")
        return JSONObject(reply.substring(s, e + 1))
    }

    // ---------- Screen reading ----------
    private fun readScreen(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(screen not readable)"
        val sb = StringBuilder("App: ${root.packageName}\n")
        walk(root, sb)
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || nodes.size >= 120) return
        if (n.isVisibleToUser) {
            val t = n.text?.toString()?.take(60) ?: ""
            val d = n.contentDescription?.toString()?.take(60) ?: ""
            if (t.isNotEmpty() || d.isNotEmpty() || n.isClickable || n.isEditable) {
                val id = nodes.size
                nodes[id] = n
                sb.append("[$id] ${n.className?.toString()?.substringAfterLast('.')}")
                if (t.isNotEmpty()) sb.append(" text=\"$t\"")
                if (d.isNotEmpty()) sb.append(" desc=\"$d\"")
                if (n.isClickable) sb.append(" click")
                if (n.isEditable) sb.append(" edit")
                if (n.isScrollable) sb.append(" scroll")
                sb.append("\n")
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb)
    }

    // ---------- Actions ----------
    private fun perform(a: JSONObject): String {
        return when (a.optString("action")) {
            "open_app" -> openApp(a.optString("name"))
            "tap" -> {
                val n = nodes[a.optInt("id", -1)] ?: return "no such id"
                clickNode(n)
            }
            "type" -> {
                val n = nodes[a.optInt("id", -1)] ?: return "no such id"
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text"))
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(400)
                if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok" else "set_text failed"
            }
            "enter" -> {
                val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null && Build.VERSION.SDK_INT >= 30) {
                    if (f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "ok" else "enter failed"
                } else "no focused input; tap a suggestion instead"
            }
            "scroll" -> {
                val m = resources.displayMetrics
                val x = m.widthPixels / 2f
                val down = a.optString("direction") != "up"
                swipe(x, m.heightPixels * (if (down) 0.7f else 0.3f), m.heightPixels * (if (down) 0.3f else 0.7f))
                "ok"
            }
            "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "ok" }
            "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "ok" }
            "wait" -> { Thread.sleep(2000); "ok" }
            else -> "unknown action"
        }
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val pkg = (list.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: list.firstOrNull { it.loadLabel(pm).toString().contains(name, true) })
            ?.activityInfo?.packageName ?: return "app not found: $name"
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "cannot launch"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        Thread.sleep(2000)
        return "opened $pkg"
    }

    private fun clickNode(node: AccessibilityNodeInfo): String {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        val r = Rect()
        node.getBoundsInScreen(r)
        tapAt(r.exactCenterX(), r.exactCenterY())
        return "ok (gesture)"
    }

    private fun tapAt(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
        dispatchGesture(g, null, null)
    }

    private fun swipe(x: Float, y1: Float, y2: Float) {
        val p = Path()
        p.moveTo(x, y1)
        p.lineTo(x, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 400)).build()
        dispatchGesture(g, null, null)
    }
}
