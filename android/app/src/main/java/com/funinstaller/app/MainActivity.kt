package com.funinstaller.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The app screen: one-time setup, "what should I install?", plan review and progress. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var settings: AppSettings
    private lateinit var setupBox: LinearLayout
    private lateinit var requestBox: LinearLayout
    private lateinit var requestInput: EditText
    private lateinit var jobBox: LinearLayout
    private lateinit var logText: TextView
    private val accent = Color.rgb(109, 74, 255)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        column.addView(text("Fun Installer", 28f, bold = true))
        column.addView(text("Tell it what to install. It finds the official download, installs it, and taps through the setup for you.", 15f, dim = true).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        setupBox = card()
        column.addView(setupBox)

        requestBox = card()
        requestInput = EditText(this).apply {
            hint = "e.g. Install the PPSSPP emulator and set my games folder to Download/Games"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        requestBox.addView(text("What should I install or set up?", 17f, bold = true))
        requestBox.addView(requestInput)
        val examples = listOf(
            "Install the PPSSPP emulator and set my games folder to Download/Games",
            "Install RetroArch from its official website and set up the main menu",
            "Install Dolphin emulator and turn on the recommended graphics settings",
        )
        requestBox.addView(text("Examples (tap one):", 13f, dim = true).apply { setPadding(0, dp(6), 0, 0) })
        examples.forEach { ex ->
            requestBox.addView(text("• $ex", 14f).apply {
                setTextColor(accent)
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { requestInput.setText(ex) }
            })
        }
        requestBox.addView(button("Make a plan", primary = true) { makePlan() })
        column.addView(requestBox)

        jobBox = card()
        column.addView(jobBox)

        column.addView(settingsCard())

        logText = text("", 12f, dim = true).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }
        column.addView(button("Show details") {
            logText.visibility = if (logText.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        column.addView(logText)

        setContentView(ScrollView(this).apply { addView(column) })

        scope.launch { Jobs.state.collect { renderJob(it) } }
        scope.launch { Jobs.log.collect { lines -> logText.text = lines.joinToString("\n").ifEmpty { "(nothing yet)" } } }
    }

    override fun onResume() {
        super.onResume()
        renderSetup()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup

    private fun renderSetup() {
        setupBox.removeAllViews()
        val hasKey = settings.apiKey.isNotBlank()
        val hasAccess = AgentService.isEnabled(this)
        val canInstall = Installer.canInstall(this)
        if (hasKey && hasAccess && canInstall) {
            setupBox.visibility = View.GONE
            return
        }
        setupBox.visibility = View.VISIBLE
        setupBox.addView(text("Setup (one time)", 17f, bold = true))

        setupBox.addView(step(hasKey, "1. Add your Claude API key",
            "Create one at console.anthropic.com → API keys. It's stored only on this tablet."))
        if (!hasKey) setupBox.addView(button("Add API key", primary = true) { askForKey() })

        setupBox.addView(step(hasAccess, "2. Let Fun Installer control the screen",
            "In Accessibility, open Fun Installer (it may be under \"Downloaded apps\" or \"Installed apps\") and turn it on. " +
                "If the switch is greyed out, open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then try again."))
        if (!hasAccess) {
            setupBox.addView(button("Open Accessibility settings", primary = hasKey) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            setupBox.addView(button("Open App info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        setupBox.addView(step(canInstall, "3. Allow installing apps",
            "Turn on \"Allow from this source\". Android will still ask you before each install."))
        if (!canInstall) setupBox.addView(button("Open setting") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
    }

    private fun step(done: Boolean, title: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(2))
        addView(text((if (done) "✓ " else "○ ") + title, 15f, bold = true).apply {
            if (done) setTextColor(Color.rgb(46, 160, 67))
        })
        if (!done) addView(text(detail, 13f, dim = true))
    }

    private fun askForKey() {
        val input = EditText(this).apply {
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKey)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text("Paste your key from console.anthropic.com → API keys. Usage is billed to your Anthropic account.", 14f))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Claude API key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                settings.apiKey = input.text.toString()
                renderSetup()
            }
            .setNeutralButton("Get a key") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -------------------------------------------------------------------- job

    private fun makePlan() {
        val request = requestInput.text.toString().trim()
        when {
            request.isEmpty() -> toast("Type what you'd like to install first.")
            settings.apiKey.isBlank() -> askForKey()
            else -> Jobs.plan(this, request)
        }
    }

    private fun renderJob(s: JobState) {
        jobBox.removeAllViews()
        requestBox.visibility = if (s is JobState.Idle || s is JobState.Error || s is JobState.Done) View.VISIBLE else View.GONE
        jobBox.visibility = if (s is JobState.Idle) View.GONE else View.VISIBLE
        when (s) {
            is JobState.Idle -> {}
            is JobState.Planning -> {
                jobBox.addView(text("Researching and planning…", 17f, bold = true))
                jobBox.addView(text("${s.model} is searching for the official download and the right steps. This usually takes under a minute.", 14f, dim = true))
                jobBox.addView(button("Cancel") { Jobs.reset() })
            }
            is JobState.Review -> {
                jobBox.addView(text(s.plan.title, 18f, bold = true))
                jobBox.addView(text(s.plan.forUser(), 14f).apply { setPadding(0, dp(6), 0, dp(6)) })
                jobBox.addView(text("Planning cost about ${s.cost}.", 12f, dim = true))
                if (s.plan.canDo) {
                    jobBox.addView(text("When you tap Start, Fun Installer leaves this screen and works in the other apps. Watch the floating card; tap Stop on it any time.", 13f, dim = true).apply {
                        setPadding(0, dp(8), 0, 0)
                    })
                    jobBox.addView(button("Start", primary = true) {
                        val error = Jobs.start(this)
                        if (error != null) toast(error) else moveTaskToBack(true)
                    })
                    jobBox.addView(button("Cancel") { Jobs.reset() })
                } else {
                    jobBox.addView(button("OK") { Jobs.reset() })
                }
            }
            is JobState.Running -> {
                jobBox.addView(text("Working: ${s.title}", 17f, bold = true))
                jobBox.addView(text(s.status, 14f))
                jobBox.addView(text("Cost so far: ${s.cost}", 12f, dim = true))
                jobBox.addView(button("Stop", primary = true) { Jobs.stop("Stopped.") })
            }
            is JobState.Done -> {
                jobBox.addView(text((if (s.success) "✓ " else "") + s.title, 17f, bold = true))
                jobBox.addView(text(s.summary, 14f).apply { setPadding(0, dp(6), 0, dp(6)) })
                jobBox.addView(text("Total cost about ${s.cost}.", 12f, dim = true))
                jobBox.addView(button("New job") { Jobs.reset(); requestInput.setText("") })
            }
            is JobState.Error -> {
                jobBox.addView(text("Something went wrong", 17f, bold = true))
                jobBox.addView(text(s.message, 14f))
                jobBox.addView(button("OK") { Jobs.reset() })
            }
        }
    }

    // --------------------------------------------------------------- settings

    private fun settingsCard(): LinearLayout = card().apply {
        addView(text("Settings", 17f, bold = true))
        addView(text("Which Claude models to use", 14f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        val group = RadioGroup(this@MainActivity)
        val saver = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "Saver (recommended): Sonnet 5.5 plans, Haiku 5.5 does the tapping, Sonnet steps in when it's stuck. Usually a few cents to well under a dollar per job."
        }
        val best = RadioButton(this@MainActivity).apply {
            id = View.generateViewId()
            text = "Best quality: Opus 5.5 plans and helps, Sonnet 5.5 does the tapping. Several times the cost."
        }
        group.addView(saver)
        group.addView(best)
        group.check(if (settings.mode == "best") best.id else saver.id)
        group.setOnCheckedChangeListener { _, checked -> settings.mode = if (checked == best.id) "best" else "saver" }
        addView(group)

        addView(text("Spending limit per job (US$)", 14f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        addView(text("Fun Installer asks before going over this.", 13f, dim = true))
        val budget = EditText(this@MainActivity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format("%.2f", settings.budget))
            setOnFocusChangeListener { _, focused ->
                if (!focused) text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let { settings.budget = it }
            }
        }
        addView(budget)
        addView(button("Change API key") { askForKey() })
    }

    // --------------------------------------------------------------- helpers

    private fun text(s: String, size: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (dim) alpha = 0.7f
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        if (primary) {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(accent) }
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        gravity = Gravity.START
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(28, 128, 128, 160))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
