package com.funinstaller.app

/**
 * Safety rules enforced in code, whatever the AI decides.
 */
object Safety {
    /** System install confirmation screens: only the user taps these. */
    val INSTALLER_PACKAGES = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
    )

    /** Runtime permission dialogs ("Allow X to access…?"): only the user answers these. */
    val PERMISSION_PACKAGES = setOf(
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /** Apps Fun Installer never reads, taps, types into or opens. */
    private val BLOCKED = listOf(
        // Banking, payments and money
        "*bank*", "*wallet*", "com.paypal.*", "com.venmo", "com.squareup.cash", "com.zellepay.*",
        "com.coinbase.*", "com.binance.*", "com.robinhood.*",
        // Passwords and two-factor codes
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.*",
        "com.x8bit.bitwarden", "com.onepassword.*", "com.lastpass.*", "com.dashlane*",
        "com.agilebits.*", "proton.android.pass",
        // Messages and email
        "com.whatsapp*", "org.telegram.*", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca",
        "com.discord", "com.google.android.gm", "com.microsoft.office.outlook", "ch.protonmail.android",
        // Terminals (could be used to run commands)
        "com.termux", "com.termux.*",
    )

    private val blockedRegexes = BLOCKED.map { glob ->
        Regex("^" + glob.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)
    }

    fun isBlocked(pkg: String?): Boolean =
        !pkg.isNullOrEmpty() && blockedRegexes.any { it.matches(pkg) }

    private val purchaseWords = Regex(
        """(\$|€|£|¥)\s?\d|\b(buy|purchase|subscribe|pay now|place order|checkout)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** True for buttons that spend money; the AI is never allowed to tap these. */
    fun looksLikePurchase(label: String): Boolean = purchaseWords.containsMatchIn(label)

    private val okLinkSchemes = setOf("http", "https", "market")

    fun linkAllowed(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme in okLinkSchemes
    }
}
