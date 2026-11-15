package com.funinstaller.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings stored privately on the tablet. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    /** "saver" (Haiku taps, Sonnet plans) or "best" (Sonnet taps, Opus plans). */
    var mode: String
        get() = prefs.getString("mode", "saver") ?: "saver"
        set(v) = prefs.edit().putString("mode", v).apply()

    /** Spending limit per job in dollars; Fun Installer asks before going over it. */
    var budget: Double
        get() = prefs.getFloat("budget", 1.0f).toDouble()
        set(v) = prefs.edit().putFloat("budget", v.toFloat()).apply()
}

/** What earlier jobs learned, so the planner can reuse it next time. */
object Notes {
    private fun dir(context: Context) = File(context.filesDir, "notes").apply { mkdirs() }

    fun save(context: Context, request: String, plan: Plan, outcome: Agent.Outcome, issues: List<String>) {
        runCatching {
            val o = JSONObject()
                .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                .put("request", request)
                .put("title", plan.title)
                .put("success", outcome.success)
                .put("summary", outcome.summary)
                .put("steps", JSONArray(plan.steps))
                .put("downloads", JSONArray(plan.downloads.map { it.url }))
                .put("problems", JSONArray(issues.take(10)))
            File(dir(context), "${System.currentTimeMillis()}.json").writeText(o.toString(2))
        }
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()

    /** Up to three earlier jobs that look related to [request], as text for the planner. */
    fun relevant(context: Context, request: String): String {
        val want = words(request) - setOf("install", "the", "and", "for", "set", "with", "add", "my", "tablet")
        val notes = dir(context).listFiles().orEmpty()
            .mapNotNull { f -> runCatching { JSONObject(f.readText()) }.getOrNull() }
        return notes
            .map { it to (words(it.optString("request") + " " + it.optString("title")) intersect want).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .joinToString("\n\n") { (o, _) ->
                buildString {
                    append("- ${o.optString("date")}: \"${o.optString("request")}\" → ")
                    append(if (o.optBoolean("success")) "succeeded" else "did not finish").append(". ")
                    append(o.optString("summary").take(600))
                    val problems = o.optJSONArray("problems")
                    if (problems != null && problems.length() > 0) {
                        append(" Problems hit: ")
                        append((0 until problems.length()).joinToString("; ") { problems.optString(it) }.take(500))
                    }
                    val dls = o.optJSONArray("downloads")
                    if (dls != null && dls.length() > 0) {
                        append(" Downloads used: ")
                        append((0 until dls.length()).joinToString(", ") { dls.optString(it) })
                    }
                }
            }
    }
}
