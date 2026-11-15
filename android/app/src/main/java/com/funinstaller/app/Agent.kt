package com.funinstaller.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Carries out an approved [Plan] on the tablet, one action per model call.
 * The executor model (Haiku in Saver mode) does the routine steps; when it's
 * stuck, the rescuer model (Sonnet) gets a screenshot and takes over for a turn.
 */
class Agent(
    private val context: Context,
    private val claude: Claude,
    private val meter: CostMeter,
    private val models: ModelSet,
    plan: Plan,
    private val ui: JobUi,
    private var budget: Double,
    private val budgetStep: Double,
) {
    interface JobUi {
        fun status(text: String)
        suspend fun ask(question: String): Boolean
        suspend fun tell(message: String)
        fun log(line: String)
    }

    data class Outcome(val success: Boolean, val summary: String)

    companion object {
        private const val MAX_TURNS = 250
        private const val MAX_RESCUES = 10
        private const val WAIT_LIMIT_MS = 15 * 60 * 1000L
    }

    private val title = plan.title
    private val summary = plan.summary
    private val userMustDo = plan.userMustDo
    private val steps = plan.steps.toMutableList()
    private val stepsDone = sortedSetOf<Int>()
    private val downloads = plan.downloads.toMutableList()
    private val downloaded = mutableMapOf<String, String>() // download id -> file id
    private val files = linkedMapOf<String, JobFile>()
    private val history = mutableListOf<String>()
    private var helperNote = ""
    private var wantShot = false
    private var helpReason = ""
    private var lastSignature = ""
    private var sameScreen = 0
    private var lastActionWasOnScreen = false
    private val onScreenActions = setOf(
        "tap", "long_press", "tap_position", "type_text", "scroll", "press_key", "open_app", "open_link",
    )
    private var failures = 0
    private var noAction = 0
    private var rescues = 0
    private var turn = 0
    val issues = mutableListOf<String>()

    private val service: AgentService
        get() = AgentService.instance ?: throw ClaudeException("Accessibility for Fun Installer was turned off.")

    suspend fun run(): Outcome {
        while (turn < MAX_TURNS) {
            currentCoroutineContext().ensureActive()
            turn++
            overBudget()?.let { return it }

            val svc = service
            svc.settle()
            val screen = svc.readScreen()
            val pkg = screen.activePackage

            // Screens that only the user handles: wait without calling Claude.
            when {
                pkg in Safety.INSTALLER_PACKAGES -> {
                    waitWhile("Your turn: tap Install (or Cancel) on Android's install screen.") { it in Safety.INSTALLER_PACKAGES }
                    continue
                }
                pkg in Safety.PERMISSION_PACKAGES -> {
                    waitWhile("Your turn: choose Allow or Don't allow on the pop-up.") { it in Safety.PERMISSION_PACKAGES }
                    continue
                }
                pkg == context.packageName -> {
                    waitWhile("Paused while the Fun Installer app is open. Leave it to continue.") { it == context.packageName }
                    continue
                }
                screen.blocked -> {
                    ui.tell("Fun Installer can't work inside this app ($pkg). Please leave it, then tap Done.")
                    continue
                }
            }

            // Only taps/typing/scrolling that change nothing count as "stuck";
            // downloads, installs and questions legitimately leave the screen as it was.
            if (screen.signature != lastSignature) sameScreen = 0
            else if (lastActionWasOnScreen) sameScreen++
            lastSignature = screen.signature
            if (sameScreen >= 3 && helpReason.isEmpty()) helpReason = "The screen hasn't changed for 3 turns."
            if (failures >= 2 && helpReason.isEmpty()) helpReason = "The last actions failed."
            if (noAction >= 2 && helpReason.isEmpty()) helpReason = "The assistant didn't choose an action."
            val rescue = helpReason.isNotEmpty()
            if (rescue && ++rescues > MAX_RESCUES) {
                return Outcome(false, "Fun Installer got stuck too many times and stopped. Last problem: $helpReason")
            }

            val shot = if (rescue || wantShot || screen.elements.size < 3) svc.screenshot() else null
            wantShot = false
            val model = if (rescue) models.rescuer else models.executor
            ui.status(if (rescue) "Thinking harder (${Models.displayName(model)})…" else "Looking at the screen…")

            val msg = ask(model, rescue, screen, shot)
            if (Claude.isRefusal(msg)) {
                issues += "Claude declined a step"
                if (rescue) return Outcome(false, "Claude declined to continue this job.")
                helpReason = "The assistant model declined this step."
                continue
            }
            val call = Claude.firstToolUse(msg)
            if (call == null) {
                noAction++
                record(rescue, "(no action)", Claude.textOf(msg).take(200).ifEmpty { "no tool call" })
                continue
            }
            noAction = 0
            val outcome = execute(call, screen, shot, rescue)
            if (outcome != null) return outcome
            if (rescue && call.name() != "need_help") {
                helpReason = ""
                sameScreen = 0
                failures = 0
            }
        }
        return Outcome(false, "Fun Installer stopped after $MAX_TURNS actions without finishing.")
    }

    // ------------------------------------------------------------- asking Claude

    private fun planText(): String = buildString {
        append("GOAL: ").append(title).append('\n')
        append("ABOUT: ").append(summary).append("\n\nPLAN:\n")
        steps.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
        if (userMustDo.isNotEmpty()) {
            append("\nTHE USER DOES THESE THEMSELVES:\n")
            userMustDo.forEach { append("- ").append(it).append('\n') }
        }
        append("\nAPPROVED DOWNLOADS:\n")
        if (downloads.isEmpty()) append("(none)\n")
        downloads.forEach { append(it.id).append(": ").append(it.purpose).append(" (").append(it.fileName).append(")\n") }
    }

    private fun situationText(screen: Screen, rescue: Boolean, shot: AgentService.Shot?): String = buildString {
        val next = (1..steps.size).firstOrNull { it !in stepsDone }
        append("PROGRESS: ")
        append(if (stepsDone.isEmpty()) "no steps finished yet" else "finished steps ${stepsDone.joinToString()}")
        append(if (next != null) "; next step is $next.\n" else "; all steps are marked finished.\n")
        append("\nFILES:\n")
        if (files.isEmpty()) append("(none yet)\n")
        files.values.forEach { f ->
            val from = downloaded.entries.firstOrNull { it.value == f.id }?.key
            append(f.describe()).append(if (from != null) " from $from" else "").append('\n')
        }
        val recent = history.takeLast(if (rescue) 40 else 15)
        append("\nRECENT ACTIONS (oldest first):\n")
        if (recent.isEmpty()) append("(none yet)\n")
        recent.forEach { append(it).append('\n') }
        if (helperNote.isNotEmpty()) append("\nNOTE FROM THE HELPER: ").append(helperNote).append('\n')
        if (rescue) append("\nWHY YOU WERE CALLED: ").append(helpReason).append('\n')
        append("\nCURRENT SCREEN:\n").append(screen.render())
        if (shot != null) append("\nA screenshot of this screen is attached (${shot.width}x${shot.height}).\n")
        append("\nChoose the single next action and call exactly one tool.")
    }

    private suspend fun ask(model: String, rescue: Boolean, screen: Screen, shot: AgentService.Shot?): BetaMessage =
        withContext(Dispatchers.IO) {
            val content = mutableListOf(
                BetaContentBlockParam.ofText(
                    BetaTextBlockParam.builder()
                        .text(planText())
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build(),
                ),
                BetaContentBlockParam.ofText(situationText(screen, rescue, shot)),
            )
            if (shot != null) {
                content += BetaContentBlockParam.ofImage(
                    BetaBase64ImageSource.builder()
                        .data(shot.base64Jpeg)
                        .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
            }
            val earlier = mutableListOf<BetaMessage>()
            var msg: BetaMessage
            var rounds = 0
            do {
                val b = Claude.request(
                    model,
                    if (rescue) Prompts.RESCUER else Prompts.EXECUTOR,
                    if (rescue) BetaOutputConfig.Effort.MEDIUM else BetaOutputConfig.Effort.LOW,
                    if (rescue) 16000 else 4096,
                    withWebTools = rescue,
                )
                if (rescue) {
                    // The helper also gets web search, so its tools must be non-strict (see Tools.forWeb).
                    (Tools.executorForWeb + Tools.forWeb(listOf(Tools.revisePlan))).forEach { b.addTool(it) }
                    b.addTool(BetaWebSearchTool20260209.builder().maxUses(5L).build())
                    b.addTool(BetaWebFetchTool20260209.builder().maxUses(8L).build())
                } else {
                    Tools.executor.forEach { b.addTool(it) }
                }
                b.addUserMessageOfContentBlockParams(content)
                earlier.forEach { b.addMessage(it) }
                msg = claude.send(b.build(), model, meter)
                earlier += msg
            } while (Claude.isPause(msg) && ++rounds < 5)
            msg
        }

    // ---------------------------------------------------------------- acting

    private fun record(rescue: Boolean, action: String, result: String) {
        val line = "#$turn ${if (rescue) "(helper) " else ""}$action -> ${result.take(400)}"
        history += line
        ui.log(line)
    }

    private suspend fun execute(call: BetaToolUseBlock, screen: Screen, shot: AgentService.Shot?, rescue: Boolean): Outcome? {
        val a = Claude.inputOf(call)
        val name = call.name()
        a.str("reason").takeIf { it.isNotBlank() }?.let { ui.status(it) }
        val svc = service
        var action = name
        val result: String = try {
            when (name) {
                "tap", "long_press" -> {
                    val e = screen.element(a.int("element"))
                    action = "$name [${a.int("element")}] \"${e?.name() ?: "?"}\""
                    when {
                        e == null -> "failed: there's no element [${a.int("element")}] on this screen"
                        Safety.looksLikePurchase(e.name()) -> "refused: that looks like a purchase button; only the user may buy things"
                        else -> svc.tapElement(e, name == "long_press")
                    }
                }
                "tap_position" -> {
                    val x = a.num("x_percent").coerceIn(0.0, 100.0)
                    val y = a.num("y_percent").coerceIn(0.0, 100.0)
                    action = "tap_position ${x.toInt()}%,${y.toInt()}%"
                    if (shot == null) "failed: no screenshot was attached; call look_closer first"
                    else {
                        val size = ScreenReader.displaySize(svc)
                        if (svc.tapAt((size.x * x / 100).toInt(), (size.y * y / 100).toInt())) "ok" else "failed"
                    }
                }
                "type_text" -> {
                    val idx = a.int("element")
                    val e = if (idx < 0) null else screen.element(idx)
                    action = "type_text into ${if (idx < 0) "focused field" else "[$idx]"}"
                    if (idx >= 0 && e == null) "failed: there's no element [$idx] on this screen"
                    else {
                        val r = svc.typeInto(e, a.str("text"))
                        if (r == "ok" && a.bool("press_enter")) {
                            if (svc.pressEnter(e)) "ok, typed and pressed Enter" else "typed, but Enter didn't work"
                        } else r
                    }
                }
                "scroll" -> {
                    val idx = a.int("element")
                    action = "scroll ${a.str("direction")}" + if (idx >= 0) " in [$idx]" else ""
                    val e = if (idx < 0) null else screen.element(idx)
                    if (idx >= 0 && e == null) "failed: there's no element [$idx]" else svc.scroll(a.str("direction"), e)
                }
                "press_key" -> { action = "press ${a.str("key")}"; svc.pressKey(a.str("key")) }
                "open_app" -> { action = "open_app ${a.str("package")}"; svc.openApp(a.str("package").trim()) }
                "open_link" -> { action = "open_link ${a.str("url")}"; svc.openLink(a.str("url").trim()) }
                "wait" -> {
                    val s = a.int("seconds", 3).coerceIn(1, 60)
                    action = "wait ${s}s"
                    delay(s * 1000L)
                    "ok"
                }
                "look_closer" -> { wantShot = true; "ok, a screenshot comes with the next screen" }
                "download" -> { action = "download ${a.str("download_id")}"; download(a.str("download_id").trim()) }
                "install" -> { action = "install ${a.str("file_id")}"; install(a.str("file_id").trim()) }
                "unpack" -> { action = "unpack ${a.str("file_id")}"; unpack(a.str("file_id").trim()) }
                "save_to_downloads" -> { action = "save_to_downloads ${a.str("file_id")}"; save(a.str("file_id").trim()) }
                "step_done" -> {
                    val n = a.int("step")
                    action = "step_done $n (${a.str("note")})"
                    if (n in 1..steps.size) { stepsDone += n; "ok" } else "failed: there's no step $n"
                }
                "ask_user" -> {
                    action = "ask_user \"${a.str("question")}\""
                    if (ui.ask(a.str("question"))) "the user answered YES" else "the user answered NO"
                }
                "tell_user" -> {
                    action = "tell_user \"${a.str("message")}\""
                    ui.tell(a.str("message"))
                    "the user tapped Done"
                }
                "need_help" -> {
                    action = "need_help \"${a.str("problem")}\""
                    helpReason = a.str("problem").ifBlank { "The assistant asked for help." }
                    issues += helpReason
                    "ok, the helper looks next"
                }
                "revise_plan" -> { action = "revise_plan"; revise(a) }
                "finish" -> {
                    record(rescue, "finish", a.str("summary"))
                    return Outcome(a.bool("success"), a.str("summary").ifBlank { "Finished." })
                }
                else -> "failed: unknown action $name"
            }
        } catch (e: ClaudeException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "failed: ${e.message ?: e.javaClass.simpleName}"
        }
        record(rescue, action, result)
        failures = if (result.startsWith("failed") || result.startsWith("refused")) failures + 1 else 0
        lastActionWasOnScreen = name in onScreenActions
        return null
    }

    private suspend fun download(id: String): String {
        val d = downloads.firstOrNull { it.id == id } ?: return "failed: $id isn't one of the approved downloads"
        downloaded[id]?.let { return "already downloaded as $it" }
        ui.status("Downloading ${d.fileName}…")
        val file = withContext(Dispatchers.IO) {
            Files.download(context, d.url, d.fileName) { p -> ui.status("Downloading ${d.fileName}: $p") }
        }
        val type = withContext(Dispatchers.IO) { Files.sniff(file) }
        val sha = withContext(Dispatchers.IO) { Files.sha256(file) }
        if (d.sha256.isNotBlank() && !d.sha256.equals(sha, ignoreCase = true)) {
            file.delete()
            return "failed: the file's SHA-256 doesn't match the one the project published, so it was deleted"
        }
        val fid = "f${files.size + 1}"
        files[fid] = JobFile(fid, file, type, sha)
        downloaded[id] = fid
        val warning = if (type.startsWith("WEB PAGE")) " WARNING: that's a web page, not the file, so the link is wrong; call need_help." else ""
        return "ok: saved as $fid (${file.name}, $type, ${Files.human(file.length())}).$warning"
    }

    private suspend fun install(fid: String): String {
        val f = files[fid] ?: return "failed: there's no file $fid"
        if (!f.type.startsWith("APK")) return "failed: $fid is a ${f.type}, not an APK"
        if (!Installer.canInstall(context)) {
            if (!ui.ask("Android needs your OK before Fun Installer can install apps. Open that setting now?")) {
                return "failed: the user didn't allow installing apps"
            }
            service.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            ui.tell("Turn on \"Allow from this source\" for Fun Installer, then tap Done.")
            if (!Installer.canInstall(context)) return "failed: installing apps is still not allowed"
        }
        val apks = withContext(Dispatchers.IO) { Files.apksIn(context, f.file) }
        ui.status("Installing ${f.file.name}. Tap Install when Android asks.")
        val r = Installer.install(context, apks)
        if (r.startsWith("ok")) f.installed = true
        return r
    }

    private suspend fun unpack(fid: String): String {
        val f = files[fid] ?: return "failed: there's no file $fid"
        if (!f.type.startsWith("zip") && !f.type.startsWith("APK bundle")) return "failed: $fid is a ${f.type}, not a zip"
        ui.status("Extracting ${f.file.name}…")
        return withContext(Dispatchers.IO) { Files.unpack(context, f.file) { p -> ui.status("Extracting: $p") } }
    }

    private suspend fun save(fid: String): String {
        val f = files[fid] ?: return "failed: there's no file $fid"
        val path = withContext(Dispatchers.IO) { Files.saveToDownloads(context, f.file) }
        return "ok: copied to $path"
    }

    private suspend fun revise(a: Map<String, Any?>): String {
        val remaining = a.strList("remaining_steps").filter { it.isNotBlank() }
        val extra = Plan.downloadsFrom(a.objList("new_downloads"), downloads.size + 1)
        if (extra.isNotEmpty()) {
            val list = extra.joinToString("\n") { "• ${it.purpose}\n  ${it.url}" }
            if (!ui.ask("Claude wants to change the plan and also download:\n$list\n\nAllow?")) {
                return "failed: the user didn't approve the extra downloads, so the plan wasn't changed"
            }
            downloads += extra
        }
        val firstOpen = (1..steps.size).firstOrNull { it !in stepsDone } ?: (steps.size + 1)
        val kept = steps.take(firstOpen - 1)
        steps.clear()
        steps += kept
        steps += remaining
        stepsDone.removeAll { it > kept.size }
        helperNote = a.str("note")
        return "ok: plan updated; steps ${kept.size + 1}-${steps.size} are new" +
            if (extra.isNotEmpty()) "; new approved downloads ${extra.joinToString { it.id }}" else ""
    }

    private suspend fun waitWhile(message: String, condition: (String) -> Boolean) {
        ui.status(message)
        val start = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val pkg = service.rootInActiveWindow?.packageName?.toString() ?: ""
            if (!condition(pkg)) break
            if (System.currentTimeMillis() - start > WAIT_LIMIT_MS) {
                throw ClaudeException("Waited 15 minutes for you, so the job stopped.")
            }
        }
        record(false, "(waited for the user)", message)
    }

    private suspend fun overBudget(): Outcome? {
        if (meter.dollars <= budget) return null
        val keepGoing = ui.ask(
            "This job has cost about ${meter.format()} so far, over your limit of $" +
                String.format("%.2f", budget) + ". Keep going for up to $" + String.format("%.2f", budgetStep) + " more?",
        )
        if (keepGoing) {
            budget += budgetStep
            return null
        }
        return Outcome(false, "Stopped at your spending limit (${meter.format()} used).")
    }
}
