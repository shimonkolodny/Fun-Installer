package com.funinstaller.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface JobState {
    data object Idle : JobState
    data class Planning(val request: String, val model: String) : JobState
    data class Review(val request: String, val plan: Plan, val cost: String) : JobState
    data class Running(val title: String, val status: String, val cost: String) : JobState
    data class Done(val success: Boolean, val title: String, val summary: String, val cost: String) : JobState
    data class Error(val message: String) : JobState
}

/** The one job at a time: plan → user reviews → run → done. Shared by the app screen and the overlay. */
object Jobs {
    val state = MutableStateFlow<JobState>(JobState.Idle)
    val log = MutableStateFlow<List<String>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var current: Job? = null
    private var claude: Claude? = null
    private var meter = CostMeter()

    fun addLog(line: String) {
        log.value = (log.value + line).takeLast(300)
    }

    fun plan(context: Context, request: String) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        cancelCurrent()
        meter = CostMeter()
        log.value = emptyList()
        val models = ModelSet.forMode(settings.mode)
        state.value = JobState.Planning(request, Models.displayName(models.planner))
        current = scope.launch {
            try {
                val c = Claude(settings.apiKey).also { claude = it }
                addLog("Planning with ${Models.displayName(models.planner)}: $request")
                val plan = withContext(Dispatchers.IO) {
                    Planner(c, meter, models).plan(request, Planner.deviceInfo(app), Notes.relevant(app, request))
                }
                addLog("Plan ready: ${plan.title} (${meter.format()} so far)")
                state.value = JobState.Review(request, plan, meter.format())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                state.value = JobState.Error(t.message ?: t.toString())
            }
        }
    }

    /** Start the reviewed plan. Returns an error message, or null if it started. */
    fun start(context: Context): String? {
        val review = state.value as? JobState.Review ?: return "There's no plan to start."
        val svc = AgentService.instance
            ?: return "First let Fun Installer control the screen (step 2 at the top)."
        val c = claude ?: return "Please make the plan again."
        val app = context.applicationContext
        val settings = AppSettings(app)
        val models = ModelSet.forMode(settings.mode)
        val ui = OverlayUi(app, svc.overlay, meter) { stop("Stopped.") }
        state.value = JobState.Running(review.plan.title, "Starting…", meter.format())
        current = scope.launch {
            try {
                ui.status("Starting…")
                val agent = Agent(app, c, meter, models, review.plan, ui, settings.budget, settings.budget)
                val outcome = agent.run()
                Notes.save(app, review.request, review.plan, outcome, agent.issues)
                state.value = JobState.Done(outcome.success, review.plan.title, outcome.summary, meter.format())
                ui.finished(outcome.success, outcome.summary)
            } catch (e: CancellationException) {
                val why = e.message ?: "Stopped."
                state.value = JobState.Done(false, review.plan.title, why, meter.format())
                ui.finished(false, why)
            } catch (t: Throwable) {
                val why = t.message ?: t.toString()
                state.value = JobState.Error(why)
                ui.finished(false, why)
            }
        }
        return null
    }

    fun updateStatus(text: String) {
        val s = state.value
        if (s is JobState.Running) state.value = s.copy(status = text, cost = meter.format())
    }

    fun stop(reason: String) {
        current?.cancel(CancellationException(reason))
    }

    fun reset() {
        cancelCurrent()
        state.value = JobState.Idle
    }

    private fun cancelCurrent() {
        current?.cancel()
        current = null
        claude?.let { c -> scope.launch(Dispatchers.IO) { runCatching { c.close() } } }
        claude = null
    }
}

/** Shows the job's progress and questions on the floating card. */
class OverlayUi(
    private val app: Context,
    private val overlay: Overlay,
    private val meter: CostMeter,
    private val onStop: () -> Unit,
) : Agent.JobUi {
    @Volatile
    private var lastStatus = ""
    private val stopButton get() = Overlay.Button("Stop") { onStop() }
    private fun heading() = "Fun Installer · ${meter.format()}"

    override fun status(text: String) {
        lastStatus = text
        overlay.show(heading(), text, listOf(stopButton))
        Jobs.updateStatus(text)
    }

    override suspend fun ask(question: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        Jobs.updateStatus("Waiting for your answer on the floating card")
        overlay.show("Fun Installer asks", question, listOf(
            stopButton,
            Overlay.Button("No") { answer.complete(false) },
            Overlay.Button("Yes") { answer.complete(true) },
        ))
        return try { answer.await() } finally { status(lastStatus) }
    }

    override suspend fun tell(message: String) {
        val done = CompletableDeferred<Unit>()
        Jobs.updateStatus("Waiting for you (see the floating card)")
        overlay.show("Your turn", message, listOf(stopButton, Overlay.Button("Done") { done.complete(Unit) }))
        try { done.await() } finally { status(lastStatus) }
    }

    override fun log(line: String) = Jobs.addLog(line)

    fun finished(success: Boolean, summary: String) {
        overlay.show(
            if (success) "Done ✓ · ${meter.format()}" else "Stopped · ${meter.format()}",
            summary,
            listOf(
                Overlay.Button("Open app") {
                    overlay.hide()
                    app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Overlay.Button("Close") { overlay.hide() },
            ),
        )
    }
}
