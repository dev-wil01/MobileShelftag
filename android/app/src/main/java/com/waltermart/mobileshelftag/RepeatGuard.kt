package com.waltermart.mobileshelftag

/**
 * Keeps one barcode held in front of the camera from being added again and again.
 * A code is accepted, then ignored for as long as the camera keeps seeing it; it is accepted
 * again only after it has been out of sight for [quietMs] (a new item, or the same item
 * deliberately scanned a second time).
 */
class RepeatGuard(private val quietMs: Long = 1500) {
    private var lastCode: String? = null
    private var lastSeenMs = 0L

    fun accept(code: String, nowMs: Long): Boolean {
        val same = code == lastCode && nowMs - lastSeenMs < quietMs
        lastCode = code
        lastSeenMs = nowMs // every sighting extends the quiet period
        return !same
    }
}
