package com.funinstaller.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Download(val id: String, val url: String, val fileName: String, val purpose: String, val sha256: String)

data class Plan(
    val title: String,
    val summary: String,
    val canDo: Boolean,
    val appPackage: String,
    val downloads: List<Download>,
    val steps: List<String>,
    val userMustDo: List<String>,
    val warnings: List<String>,
) {
    companion object {
        fun downloadsFrom(items: List<Map<String, Any?>>, firstId: Int): List<Download> =
            items.mapIndexedNotNull { i, d ->
                val url = d.str("url").trim()
                if (!url.startsWith("https://") && !url.startsWith("http://")) null
                else Download("d${firstId + i}", url, d.str("file_name"), d.str("purpose"), d.str("sha256").trim())
            }

        fun fromToolInput(m: Map<String, Any?>) = Plan(
            title = m.str("title").ifBlank { "Install" },
            summary = m.str("summary"),
            canDo = m.bool("can_do"),
            appPackage = m.str("app_package").trim(),
            downloads = downloadsFrom(m.objList("downloads"), 1),
            steps = m.strList("steps").filter { it.isNotBlank() },
            userMustDo = m.strList("user_must_do").filter { it.isNotBlank() },
            warnings = m.strList("warnings").filter { it.isNotBlank() },
        )
    }

    /** Plain-text version for the user to review. */
    fun forUser(): String = buildString {
        append(summary.trim()).append("\n")
        if (downloads.isNotEmpty()) {
            append("\nDownloads:\n")
            downloads.forEach { append("• ").append(it.purpose).append("\n   ").append(it.url).append("\n") }
        }
        append("\nSteps:\n")
        steps.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append("\n") }
        if (userMustDo.isNotEmpty()) {
            append("\nYou'll need to:\n")
            userMustDo.forEach { append("• ").append(it).append("\n") }
        }
        if (warnings.isNotEmpty()) {
            append("\nGood to know:\n")
            warnings.forEach { append("• ").append(it).append("\n") }
        }
    }.trim()
}

/** Asks the planning model (with web search) to research the request and return a [Plan]. */
class Planner(private val claude: Claude, private val meter: CostMeter, private val models: ModelSet) {

    fun plan(request: String, deviceInfo: String, notes: String): Plan {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val system = Prompts.PLANNER.replace("{DATE}", date)
        val user = buildString {
            append("The user asked: ").append(request.trim()).append("\n\n")
            append("Tablet:\n").append(deviceInfo).append("\n")
            if (notes.isNotBlank()) append("\nNotes from earlier jobs:\n").append(notes).append("\n")
        }
        val history = mutableListOf<Any>(user) // String = user text, BetaMessage = assistant turn
        repeat(8) {
            val b = Claude.request(models.planner, system, BetaOutputConfig.Effort.MEDIUM, 16000, withWebTools = true)
                .addTool(BetaWebSearchTool20260209.builder().maxUses(8L).build())
                .addTool(BetaWebFetchTool20260209.builder().maxUses(12L).build())
            Tools.forWeb(listOf(Tools.submitPlan)).forEach { b.addTool(it) }
            history.forEach { if (it is String) b.addUserMessage(it) else b.addMessage(it as BetaMessage) }
            val msg = claude.send(b.build(), models.planner, meter)
            if (Claude.isRefusal(msg)) {
                throw ClaudeException("Claude declined to plan this request. Try describing it differently.")
            }
            val call = Claude.firstToolUse(msg)
            if (call != null && call.name() == "submit_plan") return Plan.fromToolInput(Claude.inputOf(call))
            history += msg
            if (!Claude.isPause(msg)) history += "Please call submit_plan now with your plan."
        }
        throw ClaudeException("Claude couldn't finish a plan. Try again, or describe what you want more simply.")
    }

    companion object {
        fun deviceInfo(context: Context): String {
            val m = context.resources.displayMetrics
            val apps = context.packageManager.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
                .take(200)
                .joinToString(", ") { "${context.packageManager.getApplicationLabel(it)} (${it.packageName})" }
            return buildString {
                append("- Model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("- Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                append("- CPU types (ABIs), best first: ${Build.SUPPORTED_ABIS.joinToString()}\n")
                append("- Screen: ${m.widthPixels}x${m.heightPixels}\n")
                append("- Apps the user installed: ${apps.ifEmpty { "none" }}\n")
            }
        }
    }
}
