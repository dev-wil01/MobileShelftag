package com.waltermart.mobileshelftag

/**
 * Checks the PC address typed in Settings. The app must never talk to "localhost": on the
 * handheld that is the handheld itself, not the Windows PC. There is deliberately NO default
 * address anywhere in the app; it must be entered once (the PC's real LAN address or name).
 */
object HostRules {
    private val allowed = Regex("^[A-Za-z0-9.-]{1,253}$")
    private val loopback = Regex("^127(\\.\\d{1,3}){3}$")

    /** Returns a message for the user, or null when the address is acceptable. */
    fun problem(host: String): String? {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return "Enter the PC address."
        if (!allowed.matches(h)) return "The PC address is not valid."
        if (h == "localhost" || h.endsWith(".localhost") || loopback.matches(h) || h == "0.0.0.0" || h == "10.0.2.2") {
            return "'$host' points to this handheld, not to the PC. Enter the PC's network address (for example 10.202.180.115)."
        }
        return null
    }
}
