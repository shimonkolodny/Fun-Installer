package com.funinstaller.app

import com.anthropic.models.beta.messages.BetaTool

/** The actions Claude can take. Each call is checked and carried out by [Agent]. */
object Tools {
    private fun str(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun int(desc: String) = mapOf("type" to "integer", "description" to desc)
    private fun num(desc: String) = mapOf("type" to "number", "description" to desc)
    private fun bool(desc: String) = mapOf("type" to "boolean", "description" to desc)
    private fun choice(desc: String, vararg values: String) =
        mapOf("type" to "string", "enum" to values.toList(), "description" to desc)
    private fun strList(desc: String) = mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to desc)

    private val reason = str("A 3-6 word status shown to the user, e.g. 'Opening emulator settings'.")
    private val element = int("The element's number from the CURRENT SCREEN.")

    private val downloadItem = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "url" to str("Direct https link to the file itself (not a web page)."),
            "file_name" to str("File name to save as, e.g. 'ppsspp-1.19-arm64.apk'."),
            "purpose" to str("What it is, e.g. 'PPSSPP app (official GitHub release)'."),
            "sha256" to str("SHA-256 published by the project, or empty if none."),
        ),
        "required" to listOf("url", "file_name", "purpose", "sha256"),
        "additionalProperties" to false,
    )
    private fun downloadList(desc: String) = mapOf("type" to "array", "items" to downloadItem, "description" to desc)

    val executor: List<BetaTool> = listOf(
        Claude.tool("tap", "Tap an element from the CURRENT SCREEN.",
            mapOf("element" to element, "reason" to reason)),
        Claude.tool("tap_position", "Tap a spot on the attached screenshot, given as percentages of its width and height. Only use this when a screenshot is attached and the element list can't be used.",
            mapOf("x_percent" to num("0-100 from the left edge."), "y_percent" to num("0-100 from the top edge."), "reason" to reason)),
        Claude.tool("long_press", "Press and hold an element.",
            mapOf("element" to element, "reason" to reason)),
        Claude.tool("type_text", "Replace the text in a text field. Tap the field first if it isn't focused.",
            mapOf("element" to int("The text field's number, or -1 for the focused field."),
                "text" to str("The text to enter."), "press_enter" to bool("Press Enter/Go/Search afterwards."), "reason" to reason)),
        Claude.tool("scroll", "Scroll to reveal more content in a direction.",
            mapOf("direction" to choice("Which way to reveal more content.", "down", "up", "left", "right"),
                "element" to int("Number of the list to scroll inside, or -1 for the whole screen."), "reason" to reason)),
        Claude.tool("press_key", "Press a system key.",
            mapOf("key" to choice("The key.", "back", "home", "recents", "enter"), "reason" to reason)),
        Claude.tool("open_app", "Open an installed app by its package name.",
            mapOf("package" to str("Package name, e.g. 'org.ppsspp.ppsspp'."), "reason" to reason)),
        Claude.tool("open_link", "Open a web link in the browser, or a market://details?id=... link in the Play Store.",
            mapOf("url" to str("The link."), "reason" to reason)),
        Claude.tool("wait", "Wait for something to finish loading.",
            mapOf("seconds" to int("1 to 60."), "reason" to reason)),
        Claude.tool("look_closer", "Get a screenshot with the next screen, for games, images or screens the element list doesn't describe well.",
            mapOf("reason" to reason)),
        Claude.tool("download", "Download one of the APPROVED DOWNLOADS. Afterwards it appears in FILES.",
            mapOf("download_id" to str("Id from APPROVED DOWNLOADS, e.g. 'd1'."), "reason" to reason)),
        Claude.tool("install", "Install a downloaded APK or APK bundle from FILES. Android then asks the user to confirm.",
            mapOf("file_id" to str("Id from FILES, e.g. 'f1'."), "reason" to reason)),
        Claude.tool("unpack", "Extract a downloaded .zip from FILES into /sdcard/Download/FunInstaller/<name>/ and list its contents.",
            mapOf("file_id" to str("Id from FILES."), "reason" to reason)),
        Claude.tool("save_to_downloads", "Copy a downloaded file from FILES, unchanged, into /sdcard/Download/FunInstaller/ so other apps can open it.",
            mapOf("file_id" to str("Id from FILES."), "reason" to reason)),
        Claude.tool("step_done", "Mark a plan step as finished.",
            mapOf("step" to int("The step's number in the PLAN."), "note" to str("What was done, in a few words."))),
        Claude.tool("ask_user", "Ask the user a yes/no question and wait for the answer. Required before changing system settings or special access.",
            mapOf("question" to str("A short, clear yes/no question."))),
        Claude.tool("tell_user", "Ask the user to do something only they should do (sign in, provide their own files, pay, choose something personal), and wait until they tap Done.",
            mapOf("message" to str("Exactly what they need to do."))),
        Claude.tool("need_help", "Ask the senior helper to take over for a moment when you're stuck, unsure, or the screen doesn't match the plan.",
            mapOf("problem" to str("What's wrong, in one or two sentences."))),
        Claude.tool("finish", "End the job. Only call with success=true after you have seen on screen that the goal was reached.",
            mapOf("success" to bool("Whether the goal was reached."), "summary" to str("2-4 sentences for the user: what was installed or set up, and anything left for them to do."))),
    )

    /** The same tools without strict mode, for requests that also carry web search/fetch. */
    fun forWeb(tools: List<BetaTool>): List<BetaTool> = tools.map { it.toBuilder().strict(false).build() }

    val executorForWeb: List<BetaTool> by lazy { forWeb(executor) }

    val revisePlan: BetaTool = Claude.tool(
        "revise_plan",
        "Replace the remaining (not yet finished) plan steps, for example when the plan turned out to be wrong. New downloads must be from official sources; the user is asked to approve them.",
        mapOf(
            "remaining_steps" to strList("The new steps still to do, in order."),
            "new_downloads" to downloadList("Extra downloads needed, or an empty list."),
            "note" to str("Why the plan changed, for the user."),
        ),
    )

    val submitPlan: BetaTool = Claude.tool(
        "submit_plan",
        "Submit the finished plan. Call this exactly once, after your research.",
        mapOf(
            "title" to str("Short title, e.g. 'Install PPSSPP and set the games folder'."),
            "summary" to str("2-4 plain sentences for the user: what will happen and where things come from."),
            "can_do" to bool("False if the request can't or mustn't be done (explain in summary)."),
            "app_package" to str("Package name of the main app involved, or empty."),
            "downloads" to downloadList("Files to download, from official sources only. Empty if none (e.g. Play Store installs)."),
            "steps" to strList("Concrete numbered steps for the tablet assistant, naming the buttons and screens to look for."),
            "user_must_do" to strList("Things only the user can do (their own game files or BIOS, signing in). Empty if none."),
            "warnings" to strList("Anything the user should know first. Empty if none."),
        ),
    )
}
