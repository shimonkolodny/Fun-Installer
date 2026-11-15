package com.funinstaller.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One thing on screen the AI can refer to by number. */
class UiElement(
    val index: Int,
    val cls: String,
    val label: String,
    val hint: String,
    val subs: List<String>,
    val id: String,
    val pkg: String,
    val bounds: Rect,
    val flags: List<String>,
    val node: AccessibilityNodeInfo,
    val windowTitle: String,
) {
    val cx: Int get() = bounds.centerX()
    val cy: Int get() = bounds.centerY()
    val isPassword: Boolean get() = "password" in flags
    val isEditable: Boolean get() = "edit" in flags
    val isScrollable: Boolean get() = "scroll" in flags

    /** The best short name for messages, e.g. "Download". */
    fun name(): String = label.ifEmpty { subs.joinToString(" / ").ifEmpty { cls } }

    fun render(): String = buildString {
        append("[").append(index).append("] ").append(cls.ifEmpty { "View" })
        if (label.isNotEmpty()) append(" \"").append(label).append('"')
        if (hint.isNotEmpty()) append(" hint=\"").append(hint).append('"')
        if (subs.isNotEmpty()) append(" > ").append(subs.joinToString(" / ") { "\"$it\"" })
        if (id.isNotEmpty()) append(" #").append(id)
        append(" @").append(cx).append(',').append(cy)
        if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
    }
}

/** A readable snapshot of what's on screen. */
class Screen(
    val activePackage: String,
    val elements: List<UiElement>,
    val width: Int,
    val height: Int,
    val notes: List<String>,
) {
    val blocked: Boolean get() = Safety.isBlocked(activePackage)

    fun render(): String = buildString {
        append("Active app: ").append(activePackage.ifEmpty { "unknown" })
        append("   (screen ").append(width).append('x').append(height).append(")\n")
        notes.forEach { append("(").append(it).append(")\n") }
        var lastWindow = ""
        val multiWindow = elements.map { it.windowTitle }.distinct().size > 1
        for (e in elements) {
            if (multiWindow && e.windowTitle != lastWindow) {
                append("-- ").append(e.windowTitle).append(" --\n")
                lastWindow = e.windowTitle
            }
            append(e.render()).append('\n')
        }
        if (elements.isEmpty()) {
            append("(no readable elements: probably a game, video, image or custom-drawn screen; use look_closer)\n")
        }
    }

    /** Changes whenever the visible content changes; used to notice when the AI is stuck. */
    val signature: String by lazy {
        activePackage + "|" + elements.joinToString("|") { it.label + it.subs + it.flags }.hashCode()
    }

    fun element(index: Int): UiElement? = elements.getOrNull(index)
}

object ScreenReader {
    private const val MAX_ELEMENTS = 250

    fun displaySize(service: AccessibilityService): Point {
        val dm = service.getSystemService(DisplayManager::class.java)
        val p = Point()
        @Suppress("DEPRECATION")
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        if (p.x == 0) {
            val m = service.resources.displayMetrics
            p.set(m.widthPixels, m.heightPixels)
        }
        return p
    }

    fun read(service: AccessibilityService): Screen {
        val size = displaySize(service)
        val screenRect = Rect(0, 0, size.x, size.y)
        val own = service.packageName
        val notes = mutableListOf<String>()
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        var activePkg = activeRoot?.packageName?.toString() ?: ""

        val windows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }
            .sortedByDescending { it.layer }

        val roots = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        for (w in windows) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == "com.android.systemui" && !w.isActive && !w.isFocused) continue // status/nav bars
            val title = (w.title?.toString()?.takeIf { it.isNotBlank() } ?: pkg) +
                if (w.isActive) " (active)" else ""
            roots += root to title
            if (activePkg.isEmpty() && w.isActive) activePkg = pkg
        }
        if (roots.isEmpty() && activeRoot != null) roots += activeRoot to activePkg

        val out = mutableListOf<UiElement>()
        for ((root, title) in roots) {
            val pkg = root.packageName?.toString() ?: ""
            when {
                pkg == own -> notes += "Fun Installer's own screen is open; open the app you need instead"
                Safety.isBlocked(pkg) -> notes += "$pkg is a private app (banking, passwords, messages); its contents are hidden and Fun Installer must not use it"
                else -> walk(root, false, out, screenRect, title, 0)
            }
            if (out.size >= MAX_ELEMENTS) {
                notes += "list cut off at $MAX_ELEMENTS elements"
                break
            }
        }
        return Screen(activePkg, out, size.x, size.y, notes)
    }

    private fun clean(s: CharSequence?, limit: Int = 100): String {
        val t = s?.toString()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: ""
        return if (t.length <= limit) t else t.take(limit - 1) + "…"
    }

    private fun descendantLabels(node: AccessibilityNodeInfo, exclude: String): List<String> {
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to 1) }
        var visited = 0
        while (queue.isNotEmpty() && found.size < 4 && visited < 60) {
            val (n, depth) = queue.removeFirst()
            visited++
            for (t in listOf(clean(n.text), clean(n.contentDescription))) {
                if (t.isNotEmpty() && t != exclude && t !in found) found += t
            }
            if (depth < 6) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return found.take(4)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        covered: Boolean,
        out: MutableList<UiElement>,
        screenRect: Rect,
        windowTitle: String,
        depth: Int,
    ) {
        if (depth > 70 || out.size >= MAX_ELEMENTS) return
        var nextCovered = covered
        val r = Rect()
        node.getBoundsInScreen(r)
        val onScreen = r.intersect(screenRect) && r.width() > 0 && r.height() > 0
        if (onScreen && node.isVisibleToUser) {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val text = clean(node.text)
            val desc = clean(node.contentDescription)
            val hint = clean(node.hintText)
            var label = text.ifEmpty { desc }
            if (text.isNotEmpty() && desc.isNotEmpty() && desc != text) label = "$text | $desc"
            val clickable = node.isClickable
            val longClick = node.isLongClickable
            val scrollable = node.isScrollable
            val checkable = node.isCheckable
            val editable = node.isEditable || cls.contains("EditText")
            val interactive = clickable || longClick || scrollable || checkable || editable
            val subs = if (interactive && !scrollable) descendantLabels(node, label) else emptyList()
            if (interactive || (label.isNotEmpty() && !covered)) {
                val flags = buildList {
                    if (clickable) add("tap")
                    if (longClick) add("long")
                    if (scrollable) add("scroll")
                    if (editable) add("edit")
                    if (checkable) add(if (node.isChecked) "on" else "off")
                    if (node.isFocused) add("focused")
                    if (node.isSelected) add("selected")
                    if (node.isPassword) add("password")
                    if (!node.isEnabled) add("disabled")
                }
                val rid = node.viewIdResourceName ?: ""
                out += UiElement(
                    index = out.size, cls = cls, label = label,
                    hint = if (hint != label) hint else "", subs = subs,
                    id = rid.substringAfter('/', rid), pkg = node.packageName?.toString() ?: "",
                    bounds = Rect(r), flags = flags, node = node, windowTitle = windowTitle,
                )
            }
            if ((clickable || longClick) && !scrollable && (label.isNotEmpty() || subs.isNotEmpty())) {
                nextCovered = true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, nextCovered, out, screenRect, windowTitle, depth + 1)
        }
    }
}
